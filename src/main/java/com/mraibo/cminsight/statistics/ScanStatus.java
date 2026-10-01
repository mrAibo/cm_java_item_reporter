package com.mraibo.cminsight.statistics;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * An immutable view of one scan's progress, or of the last scan's outcome.
 *
 * <p>A snapshot of numbers and instants only: reading status never waits for a query, never touches a JDBC
 * object and never blocks a worker. That is deliberate - a status endpoint may be polled while a scan runs,
 * and a status read that blocked on a scan's lock would make the endpoint's latency depend on the
 * database's.
 *
 * <h2>The phases and what each one means for publication</h2>
 *
 * <ul>
 *   <li>{@link Phase#IDLE} - no scan has run in this context yet;</li>
 *   <li>{@link Phase#RUNNING} - the frozen list is being visited. The PREVIOUS completed snapshot, if any,
 *       stays visible throughout;</li>
 *   <li>{@link Phase#COMPLETED} - every frozen ItemType was visited and ONE snapshot was published
 *       atomically. It may still contain failed or partial ItemTypes; see
 *       {@link StatisticsSnapshot#coverage()};</li>
 *   <li>{@link Phase#TIMED_OUT} - the overall scan deadline expired before the list was exhausted. NO
 *       snapshot was published, so the previous one remains the current answer;</li>
 *   <li>{@link Phase#CANCELLED} - a repository switch, a shutdown or an explicit cancel stopped the scan
 *       before it finished. NO snapshot was published;</li>
 *   <li>{@link Phase#FAILED} - the scan could not even reach the item loop (the database current date could
 *       not be read, or the coordinator failed). NO snapshot was published.</li>
 * </ul>
 *
 * <p>{@link #anchorDate()} is the single database date the scan used. It is {@code null} until the scan has
 * read it, which is exactly the window in which no item has been measured yet - so a consumer can never see
 * an anchor from one day next to totals from another.
 *
 * <p>The short accessor names ({@link #running()}, {@link #total()}, {@link #completed()},
 * {@link #failed()}, {@link #durationMillis()}, {@link #ratePerSecond()}, {@link #currentItemType()}) are
 * the canonical components; the longer spellings below are aliases for callers that prefer the explicit
 * form. They all read the same field, so they can never disagree.
 *
 * @param repositoryId        the repository the scan belongs to
 * @param phase               the scan's phase
 * @param running             true while a scan is actually running
 * @param scanId              monotonically increasing per repository context, starting at 1
 * @param total               size of the FROZEN ItemType list
 * @param completed           how many of them have been attempted
 * @param failed              how many of those failed outright
 * @param anchorDate          the database current date, or {@code null} before it was read
 * @param startedAt           when the scan started, or {@code null} while idle
 * @param finishedAt          when the scan reached a terminal phase, or {@code null} while running
 * @param durationMillis      elapsed milliseconds: live while running, final once terminal
 * @param ratePerSecond       current completion rate, 0 while nothing has completed
 * @param currentItemType     the ItemType most recently started, or an empty string
 * @param coverage            how much of the frozen list has been accounted for
 * @param failureReason       sanitized reason for TIMED_OUT / FAILED / CANCELLED, or an empty string
 */
public record ScanStatus(String repositoryId,
                         Phase phase,
                         boolean running,
                         long scanId,
                         int total,
                         int completed,
                         int failed,
                         LocalDate anchorDate,
                         Instant startedAt,
                         Instant finishedAt,
                         long durationMillis,
                         double ratePerSecond,
                         String currentItemType,
                         StatisticsCoverage coverage,
                         String failureReason) {

    /** The phase of one scan. See the type javadoc for what each phase means for publication. */
    public enum Phase {
        /** No scan has run yet in this context. */
        IDLE,
        /** A scan is visiting the frozen ItemType list. */
        RUNNING,
        /** The whole frozen list was visited and one snapshot was published atomically. */
        COMPLETED,
        /** The overall scan deadline expired; nothing was published. */
        TIMED_OUT,
        /** The scan was cancelled (switch, shutdown or explicit cancel); nothing was published. */
        CANCELLED,
        /** The scan failed before the item loop; nothing was published. */
        FAILED
    }

    static final int MAX_REASON_LENGTH = 200;

    public ScanStatus {
        repositoryId = repositoryId == null ? "" : repositoryId.trim();
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(coverage, "coverage");
        currentItemType = SanitizedText.clean(currentItemType, 128);
        failureReason = SanitizedText.clean(failureReason, MAX_REASON_LENGTH);
        if (ratePerSecond < 0 || durationMillis < 0) {
            throw new IllegalArgumentException("status durations and rates must not be negative");
        }
        if (total < 0 || completed < 0 || failed < 0 || completed > total || failed > completed) {
            throw new IllegalArgumentException("inconsistent scan counters: total=" + total
                    + ", completed=" + completed + ", failed=" + failed);
        }
    }

    /** The status of a context that has not run a scan yet. */
    public static ScanStatus idle(String repositoryId) {
        return new ScanStatus(repositoryId, Phase.IDLE, false, 0L, 0, 0, 0, null, null, null,
                0L, 0.0, "", StatisticsCoverage.none(), "");
    }

    /** True when the last scan (or the running one) is over, whatever the outcome. */
    public boolean terminal() {
        return phase != Phase.IDLE && phase != Phase.RUNNING;
    }

    /** True only when the last scan published a snapshot of the whole frozen list. */
    public boolean published() {
        return phase == Phase.COMPLETED;
    }

    /** Alias of {@link #running()}. */
    public boolean scanInFlight() {
        return running;
    }

    /** Alias of {@link #total()}. */
    public int totalItemTypes() {
        return total;
    }

    /** Alias of {@link #completed()}. */
    public int completedItemTypes() {
        return completed;
    }

    /** Alias of {@link #failed()}. */
    public int failedItemTypes() {
        return failed;
    }

    /** Alias of {@link #durationMillis()}. */
    public long durationMs() {
        return durationMillis;
    }

    /** Alias of {@link #ratePerSecond()}. */
    public double itemTypesPerSecond() {
        return ratePerSecond;
    }

    /** Alias of {@link #currentItemType()}. */
    public String currentItemTypeName() {
        return currentItemType;
    }

    /** Elapsed time, as a {@link Duration} for a caller that does not want to do millisecond arithmetic. */
    public Duration elapsed() {
        return Duration.ofMillis(durationMillis);
    }

    @Override
    public String toString() {
        return "ScanStatus[id=" + scanId + ", " + phase
                + (running ? ", running" : "")
                + ", " + completed + "/" + total + " (" + failed + " failed)"
                + (anchorDate == null ? "" : ", anchor=" + anchorDate)
                + (failureReason.isEmpty() ? "" : ", reason=" + failureReason) + "]";
    }
}
