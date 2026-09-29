package com.mraibo.cminsight.statistics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One completed scan's result: immutable, self-consistent, and published atomically or not at all.
 *
 * <h2>The publication rule this type exists for</h2>
 *
 * <p>While a scan runs, the previous completed snapshot remains the visible one - it is not cleared,
 * mutated or half-replaced. When the scan reaches normal terminal completion after visiting its frozen
 * ItemType list, exactly ONE new snapshot is built and published with a single atomic reference store, even
 * when some ItemTypes came back ERROR; the failures are then described by
 * {@link #partialFailureCount()} and {@link #coverage()} rather than by a missing snapshot.
 *
 * <p>A catastrophic coordinator failure, an overall-timeout abort, a repository switch or a cancellation
 * publishes NOTHING: no half-built object is ever visible, and the previous completed snapshot stays the
 * current answer. That is why there is no public constructor that takes a partially filled result map:
 * {@link #of} takes the frozen list and the finished per-ItemType results together, and a caller that has
 * not finished cannot build a snapshot at all.
 *
 * <h2>No persistent history</h2>
 *
 * <p>This goal keeps one snapshot per repository context, not a timeline. The record is immutable, but the
 * holder replaces it; nothing accumulates.
 *
 * @param repositoryId       the repository the snapshot belongs to
 * @param scanId             the scan that produced it, monotonically increasing per context
 * @param capturedAt         when the snapshot was published
 * @param scanStartedAt      when the scan started
 * @param scanDurationMs     how long the scan took
 * @param anchorDate         the single database current date every window of this snapshot was anchored on
 * @param perItemType        one result per frozen ItemType, in the frozen order, immutable
 * @param totals             overall totals, grouped by the metadata business classification
 * @param coverage           how much of the frozen list is accounted for, and how completely
 * @param partialFailureCount ItemTypes that were not measured completely (failed + partial)
 */
public record StatisticsSnapshot(String repositoryId,
                                 long scanId,
                                 Instant capturedAt,
                                 Instant scanStartedAt,
                                 long scanDurationMs,
                                 LocalDate anchorDate,
                                 List<ItemTypeStatistics> perItemType,
                                 StatisticsTotals totals,
                                 StatisticsCoverage coverage,
                                 int partialFailureCount) {

    public StatisticsSnapshot {
        repositoryId = repositoryId == null ? "" : repositoryId.trim();
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(scanStartedAt, "scanStartedAt");
        Objects.requireNonNull(anchorDate, "anchorDate");
        Objects.requireNonNull(perItemType, "perItemType");
        Objects.requireNonNull(totals, "totals");
        Objects.requireNonNull(coverage, "coverage");
        perItemType = List.copyOf(perItemType);
        if (scanDurationMs < 0) {
            throw new IllegalArgumentException("scanDurationMs must not be negative");
        }
        if (partialFailureCount < 0) {
            throw new IllegalArgumentException("partialFailureCount must not be negative");
        }
    }

    /**
     * Builds the ONE snapshot of a scan that visited its whole frozen list.
     *
     * <p>The totals, the coverage and the partial-failure count are derived here rather than accepted from
     * the caller, so a snapshot cannot claim a coverage that contradicts the results it carries. This is the
     * only factory the coordinator uses, which is what makes "totals always report coverage/partial-failure
     * state" structural instead of a convention.
     *
     * @param frozenItemTypeCount how many ItemTypes the scan froze at start; must equal {@code results.size()}
     * @throws IllegalArgumentException when the result count does not match the frozen list, i.e. when a
     *         caller tries to publish a partial scan
     */
    public static StatisticsSnapshot of(String repositoryId,
                                        long scanId,
                                        Instant capturedAt,
                                        Instant scanStartedAt,
                                        long scanDurationMs,
                                        LocalDate anchorDate,
                                        int frozenItemTypeCount,
                                        List<ItemTypeStatistics> results) {
        Objects.requireNonNull(results, "results");
        if (results.size() != frozenItemTypeCount) {
            // Refusing here is the last line of defence of the publication rule: a scan that did not visit
            // every frozen ItemType must not become a snapshot at all.
            throw new IllegalArgumentException("refusing to publish a partial snapshot: " + results.size()
                    + " result(s) for a frozen list of " + frozenItemTypeCount + " ItemType(s)");
        }
        List<ItemTypeStatistics> copy = List.copyOf(results);
        StatisticsTotals totals = StatisticsTotals.from(copy);
        int failed = 0;
        int partial = 0;
        for (ItemTypeStatistics result : copy) {
            switch (result.status()) {
                case ERROR -> failed++;
                case PARTIAL -> partial++;
                case OK -> { }
            }
        }
        StatisticsCoverage coverage = StatisticsCoverage.of(copy.size(), failed, partial);
        return new StatisticsSnapshot(repositoryId, scanId, capturedAt, scanStartedAt, scanDurationMs,
                anchorDate, copy, totals, coverage, failed + partial);
    }

    /** True when every frozen ItemType of this snapshot was measured completely. */
    public boolean complete() {
        return coverage.complete();
    }

    /** The result of one ItemType by name, or empty when the scan did not cover it. */
    public Optional<ItemTypeStatistics> itemType(String itemTypeName) {
        if (itemTypeName == null) {
            return Optional.empty();
        }
        for (ItemTypeStatistics result : perItemType) {
            if (result.itemTypeName().equals(itemTypeName)) {
                return Optional.of(result);
            }
        }
        return Optional.empty();
    }

    /** The results that failed, for a consumer that wants to explain an incomplete total. */
    public List<ItemTypeStatistics> failures() {
        List<ItemTypeStatistics> failures = new ArrayList<>(0);
        for (ItemTypeStatistics result : perItemType) {
            if (result.failed()) {
                failures.add(result);
            }
        }
        return List.copyOf(failures);
    }

    /** Human-readable summary of how complete this snapshot is, for a log line or a banner. */
    public String coverageDescription() {
        if (complete()) {
            return "all " + coverage.requestedItemTypes() + " ItemType(s) measured";
        }
        return coverage.requestedItemTypes() + " ItemType(s) requested, "
                + coverage.succeededItemTypes() + " complete, "
                + coverage.partialItemTypes() + " partial, "
                + coverage.failedItemTypes() + " failed";
    }

    @Override
    public String toString() {
        return "StatisticsSnapshot[repository=" + repositoryId + ", scan=" + scanId
                + ", captured=" + capturedAt
                + ", anchor=" + anchorDate
                + ", totals=" + totals
                + ", " + coverageDescription() + "]";
    }
}
