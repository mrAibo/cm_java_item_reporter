package com.mraibo.cminsight.web;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The analytics facts the HTTP tier is allowed to publish, as one typed port.
 *
 * <h2>Why the web tier does not read the statistics DTOs directly</h2>
 *
 * <p>The statistics half of the runtime is implemented behind
 * {@code com.mraibo.cminsight.statistics} and {@code com.mraibo.cminsight.db}. This interface is the
 * single seam the routes are written against, and {@code app.RepositoryAnalyticsApi} is the single
 * adapter that maps the statistics read model into it. That has two consequences worth stating:
 *
 * <ul>
 *   <li><strong>A response cannot widen by accident.</strong> The JSON a client receives is built from
 *       the components of these records, so a field that is not declared here has nowhere to appear in
 *       a response. Goal 03 section 13 forbids publishing the JDBC URL, the database user name, a schema
 *       taken from an exception, raw SQL, a raw {@code SQLException} message and secret source contents;
 *       a value-free port is the cheapest way to make that structural rather than a review promise - the
 *       same argument {@code CmPoolDiagnostics} makes for the CM half.</li>
 *   <li><strong>Tests can drive every response state without a database.</strong> A hostile or degraded
 *       seam is constructible, so "no driver installed", "statistics disabled", "a refresh is already
 *       running" and "the producer tried to leak a JDBC URL" are all reachable with no driver JAR, no
 *       server and no fake JDBC machinery.</li>
 * </ul>
 *
 * <p>Typed, not a {@code Map<String,Object>} service locator: every fact has a declared accessor, so a
 * caller cannot obtain something the port does not offer and cannot silently substitute one.
 *
 * <h2>Immutability and cheap reads</h2>
 *
 * <p>Every component is an immutable record over primitives, strings and instants. Implementations must
 * answer from state they already hold - a diagnostics read that blocked on the database would delay a
 * request for no reason, and {@code GET /api/diagnostics/jdbc} must never open a database connection.
 *
 * <h2>States</h2>
 *
 * <p>{@link State} distinguishes the three operator-visible situations, because they need different
 * actions and the API must never render one as another:
 *
 * <ul>
 *   <li>{@link State#AVAILABLE} - analytics is configured, the driver is present and a scan can be
 *       requested;</li>
 *   <li>{@link State#DISABLED} - {@code feature.statistics=false}: the operator switched the feature
 *       off. Nothing is wrong.</li>
 *   <li>{@link State#UNAVAILABLE} - the feature is on but analytics cannot be used right now: no JDBC
 *       driver, a URL/vendor mismatch, an unusable schema, or no repository active. The repository is
 *       still activated and its metadata and retention routes still work - Goal 03 section 3.</li>
 * </ul>
 *
 * <p>{@link #reason()} carries a fixed, already-value-free sentence for the non-{@link State#AVAILABLE}
 * cases. The web tier sanitises it again before publishing it, so a producer that returns a raw driver
 * message still cannot put a URL or a credential into a response body.
 */
public interface AnalyticsApi {

    /** Whether analytics can be used, is switched off, or cannot be used right now. */
    enum State {
        AVAILABLE,
        DISABLED,
        UNAVAILABLE
    }

    /**
     * What one refresh request did.
     *
     * <p>Four outcomes and not a boolean, because the route has to answer four different things: a new
     * scan started ({@code 202}), a scan was already in flight so nothing was started
     * ({@code 409 scan_in_progress}), the repository is going away ({@code 409 repository_closing}), and
     * no scan could be started at all ({@code 503}). A boolean would collapse them, and a client could
     * not tell a conflict from a misconfiguration.
     */
    enum RefreshOutcome {
        /** A new scan was started by this call. At most one scan is ever in flight. */
        STARTED,
        /** A scan was already in flight; this call started nothing and changed nothing. */
        ALREADY_RUNNING,
        /** The repository context is closing or closed, so no scan was started. */
        CLOSED,
        /** Analytics is disabled or unavailable, so no scan was started. */
        UNAVAILABLE
    }

    /** The three states a single metric can be in. {@code ERROR} and {@code UNAVAILABLE} carry no number. */
    enum MetricState {
        AVAILABLE,
        UNAVAILABLE,
        ERROR
    }

    /**
     * One metric value.
     *
     * <p>The canonical constructor enforces the goal's rule that an unavailable or failed metric is
     * <em>never</em> rendered as numeric zero: {@code value} must be {@code null} unless the state is
     * {@link MetricState#AVAILABLE}, and must be present when it is. A producer therefore cannot publish
     * "0 items" for an ItemType it could not count, and a reader cannot mistake one for the other.
     */
    record Metric(MetricState state, Long value, String reason) {

        public Metric {
            Objects.requireNonNull(state, "state");
            if (state == MetricState.AVAILABLE && value == null) {
                throw new IllegalArgumentException("An AVAILABLE metric must carry a value");
            }
            if (state != MetricState.AVAILABLE && value != null) {
                throw new IllegalArgumentException(
                        "A " + state + " metric must not carry a number: an unavailable or failed count is"
                                + " never zero");
            }
        }

        public static Metric available(long value) {
            return new Metric(MetricState.AVAILABLE, value, "");
        }

        public static Metric unavailable(String reason) {
            return new Metric(MetricState.UNAVAILABLE, null, reason);
        }

        public static Metric error(String reason) {
            return new Metric(MetricState.ERROR, null, reason);
        }

        public boolean available() {
            return state == MetricState.AVAILABLE;
        }
    }

    /**
     * One ItemType's result.
     *
     * <p>{@code versions} and {@code parts} stay {@link MetricState#UNAVAILABLE} in this goal: IBM's
     * documented semantics for them are not implemented, and reporting a number nobody computed would be
     * worse than reporting that there is none.
     *
     * @param name                   the ItemType name, as metadata reports it
     * @param itemTypeId             the ItemType id, or {@code null} when metadata did not supply one
     * @param businessClassification the label already present in metadata (never a second SAP/NON-SAP
     *                               implementation)
     * @param status                 {@code OK}, {@code PARTIAL} or {@code ERROR}, as the statistics layer
     *                               derived it from the metrics themselves; a partially measured ItemType
     *                               must not be published as OK just because its total was available
     * @param error                  an already-sanitised one-line failure description, or {@code ""}
     */
    record ItemTypeResult(
            String name,
            String itemTypeId,
            String businessClassification,
            String status,
            Metric totalItems,
            Metric today,
            Metric last7Days,
            Metric last30Days,
            Metric currentYear,
            Metric versions,
            Metric parts,
            String error) {

        public ItemTypeResult {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(totalItems, "totalItems");
            status = status == null || status.isBlank() ? "ERROR" : status;
        }

        /** True when this ItemType could not be counted at all. */
        public boolean failed() {
            return "ERROR".equals(status);
        }
    }

    /**
     * Totals for one business classification, so a subtotal is never presented as the whole.
     *
     * <p>Named {@code ClassificationGroup} rather than {@code ClassificationTotals} because the statistics
     * layer already has a record of that name: two identically named types meaning slightly different
     * things is exactly how a mapping is quietly wired to the wrong one.
     */
    record ClassificationGroup(String classification, int itemTypes, Metric totalItems, int failedItemTypes) {

        public ClassificationGroup {
            Objects.requireNonNull(classification, "classification");
            totalItems = totalItems == null ? Metric.unavailable("") : totalItems;
        }
    }

    /**
     * The overall totals of one snapshot, with the coverage they were computed from.
     *
     * @param totalItems        the sum of the per-ItemType totals that ARE available; it is only a
     *                          complete total when {@code itemTypesWithTotals == itemTypes} and
     *                          {@code failedItemTypes == 0}. Deliberately a {@link Metric}: when no
     *                          ItemType produced a measured total, the sum is reported as unavailable
     *                          rather than as {@code 0} items
     * @param itemTypes         how many ItemTypes the frozen scan list held
     * @param itemTypesWithTotals how many contributed an available total to {@code totalItems}
     * @param failedItemTypes   how many could not be counted
     * @param partialFailure    true when the snapshot does not cover every ItemType
     * @param versions          always unavailable in this goal
     * @param parts             always unavailable in this goal
     */
    record Totals(
            Metric totalItems,
            int itemTypes,
            int itemTypesWithTotals,
            int failedItemTypes,
            boolean partialFailure,
            Metric versions,
            Metric parts,
            List<ClassificationGroup> byClassification) {

        public Totals {
            totalItems = totalItems == null ? Metric.unavailable("") : totalItems;
            versions = versions == null ? Metric.unavailable("") : versions;
            parts = parts == null ? Metric.unavailable("") : parts;
            byClassification = byClassification == null ? List.of() : List.copyOf(byClassification);
        }
    }

    /**
     * One completed, immutable snapshot: what the API calls "the latest statistics".
     *
     * @param capturedAt         when the snapshot was published
     * @param scanStartedAt      when the scan that produced it started
     * @param scanDurationMillis how long that scan took
     * @param partialFailureCount how many ItemTypes are ERROR in this snapshot
     */
    record Snapshot(
            String repositoryId,
            Instant capturedAt,
            Instant scanStartedAt,
            long scanDurationMillis,
            int partialFailureCount,
            List<ItemTypeResult> itemTypes,
            Totals totals) {

        public Snapshot {
            itemTypes = itemTypes == null ? List.of() : List.copyOf(itemTypes);
        }
    }

    /**
     * The current scan, in flight or finished.
     *
     * <p>{@code running=false} with a {@code null} {@code startedAt} is the "no scan has run yet" shape;
     * the route renders it as such rather than as a completed scan of zero ItemTypes. {@code phase} and
     * {@code reason} are what tell "the last scan timed out and published nothing" apart from "a scan
     * completed" - a distinction the counters alone cannot express, because both can report the same
     * {@code completed} count.
     */
    record Scan(
            boolean running,
            String phase,
            Instant startedAt,
            Instant finishedAt,
            long durationMillis,
            int total,
            int completed,
            int failed,
            double ratePerSecond,
            String currentItemType,
            String reason) {

        public Scan {
            phase = phase == null || phase.isBlank() ? "IDLE" : phase;
            currentItemType = currentItemType == null ? "" : currentItemType;
            reason = reason == null ? "" : reason;
        }

        /** The state before any scan has been requested. */
        public static Scan idle() {
            return new Scan(false, "IDLE", null, null, 0L, 0, 0, 0, 0.0d, "", "");
        }
    }

    /**
     * A database failure, reduced to the value-free parts Goal 03 sections 11 and 13 allow.
     *
     * <p>Deliberately no raw message component. A raw {@code SQLException} message routinely quotes the
     * SQL, the schema, the user name or the URL, and the route therefore emits each part separately, each
     * one validated against a strict pattern and dropped when it does not match: a value that is not a
     * plausible SQLState or vendor code simply has nowhere to appear. {@code summary} is the producer's
     * already-sanitised one-line description, and the web tier runs it through
     * {@code DiagnosticText.scrub} - control characters stripped, any {@code jdbc:...} URL, any
     * {@code user=}/{@code password=} value and any {@code //user:secret@} userinfo redacted, and the
     * length capped - so even a hostile producer cannot put a URL or a credential into a response.
     *
     * @param operation  a fixed operation label, for example {@code connect} or {@code aggregate}
     * @param sqlState   the SQLState, when the driver supplied a usable one
     * @param vendorCode the vendor error code as text, when the driver supplied one
     * @param summary    an already-sanitised one-line description, or {@code ""}
     */
    record Failure(String operation, String sqlState, String vendorCode, String summary) {

        /** A failure whose only safe content is the producer's sanitised one-liner. */
        public static Failure summaryOf(String summary) {
            return new Failure("", "", "", summary == null ? "" : summary);
        }

        /** True when this record has nothing at all to publish. */
        public boolean empty() {
            return isBlank(operation) && isBlank(sqlState) && isBlank(vendorCode) && isBlank(summary);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    /**
     * The JDBC pool's counters, mirroring {@code CmPoolDiagnostics}.
     *
     * <p>Counts, states and durations only. {@code lastError} is the sanitised, value-free failure
     * record rather than the pool's own text, so a raw driver message has no field to travel in. The
     * three nullable components are {@code null} when the producer does not report them - never
     * substituted with {@code 0}, which would be a measurement nobody made.
     *
     * <p>{@code openedConnections}, {@code liveConnections} and {@code peakLiveConnections} are the
     * PHYSICAL facts the pool's own slot accounting cannot express: the hard bound is a promise about
     * physical connections, and the peak is the observation that lets an operator check it.
     */
    record Pool(
            String poolName,
            int configuredSize,
            int capacityInUse,
            int available,
            int leased,
            int creating,
            int retiring,
            int quarantined,
            long createAttempts,
            long created,
            long createFailures,
            long createQuarantineFailures,
            long closeAttempts,
            long closeSuccesses,
            long closeFailures,
            long borrowCount,
            long borrowTimeoutCount,
            Double averageBorrowWaitMillis,
            Double maxBorrowWaitMillis,
            String closeState,
            Long oldestSessionAgeMillis,
            boolean degraded,
            long openedConnections,
            long liveConnections,
            long peakLiveConnections,
            Failure lastError) {
    }

    /**
     * Local driver readiness.
     *
     * <p>Deliberately carries no URL text of any kind - not the configured URL, and not even the vendor
     * family prefix. The family is already expressed by {@code vendor}, the mismatch by
     * {@code vendorUrlMatches}, and a response that contains no {@code jdbc:}-prefixed text at all is one
     * a reviewer can check by eye instead of by argument.
     *
     * @param vendor          the profile's database family, as {@code DatabaseVendor} names it
     * @param installed       whether the driver class can be loaded/registered locally
     * @param ready           whether it can actually be used (installed AND registered AND the URL family
     *                        matches)
     * @param identity        the driver class name, or {@code ""} when unknown; a class name is safe to
     *                        publish, a URL is not, and the web tier validates the shape before emitting it
     * @param vendorUrlMatches false when the configured URL belongs to another vendor's family
     */
    record Driver(
            String vendor,
            boolean installed,
            boolean ready,
            String identity,
            boolean vendorUrlMatches) {

        public Driver {
            vendor = vendor == null ? "" : vendor;
            identity = identity == null ? "" : identity;
        }

        /** The shape before any repository is active: nothing known, nothing claimed. */
        public static Driver unknown() {
            return new Driver("", false, false, "", true);
        }
    }

    /** The state of analytics. */
    State state();

    /**
     * A fixed, value-free explanation for a non-{@link State#AVAILABLE} state, or {@code ""}.
     *
     * <p>The contract is the same as {@code CmPoolDiagnostics#lastAdapterError()}: a category and a
     * detail an operator can act on, never a vendor message verbatim. The web tier sanitises it again.
     */
    String reason();

    /** The latest COMPLETED snapshot, or empty when none has ever completed. */
    Optional<Snapshot> snapshot();

    /** The current scan status; never {@code null}. */
    Scan scan();

    /**
     * Requests one scan.
     *
     * <p>Must be atomic with respect to the "at most one scan" rule: a second call while a scan is in
     * flight returns {@link RefreshOutcome#ALREADY_RUNNING} and starts nothing.
     */
    RefreshOutcome refresh();

    /** Driver, pool, scan and last-failure facts for {@code GET /api/diagnostics/jdbc}; never {@code null}. */
    Jdbc jdbc();

    /**
     * The JDBC diagnostics payload.
     *
     * @param schemaConfigured whether {@code repository.jdbc.schema} is configured (as opposed to being
     *                         derived from a live session at scan time). Reported as a fact, so an
     *                         operator can fix a wrong schema without the response naming it.
     */
    record Jdbc(
            State state,
            String reason,
            Driver driver,
            boolean schemaConfigured,
            Optional<Pool> pool,
            Scan scan,
            Failure lastError) {

        public Jdbc {
            Objects.requireNonNull(state, "state");
            driver = driver == null ? Driver.unknown() : driver;
            pool = pool == null ? Optional.empty() : pool;
            scan = scan == null ? Scan.idle() : scan;
        }
    }

    /**
     * The seam used where no analytics capability is wired at all.
     *
     * <p>Exists so every wiring path - including the legacy three-argument
     * {@code WebServer.installCmApiRoutes} that tests use - still INSTALLS the three routes. A route that
     * is missing answers {@code 404}, which reads like a path typo; a route that reports the documented
     * unavailable state tells the operator what is actually true. That distinction is the whole reason
     * this factory exists.
     *
     * @param reason a fixed, value-free sentence explaining why analytics is not wired
     */
    static AnalyticsApi unavailable(String reason) {
        String fixed = reason == null ? "" : reason;
        return new AnalyticsApi() {

            @Override
            public State state() {
                return State.UNAVAILABLE;
            }

            @Override
            public String reason() {
                return fixed;
            }

            @Override
            public Optional<Snapshot> snapshot() {
                return Optional.empty();
            }

            @Override
            public Scan scan() {
                return Scan.idle();
            }

            @Override
            public RefreshOutcome refresh() {
                return RefreshOutcome.UNAVAILABLE;
            }

            @Override
            public Jdbc jdbc() {
                return new Jdbc(State.UNAVAILABLE, fixed, Driver.unknown(), false, Optional.empty(),
                        Scan.idle(), null);
            }

            @Override
            public String toString() {
                return "AnalyticsApi[unavailable]";
            }
        };
    }
}
