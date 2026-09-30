package com.mraibo.cminsight.statistics;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * How old a statistics result is, and whether that age exceeds the configured freshness threshold.
 *
 * <h2>A judgement about age, never a cache</h2>
 *
 * <p>{@code cache.statistics.ttl.seconds} is deliberately NOT a second cache and NOT an implicit refresh
 * trigger. The published {@link StatisticsSnapshot} remains the single authoritative in-memory statistics
 * truth and this record only <em>judges</em> it. Two consequences are load-bearing:
 *
 * <ul>
 *   <li>a stale result stays <strong>visible</strong> - {@link #stale()} is reported, and the value is
 *       still served. Nothing is discarded because a threshold passed;</li>
 *   <li>a GET never causes database work. Because freshness is a pure function of a timestamp and a
 *       threshold, computing it cannot start a scan, which is why this is a value type with no access to a
 *       pool rather than a service.</li>
 * </ul>
 *
 * <h2>Age is always reported, even when there is nothing to judge</h2>
 *
 * <p>When no snapshot exists yet, {@link #none()} reports {@link #known()} as false and
 * {@link #stale()} as false - "unknown" is deliberately not the same answer as "stale", because a caller
 * that treated them the same would show a staleness banner for a repository that has simply never been
 * scanned.
 *
 * @param known        true when a captured timestamp exists to judge
 * @param capturedAt   when the judged result was captured; null only when {@code known} is false
 * @param age          how long ago it was captured, never negative; null when {@code known} is false
 * @param threshold    the configured freshness period; {@link Duration#ZERO} means "any non-zero age is
 *                     stale"
 * @param stale        true when {@code known} and the age exceeds the threshold
 */
public record Freshness(boolean known,
                        Instant capturedAt,
                        Duration age,
                        Duration threshold,
                        boolean stale) {

    public Freshness {
        Objects.requireNonNull(threshold, "threshold");
        if (!known && (capturedAt != null || age != null)) {
            throw new IllegalArgumentException("an unknown freshness cannot carry a timestamp or an age");
        }
        if (age != null && age.isNegative()) {
            throw new IllegalArgumentException("age must not be negative but was " + age);
        }
        if (threshold.isNegative()) {
            throw new IllegalArgumentException("the freshness threshold must not be negative");
        }
    }

    /**
     * Nothing has been captured yet, so there is no age to judge.
     *
     * <p>Deliberately NOT stale: an operator who has never run a scan should see "no data yet", not a
     * staleness warning about data that does not exist.
     */
    public static Freshness none(Duration threshold) {
        return new Freshness(false, null, null, threshold, false);
    }

    /**
     * Judges {@code capturedAt} against {@code threshold} at {@code now}.
     *
     * <p>A capture time in the future - a clock adjustment, or a stored value from another host - is
     * clamped to an age of zero rather than reported as a negative age, because a negative age would make
     * "stale" unpredictable and is never a fact worth surfacing.
     *
     * <p>A threshold of {@link Duration#ZERO} means any non-zero age is stale, as the goal specifies. A
     * result captured in the same instant it is judged is therefore fresh even at zero, which is the only
     * reading of "0 means no freshness period" that does not declare a just-published result stale.
     */
    public static Freshness of(Instant capturedAt, Duration threshold, Instant now) {
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(now, "now");
        Duration age = Duration.between(capturedAt, now);
        if (age.isNegative()) {
            age = Duration.ZERO;
        }
        return new Freshness(true, capturedAt, age, threshold, age.compareTo(threshold) > 0);
    }

    /** Age in whole milliseconds for a payload, or empty when nothing has been captured. */
    public Optional<Long> ageMillis() {
        return age == null ? Optional.empty() : Optional.of(age.toMillis());
    }

    /** The configured threshold in whole seconds, for a payload or a diagnostics line. */
    public long thresholdSeconds() {
        return threshold.toSeconds();
    }

    /** A short value-free description for diagnostics. It names no repository, URL or credential. */
    public String describe() {
        if (!known) {
            return "no completed scan yet";
        }
        return (stale ? "stale" : "fresh") + ": captured " + age.toSeconds() + "s ago, threshold "
                + threshold.toSeconds() + "s";
    }
}
