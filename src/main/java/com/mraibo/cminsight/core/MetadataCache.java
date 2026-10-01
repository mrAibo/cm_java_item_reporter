package com.mraibo.cminsight.core;

import com.mraibo.cminsight.metadata.ItemTypeInfo;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.metadata.MetadataRepository;
import com.mraibo.cminsight.retention.RetentionPolicyInfo;
import com.mraibo.cminsight.retention.RetentionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The per-repository metadata cache: one instance per {@link com.mraibo.cminsight.repository.RepositoryContext},
 * created with the context, published with the context and released with the context.
 *
 * <h2>What is cached, and what is deliberately not</h2>
 *
 * <p>Three list snapshots are cached, because those are the reads that cost a round trip per element and
 * that a console asks for on every page: the ItemType list, the retention policy name list and the
 * retention policy list. Detail reads - one ItemType by name, one policy by name, the assignments of one
 * policy - are passed straight through to the service that owns them. That is a scope decision, stated so
 * it cannot be mistaken for an oversight: caching one entry per requested name would need an eviction
 * policy keyed by caller-supplied text, and the list snapshots already carry the expensive mapping. A
 * pass-through still borrows its session from the bounded pool, so it stays inside the hard bound.
 *
 * <h2>Freshness, single flight and last-known-good</h2>
 *
 * <ul>
 *   <li><strong>TTL.</strong> A snapshot is fresh for {@code cache.metadata.ttl.seconds}. A TTL of
 *       {@code 0} disables caching entirely: every read loads through, and no snapshot is retained.</li>
 *   <li><strong>Single flight per key.</strong> A refresh happens under that key's own lock, so a second
 *       caller that arrives while a refresh is running WAITS for it and then uses its result. It never
 *       launches a duplicate refresh, so a burst of requests cannot turn into a burst of CM sessions.</li>
 *   <li><strong>Last known good.</strong> A failed refresh never replaces a known-good snapshot and never
 *       publishes partial data: the previous snapshot keeps being served, its age keeps growing, and the
 *       failure is recorded on {@link #lastError()} so diagnostics can show why the data is stale. When
 *       there is no known-good snapshot at all, the failure reaches the caller - an empty answer would be
 *       indistinguishable from "the repository really has no ItemTypes".</li>
 *   <li><strong>Failure backoff.</strong> After a failed refresh the same key is not retried until the TTL
 *       has passed again, so a server that is down does not get one connection attempt per request. One
 *       attempt per TTL window per key is the documented behaviour.</li>
 * </ul>
 *
 * <h2>Isolation and content</h2>
 *
 * <p>Nothing here is shared between repositories, and nothing survives the context: the cache holds only
 * immutable DTOs produced by the adapter, never a live session and never a vendor object. It stores no
 * document content, no user content and no history - only the current snapshot of each key, which is what
 * the read-only viewer needs.
 *
 * <p>Every method that loads is called outside the CM session pool's lock, so a refresh can never stall a
 * borrow or a lifecycle decision.
 */
public final class MetadataCache implements AutoCloseable {

    private final MetadataRepository metadata;
    private final RetentionRepository retention;
    private final Duration ttl;
    private final long ttlNanos;
    private final boolean caching;

    private final Slot<List<ItemTypeSummary>> itemTypes = new Slot<>();
    private final Slot<List<String>> policyNames = new Slot<>();
    private final Slot<List<RetentionPolicyInfo>> policies = new Slot<>();

    private final MetadataRepository metadataView = new MetadataView();
    private final RetentionRepository retentionView = new RetentionView();

    /**
     * The last load failure, already sanitised: a type name and the service's own message, which the
     * adapter guarantees carries no credential and no raw SDK text. Empty when the last load succeeded or
     * none has run.
     */
    private volatile String lastError = "";

    private volatile boolean closed;

    /**
     * @param ttl       how long a snapshot stays fresh; {@link Duration#ZERO} disables caching
     * @param metadata  the ItemType service, or {@code null} when this repository has none
     * @param retention the retention service, or {@code null} when this repository has none
     */
    public MetadataCache(Duration ttl, MetadataRepository metadata, RetentionRepository retention) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("cache.metadata.ttl.seconds must not be negative");
        }
        this.metadata = metadata;
        this.retention = retention;
        this.caching = !ttl.isZero();
        this.ttlNanos = caching ? ttl.toNanos() : 0L;
    }

    /** The configured time-to-live of one snapshot. */
    public Duration ttl() {
        return ttl;
    }

    /** True when this cache actually retains snapshots (a TTL of 0 disables it). */
    public boolean caching() {
        return caching;
    }

    /**
     * The ItemType service backed by this cache, or {@code null} when the repository has none.
     *
     * <p>Returns {@code null} rather than an {@code Optional} so a context can store it in
     * {@link RepositoryServices} unchanged: "this repository has no metadata service" and "this service
     * exists but is currently unavailable" stay different answers.
     */
    public MetadataRepository metadataView() {
        return metadata == null ? null : metadataView;
    }

    /** The retention service backed by this cache, or {@code null} when the repository has none. */
    public RetentionRepository retentionView() {
        return retention == null ? null : retentionView;
    }

    /**
     * When the ItemType snapshot was loaded, or empty when nothing has been loaded yet.
     *
     * <p>The ItemType list is the primary snapshot this cache reports on: it is the read that drives the
     * console's main page, and reporting one age is more useful to an operator than reporting three.
     */
    public Optional<Instant> snapshotAt() {
        Instant loadedAt = itemTypes.loadedAt;
        return loadedAt == null ? Optional.empty() : Optional.of(loadedAt);
    }

    /** Age of the ItemType snapshot, or {@link Duration#ZERO} when nothing has been loaded yet. */
    public Duration age() {
        long loadedAtNanos = itemTypes.loadedAtNanos;
        if (!itemTypes.loaded || loadedAtNanos == 0L) {
            return Duration.ZERO;
        }
        long elapsed = System.nanoTime() - loadedAtNanos;
        return elapsed <= 0L ? Duration.ZERO : Duration.ofNanos(elapsed);
    }

    /**
     * True when the ItemType snapshot exists and is inside its TTL.
     *
     * <p>A stale-but-served snapshot reports {@code false} here and non-empty from
     * {@link #lastError()}, which is exactly the pair an operator needs: "the data you are looking at is
     * old, and here is why".
     */
    public boolean fresh() {
        return caching && itemTypes.fresh(System.nanoTime(), ttlNanos);
    }

    /** The last sanitised load failure, or empty when the last load succeeded or none has run. */
    public Optional<String> lastError() {
        String error = lastError;
        return error.isEmpty() ? Optional.empty() : Optional.of(error);
    }

    /** Drops every snapshot, so the next read reloads. Keeps the cache usable. */
    public void invalidate() {
        for (Slot<?> slot : slots()) {
            slot.lock.lock();
            try {
                slot.clear();
            } finally {
                slot.lock.unlock();
            }
        }
        lastError = "";
    }

    /**
     * Releases the cache.
     *
     * <p>Idempotent, and it holds no physical resource: closing only drops the snapshots and refuses
     * further reads, which is what "the cache dies with the context" means in practice. It is an owned
     * resource of the context, so it is closed in the same reverse order as the pool it reads through.
     */
    @Override
    public void close() {
        // Acquire every slot lock before clearing, so a load that is in flight when the context closes
        // cannot publish a snapshot into a cache that has already been released.
        for (Slot<?> slot : slots()) {
            slot.lock.lock();
        }
        try {
            closed = true;
            for (Slot<?> slot : slots()) {
                slot.clear();
            }
            lastError = "";
        } finally {
            for (Slot<?> slot : slots()) {
                slot.lock.unlock();
            }
        }
    }

    /** True once {@link #close()} has run. */
    public boolean isClosed() {
        return closed;
    }

    @Override
    public String toString() {
        return "MetadataCache[ttl=" + ttl.toSeconds() + "s"
                + ", caching=" + caching
                + ", itemTypes=" + itemTypes.loaded
                + ", policies=" + policies.loaded
                + ", lastError=" + (lastError.isEmpty() ? "none" : "recorded")
                + ", closed=" + closed + "]";
    }

    // ---------------------------------------------------------------- internals

    private List<Slot<?>> slots() {
        return List.of(itemTypes, policyNames, policies);
    }

    /**
     * Reads one snapshot: returns the fresh value, refreshes it once under the key's own lock, keeps the
     * last known-good value when the refresh fails, and reports the original failure when there is none.
     */
    private <T> T read(Slot<T> slot, Supplier<T> loader) {
        if (!caching) {
            // No snapshot is retained, so there is nothing to share and nothing to keep. The load still
            // happens exactly once per call - the caller's own call.
            return loader.get();
        }
        long now = System.nanoTime();
        if (slot.fresh(now, ttlNanos)) {
            return slot.value;
        }
        slot.lock.lock();
        try {
            now = System.nanoTime();
            if (slot.fresh(now, ttlNanos)) {
                // Another caller refreshed while we waited for the lock. Its result is ours too: this is
                // the "observe the in-flight refresh" half of the single-flight contract.
                return slot.value;
            }
            if (slot.loaded && slot.attemptedRecently(now, ttlNanos)) {
                // The previous refresh failed inside the current TTL window. Serve the last known-good
                // snapshot instead of hammering a server that just refused, and never replace it.
                return slot.value;
            }
            RuntimeException previousFailure = slot.lastFailure;
            if (!slot.loaded && slot.attemptedRecently(now, ttlNanos) && previousFailure != null) {
                // Nothing good to serve: report the same failure again rather than pretending the
                // repository is empty.
                throw previousFailure;
            }
            try {
                T value = loader.get();
                slot.store(value, now);
                lastError = "";
                return value;
            } catch (RuntimeException failure) {
                slot.recordFailure(failure, now);
                lastError = describe(failure);
                if (slot.loaded) {
                    return slot.value;
                }
                throw failure;
            }
        } finally {
            slot.lock.unlock();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("The metadata cache is closed; the repository context it "
                    + "belongs to has been released");
        }
    }

    /**
     * One cached snapshot. All state is volatile so the lock-free fast path reads a consistent value; the
     * lock only ever serialises a REFRESH.
     */
    private static final class Slot<T> {

        private final ReentrantLock lock = new ReentrantLock();
        private volatile T value;
        private volatile boolean loaded;
        private volatile long loadedAtNanos;
        private volatile Instant loadedAt;
        private volatile long attemptedAtNanos;
        private volatile RuntimeException lastFailure;

        boolean fresh(long now, long ttlNanos) {
            return loaded && now - loadedAtNanos < ttlNanos;
        }

        boolean attemptedRecently(long now, long ttlNanos) {
            return attemptedAtNanos != 0L && now - attemptedAtNanos < ttlNanos;
        }

        void store(T newValue, long now) {
            value = newValue;
            loaded = true;
            loadedAtNanos = now;
            loadedAt = Instant.now();
            attemptedAtNanos = now;
            lastFailure = null;
        }

        void recordFailure(RuntimeException failure, long now) {
            attemptedAtNanos = now;
            lastFailure = failure;
        }

        void clear() {
            value = null;
            loaded = false;
            loadedAtNanos = 0L;
            loadedAt = null;
            attemptedAtNanos = 0L;
            lastFailure = null;
        }
    }

    private static String describe(RuntimeException failure) {
        String type = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        String text = message == null || message.isBlank() ? type : type + ": " + message;
        // Bounded so a hostile or verbose service cannot grow the diagnostics text without limit.
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH) + "...";
    }

    private static final int MAX_ERROR_LENGTH = 500;

    /**
     * The ItemType service as the rest of the runtime sees it: cached lists, pass-through details.
     *
     * <p>A separate view type exists because {@code MetadataRepository} and {@code RetentionRepository}
     * both declare {@code available()}: one method on the cache could not answer two different questions,
     * and conflating them would report a repository with retention but no ItemTypes as fully available.
     */
    private final class MetadataView implements MetadataRepository {

        @Override
        public List<ItemTypeSummary> listItemTypes() {
            requireOpen();
            if (metadata == null) {
                return List.of();
            }
            return read(itemTypes, () -> List.copyOf(metadata.listItemTypes()));
        }

        @Override
        public Optional<ItemTypeInfo> itemType(String name) {
            requireOpen();
            if (metadata == null || name == null || name.isBlank()) {
                return Optional.empty();
            }
            // Pass-through by design: see the class notes. The service borrows its own pooled session.
            return metadata.itemType(name.trim());
        }

        @Override
        public boolean available() {
            return !closed && metadata != null && metadata.available();
        }

        @Override
        public String toString() {
            return "MetadataCache.metadata[ttl=" + ttl.toSeconds() + "s, age=" + age().toMillis() + "ms]";
        }
    }

    /** The retention service as the rest of the runtime sees it: cached lists, pass-through details. */
    private final class RetentionView implements RetentionRepository {

        @Override
        public List<String> listPolicyNames() {
            requireOpen();
            if (retention == null) {
                return List.of();
            }
            return read(policyNames, () -> List.copyOf(retention.listPolicyNames()));
        }

        @Override
        public List<RetentionPolicyInfo> listPolicies() {
            requireOpen();
            if (retention == null) {
                return List.of();
            }
            return read(policies, () -> List.copyOf(retention.listPolicies()));
        }

        @Override
        public Optional<RetentionPolicyInfo> policy(String name) {
            requireOpen();
            if (retention == null || name == null || name.isBlank()) {
                return Optional.empty();
            }
            return retention.policy(name.trim());
        }

        @Override
        public List<String> itemTypeNamesForPolicy(String policyName) {
            requireOpen();
            if (retention == null || policyName == null || policyName.isBlank()) {
                return List.of();
            }
            return retention.itemTypeNamesForPolicy(policyName.trim());
        }

        @Override
        public boolean available() {
            return !closed && retention != null && retention.available();
        }

        @Override
        public String toString() {
            return "MetadataCache.retention[ttl=" + ttl.toSeconds() + "s]";
        }
    }
}
