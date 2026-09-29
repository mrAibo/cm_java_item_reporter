package com.mraibo.cminsight.web;

import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.web.http.HttpStatus;
import com.mraibo.cminsight.web.http.JsonWriter;
import com.mraibo.cminsight.web.http.RequestContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The authenticated analytics API: statistics status, the scan action and JDBC diagnostics.
 *
 * <h2>The route table</h2>
 *
 * <pre>
 *   GET  /api/statistics             availability, active repository, scan progress, latest snapshot
 *   POST /api/statistics/refresh     starts at most one scan; requires the action-guard header
 *   GET  /api/diagnostics/jdbc       driver, pool and scan facts, plus a sanitised last JDBC error
 * </pre>
 *
 * <p>Every route is registered through the authenticated {@link Router} methods only, exactly like the CM
 * read API, so an unauthenticated caller learns nothing - not even whether a repository is active.
 *
 * <h2>Installed unconditionally, and that is the point</h2>
 *
 * <p>This tree once shipped a build whose CM routes were implemented, reviewed and never installed, so
 * every CM endpoint answered {@code 404} at runtime while every handler-level test passed. The same trap
 * applies here with a sharper edge: "no JDBC driver is installed" is a NORMAL state of this application
 * (Goal 03 section 3 makes analytics optional to repository activation), and a {@code 404} for that state
 * is indistinguishable from a typo in the client's URL. So the three routes are installed by
 * {@link CmApiRoutes#install(Router)} alongside the CM routes, which {@code Main.serve()} calls before the
 * socket is opened, and they answer with the documented {@link AnalyticsApi.State} rather than by
 * disappearing.
 *
 * <h2>The refresh action guard</h2>
 *
 * <p>{@code POST /api/statistics/refresh} changes local process state, so it requires
 * {@code X-CM-Insight-Action: statistics-refresh} through the same {@link ActionGuard} the repository
 * selection uses. The check runs before the repository is read, before availability is consulted and
 * before anything is started: a request with a missing or wrong header has ZERO side effects - in
 * particular it can never start a scan. The header is the control because a cross-site HTML form cannot
 * set one; the three form-sendable content types are refused as well.
 *
 * <h2>Status codes</h2>
 *
 * <pre>
 *   GET  /api/statistics              200 always - the state is the answer, including "unavailable"
 *   POST /api/statistics/refresh      403 missing/wrong action header (before any state is touched)
 *                                     409 no active repository
 *                                     503 statistics are disabled or unavailable, with the reason
 *                                     409 a scan is already in flight (scan_in_progress)
 *                                     409 the repository context is closing (repository_closing)
 *                                     202 a new scan was started
 *   GET  /api/diagnostics/jdbc        200 always, like /api/diagnostics/cm: "why can this not read"
 *                                     has to be answerable exactly when nothing is active
 * </pre>
 *
 * <p>A statistics route never answers {@code 5xx} because analytics is unavailable: an unavailable
 * analytics capability is a described state, not a server failure. A refused refresh is the documented
 * {@code 403/409/503} set instead, because the client asked for an action that could not be taken.
 *
 * <h2>Value-free payloads</h2>
 *
 * <p>Every string published here passes through {@link DiagnosticText}: the statistics response carries
 * the sanitised availability reason and the sanitised per-ItemType error, and the diagnostics response
 * carries a {@code driver} object whose identity is a validated class name, a URL <em>family prefix</em>
 * with no host in it, a {@code schema} object that reports configured-versus-derived as a label, pool and
 * scan counters, and a last-error object whose components are each validated against the exact shape that
 * slot may have. There is no field anywhere in either payload for the JDBC URL, the database user name, a
 * schema taken from an exception, raw SQL or a raw {@code SQLException} message.
 */
public final class StatisticsApiRoutes {

    /** Path of the statistics status read. */
    public static final String STATISTICS_PATH = "/api/statistics";

    /** Path of the scan action. */
    public static final String REFRESH_PATH = "/api/statistics/refresh";

    /** Path of the JDBC diagnostics read. */
    public static final String JDBC_DIAGNOSTICS_PATH = "/api/diagnostics/jdbc";

    /**
     * 503 for "the feature is off or the database side cannot be used", kept local rather than added to
     * {@link HttpStatus}: it is this route family's "the capability you asked for is not here" answer.
     */
    private static final int STATISTICS_UNAVAILABLE = 503;

    /** 409, the same conflict family the repository routes use for a state that forbids the request. */
    private static final int CONFLICT = 409;

    /**
     * Fixed text for a read that threw. The failure's own message is never used: it comes from the
     * statistics layer and may name a resource, and a response body is not the place to find out whether
     * that text is safe. The type is not published either - the operator gets a state and a next step.
     */
    private static final String READ_FAILED =
            "The analytics capability could not be read; see the server log and GET /api/diagnostics/jdbc";

    private final RepositoryManager repositories;
    private final AnalyticsApi analytics;

    /**
     * @param repositories the manager that owns the single active repository
     * @param analytics    the analytics port; {@code null} is replaced by
     *                     {@link AnalyticsApi#unavailable(String)} so a wiring mistake degrades to the
     *                     documented unavailable state instead of a {@code NullPointerException} in a
     *                     request thread
     */
    public StatisticsApiRoutes(RepositoryManager repositories, AnalyticsApi analytics) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.analytics = analytics == null
                ? AnalyticsApi.unavailable("No analytics capability is wired into this runtime")
                : analytics;
    }

    /** Registers the three routes. All of them are authenticated. */
    public void install(Router router) {
        Objects.requireNonNull(router, "router");
        router.get(STATISTICS_PATH, this::statistics);
        router.post(REFRESH_PATH, this::refresh);
        router.get(JDBC_DIAGNOSTICS_PATH, this::jdbcDiagnostics);
    }

    // ---------------------------------------------------------------- GET /api/statistics

    private void statistics(RequestContext ctx) {
        View view = view();
        RepositoryManager.Status status = repositories.status();
        AnalyticsApi.Snapshot snapshot = view.snapshot().orElse(null);

        ctx.sendJson(HttpStatus.OK, JsonWriter.object(
                "available", view.state() == AnalyticsApi.State.AVAILABLE,
                "state", view.state().name(),
                "reason", view.reason(),
                "repositoryActive", status.usable(),
                "repositoryId", activeRepositoryId(status),
                // Whether a scan is in flight is also inside "scan"; it is mirrored here because it is the
                // one fact a polling client branches on before it decides to parse the rest.
                "refreshing", view.scan().running(),
                // Freshness, reported rather than judged: this goal defines no staleness threshold for a
                // snapshot, so the age is published and the client decides what to do with it.
                "capturedAt", instant(snapshot == null ? null : snapshot.capturedAt()),
                "ageMillis", ageMillis(snapshot == null ? null : snapshot.capturedAt()),
                "scan", JsonWriter.raw(scanJson(view.scan())),
                "snapshot", JsonWriter.raw(snapshotJson(snapshot))));
    }

    // ---------------------------------------------------------------- POST /api/statistics/refresh

    /**
     * Starts one scan, or explains deterministically why it did not.
     *
     * <p>The order is load-bearing and is the whole security property of this route: the action guard
     * first, so a request without the exact header cannot read the repository state, cannot consult
     * availability and cannot start anything. Only then the repository (409 when none is active), then
     * availability (503 with the documented reason), then the single atomic {@code refresh()} call whose
     * own outcome decides between {@code 202}, the {@code 409 scan_in_progress} conflict and the
     * {@code 409 repository_closing} state.
     *
     * <p>There is deliberately no "is a scan running?" pre-check: the decision belongs to the one atomic
     * call, and a pre-check would be a race that could report a conflict for a scan that had just
     * finished, or start a second one for a scan that had just begun.
     */
    private void refresh(RequestContext ctx) {
        if (!ActionGuard.authorises(ctx, ActionGuard.STATISTICS_REFRESH_ACTION)) {
            ctx.sendError(HttpStatus.FORBIDDEN, "action_forbidden",
                    ActionGuard.refusalMessage(ActionGuard.STATISTICS_REFRESH_ACTION));
            return;
        }

        RepositoryManager.Status status = repositories.status();
        if (!status.usable()) {
            ctx.sendError(CONFLICT, "no_active_repository",
                    "No repository is active; activate one before requesting a statistics scan");
            return;
        }

        View view = view();
        if (view.state() != AnalyticsApi.State.AVAILABLE) {
            unavailable(ctx, view);
            return;
        }

        AnalyticsApi.RefreshOutcome outcome;
        try {
            outcome = analytics.refresh();
        } catch (RuntimeException failure) {
            unavailable(ctx, new View(AnalyticsApi.State.UNAVAILABLE, READ_FAILED,
                    AnalyticsApi.Scan.idle(), Optional.empty()));
            return;
        }
        if (outcome == null) {
            unavailable(ctx, new View(AnalyticsApi.State.UNAVAILABLE, READ_FAILED,
                    AnalyticsApi.Scan.idle(), Optional.empty()));
            return;
        }

        switch (outcome) {
            case STARTED -> ctx.sendJson(HttpStatus.ACCEPTED, JsonWriter.object(
                    "started", true,
                    "repositoryId", activeRepositoryId(status),
                    "state", AnalyticsApi.State.AVAILABLE.name(),
                    "scan", JsonWriter.raw(scanJson(view.scan()))));
            case ALREADY_RUNNING -> ctx.sendError(CONFLICT, "scan_in_progress",
                    "A statistics scan is already running for this repository; no second scan was started");
            case CLOSED -> ctx.sendError(CONFLICT, "repository_closing",
                    "The repository is shutting down and no scan can be started");
            case UNAVAILABLE -> unavailable(ctx, view);
        }
    }

    /**
     * The documented unavailable answer for an action that could not be taken.
     *
     * <p>508-free and explicit: the state, the reason and the fact that nothing was started. A client can
     * distinguish "statistics is switched off" from "no driver is installed" from "no repository is
     * active" without reading a server log.
     */
    private static void unavailable(RequestContext ctx, View view) {
        ctx.sendJson(STATISTICS_UNAVAILABLE, JsonWriter.object(
                "started", false,
                "available", false,
                "state", view.state().name(),
                "reason", view.reason()));
    }

    // ---------------------------------------------------------------- GET /api/diagnostics/jdbc

    private void jdbcDiagnostics(RequestContext ctx) {
        AnalyticsApi.Jdbc jdbc = jdbc();
        RepositoryManager.Status status = repositories.status();
        AnalyticsApi.Driver driver = jdbc.driver();

        ctx.sendJson(HttpStatus.OK, JsonWriter.object(
                "available", jdbc.state() == AnalyticsApi.State.AVAILABLE,
                "state", jdbc.state().name(),
                "reason", DiagnosticText.scrub(jdbc.reason()),
                "repositoryActive", status.usable(),
                "repositoryId", activeRepositoryId(status),
                "vendor", DiagnosticText.label(driver.vendor()),
                "driver", JsonWriter.raw(driverJson(driver)),
                // Configured-versus-derivable as a LABEL: an operator can tell whether the schema comes
                // from configuration or has to be read from a live session at scan time, and neither the
                // schema name nor anything an exception said about it is published here.
                "schema", JsonWriter.raw(schemaJson(jdbc, driver)),
                "pool", JsonWriter.raw(poolJson(jdbc.pool().orElse(null))),
                "scan", JsonWriter.raw(scanJson(jdbc.scan())),
                "lastJdbcError", JsonWriter.raw(failureJson(jdbc.lastError()))));
    }

    private static String driverJson(AnalyticsApi.Driver driver) {
        return JsonWriter.object(
                "vendor", DiagnosticText.label(driver.vendor()),
                "installed", driver.installed(),
                "ready", driver.ready(),
                // Validated class name, or empty: a URL cannot survive the class-name shape, so "safe
                // driver identity" is structural rather than a promise.
                "identity", DiagnosticText.driverClass(driver.identity()),
                "vendorUrlMatches", driver.vendorUrlMatches());
    }

    private static String schemaJson(AnalyticsApi.Jdbc jdbc, AnalyticsApi.Driver driver) {
        boolean known = !driver.vendor().isBlank();
        return JsonWriter.object(
                "configured", jdbc.schemaConfigured(),
                "source", !known ? "UNKNOWN"
                        : jdbc.schemaConfigured() ? "CONFIGURED" : "DERIVED_AT_SCAN");
    }

    private static String poolJson(AnalyticsApi.Pool pool) {
        if (pool == null) {
            return JsonWriter.object("present", false);
        }
        return JsonWriter.object(
                "present", true,
                "poolName", DiagnosticText.scrub(pool.poolName()),
                "configuredSize", pool.configuredSize(),
                "capacityInUse", pool.capacityInUse(),
                "available", pool.available(),
                "leased", pool.leased(),
                "creating", pool.creating(),
                "retiring", pool.retiring(),
                "quarantined", pool.quarantined(),
                "createAttempts", pool.createAttempts(),
                "created", pool.created(),
                "createFailures", pool.createFailures(),
                "createQuarantineFailures", pool.createQuarantineFailures(),
                "closeAttempts", pool.closeAttempts(),
                "closeSuccesses", pool.closeSuccesses(),
                "closeFailures", pool.closeFailures(),
                "borrowCount", pool.borrowCount(),
                "borrowTimeoutCount", pool.borrowTimeoutCount(),
                // null, never 0: a wait or an age nobody measured must not read as a measurement.
                "averageBorrowWaitMillis", finiteOrNull(pool.averageBorrowWaitMillis()),
                "maxBorrowWaitMillis", finiteOrNull(pool.maxBorrowWaitMillis()),
                "oldestSessionAgeMillis", pool.oldestSessionAgeMillis(),
                // The physical connection facts: the hard bound is a promise about physical connections,
                // and the observed peak is what lets an operator check the promise held.
                "openedConnections", pool.openedConnections(),
                "liveConnections", pool.liveConnections(),
                "peakLiveConnections", pool.peakLiveConnections(),
                "closeState", DiagnosticText.label(upperCase(pool.closeState())),
                "degraded", pool.degraded(),
                "lastError", JsonWriter.raw(failureJson(pool.lastError())));
    }

    private static String failureJson(AnalyticsApi.Failure failure) {
        if (failure == null || failure.empty()) {
            return "null";
        }
        return JsonWriter.object(
                // Each component is validated against the exact shape its slot may have, so a raw driver
                // message cannot be smuggled into any of them; the free text is scrubbed as well.
                "operation", DiagnosticText.operation(failure.operation()),
                "sqlState", DiagnosticText.sqlState(failure.sqlState()),
                "vendorCode", DiagnosticText.vendorCode(failure.vendorCode()),
                "summary", DiagnosticText.scrub(failure.summary()));
    }

    // ---------------------------------------------------------------- payload shapers

    private static String snapshotJson(AnalyticsApi.Snapshot snapshot) {
        if (snapshot == null) {
            // "no completed snapshot yet" and "a snapshot with no ItemTypes" are different facts, and an
            // empty object would render them identically.
            return JsonWriter.object("present", false);
        }
        AnalyticsApi.Totals totals = snapshot.totals();
        Object[] itemTypes = new Object[snapshot.itemTypes().size()];
        for (int i = 0; i < snapshot.itemTypes().size(); i++) {
            itemTypes[i] = JsonWriter.raw(itemTypeJson(snapshot.itemTypes().get(i)));
        }
        return JsonWriter.object(
                "present", true,
                "repositoryId", DiagnosticText.scrub(snapshot.repositoryId()),
                "capturedAt", instant(snapshot.capturedAt()),
                "scanStartedAt", instant(snapshot.scanStartedAt()),
                "scanDurationMillis", snapshot.scanDurationMillis(),
                "partialFailureCount", snapshot.partialFailureCount(),
                "coverage", JsonWriter.raw(coverageJson(totals)),
                "totals", JsonWriter.raw(totalsJson(totals)),
                "itemTypes", JsonWriter.raw(JsonWriter.array(itemTypes)));
    }

    /**
     * The coverage of one snapshot, so a subtotal over 98 of 100 ItemTypes is never presented as the
     * whole. {@code complete} is true only when every frozen ItemType contributed an available total.
     */
    private static String coverageJson(AnalyticsApi.Totals totals) {
        if (totals == null) {
            return JsonWriter.object("itemTypes", 0, "itemTypesWithTotals", 0, "failedItemTypes", 0,
                    "complete", false);
        }
        return JsonWriter.object(
                "itemTypes", totals.itemTypes(),
                "itemTypesWithTotals", totals.itemTypesWithTotals(),
                "failedItemTypes", totals.failedItemTypes(),
                "complete", !totals.partialFailure());
    }

    private static String totalsJson(AnalyticsApi.Totals totals) {
        if (totals == null) {
            return JsonWriter.object("totalItems", JsonWriter.raw(metricJson(null)),
                    "partialFailure", true);
        }
        Object[] groups = new Object[totals.byClassification().size()];
        for (int i = 0; i < totals.byClassification().size(); i++) {
            groups[i] = JsonWriter.raw(classificationJson(totals.byClassification().get(i)));
        }
        return JsonWriter.object(
                // The sum of the totals that exist, published beside the coverage that says how much of
                // the ItemType list it covers - and as a METRIC, so a scan in which nothing was measured
                // reports no number rather than a zero that reads like a measurement.
                "totalItems", JsonWriter.raw(metricJson(totals.totalItems())),
                "itemTypes", totals.itemTypes(),
                "itemTypesWithTotals", totals.itemTypesWithTotals(),
                "failedItemTypes", totals.failedItemTypes(),
                "partialFailure", totals.partialFailure(),
                "versions", JsonWriter.raw(metricJson(totals.versions())),
                "parts", JsonWriter.raw(metricJson(totals.parts())),
                "byClassification", JsonWriter.raw(JsonWriter.array(groups)));
    }

    private static String classificationJson(AnalyticsApi.ClassificationGroup totals) {
        return JsonWriter.object(
                "classification", DiagnosticText.scrub(totals.classification()),
                "itemTypes", totals.itemTypes(),
                "failedItemTypes", totals.failedItemTypes(),
                "totalItems", JsonWriter.raw(metricJson(totals.totalItems())));
    }

    private static String itemTypeJson(AnalyticsApi.ItemTypeResult result) {
        return JsonWriter.object(
                "name", DiagnosticText.scrub(result.name()),
                "itemTypeId", DiagnosticText.scrub(result.itemTypeId()),
                "businessClassification", DiagnosticText.scrub(result.businessClassification()),
                // The derived status, validated as a label: OK / PARTIAL / ERROR. A partially measured
                // ItemType is reported as PARTIAL here, so an incomplete number cannot be presented as a
                // complete one by looking at the enclosing object.
                "status", DiagnosticText.label(result.status()),
                "totalItems", JsonWriter.raw(metricJson(result.totalItems())),
                "today", JsonWriter.raw(metricJson(result.today())),
                "last7Days", JsonWriter.raw(metricJson(result.last7Days())),
                "last30Days", JsonWriter.raw(metricJson(result.last30Days())),
                "currentYear", JsonWriter.raw(metricJson(result.currentYear())),
                "versions", JsonWriter.raw(metricJson(result.versions())),
                "parts", JsonWriter.raw(metricJson(result.parts())),
                "error", DiagnosticText.scrub(result.error()));
    }

    /**
     * One metric. An unavailable or failed metric carries NO number: the state is published and the value
     * key is {@code null}, so a client can never read "0 items" for something nobody counted.
     */
    private static String metricJson(AnalyticsApi.Metric metric) {
        if (metric == null) {
            return JsonWriter.object("available", false, "state",
                    AnalyticsApi.MetricState.UNAVAILABLE.name(), "value", null);
        }
        if (metric.state() == AnalyticsApi.MetricState.AVAILABLE) {
            return JsonWriter.object(
                    "available", true,
                    "state", metric.state().name(),
                    "value", metric.value());
        }
        return JsonWriter.object(
                "available", false,
                "state", metric.state().name(),
                "value", null,
                "reason", DiagnosticText.scrub(metric.reason()));
    }

    private static String scanJson(AnalyticsApi.Scan scan) {
        AnalyticsApi.Scan current = scan == null ? AnalyticsApi.Scan.idle() : scan;
        return JsonWriter.object(
                "running", current.running(),
                // IDLE / RUNNING / COMPLETED / TIMED_OUT / CANCELLED / FAILED. Without it, "the last scan
                // timed out and published nothing" and "a scan completed" can look identical.
                "phase", DiagnosticText.label(current.phase()),
                "startedAt", instant(current.startedAt()),
                "finishedAt", instant(current.finishedAt()),
                "durationMillis", current.durationMillis(),
                "total", current.total(),
                "completed", current.completed(),
                "failed", current.failed(),
                "ratePerSecond", finiteOrNull(current.ratePerSecond()),
                "currentItemType", DiagnosticText.scrub(current.currentItemType()),
                "reason", DiagnosticText.scrub(current.reason()));
    }

    // ---------------------------------------------------------------- the analytics read

    /**
     * One consistent read of the analytics port.
     *
     * <p>A failure in ANY of the four reads yields the unavailable state with fixed text for the whole
     * view rather than a partly-populated answer: a response that carried a state from one instant and a
     * snapshot from another would be exactly the "looks like an answer" failure this tree keeps closing.
     * The exception's message is never used - the analytics layer's text may name a resource, and the
     * router's own 500 path exists for a genuinely broken wiring.
     */
    private View view() {
        try {
            AnalyticsApi.State state = analytics.state();
            return new View(
                    state == null ? AnalyticsApi.State.UNAVAILABLE : state,
                    DiagnosticText.scrub(analytics.reason()),
                    analytics.scan(),
                    analytics.snapshot());
        } catch (RuntimeException failure) {
            return new View(AnalyticsApi.State.UNAVAILABLE, READ_FAILED,
                    AnalyticsApi.Scan.idle(), Optional.empty());
        }
    }

    /**
     * One consistent read of the port: the state, its reason, the current scan and the latest snapshot.
     *
     * <p>Private to this class on purpose: the port describes what a producer must offer, not how this
     * route family bundles a read. A response that mixed a state from one instant with a snapshot from
     * another would be the "looks like an answer" failure this tree keeps closing.
     */
    private record View(AnalyticsApi.State state, String reason, AnalyticsApi.Scan scan,
                        Optional<AnalyticsApi.Snapshot> snapshot) {

        private View {
            state = state == null ? AnalyticsApi.State.UNAVAILABLE : state;
            reason = reason == null ? "" : reason;
            scan = scan == null ? AnalyticsApi.Scan.idle() : scan;
            snapshot = snapshot == null ? Optional.empty() : snapshot;
        }
    }

    private AnalyticsApi.Jdbc jdbc() {
        try {
            AnalyticsApi.Jdbc jdbc = analytics.jdbc();
            return jdbc == null
                    ? new AnalyticsApi.Jdbc(AnalyticsApi.State.UNAVAILABLE, READ_FAILED,
                            AnalyticsApi.Driver.unknown(), false, Optional.empty(),
                            AnalyticsApi.Scan.idle(), null)
                    : jdbc;
        } catch (RuntimeException failure) {
            return new AnalyticsApi.Jdbc(AnalyticsApi.State.UNAVAILABLE, READ_FAILED,
                    AnalyticsApi.Driver.unknown(), false, Optional.empty(), AnalyticsApi.Scan.idle(), null);
        }
    }

    private static String activeRepositoryId(RepositoryManager.Status status) {
        RepositoryContext context = status.context();
        return context == null ? "" : context.profileUnchecked().id();
    }

    // ---------------------------------------------------------------- value-free helpers

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    /**
     * The age of a snapshot in whole milliseconds, or {@code null} when there is none.
     *
     * <p>Clamped at zero: a clock step between capture and read must not produce a negative age, which
     * would read as a snapshot from the future.
     */
    private static Long ageMillis(Instant capturedAt) {
        if (capturedAt == null) {
            return null;
        }
        long age = Duration.between(capturedAt, Instant.now()).toMillis();
        return Math.max(0L, age);
    }

    /** JSON has no representation for NaN or infinity, so a non-finite number is reported as null. */
    private static Double finiteOrNull(Double value) {
        return value != null && Double.isFinite(value) ? value : null;
    }

    private static String upperCase(String value) {
        return value == null ? "" : value.toUpperCase(java.util.Locale.ROOT);
    }

    @Override
    public String toString() {
        return "StatisticsApiRoutes[repositories=" + repositories.state().name()
                + ", analytics=" + analytics + "]";
    }
}
