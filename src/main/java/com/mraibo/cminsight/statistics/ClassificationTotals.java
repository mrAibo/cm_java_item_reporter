package com.mraibo.cminsight.statistics;

/**
 * The part of a snapshot total that belongs to one {@code ItemTypeSummary.businessClassification()} label.
 *
 * <p>The label is CONSUMED from metadata, never re-derived: SAP/NON-SAP classification was fixed in Goal
 * 02A by {@code ClassificationRules}, the metadata layer already carries the resulting label on every
 * {@code ItemTypeSummary}, and a second implementation here would be a second answer to the same question.
 * A label that metadata left empty is grouped under the empty label rather than replaced by an invented
 * one, so nothing in this goal can disagree with the classification the operator sees elsewhere.
 *
 * <p>{@link #logicalItems()} sums only the ItemTypes whose total was actually measured. It is NOT a
 * complete subtotal for the class unless {@link #errorItemTypes()} is zero, which is why the failure
 * counts travel with it: a reader compares the two instead of assuming.
 *
 * @param businessClassification the label exactly as metadata reported it
 * @param itemTypes              how many ItemTypes of the scan carry this label
 * @param logicalItems           distinct-ItemID total across the ItemTypes of this label whose total was
 *                               measured
 * @param partialItemTypes       how many of them produced a total but not every window metric
 * @param errorItemTypes         how many of them failed and therefore contribute nothing
 */
public record ClassificationTotals(String businessClassification,
                                   long itemTypes,
                                   long logicalItems,
                                   long partialItemTypes,
                                   long errorItemTypes) {

    public ClassificationTotals {
        businessClassification = businessClassification == null ? "" : businessClassification.trim();
    }

    /** True when every ItemType with this label was measured completely. */
    public boolean complete() {
        return partialItemTypes == 0 && errorItemTypes == 0;
    }

    /** The label to display: the metadata label, or a placeholder when metadata carried none. */
    public String displayLabel() {
        return businessClassification.isEmpty() ? "(unclassified)" : businessClassification;
    }

    @Override
    public String toString() {
        return displayLabel() + "[itemTypes=" + itemTypes + ", logicalItems=" + logicalItems
                + ", partial=" + partialItemTypes + ", error=" + errorItemTypes + "]";
    }
}
