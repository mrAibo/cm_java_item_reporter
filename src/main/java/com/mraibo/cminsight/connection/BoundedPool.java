package com.mraibo.cminsight.connection;

import com.mraibo.cminsight.core.CloseOutcomeAware;
import com.mraibo.cminsight.core.CloseState;
import com.mraibo.cminsight.core.CloseStateAware;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A hard-bounded, dependency-free resource pool.
 *
 * <h2>Capacity rule</h2>
 *
 * Every capacity slot is accounted for under a single lock as exactly one of five states: idle,
 * leased, being created, retiring (closed but not yet finished closing), or quarantined (a close whose
 * outcome is uncertain). A borrow may only proceed when the sum of those states is below the configured
 * size, so the pool can <em>never</em> overshoot. When the pool is exhausted a borrow waits up to the
 * borrow timeout and then fails with {@link TimeoutException}; exactly the required backpressure. There
 * is no emergency-connection path and no unbounded fallback: CM_Migrator's emergency connection
 * behaviour is deliberately absent.
 *
 * <h2>An uncertain close never frees physical capacity</h2>
 *
 * A resource whose {@code close()} throws has an <em>unknown</em> physical outcome: the exception does
 * not prove that the underlying CM session or JDBC connection is gone. Such a slot is therefore moved to
 * quarantine and stays consumed, so no replacement can be created while the old resource may still
 * exist. This deliberately degrades the pool rather than risk exceeding the configured physical hard
 * bound - safety outranks availability. Only a {@code close()} that returned normally frees a slot.
 * {@link PoolMetrics#quarantined()} and {@link PoolMetrics#degraded()} make that state visible, and
 * {@code closeAttempts}/{@code closeSuccesses}/{@code closeFailures} keep the two outcomes distinct.
 *
 * <h2>Refill without threads</h2>
 *
 * There is no refill worker, no scheduled task and no thread pool. A retired or unhealthy resource
 * simply frees its slot, and the next borrow that needs capacity creates a replacement while holding
 * a reserved slot. Refill races are therefore impossible by construction, and a burst of retiring
 * resources cannot spawn a wave of creating threads.
 *
 * <p>A creation that is still in flight when {@link #close()} begins is never handed out: it is retired
 * through the same path as any other retirement, so its slot is freed or quarantined exactly once.
 *
 * <h2>Strictness versus promptness</h2>
 *
 * A retiring resource keeps its slot until {@code close()} has actually returned. That means a slow
 * close can briefly delay a borrow, but the pool never opens a replacement while the old resource is
 * still alive. Bounded-but-slower was chosen over fast-but-overshooting because the hard bound is a
 * non-negotiable architecture rule.
 *
 * <h2>Shutdown has a state, not just an uncertainty flag</h2>
 *
 * {@link #close()} returns as soon as the idle resources are released; a resource that is still out on a
 * lease is closed later, by the thread that returns it. The pool therefore reports a
 * {@link CloseState} rather than a boolean:
 *
 * <ul>
 *   <li>{@link CloseState#NOT_CLOSED} while the pool is in service;</li>
 *   <li>{@link CloseState#CLOSING} from the moment close begins until the last leased, creating or
 *       retiring slot has finished - the pool is closed to new borrows, but a physical resource is still
 *       alive, so this is NOT a clean shutdown;</li>
 *   <li>{@link CloseState#CLOSED_CLEAN} once every slot is proven released and nothing is quarantined;</li>
 *   <li>{@link CloseState#CLOSED_UNCERTAIN} once any slot is quarantined.</li>
 * </ul>
 *
 * <p>{@link #isClosed()} keeps its original meaning - close was requested - so it says nothing about
 * whether the physical resources are gone. {@link #closeState()} is the question to ask for that.
 *
 * <h2>Usage rotation cannot be forgotten</h2>
 *
 * Every completed borrow/use/close cycle counts as one usage automatically, so a caller that only uses
 * try-with-resources still advances the operation budget. {@link Lease#recordOperation()} adds explicit
 * counts on top, and {@link PoolMetrics#automaticUsages()} and
 * {@link PoolMetrics#explicitOperations()} keep the two sources separately visible.
 *
 * @param <T> pooled resource type
 */
public final class BoundedPool<T extends AutoCloseable> implements CloseOutcomeAware, CloseStateAware {

    /** Why a resource is being taken out of service. */
    private enum Rotation {
        AGE,
        OPERATIONS,
        UNHEALTHY,
        SHUTDOWN
    }

    /** One pooled resource plus its lifetime accounting. */
    private static final class Entry<T> {
        private final T value;
        private final long sequence;
        private final long createdAtNanos;
        private final AtomicLong operations = new AtomicLong();

        private Entry(T value, long sequence, long createdAtNanos) {
            this.value = value;
            this.sequence = sequence;
            this.createdAtNanos = createdAtNanos;
        }
    }

    private final String name;
    private final int size;
    private final Duration borrowTimeout;
    private final ResourceFactory<T> factory;
    private final long maxAgeNanos;
    private final long maxOperationsPerResource;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition capacityChanged = lock.newCondition();
    private final ArrayDeque<Entry<T>> idle = new ArrayDeque<>();

    // Guarded by lock.
    private int leasedCount;
    private int creatingCount;
    private int retiringCount;
    /**
     * Slots whose resource had an UNCERTAIN close: {@code close()} threw, so the physical session or
     * connection may still exist. They keep consuming capacity for the lifetime of this pool, which is
     * deliberate: there is no in-process way to prove the resource is gone, and keeping the slot is
     * exactly what stops a replacement from pushing the pool past its configured physical bound. The
     * state is visible through {@link PoolMetrics#quarantined()} and {@link PoolMetrics#degraded()};
     * recovering the capacity means resolving the underlying resource and replacing the pool - a later
     * goal's adapter concern - rather than something this class can do for the caller.
     */
    private int quarantinedCount;
    private boolean initialized;
    private volatile boolean closed;

    // Lock-free counters.
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong borrowCount = new AtomicLong();
    private final AtomicLong borrowTimeoutCount = new AtomicLong();
    private final AtomicLong totalWaitNanos = new AtomicLong();
    private final AtomicLong maxWaitNanos = new AtomicLong();
    private final AtomicLong createAttempts = new AtomicLong();
    private final AtomicLong initialCreations = new AtomicLong();
    private final AtomicLong replacementCreations = new AtomicLong();
    private final AtomicLong created = new AtomicLong();
    private final AtomicLong createFailures = new AtomicLong();
    private final AtomicLong createQuarantineFailures = new AtomicLong();
    private final AtomicLong closeAttempts = new AtomicLong();
    private final AtomicLong closeSuccesses = new AtomicLong();
    private final AtomicLong closeFailures = new AtomicLong();
    private final AtomicLong validationFailures = new AtomicLong();
    private final AtomicLong ageRotations = new AtomicLong();
    private final AtomicLong operationRotations = new AtomicLong();
    private final AtomicLong unhealthyRotations = new AtomicLong();
    private final AtomicLong automaticUsages = new AtomicLong();
    private final AtomicLong explicitOperations = new AtomicLong();

    /** A pool with no age or operation based rotation. */
    public BoundedPool(String name, int size, Duration borrowTimeout, ResourceFactory<T> factory) {
        this(name, size, borrowTimeout, factory, null, 0);
    }

    /**
     * @param name                    label used in diagnostics
     * @param size                    hard maximum number of live resources, at least 1
     * @param borrowTimeout           how long a borrow waits before failing with backpressure
     * @param factory                 creates and validates resources
     * @param maxAge                  rotate a resource once it is older than this, or {@code null}
     * @param maxOperationsPerResource rotate a resource after this many operations, or 0 for unlimited
     */
    public BoundedPool(String name,
                       int size,
                       Duration borrowTimeout,
                       ResourceFactory<T> factory,
                       Duration maxAge,
                       long maxOperationsPerResource) {
        this.name = name == null || name.isBlank() ? "pool" : name.trim();
        if (size < 1) {
            throw new IllegalArgumentException("Pool '" + this.name + "' size must be >= 1 but was " + size);
        }
        this.size = size;
        this.borrowTimeout = Objects.requireNonNull(borrowTimeout, "borrowTimeout");
        if (borrowTimeout.isZero() || borrowTimeout.isNegative()) {
            throw new IllegalArgumentException("Pool '" + this.name + "' borrow timeout must be positive");
        }
        this.factory = Objects.requireNonNull(factory, "factory");
        this.maxAgeNanos = maxAge == null || maxAge.isZero() || maxAge.isNegative()
                ? Long.MAX_VALUE : maxAge.toNanos();
        this.maxOperationsPerResource = maxOperationsPerResource <= 0
                ? Long.MAX_VALUE : maxOperationsPerResource;
    }

    public String name() {
        return name;
    }

    public int configuredSize() {
        return size;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Eagerly creates every configured resource, which validates connectivity at startup and fails
     * fast instead of letting the first request discover a broken repository.
     *
     * <p>On failure every resource created so far is closed and the pool is left empty and
     * uninitialized, so the caller can retry or discard it.
     */
    public void initialize() throws Exception {
        lock.lock();
        try {
            ensureOpen();
            if (initialized) {
                throw new IllegalStateException("Pool '" + name + "' is already initialized");
            }
            if (capacityInUse() != 0) {
                throw new IllegalStateException("Pool '" + name + "' has already handed out resources");
            }
            initialized = true;
            creatingCount += size;
        } finally {
            lock.unlock();
        }

        List<Entry<T>> entries = new ArrayList<>(size);
        // A creation attempt that rolled the pool back with an UNPROVEN cleanup keeps its reserved slot
        // quarantined instead of being released, and the reservation it used must therefore not be part
        // of the blanket rollback below.
        boolean unprovenCreation = false;
        try {
            for (int i = 0; i < size; i++) {
                entries.add(newEntry(true));
            }
        } catch (Throwable failure) {
            // Roll back completely, whatever went wrong.
            //
            // The reservation moves from `creating` to `retiring` BEFORE anything is closed, so every
            // entry that was actually created finishes through the normal retirement path: its slot is
            // freed only when close() returned normally, and quarantined when the outcome is uncertain.
            // Decrementing creatingCount while closing would free capacity for resources that may still
            // exist.
            //
            // The attempt that FAILED is not in `entries` - the pool never received its resource - so its
            // own reservation is resolved here. It is released only when the failure proved that nothing
            // was left behind; an unproven cleanup is quarantined so no replacement can be created while
            // the physical resource may still exist.
            unprovenCreation = quarantineUnprovenCreation(failure);
            lock.lock();
            try {
                if (!unprovenCreation) {
                    creatingCount--;
                }
                creatingCount -= entries.size();
                retiringCount += entries.size();
                initialized = false;
                capacityChanged.signalAll();
            } finally {
                lock.unlock();
            }
            Error closeFailure = null;
            try {
                closeAllAndFinish(entries);
            } catch (Error fatal) {
                closeFailure = fatal;
            }
            if (closeFailure != null) {
                failure.addSuppressed(closeFailure);
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw (Exception) failure;
        }

        List<Entry<T>> discarded = null;
        lock.lock();
        try {
            if (closed) {
                // close() began while this pool was still being filled: these resources must never be
                // published into the idle set. The reservation moves from `creating` to `retiring`, so
                // each slot is freed only when its close actually succeeded.
                creatingCount -= size;
                retiringCount += entries.size();
                discarded = new ArrayList<>(entries);
            } else {
                creatingCount -= size;
                for (Entry<T> entry : entries) {
                    idle.addLast(entry);
                }
            }
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
        if (discarded != null) {
            closeAllAndFinish(discarded);
        }
    }

    /**
     * Borrows a resource, waiting up to the configured borrow timeout.
     *
     * @throws TimeoutException     when every slot is in use, i.e. backpressure
     * @throws InterruptedException when the calling thread is interrupted while waiting
     * @throws PoolException        when a reserved slot cannot be filled
     */
    public Lease<T> borrow() throws InterruptedException, TimeoutException {
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + borrowTimeout.toNanos();
        // Counted at the ATTEMPT, so a borrow refused because the pool is closed or exhausted is still
        // visible. Counting only the outcomes would show an operator one borrow where two were attempted.
        borrowCount.incrementAndGet();
        final Entry<T> entry;
        boolean waitRecorded = false;
        try {
            entry = acquire(deadlineNanos);
        } catch (TimeoutException e) {
            recordWait(startNanos);
            waitRecorded = true;
            borrowTimeoutCount.incrementAndGet();
            throw e;
        } finally {
            // The wait is recorded for EVERY attempt, including one refused because the pool closed or a
            // creation failed: borrowCount counts attempts now, so the latency metrics must describe the
            // same population or they would report 0 ms for a borrow that actually waited.
            if (!waitRecorded) {
                recordWait(startNanos);
            }
        }

        // A completed borrow/use/close cycle always counts as one usage. Operation-based rotation must
        // not depend on the caller remembering to invoke recordOperation(): an ordinary
        // try-with-resources block has to advance the budget, or a resource could sit at zero lifetime
        // usage forever and never rotate.
        entry.operations.incrementAndGet();
        automaticUsages.incrementAndGet();

        return new Lease<>(
                entry.value,
                lease -> release(entry),
                count -> {
                    entry.operations.addAndGet(count);
                    explicitOperations.addAndGet(count);
                },
                startNanos,
                name + "#" + entry.sequence);
    }

    /**
     * Retires idle resources that exceeded their age or operation budget or that fail validation.
     *
     * <p>This is the explicit rotation hook for a maintenance task. Rotation also happens
     * automatically when a lease is returned.
     *
     * @return how many idle resources were retired
     */
    public int rotateStale() {
        List<Entry<T>> stale = new ArrayList<>();
        Throwable probeFailure = null;
        lock.lock();
        try {
            if (closed) {
                return 0;
            }
            Iterator<Entry<T>> iterator = idle.iterator();
            while (iterator.hasNext()) {
                Entry<T> entry = iterator.next();
                Rotation rotation;
                try {
                    rotation = rotationFor(entry);
                } catch (Throwable failure) {
                    // A probe failure must not abort the whole sweep with nothing counted: the resource is
                    // treated as suspect and retired like any other, and the failure is reported after the
                    // sweep has been applied.
                    validationFailures.incrementAndGet();
                    rotation = Rotation.UNHEALTHY;
                    if (probeFailure == null) {
                        probeFailure = failure;
                    }
                }
                if (rotation == null || rotation == Rotation.SHUTDOWN) {
                    continue;
                }
                iterator.remove();
                retiringCount++;
                recordRotation(rotation);
                stale.add(entry);
            }
        } finally {
            lock.unlock();
        }

        closeAllAndFinish(stale);
        if (probeFailure != null) {
            if (probeFailure instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) probeFailure;
        }
        return stale.size();
    }

    /**
     * Waits until every lease has been returned and every in-flight creation or close has finished.
     *
     * <p>Deliberately unchanged by Goal 01C: this answers "has everything settled?", which is a useful
     * question while the pool is still open (a maintenance sweep, for example). It is NOT the shutdown
     * question - an open pool with nothing outstanding is perfectly quiescent and must still report
     * true here, or every existing caller would be broken by a semantic change it never asked for. Ask
     * {@link #closeState()} whether the SHUTDOWN is clean.
     *
     * @return true when the pool became quiescent within the timeout
     */
    public boolean awaitQuiescence(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        lock.lock();
        try {
            for (;;) {
                if (leasedCount == 0 && creatingCount == 0 && retiringCount == 0) {
                    return true;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                capacityChanged.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * The shutdown state of this pool, read under the same lock that authorises every capacity change.
     *
     * <p>The three-way answer is the point. Before Goal 01C a caller could only ask
     * {@link #closedWithUncertainResources()}, which is {@code false} both for a pool that released
     * everything AND for a pool that is still waiting for a lease to come back - so a repository switch
     * reading only that flag would create the next repository's connections while an old one was
     * demonstrably still alive. {@link CloseState#CLOSING} is that second case, and it is neither clean
     * nor uncertain: the resource is not leaking, it is simply still in use.
     *
     * <p>Ordering matters and is fixed here: {@code CLOSED_UNCERTAIN} is checked before anything else, so
     * a quarantined slot can never be described as pending, and pending can never be described as clean.
     */
    @Override
    public CloseState closeState() {
        lock.lock();
        try {
            if (!closed) {
                return CloseState.NOT_CLOSED;
            }
            if (quarantinedCount > 0) {
                return CloseState.CLOSED_UNCERTAIN;
            }
            if (leasedCount > 0 || creatingCount > 0 || retiringCount > 0) {
                // Close has begun and a physical resource is still outstanding: a lease is out with its
                // user, a creation is materialising a session, or a retirement has not finished closing.
                // None of those is evidence that the resource is gone.
                return CloseState.CLOSING;
            }
            return CloseState.CLOSED_CLEAN;
        } finally {
            lock.unlock();
        }
    }

    public PoolMetrics metrics() {
        lock.lock();
        try {
            long borrows = borrowCount.get();
            return new PoolMetrics(
                    name,
                    size,
                    idle.size(),
                    leasedCount,
                    creatingCount,
                    retiringCount,
                    quarantinedCount,
                    borrows,
                    borrowTimeoutCount.get(),
                    borrows == 0 ? 0.0 : (totalWaitNanos.get() / 1_000_000.0) / borrows,
                    maxWaitNanos.get() / 1_000_000.0,
                    createAttempts.get(),
                    initialCreations.get(),
                    replacementCreations.get(),
                    created.get(),
                    createFailures.get(),
                    createQuarantineFailures.get(),
                    closeAttempts.get(),
                    closeSuccesses.get(),
                    closeFailures.get(),
                    validationFailures.get(),
                    ageRotations.get(),
                    operationRotations.get(),
                    unhealthyRotations.get(),
                    automaticUsages.get(),
                    explicitOperations.get());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Graceful shutdown.
     *
     * <p>{@code closed} is set before the idle set is drained, so a lease returned concurrently is
     * retired by the returning thread rather than being added to a pool that no longer drains.
     * Leased resources are closed by the thread that returns them; use {@link #closeState()} - or
     * {@link #awaitQuiescence(Duration)} - to see whether that has finished.
     *
     * <p>A normal return therefore means "the idle resources are released and no new lease will be
     * handed out", never "every physical resource is gone". Counting the idle set as retiring before
     * closing it is what keeps that honest: an in-flight close is still a capacity slot, so the pool
     * reports {@link CloseState#CLOSING} rather than a clean shutdown until it finishes.
     */
    @Override
    public void close() {
        List<Entry<T>> drained = new ArrayList<>();
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            while (!idle.isEmpty()) {
                drained.add(idle.pollFirst());
            }
            // The drained resources are still open, so they keep their capacity slots until the close
            // below has actually returned. Without this, capacityInUse() would report 0 and
            // awaitQuiescence() would claim the pool is quiescent while up to `size` resources are
            // still alive - which is exactly what the documented trade-off promises not to happen.
            retiringCount += drained.size();
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
        closeAllAndFinish(drained);
    }

    /**
     * Reports whether this pool's shutdown left physical resources in an unknown state.
     *
     * <p>Quarantine is the only source of uncertainty. A resource whose {@code close()} threw may still
     * exist, so its slot deliberately stays consumed; that is invisible to a caller that only watches
     * for a thrown exception, because this pool handles the failure internally and returns normally.
     * Exposing it here is what lets {@code RepositoryContext} and {@code RepositoryManager} keep the
     * fail-closed switch rule without knowing anything about pools.
     *
     * <p>A pool that closed everything cleanly reports false, so an ordinary shutdown is never turned
     * into a failure.
     *
     * <p>This is deliberately the UNCERTAINTY question, not the SHUTDOWN question, and it answers
     * {@code false} for a pool that is still draining an outstanding lease. That case is not uncertain -
     * it is unfinished - and it is reported by {@link #closeState()} as {@link CloseState#CLOSING}.
     * Every caller that must know whether the physical resources are gone has to consult the state;
     * this boolean alone cannot express it.
     */
    @Override
    public boolean closedWithUncertainResources() {
        return closeState() == CloseState.CLOSED_UNCERTAIN;
    }

    @Override
    public String uncertainCloseDetail() {
        lock.lock();
        try {
            if (quarantinedCount == 0) {
                return "";
            }
            return quarantinedCount + " pooled resource(s) of '" + name + "' were quarantined: close() "
                    + "did not return normally, so the physical session or connection may still exist "
                    + "and its capacity stays consumed";
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- internals

    private Entry<T> acquire(long deadlineNanos) throws InterruptedException, TimeoutException {
        for (;;) {
            Entry<T> idleEntry = takeIdle();
            if (idleEntry != null) {
                return idleEntry;
            }
            if (reserveCreationSlot()) {
                return createReserved();
            }
            awaitCapacity(deadlineNanos);
        }
    }

    /** Takes a healthy idle resource, retiring any that fail validation. */
    private Entry<T> takeIdle() {
        Entry<T> result = null;
        List<Entry<T>> retiring = null;
        Throwable probeFailure = null;

        lock.lock();
        try {
            ensureOpen();
            while (!idle.isEmpty()) {
                Entry<T> entry = idle.pollFirst();
                Rotation rotation;
                boolean probeFailed = false;
                try {
                    rotation = rotationFor(entry);
                } catch (Throwable failure) {
                    // The health probe threw, so this resource's state is unknown. Retire it like any
                    // other suspect resource instead of letting it fall out of the bookkeeping: an
                    // unaccounted slot plus a physically alive resource is exactly how the pool would
                    // exceed its configured hard bound (measured before this fix: physical live 3..6
                    // with configured size 1, because the next borrow created a replacement).
                    validationFailures.incrementAndGet();
                    rotation = Rotation.UNHEALTHY;
                    probeFailed = true;
                    if (probeFailure == null) {
                        probeFailure = failure;
                    }
                }
                if (rotation != null && rotation != Rotation.SHUTDOWN) {
                    retiringCount++;
                    recordRotation(rotation);
                    if (retiring == null) {
                        retiring = new ArrayList<>(2);
                    }
                    retiring.add(entry);
                    if (probeFailed) {
                        // Stop scanning. Continuing would allow a later HEALTHY entry to be promoted to
                        // leased and then abandoned when the remembered probe failure is rethrown below,
                        // leaving it leased forever and unreachable - even for close() - which is a leaked
                        // session plus silent capacity loss.
                        break;
                    }
                    continue;
                }
                leasedCount++;
                result = entry;
                break;
            }
        } finally {
            lock.unlock();
        }

        if (retiring != null) {
            closeAllAndFinish(retiring);
        }
        if (probeFailure != null) {
            if (probeFailure instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) probeFailure;
        }
        return result;
    }

    /** Reserves one capacity slot for a creation that will happen outside the lock. */
    private boolean reserveCreationSlot() {
        lock.lock();
        try {
            ensureOpen();
            if (capacityInUse() < size) {
                creatingCount++;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Fills a capacity slot reserved by {@link #reserveCreationSlot()}.
     *
     * <p>Two failure modes are handled here because either would otherwise corrupt accounting:
     *
     * <ul>
     *   <li>the creation fails or throws an {@link Error} - the reserved slot must be given back for
     *       <em>every</em> outcome, or {@code creating} stays incremented forever and the pool
     *       permanently loses capacity;</li>
     *   <li>the pool is closed while the creation is still in flight - the resource must never be
     *       leased, because the invariant is that no new lease is handed out once close has begun. It is
     *       retired instead, so its slot is freed or quarantined through the normal path.</li>
     * </ul>
     */
    private Entry<T> createReserved() throws InterruptedException, TimeoutException {
        final Entry<T> entry;
        try {
            entry = newEntry(false);
        } catch (Throwable failure) {
            // The outcome decides whether the reserved slot may come back. See
            // quarantineUnprovenCreation: an attempt that could not prove it released what it allocated
            // keeps its slot consumed, so no replacement can be created on top of a resource that may
            // still exist.
            if (!quarantineUnprovenCreation(failure)) {
                releaseCreationSlot();
            }
            if (failure instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new PoolException("Pool '" + name + "' could not create a resource: "
                    + describe(failure), failure);
        }

        final boolean poolClosed;
        lock.lock();
        try {
            creatingCount--;
            poolClosed = closed;
            if (poolClosed) {
                retiringCount++;
            } else {
                leasedCount++;
            }
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }

        if (poolClosed) {
            boolean closedCleanly = false;
            try {
                closedCleanly = attemptClose(entry);
            } finally {
                finishRetirement(closedCleanly);
            }
            throw new IllegalStateException("Pool '" + name
                    + "' was closed while a resource was being created; no lease is handed out");
        }
        return entry;
    }

    /** Returns one reserved creation slot. Used by every failure path of a reserved creation. */
    private void releaseCreationSlot() {
        lock.lock();
        try {
            creatingCount--;
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Converts a reserved creation slot into a quarantined one when the failure reported an unproven
     * cleanup, and reports whether it did.
     *
     * <h2>Why a failed creation can consume capacity</h2>
     *
     * <p>The pool cannot see what a factory did before it threw. A factory that opened a physical CM
     * session and then failed to remove it leaves that session alive while the pool believes the
     * attempt was empty; releasing the slot would authorise a replacement next to a resource that is
     * still there, and the configured size is a hard <em>physical</em> bound. So the factory reports the
     * outcome (see {@link CreationFailure}) and an unproven cleanup keeps the slot consumed for the
     * lifetime of this pool - the same treatment a slot gets when a {@code close()} throws.
     *
     * <p>{@code creating--} and {@code quarantined++} happen in ONE lock section on purpose: the pool
     * must never be observable with the slot in neither state, because
     * {@code available + leased + creating + retiring + quarantined} is the identity that bounds every
     * later creation. A window with the slot in neither set would let the next borrow overshoot.
     *
     * <p>An ordinary exception - anything that is not a {@link CreationFailure} - keeps the historical
     * reading and returns {@code false}, so the slot is released. That is what keeps every existing
     * {@code ResourceFactory} implementation and every committed Goal 01 assertion working unchanged.
     * The residual risk of that default is recorded in {@code STATUS.md}.
     *
     * @return true when the slot was quarantined, false when the caller must release it
     */
    private boolean quarantineUnprovenCreation(Throwable failure) {
        if (!(failure instanceof CreationFailure creationFailure)
                || creationFailure.cleanupProven()) {
            return false;
        }
        lock.lock();
        try {
            creatingCount--;
            quarantinedCount++;
            createQuarantineFailures.incrementAndGet();
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
        return true;
    }

    private void awaitCapacity(long deadlineNanos) throws InterruptedException, TimeoutException {
        lock.lock();
        try {
            for (;;) {
                ensureOpen();
                if (!idle.isEmpty() || capacityInUse() < size) {
                    return;
                }
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("Pool '" + name + "' is exhausted: all " + size
                            + " resource(s) are in use and none was released within "
                            + borrowTimeout.toMillis() + " ms");
                }
                capacityChanged.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }

    private void release(Entry<T> entry) {
        Rotation rotation;
        Throwable probeFailure = null;
        try {
            rotation = rotationFor(entry);
        } catch (Throwable failure) {
            // The probe threw, so the resource's state is unknown: retire it. The lease must be handed
            // back regardless - losing it here would stick the slot as leased forever with
            // quarantined()==0 and degraded()==false, which is silent permanent degradation that no
            // metric would explain.
            validationFailures.incrementAndGet();
            rotation = Rotation.UNHEALTHY;
            probeFailure = failure;
        }
        boolean retire = rotation != null;

        lock.lock();
        try {
            leasedCount--;
            if (!retire && closed) {
                // close() ran between our rotation check and this point: retire rather than leak.
                retire = true;
                rotation = Rotation.SHUTDOWN;
            }
            if (retire) {
                retiringCount++;
            } else {
                idle.addLast(entry);
            }
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }

        if (retire) {
            if (rotation != Rotation.SHUTDOWN) {
                recordRotation(rotation);
            }
            boolean closedCleanly = false;
            try {
                closedCleanly = attemptClose(entry);
            } finally {
                // The slot is always accounted for: freed when close() returned normally, quarantined
                // when the outcome is uncertain. One bad resource must never corrupt the accounting.
                finishRetirement(closedCleanly);
            }
        }

        if (probeFailure != null) {
            if (probeFailure instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) probeFailure;
        }
    }

    /**
     * Ends one retirement: frees the slot when the close succeeded, or quarantines it when the close
     * outcome is uncertain.
     *
     * <p>This is the single place where a retirement returns capacity, so the two outcomes cannot be
     * confused. Quarantine deliberately consumes the slot: it may degrade the pool, and that is the safe
     * direction, because a close exception never proves that the physical connection disappeared.
     */
    private void finishRetirement(boolean closedCleanly) {
        lock.lock();
        try {
            retiringCount--;
            if (!closedCleanly) {
                quarantinedCount++;
            }
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes every entry, ends each retirement, and rethrows the first Error afterwards.
     *
     * <p>Two properties matter here and both are enforced by construction:
     *
     * <ul>
     *   <li>closing continues to the end of the list - a plain {@code try/finally} around a single close
     *       would let one failure abandon the remaining resources;</li>
     *   <li>a resource whose close threw has an UNCERTAIN physical outcome, so its slot is quarantined
     *       instead of freed. Freeing it would authorise a replacement while the old session or
     *       connection may still exist, which is exactly how a pool exceeds its configured physical
     *       hard bound.</li>
     * </ul>
     */
    private void closeAllAndFinish(List<Entry<T>> entries) {
        Error fatal = null;
        for (Entry<T> entry : entries) {
            boolean closedCleanly = false;
            try {
                closedCleanly = attemptClose(entry);
            } catch (Error e) {
                if (fatal == null) {
                    fatal = e;
                }
            } finally {
                finishRetirement(closedCleanly);
            }
        }
        if (fatal != null) {
            throw fatal;
        }
    }

    /**
     * Attempts to close one resource.
     *
     * @return true only when {@code close()} returned normally, which is the only evidence that the
     *         physical resource is gone. A thrown exception - or an Error, which propagates - means the
     *         outcome is uncertain, and the caller must quarantine the slot rather than free it.
     */
    private boolean attemptClose(Entry<T> entry) {
        closeAttempts.incrementAndGet();
        try {
            entry.value.close();
            closeSuccesses.incrementAndGet();
            return true;
        } catch (Exception e) {
            closeFailures.incrementAndGet();
            return false;
        } catch (Error e) {
            // An Error is still an unsuccessful close, so it is counted as a failure as well as
            // propagated. Without this, closeAttempts would not equal successes + failures and an
            // operator could not explain where capacity went.
            closeFailures.incrementAndGet();
            throw e;
        }
    }

    /** Returns the rotation reason, or {@code null} when the resource may stay in service. */
    private Rotation rotationFor(Entry<T> entry) {
        if (closed) {
            return Rotation.SHUTDOWN;
        }
        long now = System.nanoTime();
        if (maxAgeNanos != Long.MAX_VALUE && now - entry.createdAtNanos >= maxAgeNanos) {
            return Rotation.AGE;
        }
        if (maxOperationsPerResource != Long.MAX_VALUE
                && entry.operations.get() >= maxOperationsPerResource) {
            return Rotation.OPERATIONS;
        }
        if (!isHealthy(entry.value)) {
            validationFailures.incrementAndGet();
            return Rotation.UNHEALTHY;
        }
        return null;
    }

    private boolean isHealthy(T resource) {
        try {
            return factory.isHealthy(resource);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void recordRotation(Rotation rotation) {
        switch (rotation) {
            case AGE -> ageRotations.incrementAndGet();
            case OPERATIONS -> operationRotations.incrementAndGet();
            case UNHEALTHY -> unhealthyRotations.incrementAndGet();
            case SHUTDOWN -> { }
        }
    }

    private Entry<T> newEntry(boolean initial) throws Exception {
        createAttempts.incrementAndGet();
        final T resource;
        try {
            resource = factory.create();
        } catch (Throwable failure) {
            // Every failed creation is counted, including an Error, so that
            // createAttempts == created + createFailures always holds and capacity loss stays explainable.
            createFailures.incrementAndGet();
            if (failure instanceof Error error) {
                throw error;
            }
            throw (Exception) failure;
        }
        if (resource == null) {
            createFailures.incrementAndGet();
            throw new IllegalStateException("ResourceFactory '" + factory.describe()
                    + "' returned null for pool '" + name + "'");
        }
        created.incrementAndGet();
        // Initial population and replacement creation are different events, so they are counted
        // separately. Only the CM/JDBC adapter can say whether a creation was a reconnect, so this pool
        // deliberately publishes no "reconnect" counter.
        if (initial) {
            initialCreations.incrementAndGet();
        } else {
            replacementCreations.incrementAndGet();
        }
        return new Entry<>(resource, sequence.incrementAndGet(), System.nanoTime());
    }

    private int capacityInUse() {
        // Quarantined slots are consumed on purpose: an uncertain close may still hold a physical
        // session or connection, so its slot must not become available to a replacement.
        return idle.size() + leasedCount + creatingCount + retiringCount + quarantinedCount;
    }

    private void recordWait(long startNanos) {
        long waited = System.nanoTime() - startNanos;
        totalWaitNanos.addAndGet(waited);
        maxWaitNanos.accumulateAndGet(waited, Math::max);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Pool '" + name + "' is closed");
        }
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    @Override
    public String toString() {
        return "BoundedPool[name=" + name + ", size=" + size + ", closed=" + closed
                + ", closeState=" + closeState() + "]";
    }
}
