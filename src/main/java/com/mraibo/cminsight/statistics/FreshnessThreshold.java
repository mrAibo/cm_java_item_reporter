package com.mraibo.cminsight.statistics;

import com.mraibo.cminsight.config.AppConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The one reader of {@code cache.statistics.ttl.seconds}: a freshness <em>threshold</em>, and nothing else.
 *
 * <h2>What this setting is not</h2>
 *
 * <p>It is not a second cache, not an eviction rule and not an implicit refresh trigger. The latest
 * completed {@link StatisticsSnapshot} published by the scan coordinator stays the single authoritative
 * in-memory statistics truth; this type only turns a configured number of seconds into a {@link Freshness}
 * {@linkplain #judge(Optional, Instant) judgement} about that snapshot. Two consequences the goal makes
 * load-bearing, and which the shapes here enforce rather than describe:
 *
 * <ul>
 *   <li>a threshold of {@code 0} means "any non-zero age is stale" and still serves the value - nothing is
 *       discarded because a threshold passed;</li>
 *   <li>judging is a pure function of a timestamp and a duration. This type holds no pool, no engine and no
 *       supplier, so no GET can reach a database through it. That is why the reader is a value type rather
 *       than a service that "checks" the cache.</li>
 * </ul>
 *
 * <h2>Range and default</h2>
 *
 * <pre>
 *   cache.statistics.ttl.seconds   default 300, 0..86400
 * </pre>
 *
 * <p>Read through {@link AppConfig#getInt(String, int, int, int)}, the same strict idiom
 * {@code cm.pool.*}, {@code jdbc.pool.*} and {@code statistics.*} use, so a malformed or out-of-range value
 * is refused at startup with the key named instead of being clamped into something the operator did not
 * write.
 *
 * @param threshold the freshness period; {@link Duration#ZERO} means any non-zero age is stale
 */
public record FreshnessThreshold(Duration threshold) {

    /** The configuration key this type is the only reader of. */
    public static final String KEY = "cache.statistics.ttl.seconds";

    /** Documented default: 300 seconds. */
    public static final int DEFAULT_SECONDS = 300;

    /** Documented minimum: 0 seconds, meaning "any non-zero age is stale". */
    public static final int MIN_SECONDS = 0;

    /** Documented maximum: 24 hours. */
    public static final int MAX_SECONDS = 86_400;

    public FreshnessThreshold {
        Objects.requireNonNull(threshold, "threshold");
        if (threshold.isNegative()) {
            throw new IllegalArgumentException(KEY + " must not be negative but was " + threshold);
        }
        if (threshold.toSeconds() > MAX_SECONDS) {
            throw new IllegalArgumentException(KEY + " must be at most " + MAX_SECONDS + " seconds but was "
                    + threshold.toSeconds());
        }
    }

    /**
     * Reads the configured threshold, or the documented default when the key is absent.
     *
     * @throws com.mraibo.cminsight.config.ConfigException when the value is not an integer or is outside
     *         {@code 0..86400}
     */
    public static FreshnessThreshold from(AppConfig config) {
        Objects.requireNonNull(config, "config");
        return ofSeconds(config.getInt(KEY, DEFAULT_SECONDS, MIN_SECONDS, MAX_SECONDS));
    }

    /** The documented default over an absent key. */
    public static FreshnessThreshold defaults() {
        return ofSeconds(DEFAULT_SECONDS);
    }

    /**
     * The documented default for a caller that already holds a parsed value.
     *
     * @throws IllegalArgumentException when the range is violated; an out-of-range value is a programming
     *         error here, because a configuration value went through {@link AppConfig} first
     */
    public static FreshnessThreshold ofSeconds(long seconds) {
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException(KEY + " must be between " + MIN_SECONDS + " and "
                    + MAX_SECONDS + " but was " + seconds);
        }
        return new FreshnessThreshold(Duration.ofSeconds(seconds));
    }

    /** The configured period in whole seconds, for a payload or a doctor line. */
    public long seconds() {
        return threshold.toSeconds();
    }

    /** Judges one known capture instant; the age is clamped at zero for a future timestamp. */
    public Freshness judge(Instant capturedAt, Instant now) {
        return Freshness.of(capturedAt, threshold, now);
    }

    /**
     * Judges the latest published snapshot.
     *
     * <p>An absent snapshot is {@link Freshness#none(Duration)}, deliberately NOT stale: a repository that
     * has never completed a scan should read "no completed scan yet", not a staleness warning about data
     * that does not exist. A present snapshot is judged on its own {@code capturedAt} - the instant the
     * coordinator published it, which is data the snapshot already carries - so no second timestamp is kept
     * anywhere.
     */
    public Freshness judge(Optional<StatisticsSnapshot> snapshot, Instant now) {
        Objects.requireNonNull(now, "now");
        StatisticsSnapshot current = snapshot == null ? null : snapshot.orElse(null);
        return current == null ? Freshness.none(threshold) : Freshness.of(current.capturedAt(), threshold, now);
    }

    /** The not-yet-scanned judgement, for a caller with no snapshot at all. */
    public Freshness none() {
        return Freshness.none(threshold);
    }

    /** One line for the doctor/configuration output; carries no credential, URL or path. */
    public String describe() {
        return KEY + "=" + seconds() + "s";
    }

    @Override
    public String toString() {
        return "FreshnessThreshold[" + describe() + "]";
    }
}
