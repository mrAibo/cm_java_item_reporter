package com.mraibo.cminsight.ibm.internal;

import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CmSession;
import com.mraibo.cminsight.core.CloseState;
import com.mraibo.cminsight.core.CmPoolDiagnostics;
import com.mraibo.cminsight.ibm.CmAdapterSettings;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The adapter's view of the CM session pool of one active repository.
 *
 * <h2>What this class is, and what it is not</h2>
 *
 * <p>It is NOT the pool. The core builds and owns the {@link BoundedPool} - it wraps
 * {@link IbmCmSessionFactory} in its own resource factory, eagerly initializes the configured sessions, and
 * only then asks the adapter for its read services over that pool. Every hard bound, the quarantine rule
 * and the whole {@link CloseState} lifecycle therefore stay in the committed, reviewed pool, and this class
 * adds nothing to them.
 *
 * <p>What it adds is the two answers the pool genuinely cannot give, and which section L of the goal asks
 * for:
 *
 * <ul>
 *   <li>the age of the oldest session the pool still holds - the pool exposes no per-resource creation
 *       time, so the adapter records it, because the adapter is what creates sessions;</li>
 *   <li>the vendor API release and the last sanitised adapter failure - the pool is deliberately
 *       vendor-neutral and has nowhere to put either.</li>
 * </ul>
 *
 * <h2>Every read is local</h2>
 *
 * <p>No method here performs I/O, opens a session, asks the SDK anything or waits. Pool figures come from
 * the pool's own lock-guarded snapshot; the age and error readings are local fields. That is a requirement,
 * not a nicety: this interface is read by the diagnostics endpoint, and the same machine answers whether a
 * repository switch may proceed - a diagnostics read that blocked on the network would delay a lifecycle
 * decision for no reason.
 *
 * <h2>Value-free by construction</h2>
 *
 * <p>There is nowhere in this class for a credential to be stored: it holds counts, states, durations, a
 * version string and one sanitised line. {@link #lastAdapterError()} is the only free-text accessor, and the
 * text it returns was produced by {@link IbmErrorSanitizer} - a category, an operation label and an IBM
 * exception class name, never a vendor message verbatim.
 *
 * <h2>Why it composes the core pool rather than wrapping it in another pool</h2>
 *
 * <p>A second bounded pool around the first would be a second bound to keep correct, and it would double
 * the number of live sessions for no benefit. The pool is passed in already initialized and fully
 * accountable, which is exactly what a diagnostics reader needs.
 *
 * <h2>Why there is no adapter-side {@code CmSessionPool} class</h2>
 *
 * <p>{@code GOAL_02_IMPLEMENTATION_SPEC.md} section 5 lists a {@code CmSessionPool} under the adapter's
 * internals, on the assumption that the adapter would compose the pool. The committed core does not work that
 * way, and the difference is deliberate on the core's side: {@code ProductionRepositoryContextFactory} builds
 * the {@link BoundedPool} itself, feeds it a wrapper around the adapter's {@link com.mraibo.cminsight.core.CmSessionFactory},
 * eagerly initializes it, and only then hands it to the adapter so the adapter can build its read services.
 *
 * <p>That is the stronger arrangement, and reason enough not to duplicate it here:
 *
 * <ul>
 *   <li><strong>One pool, one owner.</strong> The pool is registered as an owned resource of the
 *       {@code RepositoryContext} before it holds anything, so the Goal 01C close-state rules apply to it
 *       automatically and no second component has to remember to close it.</li>
 *   <li><strong>The bound is unambiguous.</strong> A pool composed inside the adapter and a pool composed by
 *       the core would each be a bound, and two bounds that can disagree are how a physical session limit is
 *       breached.</li>
 *   <li><strong>The core needs no vendor type to build a pool.</strong> It depends only on
 *       {@code CmSessionFactory}, so a core-only build has no adapter class on its path at all.</li>
 * </ul>
 *
 * <p>What the adapter still owes - and what this class plus {@link IbmCmSessionFactory} provide - is the
 * adapter-specific half: the resource factory that reports an unproven creation honestly so that pool can
 * quarantine the right slot, the cheap health flag, and the diagnostics that need vendor knowledge.
 */
public final class IbmCmPoolDiagnostics implements CmPoolDiagnostics {

    private final BoundedPool<CmSession> pool;
    private final CmAdapterSettings settings;
    private final IbmCmSessionFactory sessionFactory;

    /**
     * @param pool           the pool the core built and initialized for this activation; the only session
     *                       source in the process
     * @param settings       the validated bounds, for the reporting labels only
     * @param sessionFactory the factory that creates sessions, and therefore the only thing that knows a
     *                       session's age
     */
    public IbmCmPoolDiagnostics(BoundedPool<CmSession> pool,
                                CmAdapterSettings settings,
                                IbmCmSessionFactory sessionFactory) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.settings = settings;
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    }

    /**
     * Per-session diagnostics stamp: when the session was created, and whether teardown has since finished.
     *
     * <p>A tiny mutable holder rather than a bare timestamp because liveness has to be recorded from a
     * lock-free read in {@link #oldestSessionAge()}, and because the committed read-only source guard forbids
     * collection mutators inside this source set. Rewriting one field of an existing value is both cheaper
     * and legal.
     */
    public static final class SessionStamp {

        private final long createdAtNanos;
        private volatile boolean live = true;

        SessionStamp(long createdAtNanos) {
            this.createdAtNanos = createdAtNanos;
        }
    }

    /**
     * The vendor API release of the loaded SDK, or empty when it could not be read.
     *
     * <p>Delegates to the provider so the registry's advertised release and this one are the same value from
     * the same lookup - one answer, not two that can drift.
     */
    public Optional<String> sdkRelease() {
        String version = IbmCmAdapterProvider.sdkReleaseOrEmpty();
        return version.isEmpty() ? Optional.empty() : Optional.of(version);
    }

    // ---------------------------------------------------------------- CmPoolDiagnostics

    @Override
    public String poolName() {
        return pool.name();
    }

    @Override
    public int configuredSize() {
        return pool.configuredSize();
    }

    /**
     * Capacity slots consumed right now.
     *
     * <p>Read from the pool's own snapshot, whose identity
     * {@code available + leased + creating + retiring + quarantined} is maintained under one lock, so this
     * can never disagree with the individual accessors below or exceed {@link #configuredSize()}.
     */
    @Override
    public int capacityInUse() {
        return pool.metrics().capacityInUse();
    }

    @Override
    public int available() {
        return pool.metrics().available();
    }

    @Override
    public int leased() {
        return pool.metrics().leased();
    }

    @Override
    public int creating() {
        return pool.metrics().creating();
    }

    @Override
    public int retiring() {
        return pool.metrics().retiring();
    }

    @Override
    public int quarantined() {
        return pool.metrics().quarantined();
    }

    @Override
    public long createAttempts() {
        return pool.metrics().createAttempts();
    }

    @Override
    public long created() {
        return pool.metrics().created();
    }

    @Override
    public long createFailures() {
        return pool.metrics().createFailures();
    }

    /**
     * Creation attempts whose cleanup could not be proven, so their slot stayed consumed.
     *
     * <p>The counter that explains a pool running below its configured size: without it, capacity would
     * simply have vanished from the operator's point of view.
     */
    @Override
    public long createQuarantineFailures() {
        return pool.metrics().createQuarantineFailures();
    }

    @Override
    public long closeAttempts() {
        return pool.metrics().closeAttempts();
    }

    @Override
    public long closeSuccesses() {
        return pool.metrics().closeSuccesses();
    }

    @Override
    public long closeFailures() {
        return pool.metrics().closeFailures();
    }

    @Override
    public long borrowCount() {
        return pool.metrics().borrowCount();
    }

    @Override
    public long borrowTimeoutCount() {
        return pool.metrics().borrowTimeoutCount();
    }

    @Override
    public double averageBorrowWaitMillis() {
        return pool.metrics().averageBorrowWaitMs();
    }

    @Override
    public double maxBorrowWaitMillis() {
        return pool.metrics().maxBorrowWaitMs();
    }

    @Override
    public CloseState closeState() {
        return pool.closeState();
    }

    /**
     * Age of the oldest session the pool still holds, or empty when it holds none.
     *
     * <p>Answered from locally recorded creation stamps, never by asking the SDK.
     *
     * <h2>Compaction instead of mutation</h2>
     *
     * <p>Retired sessions must not inflate the reading, and their entries must not accumulate for the
     * lifetime of the pool. Both are handled without any collection mutator: this method walks the stamps,
     * rewrites the one {@code boolean} that says a session has finished - a field write on the value, not a
     * collection operation - and, once the map has outgrown the configured pool size several times over,
     * has it replaced wholesale by {@link IbmCmSessionFactory} with a fresh map built from the stamps that
     * are still live. A concurrent reader therefore always sees a complete map.
     *
     * <p>Consequence worth stating plainly: this reports the oldest session <em>the pool still holds</em>,
     * so a pool between rotations reports empty rather than the age of a session it already released. That
     * is the honest answer to "how old is what you are holding".
     */
    @Override
    public Optional<Duration> oldestSessionAge() {
        ConcurrentHashMap<IbmCmSession, SessionStamp> stamps = sessionFactory.sessionStamps();
        long now = System.nanoTime();
        long oldest = 0L;
        boolean anyLive = false;
        boolean anyFinished = false;

        for (Map.Entry<IbmCmSession, SessionStamp> entry : stamps.entrySet()) {
            SessionStamp stamp = entry.getValue();
            if (!entry.getKey().isLive()) {
                if (stamp != null) {
                    stamp.live = false;
                }
                anyFinished = true;
                continue;
            }
            if (stamp != null) {
                oldest = Math.max(oldest, now - stamp.createdAtNanos);
            }
            anyLive = true;
        }

        if (anyFinished) {
            compactStamps(stamps);
        }
        if (!anyLive || oldest <= 0L) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos(oldest));
    }

    @Override
    public boolean degraded() {
        return pool.metrics().degraded();
    }

    /**
     * The last sanitised adapter failure, or empty when none has occurred.
     *
     * <p>Value-free by contract: the text was produced by {@link IbmErrorSanitizer} and is never a vendor
     * message verbatim. The pool's own creation-failure log and this one describe the same events from two
     * sides, and both are safe to print.
     */
    @Override
    public Optional<String> lastAdapterError() {
        String error = sessionFactory.lastAdapterErrorText();
        return error == null || error.isEmpty() ? Optional.empty() : Optional.of(error);
    }

    @Override
    public String toString() {
        return "IbmCmPoolDiagnostics[pool=" + pool.name()
                + ", configuredSize=" + configuredSize()
                + ", capacityInUse=" + capacityInUse()
                + ", cacheTtlSeconds=" + (settings == null ? 0 : settings.metadataCacheTtlSeconds())
                + ", closeState=" + closeState() + "]";
    }

    // ---------------------------------------------------------------- internals

    /**
     * Replaces a stamp map that has grown well past the number of sessions the pool can hold.
     *
     * <p>Done by REPLACEMENT rather than removal, for two reasons that happen to agree. The committed
     * read-only source guard forbids collection mutators in this source set, and it is right to: the only
     * legitimate mutation here is "start over with what is still true", which a whole-map swap expresses
     * without ever exposing a half-pruned map to a concurrent reader.
     *
     * <p>Compaction is deliberately threshold-based rather than per read: a diagnostics endpoint that rebuilt
     * a map on every call would charge an operator for looking, and the map is bounded by a small multiple of
     * {@code cm.pool.size} in between.
     */
    private void compactStamps(ConcurrentHashMap<IbmCmSession, SessionStamp> stamps) {
        int threshold = Math.max(8, configuredSize() * 4);
        if (stamps.size() <= threshold) {
            return;
        }
        ConcurrentHashMap<IbmCmSession, SessionStamp> compacted = new ConcurrentHashMap<>();
        for (Map.Entry<IbmCmSession, SessionStamp> entry : stamps.entrySet()) {
            SessionStamp stamp = entry.getValue();
            if (stamp != null && stamp.live && entry.getKey().isLive()) {
                compacted.put(entry.getKey(), stamp);
            }
        }
        // Published by the factory, which owns the field, so a session created concurrently lands in
        // either the map being replaced or the replacement - never nowhere.
        sessionFactory.compactStampsWith(compacted);
    }
}
