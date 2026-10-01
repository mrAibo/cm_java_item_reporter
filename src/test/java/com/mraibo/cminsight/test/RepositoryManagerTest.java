package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryException;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.repository.RepositoryState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** The repository switch lifecycle: ordering, fail-closed activation, concurrency and shutdown. */
public class RepositoryManagerTest {

    private static AutoCloseable closeable(String label, List<String> events) {
        return () -> events.add(label);
    }

    private static long openContexts(List<RepositoryContext> built) {
        synchronized (built) {
            return built.stream().filter(context -> !context.isClosed()).count();
        }
    }

    private static RepositoryProfile profile(String id) {
        return TestSupport.profile(id);
    }

    public void switchPublishesAnActiveContext() throws Exception {
        List<RepositoryContext> built = Collections.synchronizedList(new ArrayList<>());
        RepositoryManager manager = new RepositoryManager(profile -> {
            RepositoryContext context = new RepositoryContext(profile);
            built.add(context);
            return context;
        });

        Assert.assertEquals(RepositoryState.NONE, manager.state(), "a fresh manager has no repository");
        Assert.assertTrue(manager.activeContext().isEmpty(), "nothing is published yet");
        Assert.assertTrue(manager.activeProfile().isEmpty(), "no profile is published yet");
        Assert.assertTrue(manager.activeRepositoryId() == null, "no repository id yet");
        Assert.assertTrue(manager.lastFailure().isEmpty(), "no failure yet");
        Assert.assertTrue(RepositoryState.NONE.isInactive(), "NONE is an inactive state");
        Assert.assertFalse(RepositoryState.ACTIVE.isInactive(), "ACTIVE is not an inactive state");

        manager.switchTo(profile("crm"));
        Assert.assertEquals(RepositoryState.ACTIVE, manager.state(), "a successful switch activates");
        Assert.assertEquals("crm", manager.activeRepositoryId(), "the active repository id is published");
        Assert.assertEquals("crm", manager.activeProfile().orElseThrow().id(), "the profile is published");
        Assert.assertTrue(manager.activeContext().isPresent(), "the context is published");
        Assert.assertEquals(1L, openContexts(built), "exactly one context is open");
        Assert.assertTrue(manager.lastFailure().isEmpty(), "a success leaves no last failure");
        Assert.assertEquals("ACTIVE", manager.statusSnapshot().get("state"), "the snapshot reports the state");
        Assert.assertEquals("active", manager.statusSnapshot().get("stateDescription"), "the snapshot describes it");
        Assert.assertEquals("crm", manager.statusSnapshot().get("repositoryId"), "the snapshot names the repository");
        Assert.assertEquals("", manager.statusSnapshot().get("lastFailure"), "the snapshot has no failure text");
        Assert.assertTrue(manager.diagnostics().stream().anyMatch(d -> d.contains("Activated repository 'crm'")),
                "activation is recorded: " + manager.diagnostics());

        manager.close();
        Assert.assertEquals(RepositoryState.CLOSED, manager.state(), "close() marks the manager closed");
        Assert.assertEquals(0L, openContexts(built), "close() closes the active context");
        Assert.assertTrue(manager.activeContext().isEmpty(), "nothing is active after close");
        Assert.assertTrue(manager.activeRepositoryId() == null, "no repository id after close");
    }

    public void switchClosesThePreviousContextBeforeCreatingTheNewOne() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<RepositoryContext> built = Collections.synchronizedList(new ArrayList<>());
        RepositoryManager manager = new RepositoryManager(profile -> {
            events.add("create:" + profile.id());
            RepositoryContext context = new RepositoryContext(profile,
                    List.of(closeable("close:" + profile.id(), events)));
            built.add(context);
            return context;
        });

        manager.switchTo(profile("alpha"));
        Assert.assertEquals(List.of("create:alpha"), List.copyOf(events), "the first context is created");
        Assert.assertEquals(1L, openContexts(built), "one context is open");

        manager.switchTo(profile("beta"));
        Assert.assertEquals(List.of("create:alpha", "close:alpha", "create:beta"), List.copyOf(events),
                "the previous context is closed before the new one is created");
        Assert.assertEquals(1L, openContexts(built), "still exactly one context is open");
        Assert.assertEquals("beta", manager.activeRepositoryId(), "the new repository is published");
        Assert.assertEquals(RepositoryState.ACTIVE, manager.state(), "the manager is active");

        manager.close();
        Assert.assertEquals(List.of("create:alpha", "close:alpha", "create:beta", "close:beta"),
                List.copyOf(events), "close() releases the remaining context");
        Assert.assertEquals(0L, openContexts(built), "nothing stays open");
    }

    public void factoryFailureLeavesTheFailedStateAndPublishesNothing() {
        RepositoryManager manager = new RepositoryManager(profile -> {
            throw new IOException("simulated activation failure for " + profile.id());
        });

        RepositoryException failure = Assert.assertThrows(RepositoryException.class,
                () -> manager.switchTo(profile("crm")), "a factory failure must surface as RepositoryException");
        Assert.assertTrue(failure.getMessage().contains("crm"),
                "the message names the repository: " + failure.getMessage());
        Assert.assertTrue(failure.getMessage().contains("simulated activation failure"),
                "the message keeps the cause: " + failure.getMessage());
        Assert.assertNotNull(failure.getCause(), "the cause is preserved");
        Assert.assertEquals(RepositoryState.FAILED, manager.state(), "the state is FAILED");
        Assert.assertTrue(manager.activeContext().isEmpty(), "nothing is published");
        Assert.assertTrue(manager.activeProfile().isEmpty(), "no profile is published");
        Assert.assertTrue(manager.lastFailure().isPresent(), "the failure is recorded");
        Assert.assertTrue(manager.lastFailure().get().contains("simulated activation failure"),
                "the recorded failure explains itself: " + manager.lastFailure());
        Assert.assertEquals("", manager.statusSnapshot().get("repositoryId"), "the snapshot names no repository");
        Assert.assertTrue(manager.state().isInactive(), "FAILED is an inactive state");
        Assert.assertTrue(manager.lastFailure().isPresent()
                        && manager.statusSnapshot().get("lastFailure").contains("simulated"),
                "the snapshot carries the failure: " + manager.statusSnapshot());
        manager.close();
    }

    public void aContextForAnotherRepositoryIsRejectedAndClosed() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        RepositoryContext wrong = new RepositoryContext(profile("other"), List.of(closeable("close:other", events)));
        RepositoryManager manager = new RepositoryManager(returned -> wrong);

        RepositoryException failure = Assert.assertThrows(RepositoryException.class,
                () -> manager.switchTo(profile("crm")), "a context for another repository must be rejected");
        Assert.assertTrue(failure.getMessage().contains("other"),
                "the message names the returned repository: " + failure.getMessage());
        Assert.assertEquals(List.of("close:other"), List.copyOf(events),
                "the rejected context is closed rather than leaked");
        Assert.assertTrue(wrong.isClosed(), "the mismatched context is closed");
        Assert.assertEquals(RepositoryState.FAILED, manager.state(), "the switch failed");
        Assert.assertTrue(manager.activeContext().isEmpty(), "nothing is published");
        manager.close();
    }

    public void anAlreadyClosedContextIsRejected() {
        RepositoryContext closed = new RepositoryContext(profile("crm"));
        closed.close();
        RepositoryManager manager = new RepositoryManager(returned -> closed);

        RepositoryException failure = Assert.assertThrows(RepositoryException.class,
                () -> manager.switchTo(profile("crm")), "an already closed context must be rejected");
        Assert.assertTrue(failure.getMessage().contains("already closed"),
                "the message explains why: " + failure.getMessage());
        Assert.assertEquals(RepositoryState.FAILED, manager.state(), "the switch failed");
        Assert.assertTrue(manager.activeContext().isEmpty(), "nothing is published");
        manager.close();
    }

    public void deactivateClosesTheContextAndReturnsToNone() throws Exception {
        List<RepositoryContext> built = Collections.synchronizedList(new ArrayList<>());
        RepositoryManager manager = new RepositoryManager(profile -> {
            RepositoryContext context = new RepositoryContext(profile);
            built.add(context);
            return context;
        });
        manager.switchTo(profile("crm"));
        RepositoryContext context = manager.activeContext().orElseThrow();

        Assert.assertTrue(manager.deactivate(), "deactivate reports that something was closed");
        Assert.assertEquals(RepositoryState.NONE, manager.state(), "the manager returns to NONE");
        Assert.assertTrue(context.isClosed(), "the context is closed");
        Assert.assertTrue(manager.activeContext().isEmpty(), "nothing is published");
        Assert.assertEquals(0L, openContexts(built), "no context stays open");
        Assert.assertFalse(manager.deactivate(), "a second deactivate reports that nothing was closed");
        Assert.assertEquals(RepositoryState.NONE, manager.state(), "the state stays NONE");
        Assert.assertTrue(manager.diagnostics().stream().anyMatch(d -> d.contains("Deactivated repository 'crm'")),
                "deactivation is recorded: " + manager.diagnostics());
        manager.close();
    }

    public void closeIsIdempotentAndMakesSwitchingImpossible() throws Exception {
        List<RepositoryContext> built = Collections.synchronizedList(new ArrayList<>());
        RepositoryManager manager = new RepositoryManager(profile -> {
            RepositoryContext context = new RepositoryContext(profile);
            built.add(context);
            return context;
        });
        manager.switchTo(profile("crm"));
        RepositoryContext context = manager.activeContext().orElseThrow();

        manager.close();
        Assert.assertEquals(RepositoryState.CLOSED, manager.state(), "the manager is closed");
        Assert.assertTrue(context.isClosed(), "the active context was closed");
        Assert.assertEquals(0L, openContexts(built), "nothing stays open");
        Assert.assertTrue(manager.activeRepositoryId() == null, "no repository is reported");

        int builtBefore = built.size();
        RepositoryException failure = Assert.assertThrows(RepositoryException.class,
                () -> manager.switchTo(profile("beta")), "a closed manager refuses to switch");
        Assert.assertTrue(failure.getMessage().contains("closed"), "the message explains it: " + failure.getMessage());
        Assert.assertEquals(builtBefore, built.size(), "no context was built for a refused switch");

        manager.close();
        Assert.assertEquals(RepositoryState.CLOSED, manager.state(), "close() is idempotent");
        Assert.assertEquals(0L, openContexts(built), "an idempotent close changes nothing");
        Assert.assertFalse(manager.deactivate(), "deactivate on a closed manager has nothing to do");
    }

    public void concurrentSwitchesNeverLeaveTwoContextsActive() throws Exception {
        List<RepositoryContext> built = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger overlappingCreations = new AtomicInteger();
        RepositoryManager manager = new RepositoryManager(profile -> {
            if (openContexts(built) != 0) {
                overlappingCreations.incrementAndGet();
            }
            RepositoryContext context = new RepositoryContext(profile);
            built.add(context);
            return context;
        });

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int slot = 0; slot < threads; slot++) {
            final int index = slot;
            Thread thread = new Thread(() -> {
                try {
                    start.await(20, TimeUnit.SECONDS);
                    manager.switchTo(profile("repo" + index));
                } catch (Throwable failure) {
                    failures.add(failure);
                } finally {
                    done.countDown();
                }
            }, "switch-" + slot);
            thread.setDaemon(true);
            thread.start();
        }

        start.countDown();
        Assert.assertTrue(done.await(20, TimeUnit.SECONDS), "every switch must finish");
        Assert.assertTrue(failures.isEmpty(), "no switch failed: " + failures);
        Assert.assertEquals(RepositoryState.ACTIVE, manager.state(), "one repository ends up active");
        Assert.assertTrue(manager.activeContext().isPresent(), "a context is published");
        Assert.assertEquals(1L, openContexts(built), "exactly one context is open at the end");
        Assert.assertEquals(threads, built.size(), "every switch built a context");
        Assert.assertEquals(0, overlappingCreations.get(),
                "no context was created while another one was still open");

        manager.close();
        Assert.assertEquals(0L, openContexts(built), "close() releases the last context");
    }

    public void contextClosesResourcesInReverseOrderAndCollectsFailures() throws Exception {
        List<String> order = new ArrayList<>();
        AutoCloseable first = () -> order.add("first");
        AutoCloseable failing = () -> {
            order.add("failing");
            throw new IOException("resource refused to close");
        };
        AutoCloseable last = () -> order.add("last");
        RepositoryProfile profile = profile("crm");
        RepositoryContext context = new RepositoryContext(profile, List.of(first, failing, last));

        Assert.assertEquals(3, context.resources().size(), "all three resources are owned");
        Assert.assertEquals(profile, context.profile(), "the profile is available while open");
        Assert.assertEquals("crm", context.profileUnchecked().id(), "the unchecked profile is available too");
        Assert.assertNotNull(context.createdAt(), "the creation time is recorded");
        Assert.assertFalse(context.isClosed(), "a fresh context is open");
        Assert.assertTrue(context.closeFailures().isEmpty(), "no failures before closing");

        context.close();
        Assert.assertEquals(List.of("last", "failing", "first"), order,
                "resources close in reverse acquisition order and a failure never stops the rest");
        Assert.assertTrue(context.isClosed(), "the context reports itself closed");
        Assert.assertEquals(1, context.closeFailures().size(), "one failure is collected");
        Assert.assertTrue(context.closeFailures().get(0).contains("resource refused to close"),
                "the failure keeps its message: " + context.closeFailures());
        Assert.assertThrows(IllegalStateException.class, context::profile, "profile() fails once closed");

        context.close();
        Assert.assertEquals(3, order.size(), "close() is idempotent and re-closes nothing");
        Assert.assertEquals(1, context.closeFailures().size(), "the failure list is unchanged");
    }

    /**
     * Goal 01A (B): an uncertain close of the previous context must fail the switch CLOSED - no new
     * context, nothing published, state FAILED, and a diagnostic that names the failures.
     *
     * <p>This test replaces the Goal 01 test {@code diagnosticsRecordResourceCloseFailuresOnASwitch},
     * which asserted the OLD semantics: the manager recorded the close failure and activated the new
     * repository anyway. Opening new connections while the previous ones may still hold a physical CM
     * session is exactly how the configured physical bound is exceeded, so section B turns that into a
     * refusal.
     *
     * <p>Fails against the pre-Goal-01A {@link RepositoryManager}, which published 'beta' as ACTIVE
     * after the failed close.
     */
    public void anUncertainCloseOfThePreviousContextFailsTheSwitchClosed() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<String> created = Collections.synchronizedList(new ArrayList<>());
        AutoCloseable failing = () -> {
            events.add("close-requested");
            throw new IOException("closing failed for a resource");
        };
        RepositoryManager manager = new RepositoryManager(profile -> {
            created.add(profile.id());
            return new RepositoryContext(profile, List.of(failing));
        });

        manager.switchTo(profile("crm"));
        Assert.assertEquals("crm", manager.activeRepositoryId(), "the first repository is active");
        Assert.assertEquals(List.of("crm"), List.copyOf(created), "only the first context was created");

        RepositoryException failure = Assert.assertThrows(RepositoryException.class,
                () -> manager.switchTo(profile("beta")),
                "an uncertain close of the previous context must refuse the switch");
        Assert.assertTrue(events.contains("close-requested"), "the previous context was still closed first");
        Assert.assertEquals(List.of("crm"), List.copyOf(created),
                "the factory was never called for the refused switch");
        Assert.assertEquals(RepositoryState.FAILED, manager.state(), "the manager reports FAILED");
        Assert.assertTrue(manager.activeContext().isEmpty(), "nothing is published");
        Assert.assertTrue(manager.activeRepositoryId() == null, "no repository id is published");
        Assert.assertFalse(manager.status().usable(), "there is no usable context after the refused switch");
        Assert.assertTrue(failure.getMessage().contains("closing the previous repository 'crm'"),
                "the failure names the previous repository: " + failure.getMessage());
        Assert.assertTrue(failure.getMessage().contains("closing failed for a resource"),
                "the failure names the resource failure: " + failure.getMessage());
        Assert.assertTrue(manager.diagnostics().stream()
                        .anyMatch(d -> d.contains("closing failed for a resource")),
                "the close failure is recorded: " + manager.diagnostics());
        Assert.assertTrue(manager.diagnostics().stream().anyMatch(d -> d.contains("resource failure")),
                "the number of failures is summarised: " + manager.diagnostics());
        Assert.assertTrue(manager.lastFailure().orElseThrow().contains("uncertain"),
                "lastFailure explains the refusal: " + manager.lastFailure());

        manager.close();
        Assert.assertEquals(RepositoryState.CLOSED, manager.state(),
                "shutdown still works after a refused switch");
    }

    /**
     * Goal 01A (B): {@code close()} stays best-effort - an uncertain shutdown close is recorded but
     * must not abort the shutdown or leave the manager in a non-CLOSED state.
     */
    public void closeStaysBestEffortWhenAContextCloseThrows() throws Exception {
        AutoCloseable fatal = () -> {
            throw new ResourceCloseError("fatal resource close");
        };
        RepositoryManager manager = new RepositoryManager(profile ->
                new RepositoryContext(profile, List.of(fatal)));
        manager.switchTo(profile("crm"));

        manager.close();
        Assert.assertEquals(RepositoryState.CLOSED, manager.state(), "close() still ends CLOSED");
        Assert.assertTrue(manager.activeContext().isEmpty(), "nothing stays published");
        Assert.assertTrue(manager.diagnostics().stream()
                        .anyMatch(d -> d.contains("Error closing repository context during shutdown")),
                "the uncertain shutdown close is recorded: " + manager.diagnostics());

        manager.close();
        Assert.assertEquals(RepositoryState.CLOSED, manager.state(), "close() stays idempotent");
    }

    /**
     * Goal 01A (B): {@code deactivate()} must not claim "nothing is active" after an uncertain close.
     */
    public void deactivateAfterAnUncertainCloseReportsFailedInsteadOfNone() throws Exception {
        AutoCloseable failing = () -> {
            throw new IOException("closing failed for a resource");
        };
        RepositoryManager manager = new RepositoryManager(profile ->
                new RepositoryContext(profile, List.of(failing)));
        manager.switchTo(profile("crm"));

        Assert.assertTrue(manager.deactivate(), "deactivate reports that it closed the context");
        Assert.assertEquals(RepositoryState.FAILED, manager.state(),
                "a resource that refused to close means the manager must not report NONE");
        Assert.assertTrue(manager.activeContext().isEmpty(), "nothing is published");
        Assert.assertTrue(manager.lastFailure().orElseThrow().contains("uncertain"),
                "lastFailure explains the uncertain close: " + manager.lastFailure());
        Assert.assertTrue(manager.diagnostics().stream().anyMatch(d -> d.contains("resource failure")),
                "the diagnostic names the failure count: " + manager.diagnostics());
        manager.close();
    }

    /**
     * Regression (t4 F2): a single atomic snapshot must never pair {@code ACTIVE} with no usable
     * context, and a context must never be published outside {@code ACTIVE}.
     *
     * <p>The sample is taken with ONE {@code status()} call. Calling {@code state()} and then
     * {@code activeContext()} is two different instants, so a switch completing in between yields a
     * pair that never existed and the test would fail against correct production code.
     *
     * <p>For the same reason the zero-tolerance check is the FROZEN pair only (ACTIVE holds a context,
     * and no other state holds one). {@code Status.usable()} additionally re-reads the context's
     * mutable {@code closed} flag at evaluation time, which is a second instant: a switch can close the
     * context after the atomic read, so a closed-but-correctly-published pair is not a defect. That
     * predicate is therefore asserted as a control instead (it must be observed true during the run
     * and must be true in the quiescent end state).
     *
     * <p>The test is provably not blind: it counts the ACTIVE-with-context samples and the non-ACTIVE
     * samples it really observed, and its detector is exercised on the impossible pairs themselves.
     */
    public void activeStateIsNeverObservedWithoutAPublishedContext() throws Exception {
        List<RepositoryContext> built = Collections.synchronizedList(new ArrayList<>());
        RepositoryManager manager = new RepositoryManager(profile -> {
            // A switch must take measurable time, otherwise the sampler cannot observe the phases.
            Thread.sleep(0L, 300_000);
            RepositoryContext context = new RepositoryContext(profile);
            built.add(context);
            return context;
        });

        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong samples = new AtomicLong();
        AtomicLong activeWithContext = new AtomicLong();
        AtomicLong usableNow = new AtomicLong();
        AtomicLong inactiveSamples = new AtomicLong();
        AtomicLong inconsistent = new AtomicLong();
        List<String> details = Collections.synchronizedList(new ArrayList<>());

        Thread sampler = new Thread(() -> {
            while (!stop.get()) {
                RepositoryManager.Status snapshot = manager.status();
                samples.incrementAndGet();
                String problem = inconsistentPair(snapshot);
                if (problem != null) {
                    inconsistent.incrementAndGet();
                    if (details.size() < 5) {
                        details.add(problem);
                    }
                } else if (snapshot.state() == RepositoryState.ACTIVE) {
                    activeWithContext.incrementAndGet();
                    if (!snapshot.context().isClosed()) {
                        usableNow.incrementAndGet();
                    }
                } else {
                    inactiveSamples.incrementAndGet();
                }
            }
        }, "repository-status-sampler");
        sampler.setDaemon(true);
        sampler.start();

        try {
            for (int index = 0; index < 400; index++) {
                manager.switchTo(profile("repo" + (index % 4)));
                if (index % 25 == 0) {
                    Thread.sleep(1L);
                }
            }
        } finally {
            stop.set(true);
            sampler.join(10_000L);
        }

        Assert.assertTrue(samples.get() > 0, "the sampler ran at all: " + samples.get());
        Assert.assertTrue(activeWithContext.get() > 0,
                "the sampler really observed ACTIVE with a context in one snapshot: " + activeWithContext.get());
        Assert.assertTrue(usableNow.get() > 0,
                "the sampler really observed a published context that was still open: " + usableNow.get());
        Assert.assertTrue(inactiveSamples.get() > 0,
                "the sampler really observed the non-ACTIVE phases too: " + inactiveSamples.get());
        Assert.assertEquals(0L, inconsistent.get(),
                "no snapshot paired ACTIVE with no context, or a context with a non-ACTIVE state: " + details);
        Assert.assertTrue(manager.status().usable(),
                "once the switches stop, the published context is usable: " + manager.status().state());

        // Positive control of the detector itself: it must flag the impossible pairs it looks for.
        Assert.assertNotNull(inconsistentPair(
                        new RepositoryManager.Status(RepositoryState.ACTIVE, null)),
                "the detector must flag ACTIVE with no published context");
        Assert.assertNotNull(inconsistentPair(
                        new RepositoryManager.Status(RepositoryState.SWITCHING, new RepositoryContext(profile("crm")))),
                "the detector must flag a context published outside ACTIVE");
        Assert.assertNull(inconsistentPair(
                        new RepositoryManager.Status(RepositoryState.ACTIVE, new RepositoryContext(profile("crm")))),
                "the detector must accept the healthy ACTIVE pair");
        Assert.assertNull(inconsistentPair(new RepositoryManager.Status(RepositoryState.NONE, null)),
                "the detector must accept the inactive pair");

        manager.close();
        Assert.assertFalse(sampler.isAlive(), "the sampler stopped");
        Assert.assertEquals(0L, built.stream().filter(context -> !context.isClosed()).count(),
                "close() released every context built during the sampled switches");
    }

    /**
     * Regression (t4 F5): diagnostics stay bounded, oldest-first, and keep the most recent lifecycle
     * note. Fails against the pre-fix code, where the buffer was an unbounded list and 600 switches
     * left 600 entries.
     */
    public void diagnosticsStayBoundedAndOldestFirst() throws Exception {
        int switches = 600;
        RepositoryManager manager = new RepositoryManager(profile -> new RepositoryContext(profile));
        for (int index = 0; index < switches; index++) {
            manager.switchTo(profile("repo" + index));
        }

        List<String> diagnostics = manager.diagnostics();
        Assert.assertTrue(diagnostics.size() <= 512,
                "diagnostics are capped at 512 entries: " + diagnostics.size());
        Assert.assertTrue(diagnostics.size() > 0, "diagnostics are not empty");
        Assert.assertTrue(diagnostics.get(diagnostics.size() - 1).contains("'repo599'"),
                "the most recent activation is still present: " + diagnostics.get(diagnostics.size() - 1));

        int previous = -1;
        for (String diagnostic : diagnostics) {
            int index = repoIndex(diagnostic);
            Assert.assertTrue(index > previous, "diagnostics stay oldest-first: " + diagnostic);
            previous = index;
        }
        Assert.assertTrue(repoIndex(diagnostics.get(0)) > 0,
                "the oldest entries were dropped once the cap was reached: " + diagnostics.get(0));
        manager.close();
    }

    /**
     * Regression (t4 F7): {@link RepositoryState#isInactive()} is true for every state except ACTIVE,
     * and agrees with what the manager publishes in each state it can be driven to.
     *
     * <p>Fails against the pre-fix code, which returned false for INITIALIZING, SWITCHING and CLOSING.
     */
    public void isInactiveIsTrueForEveryStateExceptActive() throws Exception {
        for (RepositoryState state : RepositoryState.values()) {
            Assert.assertEquals(state != RepositoryState.ACTIVE, state.isInactive(),
                    state.name() + " must be inactive exactly when it is not ACTIVE");
        }
        Assert.assertEquals(7, RepositoryState.values().length, "the state set is closed");
        Assert.assertEquals("active", RepositoryState.ACTIVE.description(), "the description is stable");

        RepositoryManager manager = new RepositoryManager(profile -> new RepositoryContext(profile));
        Assert.assertEquals(RepositoryState.NONE, manager.state(), "a fresh manager is NONE");
        Assert.assertTrue(manager.state().isInactive(), "NONE is inactive");
        Assert.assertTrue(manager.activeContext().isEmpty(), "NONE publishes nothing");

        manager.switchTo(profile("crm"));
        Assert.assertEquals(RepositoryState.ACTIVE, manager.state(), "an activation is ACTIVE");
        Assert.assertFalse(manager.state().isInactive(), "ACTIVE is not inactive");
        Assert.assertTrue(manager.activeContext().isPresent(), "ACTIVE publishes a context");

        Assert.assertTrue(manager.deactivate(), "deactivate closes the context");
        Assert.assertEquals(RepositoryState.NONE, manager.state(), "deactivation returns to NONE");
        Assert.assertTrue(manager.state().isInactive(), "NONE is inactive again");
        Assert.assertTrue(manager.activeContext().isEmpty(), "NONE publishes nothing again");

        RepositoryManager failing = new RepositoryManager(profile -> {
            throw new IOException("no repository here");
        });
        Assert.assertThrows(RepositoryException.class, () -> failing.switchTo(profile("crm")),
                "the activation fails");
        Assert.assertEquals(RepositoryState.FAILED, failing.state(), "a failed activation is FAILED");
        Assert.assertTrue(failing.state().isInactive(), "FAILED is inactive");
        Assert.assertTrue(failing.activeContext().isEmpty(), "FAILED publishes nothing");
        failing.close();

        manager.close();
        Assert.assertEquals(RepositoryState.CLOSED, manager.state(), "close() ends in CLOSED");
        Assert.assertTrue(manager.state().isInactive(), "CLOSED is inactive");
        Assert.assertTrue(manager.activeContext().isEmpty(), "CLOSED publishes nothing");
    }

    /**
     * Regression (t4 F3): an {@link Error} from one resource must not stop the remaining resources from
     * being released, must be recorded, and must still surface to the caller.
     *
     * <p>Fails against the pre-fix code, which caught only {@code Exception}: the Error aborted the
     * loop (so "first" stayed open) and {@code closeFailures()} stayed empty.
     */
    public void contextCloseRecordsAnErrorAndStillReleasesEveryOtherResource() {
        List<String> order = new ArrayList<>();
        AutoCloseable first = () -> order.add("first");
        AutoCloseable fatal = () -> {
            order.add("fatal");
            throw new ResourceCloseError("resource close failed");
        };
        AutoCloseable last = () -> order.add("last");
        RepositoryContext context = new RepositoryContext(profile("crm"), List.of(first, fatal, last));

        ResourceCloseError thrown = Assert.assertThrows(ResourceCloseError.class, context::close,
                "the Error still surfaces to the caller of close()");
        Assert.assertTrue(thrown.getMessage().contains("resource close failed"), "the same Error is rethrown");
        Assert.assertEquals(List.of("last", "fatal", "first"), order,
                "reverse order, and the Error did not stop the remaining resource from closing");
        Assert.assertTrue(context.isClosed(), "the context is still marked closed");
        Assert.assertEquals(1, context.closeFailures().size(), "the Error is recorded as a close failure");
        Assert.assertTrue(context.closeFailures().get(0).contains("ResourceCloseError"),
                "the failure names the Error type: " + context.closeFailures());
    }

    /**
     * The single definition of an impossible (state, context) pair inside ONE atomic snapshot, or
     * {@code null} when the pair is fine. Deliberately a pure function of the snapshot: anything that
     * re-reads a live field of the context would measure a second instant.
     */
    private static String inconsistentPair(RepositoryManager.Status snapshot) {
        boolean holdsContext = snapshot.context() != null;
        if (snapshot.state() == RepositoryState.ACTIVE && !holdsContext) {
            return "ACTIVE with no context in the same snapshot";
        }
        if (snapshot.state() != RepositoryState.ACTIVE && holdsContext) {
            return "a context was published while the state was " + snapshot.state();
        }
        return null;
    }

    private static int repoIndex(String diagnostic) {
        int start = diagnostic.indexOf("'repo");
        if (start < 0) {
            throw new AssertionError("diagnostic does not name a repository: " + diagnostic);
        }
        int end = diagnostic.indexOf('\'', start + 1);
        return Integer.parseInt(diagnostic.substring(start + 5, end));
    }
}
