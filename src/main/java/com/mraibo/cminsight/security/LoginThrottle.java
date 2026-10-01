package com.mraibo.cminsight.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Brute-force guard: counts consecutive failed authentication attempts per key and locks the key out
 * for a fixed period once the threshold is reached.
 *
 * <p>The key space is attacker-controlled in principle (one key per source address), so the
 * table is hard-bounded: it is pruned back to a low-water mark as soon as it exceeds
 * {@code maxTrackedKeys}, which makes the map's memory use a function of configuration rather
 * than of how many distinct addresses happen to connect.
 *
 * <p>Eviction prefers the oldest entry that is NOT currently locked out, so an ordinary key flood
 * cannot clear a live lockout. Boundedness still wins in the extreme case: if every tracked key is
 * locked - which happens immediately when {@code maxFailures} is 1 - the oldest locked entry is
 * evicted rather than letting the table grow without limit. Unbounded memory is the worse failure, so
 * the trade-off is deliberate: raise {@code maxTrackedKeys} for a very large or proxied client
 * population, and prefer a {@code maxFailures} above 1 so the unlocked buffer exists.
 *
 * <p>The clock is a {@link LongSupplier} of nanoseconds so lockout behaviour is deterministic in tests
 * without any sleeping.
 */
public final class LoginThrottle {

    private static final int MIN_TRACKED_KEYS = 16;
    private static final int MAX_KEY_LENGTH = 256;
    private static final int PRUNE_NUMERATOR = 3;
    private static final int PRUNE_DENOMINATOR = 4;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private static final class Entry {
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long lockedUntilNanos;
        private volatile long lastTouchedNanos;
    }

    private final int maxFailures;
    private final long lockoutNanos;
    private final int maxTrackedKeys;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public LoginThrottle(int maxFailures, Duration lockout, int maxTrackedKeys) {
        this(maxFailures, lockout, maxTrackedKeys, System::nanoTime);
    }

    /** Test seam: supply a deterministic nanosecond clock. */
    public LoginThrottle(int maxFailures, Duration lockout, int maxTrackedKeys, LongSupplier clock) {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("maxFailures must be >= 1");
        }
        Objects.requireNonNull(lockout, "lockout");
        if (lockout.isZero() || lockout.isNegative()) {
            throw new IllegalArgumentException("lockout must be positive");
        }
        if (maxTrackedKeys < MIN_TRACKED_KEYS) {
            throw new IllegalArgumentException("maxTrackedKeys must be >= " + MIN_TRACKED_KEYS);
        }
        this.maxFailures = maxFailures;
        this.lockoutNanos = lockout.toNanos();
        this.maxTrackedKeys = maxTrackedKeys;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** True while the key is locked out. An expired lockout resets the failure count. */
    public boolean isLocked(String key) {
        Entry entry = entries.get(key(key));
        if (entry == null) {
            return false;
        }
        long now = clock.getAsLong();
        if (entry.lockedUntilNanos > now) {
            entry.lastTouchedNanos = now;
            return true;
        }
        if (entry.lockedUntilNanos != 0L) {
            entry.failures.set(0);
            entry.lockedUntilNanos = 0L;
        }
        entry.lastTouchedNanos = now;
        return false;
    }

    /** Records one failed attempt and locks the key when the threshold is reached. */
    public void recordFailure(String key) {
        String normalized = key(key);
        long now = clock.getAsLong();
        Entry entry = entries.computeIfAbsent(normalized, ignored -> new Entry());
        entry.lastTouchedNanos = now;
        if (entry.failures.incrementAndGet() >= maxFailures) {
            entry.lockedUntilNanos = now + lockoutNanos;
        }
        pruneIfNeeded(now);
    }

    /** Clears the failure count for a key after a successful authentication. */
    public void recordSuccess(String key) {
        entries.remove(key(key));
    }

    /** Time left on the lockout, or {@link Duration#ZERO} when the key is not locked. */
    public Duration remainingLockout(String key) {
        Entry entry = entries.get(key(key));
        if (entry == null) {
            return Duration.ZERO;
        }
        long remaining = entry.lockedUntilNanos - clock.getAsLong();
        return remaining <= 0L ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    /** Number of keys currently tracked. Bounded by the constructor's {@code maxTrackedKeys}. */
    public int trackedKeyCount() {
        return entries.size();
    }

    /** One eviction candidate with its age and lock state frozen at snapshot time. */
    private record Victim(String key, Entry entry, long touchedNanos, boolean locked) {
    }

    private void pruneIfNeeded(long now) {
        if (entries.size() <= maxTrackedKeys) {
            return;
        }
        // Cheap pass first: drop entries whose lockout has expired and that have no recent failures.
        entries.forEach((candidate, entry) -> {
            if (entry.lockedUntilNanos <= now && entry.failures.get() == 0) {
                entries.remove(candidate, entry);
            }
        });
        if (entries.size() <= maxTrackedKeys) {
            return;
        }
        // Still over the limit: evict the least recently used down to a low-water mark so that a key
        // flood does not pay a full sort on every single new key.
        //
        // The age AND the lock state are frozen into an immutable snapshot BEFORE sorting.
        // lastTouchedNanos and lockedUntilNanos are written by other threads while this runs, and a
        // comparator that reads those live fields can report an inconsistent ordering for the same
        // pair, which makes TimSort throw "Comparison method violates its general contract!" - turning
        // a brute-force attempt into a stream of 500s instead of 401s and 429s.
        List<Victim> snapshot = new ArrayList<>(entries.size());
        entries.forEach((candidate, entry) -> snapshot.add(
                new Victim(candidate, entry, entry.lastTouchedNanos, entry.lockedUntilNanos > now)));
        // Unlocked entries are evicted first, so a key flood cannot evict a live lockout while there is
        // anything else to give up. Locked entries are only evicted when nothing else remains.
        snapshot.sort(Comparator
                .comparingInt((Victim victim) -> victim.locked() ? 1 : 0)
                .thenComparingLong(Victim::touchedNanos));

        int target = Math.max(MIN_TRACKED_KEYS,
                (int) ((long) maxTrackedKeys * PRUNE_NUMERATOR / PRUNE_DENOMINATOR));
        int removeCount = Math.max(0, snapshot.size() - target);
        for (int i = 0; i < removeCount && i < snapshot.size(); i++) {
            Victim victim = snapshot.get(i);
            entries.remove(victim.key(), victim.entry());
        }
    }

    private static String key(String raw) {
        Objects.requireNonNull(raw, "key");
        if (raw.isEmpty() || raw.isBlank()) {
            throw new IllegalArgumentException("throttle key must not be blank");
        }
        if (raw.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("throttle key must not exceed " + MAX_KEY_LENGTH + " characters");
        }
        return raw;
    }

    /** Convenience for callers that need to round a remaining lockout up to whole seconds. */
    public static long secondsCeil(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            return 0L;
        }
        return (duration.toNanos() + NANOS_PER_SECOND - 1L) / NANOS_PER_SECOND;
    }
}
