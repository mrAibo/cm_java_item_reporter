package com.mraibo.cminsight.test;

import com.mraibo.cminsight.connection.ResourceFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A test-only MUTANT: the pre-correction create-failure rule, kept as an executable control.
 *
 * <h2>Why a copy of the accounting exists in the test tree</h2>
 *
 * <p>Section A changes which {@code create()} failures may release a reserved capacity slot. A test that
 * only measures the CORRECTED pool proves nothing about that change: it can pass because the measurement
 * is too weak to see a breach, not because the bound held. So the mutation control has to measure the
 * other direction too, and the only honest way to do that is to run the old rule and watch the same
 * measuring factory report a physical overshoot.
 *
 * <p>{@link com.mraibo.cminsight.connection.BoundedPool} is {@code final} and is not injectable, so the
 * mutant cannot be produced by subclassing or by a flag. This class is therefore a deliberately minimal,
 * faithful re-statement of the pre-correction accounting for exactly one property: a reserved creation
 * slot is released whenever {@code create()} throws, whatever it threw. Everything else is kept
 * equivalent - the five-way accounting, creation outside the lock, a reserved slot per in-flight attempt,
 * backpressure with the borrow timeout, and idle resources closed by {@code close()} - because the
 * control is only meaningful if the two pools differ in the ONE respect under test and agree in the
 * rest.
 *
 * <h2>What it is not</h2>
 *
 * <p>Not production code and not a second implementation of the pool: it is never registered with
 * {@link SelfTest} as a suite, it is used by {@link UncertainCreationTest} alone, and its only output is
 * the physical leak it demonstrates. The corrected {@code BoundedPool} remains the only pool the product
 * has.
 */
final class LegacyReleasePool<T extends AutoCloseable> {

    private final String name;
    private final int size;
    private final Duration borrowTimeout;
    private final ResourceFactory<T> factory;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition capacityChanged = lock.newCondition();
    private final ArrayDeque<T> idle = new ArrayDeque<>();

    // Guarded by lock.
    private int leasedCount;
    private int creatingCount;
    private int retiringCount;
    private boolean closed;

    LegacyReleasePool(String name, int size, Duration borrowTimeout, ResourceFactory<T> factory) {
        this.name = name == null || name.isBlank() ? "mutant-pool" : name.trim();
        if (size < 1) {
            throw new IllegalArgumentException("mutant pool size must be >= 1 but was " + size);
        }
        this.size = size;
        this.borrowTimeout = Objects.requireNonNull(borrowTimeout, "borrowTimeout");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    int configuredSize() {
        return size;
    }

    int available() {
        lock.lock();
        try {
            return idle.size();
        } finally {
            lock.unlock();
        }
    }

    int leased() {
        lock.lock();
        try {
            return leasedCount;
        } finally {
            lock.unlock();
        }
    }

    int creating() {
        lock.lock();
        try {
            return creatingCount;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The mutant's own view of consumed capacity. It is the number the mutation control asserts on,
     * because a pool that believes it has free capacity is exactly what authorises the next overshooting
     * creation.
     */
    int capacityInUse() {
        lock.lock();
        try {
            return idle.size() + leasedCount + creatingCount + retiringCount;
        } finally {
            lock.unlock();
        }
    }

    /** The old rule never quarantines a creation failure: the slot is always given back. */
    int quarantined() {
        return 0;
    }

    T borrow() throws Exception {
        long deadlineNanos = System.nanoTime() + borrowTimeout.toNanos();
        lock.lock();
        try {
            for (;;) {
                if (closed) {
                    throw new IllegalStateException("Mutant pool '" + name + "' is closed");
                }
                if (!idle.isEmpty()) {
                    leasedCount++;
                    return idle.pollFirst();
                }
                if (capacityInUse() < size) {
                    creatingCount++;
                    break;
                }
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("Mutant pool '" + name + "' is exhausted: all " + size
                            + " slot(s) are in use and none was released within "
                            + borrowTimeout.toMillis() + " ms");
                }
                capacityChanged.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }

        final T resource;
        try {
            resource = factory.create();
        } catch (Throwable failure) {
            // ---------------- THE MUTATED RULE under test ----------------
            // "A throwing create() produced nothing." The reserved slot is given back unconditionally,
            // with no cleanup evidence asked for and none obtained. This is the pre-correction behaviour
            // of BoundedPool.createReserved()/quarantineUnprovenCreation().
            releaseCreationSlot();
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            if (failure instanceof Exception checked) {
                throw checked;
            }
            throw new IllegalStateException("the factory threw a Throwable this mutant cannot rethrow");
        }
        if (resource == null) {
            releaseCreationSlot();
            throw new IllegalStateException("Mutant pool '" + name + "': the factory returned null");
        }

        boolean poolClosed;
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
            boolean clean = false;
            try {
                clean = attemptClose(resource);
            } finally {
                finishRetirement(clean);
            }
            throw new IllegalStateException("Mutant pool '" + name
                    + "' was closed while a resource was being created");
        }
        return resource;
    }

    void release(T resource) {
        boolean retire;
        lock.lock();
        try {
            leasedCount--;
            retire = closed;
            if (retire) {
                retiringCount++;
            } else {
                idle.addLast(resource);
            }
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
        if (retire) {
            finishRetirement(attemptClose(resource));
        }
    }

    void close() {
        ArrayDeque<T> drained = new ArrayDeque<>();
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            while (!idle.isEmpty()) {
                drained.addLast(idle.pollFirst());
            }
            retiringCount += drained.size();
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
        for (T resource : drained) {
            finishRetirement(attemptClose(resource));
        }
    }

    private void releaseCreationSlot() {
        lock.lock();
        try {
            creatingCount--;
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private boolean attemptClose(T resource) {
        try {
            resource.close();
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private void finishRetirement(boolean clean) {
        lock.lock();
        try {
            retiringCount--;
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
