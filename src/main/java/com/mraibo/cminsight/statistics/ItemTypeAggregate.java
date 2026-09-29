package com.mraibo.cminsight.statistics;

import java.util.Objects;

/**
 * One ItemType's aggregate, exactly as the aggregate SQL returned it.
 *
 * <h2>What the number means</h2>
 *
 * <p>{@link #logicalItems()} is the count of DISTINCT ItemIDs, deduplicated across versions AND across
 * every expected root segment. It is never {@code COUNT(*)} over an ICMUT root table: all versions of one
 * item share the ItemID, so a row count would multiply an item by its version count, and a multi-segment
 * ItemType would additionally be counted per segment. The deduplication is the SQL layer's obligation; this
 * record only carries the result, so a caller can neither re-derive nor reinterpret it.
 *
 * <h2>Why the windows are {@link MetricValue}s and the total is a plain long</h2>
 *
 * <p>The total is a plain {@code long} because reaching this record at all means the aggregate query ran:
 * a failure throws instead (see {@link StatisticsEngine#aggregate}), so an absent total is expressed as an
 * ERROR result for the whole ItemType rather than as a null field here.
 *
 * <p>The four window metrics can each be absent even when the query succeeded: if a window boundary cannot
 * be encoded from the documented ItemID date rules, that metric is {@link MetricValue#unavailable()} while
 * the total stays AVAILABLE. That is the goal's rule - a provable total must not be thrown away because a
 * date boundary is unrepresentable - and it is why the windows are not plain longs.
 *
 * <p>Versions and Parts are deliberately NOT here. They stay {@code MetricValue.UNAVAILABLE} until the
 * documented research gate, and {@link ItemTypeStatistics} forces that regardless of what this record
 * carries, so no engine can quietly introduce them.
 *
 * @param logicalItems        distinct ItemIDs across versions and across all expected root segments
 * @param createdToday        items whose ItemID date falls in {@code [anchor, anchor + 1)}
 * @param createdLast7Days    items whose ItemID date falls in {@code [anchor - 6, anchor + 1)}
 * @param createdLast30Days   items whose ItemID date falls in {@code [anchor - 29, anchor + 1)}
 * @param createdCurrentYear  items whose ItemID date falls in {@code [Jan 1, Jan 1 next year)}
 */
public record ItemTypeAggregate(long logicalItems,
                                MetricValue createdToday,
                                MetricValue createdLast7Days,
                                MetricValue createdLast30Days,
                                MetricValue createdCurrentYear) {

    public ItemTypeAggregate {
        if (logicalItems < 0) {
            throw new IllegalArgumentException("logicalItems must not be negative but was " + logicalItems);
        }
        Objects.requireNonNull(createdToday, "createdToday");
        Objects.requireNonNull(createdLast7Days, "createdLast7Days");
        Objects.requireNonNull(createdLast30Days, "createdLast30Days");
        Objects.requireNonNull(createdCurrentYear, "createdCurrentYear");
    }

    @Override
    public String toString() {
        return "ItemTypeAggregate[logicalItems=" + logicalItems
                + ", today=" + createdToday
                + ", last7=" + createdLast7Days
                + ", last30=" + createdLast30Days
                + ", year=" + createdCurrentYear
                + "]";
    }
}
