package com.mraibo.cminsight.test;

import java.io.IOException;
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
    private volatile IOException uncertainClose;
    private volatile Error probeError;

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

    /**
     * Makes every subsequent {@code close()} throw <em>before</em> the resource marks itself closed and
     * before the factory's live count drops.
     *
     * <p>That is the uncertain outcome Goal 01A A2 is about: the exception proves nothing about the
     * physical resource, which may still exist. The pool must quarantine the slot rather than free it,
     * and must never authorise a replacement while this fake still counts as live.
     */
    void failCloseUncertain(IOException exception) {
        this.uncertainClose = exception;
    }

    /**
     * Makes THIS resource's health probe throw the Error, while every other resource keeps probing
     * normally. Pass {@code null} to clear it again.
     *
     * <p>A pool holding several idle resources needs to see one hostile resource among healthy ones:
     * that is the only shape in which a scan that continues past a probe failure can promote a healthy
     * neighbour and then abandon it.
     */
    void failProbeWith(Error error) {
        this.probeError = error;
    }

    /** The Error this resource's probe must throw, or {@code null}. */
    Error probeError() {
        return probeError;
    }

    /** How many times {@code close()} was called, including repeated calls. */
    int closeCalls() {
        return closeCalls.get();
    }

    @Override
    public void close() throws IOException {
        owner.awaitCloseGate();
        closeCalls.incrementAndGet();
        IOException uncertain = this.uncertainClose;
        if (uncertain != null) {
            // Deliberately BEFORE the resource marks itself closed: the physical outcome is unknown.
            throw uncertain;
        }
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
