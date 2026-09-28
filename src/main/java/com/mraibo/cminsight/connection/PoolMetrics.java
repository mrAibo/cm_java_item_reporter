package com.mraibo.cminsight.connection;

public record PoolMetrics(
        int configuredSize,
        int available,
        int leased,
        long borrowCount,
        double averageBorrowWaitMs,
        double maxBorrowWaitMs,
        long created,
        long closed,
        long validationFailures) {
}
