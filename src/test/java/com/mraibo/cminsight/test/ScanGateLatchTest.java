package com.mraibo.cminsight.test;

import com.mraibo.cminsight.core.CloseState;
import com.mraibo.cminsight.core.StatisticsSettings;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.statistics.ItemTypeAggregate;
import com.mraibo.cminsight.statistics.MetricValue;
import com.mraibo.cminsight.statistics.ScanCancellation;
import com.mraibo.cminsight.statistics.ScanCoordinator;
import com.mraibo.cminsight.statistics.ScanStartResult;
import com.mraibo.cminsight.statistics.ScanStatus;
import com.mraibo.cminsight.statistics.ScanWindows;
import com.mraibo.cminsight.statistics.StatisticsEngine;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Goal 03A section A: the one-scan gate must never be released while a worker of the previous scan is still
 * alive.
 *
 * <h2>The hostile worker, and why it must be hostile in three directions at once</h2>
 *
 * <p>The defect was that a worker still alive after the drain grace lost both the gate and its thread
 * reference: {@code finishScan()} cleared {@code scanInFlight}, the active scan and the tracked thread list
 * unconditionally, so a second refresh was accepted while the old worker was still holding a JDBC lease. A
 * worker that responds to ANY of the three levers the coordinator has does not reproduce it - the old drain
 * grace would have let it exit, the gate would have been released legitimately, and the test would prove
 * nothing. So {@link HostileEngine} deliberately ignores all three, and each one is MEASURED rather than
 * assumed:
 *
 * <ol>
 *   <li><b>scan cancellation</b> - it never reads {@link ScanCancellation#isCancelled()};</li>
 *   <li><b>thread interruption</b> - it counts every {@link InterruptedException} its wait loop swallows and
 *       keeps waiting, so the interrupt is demonstrably delivered (the counter moves) and demonstrably
 *       ignored (the worker is still parked);</li>
 *   <li><b>the Statement-style abort callback</b> - it registers one with
 *       {@link ScanCancellation#register(Runnable)} so the registration is genuinely delivered, and the
 *       action only counts. That is the production shape ({@code Statement.cancel()} reached through the
 *       scan's signal) with a driver that ignores the cancel.</li>
 * </ol>
 *
 * <p>The only thing that lets a parked query return is the test's own release latch - a latch, never a
 * sleep, so the interleaving is exact and the wall clock never decides the outcome.
 *
 * <h2>Where the terminal phase is asserted, and why not earlier</h2>
 *
 * <p>The coordinator may hold its bounded drain grace MORE THAN ONCE for the same worker before its
 * supervisor resolves the phase, so waiting for {@code TIMED_OUT} while a hostile worker is parked can cost
 * two drain graces. That is a property of the implementation, not of the invariant, so this suite does not
 * make its early assertions depend on it: the DEADLINE having fired is measured directly - the abort callback
 * it runs and the interrupt it injects - the gate, the thread tracking, the refusal and the close state are
 * asserted while the worker is still alive, and the terminal phase is asserted once the scan has really
 * drained, where it is both observable and unambiguous. The three-way "result final, thread alive, gate
 * latched" fact is asserted in its cheap form in
 * {@link #theWorkerThatOutlivesItsSupervisorReleasesTheGateItselfAndOnlyThenANewScanProceeds()}, where an
 * explicit cancellation makes the supervisor finish promptly, and with {@code TIMED_OUT} in
 * {@code AnchorDeadlineCancellationTest}, where the deadline reaches the anchor before any worker exists.
 *
 * <h2>The mutation control is not optional</h2>
 *
 * <p>{@link #thePreFixReleaseRuleWouldBeDetectedByTheseAssertions()} reproduces the PRE-FIX rule as
 * executable code - including the discarded second join and the unconditional release - and then runs the
 * EXACT assertions of this suite against it, requiring each one to fail. That is what makes "the fix works"
 * evidence instead of a description of the cases that happened to be written.
 *
 * <h2>Latches, never sleeps</h2>
 *
 * <p>Every wait here is a {@link CountDownLatch} or a condition poll with an explicit bounded deadline used
 * only as a FAILURE deadline. Two waits cannot be expressed as a latch and are stated as such: observing that
 * the old drain grace has elapsed (the assertion is ABOUT that elapsed time), and observing that a thread
 * which just released the gate has finished terminating (the releasing thread is still executing its own
 * {@code finally} at the instant the gate opens).
 */
public class ScanGateLatchTest {

    /** The repository id used throughout, so thread names are predictable. */
    private static final String REPOSITORY = "gate-latch";

    /** A generous failure deadline: it can only ever turn a hang into a failure. */
    private static final Duration WAIT = Duration.ofSeconds(20);

    /**
     * The PRE-FIX drain grace, reproduced as a value because the assertions are ABOUT it: the worker must
     * still be alive after this much wall clock. The production constant is private, and it is the bound the
     * defect released at, not a number this suite may read from the code under test.
     */
    private static final Duration OLD_DRAIN_GRACE = Duration.ofSeconds(10);

    /** The wall-clock window the test must observe while the worker is still parked. */
    private static final Duration ALIVE_PAST_THE_OLD_GRACE = OLD_DRAIN_GRACE.plusSeconds(1);

    /** The bound the hostile engine may stay parked before it gives up, so no test can hang the build. */
    private static final Duration ENGINE_FAILURE_DEADLINE = Duration.ofSeconds(25);

    /** The short overall deadline under test: it must fire long before anything else could. */
    private static final Duration SCAN_DEADLINE = Duration.ofSeconds(1);

    // ------------------------------------------------------------------ fixtures

    private static ItemTypeSummary itemType(String name) {
        return new ItemTypeSummary(name, "", name.hashCode() & 0xffff, "SAP", "SAP", "");
    }

    private static List<ItemTypeSummary> itemTypes(int count) {
        List<ItemTypeSummary> list = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            list.add(itemType("ItemType" + index));
        }
        return List.copyOf(list);
    }

    /** The aggregate one measured ItemType answers with; named apart from the engine method it serves. */
    private static ItemTypeAggregate measured(long logicalItems) {
        return new ItemTypeAggregate(logicalItems, MetricValue.available(0L), MetricValue.available(0L),
                MetricValue.available(0L), MetricValue.available(0L));
    }

    private static StatisticsSettings settings(int workers, Duration scanTimeout) {
        return new StatisticsSettings(true, workers, Duration.ofSeconds(5), scanTimeout);
    }

    /** Every live thread whose name carries the coordinator's prefix. */
    private static Set<String> scanThreads() {
        Set<String> names = new TreeSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(ScanCoordinator.THREAD_NAME_PREFIX)) {
                names.add(thread.getName());
            }
        }
        return names;
    }

    /** Every live thread of one named kind, selected by the documented thread-name suffix. */
    private static Set<String> threadsNamed(String suffixFragment) {
        Set<String> names = new TreeSet<>();
        for (String name : scanThreads()) {
            if (name.contains(suffixFragment)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Polls a condition until it holds, with an explicit bound that is only ever a FAILURE deadline.
     *
     * <p>Used where no latch can express the fact being observed. It never sequences anything: every
     * interleaving in this suite is decided by a latch.
     */
    private static boolean awaitCondition(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
        return true;
    }

    /**
     * Waits until at least {@code minimum} has elapsed since {@code since}.
     *
     * <p>This is the one wait that is genuinely ABOUT elapsed wall-clock time - the assertion is "the worker
     * is still alive more than an old-drain-grace later" - so no latch can express it and a sleep would be a
     * sequencing device. It is a bounded condition poll on the clock: it fails the test rather than hanging it
     * if the time cannot be observed.
     */
    private static boolean awaitElapsed(Instant since, Duration minimum, Duration failureDeadline) {
        long deadline = System.nanoTime() + failureDeadline.toNanos();
        while (Duration.between(since, Instant.now()).compareTo(minimum) < 0) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        return true;
    }

    // ------------------------------------------------------------------ the hostile engine

    /**
     * A deterministic {@link StatisticsEngine} whose parked query is DELIBERATELY HOSTILE.
     *
     * <p>See the class comment: it ignores scan cancellation, thread interruption and its own registered
     * abort callback, and it counts each of the three so a test asserts a delivered-then-ignored fact rather
     * than an assumption.
     */
    private static final class HostileEngine implements StatisticsEngine {

        private final LocalDate anchor;
        private final AtomicInteger anchorReads = new AtomicInteger();
        private final AtomicInteger aggregateEntries = new AtomicInteger();
        private final AtomicInteger abortCallbacks = new AtomicInteger();
        private final AtomicInteger interruptsSwallowed = new AtomicInteger();
        private final AtomicBoolean releasedByFailureDeadline = new AtomicBoolean();

        private volatile int parkRemaining;
        private volatile CountDownLatch entered = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);

        private HostileEngine(LocalDate anchor) {
            this.anchor = anchor;
        }

        /** Parks the next {@code count} aggregate calls until {@link #releaseParked()} is counted down. */
        void parkNextQueries(int count) {
            this.entered = new CountDownLatch(1);
            this.release = new CountDownLatch(1);
            this.parkRemaining = count;
        }

        /** The only thing that lets a parked query return. */
        void releaseParked() {
            release.countDown();
        }

        /** True while a parked query has not been released, so the worker is genuinely still inside it. */
        boolean parkedQueryStillHolding() {
            return release.getCount() > 0;
        }

        boolean awaitEntered(Duration timeout) {
            try {
                return entered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        int anchorReads() {
            return anchorReads.get();
        }

        int aggregateEntries() {
            return aggregateEntries.get();
        }

        /** How many times the scan's abort action (the Statement-style callback) was really invoked. */
        int abortCallbacks() {
            return abortCallbacks.get();
        }

        /** How many interrupts this hostile worker swallowed instead of obeying. */
        int interruptsSwallowed() {
            return interruptsSwallowed.get();
        }

        boolean releasedByFailureDeadline() {
            return releasedByFailureDeadline.get();
        }

        @Override
        public LocalDate databaseCurrentDate(ScanCancellation cancellation) {
            anchorReads.incrementAndGet();
            return anchor;
        }

        @Override
        public ItemTypeAggregate aggregate(ItemTypeSummary itemType,
                                           ScanWindows windows,
                                           ScanCancellation cancellation) throws Exception {
            aggregateEntries.incrementAndGet();
            // THE production shape of an abort action: the coordinator will run this when the scan is
            // cancelled, times out or closes. A hostile worker registers one and then ignores it, which is
            // what a driver that ignores Statement.cancel() looks like.
            ScanCancellation.Registration registration = cancellation.register(abortCallbacks::incrementAndGet);
            try {
                if (parkRemaining > 0) {
                    parkRemaining--;
                    entered.countDown();
                    if (!awaitIgnoringInterrupts(release, interruptsSwallowed)) {
                        releasedByFailureDeadline.set(true);
                    }
                    return measured(0L);
                }
                return measured(1L);
            } finally {
                registration.close();
            }
        }
    }

    /**
     * Waits on a latch while DELIBERATELY IGNORING interruption.
     *
     * <p>Each {@link InterruptedException} is counted and swallowed, and the wait continues: a worker that
     * honoured an interrupt would exit at the coordinator's {@code cancelInternal} and the defect could not be
     * reproduced. The interrupt flag is deliberately left cleared rather than restored - restoring it would
     * make the worker exit at its next {@code isInterrupted()} check, which is the opposite of hostile.
     *
     * @return true when the latch was released, false when the failure deadline expired
     */
    private static boolean awaitIgnoringInterrupts(CountDownLatch latch, AtomicInteger interruptsSwallowed) {
        long deadline = System.nanoTime() + ENGINE_FAILURE_DEADLINE.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return latch.getCount() == 0L;
            }
            try {
                if (latch.await(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)), TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException ignored) {
                // HOSTILE: an interrupt is counted and NOT obeyed. Thread.interrupted() cleared the flag.
                interruptsSwallowed.incrementAndGet();
            }
        }
    }

    /** Builds the package-private lifecycle-hook constructor without widening the production API. */
    private static ScanCoordinator coordinatorWithSupervisorHook(StatisticsSettings settings,
                                                                 String repository,
                                                                 java.util.function.Supplier<List<ItemTypeSummary>> source,
                                                                 StatisticsEngine engine,
                                                                 Runnable hook) throws Exception {
        java.lang.reflect.Constructor<ScanCoordinator> constructor = ScanCoordinator.class
                .getDeclaredConstructor(StatisticsSettings.class, String.class,
                        java.util.function.Supplier.class, StatisticsEngine.class, Runnable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(settings, repository, source, engine, hook);
    }

    /**
     * Goal 03B: terminal status is NOT physical supervisor death.
     *
     * <p>The hook parks at the supervisor's last action before Java-thread return. While parked, every
     * logical fact is final, but Thread.isAlive() is still true; the gate must therefore remain latched and
     * generation N+1 must be refused. This is the exact interleaving the self-deregister fix has to survive.
     */
    public void supervisorMustPhysicallyDieBeforeTheNextGenerationCanStart() throws Exception {
        CountDownLatch finalActionEntered = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        AtomicInteger swallowed = new AtomicInteger();
        Runnable finalAction = () -> {
            finalActionEntered.countDown();
            awaitIgnoringInterrupts(allowReturn, swallowed);
        };

        HostileEngine engine = new HostileEngine(LocalDate.of(2024, 6, 15));
        ScanCoordinator coordinator = coordinatorWithSupervisorHook(
                settings(1, Duration.ofSeconds(30)), REPOSITORY, () -> itemTypes(1), engine, finalAction);
        try {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "generation N starts");
            Assert.assertTrue(finalActionEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS),
                    "the supervisor must reach its final pre-return barrier");
            Assert.assertEquals(ScanStatus.Phase.COMPLETED, coordinator.progress().phase(),
                    "the result is already logically final while the supervisor is still alive");
            Assert.assertTrue(awaitCondition(() -> !threadsNamed("-supervisor").isEmpty(), WAIT),
                    "the old supervisor Java thread must still be physically alive");
            Assert.assertTrue(coordinator.isScanInFlight(),
                    "logical completion must not release the gate while that supervisor is alive");
            Assert.assertEquals(ScanStartResult.ALREADY_RUNNING, coordinator.requestScan(),
                    "generation N+1 is refused until the old supervisor actually returns");
            Assert.assertEquals(1, engine.anchorReads(),
                    "the refused request cannot start another generation or read another anchor");

            allowReturn.countDown();
            Assert.assertTrue(coordinator.awaitScanCompletion(WAIT),
                    "the generation reaper releases only after the supervisor physically dies");
            Assert.assertTrue(awaitCondition(() -> threadsNamed("-supervisor").isEmpty(), WAIT),
                    "the old supervisor is now observably dead");
            Assert.assertFalse(coordinator.isScanInFlight(), "the gate is open only after that death");

            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(),
                    "only now may generation N+1 start");
            Assert.assertTrue(coordinator.awaitScanCompletion(WAIT), "generation N+1 completes");
            Assert.assertEquals(2, engine.anchorReads(),
                    "each generation reads exactly one anchor; old cleanup did not start or disturb N+1");
        } finally {
            allowReturn.countDown();
            coordinator.close();
        }
    }

    /** The reaper's own remaining lifetime is still coordinator-owned during context close. */
    public void closeRemainsClosingUntilTheHeldSupervisorAndItsReaperAreActuallyGone() throws Exception {
        CountDownLatch finalActionEntered = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        AtomicInteger swallowed = new AtomicInteger();
        Runnable finalAction = () -> {
            finalActionEntered.countDown();
            awaitIgnoringInterrupts(allowReturn, swallowed);
        };

        HostileEngine engine = new HostileEngine(LocalDate.of(2024, 6, 15));
        ScanCoordinator coordinator = coordinatorWithSupervisorHook(
                settings(1, Duration.ofSeconds(1)), REPOSITORY, () -> itemTypes(1), engine, finalAction);
        try {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the scan starts");
            Assert.assertTrue(finalActionEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS),
                    "the supervisor reaches the final pre-return barrier");
            coordinator.close(); // bounded: the hostile hook ignores the close interrupt
            Assert.assertEquals(CloseState.CLOSING, coordinator.closeState(),
                    "a physically alive old-generation supervisor/reaper keeps repository shutdown pending");
            Assert.assertTrue(coordinator.isScanInFlight(),
                    "close returning is not evidence that the scan generation physically ended");

            allowReturn.countDown();
            Assert.assertTrue(coordinator.awaitScanCompletion(WAIT), "the physical generation drains");
            Assert.assertTrue(awaitCondition(() -> coordinator.closeState() == CloseState.CLOSED_CLEAN, WAIT),
                    "CLOSED_CLEAN is reported only after all coordinator-owned old-generation threads die");
        } finally {
            allowReturn.countDown();
            coordinator.close();
        }
    }

    /** Opposite control for the Goal 03B defect: self-removal before return demonstrably opens too early. */
    public void selfDeregisterBeforeThreadDeathWouldBeDetectedByTheNewAssertions() throws Exception {
        CountDownLatch removedSelf = new CountDownLatch(1);
        CountDownLatch allowDeath = new CountDownLatch(1);
        AtomicBoolean mutantGate = new AtomicBoolean(true);
        List<Thread> mutantTracked = new CopyOnWriteArrayList<>();

        Thread mutant = new Thread(() -> {
            mutantTracked.remove(Thread.currentThread());
            mutantGate.set(false); // the bad rule: logical cleanup authorises the next generation
            removedSelf.countDown();
            awaitIgnoringInterrupts(allowDeath, new AtomicInteger());
        }, ScanCoordinator.THREAD_NAME_PREFIX + REPOSITORY + "-mutant-supervisor");
        mutant.setDaemon(true);
        mutantTracked.add(mutant);
        mutant.start();
        try {
            Assert.assertTrue(removedSelf.await(WAIT.toMillis(), TimeUnit.MILLISECONDS),
                    "the mutant must reach its self-deregister-before-return window");
            Assert.assertTrue(mutant.isAlive(), "the mutant supervisor is physically alive");
            Assert.assertFalse(mutantGate.get(),
                    "yet its bad self-deregister rule has already opened the gate");
            Assert.assertTrue(mutantTracked.isEmpty(),
                    "and it has already erased the live thread reference: precisely the reviewed defect");
        } finally {
            allowDeath.countDown();
            mutant.join(WAIT.toMillis());
        }
        Assert.assertFalse(mutant.isAlive(), "the opposite control leaves no thread behind");
    }

    // ================================================================== facts 1-7 (deadline path)

    /**
     * The full hostile-worker sequence on the DEADLINE path: the scan reaches its deadline, the worker
     * outlives the old drain grace, nothing is published, a second refresh is refused, the thread stays
     * tracked, the close state is CLOSING, and only after the worker really exits is the gate released and the
     * close clean.
     */
    public void aHostileWorkerKeepsTheGateLatchedThreadTrackedAndCloseStateClosing() throws Exception {
        HostileEngine engine = new HostileEngine(LocalDate.of(2024, 6, 15));
        engine.parkNextQueries(1);
        ScanCoordinator coordinator = new ScanCoordinator(settings(1, SCAN_DEADLINE), REPOSITORY,
                () -> itemTypes(3), engine);
        try {
            // (1) the first scan runs, reads its one anchor, and a worker is genuinely inside its query.
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the first scan starts");
            Assert.assertTrue(engine.awaitEntered(WAIT),
                    "a worker must be inside the parked query before anything is asserted about the deadline");
            Assert.assertEquals(1, engine.anchorReads(), "the scan reads its one database anchor");

            // The deadline FIRES, and both of the levers it pulls are measured: the registered abort action
            // is really invoked, and the worker is really interrupted. Neither may be inferred.
            Assert.assertTrue(awaitCondition(() -> engine.abortCallbacks() >= 1, WAIT),
                    "the deadline must run the abort action a query registers through the scan's cancellation"
                            + " signal, but it was invoked " + engine.abortCallbacks() + " time(s)");
            Assert.assertTrue(awaitCondition(() -> engine.interruptsSwallowed() >= 1, WAIT),
                    "the deadline must also interrupt the scan's threads, and the hostile worker must have"
                            + " swallowed at least one interrupt; it swallowed "
                            + engine.interruptsSwallowed());
            Assert.assertTrue(engine.parkedQueryStillHolding(),
                    "and the hostile worker must still be inside its query afterwards: it obeys neither"
                            + " lever");
            Instant deadlineObservedAt = Instant.now();

            // (3) the snapshot is unchanged: a scan that was aborted publishes NOTHING.
            Assert.assertTrue(coordinator.snapshot().isEmpty(),
                    "a timed-out scan must publish no snapshot, and there was no previous one either");
            // close has not been requested, so the answer is NOT_CLOSED - not a premature CLOSING.
            Assert.assertEquals(CloseState.NOT_CLOSED, coordinator.closeState(),
                    "a coordinator whose close has not begun is NOT_CLOSED, even while a scan runs");

            // (4) THE assertion the defect broke: a second refresh is refused while the old worker is alive.
            Assert.assertEquals(ScanStartResult.ALREADY_RUNNING, coordinator.requestScan(),
                    "a SECOND REFRESH MUST BE REFUSED while a worker of the previous scan is still alive -"
                            + " this is the assertion the Goal 03 defect broke");
            Assert.assertEquals(1, engine.anchorReads(),
                    "and the refused request must have no side effect at all: the anchor was read once");
            Assert.assertEquals(1, engine.aggregateEntries(),
                    "nor may it have started a second worker: exactly one aggregate entry exists");

            // (2) the worker REMAINS ALIVE PAST THE OLD DRAIN GRACE. The wait is a bounded clock poll
            // because the assertion IS about elapsed time, and it is deliberately independent of the
            // coordinator's own bounded waits: an implementation that skipped its grace must not be able to
            // make this assertion vacuous.
            Assert.assertTrue(awaitElapsed(deadlineObservedAt, ALIVE_PAST_THE_OLD_GRACE, WAIT),
                    "the test must observe the world more than " + OLD_DRAIN_GRACE + " after the deadline");
            Assert.assertTrue(engine.parkedQueryStillHolding(),
                    "the HOSTILE worker must still be inside its query " + ALIVE_PAST_THE_OLD_GRACE
                            + " after the deadline: a worker that exited here would have been released"
                            + " legitimately by the old grace");

            // (5) the thread is still tracked, and the gate is still latched with the snapshot unchanged.
            Assert.assertTrue(coordinator.isScanInFlight(),
                    "AND THE GATE MUST STILL BE LATCHED: a terminal phase is not evidence that the scan's"
                            + " physical work has stopped; the tracked threads are " + scanThreads());
            Assert.assertTrue(coordinator.lingeringScanThreadCount() >= 1,
                    "and the thread reference must still be retained, but the coordinator reported "
                            + coordinator.lingeringScanThreadCount());
            Assert.assertTrue(awaitCondition(() -> !scanThreads().isEmpty(), WAIT),
                    "and the live worker thread must really exist: " + scanThreads());
            Assert.assertEquals(ScanStartResult.ALREADY_RUNNING, coordinator.requestScan(),
                    "a refresh is still refused at every later attempt");
            Assert.assertTrue(coordinator.snapshot().isEmpty(), "and the snapshot is still unchanged");

            // (6) the close state. close() is intentionally bounded, so the hostile worker outlives it.
            coordinator.close();
            Assert.assertEquals(CloseState.CLOSING, coordinator.closeState(),
                    "after close begins, a still-live scan thread means CLOSING; got "
                            + coordinator.closeState() + " with threads " + scanThreads());
            Assert.assertFalse(coordinator.closeState() == CloseState.CLOSED_CLEAN,
                    "close() returning must never be reported as CLOSED_CLEAN while a scan thread lives");
            Assert.assertFalse(coordinator.closeState() == CloseState.CLOSED_UNCERTAIN,
                    "and it must not be CLOSED_UNCERTAIN either: a merely slow thread is PENDING, not"
                            + " uncertain, and RepositoryManager maps CLOSED_UNCERTAIN to a PERMANENT switch"
                            + " refusal - conflating the two would brick repository switching after one slow"
                            + " scan");
            Assert.assertTrue(coordinator.isScanInFlight(),
                    "the one-scan gate stays latched through close as well");
            Assert.assertEquals(ScanStartResult.CLOSED, coordinator.requestScan(),
                    "and a refresh on a closed coordinator is refused");

            // (7) release the worker: it exits, and the release happens on that real exit.
            engine.releaseParked();
            Assert.assertTrue(coordinator.awaitScanCompletion(WAIT),
                    "the gate must be released once the last scan thread has REALLY exited; lingering="
                            + coordinator.lingeringScanThreadCount() + ", parked="
                            + engine.parkedQueryStillHolding());
            Assert.assertFalse(coordinator.isScanInFlight(),
                    "so the gate is finally open after the worker exited");
            Assert.assertEquals(0, coordinator.lingeringScanThreadCount(),
                    "with no tracked scan thread left alive");
            Assert.assertEquals(CloseState.CLOSED_CLEAN, coordinator.closeState(),
                    "and only NOW may the close be reported terminal-clean");
            Assert.assertTrue(coordinator.snapshot().isEmpty(),
                    "even at the end, the timed-out scan publishes nothing - the previous snapshot rule is"
                            + " unchanged");
            Assert.assertEquals(ScanStatus.Phase.TIMED_OUT, coordinator.progress().phase(),
                    "and the outcome it recorded is the deadline's TIMED_OUT, asserted here where it is"
                            + " observable: the coordinator's own bounded drain waits can delay its phase"
                            + " resolution, so this suite does not make its early assertions depend on that"
                            + " timing. The phase was " + coordinator.progress().phase());

            Assert.assertEquals(1, engine.aggregateEntries(),
                    "the hostile worker entered its query exactly once: a refused refresh is not a retry");
            Assert.assertEquals(1, engine.anchorReads(),
                    "and the scan read its anchor exactly once");
            Assert.assertFalse(engine.releasedByFailureDeadline(),
                    "the test released the worker, so the engine's own failure deadline must not have fired;"
                            + " otherwise the sequence above proved nothing about the coordinator");

            // A leak assertion on the documented thread-name suffixes: every scan thread, and the per-scan
            // watchdog, must be gone once the drain has finished and the gate is open.
            Assert.assertTrue(awaitCondition(() -> scanThreads().isEmpty(), WAIT),
                    "no coordinator thread may outlive the drain, but these are still alive: " + scanThreads());
            Assert.assertTrue(threadsNamed("-watchdog-").isEmpty(),
                    "and the per-scan watchdog must have terminated: " + threadsNamed("-watchdog-"));
        } finally {
            engine.releaseParked();
            coordinator.close();
        }
    }

    // ================================================================== three-way fact, and fact 8

    /**
     * A worker that OUTLIVES its supervisor: the result is final, the gate is still latched, the worker is
     * still tracked - and its own exit is what releases the gate, exactly once, after which alone a new scan
     * may proceed.
     *
     * <p>The stop here is an explicit {@code cancelScan()}, which is what makes this the sharpest form of the
     * sequence: the supervisor finishes its bounded joins and exits promptly while its worker lives on, so the
     * three-way fact {@code isDraining() && isScanInFlight() && terminal phase} is observable cheaply and
     * deterministically, and the release that follows can therefore only have come from the WORKER's own
     * {@code finally} - the path the reviewer asked to have covered explicitly.
     */
    public void theWorkerThatOutlivesItsSupervisorReleasesTheGateItselfAndOnlyThenANewScanProceeds()
            throws Exception {
        HostileEngine engine = new HostileEngine(LocalDate.of(2024, 6, 15));
        engine.parkNextQueries(1);
        // A long deadline, so the deadline is demonstrably NOT what stopped this scan: the only trigger is the
        // explicit cancellation below.
        ScanCoordinator coordinator = new ScanCoordinator(settings(1, Duration.ofSeconds(30)), REPOSITORY,
                () -> itemTypes(2), engine);
        try {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the first scan starts");
            Assert.assertTrue(engine.awaitEntered(WAIT), "its worker is inside the parked query");

            Assert.assertTrue(coordinator.cancelScan(), "an explicit cancellation is signalled");
            Assert.assertTrue(awaitCondition(() -> engine.abortCallbacks() >= 1, WAIT),
                    "the cancellation must run the abort action the worker registered through the scan's"
                            + " signal");
            Assert.assertTrue(awaitCondition(() -> engine.interruptsSwallowed() >= 1, WAIT),
                    "and it must interrupt the worker, which swallows it");

            // The supervisor finishes its bounded joins and exits while its worker lives on, so the scan
            // reports its result AND its still-live thread together.
            Assert.assertTrue(awaitCondition(coordinator::isDraining, WAIT),
                    "the scan must reach the explicit DRAINING state - result final, scan thread alive -"
                            + " but isDraining=" + coordinator.isDraining()
                            + ", isScanInFlight=" + coordinator.isScanInFlight()
                            + ", lingering=" + coordinator.lingeringScanThreadCount());
            Assert.assertTrue(coordinator.isScanInFlight(),
                    "AND THE GATE MUST STILL BE LATCHED while the worker lives: a terminal result is not"
                            + " evidence that the physical work stopped");
            Assert.assertEquals(ScanStatus.Phase.CANCELLED, coordinator.progress().phase(),
                    "the terminal phase is the cancellation's, while isScanInFlight() is still true: the pair"
                            + " a phase-only assertion could not express. The phase was "
                            + coordinator.progress().phase());
            Assert.assertTrue(awaitCondition(() -> threadsNamed("-supervisor").isEmpty(), WAIT),
                    "the supervisor must be demonstrably gone, otherwise the release being tested is not the"
                            + " worker's: these supervisor threads are still alive "
                            + threadsNamed("-supervisor"));
            Assert.assertTrue(engine.parkedQueryStillHolding(),
                    "and the worker must still be inside its query, ignoring cancellation, interruption and"
                            + " its abort callback");
            Assert.assertTrue(coordinator.lingeringScanThreadCount() >= 1,
                    "while still tracked by the coordinator");

            Assert.assertEquals(ScanStartResult.ALREADY_RUNNING, coordinator.requestScan(),
                    "a new scan must be refused while the old worker outlives its supervisor");
            Assert.assertEquals(1, engine.anchorReads(),
                    "and that refusal must have no side effect: no second anchor read");

            // The worker is the LAST thread of the scan, so its own exit is the release - not a bound.
            engine.releaseParked();
            Assert.assertTrue(coordinator.awaitScanCompletion(WAIT),
                    "the surviving worker's own exit must release the gate; lingering="
                            + coordinator.lingeringScanThreadCount() + ", threads=" + scanThreads()
                            + ", parked=" + engine.parkedQueryStillHolding());
            Assert.assertFalse(coordinator.isScanInFlight(), "the gate is open");
            Assert.assertFalse(coordinator.isDraining(), "and the drain is over");
            Assert.assertEquals(0, coordinator.lingeringScanThreadCount(),
                    "with every scan thread of the first scan dead");
            Assert.assertTrue(coordinator.snapshot().isEmpty(),
                    "the drained, cancelled scan publishes nothing");
            Assert.assertEquals(ScanStatus.Phase.CANCELLED, coordinator.progress().phase(),
                    "and its terminal phase is still the one that was decided first");
            Assert.assertEquals(1, engine.anchorReads(), "and the first scan still read its anchor once");

            // (8) ONLY NOW may a new scan proceed.
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(),
                    "a new scan may proceed once every thread of the previous one is dead");
            Assert.assertEquals(ScanStartResult.ALREADY_RUNNING, coordinator.requestScan(),
                    "and it is itself the one scan in flight, so a third request is refused");
            Assert.assertTrue(coordinator.awaitScanCompletion(WAIT), "the new scan completes normally");
            Assert.assertNotNull(coordinator.snapshot().orElse(null),
                    "and it publishes its own snapshot: the gate really was released, not merely re-latched");
            Assert.assertEquals(ScanStatus.Phase.COMPLETED, coordinator.progress().phase(),
                    "with a COMPLETED terminal phase");
            Assert.assertEquals(2, engine.anchorReads(),
                    "the new scan read its own anchor exactly once, so the old gate was released exactly once"
                            + " and not re-released into the new scan");
        } finally {
            engine.releaseParked();
            coordinator.close();
        }
    }

    // ================================================================== the mutation control

    /**
     * MUTATION CONTROL: the PRE-FIX rule is reproduced, and the EXACT assertions of this suite are run
     * against it and required to fail.
     *
     * <p>The defect was not "a worker survived". It was the release RULE: {@code joinWorkers(deadline...)}
     * reported a living worker, the coordinator cancelled and gave it the drain grace, THE SECOND JOIN'S
     * RESULT WAS DISCARDED, and {@code finishScan()} then set the gate free and cleared the tracked thread
     * references anyway. {@link PreFixRule} reproduces exactly that, as executable code, driving a real
     * hostile thread so that "the worker is still alive" is a fact rather than a comment.
     *
     * <p>Each check below is the SAME assertion the main sequence makes, applied to the pre-fix rule and
     * required to throw. If the pre-fix rule could satisfy them, the main sequence would be proving nothing.
     */
    public void thePreFixReleaseRuleWouldBeDetectedByTheseAssertions() throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger swallowed = new AtomicInteger();
        // A REAL hostile thread, carrying the same name shape the coordinator gives a worker, so the
        // control's "still tracked" and "still alive" are measurements of a live thread.
        Thread hostile = new Thread(() -> {
            parked.countDown();
            awaitIgnoringInterrupts(release, swallowed);
        }, ScanCoordinator.THREAD_NAME_PREFIX + REPOSITORY + "-0");
        hostile.setDaemon(true);
        hostile.start();

        PreFixRule preFix = new PreFixRule(List.of(hostile));
        try {
            Assert.assertTrue(parked.await(WAIT.toMillis(), TimeUnit.MILLISECONDS),
                    "the control's hostile worker must be running before the pre-fix rule is applied");
            preFix.runScanWithThePreFixReleaseRule(Duration.ofMillis(100));

            Assert.assertTrue(hostile.isAlive(),
                    "the control requires its worker to still be ALIVE, which is the state the pre-fix rule"
                            + " was wrong about");

            // The three assertions the main sequence makes, run against the pre-fix rule. Each MUST fail
            // there, which is what makes the main sequence evidence rather than a description.
            Assert.assertTrue(controlDetects(
                            () -> Assert.assertTrue(preFix.isScanInFlight(),
                                    "the one-scan gate must still be latched while a worker is alive"),
                            "the gate-latched assertion"),
                    "the gate assertion must FAIL on the pre-fix rule: that rule released the gate while this"
                            + " hostile worker was still alive, which is the defect");
            Assert.assertTrue(controlDetects(
                            () -> Assert.assertTrue(preFix.trackedThreads() >= 1,
                                    "the alive worker's thread reference must still be tracked"),
                            "the thread-tracked assertion"),
                    "the tracked-thread assertion must FAIL on the pre-fix rule: it cleared the thread list");
            Assert.assertTrue(controlDetects(
                            () -> Assert.assertFalse(preFix.wouldAcceptRefresh(),
                                    "a second refresh must be refused while the old worker is alive"),
                            "the second-refresh-refused assertion"),
                    "the refusal assertion must FAIL on the pre-fix rule: that is the assertion the defect"
                            + " broke, and a second refresh would have been ACCEPTED here");
            Assert.assertTrue(preFix.wouldAcceptRefresh(),
                    "and the control states the defect explicitly: on the pre-fix rule a second refresh WOULD"
                            + " be accepted while the old worker is still alive");
            Assert.assertTrue(hostile.isAlive(),
                    "and that acceptance would have happened while the worker was demonstrably still alive");
        } finally {
            release.countDown();
            hostile.join(WAIT.toMillis());
        }
        Assert.assertFalse(hostile.isAlive(), "the control must release its own worker and leave no thread");
    }

    /** Runs one assertion against the pre-fix rule and reports whether it correctly FAILED. */
    private static boolean controlDetects(Runnable assertion, String what) {
        try {
            assertion.run();
            return false;
        } catch (AssertionError detected) {
            return true;
        }
    }

    /**
     * The PRE-FIX release rule, reproduced as executable code.
     *
     * <p>It is a faithful model of Goal 03's coordinator, kept here so the mutation control does not depend on
     * the old implementation still existing: join the workers with the deadline, cancel and interrupt them,
     * join again with the drain grace - and DISCARD that second result - then release the gate and clear the
     * tracked thread references unconditionally.
     *
     * <p>The drain grace is short here because the RULE is under test, not the constant: the control must show
     * that a second join whose answer is thrown away releases the gate while the worker lives, which is true
     * for any grace at all.
     */
    private static final class PreFixRule {

        private final AtomicBoolean scanInFlight = new AtomicBoolean(true);
        private final List<Thread> tracked;

        private PreFixRule(List<Thread> tracked) {
            this.tracked = new CopyOnWriteArrayList<>(tracked);
        }

        private void runScanWithThePreFixReleaseRule(Duration oldDrainGrace) throws Exception {
            // Step 1: joinWorkers(...deadline...) reports a living worker.
            boolean lingering = joinBounded(tracked, Duration.ofMillis(50));
            Assert.assertTrue(lingering,
                    "the control requires the pre-fix deadline join to have reported a living worker");
            // Step 2: the coordinator cancels and interrupts the worker.
            for (Thread thread : tracked) {
                thread.interrupt();
            }
            // Step 3: THE DEFECT - the second join's result is discarded.
            joinBounded(tracked, oldDrainGrace);
            // Step 4: finishScan() then sets scanInFlight=false, clears the active scan and clears the
            // tracked thread list, with no check that a thread is still alive.
            scanInFlight.set(false);
            tracked.clear();
        }

        private boolean isScanInFlight() {
            return scanInFlight.get();
        }

        private int trackedThreads() {
            return tracked.size();
        }

        /**
         * What {@code requestScan()} would answer. Deliberately NON-mutating, unlike a real request: the
         * control needs to read the answer several times, and a getter that latched the gate would make the
         * second reading meaningless.
         */
        private boolean wouldAcceptRefresh() {
            return !scanInFlight.get();
        }

        /** The old bounded join: it reports whether anything is still alive, and nothing acts on it twice. */
        private static boolean joinBounded(List<Thread> threads, Duration bound) throws InterruptedException {
            long deadline = System.nanoTime() + bound.toNanos();
            boolean alive = false;
            for (Thread thread : threads) {
                if (thread.isAlive()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining > 0L) {
                        thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
                    }
                }
                alive |= thread.isAlive();
            }
            return alive;
        }
    }
}
