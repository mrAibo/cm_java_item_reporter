package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.connection.ResourceFactory;
import com.mraibo.cminsight.core.CloseState;
import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryContextFactory;
import com.mraibo.cminsight.repository.RepositoryException;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.repository.RepositoryState;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Goal 01C: a repository switch may never overlap the previous repository's live physical resources.
 *
 * <h2>The defect these tests pin</h2>
 *
 * {@code BoundedPool.close()} returns as soon as the IDLE resources are released. A resource that is
 * still out on a lease is closed later, by the thread that returns it - so at the instant close()
 * returned, the pool was "closed" while a physical session it owned was demonstrably still alive.
 * {@code quarantinedCount} was still 0, so {@code closedWithUncertainResources()} said false,
 * {@link RepositoryContext} snapshotted a clean close, and {@link RepositoryManager} was free to create
 * the next repository context on top of a live old connection. The Goal 01B review recorded this as
 * finding F3 and it is broader than a late quarantine: even a lease that comes back perfectly cleanly
 * comes back too late, because the switch has already happened.
 *
 * <h2>What is asserted</h2>
 *
 * Every test builds a REAL {@code BoundedPool<FakeResource>} inside repository context A - not a
 * hand-written {@code AutoCloseable}, which would prove nothing about the pool the adapter will use - and
 * drives the manager through the scenario with explicit latches. The decisive assertions are always the
 * same three:
 *
 * <ul>
 *   <li>the next-context factory was NOT called, counted, not merely "the state looks inactive";</li>
 *   <li>the old physical resource is still live when the switch was refused (or provably gone before the
 *       factory was allowed to run);</li>
 *   <li>the reported state says pending/uncertain - never clean - and the refusal survives EVERY retry,
 *       not just the first.</li>
 * </ul>
 *
 * <p>Ordering evidence is recorded as an event log, not only as final counts, because "the factory ran
 * after the old resource was gone" and "the factory ran and the counts happen to look fine now" are
 * different claims.
 *
 * <p>No test sleeps to create a race and none depends on scheduler luck: every interleaving is held open
 * by a latch from {@link FakePoolFactory} or by the pool's own lock, and every wait is bounded.
 */
public class RepositorySwitchQuiescenceTest {

    /** Nothing in these tests should ever wait for a resource: the pool is never exhausted on purpose. */
    private static final Duration IMPATIENT = Duration.ofMillis(200);

    /** Bound for the deterministic "wait until a thread has parked" and "wait until clean" helpers. */
    private static final Duration GENEROUS = Duration.ofSeconds(5);

    /** A pool of one resource, so "the old physical resource" is one unambiguous object. */
    private static final int SINGLE = 1;

    private static RepositoryProfile profile(String id) {
        return TestSupport.profile(id);
    }

    /**
     * A real pool of real fakes, eagerly opened exactly as a production adapter would do at repository
     * activation, so the shutdown under test has a physical resource to release.
     */
    private static BoundedPool<FakeResource> initializedPool(FakePoolFactory factory, String name)
            throws Exception {
        BoundedPool<FakeResource> pool = new BoundedPool<>(name, SINGLE, IMPATIENT, factory);
        pool.initialize();
        return pool;
    }


    /**
     * A {@link Lease} holder that can be drained without hiding the test's own intent, because
     * {@code Lease.close()} is idempotent while the POOL is not: closing a lease twice would double-count
     * a usage, so the guard is here rather than in every test.
     */
    private static void returnLease(Lease<FakeResource> lease) {
        if (!lease.isClosed()) {
            lease.close();
        }
    }

    /** Polls a predicate with a hard bound; used only where a pool has no latch to wait on. */
    private static boolean waitFor(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(2L);
        }
        return condition.getAsBoolean();
    }

    /** Records the CREATE/CLOSE/FACTORY order that a switch must respect. Thread-safe by necessity. */
    private static final class Events {

        private final List<String> events = Collections.synchronizedList(new ArrayList<>());

        void add(String event) {
            events.add(event);
        }

        List<String> snapshot() {
            return List.copyOf(events);
        }

        /** Position of the first event that starts with {@code prefix}, or -1. */
        int indexOfPrefix(String prefix) {
            List<String> copy = snapshot();
            for (int i = 0; i < copy.size(); i++) {
                if (copy.get(i).startsWith(prefix)) {
                    return i;
                }
            }
            return -1;
        }

        /** The last event that starts with {@code prefix}, or {@code null}. */
        String lastWithPrefix(String prefix) {
            List<String> copy = snapshot();
            for (int i = copy.size() - 1; i >= 0; i--) {
                if (copy.get(i).startsWith(prefix)) {
                    return copy.get(i);
                }
            }
            return null;
        }
    }

    /**
     * Records, AT THE MOMENT the repository factory is invoked, how many of the previous repository's
     * physical resources were still alive.
     *
     * <p>This is the ordering evidence Goal 01C section E demands, and it is deliberately captured from
     * inside the factory rather than reconstructed afterwards: a count read after the test finished
     * cannot tell "the factory ran after the old resource was gone" from "the factory ran while it was
     * alive and the numbers happen to look fine now". A {@link FakeResource} reports its close to the
     * {@link FakePoolFactory} it was created from - not to a wrapper - so sampling that owner's live count
     * here is the honest observation point.
     */
    private static final class RepositoryFactory implements RepositoryContextFactory {

        /** Supplies the live-resource count of the repository being torn down, sampled right now. */
        private final java.util.function.Supplier<String> liveSampler;
        private final Events events;
        private final java.util.function.BiFunction<String, RepositoryProfile, RepositoryContext> builder;
        private final List<String> liveAtFactoryCall = Collections.synchronizedList(new ArrayList<>());

        RepositoryFactory(java.util.function.Supplier<String> liveSampler,
                          Events events,
                          java.util.function.BiFunction<String, RepositoryProfile, RepositoryContext> builder) {
            this.liveSampler = liveSampler;
            this.events = events;
            this.builder = builder;
        }

        @Override
        public RepositoryContext create(RepositoryProfile profile) {
            String sample = liveSampler == null ? "n/a" : liveSampler.get();
            liveAtFactoryCall.add(profile.id() + "=" + sample);
            events.add("factory:" + profile.id());
            return builder.apply(profile.id(), profile);
        }

        /**
         * The live-resource snapshot taken when the factory was invoked for {@code repositoryId}, or
         * {@code null} when the factory was never called for it.
         */
        String liveSnapshotFor(String repositoryId) {
            for (String entry : List.copyOf(liveAtFactoryCall)) {
                if (entry.startsWith(repositoryId + "=")) {
                    return entry.substring(repositoryId.length() + 1);
                }
            }
            return null;
        }
    }

    /**
     * Goal 01C E1. The outstanding-lease scenario, end to end:
     *
     * <ol>
     *   <li>a real pool inside context A is activated through the manager;</li>
     *   <li>one lease is borrowed and deliberately KEPT;</li>
     *   <li>the switch to B is refused;</li>
     *   <li>B's factory was never called;</li>
     *   <li>the old fake is still physically live;</li>
     *   <li>both the pool and the context report a shutdown that is not terminal - and specifically NOT
     *       clean, which is what the defect published;</li>
     *   <li>the refusal is repeatable: a second and a third attempt are refused too, and still create
     *       nothing.</li>
     * </ol>
     *
     * <p>Fails against the pre-Goal-01C code at (3): {@code closedWithUncertainResources()} was false
     * because nothing was quarantined, so the switch SUCCEEDED and {@code factory:beta} appears in the
     * event log while {@code alpha} still owned a live resource.
     */
    public void anOutstandingLeaseRefusesTheSwitchAndEveryRetry() throws Exception {
        Events events = new Events();
        FakePoolFactory poolFactory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = initializedPool(poolFactory, "cm-sessions");
        Assert.assertEquals(SINGLE, poolFactory.liveCount(), "the pool eagerly opened its physical resource");
        Assert.assertEquals(CloseState.NOT_CLOSED, pool.closeState(), "an in-service pool has not begun to close");

        Lease<FakeResource> held = pool.borrow();
        Assert.assertEquals(CloseState.NOT_CLOSED, pool.closeState(),
                "holding a lease is normal use, not a shutdown state");
        Assert.assertEquals(1, pool.metrics().leased(), "the lease is accounted for");

        List<String> factoryCalls = Collections.synchronizedList(new ArrayList<>());
        RepositoryContext[] alpha = new RepositoryContext[1];
        RepositoryFactory repositoryFactory = new RepositoryFactory(
                () -> "alphaPhysicalLive=" + poolFactory.liveCount(),
                events,
                (id, requested) -> {
                    RepositoryContext context = id.equals("alpha")
                            ? new RepositoryContext(requested, List.of(pool))
                            : new RepositoryContext(requested);
                    if (id.equals("alpha")) {
                        alpha[0] = context;
                    }
                    return context;
                });
        RepositoryManager manager = new RepositoryManager(requested -> {
            factoryCalls.add(requested.id());
            return repositoryFactory.create(requested);
        });

        manager.switchTo(profile("alpha"));
        Assert.assertEquals(List.of("alpha"), List.copyOf(factoryCalls), "only A was created");
        Assert.assertEquals(RepositoryState.ACTIVE, manager.state(), "A is active");

        // (3) The switch that must be refused.
        RepositoryException refusal = Assert.assertThrows(RepositoryException.class,
                () -> manager.switchTo(profile("beta")),
                "a repository that still holds a live leased resource must refuse the switch");
        Assert.assertTrue(refusal.getMessage().contains("outstanding"),
                "the refusal names the outstanding physical resource: " + refusal.getMessage());

        // (4) and (7): counted, and counted again after two retries.
        Assert.assertEquals(List.of("alpha"), List.copyOf(factoryCalls),
                "the factory was never called for the refused switch");
        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(profile("beta")),
                "the retry must be refused as well, not only the first attempt");
        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(profile("beta")),
                "and so must every later attempt while the lease is out");
        Assert.assertEquals(List.of("alpha"), List.copyOf(factoryCalls),
                "no retry ever called the factory");

        // (5) The old resource is physically alive, and the pool says exactly that.
        Assert.assertEquals(1, poolFactory.liveCount(), "the leased fake resource is still physically live");
        Assert.assertTrue(poolFactory.closed().isEmpty(), "no resource of A was closed yet");
        Assert.assertTrue(pool.isClosed(), "the pool refused new borrows: close() was requested");
        Assert.assertEquals(CloseState.CLOSING, pool.closeState(),
                "the pool is shutting down with a resource still outstanding - not clean");
        Assert.assertFalse(pool.closedWithUncertainResources(),
                "an outstanding lease is pending, NOT uncertain: quarantine means something else");
        Assert.assertFalse(pool.closeState().isTerminalClean(),
                "a pool with an outstanding lease is not terminal-clean");

        // (6) The context derives the same answer instead of freezing a stale clean verdict.
        RepositoryContext alphaContext = alpha[0];
        Assert.assertEquals(CloseState.CLOSING, alphaContext.closeState(),
                "the context reports the pending shutdown of the pool it owns");
        Assert.assertTrue(alphaContext.isDraining(), "and it knows it is still draining");
        Assert.assertFalse(alphaContext.closedWithUncertainResources(),
                "pending is reported as pending, not as uncertainty - and certainly not as clean");
        Assert.assertEquals("", alphaContext.uncertainCloseDetail(),
                "a pending shutdown has no uncertainty to explain");

        // The manager keeps the handle on what it is still tearing down, which is what makes the retries
        // above possible.
        Assert.assertTrue(manager.closingContext().isPresent(),
                "the manager retains the context it could not finish closing");
        Assert.assertEquals("alpha", manager.closingContext().orElseThrow().profileUnchecked().id(),
                "the retained context is the previous repository");
        Assert.assertEquals(CloseState.CLOSING, manager.closingState().orElseThrow(),
                "and it is reported as still closing");
        Assert.assertEquals(RepositoryManager.Refusal.PENDING, manager.refusal().orElseThrow(),
                "the refusal is classified as recoverable, not as a permanent leak");
        Assert.assertFalse(manager.state() == RepositoryState.ACTIVE,
                "no repository is active while the switch is refused: " + manager.state());
        Assert.assertFalse(manager.state() == RepositoryState.NONE,
                "and the manager does not claim there is nothing to release: " + manager.state());
        Assert.assertTrue(manager.activeContext().isEmpty(), "nothing is published while the switch is refused");
        Assert.assertTrue(manager.activeRepositoryId() == null, "and no repository id is published");
        Assert.assertTrue(manager.activeProfile().isEmpty(), "and no profile is published");
        Assert.assertEquals("", manager.statusSnapshot().get("repositoryId"),
                "the diagnostics snapshot publishes no repository id");
        Assert.assertEquals("alpha", manager.statusSnapshot().get("closingRepositoryId"),
                "the diagnostics snapshot names the repository being drained");
        Assert.assertEquals("CLOSING", manager.statusSnapshot().get("closingState"),
                "and reports its close state");
        Assert.assertEquals("PENDING", manager.statusSnapshot().get("refusal"),
                "and the refusal classification");
        Assert.assertTrue(manager.diagnostics().stream()
                        .anyMatch(d -> d.contains("still shutting down")),
                "the diagnostics explain the pending shutdown: " + manager.diagnostics());

        // Ordering evidence: nothing of B was ever created while A was alive.
        Assert.assertEquals(null, events.lastWithPrefix("factory:beta"),
                "no factory event for beta exists at all: " + events.snapshot());
        Assert.assertEquals(null, events.lastWithPrefix("close:alpha"),
                "and nothing of A was closed while its lease was outstanding: " + events.snapshot());

        returnLease(held);
        manager.close();
    }

    /**
     * Goal 01C E2. The late CLEAN return: refused first, allowed only afterwards, and only once the old
     * physical resource is provably gone - proven by ORDER, not by final counts.
     *
     * <p>The event log is the evidence: A's resource closes before B's factory is invoked, and the switch
     * to B is refused right up to the moment A reaches terminal-clean.
     */
    public void aLateCleanReturnPermitsOnlyALaterSafeSwitch() throws Exception {
        Events events = new Events();
        FakePoolFactory poolFactory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = initializedPool(poolFactory, "cm-sessions");
        Lease<FakeResource> held = pool.borrow();

        List<String> factoryCalls = Collections.synchronizedList(new ArrayList<>());
        RepositoryContext[] alpha = new RepositoryContext[1];
        RepositoryFactory repositoryFactory = new RepositoryFactory(
                () -> "alphaPhysicalLive=" + poolFactory.liveCount(),
                events,
                (id, requested) -> {
                    RepositoryContext context = id.equals("alpha")
                            ? new RepositoryContext(requested, List.of(pool))
                            : new RepositoryContext(requested);
                    if (id.equals("alpha")) {
                        alpha[0] = context;
                    }
                    return context;
                });
        RepositoryManager manager = new RepositoryManager(requested -> {
            factoryCalls.add(requested.id());
            return repositoryFactory.create(requested);
        });

        manager.switchTo(profile("alpha"));
        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(profile("beta")),
                "still refused while the lease is outstanding");
        Assert.assertEquals(List.of("alpha"), List.copyOf(factoryCalls), "B was not created");

        FakeResource resource = poolFactory.created().get(0);
        returnLease(held);
        Assert.assertTrue(resource.isClosed(), "the late return closed the physical resource");
        Assert.assertEquals(0, poolFactory.liveCount(), "A's physical live count is now zero");

        // (3) A reaches terminal-clean with no further switch attempt needed.
        Assert.assertEquals(CloseState.CLOSED_CLEAN, pool.closeState(),
                "a late clean return takes the pool to terminal-clean");
        Assert.assertTrue(pool.closeState().isTerminalClean(), "which is terminal and clean");
        Assert.assertEquals(CloseState.CLOSED_CLEAN, alpha[0].closeState(),
                "and the context follows the pool it owns");
        Assert.assertFalse(alpha[0].closedWithUncertainResources(),
                "nothing about that shutdown is uncertain");

        // (4) Only NOW may a later explicit attempt proceed.
        manager.switchTo(profile("beta"));
        Assert.assertEquals(List.of("alpha", "beta"), List.copyOf(factoryCalls),
                "the later switch created B once A was proven released");
        Assert.assertEquals(RepositoryState.ACTIVE, manager.state(), "B is active");
        Assert.assertEquals("beta", manager.activeRepositoryId(), "and published");
        Assert.assertTrue(manager.closingContext().isEmpty(),
                "the manager dropped its handle on A only after it was terminal-clean");
        Assert.assertTrue(manager.refusal().isEmpty(), "a completed switch is not a refusal");

        // (5) Ordering evidence, sampled INSIDE the factory at the instant it ran rather than read from a
        // final count: when B was built, not one physical resource of A was still alive.
        Assert.assertEquals("alphaPhysicalLive=0", repositoryFactory.liveSnapshotFor("beta"),
                "B's factory observed zero live resources of A: " + events.snapshot());
        Assert.assertEquals("alphaPhysicalLive=1", repositoryFactory.liveSnapshotFor("alpha"),
                "and A's own factory observed its one resource while nothing was being torn down");
        Assert.assertEquals(0, poolFactory.liveCount(), "the old physical live count is zero");
        Assert.assertEquals(0, pool.metrics().capacityInUse(), "and every old capacity slot is returned");

        manager.close();
    }

    /** The same switch-ordering claim for the refused path: the factory is never reached at all. */
    private static void assertFactoryWasNeverCalled(Events events, String repositoryId) {
        for (String event : events.snapshot()) {
            Assert.assertFalse(event.equals("factory:" + repositoryId),
                    "the factory must never run for '" + repositoryId + "': " + events.snapshot());
        }
    }

    /**
     * Goal 01C E3. The late FAILED return: refused first, quarantined on the way back, and refused
     * FOREVER afterwards - the retry must not be able to launder a quarantine into a new context.
     */
    public void aLateFailedReturnStaysRefusedAndCreatesNothing() throws Exception {
        Events events = new Events();
        FakePoolFactory poolFactory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = initializedPool(poolFactory, "cm-sessions");
        Lease<FakeResource> held = pool.borrow();

        List<String> factoryCalls = Collections.synchronizedList(new ArrayList<>());
        RepositoryContext[] alpha = new RepositoryContext[1];
        RepositoryFactory repositoryFactory = new RepositoryFactory(
                () -> "alphaPhysicalLive=" + poolFactory.liveCount(),
                events,
                (id, requested) -> {
                    RepositoryContext context = id.equals("alpha")
                            ? new RepositoryContext(requested, List.of(pool))
                            : new RepositoryContext(requested);
                    if (id.equals("alpha")) {
                        alpha[0] = context;
                    }
                    return context;
                });
        RepositoryManager manager = new RepositoryManager(requested -> {
            factoryCalls.add(requested.id());
            return repositoryFactory.create(requested);
        });

        manager.switchTo(profile("alpha"));

        // (2) The first refusal happens while the lease is still out.
        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(profile("beta")),
                "the outstanding lease refuses the first switch");
        Assert.assertFalse(manager.state() == RepositoryState.ACTIVE,
                "no repository is active while the switch is refused: " + manager.state());

        // (3) Now the leased fake is configured to fail its close UNCERTAINLY, before it can prove
        // itself closed - the physical session may still exist.
        FakeResource poisoned = poolFactory.created().get(0);
        poisoned.failCloseUncertain(new IOException("simulated late CM session close failure"));

        // (4) and (5): the lease comes back, the close fails, the slot is quarantined.
        returnLease(held);
        Assert.assertEquals(CloseState.CLOSED_UNCERTAIN, pool.closeState(),
                "the failed close makes the pool terminal-uncertain");
        Assert.assertTrue(pool.closedWithUncertainResources(), "the pool reports the uncertainty");
        Assert.assertEquals(1, pool.metrics().quarantined(), "the slot is quarantined and stays consumed");
        Assert.assertEquals(1, poolFactory.liveCount(), "the resource that never proved itself gone is live");
        Assert.assertEquals(CloseState.CLOSED_UNCERTAIN, alpha[0].closeState(),
                "and the context derives terminal uncertainty from the pool");

        // (6), (7) and (8): every later switch is still refused, and B is still never created.
        RepositoryException second = Assert.assertThrows(RepositoryException.class,
                () -> manager.switchTo(profile("beta")),
                "a quarantined previous repository must still refuse the retry");
        Assert.assertTrue(second.getMessage().contains("unproven"),
                "the refusal explains that the shutdown is unproven: " + second.getMessage());
        RepositoryException third = Assert.assertThrows(RepositoryException.class,
                () -> manager.switchTo(profile("beta")),
                "and the refusal is permanent, not a one-off");
        Assert.assertTrue(third.getMessage().contains("alpha"),
                "the refusal names the previous repository: " + third.getMessage());
        Assert.assertEquals(List.of("alpha"), List.copyOf(factoryCalls),
                "B's factory invocation count is still zero after three attempts");
        assertFactoryWasNeverCalled(events, "beta");

        Assert.assertEquals(RepositoryManager.Refusal.UNCERTAIN, manager.refusal().orElseThrow(),
                "the refusal is classified as permanent");
        Assert.assertEquals(CloseState.CLOSED_UNCERTAIN, manager.closingState().orElseThrow(),
                "and the retained context is reported as terminal-uncertain");
        Assert.assertTrue(manager.activeContext().isEmpty(), "nothing was published");
        Assert.assertTrue(manager.lastFailure().orElseThrow().contains("unproven"),
                "lastFailure explains the terminal uncertainty: " + manager.lastFailure());
        Assert.assertTrue(manager.diagnostics().stream().anyMatch(d -> d.contains("quarantined")),
                "the diagnostics name the quarantine on the RETRY, not only on the first refusal: "
                        + manager.diagnostics());

        // An explicit deactivate must not launder it into NONE either.
        Assert.assertTrue(manager.deactivate(), "deactivate still reports that it dealt with the context");
        Assert.assertFalse(manager.state() == RepositoryState.NONE,
                "a quarantined repository is never reported as released");
        Assert.assertEquals(RepositoryManager.Refusal.UNCERTAIN, manager.refusal().orElseThrow(),
                "the permanent refusal classification survives deactivate");

        manager.close();
        Assert.assertEquals(RepositoryState.CLOSED, manager.state(),
                "shutdown stays best-effort even with a quarantined previous repository");
    }

    /**
     * Goal 01C E4a. An IN-FLIGHT CREATION: the pool reserved a capacity slot and a session is
     * materialising when context close begins.
     *
     * <p>Held open by the create gate, so the interleaving is exact rather than probabilistic. This is the
     * case where an adapter is halfway through opening a connection, which is precisely when "the pool is
     * closed" must not be read as "nothing is being opened".
     */
    public void anInFlightCreationRefusesTheSwitchUntilItIsRetired() throws Exception {
        FakePoolFactory poolFactory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("cm-sessions", SINGLE, IMPATIENT, poolFactory);

        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch openCreate = new CountDownLatch(1);
        poolFactory.gateCreates(createEntered, openCreate);

        List<String> factoryCalls = Collections.synchronizedList(new ArrayList<>());
        List<String> borrowFailures = Collections.synchronizedList(new ArrayList<>());
        RepositoryManager manager = new RepositoryManager(requested -> {
            factoryCalls.add(requested.id());
            return new RepositoryContext(requested, List.of(pool));
        });
        manager.switchTo(profile("alpha"));

        // A borrow reserves the slot and parks inside create(), with the resource not yet handed back.
        Thread borrower = new Thread(() -> {
            try {
                pool.borrow().close();
            } catch (Throwable failure) {
                borrowFailures.add(String.valueOf(failure));
            }
        }, "in-flight-creation");
        borrower.setDaemon(true);
        borrower.start();
        Assert.assertTrue(createEntered.await(GENEROUS.toSeconds(), TimeUnit.SECONDS),
                "the creation really parked inside the factory");
        Assert.assertEquals(1, pool.metrics().creating(), "the creation holds a reserved capacity slot");
        Assert.assertEquals(1, poolFactory.liveCount(), "and the physical resource already exists");

        // The switch closes the pool. close() must NOT wait for the creation, and must NOT report clean.
        RepositoryException refusal = Assert.assertThrows(RepositoryException.class,
                () -> manager.switchTo(profile("beta")),
                "an in-flight creation must refuse the switch");
        Assert.assertEquals(List.of("alpha"), List.copyOf(factoryCalls),
                "no context was created while a session was being opened");
        Assert.assertEquals(CloseState.CLOSING, pool.closeState(),
                "the pool reports a pending shutdown, not a clean one");
        Assert.assertFalse(pool.closeState().isTerminalClean(),
                "an in-flight creation is not terminal-clean");
        Assert.assertTrue(refusal.getMessage().contains("outstanding"),
                "the refusal names the outstanding resource: " + refusal.getMessage());
        Assert.assertEquals(RepositoryManager.Refusal.PENDING, manager.refusal().orElseThrow(),
                "and classifies it as recoverable");
        Assert.assertTrue(pool.closedWithUncertainResources() == false,
                "an in-flight creation is pending, not uncertain");
        Assert.assertEquals(CloseState.CLOSING, alphaContextState(manager),
                "the manager reports the retained previous context as still closing");

        // The creator finishes: the pool retires the resource it created instead of handing it out.
        openCreate.countDown();
        Assert.assertTrue(waitFor(() -> pool.closeState() == CloseState.CLOSED_CLEAN, GENEROUS),
                "the pool reaches terminal-clean once the creation is retired");
        Assert.assertEquals(1, borrowFailures.size(),
                "the borrow was refused because the pool closed under it: " + borrowFailures);
        Assert.assertEquals(0, poolFactory.liveCount(), "the just-created resource was closed, not leaked");
        Assert.assertEquals(0, pool.metrics().creating(), "no creation is left in flight");
        Assert.assertEquals(0, pool.metrics().capacityInUse(), "and no capacity slot is left consumed");

        manager.switchTo(profile("beta"));
        Assert.assertEquals(List.of("alpha", "beta"), List.copyOf(factoryCalls),
                "the retry proceeds only after the creation reached terminal-clean");
        manager.close();
    }

    /** The close state the manager reports for the context it is still tearing down. */
    private static CloseState alphaContextState(RepositoryManager manager) {
        return manager.closingState().orElse(CloseState.NOT_CLOSED);
    }

    /**
     * Goal 01C E4b. An IN-FLIGHT RETIREMENT, and the trap the goal names explicitly: a retry must not be
     * able to bypass a previous context that is still pending.
     *
     * <p>The lease is returned on a helper thread whose {@code close()} parks in the close gate, so the
     * pool is genuinely mid-retirement. The main thread then proves that the switch is refused while the
     * retirement is in flight, AND that the refusal is not a one-off: two further attempts are refused
     * while the retirement has not finished. Only after the gate is opened does a retry succeed - and the
     * old resource must be gone before the new context exists.
     */
    public void aRetryCannotBypassAStillPendingPreviousContext() throws Exception {
        Events events = new Events();
        FakePoolFactory poolFactory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = initializedPool(poolFactory, "cm-sessions");
        Lease<FakeResource> held = pool.borrow();

        List<String> factoryCalls = Collections.synchronizedList(new ArrayList<>());
        RepositoryFactory repositoryFactory = new RepositoryFactory(
                () -> "alphaPhysicalLive=" + poolFactory.liveCount(),
                events,
                (id, requested) -> id.equals("alpha")
                        ? new RepositoryContext(requested, List.of(pool))
                        : new RepositoryContext(requested));
        RepositoryManager manager = new RepositoryManager(requested -> {
            factoryCalls.add(requested.id());
            return repositoryFactory.create(requested);
        });
        manager.switchTo(profile("alpha"));
        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(profile("beta")),
                "the held lease refuses the switch and starts the previous close");

        // Park the returning thread inside the resource's close(), so the retirement is genuinely open.
        CountDownLatch openClose = new CountDownLatch(1);
        poolFactory.gateCloses(openClose);
        Thread returner = new Thread(() -> returnLease(held), "late-lease-return");
        returner.setDaemon(true);
        returner.start();
        Assert.assertTrue(waitFor(() -> pool.metrics().retiring() == 1, GENEROUS),
                "the returning thread really parked inside close()");

        Assert.assertEquals(CloseState.CLOSING, pool.closeState(),
                "the pool reports an in-flight retirement, not a clean shutdown");

        // The trap: a retry must re-check the retained previous context, not start from scratch.
        RepositoryException refusal = Assert.assertThrows(RepositoryException.class,
                () -> manager.switchTo(profile("beta")),
                "a retry must not bypass the previous context that is still pending");
        Assert.assertTrue(refusal.getMessage().contains("outstanding"),
                "the refusal explains what is outstanding: " + refusal.getMessage());
        Assert.assertThrows(RepositoryException.class, () -> manager.switchTo(profile("beta")),
                "and a second retry is refused too");
        Assert.assertEquals(List.of("alpha"), List.copyOf(factoryCalls),
                "three attempts created nothing while the previous context was pending");
        Assert.assertEquals(1, poolFactory.liveCount(), "the old physical resource is still alive");
        Assert.assertEquals(RepositoryManager.Refusal.PENDING, manager.refusal().orElseThrow(),
                "the refusal stays recoverable while the retirement is in flight");
        assertFactoryWasNeverCalled(events, "beta");

        // Let the retirement finish, then a later attempt must succeed.
        openClose.countDown();
        Assert.assertTrue(waitFor(() -> pool.closeState() == CloseState.CLOSED_CLEAN, GENEROUS),
                "the pool reaches terminal-clean once the retirement finished");
        returner.join(TimeUnit.SECONDS.toMillis(5));
        Assert.assertFalse(returner.isAlive(), "the returning thread finished within the bound");
        Assert.assertEquals(0, poolFactory.liveCount(), "the old resource is gone");

        manager.switchTo(profile("beta"));
        Assert.assertEquals(List.of("alpha", "beta"), List.copyOf(factoryCalls),
                "the switch proceeds once the previous repository is terminal-clean");
        Assert.assertEquals(0, poolFactory.liveCount(), "the old resource is still gone after the switch");
        Assert.assertEquals(0, pool.metrics().capacityInUse(), "and every old slot is returned");
        Assert.assertEquals("alphaPhysicalLive=0", repositoryFactory.liveSnapshotFor("beta"),
                "B's factory observed zero live resources of A: " + events.snapshot());

        Assert.assertEquals(1, poolFactory.closed().size(),
                "A's resource was closed exactly once: " + poolFactory.closed());

        manager.close();
    }

    /**
     * Goal 01C E5. The counter-regression: an ordinary context with no outstanding resources still closes
     * and switches normally, and is never misreported as pending or uncertain.
     *
     * <p>Without this, an implementation that refuses every switch after the first - or that calls every
     * clean pool shutdown "draining" - would pass all the tests above.
     */
    public void anOrdinaryContextStillClosesAndSwitchesWithoutPendingStates() throws Exception {
        FakePoolFactory poolFactory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = initializedPool(poolFactory, "cm-sessions");

        List<String> factoryCalls = Collections.synchronizedList(new ArrayList<>());
        RepositoryContext[] alpha = new RepositoryContext[1];
        RepositoryManager manager = new RepositoryManager(requested -> {
            factoryCalls.add(requested.id());
            RepositoryContext context = requested.id().equals("alpha")
                    ? new RepositoryContext(requested, List.of(pool))
                    : new RepositoryContext(requested);
            if (requested.id().equals("alpha")) {
                alpha[0] = context;
            }
            return context;
        });

        manager.switchTo(profile("alpha"));
        Assert.assertEquals(CloseState.NOT_CLOSED, alpha[0].closeState(), "an active context is not closing");

        // No lease is outstanding, so the close is complete by the time close() returns.
        manager.switchTo(profile("beta"));
        Assert.assertEquals(List.of("alpha", "beta"), List.copyOf(factoryCalls),
                "a context with nothing outstanding does not refuse the switch");
        Assert.assertEquals(RepositoryState.ACTIVE, manager.state(), "the switch completed");
        Assert.assertEquals("beta", manager.activeRepositoryId(), "B is active");
        Assert.assertTrue(manager.closingContext().isEmpty(),
                "nothing is retained for a fully released repository");
        Assert.assertTrue(manager.refusal().isEmpty(), "and nothing was refused");
        Assert.assertTrue(manager.lastFailure().isEmpty(), "and nothing failed");

        Assert.assertEquals(CloseState.CLOSED_CLEAN, pool.closeState(), "the pool closed cleanly");
        Assert.assertEquals(CloseState.CLOSED_CLEAN, alpha[0].closeState(), "and so did the context");
        Assert.assertFalse(pool.closedWithUncertainResources(), "no uncertainty was invented");
        Assert.assertFalse(alpha[0].closedWithUncertainResources(), "neither in the context");
        Assert.assertEquals(0, poolFactory.liveCount(), "no physical resource is left alive");
        Assert.assertEquals(0, pool.metrics().capacityInUse(), "and every slot is returned");
        Assert.assertEquals(0, pool.metrics().quarantined(), "nothing was quarantined");

        // Leaving the context open is the last state: NOT_CLOSED, never pending, never uncertain.
        RepositoryContext beta = manager.activeContext().orElseThrow();
        Assert.assertEquals(CloseState.NOT_CLOSED, beta.closeState(),
                "a context in service is NOT_CLOSED, not CLOSING");
        Assert.assertFalse(beta.isDraining(), "and it is not draining");

        manager.close();
        Assert.assertEquals(CloseState.CLOSED_CLEAN, beta.closeState(), "close() still ends terminal-clean");
    }

    /**
     * Goal 01C A and B at the pool level, without a manager: the three states are distinguishable and the
     * terminal one is never reported while a resource is outstanding.
     *
     * <p>This is the vocabulary test. {@code CLOSING} must not be confused with {@code CLOSED_UNCERTAIN}:
     * a pool waiting for a lease is recoverable, a quarantined pool is not, and an implementation that
     * collapses them would either refuse forever after an ordinary lease or call a live connection clean.
     */
    public void thePoolDistinguishesPendingFromUncertainAndFromClean() throws Exception {
        FakePoolFactory poolFactory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = initializedPool(poolFactory, "cm-sessions");
        Lease<FakeResource> held = pool.borrow();

        Assert.assertEquals(CloseState.NOT_CLOSED, pool.closeState(), "in service");
        Assert.assertFalse(CloseState.NOT_CLOSED.isTerminal(),
                "a pool in service has no shutdown outcome yet");
        Assert.assertFalse(CloseState.NOT_CLOSED.isTerminalClean(),
                "and it is certainly not a proven clean shutdown");

        pool.close();
        Assert.assertEquals(CloseState.CLOSING, pool.closeState(), "pending while the lease is out");
        Assert.assertFalse(pool.closeState().isTerminal(), "pending is not terminal");
        Assert.assertTrue(pool.closeState().refusesReuse(), "pending must refuse reuse");
        Assert.assertFalse(pool.closedWithUncertainResources(), "pending is not uncertainty");
        Assert.assertEquals("", pool.uncertainCloseDetail(), "and it has nothing uncertain to explain");
        Assert.assertTrue(pool.isClosed(), "close() was requested");

        returnLease(held);
        Assert.assertEquals(CloseState.CLOSED_CLEAN, pool.closeState(), "clean once the lease came back");
        Assert.assertTrue(pool.closeState().isTerminalClean(), "terminal and clean");
        Assert.assertFalse(pool.closeState().refusesReuse(), "and only this state permits reuse");

        // A second pool proves the uncertain branch of the same vocabulary.
        FakePoolFactory poisonFactory = new FakePoolFactory();
        BoundedPool<FakeResource> poisoned = initializedPool(poisonFactory, "cm-sessions-2");
        poisonFactory.created().get(0).failCloseUncertain(new IOException("simulated close failure"));
        poisoned.close();

        Assert.assertEquals(CloseState.CLOSED_UNCERTAIN, poisoned.closeState(), "terminal uncertainty");
        Assert.assertTrue(poisoned.closeState().isTerminal(), "uncertainty is terminal, unlike pending");
        Assert.assertFalse(poisoned.closeState().isTerminalClean(), "and it is never clean");
        Assert.assertFalse(pool.closeState() == poisoned.closeState(),
                "a drained pool and a quarantined pool are DIFFERENT states");
        Assert.assertTrue(poisoned.closedWithUncertainResources(), "and the uncertainty contract agrees");

        Assert.assertEquals(1, poolFactory.liveCount() + poisonFactory.liveCount(),
                "only the unproven resource is still physically live");
    }
}