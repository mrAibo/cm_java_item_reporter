package com.mraibo.cminsight.metadata;

/**
 * One ItemType in a list, without the detail fields a list does not need.
 *
 * <p>Separate from {@link ItemTypeInfo} so a list of several hundred ItemTypes does not carry every
 * detail field, and so the web layer's list endpoint and its detail endpoint have distinct shapes rather
 * than one overloaded one. Both are immutable records of JDK types only - no IBM CM type appears here,
 * which is what lets the whole list be produced, cached and rendered without an SDK.
 *
 * @param name                   the ItemType name as IBM CM reports it
 * @param description            the ItemType description, or an empty string when IBM CM has none
 * @param itemTypeId             the integer ItemType id
 * @param classification         the raw IBM classification name, never guessed
 * @param businessClassification the CM Insight classification label from {@code ClassificationRules}
 * @param retentionPolicyName    the assigned retention policy name, or an empty string when none is
 *                               assigned
 */
public record ItemTypeSummary(
        String name,
        String description,
        int itemTypeId,
        String classification,
        String businessClassification,
        String retentionPolicyName) {

    public ItemTypeSummary {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        classification = classification == null ? "" : classification;
        businessClassification = businessClassification == null ? "" : businessClassification;
        retentionPolicyName = retentionPolicyName == null ? "" : retentionPolicyName;
    }
}
