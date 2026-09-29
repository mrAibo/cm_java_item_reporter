package com.mraibo.cminsight.statistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The overall totals of one snapshot, grouped by the business classification already present in metadata.
 *
 * <h2>A total always travels with its coverage</h2>
 *
 * <p>{@link #logicalItemsTotal()} sums the distinct-ItemID totals of the ItemTypes that were actually
 * measured. It is NOT "the number of items in this repository" unless {@link #complete()} is true, and this
 * record makes that distinction impossible to drop: the four coverage counts and {@link #complete()} are
 * part of the same immutable value as the number. A consumer that wants to present a complete total has to
 * check {@link #complete()}; one that presents a subtotal has the failure counts right there to say so.
 *
 * <p>Failed ItemTypes contribute NOTHING - they are not counted as zero items, which would understate the
 * repository while looking authoritative. {@link #errorItemTypes()} is how they are visible instead.
 *
 * <p>Versions and Parts are present as {@link MetricValue#unavailable()} in every total, mirroring the
 * per-ItemType results: the goal does not produce them, and a total that simply omitted the fields would
 * let a later consumer assume zero.
 *
 * @param byBusinessClassification totals per metadata classification label, ordered by label
 * @param countedItemTypes         ItemTypes contributing to {@link #logicalItemsTotal()} (OK + PARTIAL)
 * @param availableItemTypes       ItemTypes measured completely (status OK)
 * @param partialItemTypes         ItemTypes with a total but at least one unavailable window metric
 * @param errorItemTypes           ItemTypes whose measurement failed and which contribute nothing
 * @param logicalItemsTotal        sum of the measured distinct-ItemID totals
 * @param versions                 always {@code UNAVAILABLE} in this goal
 * @param parts                    always {@code UNAVAILABLE} in this goal
 * @param complete                 true only when every ItemType of the snapshot was measured completely
 */
public record StatisticsTotals(List<ClassificationTotals> byBusinessClassification,
                               long countedItemTypes,
                               long availableItemTypes,
                               long partialItemTypes,
                               long errorItemTypes,
                               long logicalItemsTotal,
                               MetricValue versions,
                               MetricValue parts,
                               boolean complete) {

    public StatisticsTotals {
        Objects.requireNonNull(byBusinessClassification, "byBusinessClassification");
        byBusinessClassification = List.copyOf(byBusinessClassification);
        // Structural, exactly as in ItemTypeStatistics: no producer can put a number here in this goal.
        versions = MetricValue.unavailable();
        parts = MetricValue.unavailable();
    }

    /**
     * Aggregates the per-ItemType results of one snapshot.
     *
     * <p>Grouping is by {@code ItemTypeStatistics.businessClassification()}, i.e. the label metadata
     * already carries. Nothing here re-derives SAP/NON-SAP logic.
     */
    public static StatisticsTotals from(List<ItemTypeStatistics> results) {
        Objects.requireNonNull(results, "results");
        Map<String, long[]> byLabel = new TreeMap<>();
        long available = 0;
        long partial = 0;
        long errors = 0;
        long total = 0;
        for (ItemTypeStatistics result : results) {
            Objects.requireNonNull(result, "result");
            String label = result.businessClassification();
            long[] counts = byLabel.computeIfAbsent(label, key -> new long[4]);
            counts[0]++; // item types with this label
            switch (result.status()) {
                case OK -> available++;
                case PARTIAL -> partial++;
                case ERROR -> errors++;
            }
            if (result.totalAvailable()) {
                total += result.logicalItemsOrZero();
                counts[1] += result.logicalItemsOrZero();
            } else {
                counts[3]++;
            }
            if (result.status() == ItemTypeStatistics.Status.PARTIAL) {
                counts[2]++;
            }
        }
        List<ClassificationTotals> grouped = new ArrayList<>(byLabel.size());
        for (Map.Entry<String, long[]> entry : byLabel.entrySet()) {
            long[] counts = entry.getValue();
            grouped.add(new ClassificationTotals(entry.getKey(), counts[0], counts[1], counts[2], counts[3]));
        }
        long counted = available + partial;
        boolean complete = errors == 0 && partial == 0;
        return new StatisticsTotals(List.copyOf(grouped), counted, available, partial, errors, total,
                null, null, complete);
    }

    /** The totals of an empty result set: no ItemTypes, no items, and not "complete" in any useful sense. */
    public static StatisticsTotals empty() {
        return from(List.of());
    }

    /**
     * The totals for one classification label, or a zeroed entry when the label is absent.
     *
     * <p>Absent returns zeros AND {@code complete() == true}, which is the honest reading of "no ItemType
     * with this label existed in the scan" - there is nothing unmeasured about it.
     */
    public ClassificationTotals forClassification(String businessClassification) {
        String label = businessClassification == null ? "" : businessClassification.trim();
        for (ClassificationTotals totals : byBusinessClassification) {
            if (totals.businessClassification().equals(label)) {
                return totals;
            }
        }
        return new ClassificationTotals(label, 0L, 0L, 0L, 0L);
    }

    /** ItemTypes that were visited by the scan, whether or not they were measured successfully. */
    public long visitedItemTypes() {
        return availableItemTypes + partialItemTypes + errorItemTypes;
    }

    @Override
    public String toString() {
        return "StatisticsTotals[logicalItems=" + logicalItemsTotal
                + ", itemTypes=" + visitedItemTypes()
                + ", ok=" + availableItemTypes
                + ", partial=" + partialItemTypes
                + ", error=" + errorItemTypes
                + (complete ? ", complete" : ", INCOMPLETE")
                + ", classes=" + byBusinessClassification.size() + "]";
    }
}
