package com.mraibo.cminsight.connection;

/**
 * Point-in-time pool state.
 *
 * <p>{@code available + leased + creating + retiring + quarantined} is the number of capacity slots
 * currently consumed and can never exceed {@link #configuredSize}.
 *
 * <h2>Why quarantine exists</h2>
 *
 * A resource whose {@code close()} threw has an <em>uncertain</em> physical outcome: the exception does
 * not prove that the underlying CM session or JDBC connection is gone. Such a resource must therefore
 * keep consuming its capacity slot instead of authorising a replacement, or the pool could exceed the
 * configured physical hard bound. Those slots are reported as {@link #quarantined}; a non-zero value
 * means the pool is degraded, which is the safe direction.
 *
 * <h2>Creation and close counters are deliberately specific</h2>
 *
 * Initial population and replacement creation are different events, so they are counted separately and
 * there is no "reconnect" alias: only the future CM/JDBC adapter can say whether a creation was a
 * reconnect. Close attempts, successes and failures are likewise separated, because "attempted to
 * close" and "provably closed" are not the same statement.
 */
public record PoolMetrics(
        String name,
        int configuredSize,
        int available,
        int leased,
        int creating,
        int retiring,
        int quarantined,
        long borrowCount,
        long borrowTimeoutCount,
        double averageBorrowWaitMs,
        double maxBorrowWaitMs,
        long createAttempts,
        long initialCreations,
        long replacementCreations,
        long created,
        long createFailures,
        long closeAttempts,
        long closeSuccesses,
        long closeFailures,
        long validationFailures,
        long ageRotations,
        long operationRotations,
        long unhealthyRotations,
        long automaticUsages,
        long explicitOperations) {

    /** Capacity slots consumed right now. Never greater than {@link #configuredSize}. */
    public int capacityInUse() {
        return available + leased + creating + retiring + quarantined;
    }

    /**
     * True when at least one slot is quarantined, i.e. the pool is running below its configured
     * capacity because a close outcome is uncertain. Visible so the degraded state cannot be silent.
     */
    public boolean degraded() {
        return quarantined > 0;
    }

    /** Total usage recorded against resources: automatic borrow/use/close cycles plus explicit calls. */
    public long operations() {
        return automaticUsages + explicitOperations;
    }
}
