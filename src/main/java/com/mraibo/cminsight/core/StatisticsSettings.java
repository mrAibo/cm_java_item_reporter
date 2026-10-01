package com.mraibo.cminsight.core;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.ConfigException;

import java.time.Duration;
import java.util.Objects;

/**
 * The validated bounds of the analytics half: whether it is enabled at all, how many workers one scan may
 * use, and how long a single query and a whole scan may take.
 *
 * <pre>
 *   feature.statistics                  default true
 *   statistics.workers                  default min(4, jdbc.pool.size), 1..64
 *   statistics.query.timeout.seconds    default 60,   1..3600
 *   statistics.scan.timeout.seconds     default 1800, 1..86400
 * </pre>
 *
 * <h2>Workers greater than the pool is REFUSED, never clamped</h2>
 *
 * <p>{@code statistics.workers} is the number of workers a scan may run concurrently, and every
 * concurrent query owns one JDBC connection for its whole duration. A configured parallelism larger than
 * {@code jdbc.pool.size} can therefore never be delivered: the extra workers would spend their time
 * waiting for a borrow. Two dishonest alternatives were rejected - silently clamping to the pool size
 * (the operator reads a number the runtime does not use) and letting the scan run with workers that
 * cannot get a connection (the requested parallelism is reported as if it existed). So the pair is
 * checked here and the configuration is refused, naming both keys and both values.
 *
 * <p>The default is {@code min(4, jdbc.pool.size)} and is therefore never refused: a caller that does not
 * configure workers cannot create the contradiction. The default is computed from the actual pool size
 * rather than from the pool's default, so a pool of 2 gets 2 workers instead of a refused 4.
 *
 * <h2>Disabled is a first-class state</h2>
 *
 * <p>{@code feature.statistics=false} yields {@link #enabled()} {@code == false}. It is not an error and
 * it does not touch a repository: the IBM CM metadata and retention halves must stay usable with
 * statistics off, with no driver, with no credential and with an unreachable database. The coordinator is
 * simply never constructed over this settings value, and the statistics service reports
 * {@code enabled=false}.
 *
 * <p>This record holds no credential, no URL and no schema; it can be printed and rendered as-is, which
 * is what {@link #describe()} is for.
 *
 * @param enabled      whether the analytics feature is switched on
 * @param workers      fixed number of scan workers, 1..64, never greater than the JDBC pool size
 * @param queryTimeout per-query timeout applied through {@code Statement.setQueryTimeout}
 * @param scanTimeout  overall scan deadline
 */
public record StatisticsSettings(boolean enabled, int workers, Duration queryTimeout, Duration scanTimeout) {

    public static final String ENABLED_KEY = "feature.statistics";
    public static final String WORKERS_KEY = "statistics.workers";
    public static final String QUERY_TIMEOUT_KEY = "statistics.query.timeout.seconds";
    public static final String SCAN_TIMEOUT_KEY = "statistics.scan.timeout.seconds";

    /** Largest accepted worker count, matching the documented range. */
    public static final int MAX_WORKERS = 64;
    /** Default worker cap, used as {@code min(4, jdbc.pool.size)}. */
    public static final int DEFAULT_WORKERS = 4;
    /** Documented default of {@link #QUERY_TIMEOUT_KEY}, in seconds. */
    public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 60;
    /** Documented default of {@link #SCAN_TIMEOUT_KEY}, in seconds. */
    public static final int DEFAULT_SCAN_TIMEOUT_SECONDS = 1_800;

    public StatisticsSettings {
        if (workers < 1 || workers > MAX_WORKERS) {
            throw new IllegalArgumentException(WORKERS_KEY + " must be between 1 and " + MAX_WORKERS
                    + " but was " + workers);
        }
        Objects.requireNonNull(queryTimeout, "queryTimeout");
        if (queryTimeout.isZero() || queryTimeout.isNegative()) {
            throw new IllegalArgumentException(QUERY_TIMEOUT_KEY + " must be positive");
        }
        Objects.requireNonNull(scanTimeout, "scanTimeout");
        if (scanTimeout.isZero() || scanTimeout.isNegative()) {
            throw new IllegalArgumentException(SCAN_TIMEOUT_KEY + " must be positive");
        }
    }

    /**
     * Reads the analytics settings, checking the worker count against the JDBC pool it must borrow from.
     *
     * @throws ConfigException when a value is not an integer, is out of range, or when
     *                         {@code statistics.workers} exceeds {@code jdbc.pool.size}
     */
    public static StatisticsSettings from(AppConfig config, JdbcPoolSettings jdbcPool) {
        Objects.requireNonNull(jdbcPool, "jdbcPool");
        return from(config, jdbcPool.size());
    }

    /**
     * Reads the analytics settings, checking the worker count against the actual JDBC pool size.
     *
     * @param jdbcPoolSize the resolved {@code jdbc.pool.size}; the worker count may not exceed it
     * @throws ConfigException when a value is out of range, or when workers exceed the pool size
     */
    public static StatisticsSettings from(AppConfig config, int jdbcPoolSize) {
        Objects.requireNonNull(config, "config");
        if (jdbcPoolSize < 1) {
            // Not inventable here: the caller resolved it, so an impossible value is a programming error
            // rather than a configuration one, and clamping it would hide exactly the contradiction this
            // check exists to expose.
            throw new IllegalArgumentException("jdbcPoolSize must be at least 1 but was " + jdbcPoolSize);
        }
        boolean enabled = config.getBoolean(ENABLED_KEY, true);
        int defaultWorkers = Math.min(DEFAULT_WORKERS, jdbcPoolSize);
        int workers = config.getInt(WORKERS_KEY, defaultWorkers, 1, MAX_WORKERS);
        int queryTimeoutSeconds = config.getInt(QUERY_TIMEOUT_KEY, DEFAULT_QUERY_TIMEOUT_SECONDS, 1, 3_600);
        int scanTimeoutSeconds = config.getInt(SCAN_TIMEOUT_KEY, DEFAULT_SCAN_TIMEOUT_SECONDS, 1, 86_400);
        if (workers > jdbcPoolSize) {
            // Deliberately a refusal rather than a clamp, and deliberately naming BOTH keys: an operator
            // who reads "workers=8" in the configuration must be told that the pool of 4 is what makes it
            // impossible, not discover it later as unexplained borrow timeouts.
            throw new ConfigException(WORKERS_KEY + "=" + workers + " exceeds " + JdbcPoolSettings.SIZE_KEY
                    + "=" + jdbcPoolSize + ": a scan cannot run more workers than there are JDBC connections,"
                    + " and the requested parallelism is refused rather than silently reduced."
                    + " Set " + WORKERS_KEY + " to at most " + jdbcPoolSize + " or raise "
                    + JdbcPoolSettings.SIZE_KEY + ".");
        }
        return new StatisticsSettings(enabled, workers, Duration.ofSeconds(queryTimeoutSeconds),
                Duration.ofSeconds(scanTimeoutSeconds));
    }

    /** The documented defaults over a pool of the given size. */
    public static StatisticsSettings defaults(int jdbcPoolSize) {
        if (jdbcPoolSize < 1) {
            throw new IllegalArgumentException("jdbcPoolSize must be at least 1 but was " + jdbcPoolSize);
        }
        return new StatisticsSettings(true, Math.min(DEFAULT_WORKERS, jdbcPoolSize),
                Duration.ofSeconds(DEFAULT_QUERY_TIMEOUT_SECONDS),
                Duration.ofSeconds(DEFAULT_SCAN_TIMEOUT_SECONDS));
    }

    /**
     * The analytics feature switched off.
     *
     * <p>Still a valid, printable settings object with documented bounds, so a caller that only needs
     * "statistics are not part of this runtime" does not have to invent one - and so the disabled state
     * carries the same defaults it would have used had it been enabled.
     */
    public static StatisticsSettings disabled() {
        return new StatisticsSettings(false, DEFAULT_WORKERS,
                Duration.ofSeconds(DEFAULT_QUERY_TIMEOUT_SECONDS),
                Duration.ofSeconds(DEFAULT_SCAN_TIMEOUT_SECONDS));
    }

    public int queryTimeoutSeconds() {
        return (int) queryTimeout.toSeconds();
    }

    public int scanTimeoutSeconds() {
        return (int) scanTimeout.toSeconds();
    }

    /** One line for the doctor/configuration output; carries no credential and no URL. */
    public String describe() {
        return ENABLED_KEY + "=" + enabled
                + ", " + WORKERS_KEY + "=" + workers
                + ", " + QUERY_TIMEOUT_KEY + "=" + queryTimeoutSeconds()
                + ", " + SCAN_TIMEOUT_KEY + "=" + scanTimeoutSeconds();
    }

    @Override
    public String toString() {
        return "StatisticsSettings[enabled=" + enabled
                + ", workers=" + workers
                + ", queryTimeout=" + queryTimeoutSeconds() + "s"
                + ", scanTimeout=" + scanTimeoutSeconds() + "s"
                + "]";
    }
}
