package com.mraibo.cminsight.connection;

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
 * Every capacity slot is accounted for under a single lock as one of four states: idle, leased,
 * being created, or retiring (closed but not yet finished closing). A borrow may only proceed when
 * the sum of those states is below the configured size, so the pool can <em>never</em> overshoot.
 * When the pool is exhausted a borrow waits up to the borrow timeout and then fails with
 * {@link TimeoutException}; exactly the required backpressure. There is no emergency-connection path
 * and no unbounded fallback: CM_Migrator's emergency connection behaviour is deliberately absent.
 *
 * <h2>Refill without threads</h2>
 *
 * There is no refill worker, no scheduled task and no thread pool. A retired or unhealthy resource
 * simply frees its slot, and the next borrow that needs capacity creates a replacement while holding
 * a reserved slot. Refill races are therefore impossible by construction, and a burst of retiring
 * resources cannot spawn a wave of creating threads.
 *
 * <h2>Strictness versus promptness</h2>
 *
 * A retiring resource keeps its slot until {@code close()} has actually returned. That means a slow
 * close can briefly delay a borrow, but the pool never opens a replacement while the old resource is
 * still alive. Bounded-but-slower was chosen over fast-but-overshooting because the hard bound is a
 * non-negotiable architecture rule.
 *
 * @param <T> pooled resource type
 */
public final class BoundedPool<T extends AutoCloseable> implements AutoCloseable {

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
    private boolean initialized;
    private volatile boolean closed;

    // Lock-free counters.
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong borrowCount = new AtomicLong();
    private final AtomicLong borrowTimeoutCount = new AtomicLong();
    private final AtomicLong totalWaitNanos = new AtomicLong();
    private final AtomicLong maxWaitNanos = new AtomicLong();
    private final AtomicLong createAttempts = new AtomicLong();
    private final AtomicLong created = new AtomicLong();
    private final AtomicLong createFailures = new AtomicLong();
    private final AtomicLong closedResources = new AtomicLong();
    private final AtomicLong validationFailures = new AtomicLong();
    private final AtomicLong ageRotations = new AtomicLong();
    private final AtomicLong operationRotations = new AtomicLong();
    private final AtomicLong unhealthyRotations = new AtomicLong();
    private final AtomicLong operationCount = new AtomicLong();

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
        try {
            for (int i = 0; i < size; i++) {
                entries.add(newEntry());
            }
        } catch (Throwable failure) {
            // Roll back completely, whatever went wrong. Every entry created so far is closed even if
            // one of them throws an Error while closing - aborting here would leave creatingCount
            // inflated and initialized() true, which makes every later borrow time out forever.
            // Note that these entries were reserved as `creating`, not as `retiring`, so the slot is
            // returned by decrementing creatingCount once, not by finishRetirement() per entry.
            Error closeFailure = null;
            for (Entry<T> entry : entries) {
                try {
                    closeEntry(entry);
                } catch (Error fatal) {
                    if (closeFailure == null) {
                        closeFailure = fatal;
                    }
                }
            }
            lock.lock();
            try {
                creatingCount -= size;
                initialized = false;
                capacityChanged.signalAll();
            } finally {
                lock.unlock();
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
            creatingCount -= size;
            if (closed) {
                discarded = new ArrayList<>(entries);
            } else {
                for (Entry<T> entry : entries) {
                    idle.addLast(entry);
                }
                capacityChanged.signalAll();
            }
        } finally {
            lock.unlock();
        }
        if (discarded != null) {
            closeAll(discarded);
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
        final Entry<T> entry;
        try {
            entry = acquire(deadlineNanos);
        } catch (TimeoutException e) {
            recordWait(startNanos);
            borrowCount.incrementAndGet();
            borrowTimeoutCount.incrementAndGet();
            throw e;
        }
        recordWait(startNanos);
        borrowCount.incrementAndGet();

        return new Lease<>(
                entry.value,
                lease -> release(entry),
                count -> {
                    entry.operations.addAndGet(count);
                    operationCount.addAndGet(count);
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
        lock.lock();
        try {
            if (closed) {
                return 0;
            }
            Iterator<Entry<T>> iterator = idle.iterator();
            while (iterator.hasNext()) {
                Entry<T> entry = iterator.next();
                Rotation rotation = rotationFor(entry);
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
        return stale.size();
    }

    /**
     * Waits until every lease has been returned and every in-flight creation or close has finished.
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
                    borrows,
                    borrowTimeoutCount.get(),
                    borrows == 0 ? 0.0 : (totalWaitNanos.get() / 1_000_000.0) / borrows,
                    maxWaitNanos.get() / 1_000_000.0,
                    createAttempts.get(),
                    created.get(),
                    createFailures.get(),
                    closedResources.get(),
                    validationFailures.get(),
                    ageRotations.get(),
                    operationRotations.get(),
                    unhealthyRotations.get(),
                    operationCount.get());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Graceful shutdown.
     *
     * <p>{@code closed} is set before the idle set is drained, so a lease returned concurrently is
     * retired by the returning thread rather than being added to a pool that no longer drains.
     * Leased resources are closed by the thread that returns them; use
     * {@link #awaitQuiescence(Duration)} to wait for that to finish.
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

        lock.lock();
        try {
            ensureOpen();
            while (!idle.isEmpty()) {
                Entry<T> entry = idle.pollFirst();
                Rotation rotation = rotationFor(entry);
                if (rotation != null && rotation != Rotation.SHUTDOWN) {
                    retiringCount++;
                    recordRotation(rotation);
                    if (retiring == null) {
                        retiring = new ArrayList<>(2);
                    }
                    retiring.add(entry);
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

    private Entry<T> createReserved() throws InterruptedException, TimeoutException {
        Entry<T> entry;
        try {
            entry = newEntry();
        } catch (Exception e) {
            lock.lock();
            try {
                creatingCount--;
                capacityChanged.signalAll();
            } finally {
                lock.unlock();
            }
            if (e instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw new PoolException("Pool '" + name + "' could not create a resource: " + describe(e), e);
        }

        lock.lock();
        try {
            creatingCount--;
            leasedCount++;
        } finally {
            lock.unlock();
        }
        return entry;
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
        Rotation rotation = rotationFor(entry);
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
            try {
                closeEntry(entry);
            } finally {
                // Return the slot even if close() throws an Error; otherwise the pool degrades
                // permanently because of one bad resource.
                finishRetirement();
            }
        }
    }

    private void finishRetirement() {
        lock.lock();
        try {
            retiringCount--;
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes every entry and rethrows the first Error afterwards, without touching the retirement
     * counter.
     *
     * <p>Used for entries that were reserved as {@code creating} and never entered the idle set, so
     * their slot is returned by the caller's own accounting rather than by {@code finishRetirement()}.
     * As with {@link #closeAllAndFinish}, the loop must reach the end of the list before the failure is
     * reported: stopping at the first Error would leave the remaining resources open forever.
     */
    private void closeAll(List<Entry<T>> entries) {
        Error fatal = null;
        for (Entry<T> entry : entries) {
            try {
                closeEntry(entry);
            } catch (Error e) {
                if (fatal == null) {
                    fatal = e;
                }
            }
        }
        if (fatal != null) {
            throw fatal;
        }
    }

    /**
     * Closes every entry, returns each reserved slot, and rethrows the first Error afterwards.
     *
     * <p>A plain {@code try/finally} around a single close is not enough here: it would return the
     * throwing entry's slot but let the Error abort the loop, leaving the remaining resources open and
     * their slots reserved forever. Closing must continue to the end of the list, and
     * {@code finishRetirement()} must run for every entry, before the failure is reported.
     */
    private void closeAllAndFinish(List<Entry<T>> entries) {
        Error fatal = null;
        for (Entry<T> entry : entries) {
            try {
                closeEntry(entry);
            } catch (Error e) {
                if (fatal == null) {
                    fatal = e;
                }
            } finally {
                finishRetirement();
            }
        }
        if (fatal != null) {
            throw fatal;
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

    private Entry<T> newEntry() throws Exception {
        createAttempts.incrementAndGet();
        final T resource;
        try {
            resource = factory.create();
        } catch (Exception e) {
            createFailures.incrementAndGet();
            throw e;
        }
        if (resource == null) {
            createFailures.incrementAndGet();
            throw new IllegalStateException("ResourceFactory '" + factory.describe()
                    + "' returned null for pool '" + name + "'");
        }
        created.incrementAndGet();
        return new Entry<>(resource, sequence.incrementAndGet(), System.nanoTime());
    }

    private void closeEntry(Entry<T> entry) {
        try {
            entry.value.close();
        } catch (Exception ignored) {
            // A resource that refuses to close must not stop retirement of the others.
        } finally {
            closedResources.incrementAndGet();
        }
    }

    private int capacityInUse() {
        return idle.size() + leasedCount + creatingCount + retiringCount;
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
        return "BoundedPool[name=" + name + ", size=" + size + ", closed=" + closed + "]";
    }
}
