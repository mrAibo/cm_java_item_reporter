package com.mraibo.cminsight.test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake pooled resource whose lifetime is observable.
 *
 * <p>{@link FakePoolFactory} counts one live resource on creation and one fewer on the first
 * {@code close()} call, so the peak of that counter is a direct measurement of how many resources
 * the pool allowed to exist at the same time.
 */
final class FakeResource implements AutoCloseable {

    private final int id;
    private final FakePoolFactory owner;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger closeCalls = new AtomicInteger();
    private volatile boolean healthy = true;
    private volatile Error closeError;

    FakeResource(int id, FakePoolFactory owner) {
        this.id = id;
        this.owner = owner;
    }

    int id() {
        return id;
    }

    boolean isHealthy() {
        return healthy;
    }

    void setHealthy(boolean value) {
        this.healthy = value;
    }

    boolean isClosed() {
        return closed.get();
    }

    /** Makes every subsequent {@code close()} throw this Error, after the resource state is updated. */
    void failCloseWith(Error error) {
        this.closeError = error;
    }

    /** How many times {@code close()} was called, including repeated calls. */
    int closeCalls() {
        return closeCalls.get();
    }

    @Override
    public void close() {
        owner.awaitCloseGate();
        closeCalls.incrementAndGet();
        if (closed.compareAndSet(false, true)) {
            owner.onClose(this);
        }
        Error error = this.closeError;
        if (error != null) {
            throw error;
        }
    }

    @Override
    public String toString() {
        return "FakeResource#" + id;
    }
}
