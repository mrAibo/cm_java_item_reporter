package com.mraibo.cminsight.retention;

import java.util.List;

/**
 * One retention policy with every read-only property V1/V2 shows.
 *
 * <p>An immutable record of JDK types only. The IBM CM adapter maps the SDK policy object to this
 * immediately, so no SDK type and no live object leaves the adapter and the metadata cache can hold this
 * safely.
 *
 * <h2>Read-only, and narrower than the SDK on purpose</h2>
 *
 * <p>IBM's policy object exposes setters and a policy-management API exposes add, delete and update.
 * None of those is representable here: retention administration is a later goal with its own security
 * review, and this record cannot carry a mutation even if a future caller wanted one.
 *
 * <h2>Unknown values stay visible</h2>
 *
 * <p>The retention type, the period unit and the expiration action are IBM enumerations whose numeric
 * codes are not recoverable from the SDK's class files. Where this build cannot map a value with
 * certainty the readable field carries {@code UNKNOWN(<value>)} and the numeric field carries exactly
 * what the server returned. A guessed unit would silently mis-state how long customer data is kept,
 * which is the one mistake a retention viewer must never make.
 *
 * @param name                the policy name
 * @param description         the policy description, or an empty string
 * @param policyId            the integer policy id, or -1 when IBM does not expose one
 * @param retentionType       the readable retention type, or {@code UNKNOWN(<value>)}
 * @param retentionTypeCode   the exact numeric retention-type value
 * @param retentionEnabled    IBM's "retention is enabled" flag, exactly as reported
 * @param retentionPeriod     the retention period as a number and its unit, for display
 * @param retentionPeriodValue the retention period as a number, or -1 when not applicable
 * @param retentionUnit       the readable period unit, or {@code UNKNOWN(<value>)}
 * @param expirationEnabled   IBM's "expiration is enabled" flag, exactly as reported
 * @param expirationPeriod    the expiration period as a number and its unit, for display
 * @param expirationPeriodValue the expiration period as a number, or -1 when not applicable
 * @param expirationUnit      the readable period unit, or {@code UNKNOWN(<value>)}
 * @param expirationAction    the readable expiration action, or {@code UNKNOWN(<value>)}
 * @param expirationActionCode the exact numeric expiration-action value
 * @param autoDeleteSchedule  the auto-delete schedule information, or an empty string when IBM has none
 * @param commitCount         the auto-delete commit count, or -1 when not applicable
 * @param maxRows             the auto-delete maximum rows/items, or -1 when not applicable
 * @param maxDuration         the auto-delete maximum duration, or -1 when not applicable
 * @param forceCheckIn        the auto-delete force-check-in flag, exactly as reported
 * @param assignedItemTypes   the ItemTypes assigned to this policy, sorted case-insensitively
 */
public record RetentionPolicyInfo(
        String name,
        String description,
        int policyId,
        String retentionType,
        int retentionTypeCode,
        boolean retentionEnabled,
        String retentionPeriod,
        int retentionPeriodValue,
        String retentionUnit,
        boolean expirationEnabled,
        String expirationPeriod,
        int expirationPeriodValue,
        String expirationUnit,
        String expirationAction,
        int expirationActionCode,
        String autoDeleteSchedule,
        int commitCount,
        int maxRows,
        int maxDuration,
        boolean forceCheckIn,
        List<String> assignedItemTypes) {

    public RetentionPolicyInfo {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        retentionType = retentionType == null ? "" : retentionType;
        retentionPeriod = retentionPeriod == null ? "" : retentionPeriod;
        retentionUnit = retentionUnit == null ? "" : retentionUnit;
        expirationPeriod = expirationPeriod == null ? "" : expirationPeriod;
        expirationUnit = expirationUnit == null ? "" : expirationUnit;
        expirationAction = expirationAction == null ? "" : expirationAction;
        autoDeleteSchedule = autoDeleteSchedule == null ? "" : autoDeleteSchedule;
        assignedItemTypes = assignedItemTypes == null ? List.of() : List.copyOf(assignedItemTypes);
    }

    /** True when IBM assigns at least one ItemType to this policy. */
    public boolean hasAssignedItemTypes() {
        return !assignedItemTypes.isEmpty();
    }

    /** True when neither retention nor expiration is enabled, so the policy has no effect yet. */
    public boolean inactive() {
        return !retentionEnabled && !expirationEnabled;
    }

    /** The same policy with a different set of assigned ItemTypes, still immutable. */
    public RetentionPolicyInfo withAssignedItemTypes(List<String> itemTypes) {
        return new RetentionPolicyInfo(name, description, policyId, retentionType, retentionTypeCode,
                retentionEnabled, retentionPeriod, retentionPeriodValue, retentionUnit, expirationEnabled,
                expirationPeriod, expirationPeriodValue, expirationUnit, expirationAction,
                expirationActionCode, autoDeleteSchedule, commitCount, maxRows, maxDuration, forceCheckIn,
                itemTypes);
    }
}
