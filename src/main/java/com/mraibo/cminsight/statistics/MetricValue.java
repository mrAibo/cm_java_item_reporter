package com.mraibo.cminsight.statistics;

public record MetricValue(Long value, Availability availability) {
    public enum Availability { AVAILABLE, UNAVAILABLE, ERROR }
    public static MetricValue available(long value) { return new MetricValue(value, Availability.AVAILABLE); }
    public static MetricValue unavailable() { return new MetricValue(null, Availability.UNAVAILABLE); }
}
