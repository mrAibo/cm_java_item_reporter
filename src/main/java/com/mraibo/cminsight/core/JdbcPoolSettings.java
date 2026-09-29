package com.mraibo.cminsight.core;

import com.mraibo.cminsight.config.AppConfig;

import java.time.Duration;
import java.util.Objects;

/**
 * The validated, vendor-neutral bounds of the one analytics JDBC pool, read once from configuration.
 *
 * <p>Deliberately the same shape as {@link CmPoolSettings} and deliberately a value: the pool receives
 * bounds that have already been range-checked, so a misconfigured pool cannot reach the physical layer and
 * a diagnostics endpoint can print exactly the numbers the pool is running with. The keys, defaults and
 * ranges are the single source of truth for this record and are documented in
 * {@code conf/application.properties.example}:
 *
 * <pre>
 *   jdbc.pool.size                 default 4,    1..64
 *   jdbc.pool.borrow.timeout.ms    default 5000, 1..600000
 *   jdbc.pool.max.age.minutes      default 30,   1..1440
 *   jdbc.pool.max.operations       default 1000, 1..1000000
 * </pre>
 *
 * <p>Nothing here is a credential or a URL. The JDBC URL, user and password come from the repository
 * profile at connection time, never from this object, so a settings value can be logged and rendered
 * without redaction.
 *
 * <p>{@link #size()} is also the bound the scan coordinator is checked against: {@code statistics.workers}
 * is refused when it exceeds this size (see {@link StatisticsSettings#from(AppConfig, JdbcPoolSettings)}),
 * because a requested parallelism larger than the pool can never be delivered and silently clamping it
 * would misreport the configured behaviour.
 *
 * @param size          hard maximum number of live JDBC connections, at least 1
 * @param borrowTimeout how long a borrow waits before failing with backpressure
 * @param maxAge        rotate a connection once it is older than this
 * @param maxOperations rotate a connection after this many operations, or 0 for unlimited
 */
public record JdbcPoolSettings(int size, Duration borrowTimeout, Duration maxAge, long maxOperations) {

    public static final String SIZE_KEY = "jdbc.pool.size";
    public static final String BORROW_TIMEOUT_KEY = "jdbc.pool.borrow.timeout.ms";
    public static final String MAX_AGE_KEY = "jdbc.pool.max.age.minutes";
    public static final String MAX_OPERATIONS_KEY = "jdbc.pool.max.operations";

    /** Documented default of {@link #SIZE_KEY}. */
    public static final int DEFAULT_SIZE = 4;
    /** Documented default of {@link #BORROW_TIMEOUT_KEY}, in milliseconds. */
    public static final int DEFAULT_BORROW_TIMEOUT_MS = 5_000;
    /** Documented default of {@link #MAX_AGE_KEY}, in minutes. */
    public static final int DEFAULT_MAX_AGE_MINUTES = 30;
    /** Documented default of {@link #MAX_OPERATIONS_KEY}. */
    public static final long DEFAULT_MAX_OPERATIONS = 1_000L;

    /** Largest accepted pool size, matching {@code cm.pool.size}. */
    public static final int MAX_SIZE = 64;

    public JdbcPoolSettings {
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException(SIZE_KEY + " must be between 1 and " + MAX_SIZE
                    + " but was " + size);
        }
        Objects.requireNonNull(borrowTimeout, "borrowTimeout");
        if (borrowTimeout.isZero() || borrowTimeout.isNegative()) {
            throw new IllegalArgumentException(BORROW_TIMEOUT_KEY + " must be positive");
        }
        Objects.requireNonNull(maxAge, "maxAge");
        if (maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException(MAX_AGE_KEY + " must be positive");
        }
        if (maxOperations < 0) {
            throw new IllegalArgumentException(MAX_OPERATIONS_KEY + " must not be negative but was "
                    + maxOperations);
        }
    }

    /**
     * Reads the JDBC pool bounds from configuration, failing closed on every out-of-range value.
     *
     * <p>Uses the same strict integer readers as {@code cm.pool.*}, so a typo such as
     * {@code jdbc.pool.size=0} or a value above the documented maximum is a configuration error naming the
     * key, never a silently clamped number. No connection is opened, and no driver is loaded, by reading
     * configuration: this method is safe on the activation path of a repository whose database is
     * unreachable.
     *
     * @throws com.mraibo.cminsight.config.ConfigException when a value is not an integer or is out of range
     */
    public static JdbcPoolSettings from(AppConfig config) {
        Objects.requireNonNull(config, "config");
        int size = config.getInt(SIZE_KEY, DEFAULT_SIZE, 1, MAX_SIZE);
        int borrowTimeoutMs = config.getInt(BORROW_TIMEOUT_KEY, DEFAULT_BORROW_TIMEOUT_MS, 1, 600_000);
        int maxAgeMinutes = config.getInt(MAX_AGE_KEY, DEFAULT_MAX_AGE_MINUTES, 1, 1_440);
        long maxOperations = config.getLong(MAX_OPERATIONS_KEY, DEFAULT_MAX_OPERATIONS, 1L, 1_000_000L);
        return new JdbcPoolSettings(size, Duration.ofMillis(borrowTimeoutMs),
                Duration.ofMinutes(maxAgeMinutes), maxOperations);
    }

    /** The documented defaults, used when no configuration is available. */
    public static JdbcPoolSettings defaults() {
        return new JdbcPoolSettings(DEFAULT_SIZE, Duration.ofMillis(DEFAULT_BORROW_TIMEOUT_MS),
                Duration.ofMinutes(DEFAULT_MAX_AGE_MINUTES), DEFAULT_MAX_OPERATIONS);
    }

    public long borrowTimeoutMillis() {
        return borrowTimeout.toMillis();
    }

    public long maxAgeMinutes() {
        return maxAge.toMinutes();
    }

    /** True when connections are rotated after a fixed operation count rather than never. */
    public boolean rotatesByOperations() {
        return maxOperations > 0L;
    }

    /** One line for the doctor/configuration output; carries no credential and no URL. */
    public String describe() {
        return SIZE_KEY + "=" + size
                + ", " + BORROW_TIMEOUT_KEY + "=" + borrowTimeoutMillis()
                + ", " + MAX_AGE_KEY + "=" + maxAgeMinutes()
                + ", " + MAX_OPERATIONS_KEY + "="
                + (rotatesByOperations() ? Long.toString(maxOperations) : "unlimited");
    }

    @Override
    public String toString() {
        return "JdbcPoolSettings[size=" + size
                + ", borrowTimeout=" + borrowTimeout.toMillis() + "ms"
                + ", maxAge=" + maxAge.toMinutes() + "min"
                + ", maxOperations=" + (rotatesByOperations() ? Long.toString(maxOperations) : "unlimited")
                + "]";
    }
}
