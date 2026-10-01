package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.core.CloseOutcomeAware;
import com.mraibo.cminsight.core.CloseState;
import com.mraibo.cminsight.core.CloseStateAware;
import com.mraibo.cminsight.core.RepositoryServices;
import com.mraibo.cminsight.repository.ActivationFailedException;
import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryContextFactory;
import com.mraibo.cminsight.repository.RepositoryException;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.repository.RepositoryState;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Goal 02 section C: what happens when activation FAILS AFTER allocating.
 *
 * <h2>The defect these tests exist for</h2>
 *
 * <p>A {@link RepositoryContextFactory} may allocate before it fails: the production CM factory opens a
 * bounded session pool, initializes it, and only then builds its read services. If a later step throws, the
 * pool it already allocated is physically live and the manager has never seen it - the manager can only
 * close what a factory RETURNED. Releasing that reference along with the exception would make the pool
 * invisible, and the next activation attempt would open a second one on top of resources the first still
 * owns. That is the same class of defect Goal 01C removed from the repository-switch path, one layer
 * further out.
 *
 * <p>The fix is the {@link ActivationFailedException#cleanupContext()} channel: the factory hands the
 * partially built context back, the manager retains it in the same latch a switch uses, and every later
 * activation is refused until that context is proven terminal-clean. The three outcomes therefore match a
 * switch away from a live repository exactly:
 *
 * <ul>
 *   <li>{@link CloseState#CLOSED_CLEAN} - a later explicit activation may proceed;</li>
 *   <li>{@link CloseState#CLOSING} - refused as {@code PENDING} until the resource is released;</li>
 *   <li>{@link CloseState#CLOSED_UNCERTAIN} - refused as {@code UNCERTAIN} permanently, in-process.</li>
 * </ul>
 *
 * <h2>What is asserted, and why it is the factory's call count</h2>
 *
 * <p>The property that matters is not the exception text but that <strong>the factory is never asked
 * again while a retained physical resource remains</strong>. A refused activation that still called
 * {@code create()} would have opened the second pool this section exists to prevent, so the refusal is
 * asserted together with a create-call count that must not have moved.
 *
 * <p>All interleavings are driven by direct calls on a fake context, never by sleeps.
 */
public final class ActivationCleanupTest {

    /**
     * An activation that fails while holding a resource the manager must retain, and what the manager then
     * does with the next activation request.
     */
    public void anUncertainCleanupRefusesEveryLaterActivationPermanently() {
        HoldResource pool = HoldResource.uncertain();
        CountingFactory factory = new CountingFactory();
        factory.failWith(pool);
        RepositoryManager manager = new RepositoryManager(factory);

        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(TestSupport.profile("alpha")),
                "the first activation failure must surface");
        Assert.assertEquals(1, factory.createCalls(),
                "C: the factory was called once for the failed activation");
        Assert.assertEquals(1, pool.closeCalls(), "C: the retained context was closed by the manager");

        int createsAfterFailure = factory.createCalls();
        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(TestSupport.profile("alpha")),
                "C: a second activation must be refused while the retained resource's shutdown is unproven");
        Assert.assertEquals(createsAfterFailure, factory.createCalls(),
                "C: the refusal must not call the factory again - the whole point is that no second pool is"
                        + " opened on top of a resource that may still exist");
        Assert.assertEquals(RepositoryManager.Refusal.UNCERTAIN, manager.refusal().orElse(null),
                "C: the refusal is UNCERTAIN, which tells an operator to restart rather than wait");
    }

    /** A retained resource that is merely still draining is PENDING, not UNCERTAIN. */
    public void aPendingCleanupRefusesActivationUntilTheResourceIsReleased() {
        HoldResource pool = HoldResource.pending();
        CountingFactory factory = new CountingFactory();
        factory.failWith(pool);
        RepositoryManager manager = new RepositoryManager(factory);

        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(TestSupport.profile("alpha")),
                "the first activation failure must surface");

        int createsAfterFailure = factory.createCalls();
        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(TestSupport.profile("alpha")),
                "C: activation is refused while a physical resource is still outstanding");
        Assert.assertEquals(createsAfterFailure, factory.createCalls(),
                "C: and the factory is not called again while that resource is outstanding");
        Assert.assertEquals(RepositoryManager.Refusal.PENDING, manager.refusal().orElse(null),
                "C: PENDING is reported, which is recoverable - unlike UNCERTAIN");
    }

    /**
     * Once the retained resource is proven released, a later explicit activation proceeds.
     *
     * <p>The counterpart of the PENDING case: a latch that could never be released would make the process
     * permanently unable to activate any repository, so "blocked until terminal-clean" has to mean
     * "unblocked afterwards".
     */
    public void aReleasedResourceLetsALaterActivationProceed() {
        HoldResource pool = HoldResource.pending();
        CountingFactory factory = new CountingFactory();
        factory.failWith(pool);
        RepositoryManager manager = new RepositoryManager(factory);

        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(TestSupport.profile("alpha")),
                "the first activation failure must surface");
        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(TestSupport.profile("alpha")),
                "the pending cleanup refuses the retry");

        pool.release();
        factory.succeedFromNowOn();
        try {
            manager.switchTo(TestSupport.profile("alpha"));
        } catch (RepositoryException failure) {
            Assert.fail("C: once the retained resource is terminal-clean, activation must be allowed: " + failure);
            return;
        }

        Assert.assertEquals(2, factory.createCalls(),
                "C: the factory is called again only after the retained resource is proven released");
        Assert.assertEquals(RepositoryState.ACTIVE, manager.state(),
                "C: the repository is active");
        Assert.assertTrue(manager.activeContext().isPresent(), "C: and its context is published");
    }

    /** An activation failure that retains nothing still fails cleanly and does not block the next attempt. */
    public void aFailureWithoutACleanupContextDoesNotLatchAnything() {
        CountingFactory factory = new CountingFactory();
        factory.failWithoutCleanup();
        RepositoryManager manager = new RepositoryManager(factory);

        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(TestSupport.profile("alpha")),
                "the failure must surface");
        factory.succeedFromNowOn();
        try {
            manager.switchTo(TestSupport.profile("alpha"));
        } catch (RepositoryException failure) {
            Assert.fail("C: a failure that allocated nothing must not block a later activation: " + failure);
            return;
        }
        Assert.assertEquals(RepositoryState.ACTIVE, manager.state(), "C: the repository is active");
    }

    /**
     * The retained context is visible while it is retained, so the leftover is never silent.
     *
     * <p>An operator has to be able to see WHICH repository still owns a live resource; a refusal that
     * named nothing would send them looking for a leaked lease instead.
     */
    public void theRetainedCleanupContextIsVisibleWhileItIsRetained() {
        HoldResource pool = HoldResource.uncertain();
        CountingFactory factory = new CountingFactory();
        factory.failWith(pool);
        RepositoryManager manager = new RepositoryManager(factory);

        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(TestSupport.profile("alpha")),
                "the failure must surface");

        Assert.assertTrue(manager.closingContext().isPresent(),
                "C: the partially built context must stay visible on the latch while it is unresolved");
        Assert.assertEquals("alpha", manager.closingContext().orElseThrow().profileUnchecked().id(),
                "C: and it names the repository that owns the outstanding resource");
        Assert.assertEquals(java.util.Optional.of(CloseState.CLOSED_UNCERTAIN), manager.closingState(),
                "C: its state is reported, so an operator can tell unproven from pending");
        Assert.assertFalse(manager.lastFailure().orElse("").isBlank(),
                "C: the refusal is recorded for diagnostics");
    }

    /** A resource whose close threw is uncertain, and the retained latch reports it as unproven. */
    public void aResourceThatRefusesToCloseMakesTheCleanupUncertain() {
        ThrowOnClose pool = new ThrowOnClose();
        CountingFactory factory = new CountingFactory();
        factory.failWithResource(pool);
        RepositoryManager manager = new RepositoryManager(factory);

        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(TestSupport.profile("alpha")),
                "the failure must surface");

        Assert.assertEquals(1, pool.closeCalls(), "C: the manager attempted to close the retained resource");
        Assert.assertEquals(CloseState.CLOSED_UNCERTAIN, manager.closingState().orElse(null),
                "C: a resource that refused to close may still hold a physical connection, so the shutdown is"
                        + " uncertain rather than merely pending");
        int creates = factory.createCalls();
        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(TestSupport.profile("alpha")),
                "C: and it stays refused");
        Assert.assertEquals(creates, factory.createCalls(),
                "C: without calling the factory again");
    }

    // ------------------------------------------------------------------ fakes

    /**
     * A factory that records how often it was asked to create a context.
     *
     * <p>The call count is the assertion for every refusal case: "refused" and "refused without opening a
     * second pool" are different statements, and only the count tells them apart.
     */
    private static final class CountingFactory implements RepositoryContextFactory {

        private final AtomicInteger createCalls = new AtomicInteger();
        private volatile HoldResource retained;
        private volatile ThrowOnClose retainedThrower;
        private volatile boolean failWithoutContext;
        private volatile boolean succeed;

        void failWith(HoldResource resource) {
            this.retained = resource;
            this.retainedThrower = null;
            this.succeed = false;
        }

        void failWithResource(ThrowOnClose resource) {
            this.retainedThrower = resource;
            this.retained = null;
            this.succeed = false;
        }

        void failWithoutCleanup() {
            this.retained = null;
            this.retainedThrower = null;
            this.failWithoutContext = true;
            this.succeed = false;
        }

        void succeedFromNowOn() {
            this.retained = null;
            this.retainedThrower = null;
            this.failWithoutContext = false;
            this.succeed = true;
        }

        int createCalls() {
            return createCalls.get();
        }

        @Override
        public RepositoryContext create(RepositoryProfile profile) throws Exception {
            createCalls.incrementAndGet();
            if (succeed) {
                return new RepositoryContext(profile, List.of(new HoldResource()),
                        RepositoryServices.NONE);
            }
            if (retained != null) {
                throw new ActivationFailedException(
                        "activation of '" + profile.id() + "' failed after allocating", retained.context(profile),
                        new IllegalStateException("a later activation step failed"));
            }
            if (retainedThrower != null) {
                throw new ActivationFailedException(
                        "activation of '" + profile.id() + "' failed after allocating",
                        retainedThrower.context(profile),
                        new IllegalStateException("a later activation step failed"));
            }
            if (failWithoutContext) {
                throw new ActivationFailedException("activation of '" + profile.id() + "' failed before allocating");
            }
            throw new IllegalStateException("the fake factory was not told what to do");
        }
    }

    /** A resource whose clone simply throws, with no way to report an outcome: a plain AutoCloseable. */
    private static final class ThrowOnClose implements AutoCloseable {

        private final AtomicInteger closeCalls = new AtomicInteger();

        int closeCalls() {
            return closeCalls.get();
        }

        RepositoryContext context(RepositoryProfile profile) {
            return new RepositoryContext(profile, List.of(this), RepositoryServices.NONE);
        }

        @Override
        public void close() throws Exception {
            closeCalls.incrementAndGet();
            throw new IllegalStateException("the pooled resource refused to close");
        }
    }

    /**
     * The resource a failing factory had already allocated: a closeable that can report its own physical
     * shutdown.
     *
     * <p>Models the bounded session pool without depending on it: what matters here is only that a context
     * can be LEFT clean, still draining, or unproven, and that the manager's response follows that state.
     * Implements {@link CloseOutcomeAware}, whose {@code close()} deliberately declares no checked exception
     * - a resource that can REPORT an uncertain shutdown carries the outcome in
     * {@link #closedWithUncertainResources()} instead of throwing, which is exactly how a pool behaves. The
     * throwing case is therefore modelled by {@link ThrowOnClose}, a plain {@code AutoCloseable}.
     */
    private static final class HoldResource implements CloseOutcomeAware, CloseStateAware {

        private final AtomicInteger closeCalls = new AtomicInteger();
        private volatile CloseState state;

        private HoldResource() {
            this(CloseState.CLOSED_CLEAN);
        }

        private HoldResource(CloseState state) {
            this.state = state;
        }

        static HoldResource uncertain() {
            return new HoldResource(CloseState.CLOSED_UNCERTAIN);
        }

        static HoldResource pending() {
            return new HoldResource(CloseState.CLOSING);
        }

        /** Simulates the outstanding physical resource finally coming back. */
        void release() {
            this.state = CloseState.CLOSED_CLEAN;
        }

        int closeCalls() {
            return closeCalls.get();
        }

        RepositoryContext context(RepositoryProfile profile) {
            return new RepositoryContext(profile, List.of(this), RepositoryServices.NONE);
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            // A normal return does NOT prove the physical resource is gone for a still-draining pool, so the
            // state is left as it is: CloseOutcomeAware is what the context consults.
        }

        @Override
        public boolean closedWithUncertainResources() {
            return state == CloseState.CLOSED_UNCERTAIN;
        }

        @Override
        public String uncertainCloseDetail() {
            return state == CloseState.CLOSED_UNCERTAIN ? "1 pooled session(s) quarantined" : "";
        }

        @Override
        public CloseState closeState() {
            return state;
        }
    }
}
