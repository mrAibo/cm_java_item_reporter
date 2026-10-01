package com.mraibo.cminsight.history;

import java.util.List;
import java.util.Objects;

/**
 * One stored history snapshot in full: the list row plus the per-ItemType detail it was captured with.
 *
 * <p>Everything a viewer or a report needs is here, which is the point of storing it: a historical report
 * must be renderable with <strong>zero</strong> IBM CM or repository-database reads. If a field was not
 * captured, the answer is "unknown" - never a live query to fill the gap.
 *
 * @param summary    the identity, timing, coverage and total, as listed
 * @param itemTypes  one captured row per ItemType, in the frozen order the scan used
 * @param warning    a sanitized, value-free note recorded at capture time, or empty
 */
public record HistoryDetail(HistorySummary summary, List<HistoryItemType> itemTypes, String warning) {

    public HistoryDetail {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(itemTypes, "itemTypes");
        itemTypes = List.copyOf(itemTypes);
        warning = warning == null ? "" : warning.trim();
        if (itemTypes.size() != summary.itemTypeCount()) {
            // Refusing here keeps the stored detail consistent with the row that summarises it: a detail that
            // disagrees with its own summary is worse than a missing one, because a report would render the
            // summary's coverage against a different set of rows.
            throw new IllegalArgumentException("a history detail must carry exactly the ItemType count its"
                    + " summary declares: " + itemTypes.size() + " row(s) for a summary of "
                    + summary.itemTypeCount());
        }
    }

    /** The identity of this entry. */
    public HistoryId id() {
        return summary.id();
    }

    /** True when a warning was recorded at capture time. */
    public boolean hasWarning() {
        return !warning.isEmpty();
    }

    /** A captured row by ItemType id, or null when the snapshot did not cover it. */
    public HistoryItemType itemTypeById(int itemTypeId) {
        for (HistoryItemType row : itemTypes) {
            if (row.itemTypeId() == itemTypeId) {
                return row;
            }
        }
        return null;
    }

    /** A short value-free description for diagnostics. */
    public String describe() {
        return summary.describe() + " with " + itemTypes.size() + " ItemType row(s)";
    }
}
