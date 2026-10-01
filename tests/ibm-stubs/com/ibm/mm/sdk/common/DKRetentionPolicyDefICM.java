/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * ICM retention policy definition. Every accessor the Goal 02 retention read service needs is here.
 *
 * <p>Read members only: the real class also declares the matching setters ({@code setID},
 * {@code setName}, {@code setDescription}, {@code setRetentionType}, {@code setRetentionEnabled},
 * {@code setRetentionTimePeriod}, {@code setDefaultRetentionTimeUnit}, {@code setExpirationEnabled},
 * {@code setExpirationTimePeriod}, {@code setDefaultExpirationTimeUnit}, {@code setExpirationAction}
 * and the delete-expired-items setters). None is declared, so nothing in {@code src/ibm/java} can
 * mutate a policy definition.
 *
 * <p>Every numeric getter on this class returns {@code int}; no {@code short} return type exists on
 * it, and every flag uses the {@code is} prefix.
 */
public class DKRetentionPolicyDefICM implements DKMessageIdICM {

    public DKRetentionPolicyDefICM() {
        // no state in a compile stub
    }

    public DKRetentionPolicyDefICM(java.lang.String policyName) {
        // no state in a compile stub
    }

    public DKRetentionPolicyDefICM(DKRetentionPolicyDefICM source) {
        // no state in a compile stub
    }

    public int getID() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String getName() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String getDescription() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public DK_ICM_RETENTION_TYPE getRetentionType() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public boolean isRetentionEnabled() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public int getRetentionTimePeriod() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public DK_ICM_POLICY_TIME_UNIT getDefaultRetentionTimeUnit() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public boolean isExpirationEnabled() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public int getExpirationTimePeriod() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public DK_ICM_POLICY_TIME_UNIT getDefaultExpirationTimeUnit() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public DK_ICM_EXPIRATION_ACTION_TYPE getExpirationAction() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String getDeleteExpiredItemsScheduleInformation() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public int getDeleteExpiredItemsCommitCount() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public int getDeleteExpiredItemsMaximumRows() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public int getDeleteExpiredItemsMaximumDuration() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public boolean isDeleteExpiredItemsForceCheckInEnabled() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    /**
     * Retention type. The two constant names are verified from the real class file.
     *
     * <p>NOT RECOVERABLE FROM BYTECODE: the mapping between these constants (or their ordinals) and
     * the CM numeric retention-type code is not present in the class files and is not documented in
     * the reconnaissance report (section 12 item 3). Do NOT invent a number and do NOT assume
     * {@code ordinal()} is the CM code. Map only what is certain and render anything else as
     * {@code UNKNOWN(<value>)} (GOAL_02_IMPLEMENTATION_SPEC.md sections 1 and 6.1).
     */
    public enum DK_ICM_RETENTION_TYPE {

        FIXED_TIME,

        EVENT_DRIVEN
    }

    /**
     * Retention/expiration period unit. The four constant names are verified from the real class
     * file.
     *
     * <p>NOT RECOVERABLE FROM BYTECODE: the mapping between these constants (or their ordinals) and
     * the CM numeric time-unit code is not present in the class files and is not documented in the
     * reconnaissance report (section 12 item 3). Do NOT invent a number. Render anything that cannot
     * be mapped with certainty as {@code UNKNOWN(<value>)}.
     */
    public enum DK_ICM_POLICY_TIME_UNIT {

        DAY,

        WEEK,

        MONTH,

        YEAR
    }

    /**
     * Expiration action. The two constant names are verified from the real class file.
     *
     * <p>NOT RECOVERABLE FROM BYTECODE: the mapping between these constants (or their ordinals) and
     * the CM numeric expiration-action code is not present in the class files and is not documented
     * in the reconnaissance report (section 12 item 3). Do NOT invent a number. Render anything that
     * cannot be mapped with certainty as {@code UNKNOWN(<value>)}.
     */
    public enum DK_ICM_EXPIRATION_ACTION_TYPE {

        NO_ACTION,

        AUTO_DELETE
    }
}
