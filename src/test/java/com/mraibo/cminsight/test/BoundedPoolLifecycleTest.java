package com.mraibo.cminsight.test;

import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.connection.PoolMetrics;
import com.mraibo.cminsight.connection.ResourceFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Startup, shutdown, refill after an unhealthy resource and the rotation budgets. */
public class BoundedPoolLifecycleTest {

    private static final Duration GENEROUS = Duration.ofSeconds(5);

    public void initializeFillsThePoolToOneResourcePerSlot() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("init", 3, Duration.ofSeconds(2), factory);

        pool.initialize();
        PoolMetrics metrics = pool.metrics();
        Assert.assertEquals(3, metrics.available(), "initialize creates one idle resource per slot");
        Assert.assertEquals(3L, metrics.created(), "three resources were created");
        Assert.assertEquals(3L, metrics.createAttempts(), "three creation attempts succeeded");
        Assert.assertEquals(3, factory.liveCount(), "three resources are alive");
        Assert.assertEquals(3, metrics.capacityInUse(), "capacity in use equals the configured size");
        Assert.assertThrows(IllegalStateException.class, pool::initialize, "a second initialize is refused");

        pool.close();
        Assert.assertEquals(0, factory.liveCount(), "close released every idle resource");
        Assert.assertEquals(3L, pool.metrics().closeSuccesses(), "every resource was closed exactly once");
    }

    /**
     * A partial {@code initialize()} failure closes what it created and quarantines the failed attempt's
     * reservation.
     *
     * <p>Rewritten for Goal 02A section A. This factory throws a plain {@link IOException} BEFORE it
     * allocates anything, and the pre-correction pool read that as "the attempt was empty" and returned the
     * reservation. That reading is exactly what the correction removes: a plain throw is not cleanup
     * evidence, so the failed attempt's slot is quarantined, the pool is left with one slot permanently
     * consumed, and it therefore refuses to be filled again - the honest operator-facing answer being "build
     * a new pool" rather than "retry into a slot whose physical outcome is unknown".
     *
     * <p>The resources that WERE created are still asserted to be closed: a quarantine of the failed
     * attempt must not abandon its neighbours.
     */
    public void initializeFailureClosesWhatItCreatedAndQuarantinesTheFailedSlot() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean failTheThird = new AtomicBoolean(true);
        ResourceFactory<FakeResource> flaky = new ResourceFactory<>() {
            @Override
            public FakeResource create() throws Exception {
                if (attempts.incrementAndGet() == 3 && failTheThird.getAndSet(false)) {
                    factory.failNextCreates(1);
                    return factory.create();
                }
                return factory.create();
            }

            @Override
            public boolean isHealthy(FakeResource resource) {
                return factory.isHealthy(resource);
            }

            @Override
            public String describe() {
                return "flaky";
            }
        };
        BoundedPool<FakeResource> pool = new BoundedPool<>("init-fail", 3, Duration.ofSeconds(2), flaky);

        Assert.assertThrows(IOException.class, pool::initialize, "a factory failure must propagate");
        Assert.assertEquals(2, factory.created().size(), "two resources had been created before the failure");
        Assert.assertEquals(2, factory.closed().size(), "the resources created so far were closed");
        Assert.assertEquals(0, factory.liveCount(), "nothing was leaked");
        PoolMetrics metrics = pool.metrics();
        Assert.assertEquals(0, metrics.available(), "the pool is left empty");
        Assert.assertEquals(0, metrics.leased(), "nothing is leased");
        Assert.assertEquals(0, metrics.creating(),
                "A: every reservation is resolved; the failing attempt is not left in flight");
        Assert.assertEquals(1, metrics.quarantined(),
                "A: a plain failure is not cleanup evidence, so the failed attempt's slot is quarantined");
        Assert.assertEquals(1L, metrics.createQuarantineFailures(), "A: and the event is counted");
        Assert.assertEquals(1, metrics.capacityInUse(),
                "A: the quarantined slot is the only capacity left accounted for");
        Assert.assertEquals(3L, metrics.createAttempts(), "the failing attempt is counted");
        Assert.assertEquals(1L, metrics.createFailures(), "the failure is counted");

        IllegalStateException refused = Assert.assertThrows(IllegalStateException.class, pool::initialize,
                "A: a pool that lost a slot to a quarantine must refuse to be filled again");
        Assert.assertTrue(refused.getMessage().contains("quarantined=1"),
                "A: the refusal names the quarantine rather than a phantom lease: " + refused.getMessage());
        Assert.assertEquals(1, metrics.capacityInUse(), "A: the refused retry changed no accounting");
        Assert.assertEquals(1, metrics.quarantined(), "A: and quarantined nothing further");
    }

    public void closeRefusesFurtherBorrowsAndReleasesEveryResource() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("shutdown", 2, Duration.ofMillis(200), factory);
        pool.initialize();
        Lease<FakeResource> outstanding = pool.borrow();
        FakeResource leasedResource = outstanding.value();
        FakeResource idleResource = factory.created().stream()
                .filter(resource -> resource != leasedResource)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected one idle resource"));
        Assert.assertFalse(idleResource.isClosed(), "the idle resource is alive before the shutdown");

        pool.close();
        Assert.assertTrue(pool.isClosed(), "the pool reports itself closed");
        Assert.assertTrue(idleResource.isClosed(), "an idle resource is closed by close()");
        Assert.assertFalse(leasedResource.isClosed(), "an outstanding lease is closed only when it is returned");
        Assert.assertFalse(pool.awaitQuiescence(Duration.ofMillis(200)),
                "an outstanding lease means the pool is not quiescent");
        Assert.assertThrows(IllegalStateException.class, pool::borrow, "a closed pool refuses to lend");
        Assert.assertThrows(IllegalStateException.class, pool::initialize, "a closed pool refuses to initialize");
        Assert.assertEquals(0, pool.rotateStale(), "a closed pool rotates nothing");

        outstanding.close();
        Assert.assertTrue(leasedResource.isClosed(), "returning the lease closes the resource");
        Assert.assertTrue(pool.awaitQuiescence(GENEROUS), "the pool becomes quiescent once the lease is back");
        Assert.assertEquals(2L, pool.metrics().closeSuccesses(), "both resources were closed exactly once");
        Assert.assertEquals(1, leasedResource.closeCalls(), "the returned lease closes its resource once");
        Assert.assertEquals(1, idleResource.closeCalls(), "close() closes an idle resource once");

        pool.close();
        Assert.assertEquals(2L, pool.metrics().closeSuccesses(), "close() is idempotent");
        Assert.assertEquals(1, leasedResource.closeCalls(), "an idempotent close re-closes nothing");
        Assert.assertEquals(1, idleResource.closeCalls(), "an idempotent close re-closes nothing");
        Assert.assertTrue(pool.isClosed(), "the pool stays closed");
    }

    public void leaseTracksOperationsAndRejectsUseAfterClose() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("lease", 1, Duration.ofSeconds(1), factory);
        Lease<FakeResource> lease = pool.borrow();

        Assert.assertNotNull(lease.value(), "an open lease exposes its resource");
        lease.recordOperation();
        lease.recordOperations(2);
        lease.recordOperations(0);
        lease.recordOperations(-5);
        Assert.assertEquals(3L, lease.operations(), "only positive operation counts are recorded");
        Assert.assertTrue(lease.heldMillis() >= 0L, "held time is a non-negative duration");
        Assert.assertFalse(lease.isClosed(), "a fresh lease is open");

        lease.close();
        Assert.assertTrue(lease.isClosed(), "the lease reports itself closed");
        Assert.assertThrows(IllegalStateException.class, lease::value, "a closed lease has no value");
        Assert.assertThrows(IllegalStateException.class, lease::recordOperation, "a closed lease records nothing");
        lease.close();

        Assert.assertEquals(0, pool.metrics().leased(), "the resource was returned to the idle set");
        Assert.assertEquals(1, pool.metrics().available(), "the resource is idle again");
        // Goal 01A (A3): the borrow itself is one automatic usage; the three explicit operations are
        // reported separately and operations() is their sum (3 explicit + 1 automatic = 4).
        Assert.assertEquals(1L, pool.metrics().automaticUsages(), "the borrow/use/close cycle is one usage");
        Assert.assertEquals(3L, pool.metrics().explicitOperations(), "the pool counted the explicit operations");
        Assert.assertEquals(4L, pool.metrics().operations(), "the reported total is the sum of both sources");
        pool.close();
    }

    public void anAgeBudgetRetiresTheResourceWhenItIsReturned() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("age", 1, Duration.ofSeconds(2), factory,
                Duration.ofMillis(50), 0);
        Lease<FakeResource> lease = pool.borrow();
        FakeResource resource = lease.value();
        Thread.sleep(120L);
        lease.close();

        Assert.assertTrue(resource.isClosed(), "an expired resource is closed on return");
        Assert.assertEquals(1L, pool.metrics().ageRotations(), "the age rotation is counted");
        Assert.assertEquals(0, pool.metrics().available(), "the expired resource left the idle set");
        Assert.assertEquals(0, pool.metrics().leased(), "the lease was returned");
        Assert.assertEquals(0L, pool.metrics().createFailures(), "rotation is not a creation failure");

        Lease<FakeResource> replacement = pool.borrow();
        Assert.assertEquals(2L, pool.metrics().createAttempts(), "the next borrow refills the freed slot");
        Assert.assertTrue(replacement.value().id() != resource.id(), "the replacement is a fresh resource");
        replacement.close();
        Assert.assertEquals(1L, pool.metrics().ageRotations(), "the fresh resource is not rotated again");
        Assert.assertEquals(1, pool.metrics().available(), "the fresh resource is idle");
        pool.close();
    }

    /**
     * Goal 01A (A3) re-derivation: the usage budget counts real usage, and a plain borrow/use/close
     * cycle is itself one usage.
     *
     * <p>Before Goal 01A an explicit {@code recordOperation()} was the only thing that advanced the
     * budget, so this scenario needed a budget of two. With automatic usage counting the same scenario
     * needs a budget of three: two automatic usages plus the one explicit operation.
     */
    public void anOperationBudgetRetiresTheResourceWhenItIsReturned() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("ops", 1, Duration.ofSeconds(2), factory, null, 3);

        Lease<FakeResource> first = pool.borrow();
        FakeResource resource = first.value();
        first.recordOperation();
        Assert.assertEquals(1L, first.operations(), "one operation is recorded on the lease");
        first.close();
        Assert.assertFalse(resource.isClosed(), "a resource below its operation budget stays in service");
        Assert.assertEquals(2L, pool.metrics().operations(),
                "one automatic usage plus one explicit operation are below the budget of three");

        Lease<FakeResource> second = pool.borrow();
        Assert.assertEquals(resource.id(), second.value().id(), "the same resource is lent again");
        second.close();

        Assert.assertTrue(resource.isClosed(), "reaching the operation budget retires the resource");
        Assert.assertEquals(1L, pool.metrics().operationRotations(), "the operation rotation is counted");
        Assert.assertEquals(2L, pool.metrics().automaticUsages(), "both cycles are counted automatically");
        Assert.assertEquals(1L, pool.metrics().explicitOperations(), "the explicit operation is counted separately");
        Assert.assertEquals(3L, pool.metrics().operations(), "the reported total is the sum of both sources");
        Assert.assertEquals(0, pool.metrics().available(), "the retired resource left the idle set");
        Assert.assertEquals(1L, pool.metrics().closeSuccesses(), "the retired resource was closed once");
        pool.close();
    }

    public void rotateStaleRetiresIdleResourcesThatExceededTheirBudget() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("stale", 2, Duration.ofSeconds(2), factory,
                Duration.ofMillis(50), 0);
        pool.initialize();
        Thread.sleep(120L);

        Assert.assertEquals(2, pool.rotateStale(), "both aged idle resources are retired");
        Assert.assertEquals(2L, pool.metrics().ageRotations(), "both rotations are counted");
        Assert.assertEquals(0, pool.metrics().available(), "the idle set is empty");
        Assert.assertEquals(0, factory.liveCount(), "every retired resource was closed");
        Assert.assertEquals(0, pool.rotateStale(), "a second pass finds nothing");

        Lease<FakeResource> fresh = pool.borrow();
        Assert.assertEquals(3L, pool.metrics().createAttempts(), "the freed slot is refilled on demand");
        Assert.assertEquals(2L, pool.metrics().ageRotations(), "the fresh resource is not rotated");
        Assert.assertEquals(1, factory.liveCount(), "exactly one resource was refilled for the borrowed slot");
        fresh.close();
        pool.close();
    }

    public void rotateStaleKeepsHealthyResourcesWhenThereIsNoBudget() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("no-stale", 2, Duration.ofSeconds(1), factory);
        pool.initialize();

        Assert.assertEquals(0, pool.rotateStale(), "without a budget nothing rotates");
        Assert.assertEquals(2, pool.metrics().available(), "both resources stay idle");
        Assert.assertEquals(2, factory.liveCount(), "both resources stay alive");
        Assert.assertEquals(0L, pool.metrics().closeSuccesses(), "nothing was closed");
        pool.close();
    }

    public void rotateStaleRetiresUnhealthyIdleResources() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("unhealthy-idle", 2, Duration.ofSeconds(1), factory);
        pool.initialize();
        FakeResource unhealthy = factory.created().get(0);
        unhealthy.setHealthy(false);

        Assert.assertEquals(1, pool.rotateStale(), "the unhealthy idle resource is retired");
        Assert.assertEquals(1L, pool.metrics().unhealthyRotations(), "the unhealthy rotation is counted");
        Assert.assertEquals(1L, pool.metrics().validationFailures(), "the failed health check is counted");
        Assert.assertTrue(unhealthy.isClosed(), "the unhealthy resource was closed");
        Assert.assertEquals(1, pool.metrics().available(), "the healthy resource stays idle");
        pool.close();
    }

    public void anUnhealthyResourceIsReplacedAndTheIdleSetRecovers() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("refill", 2, Duration.ofSeconds(2), factory);
        pool.initialize();
        Assert.assertEquals(2, pool.metrics().available(), "the pool starts full");

        Lease<FakeResource> lease = pool.borrow();
        FakeResource failing = lease.value();
        failing.setHealthy(false);
        lease.close();

        Assert.assertTrue(failing.isClosed(), "an unhealthy resource is closed when it is returned");
        Assert.assertEquals(1L, pool.metrics().unhealthyRotations(), "the unhealthy rotation is counted");
        Assert.assertEquals(1L, pool.metrics().validationFailures(), "the failed health check is counted");
        Assert.assertEquals(1, pool.metrics().available(), "the healthy resource is still idle");
        Assert.assertEquals(1L, pool.metrics().closeSuccesses(), "exactly one resource has been closed");
        Assert.assertEquals(1, factory.liveCount(), "only the healthy resource is alive");

        long attemptsBefore = pool.metrics().createAttempts();
        Lease<FakeResource> healthy = pool.borrow();
        Assert.assertEquals(1, pool.metrics().leased(), "the surviving resource is lent out");
        Assert.assertEquals(0, pool.metrics().available(), "no idle resource is left");

        Lease<FakeResource> replacement = pool.borrow();
        Assert.assertEquals(attemptsBefore + 1, pool.metrics().createAttempts(),
                "the freed slot is filled on demand, not in a wave");
        Assert.assertEquals(3L, pool.metrics().created(), "the replacement is a freshly created resource");
        Assert.assertEquals(2, pool.metrics().leased(), "both slots are lent out again");
        Assert.assertTrue(healthy.value().id() != replacement.value().id(), "the two leases are distinct");
        Assert.assertEquals(2, pool.metrics().capacityInUse(), "capacity in use stays at the bound");
        Assert.assertEquals(2, factory.peakLive(), "the refill never exceeded the bound");
        Assert.assertTrue(pool.metrics().capacityInUse() <= pool.configuredSize(),
                "capacity can never exceed the configured size");

        healthy.close();
        replacement.close();
        Assert.assertTrue(pool.awaitQuiescence(GENEROUS), "the pool returns to quiescence");
        Assert.assertEquals(2, pool.metrics().available(), "the idle set recovered to the configured size");
        Assert.assertEquals(2, factory.liveCount(), "one live resource per slot");
        Assert.assertEquals(1L, pool.metrics().closeSuccesses(), "only the unhealthy resource was ever closed");
        pool.close();
        Assert.assertEquals(3L, pool.metrics().closeSuccesses(), "close() retires the two surviving resources");
        Assert.assertEquals(0, factory.liveCount(), "nothing is left alive after close()");
    }

    /**
     * A factory failure on borrow surfaces as a {@code PoolException}, leaks nothing, and quarantines the
     * reserved slot.
     *
     * <p>Rewritten for Goal 02A section A: the pre-correction pool let the next borrow create a replacement
     * here, because the plain {@link IOException} was read as "this attempt produced nothing". That reading
     * is no longer part of the contract, so the decisive assertion is now the opposite one - the pool does
     * NOT recover in the same instance, it applies backpressure - and the "no leak" property is asserted
     * where it still holds: the factory allocated nothing at all, and nothing physically alive is left
     * unaccounted for.
     */
    public void aFactoryFailureOnBorrowSurfacesAsAPoolExceptionAndQuarantinesTheSlot() {
        FakePoolFactory factory = new FakePoolFactory();
        factory.failNextCreates(1);
        BoundedPool<FakeResource> pool = new BoundedPool<>("create-fail", 1, Duration.ofMillis(300), factory);

        com.mraibo.cminsight.connection.PoolException failure = Assert.assertThrows(
                com.mraibo.cminsight.connection.PoolException.class, pool::borrow,
                "a failed reservation must surface as PoolException");
        Assert.assertTrue(failure.getMessage().contains("create-fail"),
                "the message names the pool: " + failure.getMessage());
        Assert.assertEquals(0, factory.liveCount(), "nothing was left alive");
        Assert.assertEquals(1L, pool.metrics().createFailures(), "the failure is counted");
        Assert.assertEquals(1, pool.metrics().quarantined(),
                "A: a plain failure quarantines its reserved slot - releasing it is the removed rule");
        Assert.assertEquals(1L, pool.metrics().createQuarantineFailures(), "A: and the event is counted");
        Assert.assertEquals(0, pool.metrics().creating(), "A: nothing is left in flight");
        Assert.assertEquals(1, pool.metrics().capacityInUse(), "A: the quarantined slot consumes capacity");

        Assert.assertThrows(java.util.concurrent.TimeoutException.class, pool::borrow,
                "A: the pool must NOT hand out a replacement for a slot whose outcome is unknown");
        Assert.assertEquals(1, factory.createAttempts(),
                "A: no replacement creation was attempted on top of the unknown outcome");
        pool.close();
    }

    public void poolMetricsExposeTheConfiguredShape() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("metrics", 2, Duration.ofSeconds(1), factory);
        pool.initialize();
        PoolMetrics metrics = pool.metrics();
        Assert.assertEquals("metrics", metrics.name(), "the pool name is reported");
        Assert.assertEquals(2, metrics.configuredSize(), "the configured size is reported");
        Assert.assertEquals(2, metrics.available(), "the idle count is reported");
        Assert.assertEquals(0, metrics.leased(), "the leased count is reported");
        Assert.assertEquals(0, metrics.creating(), "the in-flight creation count is reported");
        Assert.assertEquals(0, metrics.retiring(), "the retiring count is reported");
        Assert.assertEquals(0L, metrics.borrowCount(), "no borrow yet");
        Assert.assertEquals(0L, metrics.borrowTimeoutCount(), "no timeout yet");
        Assert.assertEquals(0.0, metrics.averageBorrowWaitMs(), "no average wait yet");
        Assert.assertEquals(0.0, metrics.maxBorrowWaitMs(), "no maximum wait yet");
        Assert.assertEquals(0L, metrics.operations(), "no operations yet");
        Assert.assertEquals(2, metrics.capacityInUse(), "capacity in use equals the configured size");
        Assert.assertEquals(2, pool.configuredSize(), "the pool reports the same size");
        pool.close();
        Assert.assertTrue(pool.metrics().available() == 0, "a closed pool has no idle resources");
    }

    public void unhealthyResourcesAreNeverVisibleAboveTheBound() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("bound-refill", 2, Duration.ofSeconds(2), factory);
        pool.initialize();
        List<FakeResource> created = factory.created();
        for (FakeResource resource : created) {
            resource.setHealthy(false);
        }
        Assert.assertEquals(2, pool.rotateStale(), "both unhealthy idle resources are retired");
        Assert.assertEquals(0, factory.liveCount(), "the retired resources are closed before anything is replaced");

        int borrowed = 0;
        Lease<FakeResource> first = pool.borrow();
        borrowed++;
        Lease<FakeResource> second = pool.borrow();
        borrowed++;
        Assert.assertEquals(2, borrowed, "both slots are usable again after the refill");
        Assert.assertNotNull(first.value(), "the first replacement exists");
        Assert.assertNotNull(second.value(), "the second replacement exists");
        Assert.assertEquals(2, pool.metrics().leased(), "both slots are lent");
        Assert.assertTrue(factory.peakLive() <= 2, "the bound held through the refill (peak " + factory.peakLive() + ")");
        first.close();
        second.close();
        Assert.assertTrue(pool.awaitQuiescence(GENEROUS), "the pool returns to quiescence");
        pool.close();
    }

    /**
     * Regression (t4 F1): {@code close()} must keep the drained resources' capacity slots accounted
     * for until each resource's {@code close()} has really returned.
     *
     * <p>Fails against the pre-fix code, where {@code close()} drained the idle set without counting
     * the entries as retiring: {@code capacityInUse()} was 0 and {@code awaitQuiescence()} returned
     * true while up to {@code size} resources were still open.
     */
    public void closeKeepsCapacityAccountedUntilEveryResourceIsReallyClosed() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("close-capacity", 3, Duration.ofSeconds(2), factory);
        pool.initialize();
        Assert.assertEquals(3, factory.liveCount(), "three resources are alive before the shutdown");

        CountDownLatch gate = new CountDownLatch(1);
        factory.gateCloses(gate);
        Thread closer = new Thread(pool::close, "pool-closer");
        closer.setDaemon(true);
        closer.start();
        try {
            waitFor(() -> pool.metrics().retiring() > 0, Duration.ofSeconds(2));
            Assert.assertEquals(3, pool.metrics().retiring(),
                    "every drained resource is accounted as retiring while its close is in flight");
            Assert.assertEquals(3, pool.metrics().capacityInUse(),
                    "capacity in use is still the configured size while the resources are still open");
            Assert.assertEquals(3, factory.liveCount(), "the resources really are still open");
            Assert.assertFalse(pool.awaitQuiescence(Duration.ofMillis(150)),
                    "a pool that is still closing is not quiescent");
        } finally {
            gate.countDown();
            closer.join(20_000L);
            pool.close();
        }

        Assert.assertFalse(closer.isAlive(), "close() returned once the resources were released");
        Assert.assertTrue(pool.awaitQuiescence(GENEROUS), "the pool is quiescent once the closes completed");
        Assert.assertEquals(0, pool.metrics().capacityInUse(), "no capacity is left accounted after close()");
        Assert.assertEquals(0, factory.liveCount(), "every resource was really closed");
        Assert.assertEquals(pool.metrics().created(), pool.metrics().closeSuccesses(),
                "every created resource was closed exactly once");
    }

    /**
     * Goal 01A (A2): an {@link Error} from a resource's {@code close()} leaves the physical outcome
     * UNCERTAIN, so the slot must be QUARANTINED - never freed and never refilled.
     *
     * <p>This test replaces the Goal 01 test {@code anErrorFromAResourceCloseDoesNotBurnACapacitySlot},
     * which asserted the OLD, unsafe assumption: every close failure was treated as "the resource is
     * gone", the slot was released and a replacement was created into it. Goal 01A A2 removes exactly
     * that assumption, because a close exception proves nothing about the underlying session or
     * connection - freeing the slot would authorise a replacement while the old resource may still
     * exist, which is how a hard-bounded pool overshoots.
     *
     * <p>Fails against the pre-Goal-01A code, where {@code closeEntry()} swallowed the close failure and
     * {@code finishRetirement()} always returned the slot: {@code quarantined()} would be 0,
     * {@code capacityInUse()} would be 0 and the following borrow would create a replacement.
     */
    public void anErrorFromAResourceCloseQuarantinesTheSlotInsteadOfRefillingIt() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("error-close", 1, Duration.ofMillis(300), factory);
        Lease<FakeResource> lease = pool.borrow();
        FakeResource resource = lease.value();
        resource.setHealthy(false);
        resource.failCloseWith(new ResourceCloseError("resource close failed"));

        ResourceCloseError thrown = Assert.assertThrows(ResourceCloseError.class, lease::close,
                "an Error from the resource close still reaches the caller of lease.close()");
        Assert.assertTrue(thrown.getMessage().contains("resource close failed"), "the same Error is rethrown");

        PoolMetrics metrics = pool.metrics();
        Assert.assertEquals(0, metrics.retiring(), "the retirement itself ended");
        Assert.assertEquals(1, metrics.quarantined(), "the uncertain slot is quarantined instead of freed");
        Assert.assertTrue(metrics.degraded(), "the pool reports the degraded state");
        Assert.assertEquals(1, metrics.capacityInUse(), "the quarantined slot still consumes capacity");
        Assert.assertEquals(pool.configuredSize(), metrics.capacityInUse(),
                "the pool stays at its configured size with one slot quarantined");
        Assert.assertEquals(1L, metrics.closeAttempts(), "the close was attempted once");
        Assert.assertEquals(0L, metrics.closeSuccesses(), "the close did not succeed");
        Assert.assertEquals(1L, metrics.closeFailures(), "an Error from close() is counted as a failure too");
        Assert.assertEquals(metrics.closeAttempts(), metrics.closeSuccesses() + metrics.closeFailures(),
                "closeAttempts == closeSuccesses + closeFailures, even for a fatal close");
        Assert.assertTrue(resource.isClosed(), "this fake did release itself before throwing the Error");
        Assert.assertEquals(0, factory.liveCount(), "nothing is left alive in this scenario");

        // The decisive invariant: a quarantined slot authorises no replacement, even though this
        // particular fake really did release the resource. The pool cannot know that, and safety wins.
        Assert.assertThrows(java.util.concurrent.TimeoutException.class, pool::borrow,
                "a fully quarantined pool applies backpressure instead of creating a replacement");
        Assert.assertEquals(1, factory.createAttempts(), "no replacement was created into the quarantined slot");
        Assert.assertEquals(1, factory.peakLive(), "the physical bound held");

        pool.close();
        Assert.assertEquals(1, pool.metrics().quarantined(), "quarantine survives the shutdown");
        Assert.assertEquals(1L, pool.metrics().closeAttempts(), "shutdown does not re-close a quarantined slot");
    }

    /**
     * Regression (mutation-coverage gap found by the reliability review): a lease returned while the
     * pool is closing must still be closed.
     *
     * <p>{@code close()} empties the idle set once, so a resource parked in that set after the drain
     * would never be closed again. The only window in which that can happen is a lease return that
     * passed its rotation check before {@code close()} set the flag, so the factory's health check is
     * gated to park the returning thread exactly there.
     *
     * <p>Fails against a mutant that removes the {@code if (!retire && closed)} re-check in
     * {@code release()}: pool counters still look healthy, but the resource itself is never closed
     * ({@code closeCalls() == 0}, {@code available() == 1}, {@code capacityInUse() == 1},
     * {@code closed() == 0}); hence the assertion on the resource's own close count.
     */
    public void aLeaseReturnedWhileThePoolIsClosingIsStillClosed() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("close-race", 1, Duration.ofSeconds(2), factory);
        Lease<FakeResource> lease = pool.borrow();
        FakeResource resource = lease.value();

        CountDownLatch healthGate = new CountDownLatch(1);
        CountDownLatch parked = new CountDownLatch(1);
        factory.gateHealthChecks(healthGate, parked);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        Thread returning = new Thread(() -> {
            try {
                lease.close();
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }, "pool-lease-return");
        returning.setDaemon(true);
        returning.start();

        try {
            Assert.assertTrue(parked.await(5, TimeUnit.SECONDS),
                    "the returning thread is parked inside the rotation check");
            Assert.assertFalse(resource.isClosed(), "the resource is still open while the lease returns");
            pool.close();
            Assert.assertTrue(pool.isClosed(), "the pool is closed while the lease is still returning");
        } finally {
            healthGate.countDown();
            returning.join(20_000L);
        }

        Assert.assertFalse(returning.isAlive(), "the returning thread finished once the gate was released");
        Assert.assertTrue(failures.isEmpty(), "returning the lease did not fail: " + failures);
        Assert.assertEquals(1, resource.closeCalls(), "the resource itself was closed exactly once");
        Assert.assertTrue(resource.isClosed(), "a concurrent close() left the returned resource open");
        Assert.assertEquals(0, pool.metrics().leased(), "no lease is outstanding");
        Assert.assertEquals(0, pool.metrics().available(), "nothing was parked in the closed pool's idle set");
        Assert.assertEquals(0, pool.metrics().capacityInUse(), "the capacity slot was released");
        Assert.assertEquals(1L, pool.metrics().closeSuccesses(), "the pool counted the resource as closed");
        Assert.assertTrue(pool.awaitQuiescence(GENEROUS), "the pool reports quiescence");
        Assert.assertTrue(resource.isClosed(), "quiescence is never reported while a resource is still open");
    }

    private static void waitFor(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(2L);
        }
    }
}
