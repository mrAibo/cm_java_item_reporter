package com.mraibo.cminsight.test;

import com.mraibo.cminsight.connection.CreationFailure;
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
    private final AtomicInteger failNextAfterAllocation = new AtomicInteger();
    private final AtomicInteger leakedFailures = new AtomicInteger();
    private volatile boolean failAfterAllocationCleanupProven;
    /**
     * Remaining "plain failure after allocation, resource deliberately left alive" attempts; -1 means
     * every attempt. Separate from {@link #failNextAfterAllocation} so a test cannot confuse the
     * explicit-verdict shape with the untyped one.
     */
    private final AtomicInteger unknownLeakFailures = new AtomicInteger();
    private volatile java.util.function.Supplier<Throwable> unknownLeakFailure;
    private final List<FakeResource> created = Collections.synchronizedList(new ArrayList<>());
    private final List<FakeResource> closed = Collections.synchronizedList(new ArrayList<>());
    private volatile CountDownLatch closeGate;
    private volatile CountDownLatch healthGate;
    private volatile CountDownLatch healthEntered;
    private volatile CountDownLatch createGate;
    private volatile CountDownLatch createEntered;
    private volatile Error healthError;

    /** Live resources a fail-after-allocation attempt leaked, for the test to assert on. */
    int leakedFailures() {
        return leakedFailures.get();
    }

    @Override
    public FakeResource create() throws Exception {
        creations.incrementAndGet();
        int failing = failNextCreates.getAndUpdate(remaining -> Math.max(0, remaining - 1));
        if (failing > 0) {
            failures.incrementAndGet();
            throw new IOException("simulated resource creation failure");
        }

        // Allocate FIRST, then decide whether to fail. A resource created on this path is recorded by
        // live/peak/created exactly like a successful one, so a test can prove that the pool kept the
        // slot consumed while the resource was still alive.
        FakeResource resource = new FakeResource(sequence.incrementAndGet(), this);
        created.add(resource);
        int now = live.incrementAndGet();
        peak.accumulateAndGet(now, Math::max);

        // The untyped shape: the resource physically exists, the factory then fails with a PLAIN
        // exception (or Error) and never closes it. This is the case section A is about - the pool gets
        // no cleanup evidence at all, so it must not free the slot on the strength of the throw alone.
        if (consumeUnknownLeakFailure()) {
            failures.incrementAndGet();
            leakedFailures.incrementAndGet();
            throwUnknownLeakFailure();
        }

        int failingAfterAllocation =
                failNextAfterAllocation.getAndUpdate(remaining -> Math.max(0, remaining - 1));
        if (failingAfterAllocation > 0) {
            failures.incrementAndGet();
            leakedFailures.incrementAndGet();
            boolean proven = failAfterAllocationCleanupProven;
            if (proven) {
                // The factory really did release what it allocated: the live count drops, so releasing
                // the reserved slot is honest.
                resource.close();
            }
            // Deliberately NOT counted by onClose() when unproven: the resource stays live, which is
            // what "the physical outcome is unknown" means for this fake.
            throw new CreationFailure(
                    proven ? CreationFailure.Cleanup.PROVEN_CLEAN : CreationFailure.Cleanup.UNPROVEN,
                    "simulated failure after allocation (cleanup "
                            + (proven ? "proven clean" : "unproven") + ")");
        }

        awaitCreateGate();
        return resource;
    }

    /**
     * Goal 02B: the health policy this factory states is a real LOCAL state read - {@link
     * FakeResource#isHealthy()} is one volatile field, with no I/O, no vendor call and no lock other than
     * the deterministic gate the tests install. It deliberately delegates rather than returning a constant,
     * because the fake resource HAS a health state ("a returned lease retires it"), which is exactly the
     * case the corner method must read.
     */
    @Override
    public boolean isHealthy(FakeResource resource) {
        awaitHealthGate();
        Error error = healthError;
        if (error != null) {
            // An Error is not a RuntimeException, so the pool's "the probe said no" path cannot catch
            // it: this is the case that must not lose the resource's capacity slot.
            throw error;
        }
        if (resource != null && resource.probeError() != null) {
            // A probe that fails for ONE resource only, so a multi-resource pool can prove that a scan
            // which continues past the failure does not abandon a healthy neighbour.
            throw resource.probeError();
        }
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
     * Makes the next {@code count} creation attempts fail AFTER allocating a resource, reporting the
     * cleanup as unproven.
     *
     * <p>This is the shape Goal 02 section C exists for, and the shape {@link #failNextCreates(int)}
     * cannot express: that one throws before {@code new FakeResource(...)}, so nothing was ever
     * allocated and releasing the reserved slot is genuinely correct. Here the resource IS allocated -
     * {@code live}, {@code peak} and {@code created} all record it - and the failure then reports
     * {@link CreationFailure.Cleanup#UNPROVEN}, which obliges the pool to QUARANTINE the slot rather than
     * free it. {@code liveCount()} therefore stays raised, and that is the point: a pool that released
     * the slot would hand out capacity for a resource that is demonstrably still alive.
     *
     * @param count          how many attempts fail this way
     * @param cleanupProven  true to report a proven-clean cleanup instead, which must release the slot
     */
    void failNextCreatesAfterAllocation(int count, boolean cleanupProven) {
        failNextAfterAllocation.set(count);
        failAfterAllocationCleanupProven = cleanupProven;
    }

    /**
     * Makes the next {@code count} creation attempts allocate a resource and then fail with a PLAIN,
     * UNTYPED throwable - an {@link java.io.IOException}, a {@link RuntimeException} or an {@link Error}
     * supplied by the caller - WITHOUT closing the allocated resource.
     *
     * <p>This is the shape section A is about and the shape neither existing mode can express: a plain
     * throw carries no cleanup evidence whatsoever, while {@code live} was already raised, so the physical
     * resource provably still exists. The pool must therefore quarantine the reserved slot by default; a
     * pool that released it would authorise a replacement beside a resource that is demonstrably alive.
     *
     * <p>The measured consequence is {@link #liveCount()} rising by one per attempt and - on a
     * corrected pool - {@link #peakLive()} staying at the configured size.
     *
     * @param count   how many attempts fail this way; negative means EVERY attempt
     * @param failure supplies the throwable for one attempt, so each attempt gets a fresh instance
     */
    void failNextCreatesAfterAllocationLeaking(int count, java.util.function.Supplier<Throwable> failure) {
        unknownLeakFailure = java.util.Objects.requireNonNull(failure, "failure");
        unknownLeakFailures.set(count);
    }

    /**
     * Makes EVERY creation attempt allocate a resource and then fail with a plain throwable, leaving it
     * alive. Used by the mutation control, where the number of attempts is the measurement.
     */
    void failEveryCreateAfterAllocationLeaking(java.util.function.Supplier<Throwable> failure) {
        failNextCreatesAfterAllocationLeaking(-1, failure);
    }

    private boolean consumeUnknownLeakFailure() {
        int remaining = unknownLeakFailures.get();
        if (remaining == 0 || unknownLeakFailure == null) {
            return false;
        }
        if (remaining > 0) {
            unknownLeakFailures.updateAndGet(value -> Math.max(0, value - 1));
        }
        return true;
    }

    /**
     * Throws the configured untyped failure unchanged: an {@link Error} as an Error, an exception as
     * itself. Nothing is wrapped, because the pool's decision must be made on the raw type.
     */
    private void throwUnknownLeakFailure() throws Exception {
        Throwable failure = unknownLeakFailure.get();
        if (failure instanceof Error fatal) {
            throw fatal;
        }
        if (failure instanceof Exception checked) {
            throw checked;
        }
        throw new IllegalStateException("the fake was told to fail with a Throwable it cannot throw: "
                + failure);
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

    /**
     * Makes every {@code create()} park after the resource physically exists (live count raised, peak
     * recorded) but before it is returned to {@code BoundedPool}, and counts down {@code entered} as
     * soon as one parks.
     *
     * <p>That is the only window in which {@code pool.close()} can race an in-flight lazy creation, so
     * a test can prove that the just-created resource is retired instead of being handed out.
     */
    void gateCreates(CountDownLatch entered, CountDownLatch gate) {
        this.createEntered = entered;
        this.createGate = gate;
    }

    /**
     * Makes every {@code isHealthy()} probe throw this {@link Error}.
     *
     * <p>The pool catches a {@code RuntimeException} from the probe as "unhealthy", but an Error escapes
     * that path entirely - so this is the case in which the resource's capacity slot used to be lost
     * while the resource was still physically alive. Pass {@code null} to restore normal probing.
     */
    void failHealthChecksWith(Error error) {
        this.healthError = error;
    }

    private void awaitCreateGate() {
        CountDownLatch gate = createGate;
        if (gate == null) {
            return;
        }
        CountDownLatch entered = createEntered;
        if (entered != null) {
            entered.countDown();
        }
        try {
            gate.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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
