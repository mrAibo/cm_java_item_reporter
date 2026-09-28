package com.mraibo.cminsight.test;

import com.mraibo.cminsight.connection.ResourceFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The measuring device for the pool tests.
 *
 * <p>{@code live} is incremented when a resource is handed out by {@link #create()} and decremented
 * the first time that resource is closed, so {@link #peakLive()} is the highest number of resources
 * that were simultaneously alive. A pool that keeps a hard bound on capacity can never have more
 * live resources than its configured size, so an overshoot shows up as {@code peakLive() > size}
 * and fails the test instead of hiding behind an averaged metric.
 */
final class FakePoolFactory implements ResourceFactory<FakeResource> {

    private final AtomicInteger live = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicInteger creations = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger failNextCreates = new AtomicInteger();
    private final List<FakeResource> created = Collections.synchronizedList(new ArrayList<>());
    private final List<FakeResource> closed = Collections.synchronizedList(new ArrayList<>());
    private volatile CountDownLatch closeGate;
    private volatile CountDownLatch healthGate;
    private volatile CountDownLatch healthEntered;

    @Override
    public FakeResource create() throws Exception {
        creations.incrementAndGet();
        int failing = failNextCreates.getAndUpdate(remaining -> Math.max(0, remaining - 1));
        if (failing > 0) {
            failures.incrementAndGet();
            throw new IOException("simulated resource creation failure");
        }
        FakeResource resource = new FakeResource(sequence.incrementAndGet(), this);
        created.add(resource);
        int now = live.incrementAndGet();
        peak.accumulateAndGet(now, Math::max);
        return resource;
    }

    @Override
    public boolean isHealthy(FakeResource resource) {
        awaitHealthGate();
        return resource != null && resource.isHealthy();
    }

    @Override
    public String describe() {
        return "fake-resource";
    }

    void onClose(FakeResource resource) {
        live.decrementAndGet();
        closed.add(resource);
    }

    /** Makes the next {@code count} creation attempts fail. */
    void failNextCreates(int count) {
        failNextCreates.set(count);
    }

    /**
     * Makes every resource's {@code close()} block until the gate is released, so a test can observe
     * the pool's capacity accounting while a retirement is genuinely still in progress.
     */
    void gateCloses(CountDownLatch gate) {
        this.closeGate = gate;
    }

    /**
     * Makes every {@code isHealthy()} check park until the gate is released, and counts down
     * {@code entered} as soon as one parks. That lets a test hold the returning thread inside
     * {@code BoundedPool.release()}'s rotation check, which is the only window in which the pool's
     * "close() ran meanwhile" re-check matters.
     */
    void gateHealthChecks(CountDownLatch gate, CountDownLatch entered) {
        this.healthGate = gate;
        this.healthEntered = entered;
    }

    private void awaitHealthGate() {
        CountDownLatch gate = healthGate;
        if (gate == null) {
            return;
        }
        CountDownLatch entered = healthEntered;
        if (entered != null) {
            entered.countDown();
        }
        try {
            gate.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Blocks a resource close until the gate is released. Bounded, so no test can hang forever. */
    void awaitCloseGate() {
        CountDownLatch gate = closeGate;
        if (gate == null) {
            return;
        }
        try {
            gate.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    int liveCount() {
        return live.get();
    }

    int peakLive() {
        return peak.get();
    }

    List<FakeResource> created() {
        synchronized (created) {
            return List.copyOf(created);
        }
    }

    List<FakeResource> closed() {
        synchronized (closed) {
            return List.copyOf(closed);
        }
    }

    int createAttempts() {
        return creations.get();
    }

    int createFailures() {
        return failures.get();
    }
}
