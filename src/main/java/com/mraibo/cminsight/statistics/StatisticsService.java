package com.mraibo.cminsight.statistics;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The typed analytics service one repository context exposes: the read/refresh contract plus the pool view.
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
 * <p>{@link #close()} closes the coordinator, which cancels the running scan and joins its threads. A
 * repository context must register this service's coordinator as an owned resource AFTER the JDBC pool, so
 * that the scan is drained before the pool it borrows from is closed.
 */
public final class StatisticsService implements StatisticsRepository, AutoCloseable {

    private final String repositoryId;
    private final StatisticsAvailability declared;
    private final ScanCoordinator coordinator;
    private final StatisticsDiagnosticsSource diagnosticsSource;

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
        this.repositoryId = repositoryId == null ? "" : repositoryId.trim();
        this.declared = Objects.requireNonNull(declared, "declared");
        this.coordinator = coordinator;
        this.diagnosticsSource = diagnosticsSource;
    }

    /** A service with a working coordinator and a pool view. */
    public static StatisticsService of(String repositoryId,
                                       ScanCoordinator coordinator,
                                       StatisticsDiagnosticsSource diagnosticsSource) {
        Objects.requireNonNull(coordinator, "coordinator");
        return new StatisticsService(repositoryId, coordinator.availability(), coordinator, diagnosticsSource);
    }

    /** The analytics feature is switched off; nothing was constructed. */
    public static StatisticsService disabled(String repositoryId, String reason) {
        return new StatisticsService(repositoryId, StatisticsAvailability.featureDisabled(reason), null, null);
    }

    /** The feature is wanted but no scan can run; the reason is sanitized. */
    public static StatisticsService unavailable(String repositoryId, String reason) {
        return new StatisticsService(repositoryId, StatisticsAvailability.unavailable(reason), null, null);
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
