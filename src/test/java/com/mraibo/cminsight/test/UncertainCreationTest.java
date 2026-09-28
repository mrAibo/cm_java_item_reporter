package com.mraibo.cminsight.test;

import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CreationFailure;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.connection.PoolException;
import com.mraibo.cminsight.connection.ResourceFactory;
import com.mraibo.cminsight.core.CloseState;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Goal 02 section C: a creation failure must not be able to free capacity it did not prove it released.
 *
 * <h2>The defect these tests exist for</h2>
 *
 * <p>Until Goal 02 the pool treated every throwing {@code create()} as "this attempt produced nothing"
 * and returned the reserved slot. That is correct only while the factory really did release whatever it
 * allocated. Connecting to a server is the counter-example: a factory allocates a physical session and
 * then fails, and cleaning up that half-built session can itself fail. Releasing the slot then authorises
 * a replacement next to a resource that is demonstrably still alive, and the configured pool size is a
 * hard <em>physical</em> bound, so that is a bound breach rather than a bookkeeping slip.
 *
 * <p>The fix is an explicit outcome: {@link CreationFailure.Cleanup#PROVEN_CLEAN} releases the slot,
 * {@link CreationFailure.Cleanup#UNPROVEN} quarantines it. These tests pin both directions, the capacity
 * identity while a slot is quarantined, and - importantly - that an untyped exception keeps its
 * historical meaning, because weakening that silently would break four committed Goal 01 assertions.
 *
 * <p>Every interleaving here is driven by direct calls, not by sleeps: the properties asserted are
 * arithmetic invariants and exact counts, so there is no scheduler luck to lose.
 */
public final class UncertainCreationTest {

    private static final Duration PATIENT = Duration.ofSeconds(5);
    private static final Duration IMPATIENT = Duration.ofMillis(200);

    /**
     * An UNPROVEN creation failure must quarantine the reserved slot: capacity stays consumed, no
     * replacement is authorised, and the pool reports itself degraded.
     *
     * <p>Measured on the resource itself, not only on the metrics: the factory's live count stays raised,
     * so the physical resource this failure left behind is demonstrably still there. A pool that freed the
     * slot would let the very next borrow open a second session beside it.
     */
    public void anUnprovenCreationFailureQuarantinesTheReservedSlot() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm", 1, IMPATIENT, factory);

        factory.failNextCreatesAfterAllocation(1, false);
        Assert.assertThrows(PoolException.class, pool::borrow,
                "an UNPROVEN creation failure must still surface as a PoolException");

        Assert.assertEquals(1, factory.liveCount(),
                "C: the resource the failing attempt allocated is still physically alive");
        Assert.assertEquals(1, pool.metrics().quarantined(),
                "C: the reserved slot is quarantined, not released");
        Assert.assertEquals(1, pool.metrics().capacityInUse(),
                "C: the quarantined slot still consumes capacity");
        Assert.assertEquals(1L, pool.metrics().createQuarantineFailures(),
                "C: the quarantine is counted separately from an ordinary creation failure");
        Assert.assertEquals(1L, pool.metrics().createFailures(),
                "C: the attempt is also an ordinary creation failure");
        Assert.assertTrue(pool.metrics().degraded(),
                "C: a quarantined slot makes the pool report itself degraded");
        Assert.assertEquals(0, pool.metrics().creating(),
                "C: the reserved creation slot is no longer in flight");

        // The decisive consequence: no replacement can be created while the old resource may exist.
        Assert.assertThrows(TimeoutException.class, pool::borrow,
                "C: a quarantined slot must not authorise a replacement - the borrow has to fail with"
                        + " backpressure instead of opening a second physical session");
        Assert.assertEquals(1, factory.liveCount(),
                "C: no second resource was created on top of the first");
    }

    /**
     * A PROVEN_CLEAN creation failure releases the slot, and the very next borrow succeeds.
     *
     * <p>The counterpart of the test above, and the reason the outcome is explicit rather than inferred:
     * the two failures are byte-for-byte identical to the pool except for what the factory reports, and
     * they must have opposite effects on capacity.
     */
    public void aProvenCleanCreationFailureReleasesTheSlot() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm", 1, PATIENT, factory);

        factory.failNextCreatesAfterAllocation(1, true);
        Assert.assertThrows(PoolException.class, pool::borrow,
                "a PROVEN_CLEAN creation failure still surfaces as a PoolException");

        Assert.assertEquals(0, factory.liveCount(),
                "C: the factory proved it released what it allocated, so nothing is alive");
        Assert.assertEquals(0, pool.metrics().quarantined(),
                "C: a proven-clean failure does not quarantine anything");
        Assert.assertEquals(0, pool.metrics().capacityInUse(),
                "C: the reserved slot was released");
        Assert.assertEquals(0L, pool.metrics().createQuarantineFailures(),
                "C: no quarantine was recorded");
        Assert.assertFalse(pool.metrics().degraded(),
                "C: the pool is not degraded by a failure that released everything");

        try (Lease<FakeResource> lease = pool.borrow()) {
            Assert.assertNotNull(lease.value(), "C: the pool recovers once the factory works again");
        }
        Assert.assertEquals(1, factory.liveCount(), "C: exactly one resource is live after recovery");
    }

    /**
     * A pool whose slot was quarantined by a CREATION failure must report its shutdown as UNCERTAIN, which
     * is what stops a repository switch from opening the next repository's sessions beside a possibly-live
     * one.
     *
     * <p>This crosses sections C and the Goal 01C contract, and it was the one high-value gap the tests
     * member found in this suite: the create-quarantine path was previously covered only up to
     * {@code metrics().quarantined()}, never up to the {@link CloseState} that the switch rule actually
     * reads. {@code RepositoryManager} refuses a switch when the previous context is
     * {@code CLOSED_UNCERTAIN}, and it learns that from the owned pool's state, so a creation quarantine
     * that reported {@code CLOSED_CLEAN} would let the next repository open a session while the physical
     * outcome of this one is still unknown - the exact bound breach section C exists to prevent.
     *
     * <p>Also pinned: the quarantine is what makes it uncertain, not merely that close() was called.
     * {@code closedWithUncertainResources()} is derived from the same state, so the two must agree.
     */
    public void aCreationQuarantineMakesThePoolShutdownUncertain() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm", 2, IMPATIENT, factory);

        Assert.assertEquals(CloseState.NOT_CLOSED, pool.closeState(),
                "C: an in-service pool is NOT_CLOSED, which refuses reuse without claiming a leak");
        Assert.assertFalse(pool.closedWithUncertainResources(),
                "C: an open pool with nothing quarantined is not uncertain");

        factory.failNextCreatesAfterAllocation(1, false);
        Assert.assertThrows(PoolException.class, pool::borrow,
                "C: the initial creation fails with an unproven cleanup");
        Assert.assertEquals(1, pool.metrics().quarantined(), "C: the slot is quarantined");

        // The pool is still OPEN here, so the question "did close() leave anything" is not yet being asked.
        Assert.assertEquals(CloseState.NOT_CLOSED, pool.closeState(),
                "C: open with a quarantined slot still reports NOT_CLOSED - the state describes the last"
                        + " shutdown, and there has not been one yet");
        Assert.assertEquals(1, pool.metrics().capacityInUse(),
                "C: but the quarantined slot still consumes capacity while the pool is open");

        pool.close();

        Assert.assertEquals(CloseState.CLOSED_UNCERTAIN, pool.closeState(),
                "C: a close after a CREATION quarantine is UNCERTAIN, not clean - the pool cannot prove the"
                        + " session the failed attempt left behind is gone");
        Assert.assertTrue(pool.closedWithUncertainResources(),
                "C: closedWithUncertainResources() must agree with closeState(), because the switch rule"
                        + " reads one and the context derives the other");
        Assert.assertTrue(pool.metrics().degraded(),
                "C: the pool reports itself degraded so an operator can see why capacity is missing");
        Assert.assertTrue(pool.closeState().refusesReuse(),
                "C: an uncertain shutdown must refuse reuse - this is the predicate a repository switch"
                        + " relies on before it opens the next repository's sessions");
        Assert.assertEquals(1, pool.metrics().createQuarantineFailures(),
                "C: the event is attributed to a creation quarantine, not to a close failure");
        Assert.assertEquals(0L, pool.metrics().closeFailures(),
                "C: no close failed here, so the close counters stay clean - the uncertainty did not come"
                        + " from this shutdown");
    }

    /**
     * An ORDINARY exception keeps the historical reading and releases the slot.
     *
     * <p>This is the compatibility half of the contract and it is asserted on purpose. Four committed
     * Goal 01 assertions pin that a throwing {@code create()} returns its reserved slot
     * ({@code BoundedPoolLifecycleTest.aFactoryFailureOnBorrowSurfacesAsAPoolExceptionWithNoLeak},
     * {@code BoundedPoolHardeningTest.anErrorDuringACreationReturnsTheReservedSlotInsteadOfBurningIt},
     * {@code aFailedCreationAttemptStillReportsItsWait}, and the partial-initialize case). A Goal 02
     * change that started quarantining untyped failures would break all four, and it would do so by
     * silently narrowing a documented contract rather than by failing a test. The residual risk - a
     * factory that leaks and then throws a plain exception remains invisible to the pool - is a
     * documented limitation in STATUS.md, not a hidden one.
     */
    public void anOrdinaryExceptionStillReleasesTheSlot() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm", 1, PATIENT, factory);

        factory.failNextCreates(1);
        Assert.assertThrows(PoolException.class, pool::borrow,
                "a failing factory surfaces as a PoolException");

        Assert.assertEquals(0, pool.metrics().capacityInUse(),
                "C: an untyped failure keeps its historical meaning - the reserved slot is released");
        Assert.assertEquals(0, pool.metrics().quarantined(),
                "C: an untyped failure does not quarantine anything");
        Assert.assertEquals(0L, pool.metrics().createQuarantineFailures(),
                "C: an untyped failure is not counted as a creation quarantine");

        try (Lease<FakeResource> lease = pool.borrow()) {
            Assert.assertNotNull(lease.value(), "C: the pool recovers, as it always did");
        }
    }

    /**
     * The capacity identity holds while a slot is quarantined, and the quarantine is monotone.
     *
     * <p>{@code available + leased + creating + retiring + quarantined == capacityInUse()} is the sum
     * that authorises every later creation, so a window in which a slot is in neither set would let the
     * next borrow overshoot the bound. The identity is therefore checked at every point of an
     * initialize-then-borrow sequence, not only at the end.
     */
    public void theCapacityIdentityHoldsAcrossAnUnprovenFailure() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm", 2, IMPATIENT, factory);

        assertIdentity(pool, "before anything happened");

        // Both slots are created and HELD. A returned lease makes its resource idle again and the next
        // borrow would reuse it instead of creating, which would miss the branch under test entirely.
        Lease<FakeResource> first = pool.borrow();
        Lease<FakeResource> second = pool.borrow();
        Assert.assertNotNull(first.value(), "C: the first borrow created a real resource");
        Assert.assertNotNull(second.value(), "C: the second borrow created a real resource");
        Assert.assertEquals(2, factory.liveCount(), "C: two physical resources exist, the configured bound");
        assertIdentity(pool, "with both slots leased");

        // Retire the first resource and force the replacement creation to fail with an unproven cleanup.
        // The retire frees its slot for the replacement, so the failing creation is reached - with size 1
        // it would instead be refused by backpressure before it could create anything.
        first.value().setHealthy(false);
        first.close();
        factory.failNextCreatesAfterAllocation(1, false);
        Assert.assertThrows(PoolException.class, pool::borrow,
                "C: the replacement creation fails with an unproven cleanup");
        assertIdentity(pool, "after an unproven creation failure");

        Assert.assertEquals(1, pool.metrics().quarantined(), "C: exactly one slot is quarantined");
        Assert.assertEquals(2, pool.metrics().capacityInUse(),
                "C: one live lease plus one quarantined slot consume the whole configured size");
        Assert.assertEquals(2, factory.liveCount(),
                "C: two physical resources are alive: the held one, and the one the unproven failure left"
                        + " behind with its cleanup unproven (the retired resource is proven closed, so it"
                        + " is not alive). Two is the configured bound, and the bound held.");

        // Both slots are consumed now, so a further borrow cannot create and must report backpressure
        // rather than open a session next to one that may still exist.
        int liveBefore = factory.liveCount();
        Assert.assertThrows(TimeoutException.class, pool::borrow,
                "C: the pool must refuse rather than exceed its configured physical bound");
        Assert.assertEquals(liveBefore, factory.liveCount(),
                "C: no physical resource was created by the refused borrow");

        second.close();
        pool.close();
    }

    /**
     * An initialize() that fails part-way with an UNPROVEN cleanup quarantines that attempt's slot while
     * still closing and accounting for every slot it had already filled.
     *
     * <p>This is the goal's explicit "initialize() failing part-way through several slots" case, in the
     * form that matters for the bound: the already-created resources must be released through the normal
     * retirement path, and the FAILED attempt must not be silently forgotten. The failing attempt is not
     * in the pool's created set - the pool never received its resource - so its slot is the one that
     * would have been lost with the exception.
     */
    public void aPartialInitializeFailureQuarantinesOnlyTheUnprovenAttempt() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm", 3, IMPATIENT, factory);

        // Fail the THIRD attempt after it allocates, unproven. The first two succeed and must be closed.
        factory.failNextCreatesAfterAllocation(0, false);
        ResourceFactory<FakeResource> thirdAttemptFails = new ResourceFactory<>() {
            private final AtomicInteger attempts = new AtomicInteger();

            @Override
            public FakeResource create() throws Exception {
                if (attempts.incrementAndGet() == 3) {
                    // Allocate through the fake first, so the physical resource really exists, then
                    // report the cleanup as unproven exactly as a CM adapter would.
                    factory.failNextCreatesAfterAllocation(1, false);
                }
                return factory.create();
            }

            @Override
            public boolean isHealthy(FakeResource resource) {
                return factory.isHealthy(resource);
            }
        };

        BoundedPool<FakeResource> partial = new BoundedPool<>("cm", 3, IMPATIENT, thirdAttemptFails);
        // initialize() propagates the factory's own failure unchanged - it is not wrapped, because the
        // caller of initialize() is the one that can act on it. So the CreationFailure itself arrives.
        CreationFailure propagated = Assert.assertThrows(CreationFailure.class, partial::initialize,
                "C: a partial initialize failure propagates the factory's own failure");
        Assert.assertEquals(CreationFailure.Cleanup.UNPROVEN, propagated.cleanup(),
                "C: the cleanup verdict survives the rollback, so a caller can see why capacity was lost");

        Assert.assertEquals(1, partial.metrics().quarantined(),
                "C: only the unproven attempt's slot is quarantined");
        Assert.assertEquals(0, partial.metrics().available(),
                "C: the pool is left with no usable resource");
        Assert.assertEquals(0, partial.metrics().leased(), "C: nothing is leased");
        Assert.assertEquals(1L, partial.metrics().createQuarantineFailures(),
                "C: the unproven attempt is counted");
        assertIdentity(partial, "after a partial initialize with an unproven failure");
        Assert.assertTrue(partial.metrics().capacityInUse() <= partial.metrics().configuredSize(),
                "C: capacity is never above the configured size");

        // The failed round must leave NO outstanding reservation behind: the failing attempt is resolved
        // by its own outcome and every attempt that never started is released. A leak here is not
        // cosmetic - a leaked `creating` slot makes an untouched pool report itself as still creating,
        // and makes a proven-clean activation failure refuse every later activation for the life of the
        // process, because the phantom reservation can never be released.
        Assert.assertEquals(0, partial.metrics().creating(),
                "C: a failed initialize() must not leave a phantom in-flight creation behind");
        Assert.assertEquals(1, partial.metrics().capacityInUse(),
                "C: only the quarantined slot is consumed; nothing else is reserved");

        // The pool is deliberately NOT retryable after a partial failure that quarantined a slot, and this
        // is asserted rather than assumed. Option (B) - reserving `size - quarantined` on a retry - was
        // considered and rejected: it would let a caller re-fill a pool that has permanently lost a slot
        // to an unknown physical outcome, and nothing in the production path ever re-initializes a pool
        // (a quarantined cleanup context is CLOSED_UNCERTAIN, so the manager refuses every later
        // activation and never calls the factory again). A retry that cannot happen in production is not
        // worth the extra accounting, and the operator-facing answer is "build a new pool".
        //
        // What the refusal MUST say is also asserted: the old message read "has already handed out
        // resources", which is simply wrong here - nothing was handed out, and an operator reading it
        // would hunt for a leaked lease instead of the quarantine that actually caused it.
        factory.failNextCreatesAfterAllocation(0, false);
        IllegalStateException refused = Assert.assertThrows(IllegalStateException.class, partial::initialize,
                "C: a pool that permanently lost a slot to a quarantine is not re-initializable");
        Assert.assertTrue(refused.getMessage().contains("quarantined=1"),
                "C: the refusal names the quarantine as the reason, not a phantom lease: " + refused.getMessage());
        Assert.assertTrue(refused.getMessage().contains("new pool"),
                "C: the refusal tells the operator what to do instead: " + refused.getMessage());
        assertIdentity(partial, "after the refused re-initialization");
        Assert.assertEquals(0, partial.metrics().creating(),
                "C: the refused re-initialization reserved nothing and left nothing in flight");

        pool.close();
        partial.close();
    }

    /** Asserts the capacity identity and the hard bound in one place, so no site can check only one. */
    private static void assertIdentity(BoundedPool<FakeResource> pool, String when) {
        long sum = (long) pool.metrics().available()
                + pool.metrics().leased()
                + pool.metrics().creating()
                + pool.metrics().retiring()
                + pool.metrics().quarantined();
        Assert.assertEquals((int) sum, pool.metrics().capacityInUse(),
                "C: available+leased+creating+retiring+quarantined must equal capacityInUse() " + when);
        Assert.assertTrue(pool.metrics().capacityInUse() <= pool.metrics().configuredSize(),
                "C: capacityInUse() must never exceed configuredSize() " + when);
    }

    /** A factory that throws an UNPROVEN failure is not a runtime exception, so the pool must classify it. */
    public void anUnprovenFailureIsNotMistakenForAFatalError() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm", 1, PATIENT, factory);
        factory.failNextCreatesAfterAllocation(1, false);

        PoolException failure = Assert.assertThrows(PoolException.class, pool::borrow,
                "an UNPROVEN creation failure is a recoverable pool failure, not an Error");
        Assert.assertNotNull(failure.getCause(),
                "C: the original CreationFailure is kept as the cause so an operator can see the outcome");
        Assert.assertTrue(failure.getCause() instanceof CreationFailure,
                "C: the cause is the CreationFailure itself, not a wrapper that hides the cleanup verdict");
        Assert.assertEquals(CreationFailure.Cleanup.UNPROVEN,
                ((CreationFailure) failure.getCause()).cleanup(),
                "C: the cleanup verdict survives to the caller");
    }

    /** An InterruptedException still propagates unchanged: it is not a pool failure to be reported. */
    public void anInterruptedCreationStillPropagatesAsInterruption() throws Exception {
        ResourceFactory<FakeResource> interrupted = new ResourceFactory<>() {
            @Override
            public FakeResource create() throws Exception {
                throw new InterruptedException("interrupted while connecting");
            }
        };
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm", 1, PATIENT, interrupted);
        Assert.assertThrows(InterruptedException.class, pool::borrow,
                "C: an interrupted creation must reach the caller as an interruption, not as a PoolException");
        Assert.assertEquals(0, pool.metrics().quarantined(),
                "C: a plain InterruptedException carries no cleanup verdict, so the historical release applies");
        Assert.assertEquals(0, pool.metrics().capacityInUse(),
                "C: the reserved slot was released, which keeps the pre-existing behaviour intact");
    }
}
