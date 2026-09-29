package com.mraibo.cminsight.app;

import com.mraibo.cminsight.config.DatabaseVendor;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.db.JdbcDrivers;
import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.statistics.ClassificationTotals;
import com.mraibo.cminsight.statistics.ItemTypeStatistics;
import com.mraibo.cminsight.statistics.MetricValue;
import com.mraibo.cminsight.statistics.ScanStartResult;
import com.mraibo.cminsight.statistics.ScanStatus;
import com.mraibo.cminsight.statistics.StatisticsAvailability;
import com.mraibo.cminsight.statistics.StatisticsCoverage;
import com.mraibo.cminsight.statistics.StatisticsDiagnostics;
import com.mraibo.cminsight.statistics.StatisticsRepository;
import com.mraibo.cminsight.statistics.StatisticsSnapshot;
import com.mraibo.cminsight.statistics.StatisticsTotals;
import com.mraibo.cminsight.web.AnalyticsApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The analytics port over the active repository's own statistics service.
 *
 * <h2>One adapter file, on purpose</h2>
 *
 * <p>This is the only class in {@code app} (and the only file outside {@code web/}) that names a type
 * from {@code statistics} or {@code db}. Everything the HTTP tier knows is expressed by
 * {@link AnalyticsApi}, so the mapping from the statistics read model into the published value shape
 * happens in one reviewable place, and the routes cannot reach a {@code Connection}, a {@code Statement},
 * a {@code ResultSet} or a driver object even by accident.
 *
 * <h2>Lazy by construction</h2>
 *
 * <p>Constructing this class opens no connection, loads no driver and reads no credential: the
 * constructor holds a reference to the repository manager and one boolean. The statistics service and the
 * JDBC readiness verdict are read per request from the ACTIVE context, and readiness is the local
 * class-loading/registration check from {@code JdbcDrivers} - never a network call. A repository whose
 * database is unreachable is therefore still activatable, listable and readable, which is exactly the
 * Goal 03 section 3 requirement.
 *
 * <h2>Availability is three states, and the service is the authority</h2>
 *
 * <p>{@code feature.statistics=false} is {@link State#DISABLED} - the operator switched it off, nothing is
 * wrong. The feature switched on but the driver, the URL family, the credential, the schema or the database
 * being unusable is {@link State#UNAVAILABLE}, with the service's own fixed reason. No repository being
 * active is {@link State#UNAVAILABLE} as well, because there is no analytics capability to describe. When a
 * statistics service exists it is consulted first and its own {@code enabled}/{@code available} pair wins:
 * the service knows what it was constructed with, and this class must not overrule it from a flag.
 */
public final class RepositoryAnalyticsApi implements AnalyticsApi {

    /** Fixed reason for the operator's own switch being off. */
    static final String DISABLED_REASON =
            "feature.statistics=false: the analytics half is disabled by configuration";

    /** Fixed reason for the case where no repository has been activated. */
    static final String NO_REPOSITORY_REASON =
            "no repository is active, so no analytics capability exists";

    /**
     * Fixed reason for the case where a repository IS active but was activated without an analytics
     * service - a context built by a test, or one activated while the feature was disabled.
     */
    static final String NO_SERVICE_REASON =
            "the active repository was activated without an analytics capability";

    private final RepositoryManager repositories;
    private final boolean enabled;

    /**
     * @param repositories the manager that owns the single active repository
     * @param enabled      the resolved {@code feature.statistics} value, used only when no repository is
     *                     active yet; a live statistics service answers for itself
     */
    public RepositoryAnalyticsApi(RepositoryManager repositories, boolean enabled) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.enabled = enabled;
    }

    // ---------------------------------------------------------------- state

    @Override
    public State state() {
        Active active = active();
        if (active.statistics() == null) {
            if (!enabled) {
                return State.DISABLED;
            }
            return State.UNAVAILABLE;
        }
        StatisticsAvailability availability = active.statistics().availability();
        if (!availability.enabled()) {
            return State.DISABLED;
        }
        return availability.available() ? State.AVAILABLE : State.UNAVAILABLE;
    }

    @Override
    public String reason() {
        Active active = active();
        if (active.statistics() == null) {
            if (!enabled) {
                return DISABLED_REASON;
            }
            return active.profile() == null ? NO_REPOSITORY_REASON : NO_SERVICE_REASON;
        }
        StatisticsAvailability availability = active.statistics().availability();
        if (availability.available()) {
            return "";
        }
        return availability.enabled() ? availability.reason() : DISABLED_REASON;
    }

    // ---------------------------------------------------------------- snapshot and scan

    /**
     * The latest COMPLETED snapshot, mapped into the published shape.
     *
     * <p>Deliberately returned even when analytics is currently unavailable: a scan that completed before
     * the database became unreachable is still the last thing this runtime knows, and the response carries
     * the unavailable state beside it. What is never returned is a partial object - the statistics layer
     * publishes one immutable snapshot atomically or nothing at all.
     */
    @Override
    public Optional<Snapshot> snapshot() {
        Active active = active();
        if (active.statistics() == null) {
            return Optional.empty();
        }
        return active.statistics().snapshot().map(RepositoryAnalyticsApi::snapshot);
    }

    @Override
    public Scan scan() {
        Active active = active();
        return active.statistics() == null ? Scan.idle() : scan(active.statistics().progress());
    }

    @Override
    public RefreshOutcome refresh() {
        Active active = active();
        if (active.statistics() == null) {
            return RefreshOutcome.UNAVAILABLE;
        }
        ScanStartResult result = active.statistics().requestScan();
        if (result == null) {
            return RefreshOutcome.UNAVAILABLE;
        }
        return switch (result) {
            case STARTED -> RefreshOutcome.STARTED;
            case ALREADY_RUNNING -> RefreshOutcome.ALREADY_RUNNING;
            case CLOSED -> RefreshOutcome.CLOSED;
            case UNAVAILABLE -> RefreshOutcome.UNAVAILABLE;
        };
    }

    // ---------------------------------------------------------------- diagnostics

    @Override
    public Jdbc jdbc() {
        Active active = active();
        StatisticsRepository statistics = active.statistics();
        StatisticsDiagnostics diagnostics = statistics == null ? null : statistics.diagnostics();
        State state = state();
        return new Jdbc(
                state,
                reason(),
                driver(active.profile()),
                active.profile() != null && active.profile().jdbcSchema() != null,
                pool(diagnostics),
                statistics == null ? Scan.idle() : scan(statistics.progress()),
                lastError(diagnostics));
    }

    /**
     * The LOCAL driver verdict for the active profile.
     *
     * <p>{@code installed} and {@code ready} are deliberately two facts: a driver can be present on the
     * class path while the configured URL belongs to the other vendor's family, and that pair needs a
     * different operator action from a missing jar. {@code identity} is the installed driver CLASS NAME -
     * safe to publish, and the only part of the driver that is. The vendor family is reported as
     * {@code vendor} and the family check as {@code vendorUrlMatches}; no URL text is published at all.
     *
     * <p>The identity is taken from the installed-classes probe rather than from the readiness verdict, so a
     * driver that IS installed is still named when the URL family is wrong; reporting an empty identity in
     * that case would hide the driver from the operator who needs to know it is there.
     */
    private static Driver driver(RepositoryProfile profile) {
        if (profile == null) {
            return Driver.unknown();
        }
        DatabaseVendor vendor = profile.databaseVendor();
        boolean installed = JdbcDrivers.driverInstalled(vendor);
        List<String> installedClasses = JdbcDrivers.installedDriverClasses(vendor);
        return new Driver(
                vendor.name(),
                installed,
                JdbcDrivers.readiness(vendor, profile.jdbcUrl()).ready(),
                installedClasses.isEmpty() ? "" : installedClasses.get(0),
                JdbcDrivers.urlMatchesVendor(vendor, profile.jdbcUrl()));
    }

    /**
     * The pool counters, or empty when there is no pool at all.
     *
     * <p>An empty pool name is the statistics layer's own way of saying "no pool exists" (the disabled and
     * unavailable views both use it), so it maps to an absent pool rather than to a pool reporting zeros.
     *
     * <p>The two borrow-wait averages and the oldest-session age are reported as {@code null}: the safe
     * diagnostics view does not carry them, and substituting {@code 0} would publish a measurement nobody
     * made. The value shape has the fields so a later diagnostics view can fill them without changing the
     * response contract.
     */
    private static Optional<Pool> pool(StatisticsDiagnostics diagnostics) {
        if (diagnostics == null || diagnostics.poolName().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Pool(
                diagnostics.poolName(),
                diagnostics.configuredPoolSize(),
                diagnostics.capacityInUse(),
                diagnostics.availableSlots(),
                diagnostics.leased(),
                diagnostics.creating(),
                diagnostics.retiring(),
                diagnostics.quarantined(),
                diagnostics.createAttempts(),
                diagnostics.created(),
                diagnostics.createFailures(),
                diagnostics.createQuarantineFailures(),
                diagnostics.closeAttempts(),
                diagnostics.closeSuccesses(),
                diagnostics.closeFailures(),
                diagnostics.borrowCount(),
                diagnostics.borrowTimeoutCount(),
                null,
                null,
                diagnostics.closeState(),
                null,
                diagnostics.degraded(),
                diagnostics.openedConnections(),
                diagnostics.liveConnections(),
                diagnostics.peakLiveConnections(),
                lastError(diagnostics)));
    }

    /**
     * The sanitised last JDBC error, or {@code null}.
     *
     * <p>Carried as the summary component of the failure record, which the web tier validates and scrubs
     * again before publishing. The statistics layer guarantees the text is already a fixed operation label
     * plus a SQLState/vendor code; the second pass is defence in depth, not distrust of one producer.
     */
    private static Failure lastError(StatisticsDiagnostics diagnostics) {
        if (diagnostics == null || diagnostics.lastError().isEmpty()) {
            return null;
        }
        return Failure.summaryOf(diagnostics.lastError());
    }

    // ---------------------------------------------------------------- mapping

    private static Snapshot snapshot(StatisticsSnapshot snapshot) {
        List<ItemTypeResult> results = new ArrayList<>(snapshot.perItemType().size());
        for (ItemTypeStatistics result : snapshot.perItemType()) {
            results.add(itemType(result));
        }
        return new Snapshot(
                snapshot.repositoryId(),
                snapshot.capturedAt(),
                snapshot.scanStartedAt(),
                snapshot.scanDurationMs(),
                snapshot.partialFailureCount(),
                results,
                totals(snapshot));
    }

    private static Totals totals(StatisticsSnapshot snapshot) {
        StatisticsTotals totals = snapshot.totals();
        StatisticsCoverage coverage = snapshot.coverage();
        return new Totals(
                totalMetric(totals),
                coverage.requestedItemTypes(),
                (int) Math.min(Integer.MAX_VALUE, totals.countedItemTypes()),
                coverage.failedItemTypes(),
                !totals.complete(),
                metric(totals.versions()),
                metric(totals.parts()),
                classificationTotals(totals));
    }

    /**
     * The overall total, as a metric.
     *
     * <p>A sum over zero measured ItemTypes is NOT "zero items": that is the shape a repository whose every
     * ItemType failed would otherwise publish, and it reads as a measurement. The coverage counts travel
     * beside it either way, so a reader can always see how much of the list the number covers.
     */
    private static Metric totalMetric(StatisticsTotals totals) {
        if (totals.countedItemTypes() == 0L && totals.visitedItemTypes() > 0L) {
            return Metric.unavailable(
                    "no ItemType produced a measured total, so this sum is not a count of zero items");
        }
        return Metric.available(totals.logicalItemsTotal());
    }

    private static List<AnalyticsApi.ClassificationGroup> classificationTotals(StatisticsTotals totals) {
        List<AnalyticsApi.ClassificationGroup> groups =
                new ArrayList<>(totals.byBusinessClassification().size());
        for (ClassificationTotals group : totals.byBusinessClassification()) {
            // Same rule as the overall total, per label: a class whose every ItemType failed has no
            // measured count, and reporting 0 there would understate the repository while looking exact.
            Metric items = group.itemTypes() > 0L && group.errorItemTypes() == group.itemTypes()
                    ? Metric.unavailable("every ItemType of this classification failed to be measured")
                    : Metric.available(group.logicalItems());
            groups.add(new AnalyticsApi.ClassificationGroup(
                    group.businessClassification(),
                    (int) Math.min(Integer.MAX_VALUE, group.itemTypes()),
                    items,
                    (int) Math.min(Integer.MAX_VALUE, group.errorItemTypes())));
        }
        return List.copyOf(groups);
    }

    private static ItemTypeResult itemType(ItemTypeStatistics result) {
        return new ItemTypeResult(
                result.itemTypeName(),
                Integer.toString(result.itemTypeId()),
                result.businessClassification(),
                result.status().name(),
                metric(result.logicalItems()),
                metric(result.createdToday()),
                metric(result.createdLast7Days()),
                metric(result.createdLast30Days()),
                metric(result.createdCurrentYear()),
                metric(result.versions()),
                metric(result.parts()),
                result.errorMessage());
    }

    /**
     * One measured value, or the honest absence of one.
     *
     * <p>The three states map one-to-one and no number is ever invented: an {@code ERROR} or
     * {@code UNAVAILABLE} metric becomes a metric with a {@code null} value, which the response renders
     * without a number at all.
     */
    private static Metric metric(MetricValue value) {
        if (value == null) {
            return Metric.unavailable("");
        }
        return switch (value.availability()) {
            case AVAILABLE -> Metric.available(value.valueOrZero());
            case UNAVAILABLE -> Metric.unavailable(value.reason());
            case ERROR -> Metric.error(value.reason());
        };
    }

    private static Scan scan(ScanStatus status) {
        return new Scan(
                status.running(),
                status.phase().name(),
                status.startedAt(),
                status.finishedAt(),
                status.durationMillis(),
                status.total(),
                status.completed(),
                status.failed(),
                status.ratePerSecond(),
                status.currentItemType(),
                status.failureReason());
    }

    // ---------------------------------------------------------------- the active repository

    /**
     * One consistent read of the active context, its profile and its analytics service.
     *
     * <p>From one {@link RepositoryManager#status()} snapshot, so the profile and the service belong to the
     * same activation and cannot straddle a repository switch. {@code profileUnchecked()} is deliberate:
     * {@code profile()} throws once the context is closing, and a diagnostics read must be able to describe
     * a repository that is shutting down.
     */
    private Active active() {
        RepositoryManager.Status status = repositories.status();
        RepositoryContext context = status.context();
        if (context == null) {
            return new Active(null, null);
        }
        return new Active(context.profileUnchecked(), context.statistics().orElse(null));
    }

    /** The profile and analytics service of one activation. */
    private record Active(RepositoryProfile profile, StatisticsRepository statistics) {
    }

    @Override
    public String toString() {
        State state = state();
        return "RepositoryAnalyticsApi[state=" + state + ", repositories=" + repositories.state().name() + "]";
    }
}
