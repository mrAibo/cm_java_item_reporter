package com.mraibo.cminsight.connection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class BoundedPool<T extends AutoCloseable> implements AutoCloseable {
    private final int size;
    private final Duration borrowTimeout;
    private final ResourceFactory<T> factory;
    private final ArrayBlockingQueue<T> available;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger leased = new AtomicInteger();
    private final AtomicLong borrowCount = new AtomicLong();
    private final AtomicLong totalWaitNanos = new AtomicLong();
    private final AtomicLong maxWaitNanos = new AtomicLong();
    private final AtomicLong created = new AtomicLong();
    private final AtomicLong closedResources = new AtomicLong();
    private final AtomicLong validationFailures = new AtomicLong();

    public BoundedPool(int size, Duration borrowTimeout, ResourceFactory<T> factory) {
        if (size < 1) throw new IllegalArgumentException("Pool size must be >= 1");
        this.size = size;
        this.borrowTimeout = Objects.requireNonNull(borrowTimeout, "borrowTimeout");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.available = new ArrayBlockingQueue<>(size);
    }

    public synchronized void initialize() throws Exception {
        ensureOpen();
        if (!available.isEmpty() || created.get() != 0) throw new IllegalStateException("Pool already initialized");
        List<T> started = new ArrayList<>();
        try {
            for (int i = 0; i < size; i++) {
                T resource = factory.create();
                started.add(resource);
                created.incrementAndGet();
            }
            available.addAll(started);
        } catch (Exception e) {
            for (T resource : started) safeClose(resource);
            throw e;
        }
    }

    public Lease<T> borrow() throws InterruptedException, TimeoutException {
        ensureOpen();
        long start = System.nanoTime();
        T resource = available.poll(borrowTimeout.toMillis(), TimeUnit.MILLISECONDS);
        long waited = System.nanoTime() - start;
        borrowCount.incrementAndGet();
        totalWaitNanos.addAndGet(waited);
        maxWaitNanos.accumulateAndGet(waited, Math::max);
        if (resource == null) throw new TimeoutException("Pool exhausted after " + borrowTimeout.toMillis() + " ms");
        leased.incrementAndGet();
        return new Lease<>(resource, this::release);
    }

    private void release(T resource) {
        leased.decrementAndGet();
        if (closed.get()) {
            safeClose(resource);
            return;
        }
        if (!factory.isHealthy(resource)) {
            validationFailures.incrementAndGet();
            safeClose(resource);
            refillOne();
            return;
        }
        if (!available.offer(resource)) safeClose(resource);
    }

    private void refillOne() {
        if (closed.get()) return;
        try {
            T replacement = factory.create();
            created.incrementAndGet();
            if (!available.offer(replacement)) safeClose(replacement);
        } catch (Exception ignored) {
            // Deliberately stay degraded instead of exceeding the configured bound.
        }
    }

    public PoolMetrics metrics() {
        long borrows = borrowCount.get();
        return new PoolMetrics(size, available.size(), leased.get(), borrows,
                borrows == 0 ? 0.0 : (totalWaitNanos.get() / 1_000_000.0) / borrows,
                maxWaitNanos.get() / 1_000_000.0,
                created.get(), closedResources.get(), validationFailures.get());
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Pool is closed");
    }

    private void safeClose(T resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception ignored) {
        } finally {
            closedResources.incrementAndGet();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        T resource;
        while ((resource = available.poll()) != null) safeClose(resource);
    }
}
