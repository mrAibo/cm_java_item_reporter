package com.mraibo.cminsight.core;

import java.time.Duration;
import java.util.Objects;

/**
 * The validated, vendor-neutral bounds of one CM session pool, read once from configuration.
 *
 * <p>Deliberately a value: the adapter receives bounds that have already been range-checked, so a
 * misconfigured pool cannot reach the physical layer and a diagnostics endpoint can print exactly the
 * numbers the pool is running with. The keys, defaults and ranges are documented in
 * {@code conf/application.properties.example} and are the single source of truth for this record:
 *
 * <pre>
 *   cm.pool.size                 default 4,    1..64
 *   cm.pool.borrow.timeout.ms    default 5000, 1..600000
 *   cm.pool.max.age.minutes      default 30,   1..1440
 *   cm.pool.max.operations       default 1000, 1..1000000
 * </pre>
 *
 * @param size           hard maximum number of live CM sessions, at least 1
 * @param borrowTimeout  how long a borrow waits before failing with backpressure
 * @param maxAge         rotate a session once it is older than this
 * @param maxOperations  rotate a session after this many operations, or 0 for unlimited
 */
public record CmPoolSettings(int size, Duration borrowTimeout, Duration maxAge, long maxOperations) {

    public CmPoolSettings {
        if (size < 1) {
            throw new IllegalArgumentException("cm.pool.size must be at least 1 but was " + size);
        }
        Objects.requireNonNull(borrowTimeout, "borrowTimeout");
        if (borrowTimeout.isZero() || borrowTimeout.isNegative()) {
            throw new IllegalArgumentException("cm.pool.borrow.timeout.ms must be positive");
        }
        Objects.requireNonNull(maxAge, "maxAge");
        if (maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("cm.pool.max.age.minutes must be positive");
        }
        if (maxOperations < 0) {
            throw new IllegalArgumentException("cm.pool.max.operations must not be negative but was "
                    + maxOperations);
        }
    }

    /** The documented defaults, used when a key is absent. */
    public static CmPoolSettings defaults() {
        return new CmPoolSettings(4, Duration.ofSeconds(5), Duration.ofMinutes(30), 1000L);
    }

    /** True when sessions are rotated after a fixed operation count rather than never. */
    public boolean rotatesByOperations() {
        return maxOperations > 0L;
    }

    @Override
    public String toString() {
        return "CmPoolSettings[size=" + size
                + ", borrowTimeout=" + borrowTimeout.toMillis() + "ms"
                + ", maxAge=" + maxAge.toMinutes() + "min"
                + ", maxOperations=" + (rotatesByOperations() ? Long.toString(maxOperations) : "unlimited")
                + "]";
    }
}
