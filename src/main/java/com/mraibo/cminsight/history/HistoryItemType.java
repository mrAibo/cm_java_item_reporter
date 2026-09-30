package com.mraibo.cminsight.history;

import com.mraibo.cminsight.metadata.ItemTypeSummary;

import java.util.Objects;

/**
 * One captured ItemType row inside a stored history snapshot.
 *
 * <h2>Captured context, not live metadata</h2>
 *
 * <p>The classification and retention-policy name are copied here at scan time rather than looked up when
 * the row is displayed. A historical report has to be renderable with zero live reads, and a classification
 * that changed since the scan is part of the story the snapshot tells - re-deriving it would make an old
 * report silently disagree with itself.
 *
 * <h2>Versions and Parts are absent, not unavailable</h2>
 *
 * <p>They have no field at all. The live record forces them to {@code UNAVAILABLE} structurally because a
 * guessed number would be indistinguishable from a measurement; storage goes one step further and does not
 * persist a placeholder for a metric this project cannot produce. Goal 05 owns that research, and when it
 * lands the schema gains a column and a migration - which is the explicit, versioned change a stored read
 * model is supposed to require.
 *
 * @param itemTypeId             the integer ItemType id
 * @param itemTypeName           the ItemType name as IBM CM reported it
 * @param businessClassification the classification label present in metadata at scan time, or empty
 * @param retentionPolicyName    the assigned retention policy name at scan time, or empty
 * @param status                 the captured status: OK, PARTIAL or ERROR
 * @param logicalItems           the distinct-ItemID total
 * @param createdToday           items created today by ItemID date
 * @param createdLast7Days       items created in the last 7 days
 * @param createdLast30Days      items created in the last 30 days
 * @param createdCurrentYear     items created in the current calendar year
 * @param durationMs             how long this ItemType's measurement took
 * @param reason                 a sanitized, value-free failure note, or empty
 */
public record HistoryItemType(int itemTypeId,
                              String itemTypeName,
                              String businessClassification,
                              String retentionPolicyName,
                              Status status,
                              HistoryMetric logicalItems,
                              HistoryMetric createdToday,
                              HistoryMetric createdLast7Days,
                              HistoryMetric createdLast30Days,
                              HistoryMetric createdCurrentYear,
                              long durationMs,
                              String reason) {

    /** How completely this ItemType was measured, captured at scan time. */
    public enum Status {
        /** Every metric this goal produces was available. */
        OK,
        /** The total was available but at least one window metric was not. */
        PARTIAL,
        /** The measurement failed. */
        ERROR
    }

    public HistoryItemType {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(logicalItems, "logicalItems");
        Objects.requireNonNull(createdToday, "createdToday");
        Objects.requireNonNull(createdLast7Days, "createdLast7Days");
        Objects.requireNonNull(createdLast30Days, "createdLast30Days");
        Objects.requireNonNull(createdCurrentYear, "createdCurrentYear");
        itemTypeName = itemTypeName == null ? "" : itemTypeName.trim();
        businessClassification = businessClassification == null ? "" : businessClassification.trim();
        retentionPolicyName = retentionPolicyName == null ? "" : retentionPolicyName.trim();
        reason = reason == null ? "" : reason.trim();
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
    }

    /** True when this row's measurement failed. */
    public boolean failed() {
        return status == Status.ERROR;
    }

    /** The classification label, falling back to a stable placeholder so a report is never blank. */
    public String classificationLabel() {
        return businessClassification.isEmpty() ? "Unclassified" : businessClassification;
    }

    /** The retention policy name, or empty when none was assigned at scan time. */
    public String retentionPolicyOrEmpty() {
        return retentionPolicyName;
    }

    /** The captured name from metadata as a summary, for a report that renders a plain ItemType list. */
    public ItemTypeSummary asSummary() {
        return new ItemTypeSummary(itemTypeName, "", itemTypeId, businessClassification,
                businessClassification, retentionPolicyName);
    }
}
