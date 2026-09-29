package com.mraibo.cminsight.test;

import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.connection.PoolMetrics;
import com.mraibo.cminsight.connection.ResourceFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Goal 01A section A: regression tests for the corrected pool semantics.
 *
 * <p>The existing pool tests prove the hard bound under ordinary load. These tests pin the four
 * corrections the architecture review demanded, and they are written so that they FAIL if the fix is
 * reverted - each test documents the mutation it catches:
 *
 * <ul>
 *   <li><b>A1</b> a {@code close()} that races an in-flight lazy creation hands out no lease and
 *       retires the just-created resource; a creation that throws an {@link Error} still returns its
 *       reserved capacity slot;</li>
 *   <li><b>A2</b> a close whose outcome is UNCERTAIN never frees physical capacity: the slot is
 *       quarantined, no replacement is created into it, a fully quarantined pool applies backpressure,
 *       and shutdown still offers every remaining resource to {@code close()};</li>
 *   <li><b>A3</b> a plain try-with-resources borrow/use/close cycle advances the operation budget with
 *       no explicit {@code recordOperation()} call, and explicit counts add on top;</li>
 *   <li><b>A4</b> metrics stay truthful and specific: initial vs replacement creations, close
 *       attempt/success/failure, quarantine counted in {@code capacityInUse()}, and no reconnect alias;
 *   <li><b>pool review F1-F5</b> a fatal {@link Error} from the health probe, from {@code create()} or
 *       from {@code close()} never loses a capacity slot, and the accounting identities
 *       ({@code borrowCount} = attempts, {@code createAttempts} = created + failures,
 *       {@code closeAttempts} = successes + failures) hold for fatal failures too.
 * </ul>
 *
 * <p>Determinism: every scenario is driven by latches, not by sleeps, and the only time-based step is
 * the deliberately short borrow timeout that proves backpressure.
 */
public class BoundedPoolHardeningTest {

    private static final Duration GENEROUS = Duration.ofSeconds(5);

    /** Short enough to keep the suite fast, long enough that backpressure is unambiguous. */
    private static final Duration IMPATIENT = Duration.ofMillis(200);

    // ------------------------------------------------------------------ A1

    /**
     * A1: {@link BoundedPool#close()} racing an in-flight lazy creation.
     *
     * <p>Fails against the pre-Goal-01A code, where {@code createReserved()} promoted the creation
     * straight to {@code leased} without re-reading {@code closed}: the borrower would receive a usable
     * lease from a closed pool and the resource would never be retired.
     */
    public void aCloseDuringAnInFlightCreationHandsOutNoLeaseAndRetiresTheResource() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("create-race", 1, GENEROUS, factory);

        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch createGate = new CountDownLatch(1);
        factory.gateCreates(createEntered, createGate);

        AtomicReference<Lease<FakeResource>> borrowed = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread borrower = new Thread(() -> {
            try {
                borrowed.set(pool.borrow());
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        }, "pool-lazy-borrower");
        borrower.setDaemon(true);
        borrower.start();

        try {
            Assert.assertTrue(createEntered.await(10, TimeUnit.SECONDS),
                    "the borrower is parked inside factory.create()");
            Assert.assertEquals(1, factory.liveCount(), "the resource physically exists before the close begins");
            Assert.assertEquals(0L, pool.metrics().created(), "the in-flight creation is not published yet");
            Assert.assertEquals(1, pool.metrics().creating(), "the slot is reserved as creating");

            pool.close();
            Assert.assertTrue(pool.isClosed(), "the pool is closed while the creation is still in flight");
        } finally {
            createGate.countDown();
            borrower.join(20_000L);
        }

        Assert.assertFalse(borrower.isAlive(), "the borrower finished once the creation was released");
        Assert.assertNull(borrowed.get(), "a pool that is already closed must not hand out a lease");
        Assert.assertTrue(thrown.get() instanceof IllegalStateException,
                "the borrower must fail loudly instead of receiving an untracked resource, got " + thrown.get());
        Assert.assertTrue(thrown.get().getMessage().contains("closed while a resource was being created"),
                "the failure explains the race: " + thrown.get().getMessage());

        FakeResource orphan = factory.created().get(0);
        Assert.assertTrue(orphan.isClosed(), "the just-created resource was retired instead of leaking");
        Assert.assertEquals(1, orphan.closeCalls(), "the orphan was closed exactly once");
        Assert.assertEquals(1, factory.closed().size(), "the factory saw exactly one real release");
        Assert.assertEquals(0, factory.liveCount(), "no resource is left alive after the race");

        PoolMetrics metrics = pool.metrics();
        Assert.assertEquals(0, metrics.leased(), "no lease is outstanding");
        Assert.assertEquals(0, metrics.creating(), "the creation reservation was returned");
        Assert.assertEquals(0, metrics.retiring(), "the retirement finished");
        Assert.assertEquals(0, metrics.quarantined(), "a successful close quarantines nothing");
        Assert.assertEquals(0, metrics.capacityInUse(), "the accounting ends coherent");
        Assert.assertEquals(1L, metrics.createAttempts(), "exactly one creation was attempted");
        Assert.assertEquals(1L, metrics.created(), "exactly one resource was created");
        Assert.assertEquals(0L, metrics.initialCreations(), "a lazy creation is not an initial population");
        Assert.assertEquals(1L, metrics.replacementCreations(), "a lazy creation is a replacement creation");
        Assert.assertEquals(1L, metrics.closeAttempts(), "the orphan was offered to close()");
        Assert.assertEquals(1L, metrics.closeSuccesses(), "the orphan's close succeeded");
        Assert.assertEquals(0L, metrics.closeFailures(), "no close failed");
        Assert.assertTrue(pool.awaitQuiescence(Duration.ofSeconds(2)), "the pool ends quiescent");
    }

    /**
     * A1/02A-A: a creation that throws an {@link Error} is QUARANTINED before the Error propagates.
     *
     * <p>Rewritten for Goal 02A section A. This test used to pin the opposite: the reserved slot came back,
     * because an Error was read as "this attempt produced nothing". An Error from {@code create()} is no more
     * evidence of a clean failure than a plain exception, so the conservative rule applies and the slot is
     * quarantined - which is why the old recovery half of this test had to go: a size-1 pool whose only slot
     * is quarantined is permanently degraded by design, and asserting that it recovers would assert the
     * removed rule back into place.
     *
     * <p>What is still worth pinning: the Error itself reaches the borrower unchanged (an Error must never be
     * swallowed or re-wrapped), the reservation is resolved rather than abandoned in flight, the accounting
     * identities hold for a fatal failure, and no replacement is created on top of the unknown outcome.
     */
    public void anErrorDuringACreationQuarantinesTheSlotBeforeTheErrorPropagates() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        AtomicInteger attempts = new AtomicInteger();
        ResourceFactory<FakeResource> fatalOnce = new ResourceFactory<FakeResource>() {
            @Override
            public FakeResource create() throws Exception {
                if (attempts.incrementAndGet() == 1) {
                    throw new CreationError("simulated fatal creation failure");
                }
                return factory.create();
            }

            @Override
            public boolean isHealthy(FakeResource resource) {
                return factory.isHealthy(resource);
            }

            @Override
            public String describe() {
                return "fatal-once";
            }
        };
        BoundedPool<FakeResource> pool = new BoundedPool<>("create-error", 1, IMPATIENT, fatalOnce);

        CreationError thrown = Assert.assertThrows(CreationError.class, pool::borrow,
                "an Error from the factory still reaches the borrower");
        Assert.assertEquals("simulated fatal creation failure", thrown.getMessage(), "the same Error is rethrown");
        Assert.assertEquals(0, pool.metrics().creating(), "the reserved slot was resolved, not left in flight");
        Assert.assertEquals(1, pool.metrics().quarantined(),
                "A: an Error is not cleanup evidence, so the reserved slot is quarantined");
        Assert.assertEquals(1, pool.metrics().capacityInUse(), "A: and it still consumes capacity");
        Assert.assertEquals(1L, pool.metrics().createQuarantineFailures(), "A: the event is counted");
        Assert.assertTrue(pool.metrics().degraded(), "A: the pool reports the capacity it lost");
        Assert.assertEquals(1L, pool.metrics().createAttempts(), "the failing attempt is counted");
        Assert.assertEquals(1L, pool.metrics().createFailures(),
                "the accounting identities: an Error from create() is a create failure");
        Assert.assertEquals(pool.metrics().createAttempts(),
                pool.metrics().created() + pool.metrics().createFailures(),
                "createAttempts == created + createFailures, even for a fatal creation failure");
        Assert.assertEquals(0L, pool.metrics().borrowTimeoutCount(), "a fatal creation failure is not a timeout");

        Assert.assertThrows(TimeoutException.class, pool::borrow,
                "A: a quarantined slot does not authorise a replacement for a fatal failure either");
        Assert.assertEquals(0, factory.createAttempts(),
                "A: the refused borrow never reached the factory at all - the wrapper throws before it"
                        + " delegates - so the factory saw no attempt, which is exactly what 'created"
                        + " nothing beside the unknown outcome' means");
        Assert.assertTrue(factory.created().isEmpty(),
                "A: and the delegate factory really created no resource");
        pool.close();
    }

    // ------------------------------------------------------------------ A2

    /**
     * A2: a close whose outcome is UNCERTAIN must not free physical capacity.
     *
     * <p>The fake's close throws BEFORE it marks itself closed and before the factory's live count
     * drops, so the physical session may still exist. Fails against the pre-Goal-01A code, where
     * {@code closeEntry()} swallowed the exception and {@code finishRetirement()} always returned the
     * slot: {@code quarantined()} would be 0, a replacement would be created and the physical live
     * count would reach 2 for a pool of size 1.
     */
    public void anUncertainCloseQuarantinesTheSlotAndNeverAuthorisesAReplacement() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("quarantine", 1, IMPATIENT, factory);

        Lease<FakeResource> lease = pool.borrow();
        FakeResource poisoned = lease.value();
        poisoned.setHealthy(false);
        poisoned.failCloseUncertain(new IOException("simulated CM session close failure"));

        // An ordinary close failure is best effort for the caller: the exception does not escape.
        lease.close();

        PoolMetrics metrics = pool.metrics();
        Assert.assertEquals(1, metrics.quarantined(), "the uncertain close quarantined its slot");
        Assert.assertTrue(metrics.degraded(), "the pool reports the degraded state");
        Assert.assertEquals(0, metrics.retiring(), "the retirement itself ended");
        Assert.assertEquals(1, metrics.capacityInUse(), "the quarantined slot still consumes capacity");
        Assert.assertEquals(pool.configuredSize(), metrics.capacityInUse(),
                "the pool runs at its bound with one slot quarantined");
        Assert.assertEquals(1L, metrics.closeAttempts(), "the close was attempted once");
        Assert.assertEquals(0L, metrics.closeSuccesses(), "the close did not succeed");
        Assert.assertEquals(1L, metrics.closeFailures(), "the failed close is counted");
        Assert.assertEquals(0, metrics.available(), "nothing was returned to the idle set");
        Assert.assertFalse(poisoned.isClosed(), "the resource is not claimed to be closed");
        Assert.assertEquals(1, poisoned.closeCalls(), "the resource was offered to close() exactly once");
        Assert.assertEquals(1, factory.liveCount(), "the resource with the uncertain close is still alive");

        Assert.assertThrows(TimeoutException.class, pool::borrow,
                "a fully quarantined pool applies backpressure instead of creating a replacement");
        Assert.assertEquals(1L, pool.metrics().borrowTimeoutCount(), "the refusal is reported as backpressure");
        Assert.assertEquals(1, factory.createAttempts(), "no replacement was created into the quarantined slot");
        Assert.assertEquals(1, factory.liveCount(), "the physical live count never exceeded the bound");
        Assert.assertEquals(1, factory.peakLive(), "the physical bound held");

        pool.close();
        Assert.assertTrue(pool.isClosed(), "the pool is closed");
        Assert.assertEquals(1, pool.metrics().quarantined(), "quarantine survives the shutdown");
        Assert.assertEquals(1, factory.liveCount(),
                "shutdown does not pretend that the uncertain resource is gone");
    }

    /**
     * A2: a partial quarantine must not lift the physical bound.
     *
     * <p>Fails against the pre-Goal-01A code, where the failed close freed the slot: the pool would
     * happily create a third resource for a pool of size 2, so {@code factory.peakLive()} would exceed
     * the bound and the {@code quarantined()} assertion would be 0.
     */
    public void aQuarantinedSlotNeverLetsThePhysicalLiveCountExceedTheBound() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("bound-quarantine", 2, IMPATIENT, factory);

        Lease<FakeResource> first = pool.borrow();
        FakeResource poisoned = first.value();
        poisoned.setHealthy(false);
        poisoned.failCloseUncertain(new IOException("simulated close failure"));
        first.close();

        Assert.assertEquals(1, pool.metrics().quarantined(), "one slot is quarantined");
        Assert.assertEquals(1, factory.liveCount(), "the resource with the uncertain close is still live");

        Lease<FakeResource> second = pool.borrow();
        Assert.assertTrue(second.value().id() != poisoned.id(), "the second slot got a fresh resource");
        Assert.assertEquals(2, factory.liveCount(), "the remaining slot was refilled on demand");
        Assert.assertEquals(2, factory.peakLive(), "the physical bound held exactly");
        Assert.assertEquals(2L, factory.createAttempts(), "two creations in total");
        Assert.assertEquals(2, pool.metrics().capacityInUse(), "the quarantine and the lease fill the pool");

        Assert.assertThrows(TimeoutException.class, pool::borrow, "a full pool applies backpressure");
        Assert.assertEquals(2L, factory.createAttempts(), "backpressure creates nothing");
        Assert.assertEquals(2, factory.liveCount(), "no third resource was ever created");
        Assert.assertEquals(2, factory.peakLive(), "the physical bound was never exceeded");
        Assert.assertEquals(2, pool.metrics().capacityInUse(), "capacity stays at the bound");

        pool.close();
        second.close();
        PoolMetrics metrics = pool.metrics();
        Assert.assertEquals(2L, metrics.closeAttempts(), "both resources were offered to close()");
        Assert.assertEquals(1L, metrics.closeSuccesses(), "only the healthy close succeeded");
        Assert.assertEquals(1L, metrics.closeFailures(), "the uncertain close is reported as a failure");
        Assert.assertEquals(1, metrics.quarantined(), "the quarantined slot stays consumed");
        Assert.assertEquals(0, metrics.leased(), "no lease is outstanding");
        Assert.assertEquals(0, metrics.retiring(), "no retirement is left in flight");
        Assert.assertEquals(1, metrics.capacityInUse(), "one quarantined slot remains accounted");
        Assert.assertEquals(1, factory.liveCount(), "only the possibly-open resource remains");
        Assert.assertTrue(pool.awaitQuiescence(GENEROUS), "one lease and no retirement means quiescent");
    }

    /**
     * A2: shutdown must still offer every remaining resource to {@code close()} when one close fails,
     * and must not leak the healthy ones.
     *
     * <p>Fails against a mutant whose shutdown loop stops at the first failed close: the remaining
     * resources would stay open ({@code liveCount()} 3 instead of 1) and {@code closeSuccesses()} would
     * not reach 2.
     */
    public void shutdownOffersEveryRemainingResourceToCloseWhenOneCloseFails() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("shutdown-quarantine", 3, GENEROUS, factory);
        pool.initialize();
        List<FakeResource> resources = factory.created();
        Assert.assertEquals(3, resources.size(), "initialize created one resource per slot");
        FakeResource poisoned = resources.get(0);
        poisoned.failCloseUncertain(new IOException("simulated close failure"));

        pool.close();

        PoolMetrics metrics = pool.metrics();
        Assert.assertEquals(3L, metrics.closeAttempts(), "shutdown offered every resource to close()");
        Assert.assertEquals(2L, metrics.closeSuccesses(), "the two healthy resources were really closed");
        Assert.assertEquals(1L, metrics.closeFailures(), "the failed close is reported as a failure");
        Assert.assertEquals(1, metrics.quarantined(), "the failed close quarantines its slot");
        Assert.assertTrue(metrics.degraded(), "the pool reports the degraded state");
        Assert.assertEquals(0, metrics.retiring(), "no retirement is left in flight");
        Assert.assertEquals(1, metrics.capacityInUse(), "only the quarantined slot stays consumed");
        Assert.assertEquals(1, factory.liveCount(), "nothing leaked: only the possibly-open resource remains");
        Assert.assertEquals(1, poisoned.closeCalls(), "the poisoned resource was offered to close() once");
        Assert.assertFalse(poisoned.isClosed(), "the pool never claims the uncertain resource is closed");
        for (int index = 1; index < resources.size(); index++) {
            Assert.assertTrue(resources.get(index).isClosed(), "resource " + index + " was really closed");
            Assert.assertEquals(1, resources.get(index).closeCalls(), "resource " + index + " was closed once");
        }
        Assert.assertEquals(2, factory.closed().size(), "the factory saw exactly two real releases");
    }

    /**
     * A2: an {@link Error} from one resource close must not abandon the remaining resources, and must
     * still reach the caller of {@code close()}.
     *
     * <p>Fails against a mutant whose {@code closeAllAndFinish()} rethrows the first Error immediately:
     * the second resource would never be closed and its slot would stay reserved forever.
     */
    public void anErrorFromOneShutdownCloseStillClosesTheOthersAndIsRethrown() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("shutdown-error", 2, GENEROUS, factory);
        pool.initialize();
        List<FakeResource> resources = factory.created();
        FakeResource fatal = resources.get(0);
        fatal.failCloseWith(new ResourceCloseError("simulated fatal close"));

        ResourceCloseError thrown = Assert.assertThrows(ResourceCloseError.class, pool::close,
                "the first Error from a resource close reaches the caller of pool.close()");
        Assert.assertEquals("simulated fatal close", thrown.getMessage(), "the same Error is rethrown");

        PoolMetrics metrics = pool.metrics();
        Assert.assertTrue(pool.isClosed(), "the pool is closed even though close() threw an Error");
        Assert.assertEquals(2L, metrics.closeAttempts(), "the loop reached the second resource anyway");
        Assert.assertEquals(1L, metrics.closeSuccesses(), "the healthy close is a success");
        Assert.assertEquals(1L, metrics.closeFailures(), "an Error from close() is counted as a failure too");
        Assert.assertEquals(metrics.closeAttempts(), metrics.closeSuccesses() + metrics.closeFailures(),
                "closeAttempts == closeSuccesses + closeFailures");
        Assert.assertEquals(1, metrics.quarantined(),
                "an Error is an uncertain outcome, so its slot is quarantined");
        Assert.assertTrue(metrics.degraded(), "the degraded state is visible");
        Assert.assertEquals(1, metrics.capacityInUse(), "the uncertain slot stays consumed");
        Assert.assertEquals(1, fatal.closeCalls(), "the fatal resource was attempted once");
        Assert.assertTrue(resources.get(1).isClosed(), "one fatal close must not abandon the remaining resource");
        Assert.assertEquals(1, resources.get(1).closeCalls(), "the remaining resource was closed exactly once");
        Assert.assertEquals(0, factory.liveCount(), "the fakes released themselves, so nothing is left alive");
    }

    // ------------------------------------------------------------------ A3

    /**
     * A3: rotation by usage must be automatic - no explicit {@code recordOperation()} call.
     *
     * <p>Fails against the pre-Goal-01A code, where {@code borrow()} did not touch the resource's
     * operation counter: two full try-with-resources cycles would leave it at 0, nothing would rotate
     * and the same resource would be lent forever.
     */
    public void automaticUsageRotationNeedsNoExplicitRecordOperation() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("auto-rotation", 1, GENEROUS, factory, null, 2);

        Lease<FakeResource> first = pool.borrow();
        FakeResource resource = first.value();
        first.close();
        Assert.assertFalse(resource.isClosed(), "one automatic usage is below the budget of two");
        Assert.assertEquals(0L, pool.metrics().operationRotations(), "nothing rotated yet");
        Assert.assertEquals(1L, pool.metrics().automaticUsages(), "the borrow/use/close cycle was counted");
        Assert.assertEquals(0L, pool.metrics().explicitOperations(), "no explicit operation was recorded");

        Lease<FakeResource> second = pool.borrow();
        Assert.assertEquals(resource.id(), second.value().id(), "a resource below its budget is lent again");
        second.close();
        Assert.assertTrue(resource.isClosed(), "the second automatic usage reached the budget and rotated it");
        Assert.assertEquals(1L, pool.metrics().operationRotations(), "the rotation is counted once");
        Assert.assertEquals(2L, pool.metrics().automaticUsages(), "both cycles are counted");
        Assert.assertEquals(0L, pool.metrics().explicitOperations(), "still no explicit operation");
        Assert.assertEquals(2L, pool.metrics().operations(), "the pool reports the two automatic usages");
        Assert.assertEquals(0, pool.metrics().available(), "the rotated resource left the idle set");
        Assert.assertEquals(0, pool.metrics().capacityInUse(), "the successful close freed the slot");
        Assert.assertEquals(1L, pool.metrics().closeSuccesses(), "the rotation closed the resource once");

        Lease<FakeResource> third = pool.borrow();
        Assert.assertTrue(third.value().id() != resource.id(), "the freed slot was refilled with a fresh resource");
        third.close();
        Assert.assertEquals(2L, pool.metrics().createAttempts(), "the refill is the second creation");
        Assert.assertEquals(2L, pool.metrics().replacementCreations(),
                "both the first borrow and the refill are replacement creations");
        Assert.assertEquals(3L, pool.metrics().automaticUsages(), "the third cycle is counted too");
        pool.close();
    }

    /**
     * A3: explicit operation counts add on top of the automatic usage instead of replacing it.
     *
     * <p>Fails against the pre-Goal-01A code (automatic usage absent, so the budget of three is never
     * reached) and against a mutant that folds explicit counts into {@code automaticUsages()} (the two
     * sources would no longer be separately visible).
     */
    public void explicitOperationsAddOnTopOfAutomaticUsage() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("explicit-rotation", 1, GENEROUS, factory, null, 3);

        Lease<FakeResource> first = pool.borrow();
        FakeResource resource = first.value();
        first.recordOperation();
        first.close();
        Assert.assertFalse(resource.isClosed(), "one automatic usage plus one explicit is below the budget of three");
        Assert.assertEquals(1L, pool.metrics().automaticUsages(), "the cycle counts as one automatic usage");
        Assert.assertEquals(1L, pool.metrics().explicitOperations(), "the explicit call is counted separately");
        Assert.assertEquals(2L, pool.metrics().operations(), "the budget saw both sources of usage");

        Lease<FakeResource> second = pool.borrow();
        Assert.assertEquals(resource.id(), second.value().id(), "the resource is still below its budget");
        second.close();
        Assert.assertTrue(resource.isClosed(), "the third usage reached the budget and rotated the resource");
        Assert.assertEquals(2L, pool.metrics().automaticUsages(), "both cycles are counted automatically");
        Assert.assertEquals(1L, pool.metrics().explicitOperations(), "explicit counting is not folded into usages");
        Assert.assertEquals(3L, pool.metrics().operations(), "the reported total is the sum of both sources");
        Assert.assertEquals(1L, pool.metrics().operationRotations(), "the rotation is counted once");
        pool.close();
    }

    // ------------------------------------------------------------------ A4

    /**
     * A4: initial population and replacement creation are counted separately and truthfully.
     *
     * <p>Fails against a mutant that ignores the {@code initial} flag (a lazy creation would be
     * reported as an initial creation) or that raises both counters for one creation.
     */
    public void initialAndReplacementCreationsAreDistinctAndTruthful() throws Exception {
        FakePoolFactory eagerFactory = new FakePoolFactory();
        BoundedPool<FakeResource> eager = new BoundedPool<>("initial", 2, GENEROUS, eagerFactory,
                Duration.ofMillis(50), 0);
        eager.initialize();
        Assert.assertEquals(2L, eager.metrics().initialCreations(), "initialize() counts initial creations");
        Assert.assertEquals(0L, eager.metrics().replacementCreations(), "initialize() creates no replacements");
        Assert.assertEquals(2L, eager.metrics().created(), "both resources were created");
        Assert.assertEquals(2L, eager.metrics().createAttempts(), "both attempts are counted");

        Thread.sleep(120L);
        Assert.assertEquals(2, eager.rotateStale(), "the aged idle resources are retired");
        Lease<FakeResource> refill = eager.borrow();
        Assert.assertEquals(1L, eager.metrics().replacementCreations(), "a lazy refill is a replacement creation");
        Assert.assertEquals(2L, eager.metrics().initialCreations(), "a lazy refill is not an initial creation");
        Assert.assertEquals(3L, eager.metrics().createAttempts(), "the refill is the third attempt");
        refill.close();
        eager.close();

        FakePoolFactory lazyFactory = new FakePoolFactory();
        BoundedPool<FakeResource> onDemand = new BoundedPool<>("lazy", 1, GENEROUS, lazyFactory);
        Lease<FakeResource> lease = onDemand.borrow();
        Assert.assertEquals(0L, onDemand.metrics().initialCreations(), "a lazy creation is never initial");
        Assert.assertEquals(1L, onDemand.metrics().replacementCreations(), "a lazy creation is a replacement");
        lease.close();
        onDemand.close();
    }

    /**
     * A4: close attempts, successes and failures stay distinct, and {@code capacityInUse()} counts the
     * quarantined slot.
     *
     * <p>Fails against the pre-Goal-01A code (one ambiguous {@code closed} counter, no quarantine in
     * {@code capacityInUse()}) and against a mutant that leaves quarantine out of the capacity sum.
     */
    public void closeOutcomesStayDistinctAndCapacityInUseCountsQuarantine() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("close-metrics", 1, IMPATIENT, factory, null, 1);

        Lease<FakeResource> healthy = pool.borrow();
        FakeResource firstResource = healthy.value();
        healthy.close();
        PoolMetrics afterSuccess = pool.metrics();
        Assert.assertTrue(firstResource.isClosed(), "the healthy resource was closed by the rotation");
        Assert.assertEquals(1L, afterSuccess.closeAttempts(), "one close was attempted");
        Assert.assertEquals(1L, afterSuccess.closeSuccesses(), "the attempt succeeded");
        Assert.assertEquals(0L, afterSuccess.closeFailures(), "no close failed");
        Assert.assertEquals(0, afterSuccess.quarantined(), "a successful close quarantines nothing");
        Assert.assertFalse(afterSuccess.degraded(), "the pool is not degraded");
        Assert.assertEquals(0, afterSuccess.capacityInUse(), "a successful close frees the slot");

        Lease<FakeResource> broken = pool.borrow();
        FakeResource secondResource = broken.value();
        Assert.assertTrue(secondResource.id() != firstResource.id(), "the freed slot was refilled");
        secondResource.setHealthy(false);
        secondResource.failCloseUncertain(new IOException("simulated close failure"));
        broken.close();

        PoolMetrics afterFailure = pool.metrics();
        Assert.assertEquals(2L, afterFailure.closeAttempts(), "two closes were attempted");
        Assert.assertEquals(1L, afterFailure.closeSuccesses(), "one close succeeded");
        Assert.assertEquals(1L, afterFailure.closeFailures(), "one close failed");
        Assert.assertEquals(1, afterFailure.quarantined(), "the failed close moved its slot to quarantine");
        Assert.assertTrue(afterFailure.degraded(), "the pool reports itself degraded");
        Assert.assertEquals(afterFailure.available() + afterFailure.leased() + afterFailure.creating()
                        + afterFailure.retiring() + afterFailure.quarantined(),
                afterFailure.capacityInUse(), "capacityInUse() is the sum of every slot state, quarantine included");
        Assert.assertEquals(pool.configuredSize(), afterFailure.capacityInUse(),
                "the pool stays at its bound with one slot quarantined");
        Assert.assertThrows(TimeoutException.class, pool::borrow,
                "a pool whose only slot is quarantined applies backpressure");
        Assert.assertEquals(2L, pool.metrics().createAttempts(), "no replacement was created into the quarantined slot");
        pool.close();
    }

    /**
     * A4: {@code PoolMetrics} publishes EXACTLY the documented accessor set - no reconnect alias can
     * come back under any name, and no counter can be made ambiguous again.
     *
     * <p>The whole set is asserted, not a list of forbidden names: forbidding only the three names that
     * Goal 01A happened to remove would pin those names alone, and a future alias such as
     * {@code pooledReconnects()} would slip through. An exact-set assertion makes ANY addition a
     * deliberate, reviewed change of the metrics contract, which is the intent of A4 - only the future
     * CM/JDBC adapter can say what a reconnect is, so the pool publishes no such counter at all.
     *
     * <p>Deliberately extended by Goal 02 with {@code createQuarantineFailures}: a failed creation whose
     * cleanup could not be proven now quarantines its reserved slot, and that event has to be visible
     * next to {@code createFailures} or an operator could not tell a harmless failed attempt from one
     * that permanently cost the pool a capacity slot. The set is still exact, so any further name - a
     * reconnect alias, another ambiguous 'closed' counter - still fails this test.
     *
     * <p>Fails against the pre-Goal-01A {@code PoolMetrics}, which published
     * {@code reconnectAttempts()}/{@code reconnectSuccesses()}/{@code reconnectFailures()} plus one
     * ambiguous {@code closed()} counter.
     */
    public void theMetricsContractPublishesExactlyTheDocumentedAccessorSet() {
        Set<String> actual = new TreeSet<>();
        for (Method method : PoolMetrics.class.getDeclaredMethods()) {
            if (method.getDeclaringClass() == PoolMetrics.class) {
                actual.add(method.getName());
            }
        }
        actual.removeAll(Set.of("equals", "hashCode", "toString"));

        Set<String> expected = new TreeSet<>(List.of(
                "name", "configuredSize", "available", "leased", "creating", "retiring", "quarantined",
                "borrowCount", "borrowTimeoutCount", "averageBorrowWaitMs", "maxBorrowWaitMs",
                "createAttempts", "initialCreations", "replacementCreations", "created", "createFailures",
                "createQuarantineFailures",
                "closeAttempts", "closeSuccesses", "closeFailures",
                "validationFailures", "ageRotations", "operationRotations", "unhealthyRotations",
                "automaticUsages", "explicitOperations",
                "capacityInUse", "degraded", "operations"));

        Assert.assertEquals(expected, actual,
                "A4: PoolMetrics must publish exactly the documented accessors - any extra name (a reconnect"
                        + " alias, another ambiguous 'closed' counter) is a change to the metrics contract");
        Assert.assertFalse(actual.contains("closed"),
                "A4: closeAttempts/closeSuccesses/closeFailures stay distinct, with no ambiguous 'closed'");
    }

    // ------------------------------------------ pool review F1-F5 (fatal probe/create/close failures)

    /**
     * Pool review F1: an {@link Error} from the health probe while an IDLE resource is being taken must
     * not lose that resource's capacity slot.
     *
     * <p>Fails against the pre-fix code, where the probe Error escaped {@code takeIdle()} after the entry
     * had already been removed from the idle set: the slot became unaccounted while the resource stayed
     * physically alive, so the next borrow created a replacement and the physical live count exceeded the
     * configured size (measured 3..6 with size 1).
     */
    public void anErrorFromTheHealthProbeRetiresTheIdleResourceInsteadOfLosingItsSlot() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("probe-error-idle", 1, IMPATIENT, factory);

        Lease<FakeResource> first = pool.borrow();
        FakeResource resource = first.value();
        first.close();
        Assert.assertEquals(1, pool.metrics().available(), "the healthy resource went back to the idle set");
        Assert.assertEquals(0L, pool.metrics().unhealthyRotations(), "no rotation happened yet");
        factory.failHealthChecksWith(new HealthProbeError("simulated fatal health probe"));

        HealthProbeError thrown = Assert.assertThrows(HealthProbeError.class, pool::borrow,
                "an Error from the probe still reaches the borrower");
        Assert.assertEquals("simulated fatal health probe", thrown.getMessage(), "the same Error is rethrown");

        PoolMetrics metrics = pool.metrics();
        Assert.assertTrue(resource.isClosed(), "the suspect idle resource was retired and closed");
        Assert.assertEquals(0, factory.liveCount(), "no physically alive resource is left unaccounted");
        Assert.assertEquals(1L, metrics.unhealthyRotations(), "the suspect resource counts as an unhealthy rotation");
        Assert.assertEquals(1L, metrics.validationFailures(), "the probe failure is counted as a validation failure");
        Assert.assertEquals(2L, metrics.borrowCount(),
                "both the earlier successful borrow and this refused attempt are counted");
        Assert.assertEquals(0, metrics.leased(), "nothing was leased");
        Assert.assertEquals(0, metrics.available(), "nothing was left in the idle set");
        Assert.assertEquals(0, metrics.retiring(), "the retirement finished");
        Assert.assertEquals(0, metrics.quarantined(), "the close succeeded, so nothing is quarantined");
        Assert.assertEquals(0, metrics.capacityInUse(), "the slot was returned instead of being lost");
        Assert.assertEquals(1L, metrics.closeAttempts(), "the retired resource was offered to close() once");
        Assert.assertEquals(1L, metrics.closeSuccesses(), "and it closed cleanly");

        factory.failHealthChecksWith(null);
        Lease<FakeResource> replacement = pool.borrow();
        Assert.assertTrue(replacement.value().id() != resource.id(), "the freed slot was refilled");
        Assert.assertEquals(1, factory.liveCount(), "exactly one resource is alive again");
        Assert.assertEquals(1, factory.peakLive(), "the physical bound was never exceeded");
        Assert.assertEquals(2L, pool.metrics().createAttempts(), "the refill is the second creation");
        replacement.close();
        pool.close();
    }

    /**
     * Pool review F2: an {@link Error} from the health probe while a LEASE is being returned must still
     * hand the lease back; otherwise the slot sticks as leased forever with {@code quarantined()==0} and
     * {@code degraded()==false}, which is silent permanent degradation no metric explains.
     *
     * <p>Fails against the pre-fix code, where the probe Error escaped {@code release()} before the
     * leased count was decremented: {@code leased()} stayed 1 and the resource stayed open.
     */
    public void anErrorFromTheHealthProbeOnALeaseReturnStillHandsTheLeaseBack() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("probe-error-return", 1, IMPATIENT, factory);

        Lease<FakeResource> lease = pool.borrow();
        FakeResource resource = lease.value();
        factory.failHealthChecksWith(new HealthProbeError("simulated fatal health probe"));

        HealthProbeError thrown = Assert.assertThrows(HealthProbeError.class, lease::close,
                "the probe Error reaches the caller that returned the lease");
        Assert.assertEquals("simulated fatal health probe", thrown.getMessage(), "the same Error is rethrown");

        PoolMetrics metrics = pool.metrics();
        Assert.assertEquals(0, metrics.leased(), "the lease was handed back despite the probe Error");
        Assert.assertEquals(0, metrics.retiring(), "the retirement finished");
        Assert.assertEquals(0, metrics.quarantined(), "the close succeeded, so nothing is quarantined");
        Assert.assertEquals(0, metrics.capacityInUse(), "the slot was returned, not stuck as leased");
        Assert.assertEquals(1L, metrics.unhealthyRotations(), "the suspect resource was retired as unhealthy");
        Assert.assertEquals(1L, metrics.validationFailures(), "the probe failure is counted");
        Assert.assertTrue(resource.isClosed(), "the suspect resource was closed");
        Assert.assertEquals(0, factory.liveCount(), "nothing stayed alive after the return");

        factory.failHealthChecksWith(null);
        Lease<FakeResource> replacement = pool.borrow();
        Assert.assertTrue(replacement.value().id() != resource.id(), "a fresh resource fills the freed slot");
        Assert.assertEquals(1, factory.peakLive(), "the physical bound held through the recovery");
        replacement.close();
        pool.close();
    }

    /**
     * Pool review F5: {@code borrowCount} counts ATTEMPTS, so a refused or exhausted borrow is still
     * visible - counting only outcomes showed one borrow where two were attempted.
     */
    public void borrowAttemptsAreCountedEvenWhenTheyAreRefusedOrExhausted() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("attempts", 1, Duration.ofMillis(150), factory);

        Lease<FakeResource> lease = pool.borrow();
        Assert.assertEquals(1L, pool.metrics().borrowCount(), "a successful borrow counts as one attempt");

        Assert.assertThrows(TimeoutException.class, pool::borrow, "the exhausted pool refuses the attempt");
        Assert.assertEquals(2L, pool.metrics().borrowCount(), "the exhausted attempt is counted");
        Assert.assertEquals(1L, pool.metrics().borrowTimeoutCount(), "and it is reported as a timeout");

        lease.close();
        pool.close();
        Assert.assertThrows(IllegalStateException.class, pool::borrow, "a closed pool refuses the attempt");
        Assert.assertEquals(3L, pool.metrics().borrowCount(),
                "an attempt refused because the pool is closed is still visible");
        Assert.assertEquals(1L, pool.metrics().borrowTimeoutCount(), "a closed pool is not a timeout");
    }

    /**
     * Pool review F7: a probe failure must STOP the idle scan. Continuing it allowed a later HEALTHY
     * entry to be promoted to {@code leased} and then abandoned when the remembered failure was
     * rethrown - leaked physically alive, unreachable even by {@code close()}, with the slot consumed
     * forever and {@code degraded()} false.
     *
     * <p>The two-idle-resource shape is the whole point: with a single idle resource the bug cannot
     * appear. Fails against the pre-fix code, where the scan continued: {@code leased()==1},
     * {@code available()==0}, and {@code close()} never reached the abandoned resource.
     */
    public void aProbeFailureStopsTheScanSoAHealthyEntryIsNotAbandonedAsLeased() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("probe-scan", 2, IMPATIENT, factory);
        pool.initialize();
        List<FakeResource> resources = factory.created();
        Assert.assertEquals(2, resources.size(), "two resources are idle");
        // The idle set is FIFO, so the FIRST-created resource is the one polled and probed first.
        FakeResource hostile = resources.get(0);
        FakeResource healthy = resources.get(1);
        hostile.failProbeWith(new HealthProbeError("simulated fatal health probe"));

        HealthProbeError thrown = Assert.assertThrows(HealthProbeError.class, pool::borrow,
                "the probe failure still reaches the borrower");
        Assert.assertEquals("simulated fatal health probe", thrown.getMessage(), "the same Error is rethrown");

        PoolMetrics metrics = pool.metrics();
        Assert.assertTrue(hostile.isClosed(), "the suspect resource was retired and closed");
        Assert.assertFalse(healthy.isClosed(), "the healthy neighbour was not retired");
        Assert.assertEquals(0, metrics.leased(),
                "F7: no entry may stay promoted to leased after the borrow failed");
        Assert.assertEquals(1, metrics.available(), "the healthy resource is still idle and usable");
        Assert.assertEquals(1, factory.liveCount(), "exactly one resource is physically alive");
        Assert.assertEquals(1, metrics.capacityInUse(), "the survivor accounts for its slot");
        Assert.assertEquals(1L, metrics.validationFailures(), "the probe failure is counted");
        Assert.assertFalse(metrics.degraded(), "a successful retirement leaves nothing quarantined");

        // The survivor is reachable through the normal paths, and close() releases it.
        Lease<FakeResource> survivor = pool.borrow();
        Assert.assertEquals(healthy.id(), survivor.value().id(), "the survivor is lent out normally");
        survivor.close();
        pool.close();
        Assert.assertEquals(0, factory.liveCount(), "close() reached every remaining resource");
        Assert.assertEquals(0, pool.metrics().capacityInUse(), "no capacity is left accounted");
    }

    /**
     * Pool review F8: {@code rotateStale()} must guard its probe too - an Error used to escape the sweep
     * with nothing counted and the hostile resource left in the idle set.
     *
     * <p>Fails against the pre-fix code: {@code validationFailures()} stayed 0, no rotation was
     * recorded, and the suspect resource was neither retired nor closed.
     */
    public void aProbeFailureInTheRotationSweepIsReportedAndRetiresTheSuspectResource() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("rotate-probe", 2, GENEROUS, factory);
        pool.initialize();
        List<FakeResource> resources = factory.created();
        FakeResource hostile = resources.get(0);
        hostile.failProbeWith(new HealthProbeError("simulated fatal health probe"));

        HealthProbeError thrown = Assert.assertThrows(HealthProbeError.class, pool::rotateStale,
                "the probe failure surfaces instead of escaping the sweep uncounted");
        Assert.assertEquals("simulated fatal health probe", thrown.getMessage(), "the same Error is rethrown");

        PoolMetrics metrics = pool.metrics();
        Assert.assertEquals(1L, metrics.validationFailures(), "the probe failure is counted");
        Assert.assertEquals(1L, metrics.unhealthyRotations(), "the suspect resource is an unhealthy rotation");
        Assert.assertTrue(hostile.isClosed(), "the suspect resource was retired through the normal path");
        Assert.assertEquals(0, metrics.retiring(), "the retirement finished");
        Assert.assertEquals(1, metrics.available(), "the healthy resource is still idle");
        Assert.assertEquals(1, factory.liveCount(), "nothing is leaked");
        Assert.assertEquals(0, pool.metrics().quarantined(), "the close succeeded, so nothing is quarantined");

        pool.close();
        Assert.assertEquals(0, factory.liveCount(), "close() releases the survivor");
        Assert.assertEquals(0, pool.metrics().capacityInUse(), "no capacity is left accounted");
    }

    /**
     * Pool review F9: the wait metrics must describe the same population {@code borrowCount} counts.
     *
     * <p>Fails against the pre-fix code, where the wait was recorded only on the success and timeout
     * paths: a borrow that spent 200 ms failing inside {@code create()} reported
     * {@code maxBorrowWaitMs()==0.0} while {@code borrowCount()==1}.
     */
    public void aFailedCreationAttemptStillReportsItsWait() throws Exception {
        Duration creationDelay = Duration.ofMillis(200);
        ResourceFactory<FakeResource> slowFailure = new ResourceFactory<FakeResource>() {
            @Override
            public FakeResource create() throws Exception {
                Thread.sleep(creationDelay.toMillis());
                throw new IOException("simulated slow creation failure");
            }

            @Override
            public String describe() {
                return "slow-failure";
            }
        };
        BoundedPool<FakeResource> pool = new BoundedPool<>("slow-attempt", 1, GENEROUS, slowFailure);

        Assert.assertThrows(com.mraibo.cminsight.connection.PoolException.class, pool::borrow,
                "the creation failure surfaces as PoolException");

        PoolMetrics metrics = pool.metrics();
        Assert.assertEquals(1L, metrics.borrowCount(), "the failed attempt is counted");
        Assert.assertEquals(0L, metrics.borrowTimeoutCount(), "it is not a timeout");
        Assert.assertTrue(metrics.maxBorrowWaitMs() >= 150.0,
                "F9: the failed attempt's wait is reported, got " + metrics.maxBorrowWaitMs());
        Assert.assertTrue(metrics.averageBorrowWaitMs() >= 150.0,
                "F9: the average covers the attempted borrow, got " + metrics.averageBorrowWaitMs());
        Assert.assertEquals(1, metrics.quarantined(),
                "A: the plain failure quarantines the slot it reserved - the wait metrics are asserted on the"
                        + " same event, so this also pins that the quarantine did not replace the reporting");
        Assert.assertEquals(1, metrics.capacityInUse(), "A: and the quarantined slot consumes capacity");
        pool.close();
    }

    /**
     * Pool review F3/F4, adjusted for Goal 02A section A: the two accounting identities must hold even when
     * an {@link Error} - not an exception - comes out of {@code create()} or {@code close()}.
     *
     * <p>Nothing tested these before, which is exactly how the miscounts survived: a lost create or close
     * failure makes capacity loss unexplainable from the metrics.
     *
     * <p>The create and close halves use SEPARATE pools on purpose. Before section A a fatal creation
     * released its slot, so one pool could carry both halves. A fatal creation now quarantines the only slot
     * of a size-1 pool on purpose, and a pool in that state refuses every later borrow rather than opening a
     * resource next to one whose outcome is unknown - so a single pool could not reach the close half at all,
     * and asserting that it could would be asserting the removed rule back into place.
     */
    public void theAccountingIdentitiesHoldForFatalFailures() throws Exception {
        // ---- create identities: a fatal creation is counted and quarantines its reservation ----------
        FakePoolFactory createFactory = new FakePoolFactory();
        AtomicInteger attempts = new AtomicInteger();
        ResourceFactory<FakeResource> fatalFirstCreate = new ResourceFactory<FakeResource>() {
            @Override
            public FakeResource create() throws Exception {
                if (attempts.incrementAndGet() == 1) {
                    throw new CreationError("simulated fatal creation failure");
                }
                return createFactory.create();
            }

            @Override
            public boolean isHealthy(FakeResource resource) {
                return createFactory.isHealthy(resource);
            }

            @Override
            public String describe() {
                return "fatal-once";
            }
        };
        BoundedPool<FakeResource> createPool = new BoundedPool<>("identities-create", 1, GENEROUS,
                fatalFirstCreate);
        Assert.assertThrows(CreationError.class, createPool::borrow, "the first creation fails fatally");

        PoolMetrics createMetrics = createPool.metrics();
        Assert.assertTrue(createMetrics.createFailures() >= 1L, "the fatal creation is counted as a failure");
        Assert.assertEquals(1, createMetrics.quarantined(),
                "A: the fatal creation quarantines the reservation it held");
        Assert.assertEquals(1L, createMetrics.createQuarantineFailures(), "A: and the quarantine is counted");
        Assert.assertEquals(createMetrics.createAttempts(), createMetrics.created() + createMetrics.createFailures(),
                "createAttempts == created + createFailures");
        Assert.assertEquals(0, createMetrics.creating(), "A: no reservation is left in flight");
        createPool.close();

        // ---- close identities: a fatal close is counted and quarantines its slot ----------------------
        FakePoolFactory closeFactory = new FakePoolFactory();
        BoundedPool<FakeResource> closePool = new BoundedPool<>("identities-close", 1, GENEROUS, closeFactory);
        Lease<FakeResource> lease = closePool.borrow();
        FakeResource resource = lease.value();
        resource.setHealthy(false);
        resource.failCloseWith(new ResourceCloseError("simulated fatal close"));
        Assert.assertThrows(ResourceCloseError.class, lease::close, "the close fails fatally");

        PoolMetrics closeMetrics = closePool.metrics();
        Assert.assertTrue(closeMetrics.closeFailures() >= 1L, "the fatal close is counted as a failure");
        Assert.assertEquals(1, closeMetrics.quarantined(), "the fatal close quarantines the slot");
        Assert.assertEquals(closeMetrics.closeAttempts(), closeMetrics.closeSuccesses() + closeMetrics.closeFailures(),
                "closeAttempts == closeSuccesses + closeFailures");
        Assert.assertEquals(0, closeMetrics.creating(), "no create reservation is left over");
        closePool.close();
    }

    /** An {@link Error} raised by {@code factory.create()}, {@code isHealthy()} or {@code close()}. */
    private static final class HealthProbeError extends Error {

        private static final long serialVersionUID = 1L;

        HealthProbeError(String message) {
            super(message);
        }
    }

    /** An {@link Error} raised by {@code factory.create()}, deliberately not an Exception. */
    private static final class CreationError extends Error {

        private static final long serialVersionUID = 1L;

        CreationError(String message) {
            super(message);
        }
    }
}
