package com.mraibo.cminsight.ibm.internal;

import com.ibm.mm.sdk.common.DKConstantICM;
import com.ibm.mm.sdk.common.DKItemTypeDefICM;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM;

import java.util.Locale;

/**
 * Maps IBM CM numeric and enum values to readable names, and refuses to guess.
 *
 * <h2>The rule this class exists to enforce</h2>
 *
 * <p>IBM returns several of these fields as a numeric code whose meaning is not recoverable from the
 * SDK's class files. The reconnaissance report records that explicitly for the retention type, the
 * period unit and the expiration action. A plausible-looking guess would be the worst possible outcome
 * for a retention viewer: it would silently mis-state <em>how long customer data is kept</em>, and it
 * would be believed.
 *
 * <p>So the contract here is:
 *
 * <ul>
 *   <li>a value this class can map with documented certainty becomes its name;</li>
 *   <li><strong>anything else becomes {@code UNKNOWN(<value>)}</strong> - never a blank, never a nearby
 *       constant, never a presumed ordinal;</li>
 *   <li>the exact numeric code always travels beside the name in the DTO, so an operator can see what
 *       the server actually said even when this build does not know what it means.</li>
 * </ul>
 *
 * <h2>Interface constants are read through {@code DKConstantICM}</h2>
 *
 * <p>IBM declares its enumerations as fields on {@code DKConstantICM}; the class is named explicitly
 * rather than implementing the interface, so nothing in this adapter accidentally inherits hundreds of
 * unrelated SDK constants and the set of values it depends on is visible in one place.
 */
public final class IbmEnumNames {

    private IbmEnumNames() {
    }

    /**
     * The ItemType classification name for a numeric code.
     *
     * <p>The four values are IBM's own and are documented in the working read path, so this mapping is
     * certain. Anything else - a newer server, a value this build predates - is reported as
     * {@code UNKNOWN(<value>)}.
     */
    public static String classificationName(int value) {
        return switch (value) {
            case DKConstantICM.DK_ICM_ITEMTYPE_CLASS_ITEM -> "Item";
            case DKConstantICM.DK_ICM_ITEMTYPE_CLASS_RESOURCE_ITEM -> "Resource item";
            case DKConstantICM.DK_ICM_ITEMTYPE_CLASS_DOC_MODEL -> "Document model";
            case DKConstantICM.DK_ICM_ITEMTYPE_CLASS_DOC_PART -> "Document part";
            default -> unknown(value);
        };
    }

    /**
     * The version-control name for a numeric code.
     *
     * <p>Certain: IBM publishes the three {@code DK_ICM_VERSION_CONTROL_*} values as {@code int} while
     * {@code DKItemTypeDefICM.getVersionControl()} returns {@code short}, which is a documented type
     * difference rather than a different numbering.
     */
    public static String versionControlName(int value) {
        return switch (value) {
            case DKConstantICM.DK_ICM_VERSION_CONTROL_NEVER -> "Never";
            case DKConstantICM.DK_ICM_VERSION_CONTROL_ALWAYS -> "Always";
            case DKConstantICM.DK_ICM_VERSION_CONTROL_BY_APPLICATION -> "By application";
            default -> unknown(value);
        };
    }

    /**
     * The versioning-type name for a numeric code.
     *
     * <p>Certain: the three {@code short} constants are published on {@code DKConstantICM} beside the
     * accessor that returns them.
     */
    public static String versioningTypeName(int value) {
        return switch (value) {
            case DKConstantICM.DK_ICM_DOC_NO_VERSIONING -> "No versioning";
            case DKConstantICM.DK_ICM_ITEM_VERSIONING_OPTIMIZED -> "Optimized";
            case DKConstantICM.DK_ICM_ITEM_VERSIONING_FULL -> "Full";
            default -> unknown(value);
        };
    }

    /**
     * The retention-type name.
     *
     * <p>The SDK exposes this as an enum whose numeric mapping to the CM code is NOT recoverable from
     * the class files, so the readable field is reported as unknown and only the constant's own name is
     * exposed - see {@link #retentionTypeConstantName}. The DTO's numeric field is {@code null} rather
     * than a sentinel, so "no code established" cannot be read as a number.
     */
    public static String retentionTypeName(DKRetentionPolicyDefICM.DK_ICM_RETENTION_TYPE value) {
        return UNMAPPED;
    }

    /**
     * The certainty-free constant identity of a retention type, for example {@code FIXED_TIME}.
     *
     * <p>Deliberately separate from {@link #retentionTypeName}: this is what the SDK literally said, with
     * no claim about which CM code it corresponds to. It is exposed because an operator debugging a
     * policy is better served by IBM's own constant name than by a bare "unknown", as long as it is
     * never presented as the numeric code.
     */
    public static String retentionTypeConstantName(DKRetentionPolicyDefICM.DK_ICM_RETENTION_TYPE value) {
        return value == null ? "" : value.name();
    }

    /**
     * The period-unit name.
     *
     * <p>Same reasoning as {@link #retentionTypeName}: the period unit is an enum whose CM numeric
     * mapping is not recoverable, so nothing is guessed.
     */
    public static String policyTimeUnitName(DKRetentionPolicyDefICM.DK_ICM_POLICY_TIME_UNIT value) {
        return UNMAPPED;
    }

    /** The certainty-free constant identity of a period unit, for example {@code MONTH}. */
    public static String policyTimeUnitConstantName(DKRetentionPolicyDefICM.DK_ICM_POLICY_TIME_UNIT value) {
        return value == null ? "" : value.name();
    }

    /** The expiration-action name. Unmapped for the same reason as the retention type and unit. */
    public static String expirationActionName(DKRetentionPolicyDefICM.DK_ICM_EXPIRATION_ACTION_TYPE value) {
        return UNMAPPED;
    }

    /** The certainty-free constant identity of an expiration action, for example {@code AUTO_DELETE}. */
    public static String expirationActionConstantName(
            DKRetentionPolicyDefICM.DK_ICM_EXPIRATION_ACTION_TYPE value) {
        return value == null ? "" : value.name();
    }

    /**
     * The legacy ItemType-level retention summary.
     *
     * <p>{@code DKItemTypeDefICM} exposes a default retention as a number plus a unit, and the SDK
     * declares exactly one unit constant for it: {@code DK_ICM_RETENTION_UNIT_YEAR}, together with
     * {@code DK_ICM_ITEM_RETRENTION_NO_EXPIRE} (the typo is IBM's) as the "never expires" sentinel. Only
     * those two readings are certain, so only those two produce a sentence; every other value is
     * reported as an explicit unknown with both numbers preserved.
     */
    public static String legacyRetentionSummary(int retentionValue, int unitValue) {
        if (retentionValue == DKItemTypeDefICM.DK_ICM_ITEM_RETRENTION_NO_EXPIRE) {
            return "No expiry";
        }
        if (unitValue == DKItemTypeDefICM.DK_ICM_RETENTION_UNIT_YEAR) {
            return retentionValue + " year(s)";
        }
        return "UNKNOWN(retention=" + retentionValue + ", unit=" + unitValue + ")";
    }

    /**
     * The text used wherever a numeric value cannot be mapped with certainty.
     *
     * <p>Replaces the retired {@code UNMAPPED_CODE = -1} sentinel: the retention DTO's numeric
     * enum-code fields are nullable {@code Integer}s now, so "no numeric CM code was established" is
     * {@code null} and cannot be mistaken for a number, and no sentinel constant is left for a caller to
     * pass. {@code RetentionPolicyInfo.retentionTypeCode()} documents the null contract.
     */
    public static final String UNMAPPED = "UNKNOWN";

    /** The text for one specific unknown numeric value. Never a blank and never a guessed neighbour. */
    public static String unknown(long value) {
        return "UNKNOWN(" + value + ")";
    }

    /** Case-insensitive, locale-independent name ordering used by every list this adapter returns. */
    public static int byName(String left, String right) {
        int byName = String.CASE_INSENSITIVE_ORDER.compare(normalise(left), normalise(right));
        return byName != 0 ? byName : normalise(left).compareTo(normalise(right));
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim();
    }

    /** Trims to an empty string rather than returning {@code null}, so a DTO never carries a null. */
    public static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /** A short id rendered as text, with an empty string for the "unset" readings of the SDK. */
    public static String idText(int value) {
        return value <= 0 ? "" : Integer.toString(value);
    }

    /** Locale-independent upper-casing, for comparing configuration text. */
    public static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
