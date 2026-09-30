package com.mraibo.cminsight.statistics;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The typed analytics service one repository context exposes: the read/refresh contract, the freshness
 * judgement of the published snapshot, the targeted single-ItemType capability, and the pool view.
 *
 * <h2>Why a service instead of a bare coordinator</h2>
 *
 * <p>The coordinator is the mechanism; this type is the published answer to "what does this repository know
 * about statistics?". It stays constructible in every state the goal requires - feature disabled, driver
 * absent, credential missing, database unreachable - because those are ordinary states of a runtime whose
 * IBM CM half must keep working. In each of them the service reports the availability verdict and keeps
 * answering reads ({@link #snapshot()} empty, {@link #progress()} idle), and a refresh is answered with
 * {@link ScanStartResult#UNAVAILABLE} rather than by throwing.
 *
 * <p>The pool view is delegated to a {@link StatisticsDiagnosticsSource} so the service itself holds no JDBC
 * type. A source that throws is reported as "no diagnostics" instead of letting the failure escape: a
 * diagnostics endpoint must never be the thing that fails.
 *
 * <h2>Freshness is a judgement here, never a second cache</h2>
 *
 * <p>{@link #freshness(Instant)} compares the published snapshot's own {@code capturedAt} with the
 * CONFIGURED {@code cache.statistics.ttl.seconds}, which this service carries as a
 * {@link FreshnessThreshold}. It reads no pool, touches no metadata and starts nothing, so a GET that
 * renders it cannot cause database work, and a snapshot that has gone stale is still returned by
 * {@link #snapshot()} - reported as stale, never withheld.
 *
 * <h2>The targeted capability is owned elsewhere and merely published here</h2>
 *
 * <p>{@link #refreshItemType(int)} delegates to the context's {@link TargetedRefreshService}, which owns the
 * shared gate, the engine and the per-context detail cache. The service holds no second cache and no second
 * latch: it is the typed entry point a route can reach. That capability is registered as its own context
 * resource and is closed before this service's coordinator, so a repository switch drains the targeted
 * operation and discards its detail cache first.
 *
 * <p>{@link #close()} closes the coordinator, which cancels the running scan and joins its threads. A
 * repository context must register this service's coordinator as an owned resource AFTER the JDBC pool, so
 * that the scan is drained before the pool it borrows from is closed.
 */
public final class StatisticsService implements StatisticsRepository, AutoCloseable {

    private final String repositoryId;
    private final StatisticsAvailability declared;
    private final ScanCoordinator coordinator;
    private final StatisticsDiagnosticsSource diagnosticsSource;
    private final FreshnessThreshold freshnessThreshold;
    private final TargetedRefreshService targetedRefresh;

    /**
     * @param repositoryId      the repository this service belongs to
     * @param declared          the availability verdict to report when no coordinator is present; ignored
     *                          when a coordinator exists, whose own verdict is authoritative
     * @param coordinator       the scan coordinator, or {@code null} when no scan can run
     * @param diagnosticsSource the pool view, or {@code null} when there is no pool
     */
    public StatisticsService(String repositoryId,
                             StatisticsAvailability declared,
                             ScanCoordinator coordinator,
                             StatisticsDiagnosticsSource diagnosticsSource) {
        this(repositoryId, declared, coordinator, diagnosticsSource, FreshnessThreshold.defaults(), null);
    }

    /**
     * The full form: the analytics reads, the freshness threshold the judgements are made with, and the
     * targeted single-ItemType capability when this context has one.
     *
     * @param freshnessThreshold the configured {@code cache.statistics.ttl.seconds}; required, so a
     *                           service can never silently judge freshness against a number the operator
     *                           did not write
     * @param targetedRefresh    the targeted-refresh capability, or {@code null} when this context has no
     *                           usable analytics pool to refresh with
     */
    public StatisticsService(String repositoryId,
                             StatisticsAvailability declared,
                             ScanCoordinator coordinator,
                             StatisticsDiagnosticsSource diagnosticsSource,
                             FreshnessThreshold freshnessThreshold,
                             TargetedRefreshService targetedRefresh) {
        this.repositoryId = repositoryId == null ? "" : repositoryId.trim();
        this.declared = Objects.requireNonNull(declared, "declared");
        this.coordinator = coordinator;
        this.diagnosticsSource = diagnosticsSource;
        this.freshnessThreshold = Objects.requireNonNull(freshnessThreshold, "freshnessThreshold");
        this.targetedRefresh = targetedRefresh;
    }

    /** A service with a working coordinator and a pool view. */
    public static StatisticsService of(String repositoryId,
                                       ScanCoordinator coordinator,
                                       StatisticsDiagnosticsSource diagnosticsSource) {
        Objects.requireNonNull(coordinator, "coordinator");
        return new StatisticsService(repositoryId, coordinator.availability(), coordinator, diagnosticsSource,
                FreshnessThreshold.defaults(), null);
    }

    /**
     * A service that can also refresh one ItemType and judge freshness against the configured threshold.
     *
     * <p>This is the production shape. The targeted capability is handed in rather than built here so the
     * gate, the engine and the pool stay owned by the one place that created them.
     */
    public static StatisticsService of(String repositoryId,
                                       ScanCoordinator coordinator,
                                       StatisticsDiagnosticsSource diagnosticsSource,
                                       FreshnessThreshold freshnessThreshold,
                                       TargetedRefreshService targetedRefresh) {
        Objects.requireNonNull(coordinator, "coordinator");
        return new StatisticsService(repositoryId, coordinator.availability(), coordinator, diagnosticsSource,
                freshnessThreshold, targetedRefresh);
    }

    /** The analytics feature is switched off; nothing was constructed. */
    public static StatisticsService disabled(String repositoryId, String reason) {
        return disabled(repositoryId, reason, FreshnessThreshold.defaults());
    }

    /** The feature is switched off, with the configured freshness threshold still reported. */
    public static StatisticsService disabled(String repositoryId,
                                             String reason,
                                             FreshnessThreshold freshnessThreshold) {
        return new StatisticsService(repositoryId, StatisticsAvailability.featureDisabled(reason), null, null,
                freshnessThreshold, null);
    }

    /** The feature is wanted but no scan can run; the reason is sanitized. */
    public static StatisticsService unavailable(String repositoryId, String reason) {
        return unavailable(repositoryId, reason, FreshnessThreshold.defaults());
    }

    /** The feature is wanted but unusable, with the configured freshness threshold still reported. */
    public static StatisticsService unavailable(String repositoryId,
                                                String reason,
                                                FreshnessThreshold freshnessThreshold) {
        return new StatisticsService(repositoryId, StatisticsAvailability.unavailable(reason), null, null,
                freshnessThreshold, null);
    }

    public String repositoryId() {
        return repositoryId;
    }

    /** The coordinator, when one exists; exposed for the context's own integration and for diagnostics. */
    public Optional<ScanCoordinator> coordinator() {
        return Optional.ofNullable(coordinator);
    }

    @Override
    public StatisticsAvailability availability() {
        return coordinator == null ? declared : coordinator.availability();
    }

    @Override
    public Optional<StatisticsSnapshot> snapshot() {
        return coordinator == null ? Optional.empty() : coordinator.snapshot();
    }

    /**
     * The freshness judgement of the latest published snapshot, against the CONFIGURED threshold.
     *
     * <p>A pure read: one timestamp from the snapshot the coordinator already published, compared with
     * {@code cache.statistics.ttl.seconds}. It touches no pool, reads no metadata and starts nothing, so a
     * GET that renders it cannot cause database work. A stale snapshot is still returned by
     * {@link #snapshot()} - the judgement is reported beside the value, never instead of it.
     */
    @Override
    public Freshness freshness(Instant now) {
        return freshnessThreshold.judge(snapshot(), now);
    }

    /** The configured freshness threshold this service judges with, for the configuration/doctor output. */
    public FreshnessThreshold freshnessThreshold() {
        return freshnessThreshold;
    }

    /**
     * Refreshes ONE ItemType through this context's targeted capability, or refuses when there is none.
     *
     * <p>This service is the published entry point a route holds; the capability itself is owned by the
     * context as a resource. Delegation is total: the capability returns a value for every state, so this
     * method never throws for an operator-caused condition.
     */
    @Override
    public TargetedRefreshResult refreshItemType(int itemTypeId) {
        if (targetedRefresh == null) {
            return TargetedRefreshResult.unavailable(itemTypeId,
                    "this repository has no usable analytics pool, so a single ItemType cannot be refreshed");
        }
        return targetedRefresh.refresh(itemTypeId);
    }

    /** The targeted detail published for one ItemType, or empty when there is none. */
    @Override
    public Optional<TargetedItemTypeDetail> targetedDetail(int itemTypeId) {
        return targetedRefresh == null ? Optional.empty() : targetedRefresh.detail(itemTypeId);
    }

    /** Every targeted detail published in this context, in ItemType id order. */
    @Override
    public List<TargetedItemTypeDetail> targetedDetails() {
        return targetedRefresh == null ? List.of() : targetedRefresh.details();
    }

    /** True while the targeted capability is measuring an ItemType; a diagnostics fact, not a gate. */
    @Override
    public boolean isTargetedRefreshInFlight() {
        return targetedRefresh != null && targetedRefresh.isRefreshInFlight();
    }

    /** How many ItemTypes have a published targeted detail in this context. */
    @Override
    public int targetedDetailCount() {
        return targetedRefresh == null ? 0 : targetedRefresh.detailCount();
    }

    /** The targeted-refresh capability, for diagnostics; empty when this context has none. */
    public Optional<TargetedRefreshService> targetedRefresh() {
        return Optional.ofNullable(targetedRefresh);
    }

    @Override
    public ScanStatus progress() {
        return coordinator == null ? ScanStatus.idle(repositoryId) : coordinator.progress();
    }

    @Override
    public boolean isScanInFlight() {
        return coordinator != null && coordinator.isScanInFlight();
    }

    @Override
    public ScanStartResult requestScan() {
        if (coordinator == null) {
            // A closed context keeps its coordinator (so status and snapshots stay readable), and that
            // coordinator answers CLOSED itself; this branch is the "nothing was ever constructed" case,
            // where the only honest answer is that no scan can be started.
            return ScanStartResult.UNAVAILABLE;
        }
        return coordinator.requestScan();
    }

    @Override
    public boolean cancelScan() {
        return coordinator != null && coordinator.cancelScan();
    }

    @Override
    public boolean awaitScanCompletion(Duration timeout) throws InterruptedException {
        if (coordinator == null) {
            return true;
        }
        return coordinator.awaitScanCompletion(timeout);
    }

    @Override
    public StatisticsDiagnostics diagnostics() {
        StatisticsAvailability availability = availability();
        if (diagnosticsSource == null) {
            // A usable service with no pool view (the feature is off, or nothing was constructed) must not be
            // described as an unavailable repository: the availability verdict is copied verbatim.
            return availability.available()
                    ? StatisticsDiagnostics.withoutPool(availability, "this service has no pool view")
                    : (availability.enabled()
                            ? StatisticsDiagnostics.unavailable(availability.reason())
                            : StatisticsDiagnostics.disabled(availability.reason()));
        }
        try {
            StatisticsDiagnostics diagnostics = diagnosticsSource.diagnostics(availability);
            if (diagnostics != null) {
                return diagnostics;
            }
        } catch (Throwable failure) {
            // A diagnostics endpoint must keep answering. The note names the failure TYPE only, never its
            // message, so nothing raw can leak through this path.
            return StatisticsDiagnostics.withoutPool(availability,
                    "the pool diagnostics view failed (" + failure.getClass().getSimpleName() + ")");
        }
        return StatisticsDiagnostics.withoutPool(availability, "the pool diagnostics view returned nothing");
    }

    /** Cancels the running scan and joins its threads. Idempotent. */
    @Override
    public void close() {
        if (coordinator != null) {
            coordinator.close();
        }
    }

    @Override
    public String toString() {
        return "StatisticsService[repository=" + repositoryId + ", " + availability()
                + (coordinator == null ? ", no coordinator" : ", " + coordinator.progress()) + "]";
    }
}
