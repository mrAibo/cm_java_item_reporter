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
 * <h2>Unknown values stay visible, and unmapped numeric codes are never presented as numbers</h2>
 *
 * <p>The retention type, the period unit and the expiration action are IBM enumerations whose numeric
 * codes are not recoverable from the SDK's class files: the SDK exposes the constants
 * ({@code DK_ICM_RETENTION_TYPE}, {@code DK_ICM_POLICY_TIME_UNIT}, {@code DK_ICM_EXPIRATION_ACTION_TYPE})
 * but carries no numeric code field on them, so no CM number for a constant can be read out of the SDK at
 * all. Where this build cannot map a value with certainty the readable field carries
 * {@code UNKNOWN(<constant>)} - IBM's own constant identity, which is certain - and the numeric field
 * carries {@code null}, meaning <em>no numeric CM code has been established</em>. A guessed unit or code
 * would silently mis-state how long customer data is kept, which is the one mistake a retention viewer
 * must never make.
 *
 * <p>{@code null} is therefore the only representation of "unmapped" for
 * {@link #retentionTypeCode()} and {@link #expirationActionCode()}: not {@code -1}, not {@code 0}, and
 * never the enumeration's declaration ordinal, which is a position in the SDK's class file and not a CM
 * code.
 *
 * <p>An explicitly supplied numeric code is preserved exactly as given, <strong>positive or
 * negative</strong>. Goal 02A additionally normalised every negative value to {@code null} under the
 * assertion that "a CM enum code can never be negative"; that sign rule was never established by SDK
 * evidence. The SDK's enumeration classes carry no numeric code field at all - only the constants
 * themselves - so no CM code range is known, and inventing a sign restriction is the same class of guess
 * as inventing a number. "Do not guess" cuts both ways: this record does not present a number it was not
 * given, and it does not discard a number it was given. A caller that has no established mapping passes
 * {@code null} explicitly; that is the whole absent representation.
 *
 * @param name                the policy name
 * @param description         the policy description, or an empty string
 * @param policyId            the integer policy id, or -1 when IBM does not expose one
 * @param retentionType       the readable retention type, or {@code UNKNOWN(<constant>)}
 * @param retentionTypeCode   the numeric CM retention-type code, or {@code null} when this build has no
 *                            established mapping for IBM's constant; a supplied code is preserved
 *                            verbatim, including a negative one (this build knows no CM code range)
 * @param retentionEnabled    IBM's "retention is enabled" flag, exactly as reported
 * @param retentionPeriod     the retention period as a number and its unit, for display
 * @param retentionPeriodValue the retention period as a number, or -1 when not applicable
 * @param retentionUnit       the readable period unit, or {@code UNKNOWN(<constant>)}
 * @param expirationEnabled   IBM's "expiration is enabled" flag, exactly as reported
 * @param expirationPeriod    the expiration period as a number and its unit, for display
 * @param expirationPeriodValue the expiration period as a number, or -1 when not applicable
 * @param expirationUnit      the readable period unit, or {@code UNKNOWN(<constant>)}
 * @param expirationAction    the readable expiration action, or {@code UNKNOWN(<constant>)}
 * @param expirationActionCode the numeric CM expiration-action code, or {@code null} when this build has
 *                            no established mapping for IBM's constant; a supplied code is preserved
 *                            verbatim, including a negative one
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
        Integer retentionTypeCode,
        boolean retentionEnabled,
        String retentionPeriod,
        int retentionPeriodValue,
        String retentionUnit,
        boolean expirationEnabled,
        String expirationPeriod,
        int expirationPeriodValue,
        String expirationUnit,
        String expirationAction,
        Integer expirationActionCode,
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
