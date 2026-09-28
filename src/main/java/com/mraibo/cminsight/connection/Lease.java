package com.mraibo.cminsight.connection;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * A borrowed resource that returns itself to the pool exactly once.
 *
 * <p>Usage is the documented pattern:
 *
 * <pre>{@code
 * try (Lease<CmSession> lease = cmPool.borrow()) {
 *     CmSession session = lease.value();
 *     lease.recordOperation();
 * }
 * }</pre>
 *
 * <p>Operation accounting is what drives the pool's operation-based rotation, so a long-lived
 * resource is replaced before it becomes a liability. Calls through an already closed lease are
 * rejected rather than silently ignored, because using a lease after its scope has ended is a bug.
 */
public final class Lease<T> implements AutoCloseable {

    private final T value;
    private final Consumer<Lease<T>> releaser;
    private final LongConsumer operationRecorder;
    private final long acquiredAtNanos;
    private final String description;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong operations = new AtomicLong();

    Lease(T value,
          Consumer<Lease<T>> releaser,
          LongConsumer operationRecorder,
          long acquiredAtNanos,
          String description) {
        this.value = Objects.requireNonNull(value, "value");
        this.releaser = Objects.requireNonNull(releaser, "releaser");
        this.operationRecorder = operationRecorder == null ? count -> { } : operationRecorder;
        this.acquiredAtNanos = acquiredAtNanos;
        this.description = description == null ? "resource" : description;
    }

    /**
     * The borrowed resource.
     *
     * @throws IllegalStateException when the lease is already closed
     */
    public T value() {
        if (closed.get()) {
            throw new IllegalStateException("Lease is already closed: " + description);
        }
        return value;
    }

    /** Records one completed operation against this resource and the owning pool. */
    public void recordOperation() {
        recordOperations(1);
    }

    /** Records {@code count} completed operations. Non-positive counts are ignored. */
    public void recordOperations(long count) {
        if (count <= 0) {
            return;
        }
        if (closed.get()) {
            throw new IllegalStateException("Lease is already closed: " + description);
        }
        operations.addAndGet(count);
        operationRecorder.accept(count);
    }

    /** Operations recorded through this lease. */
    public long operations() {
        return operations.get();
    }

    /** How long this lease has been held, in milliseconds. */
    public long heldMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos);
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Stable identifier of the underlying resource, for diagnostics. */
    public String description() {
        return description;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            releaser.accept(this);
        }
    }

    @Override
    public String toString() {
        return "Lease[" + description + ", operations=" + operations.get() + ", closed=" + closed.get() + "]";
    }
}
