package com.mraibo.cminsight.connection;

/**
 * Point-in-time pool state.
 *
 * <p>{@code available + leased + creating + retiring} is the number of capacity slots currently
 * accounted for and can never exceed {@link #configuredSize}.
 *
 * <p>Creation counters double as the reconnect counters required by ARCHITECTURE.md: replacing a
 * retired resource is exactly the reconnect attempt.
 */
public record PoolMetrics(
        String name,
        int configuredSize,
        int available,
        int leased,
        int creating,
        int retiring,
        long borrowCount,
        long borrowTimeoutCount,
        double averageBorrowWaitMs,
        double maxBorrowWaitMs,
        long createAttempts,
        long created,
        long createFailures,
        long closed,
        long validationFailures,
        long ageRotations,
        long operationRotations,
        long unhealthyRotations,
        long operations) {

    /** Capacity slots accounted for right now. Never greater than {@link #configuredSize}. */
    public int capacityInUse() {
        return available + leased + creating + retiring;
    }

    /** Reconnect attempts: creations attempted while replacing or growing toward capacity. */
    public long reconnectAttempts() {
        return createAttempts;
    }

    /** Reconnect successes. */
    public long reconnectSuccesses() {
        return created;
    }

    /** Reconnect failures. */
    public long reconnectFailures() {
        return createFailures;
    }
}
