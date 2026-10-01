package com.mraibo.cminsight.metadata;

/**
 * One ItemType with every read-only property V1/V2 shows.
 *
 * <p>An immutable record of JDK types only: the IBM CM adapter maps the SDK object to this immediately,
 * so no {@code com.ibm} type and no live SDK object ever leaves the adapter, and the metadata cache can
 * hold this safely (architecture rule 9: cache data, never live SDK objects).
 *
 * <h2>Three classification fields, deliberately</h2>
 *
 * <ul>
 *   <li>{@link #classification} - the raw IBM classification as a readable name. IBM's own four values
 *       are item, resource item, document model and document part.</li>
 *   <li>{@link #rawClassification} - the underlying numeric value, kept so an operator can see exactly
 *       what the server said even when it is a value this build does not know.</li>
 *   <li>{@link #businessClassification} - the CM Insight label produced by
 *       {@link com.mraibo.cminsight.config.ClassificationRules}. Never a hard-coded business rule: the
 *       rules are configuration, so a customer's own naming scheme is supported without a code change,
 *       and no SAP/NON-SAP rule is baked in.</li>
 * </ul>
 *
 * <h2>Unknown values stay visible</h2>
 *
 * <p>Where IBM exposes a numeric code this build does not know, the readable field carries
 * {@code UNKNOWN(<value>)} rather than a guess, a blank or a plausible-looking neighbour. A wrong label
 * is worse than an obviously unknown one: it would be believed. The numeric field always carries the
 * exact value the server returned.
 *
 * @param name                   the ItemType name
 * @param description            the description, or an empty string
 * @param itemTypeId             the integer id, taken from IBM's full-width id accessor
 * @param classification         the raw IBM classification name, or {@code UNKNOWN(<value>)}
 * @param rawClassification      the exact numeric classification the server returned
 * @param businessClassification the CM Insight classification label
 * @param xdoClassId             the XDO class id, or an empty string when the ItemType has none
 * @param xdoClassName           the XDO class name, or an empty string
 * @param defaultRm              the default resource manager code, rendered as text; empty when unset
 * @param collectionCode         the default collection code; empty when unset
 * @param versionControl         the readable version-control name, or {@code UNKNOWN(<value>)}
 * @param versionControlCode     the exact numeric version-control value
 * @param versioningType         the readable versioning-type name, or {@code UNKNOWN(<value>)}
 * @param versioningTypeCode     the exact numeric versioning-type value
 * @param legacyRetentionSummary the ItemType-level legacy/default retention summary when IBM exposes
 *                               one, or an empty string when it does not
 * @param retentionPolicyName    the assigned retention policy name, or an empty string when none is
 *                               assigned
 */
public record ItemTypeInfo(
        String name,
        String description,
        int itemTypeId,
        String classification,
        int rawClassification,
        String businessClassification,
        String xdoClassId,
        String xdoClassName,
        String defaultRm,
        String collectionCode,
        String versionControl,
        int versionControlCode,
        String versioningType,
        int versioningTypeCode,
        String legacyRetentionSummary,
        String retentionPolicyName) {

    public ItemTypeInfo {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        classification = classification == null ? "" : classification;
        businessClassification = businessClassification == null ? "" : businessClassification;
        xdoClassId = xdoClassId == null ? "" : xdoClassId;
        xdoClassName = xdoClassName == null ? "" : xdoClassName;
        defaultRm = defaultRm == null ? "" : defaultRm;
        collectionCode = collectionCode == null ? "" : collectionCode;
        versionControl = versionControl == null ? "" : versionControl;
        versioningType = versioningType == null ? "" : versioningType;
        legacyRetentionSummary = legacyRetentionSummary == null ? "" : legacyRetentionSummary;
        retentionPolicyName = retentionPolicyName == null ? "" : retentionPolicyName;
    }

    /** The list projection of this ItemType. */
    public ItemTypeSummary summary() {
        return new ItemTypeSummary(name, description, itemTypeId, classification, businessClassification,
                retentionPolicyName);
    }

    /** True when IBM assigns this ItemType a retention policy. */
    public boolean hasRetentionPolicy() {
        return !retentionPolicyName.isEmpty();
    }
}
