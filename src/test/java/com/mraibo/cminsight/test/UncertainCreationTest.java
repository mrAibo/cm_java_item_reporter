package com.mraibo.cminsight.test;

import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CreationFailure;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.connection.PoolException;
import com.mraibo.cminsight.connection.ResourceFactory;
import com.mraibo.cminsight.core.CloseState;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p>Goal 02 section C fixed half of that with an explicit outcome: {@code CreationFailure(PROVEN_CLEAN)}
 * releases the slot, {@code CreationFailure(UNPROVEN)} quarantines it. Goal 02A section A closes the
 * other half, and it is the half that matters for Goal 03: an ORDINARY exception carried the historical
 * reading "this attempt produced nothing" and released the slot, so a JDBC factory that opened a
 * connection and then threw a plain {@code SQLException} would silently breach the bound. The rule is now
 * fail-safe in the other direction:
 *
 * <pre>
 *   CreationFailure(PROVEN_CLEAN)                     -> release (the ONLY releasing outcome)
 *   CreationFailure(UNPROVEN)                         -> quarantine
 *   plain Exception / RuntimeException / Error        -> quarantine
 * </pre>
 *
 * <p>This file pins every branch of that table, the capacity identity while a slot is quarantined, the
 * concurrency identity under borrowed/creating/retiring slots, and - because a correct peak number proves
 * nothing on its own - an executable MUTATION CONTROL: the pre-correction "plain throw =&gt; release" rule
 * is kept as {@link LegacyReleasePool} and measured with the same factory, and it must overshoot the
 * configured physical bound while the corrected pool does not. That control is what shows the measurement
 * has the power to see a breach rather than merely that no breach was seen.
 *
 * <p>Every interleaving here is driven by direct calls or by latches, not by sleeps: the properties
 * asserted are arithmetic invariants and exact counts, so there is no scheduler luck to lose.
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
     * A PLAIN checked exception quarantines the reserved slot: fail-safe is the default, not the
     * exception.
     *
     * <p>This is the assertion that Goal 02A section A inverts, and it is asserted on the whole
     * consequence rather than on the flag: the slot stays consumed, the pool reports itself degraded and
     * reports the event as a creation quarantine, nothing stays in flight, and the very next borrow fails
     * with backpressure instead of opening a replacement.
     *
     * <p>The factory here throws BEFORE it allocates anything - the fake's own {@code liveCount()} stays
     * 0 - which is exactly the argument the old default was built on ("nothing was created, so the slot is
     * free"). The pool cannot know that, and the rule deliberately does not depend on it: a factory that
     * knows it failed before allocating must say {@code PROVEN_CLEAN} explicitly. Capacity is the price of
     * never letting an unknown outcome authorise a replacement.
     */
    public void aPlainCheckedExceptionQuarantinesTheSlotByDefault() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm", 1, IMPATIENT, factory);

        factory.failNextCreates(1);
        PoolException surfaced = Assert.assertThrows(PoolException.class, pool::borrow,
                "a plain checked exception still surfaces as the pool's own failure type");

        Assert.assertTrue(surfaced.getCause() instanceof IOException,
                "A: the original checked exception is kept as the cause: " + surfaced.getCause());
        Assert.assertEquals(0, factory.liveCount(),
                "A: this factory mode really did allocate nothing, so the physical world is empty - the"
                        + " quarantine below is therefore the conservative default and not a leak report");
        Assert.assertEquals(1, pool.metrics().quarantined(),
                "A: a plain checked exception MUST quarantine the reserved slot - releasing it is the"
                        + " fail-open rule section A removes");
        Assert.assertEquals(1, pool.metrics().capacityInUse(), "A: the quarantined slot consumes capacity");
        Assert.assertEquals(1L, pool.metrics().createQuarantineFailures(),
                "A: the event is visible as a creation quarantine, not only as a generic failure");
        Assert.assertEquals(1L, pool.metrics().createFailures(), "A: the attempt is still a creation failure");
        Assert.assertEquals(0, pool.metrics().creating(), "A: no reservation is left in flight");
        Assert.assertTrue(pool.metrics().degraded(), "A: the pool reports the capacity it lost");
        assertIdentity(pool, "after a plain checked exception");

        int attemptsBefore = factory.createAttempts();
        Assert.assertThrows(TimeoutException.class, pool::borrow,
                "A: the quarantined slot must not authorise a replacement creation");
        Assert.assertEquals(attemptsBefore, factory.createAttempts(),
                "A: no physical resource was created on top of the unknown outcome");
        pool.close();
    }

    /**
     * A plain {@link RuntimeException} after the factory physically allocated quarantines the slot, and
     * the measurement says why: the resource is demonstrably still alive.
     *
     * <p>This is the shape Goal 03's JDBC factory will have: allocate, fail, and throw something ordinary
     * because the adapter author did not know that a verdict was required. The pool must not read that as
     * "nothing happened" - {@code liveCount()} proves that something did.
     */
    public void aPlainRuntimeExceptionQuarantinesTheSlotAndKeepsThePhysicallyLiveResourceAccountedFor()
            throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm", 1, IMPATIENT, factory);

        factory.failNextCreatesAfterAllocationLeaking(1,
                () -> new IllegalStateException("simulated untyped failure after allocation"));
        PoolException surfaced = Assert.assertThrows(PoolException.class, pool::borrow,
                "A: the plain runtime failure surfaces as a PoolException");

        Assert.assertTrue(surfaced.getCause() instanceof IllegalStateException,
                "A: the original RuntimeException reaches the caller unchanged as the cause");
        Assert.assertEquals(1, factory.liveCount(),
                "A: the resource the failing attempt allocated is STILL ALIVE - the pool was given no"
                        + " cleanup evidence at all, so it may not assume the opposite");
        Assert.assertEquals(1, factory.peakLive(),
                "A: the physical peak never exceeded the configured bound");
        Assert.assertEquals(1, pool.metrics().quarantined(), "A: the slot is quarantined");
        Assert.assertEquals(1, pool.metrics().capacityInUse(), "A: and still consumes capacity");
        Assert.assertEquals(1L, pool.metrics().createQuarantineFailures(),
                "A: the quarantine is attributed to the creation failure");
        assertIdentity(pool, "after an untyped failure that left a resource behind");

        int attemptsBefore = factory.createAttempts();
        Assert.assertThrows(TimeoutException.class, pool::borrow,
                "A: with the only slot quarantined and the resource possibly alive, the pool must refuse"
                        + " rather than open a second physical session");
        Assert.assertEquals(attemptsBefore, factory.createAttempts(), "A: the refusal created nothing");
        Assert.assertEquals(1, factory.liveCount(), "A: and the physical live count stayed at the bound");
        pool.close();
    }

    /**
     * A plain {@link Error} quarantines the slot BEFORE the Error propagates.
     *
     * <p>"Before" is the load-bearing word and it is asserted the only way it can be: the Error is caught
     * by the test, and at that moment the accounting already shows the quarantined slot. An implementation
     * that quarantined during some later cleanup would leave a window in which a concurrent borrow could
     * reserve the slot and open a replacement next to the resource the Error left behind.
     */
    public void aPlainErrorQuarantinesTheSlotBeforeTheErrorPropagates() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm", 1, IMPATIENT, factory);

        factory.failNextCreatesAfterAllocationLeaking(1, () -> new UnknownCreationError("simulated fatal"));
        UnknownCreationError fatal = Assert.assertThrows(UnknownCreationError.class, pool::borrow,
                "A: an Error from the factory must still reach the borrower as that same Error");

        Assert.assertEquals("simulated fatal", fatal.getMessage(), "A: the Error is rethrown unchanged");
        Assert.assertEquals(1, pool.metrics().quarantined(),
                "A: by the time the Error is observable, the slot is already quarantined - the accounting"
                        + " must not be left to a later cleanup step");
        Assert.assertEquals(1, pool.metrics().capacityInUse(), "A: and the slot already consumes capacity");
        Assert.assertEquals(0, pool.metrics().creating(),
                "A: the reservation is resolved, not abandoned in flight");
        Assert.assertEquals(1L, pool.metrics().createQuarantineFailures(),
                "A: the fatal failure is counted as a creation quarantine");
        Assert.assertEquals(1, factory.liveCount(),
                "A: the resource the fatal attempt left behind is still physically alive");
        assertIdentity(pool, "after a plain Error");

        Assert.assertThrows(TimeoutException.class, pool::borrow,
                "A: no replacement may be created while the Error's physical outcome is unknown");
        pool.close();
    }

    /** An {@link Error} raised by {@code factory.create()} in this suite. */
    private static final class UnknownCreationError extends Error {

        private static final long serialVersionUID = 1L;

        UnknownCreationError(String message) {
            super(message);
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

    /**
     * An InterruptedException still propagates unchanged, and it is NOT an accident that releases the slot.
     *
     * <p>Both halves matter and they are asserted together. The interruption must reach the caller as an
     * interruption - wrapping it in a {@code PoolException} would disguise an orderly shutdown as a
     * repository failure - and it must not be the one plain throwable that slips past the new rule: a raw
     * interrupt is no more evidence of "the factory allocated nothing" than any other untyped failure.
     * A factory that is certain it allocated nothing must say {@code CreationFailure(PROVEN_CLEAN)} and
     * restore its own interrupt status; that explicit statement is what the pool acts on.
     */
    public void anInterruptedCreationStillPropagatesAsInterruptionAndQuarantinesTheSlot() throws Exception {
        ResourceFactory<FakeResource> interrupted = new ResourceFactory<>() {
            @Override
            public FakeResource create() throws Exception {
                throw new InterruptedException("interrupted while connecting");
            }
        };
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm", 1, IMPATIENT, interrupted);
        InterruptedException propagated = Assert.assertThrows(InterruptedException.class, pool::borrow,
                "A: an interrupted creation must reach the caller as an interruption, not as a PoolException");
        Assert.assertEquals("interrupted while connecting", propagated.getMessage(),
                "A: the interruption is the same instance-level failure, not a replacement");
        Assert.assertEquals(1, pool.metrics().quarantined(),
                "A: an untyped interruption is not cleanup evidence, so the slot is quarantined like any"
                        + " other plain failure");
        Assert.assertEquals(1L, pool.metrics().createQuarantineFailures(),
                "A: and it is counted as a creation quarantine");
        Assert.assertEquals(1, pool.metrics().capacityInUse(), "A: the slot still consumes capacity");
        Assert.assertEquals(0, pool.metrics().creating(), "A: nothing is left in flight");
        assertIdentity(pool, "after an interrupted creation");
        Assert.assertThrows(TimeoutException.class, pool::borrow,
                "A: a quarantined slot does not authorise a replacement, interrupted or not");
        pool.close();
    }

    /**
     * A partial {@code initialize()} whose failing attempt reports a PLAIN failure quarantines the failed
     * attempt's reservation and never frees it.
     *
     * <p>This is the goal's explicit "partial initialize with unknown failure never frees the failed
     * attempt's physical reservation" case, stated in the form that matters: the physical resource the
     * failing attempt left behind must stay accounted for. The pool never received it - the attempt threw
     * before returning - so its only protection is the reservation, and the assertion that the reservation
     * was not handed back is therefore the assertion that the bound still holds.
     *
     * <p>The already-created resources are asserted too: they must go through the normal retirement path
     * rather than being abandoned by the rollback, and no reservation may survive the rollback.
     */
    public void aPartialInitializeWithAnUnknownFailureNeverFreesTheFailedAttemptsReservation()
            throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        ResourceFactory<FakeResource> thirdAttemptFailsUnknown = new ResourceFactory<>() {
            private final AtomicInteger attempts = new AtomicInteger();

            @Override
            public FakeResource create() throws Exception {
                if (attempts.incrementAndGet() == 3) {
                    // Allocate for real (live/peak rise) and then fail with NO verdict at all, leaving the
                    // resource alive: the pool has no evidence it may be released.
                    factory.failNextCreatesAfterAllocationLeaking(1,
                            () -> new IllegalStateException("simulated unknown failure during initialize"));
                }
                return factory.create();
            }

            @Override
            public boolean isHealthy(FakeResource resource) {
                return factory.isHealthy(resource);
            }
        };
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm", 3, IMPATIENT, thirdAttemptFailsUnknown);

        Assert.assertThrows(IllegalStateException.class, pool::initialize,
                "A: initialize() propagates the factory's own failure unchanged, even a plain one");

        Assert.assertEquals(1, factory.liveCount(),
                "A: the resource the failed attempt allocated is still physically alive, so its reservation"
                        + " must NOT have been returned to the capacity pool");
        Assert.assertEquals(3, factory.createAttempts(),
                "A: exactly three attempts ran; the third is the one that failed");
        Assert.assertEquals(0, pool.metrics().available(), "A: the pool is not usable");
        Assert.assertEquals(0, pool.metrics().leased(), "A: nothing was leased");
        Assert.assertEquals(0, pool.metrics().creating(),
                "A: the rollback must resolve every reservation - including the never-started one - and"
                        + " leave no phantom in-flight creation");
        Assert.assertEquals(1, pool.metrics().quarantined(),
                "A: only the failed attempt's reservation is quarantined");
        Assert.assertEquals(1L, pool.metrics().createQuarantineFailures(), "A: the event is counted");
        Assert.assertEquals(1, pool.metrics().capacityInUse(),
                "A: the quarantined reservation is the only capacity still accounted for");
        Assert.assertTrue(pool.metrics().capacityInUse() <= pool.metrics().configuredSize(),
                "A: capacity never exceeds the configured size");
        assertIdentity(pool, "after a partial initialize with an unknown failure");
        Assert.assertTrue(pool.metrics().degraded(), "A: the lost capacity is visible");

        // The pool permanently lost a slot to an unknown physical outcome, so it is not re-fillable, and
        // the refusal says so rather than blaming a leaked lease.
        IllegalStateException refused = Assert.assertThrows(IllegalStateException.class, pool::initialize,
                "A: a pool that lost a slot to a quarantine is not re-initializable");
        Assert.assertTrue(refused.getMessage().contains("quarantined=1"),
                "A: the refusal names the quarantine as the reason: " + refused.getMessage());
        Assert.assertTrue(refused.getMessage().contains("new pool"),
                "A: and tells the operator what to do instead: " + refused.getMessage());
        Assert.assertEquals(1, factory.liveCount(), "A: the refused re-initialization created nothing");
        pool.close();
    }

    /**
     * A partial {@code initialize()} whose failing attempt proves a clean cleanup stays retryable and
     * releases every reservation it did not use.
     *
     * <p>The counterpart of the test above, and the only remaining coverage of the never-started
     * reservations: a plain failure now quarantines, so the "release the ones that never started" branch is
     * reached only when the failing attempt explicitly reports {@code PROVEN_CLEAN}. If that arithmetic
     * were wrong the pool would leak in-flight reservations, and an untouched retry would refuse to fill
     * itself for the life of the process.
     */
    public void aProvenCleanPartialInitializeFailureIsRetryableAndReservesNothing() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean failTheThird = new AtomicBoolean(true);
        ResourceFactory<FakeResource> thirdAttemptProvenClean = new ResourceFactory<>() {
            @Override
            public FakeResource create() throws Exception {
                if (attempts.incrementAndGet() == 3 && failTheThird.getAndSet(false)) {
                    factory.failNextCreatesAfterAllocation(1, true);
                }
                return factory.create();
            }

            @Override
            public boolean isHealthy(FakeResource resource) {
                return factory.isHealthy(resource);
            }
        };
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm", 3, IMPATIENT, thirdAttemptProvenClean);

        CreationFailure propagated = Assert.assertThrows(CreationFailure.class, pool::initialize,
                "A: the factory's own verdict propagates out of the rollback");
        Assert.assertEquals(CreationFailure.Cleanup.PROVEN_CLEAN, propagated.cleanup(),
                "A: the proven-clean verdict survives");

        Assert.assertEquals(0, factory.liveCount(),
                "A: a proven-clean attempt released what it allocated, so nothing is alive");
        Assert.assertEquals(0, pool.metrics().quarantined(),
                "A: a proven-clean failure quarantines nothing - the slot may come back");
        Assert.assertEquals(0, pool.metrics().creating(),
                "A: every reservation is resolved, including the attempts that never started");
        Assert.assertEquals(0, pool.metrics().capacityInUse(), "A: the pool is left empty");
        assertIdentity(pool, "after a proven-clean partial initialize failure");

        pool.initialize();
        Assert.assertEquals(3, pool.metrics().available(),
                "A: the retry fills every configured slot, which the leaked reservation would have made"
                        + " impossible");
        Assert.assertEquals(3, pool.metrics().capacityInUse(), "A: at the configured bound, not above it");
        Assert.assertEquals(0, pool.metrics().creating(), "A: the retry left nothing in flight");
        assertIdentity(pool, "after a successful retry");
        pool.close();
    }

    /**
     * The capacity identity holds while borrows, creations and returns overlap.
     *
     * <p>{@code available + leased + creating + retiring + quarantined == capacityInUse()} is the sum that
     * authorises every creation, and because it is maintained under the pool's single lock it must hold at
     * EVERY instant, not only between operations. The sampler below asserts it continuously while worker
     * threads borrow, fail, create, return and retire, so a window in which a slot is in neither set - the
     * only way the pool could overshoot - fails here rather than in production.
     *
     * <p>Determinism: the workers are released together by latches, one worker keeps a creation parked on a
     * gate so the {@code creating} state is genuinely observable, and the run is bounded. Nothing depends
     * on a sleep: the invariant asserted is one that must hold under every interleaving, so a violation is
     * a real defect and never a timing coincidence.
     */
    public void theCapacityIdentityHoldsUnderConcurrentBorrowCreateAndClose() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm", 3, Duration.ofMillis(150), factory);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch created = new CountDownLatch(1);
        AtomicReference<Throwable> invariantFailure = new AtomicReference<>();

        Thread sampler = new Thread(() -> {
            try {
                start.await();
                while (done.getCount() > 0) {
                    // ONE snapshot per sample, deliberately. capacityInUse() is the sum of the five fields
                    // of the SAME snapshot, so a sample assembled from five separate metrics() calls could
                    // report a difference that no single instant ever had - which would be a false failure
                    // in a test whose whole value is that its failure is real.
                    com.mraibo.cminsight.connection.PoolMetrics sample = pool.metrics();
                    long sum = (long) sample.available()
                            + sample.leased()
                            + sample.creating()
                            + sample.retiring()
                            + sample.quarantined();
                    int inUse = sample.capacityInUse();
                    if (sum != inUse) {
                        invariantFailure.compareAndSet(null, new AssertionError(
                                "A: available+leased+creating+retiring+quarantined (" + sum
                                        + ") must equal capacityInUse() (" + inUse + ") at every instant"));
                        return;
                    }
                    if (inUse > pool.configuredSize()) {
                        invariantFailure.compareAndSet(null, new AssertionError(
                                "A: capacityInUse() " + inUse + " exceeded configuredSize() "
                                        + pool.configuredSize() + " while operations overlapped"));
                        return;
                    }
                    if (inUse < 0) {
                        invariantFailure.compareAndSet(null, new AssertionError(
                                "A: capacityInUse() went negative (" + inUse + ")"));
                        return;
                    }
                }
            } catch (Throwable failure) {
                invariantFailure.compareAndSet(null, failure);
            }
        }, "pool-identity-sampler");
        sampler.setDaemon(true);

        Thread[] workers = new Thread[6];
        for (int index = 0; index < workers.length; index++) {
            boolean poisonThisWorker = index % 3 == 0;
            workers[index] = new Thread(() -> {
                try {
                    start.await();
                    for (int attempt = 0; attempt < 4; attempt++) {
                        if (attempt == 1) {
                            created.countDown();
                        }
                        Lease<FakeResource> lease;
                        try {
                            lease = pool.borrow();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (TimeoutException | PoolException refused) {
                            continue;
                        }
                        FakeResource resource = lease.value();
                        if (poisonThisWorker && attempt == 2) {
                            // Drive the retirement path concurrently with the creators: the reservation
                            // moves leased -> retiring and must stay accounted for the whole way.
                            resource.setHealthy(false);
                        }
                        lease.close();
                    }
                } catch (Throwable failure) {
                    invariantFailure.compareAndSet(null, failure);
                }
            }, "pool-identity-worker-" + index);
            workers[index].setDaemon(true);
        }

        sampler.start();
        for (Thread worker : workers) {
            worker.start();
        }
        start.countDown();
        created.await(10, TimeUnit.SECONDS);
        for (Thread worker : workers) {
            worker.join(20_000L);
            Assert.assertFalse(worker.isAlive(), "A: a worker must finish within the test budget");
        }
        done.countDown();
        sampler.join(20_000L);
        Assert.assertFalse(sampler.isAlive(), "A: the sampler must finish once the workers are done");

        if (invariantFailure.get() != null) {
            throw new AssertionError("A: the capacity identity was violated during concurrent use: "
                    + invariantFailure.get(), invariantFailure.get());
        }
        assertIdentity(pool, "after the concurrent run");
        Assert.assertTrue(factory.peakLive() <= pool.configuredSize(),
                "A: the physical peak stayed within the bound during the run: " + factory.peakLive());
        pool.close();
    }

    /**
     * MUTATION CONTROL: the pre-correction rule ("plain throw =&gt; release") exceeds the configured
     * physical bound, and the corrected pool does not.
     *
     * <p>A correct peak number proves nothing by itself - it can mean the bound held, or that the
     * measurement cannot see an overshoot. So the same measuring factory, the same number of attempts and
     * the same configured size are run against both pools:
     *
     * <ul>
     *   <li>{@link LegacyReleasePool} implements the OLD rule and must overshoot: every attempt allocates a
     *       resource, fails with a plain exception, and gets its reservation back, so the next attempt
     *       creates another one beside it;</li>
     *   <li>{@link BoundedPool} implements the corrected rule and must cap the physical peak at the
     *       configured size;</li>
     *   <li>the mutant's own {@code capacityInUse()} is asserted to be 0 while its physical live count is
     *       above the bound. That is the breach stated as a contradiction: the pool believes it has free
     *       capacity while more resources are alive than it was configured to allow.</li>
     * </ul>
     *
     * <p>If a future change restored release-on-plain-throw, the corrected half of this test would report
     * the same numbers as the mutant half and fail.
     */
    public void theMutationControlShowsTheOldReleaseRuleExceedsThePhysicalBound() throws Exception {
        final int configuredSize = 1;
        final int attempts = 3;

        // ---- the corrected pool: unknown failure => quarantine, so the bound holds ----------
        FakePoolFactory correctedFactory = new FakePoolFactory();
        correctedFactory.failEveryCreateAfterAllocationLeaking(
                () -> new IOException("simulated untyped failure that leaves the resource alive"));
        BoundedPool<FakeResource> corrected = new BoundedPool<>("mutant-control", configuredSize,
                Duration.ofMillis(50), correctedFactory);

        PoolException firstFailure = Assert.assertThrows(PoolException.class, corrected::borrow,
                "A: the first attempt fails with the plain exception the factory threw");
        Assert.assertTrue(firstFailure.getCause() instanceof IOException,
                "A: and the original untyped failure is kept as the cause: " + firstFailure.getCause());
        for (int attempt = 1; attempt < attempts; attempt++) {
            Assert.assertThrows(TimeoutException.class, corrected::borrow,
                    "A: with its only slot quarantined the corrected pool applies backpressure instead of"
                            + " creating another resource");
        }

        Assert.assertEquals(1, correctedFactory.createAttempts(),
                "A: the corrected pool created exactly ONCE - the quarantined attempt - and refused every"
                        + " later attempt instead of creating another resource");
        Assert.assertEquals(1, correctedFactory.liveCount(),
                "A: exactly one physical resource exists - the one the quarantined attempt left behind");
        Assert.assertEquals(configuredSize, correctedFactory.peakLive(),
                "A: the corrected pool never exceeded its configured physical bound");
        Assert.assertEquals(1, corrected.metrics().quarantined(), "A: its only slot is quarantined");
        Assert.assertEquals(configuredSize, corrected.metrics().capacityInUse(),
                "A: and its accounting says so");
        corrected.close();

        // ---- the mutant: plain throw => release, so the bound is breached ------------------
        FakePoolFactory mutantFactory = new FakePoolFactory();
        mutantFactory.failEveryCreateAfterAllocationLeaking(
                () -> new IOException("simulated untyped failure that leaves the resource alive"));
        LegacyReleasePool<FakeResource> mutant = new LegacyReleasePool<>("mutant-control", configuredSize,
                Duration.ofMillis(50), mutantFactory);

        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                mutant.borrow();
                Assert.fail("A: the mutant's factory always throws, so borrow() must always fail");
            } catch (IOException expected) {
                // the mutant rethrows the factory's own plain failure
            }
        }

        Assert.assertEquals(attempts, mutantFactory.createAttempts(),
                "A: the mutant created on EVERY attempt, because it gave the reservation back each time");
        Assert.assertEquals(attempts, mutantFactory.liveCount(),
                "A: every one of those resources is still physically alive");
        Assert.assertTrue(mutantFactory.peakLive() > configuredSize,
                "A: THE CONTROL: the old rule pushed the physical live count to " + mutantFactory.peakLive()
                        + " for a pool configured for " + configuredSize + " - the measurement can see the"
                        + " overshoot the corrected pool avoids");
        Assert.assertEquals(0, mutant.quarantined(), "A: the mutant never quarantines a creation failure");
        Assert.assertEquals(0, mutant.capacityInUse(),
                "A: and its accounting is the breach stated as a contradiction - it believes capacity is"
                        + " free while more resources are alive than it was configured to allow");
        Assert.assertTrue(correctedFactory.peakLive() < mutantFactory.peakLive(),
                "A: the two halves must differ, otherwise this control would pass for the wrong reason");
        mutant.close();
    }
}
