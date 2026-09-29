package com.mraibo.cminsight.statistics;

import com.mraibo.cminsight.connection.PoolMetrics;
import com.mraibo.cminsight.core.CloseState;

import java.util.Objects;

/**
 * The safe, value-only view of the analytics JDBC pool and the scan, for a diagnostics endpoint.
 *
 * <h2>Plain numbers, never a JDBC object</h2>
 *
 * <p>{@code Connection}, {@code Statement}, {@code ResultSet} and the driver itself never leave the
 * database layer, and this record is how the web layer can still describe the pool: counters, a close state
 * and, at most, one sanitized error sentence. There is no JDBC URL, no user name, no schema and no raw
 * exception message in it - the fields simply do not exist, so an accident cannot leak them.
 *
 * <p>{@link #lastError()} is expected to be the pool factory's own sanitized text (a fixed operation label
 * plus SQLState/vendor code) or an empty string, and is bounded and control-character-stripped here as
 * well, because a diagnostics page is exactly the surface where a driver message would be believed.
 *
 * @param enabled               whether the analytics feature is switched on
 * @param available             whether a scan can be served right now
 * @param availabilityReason    sanitized reason when it cannot
 * @param poolName              the pool's diagnostic name, or an empty string when there is no pool
 * @param configuredPoolSize    the hard connection bound, 0 when there is no pool
 * @param capacityInUse         borrowed + idle + creating + retiring + quarantined slots
 * @param availableSlots        idle connections ready to be borrowed
 * @param leased                connections currently on lease
 * @param creating              connections being created right now
 * @param retiring              connections being closed right now
 * @param quarantined           slots whose physical outcome is unknown and which stay consumed
 * @param createAttempts        connection creation attempts
 * @param created               connections successfully created
 * @param createFailures        attempts that produced no connection
 * @param createQuarantineFailures attempts whose failure also cost the pool a slot
 * @param closeAttempts         close attempts
 * @param closeSuccesses        closes that returned normally
 * @param closeFailures         closes that threw, i.e. quarantined the slot
 * @param borrowCount           borrow attempts (including refused ones)
 * @param borrowTimeoutCount    borrows that ran out of the borrow timeout
 * @param closeState            the pool's shutdown state, or an empty string when there is no pool
 * @param degraded              true when at least one slot is quarantined
 * @param openedConnections     physical connections opened by the factory in total
 * @param liveConnections       physical connections believed open right now
 * @param peakLiveConnections   highest number of physical connections ever live at once, i.e. the observed
 *                              hard bound
 * @param lastError             one sanitized error sentence, or an empty string
 */
public record StatisticsDiagnostics(boolean enabled,
                                    boolean available,
                                    String availabilityReason,
                                    String poolName,
                                    int configuredPoolSize,
                                    int capacityInUse,
                                    int availableSlots,
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
                                    String closeState,
                                    boolean degraded,
                                    long openedConnections,
                                    long liveConnections,
                                    long peakLiveConnections,
                                    String lastError) {

    static final int MAX_TEXT_LENGTH = 200;

    public StatisticsDiagnostics {
        availabilityReason = SanitizedText.clean(availabilityReason, MAX_TEXT_LENGTH);
        poolName = SanitizedText.clean(poolName, 128);
        closeState = SanitizedText.clean(closeState, 32);
        lastError = SanitizedText.clean(lastError, MAX_TEXT_LENGTH);
    }

    /** The feature is switched off: no pool exists and none was ever created. */
    public static StatisticsDiagnostics disabled(String reason) {
        return new StatisticsDiagnostics(false, false, reason, "", 0, 0, 0, 0, 0, 0, 0,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "", false, 0L, 0L, 0L, "");
    }

    /** The feature is enabled but there is no usable pool (no driver, no credential, bad URL, ...). */
    public static StatisticsDiagnostics unavailable(String reason) {
        return new StatisticsDiagnostics(true, false, reason, "", 0, 0, 0, 0, 0, 0, 0,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "", false, 0L, 0L, 0L, "");
    }

    /**
     * The analytics half is usable, but this particular object owns no pool to describe.
     *
     * <p>Deliberately NOT {@link #unavailable(String)}: a bare coordinator runs scans perfectly well and
     * only lacks the pool view, so reporting {@code available=false} would make a diagnostics route
     * announce a healthy repository as an unavailable one. The counters stay zero and the pool name is
     * empty, which is the literal truth for an object with no pool, and {@code note} explains the gap.
     */
    public static StatisticsDiagnostics withoutPool(StatisticsAvailability availability, String note) {
        Objects.requireNonNull(availability, "availability");
        return new StatisticsDiagnostics(availability.enabled(), availability.available(),
                availability.reason(), "", 0, 0, 0, 0, 0, 0, 0,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "", false, 0L, 0L, 0L, note);
    }

    /**
     * Maps the pool's own counters plus the factory's physical facts into the safe view.
     *
     * @param availability         the analytics availability verdict
     * @param metrics              a point-in-time {@link PoolMetrics} snapshot
     * @param closeState           the pool's shutdown state
     * @param openedConnections    physical connections opened in total
     * @param liveConnections      physical connections believed open
     * @param peakLiveConnections  the observed physical peak
     * @param lastError            already-sanitized error text, or an empty string
     */
    public static StatisticsDiagnostics of(StatisticsAvailability availability,
                                          PoolMetrics metrics,
                                          CloseState closeState,
                                          long openedConnections,
                                          long liveConnections,
                                          long peakLiveConnections,
                                          String lastError) {
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(metrics, "metrics");
        return new StatisticsDiagnostics(
                availability.enabled(),
                availability.available(),
                availability.reason(),
                metrics.name(),
                metrics.configuredSize(),
                metrics.capacityInUse(),
                metrics.available(),
                metrics.leased(),
                metrics.creating(),
                metrics.retiring(),
                metrics.quarantined(),
                metrics.createAttempts(),
                metrics.created(),
                metrics.createFailures(),
                metrics.createQuarantineFailures(),
                metrics.closeAttempts(),
                metrics.closeSuccesses(),
                metrics.closeFailures(),
                metrics.borrowCount(),
                metrics.borrowTimeoutCount(),
                closeState == null ? "" : closeState.name(),
                metrics.degraded(),
                openedConnections,
                liveConnections,
                peakLiveConnections,
                lastError);
    }

    /** One line for a log or a doctor report; carries no URL, no user and no schema. */
    public String describe() {
        if (!available) {
            return "analytics unavailable" + (availabilityReason.isEmpty() ? "" : ": " + availabilityReason);
        }
        return poolName + ": " + capacityInUse + "/" + configuredPoolSize + " slot(s) in use (leased=" + leased
                + ", idle=" + availableSlots + ", creating=" + creating + ", retiring=" + retiring
                + ", quarantined=" + quarantined + "), physical live=" + liveConnections
                + ", peak=" + peakLiveConnections + ", state=" + closeState
                + (degraded ? " [DEGRADED]" : "")
                + (lastError.isEmpty() ? "" : ", last error: " + lastError);
    }

    @Override
    public String toString() {
        return "StatisticsDiagnostics[" + describe() + "]";
    }
}
