package com.mraibo.cminsight.ibm.internal;

import com.mraibo.cminsight.connection.CmSession;

/**
 * One borrowed IBM CM session: a {@code DKDatastoreICM} wrapper plus the liveness flag the pool reads.
 *
 * <h2>The health question, and why it is a field and not a call</h2>
 *
 * <p>{@link com.mraibo.cminsight.connection.BoundedPool} asks its factory
 * {@code isHealthy(resource)} on the borrow path, on the return path and during a rotation sweep - and on
 * the borrow path it does so while holding its own lock. The same lock is taken by
 * {@code closeState()}, which is the value a repository switch reads before it decides whether it may
 * open new connections. So a health probe that talked to the server would not merely be slow: it would
 * stall every concurrent borrow AND the shutdown decision a switch depends on.
 *
 * <p>{@link #isHealthy()} is therefore a single read of a volatile boolean and nothing else. It does not
 * call {@code DKDatastoreICM.isConnected()}, does not touch the SDK, does not take a lock and cannot do
 * I/O. This is the Goal 01C risk-1 obligation, and it is the reason the flag is written in two places
 * that are both under the adapter's control:
 *
 * <ul>
 *   <li>set when {@code connect()} returned, which is the only evidence that a session works;</li>
 *   <li>cleared by {@link #markUnusable()} when any later operation proved otherwise.</li>
 * </ul>
 *
 * <p>Stronger validation - {@code validateConnection()}, a real round trip - is deliberately NOT called
 * here. It belongs after a borrow and outside the pool lock, and its failure marks the session unusable
 * so the lease return retires it. Nothing in this class or {@link IbmCmSessionFactory} opens a session
 * outside the pool: there is no emergency connection and no discovery connection.
 *
 * <h2>Teardown is attempted in full, and its outcome is never assumed</h2>
 *
 * <p>{@link #close()} performs {@code disconnect()} and {@code destroy()} - every step, even when an
 * earlier one failed - and throws {@link IbmCmCleanupFailure} if any step did not return normally. It is
 * idempotent: a second call is a no-op, which matters because both the pool and the activation failure
 * path can race to release the same session.
 *
 * <p>The datastore field is deliberately NOT cleared when teardown begins. Nulling it first would look
 * tidy and would make the state below unprovable: the object is the only handle through which
 * {@code destroy()} can still be attempted, so dropping it before the physical outcome is accounted for
 * turns a recoverable "unknown" into a leak nobody can even try to fix.
 *
 * <h2>Failures are learned, never assumed</h2>
 *
 * <p>A freshly connected session is assumed healthy until something proves otherwise, and the only thing
 * that proves otherwise is {@link #markUnusable()}. A session is never retired merely because it is old -
 * age and operation rotation are the pool's business, driven by {@code cm.pool.max.age.minutes} and
 * {@code cm.pool.max.operations}.
 */
public final class IbmCmSession implements CmSession {

    /** Where one physical session is in its life. */
    private enum State {
        /** Allocated but not yet connected; a session is never published to the pool in this state. */
        NEW,
        /** Connected. The only state in which {@link #isHealthy()} answers true. */
        LIVE,
        /**
         * Connect succeeded but a later operation failed in a way that says the session must not be
         * reused. Still physically open - the lease return closes it - but no longer handed to a caller.
         */
        UNUSABLE,
        /** Every teardown step returned normally, so the physical session is provably gone. */
        DEAD,
        /** Teardown was attempted and at least one step did not return normally. Terminal. */
        UNCERTAIN
    }

    private final String repositoryId;
    private final IcmDatastore handle;
    private final AdapterErrorSink errorSink;

    /**
     * The pool's health question, answered without touching the SDK or taking a lock.
     *
     * <p>Volatile rather than plain so the write in {@link #markUnusable()} is visible to the pool thread
     * that reads it - the pool may be in the middle of {@code rotationFor()} when that happens.
     */
    private volatile boolean healthy;

    /** Guarded by {@code this}; only ever written by {@link #markUnusable()} and {@link #close()}. */
    private State state = State.NEW;

    /**
     * @param repositoryId the profile id, for diagnostics only; never a credential
     * @param handle       the connected datastore view, or {@code null} before connect
     * @param errorSink    where a sanitised failure is recorded for diagnostics, or {@code null}
     */
    IbmCmSession(String repositoryId, IcmDatastore handle, AdapterErrorSink errorSink) {
        this.repositoryId = repositoryId == null ? "" : repositoryId;
        this.handle = handle;
        this.errorSink = errorSink;
    }

    /**
     * Creates the wrapper around a connected datastore and marks it LIVE.
     *
     * <p>The only constructor that can produce a healthy session, which is what keeps
     * {@link #isHealthy()} from ever being true for something that was not actually connected.
     */
    static IbmCmSession live(String repositoryId, IcmDatastore handle, AdapterErrorSink errorSink) {
        IbmCmSession session = new IbmCmSession(repositoryId, handle, errorSink);
        session.state = State.LIVE;
        session.healthy = true;
        return session;
    }

    @Override
    public String repositoryId() {
        return repositoryId;
    }

    /**
     * The pool's cheap liveness probe: one volatile read.
     *
     * <p>Deliberately NOT {@code DKDatastoreICM.isConnected()}. That method is a vendor call made while
     * {@code BoundedPool} holds its lock, and the project does not assume it is a cheap local operation -
     * the Goal 02 obligation is explicit that it must not be relied on here.
     */
    @Override
    public boolean isHealthy() {
        return healthy;
    }

    /**
     * Marks this session unusable after an operation failed in a way that says it must not be reused.
     *
     * <p>Idempotent and cheap. After this call {@link #isHealthy()} answers false, so the pool's return
     * path retires the session and {@link #close()} runs on the thread that returned the lease - never on
     * the pool's lock-holding thread.
     *
     * <p>A session that is already dead or uncertain is left alone: a late failure report from a
     * concurrent operation must not resurrect state or trigger a second teardown.
     *
     * @param sanitisedFailure value-free description of what happened, for diagnostics; may be empty
     */
    void markUnusable(String sanitisedFailure) {
        synchronized (this) {
            if (state == State.DEAD || state == State.UNCERTAIN) {
                return;
            }
            // The flag is cleared BEFORE the state is inspected-and-updated under the lock, so a probe
            // that runs concurrently can only ever see the conservative answer.
            healthy = false;
            if (state == State.LIVE) {
                state = State.UNUSABLE;
            }
        }
        record(sanitisedFailure);
    }

    /** {@link #markUnusable(String)} without a description. */
    void markUnusable() {
        markUnusable("");
    }

    /** The datastore view. Callers must hold this session inside a lease. */
    IcmDatastore handle() {
        return handle;
    }

    /**
     * True while this session still holds a physical datastore, i.e. until teardown has finished.
     *
     * <p>A cheap local read, used only by {@link IbmCmPoolDiagnostics} to age out its diagnostics map: a
     * diagnostics read must not ask the SDK anything. It is deliberately NOT the same question as
     * {@link #isHealthy()}. A session that was marked unusable is still LIVE until its lease is returned and
     * {@link #close()} has run, and a pool capacity slot stays consumed for exactly that interval - so
     * treating "unusable" as "gone" here would retire a session from the age reading while its slot is still
     * accounted for.
     */
    boolean isLive() {
        State current = state;
        return current != State.DEAD && current != State.UNCERTAIN;
    }

    /**
     * Releases the physical session, attempting every teardown step and reporting an unproven outcome.
     *
     * <p>Order matters and is the proven one: {@code disconnect()} while the datastore reports itself
     * connected, then {@code destroy()} ALWAYS - {@code destroy()} releases the local SDK object, and
     * skipping it because {@code disconnect()} threw is precisely how a half-torn-down session survives.
     *
     * <p>Failures are collected, never swallowed, and surfaced as {@link IbmCmCleanupFailure}, which is
     * what makes {@code BoundedPool} quarantine the capacity slot instead of freeing it. Freeing a slot
     * whose session may still exist is the one outcome the hard physical bound cannot survive.
     *
     * <p>Idempotent, and safe to call after {@link #markUnusable()}: the second call observes a terminal
     * state and returns.
     *
     * @throws IbmCmCleanupFailure when a teardown step did not return normally
     */
    @Override
    public void close() {
        synchronized (this) {
            if (state == State.DEAD || state == State.UNCERTAIN) {
                return;
            }
            healthy = false;
            state = State.UNCERTAIN;
        }

        boolean disconnectProven = true;
        boolean destroyProven = true;
        String disconnectDetail = "";

        if (handle != null) {
            boolean connected = isConnectedQuietly(handle);
            if (connected) {
                try {
                    handle.disconnect();
                } catch (Exception | Error failure) {
                    disconnectProven = false;
                    disconnectDetail = IbmErrorSanitizer.describe("disconnect", failure);
                }
            }
            try {
                handle.destroy();
            } catch (Exception | Error failure) {
                destroyProven = false;
                if (disconnectDetail.isEmpty()) {
                    disconnectDetail = IbmErrorSanitizer.describe("destroy", failure);
                }
            }
        }

        boolean proven = disconnectProven && destroyProven;
        synchronized (this) {
            // The state is set only now, after the physical outcome is known. That ordering is the
            // point: a concurrent markUnusable() during teardown sees UNCERTAIN and stays out of the way,
            // and a reader can never observe "clean" while a step is still in flight.
            state = proven ? State.DEAD : State.UNCERTAIN;
        }

        if (!proven) {
            String text = "session teardown did not return normally (" + disconnectDetail + ")";
            record(text);
            throw new IbmCmCleanupFailure(disconnectProven ? "destroy" : "disconnect", text, null);
        }
    }

    /**
     * Asks whether the datastore currently considers itself connected.
     *
     * <p>Used only during teardown, where the answer decides whether {@code disconnect()} is attempted.
     * It is never used as a health probe - see the class notes.
     *
     * <p>Any unchecked failure - or a linkage error from a half-visible SDK - is read as "connected", because
     * the conservative reading is to attempt the disconnect: if the call then fails, the slot is quarantined,
     * which is the safe direction. Reading a failure as "not connected" would skip a needed teardown and let a
     * live session be forgotten.
     */
    private static boolean isConnectedQuietly(IcmDatastore handle) {
        try {
            return handle.isConnected();
        } catch (RuntimeException | Error unknown) {
            return true;
        }
    }

    private void record(String text) {
        if (errorSink != null && text != null && !text.isBlank()) {
            errorSink.recordAdapterError(text);
        }
    }

    @Override
    public String toString() {
        return "IbmCmSession[repository=" + repositoryId + ", state=" + state
                + ", healthy=" + healthy + "]";
    }
}
