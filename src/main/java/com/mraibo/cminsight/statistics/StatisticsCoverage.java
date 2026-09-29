package com.mraibo.cminsight.statistics;

/**
 * How much of the frozen ItemType list a scan (or a finished snapshot) actually accounting for.
 *
 * <h2>Why a snapshot needs this next to its totals</h2>
 *
 * <p>A total is published only when a scan reached normal terminal completion, and it may still contain
 * ItemTypes that failed or were only partially measured. Without this record a consumer sees
 * {@code logicalItemsTotal = 12,340,000} and has no way to know whether that came from all 100 ItemTypes or
 * from the 98 that happened to succeed - which is exactly the "subtotal presented as a complete total"
 * defect. {@link #complete()} is the single-answer question ("is this every ItemType?") and the four counts
 * are the explanation.
 *
 * <p>The counts are consistently about ITEM TYPES, never about items:
 * {@code requested + failed + partial} describes the frozen list, {@link #completedItemTypes()} is how many
 * of them a measurement was attempted for, and {@link #succeededItemTypes()} is how many came back fully
 * measured. A failed ItemType contributes nothing to any item total and is never counted as zero items.
 *
 * @param requestedItemTypes how many ItemTypes the scan froze at start
 * @param completedItemTypes how many were attempted (normal completion leaves this equal to requested)
 * @param failedItemTypes    how many measurements failed outright
 * @param partialItemTypes   how many produced a total but not every window metric
 */
public record StatisticsCoverage(int requestedItemTypes,
                                 int completedItemTypes,
                                 int failedItemTypes,
                                 int partialItemTypes) {

    public StatisticsCoverage {
        if (requestedItemTypes < 0 || completedItemTypes < 0 || failedItemTypes < 0 || partialItemTypes < 0) {
            throw new IllegalArgumentException("coverage counts must not be negative");
        }
        if (completedItemTypes > requestedItemTypes) {
            throw new IllegalArgumentException("completedItemTypes (" + completedItemTypes
                    + ") cannot exceed requestedItemTypes (" + requestedItemTypes + ")");
        }
        if (failedItemTypes + partialItemTypes > completedItemTypes) {
            throw new IllegalArgumentException("failed + partial (" + (failedItemTypes + partialItemTypes)
                    + ") cannot exceed completedItemTypes (" + completedItemTypes + ")");
        }
    }

    /** Coverage of a scan that has not started: nothing requested, nothing completed. */
    public static StatisticsCoverage none() {
        return new StatisticsCoverage(0, 0, 0, 0);
    }

    /** Coverage of a scan that attempted every frozen ItemType. */
    public static StatisticsCoverage of(int requestedItemTypes, int failedItemTypes, int partialItemTypes) {
        return new StatisticsCoverage(requestedItemTypes, requestedItemTypes, failedItemTypes,
                partialItemTypes);
    }

    /** ItemTypes that were measured completely: attempted, minus failures, minus partials. */
    public int succeededItemTypes() {
        return completedItemTypes - failedItemTypes - partialItemTypes;
    }

    /** ItemTypes that are not yet attempted. */
    public int remainingItemTypes() {
        return requestedItemTypes - completedItemTypes;
    }

    /**
     * True when every frozen ItemType was visited AND every one of them was measured completely.
     *
     * <p>Deliberately stricter than "the scan finished": a finished scan with one failed ItemType has
     * {@code complete() == false}, which is what stops a partial total from being presented as a complete
     * one.
     */
    public boolean complete() {
        return completedItemTypes == requestedItemTypes && failedItemTypes == 0 && partialItemTypes == 0;
    }

    @Override
    public String toString() {
        return "StatisticsCoverage[requested=" + requestedItemTypes
                + ", completed=" + completedItemTypes
                + ", failed=" + failedItemTypes
                + ", partial=" + partialItemTypes
                + (complete() ? ", complete" : "") + "]";
    }
}
