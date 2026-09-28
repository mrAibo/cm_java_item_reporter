package com.mraibo.cminsight.statistics;

import java.time.Instant;

public record ItemTypeStatistics(
        String repositoryId,
        String itemTypeName,
        Instant capturedAt,
        MetricValue logicalItems,
        MetricValue createdToday,
        MetricValue createdLast7Days,
        MetricValue createdLast30Days,
        MetricValue createdCurrentYear,
        MetricValue versions,
        MetricValue parts,
        long durationMs,
        String source,
        String status,
        String errorMessage) {
}
