package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.core.CloseState;
import com.mraibo.cminsight.core.StatisticsSettings;
import com.mraibo.cminsight.db.JdbcSession;
import com.mraibo.cminsight.db.JdbcSessionFactory;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryException;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.statistics.ItemTypeAggregate;
import com.mraibo.cminsight.statistics.ItemTypeStatistics;
import com.mraibo.cminsight.statistics.MetricValue;
import com.mraibo.cminsight.statistics.ScanCancellation;
import com.mraibo.cminsight.statistics.ScanCoordinator;
import com.mraibo.cminsight.statistics.ScanStartResult;
import com.mraibo.cminsight.statistics.ScanStatus;
import com.mraibo.cminsight.statistics.ScanWindows;
import com.mraibo.cminsight.statistics.StatisticsEngine;
import com.mraibo.cminsight.statistics.StatisticsQueryException;
import com.mraibo.cminsight.statistics.StatisticsSnapshot;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Goal 03 section 12 and section 15 "Coordinator": bounded workers, one scan at a time, failure isolation,
 * atomic publication and cancellation.
 *
 * <h2>How a thread-free test drives a concurrent coordinator deterministically</h2>
 *
 * <p>Nothing here sleeps. Every case that needs an interleaving installs a {@link CountDownLatch} inside the
 * fake engine: a query counts down {@code entered} as it starts and then parks on {@code gate}, so the test
 * can make a specific number of queries genuinely in flight, assert about them, and only then release them.
 * The single exception is that the parked query is released by the coordinator's own abort action when a scan
 * is cancelled - which is exactly what {@code Statement.cancel()} does in production, so the shutdown cases
 * exercise the real path instead of a test-only shortcut.
 *
 * <h2>Why the fake engine is the right instrument</h2>
 *
 * <p>{@code StatisticsEngine} is the coordinator's whole database half, deliberately narrow so the scan
 * policy can be tested without a database (the interface says so). The fake therefore measures exactly what
 * this suite has to prove about the coordinator: how many queries run at once, which threads run them, which
 * ItemTypes were visited, whether the anchor was read once, and whether an abort action was registered.
 */
public class ScanCoordinatorTest {

    /** The repository id used throughout, so thread names are predictable. */
    private static final String REPOSITORY = "scan-test";

    /** A timeout long enough never to flake on a loaded machine and short enough to bound a failure. */
    private static final Duration WAIT = Duration.ofSeconds(20);

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

    private static ItemTypeAggregate aggregate(long logicalItems) {
        return new ItemTypeAggregate(logicalItems, MetricValue.available(0L), MetricValue.available(0L),
                MetricValue.available(0L), MetricValue.available(0L));
    }

    private static StatisticsSettings settings(int workers, Duration scanTimeout) {
        return new StatisticsSettings(true, workers, Duration.ofSeconds(5), scanTimeout);
    }

    /**
     * A registered fake DB2 driver with the vendor-named driver class loaded, so the JDBC pool built from a
     * {@link TestSupport} profile can really create connections.
     */
    private static FakeJdbc db2Driver() {
        FakeJdbc.loadVendorDriverClass(FakeJdbc.DB2_DRIVER_CLASS);
        return FakeJdbc.register("jdbc:db2:");
    }

    /** Every live thread whose name carries the coordinator's prefix. */
    private static Set<String> scanThreads() {
        Set<String> names = new java.util.TreeSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(ScanCoordinator.THREAD_NAME_PREFIX)) {
                names.add(thread.getName());
            }
        }
        return names;
    }

    // ------------------------------------------------------------------ the fake engine

    /**
     * A deterministic {@link StatisticsEngine}.
     *
     * <p>It counts the anchor read, records which threads ran a query, measures the peak number of
     * simultaneously executing aggregates, records the visited ItemTypes, and can park a query on a latch or
     * fail a named ItemType. It registers an abort action through the scan's cancellation signal, so a
     * cancellation really does release a parked query - the same shape the JDBC layer uses for
     * {@code Statement.cancel()}.
     */
    private static final class FakeEngine implements StatisticsEngine {

        private final LocalDate anchor;
        private final AtomicInteger anchorReads = new AtomicInteger();
        private final AtomicInteger concurrent = new AtomicInteger();
        private final AtomicInteger peakConcurrent = new AtomicInteger();
        private final AtomicInteger aggregateCalls = new AtomicInteger();
        private final Set<String> workerThreadNames = ConcurrentHashMap.newKeySet();
        private final List<String> visited = new CopyOnWriteArrayList<>();
        private final Map<String, Supplier<ItemTypeAggregate>> perItem = new ConcurrentHashMap<>();
        private final List<String> failing = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch entered = new CountDownLatch(0);
        private volatile CountDownLatch gate = new CountDownLatch(0);
        private volatile Supplier<ItemTypeAggregate> fallback = () -> ScanCoordinatorTest.aggregate(1L);

        private FakeEngine(LocalDate anchor) {
            this.anchor = Objects.requireNonNull(anchor, "anchor");
        }

        /** Parks every subsequent query until the gate is released. */
        void park(CountDownLatch enteredLatch, CountDownLatch gateLatch) {
            this.entered = enteredLatch;
            this.gate = gateLatch;
        }

        void releaseParkedQueries() {
            gate.countDown();
        }

        void failItemType(String name) {
            failing.add(name);
        }

        void answer(String name, Supplier<ItemTypeAggregate> value) {
            perItem.put(name, value);
        }

        void answerAll(Supplier<ItemTypeAggregate> value) {
            this.fallback = value;
        }

        int anchorReads() {
            return anchorReads.get();
        }

        int peakConcurrent() {
            return peakConcurrent.get();
        }

        int aggregateCalls() {
            return aggregateCalls.get();
        }

        Set<String> workerThreadNames() {
            return Set.copyOf(workerThreadNames);
        }

        List<String> visited() {
            return List.copyOf(visited);
        }

        @Override
        public LocalDate databaseCurrentDate(ScanCancellation cancellation) {
            // The signal is accepted and deliberately unused: this fake's anchor is instantaneous, so there
            // is nothing for a cancellation to reach here. The suite that pins the anchor's abort path is
            // AnchorDeadlineCancellationTest.
            anchorReads.incrementAndGet();
            return anchor;
        }

        @Override
        public ItemTypeAggregate aggregate(ItemTypeSummary itemType,
                                           ScanWindows windows,
                                           ScanCancellation cancellation) throws Exception {
            aggregateCalls.incrementAndGet();
            workerThreadNames.add(Thread.currentThread().getName());
            visited.add(itemType.name());
            int now = concurrent.incrementAndGet();
            peakConcurrent.accumulateAndGet(now, Math::max);
            ScanCancellation.Registration registration = cancellation.register(gate::countDown);
            try {
                if (windows.anchor() == null || !windows.anchor().equals(anchor)) {
                    throw new IllegalStateException("every ItemType of one scan must share the scan anchor");
                }
                if (failing.contains(itemType.name())) {
                    throw new StatisticsQueryException("logical item aggregate", "08S01", 4061,
                            new java.sql.SQLException(FakeJdbc.RAW_FAILURE_MARKER + " aggregate failed"));
                }
                entered.countDown();
                if (!gate.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("the test did not release the query gate");
                }
                if (cancellation.isCancelled()) {
                    // A cancelled query must not write a result: the coordinator decides the outcome.
                    throw new InterruptedException("cancelled");
                }
                Supplier<ItemTypeAggregate> behaviour = perItem.get(itemType.name());
                return behaviour == null ? fallback.get() : behaviour.get();
            } finally {
                registration.close();
                concurrent.decrementAndGet();
            }
        }
    }

    // ------------------------------------------------------------------ one scan at a time

    /**
     * A second {@code requestScan()} while one is running is refused with ZERO side effects.
     *
     * <p>The item-type source is the observable side effect: it is read exactly once per scan, so a second
     * call that reported {@code ALREADY_RUNNING} yet still invoked the source would be caught here. The
     * anchor is the second observable: it must be read once for the one scan that runs.
     */
    public void onlyOneScanRunsAtATimeWithNoSideEffectsOnTheSecondRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        FakeEngine engine = new FakeEngine(LocalDate.of(2024, 6, 15));
        engine.park(entered, gate);
        AtomicInteger sourceCalls = new AtomicInteger();
        ScanCoordinator coordinator = new ScanCoordinator(settings(2, Duration.ofSeconds(30)), REPOSITORY,
                () -> {
                    sourceCalls.incrementAndGet();
                    return itemTypes(4);
                }, engine);
        try {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(),
                    "the first request starts a scan");
            Assert.assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS),
                    "at least one query must be in flight before the second request is made");

            Assert.assertEquals(ScanStartResult.ALREADY_RUNNING, coordinator.requestScan(),
                    "a second request while a scan is running must be refused deterministically");
            Assert.assertEquals(ScanStartResult.ALREADY_RUNNING, coordinator.requestScan(),
                    "and it stays refused for every further attempt");
            Assert.assertEquals(1, sourceCalls.get(),
                    "the second request must have no side effect at all: the frozen ItemType source was read"
                            + " exactly once, for the one scan that runs");
            Assert.assertEquals(1, engine.anchorReads(),
                    "and the database anchor was read once, not once per request");
            Assert.assertTrue(coordinator.isScanInFlight(), "the first scan is still the one in flight");

            engine.releaseParkedQueries();
            Assert.assertTrue(coordinator.awaitScanCompletion(WAIT),
                    "the first scan must finish once its queries are released");
            Assert.assertNotNull(coordinator.snapshot().orElse(null),
                    "and it published its snapshot");
        } finally {
            coordinator.close();
        }
    }

    // ------------------------------------------------------------------ bounded concurrency

    /**
     * Concurrency never exceeds the configured worker count, measured inside the query surface, and the set
     * of threads that ran a query is exactly the coordinator's bounded worker set.
     *
     * <p>Two independent measurements of the same bound: the peak number of simultaneously executing
     * aggregates, and the distinct thread names that executed one. A coordinator that spawned a thread per
     * ItemType, or handed work to a pool that grew, would fail both. The thread-name set is also the answer
     * to "is there an unbounded queue": 40 ItemTypes still run on exactly {@code workers} threads, with no
     * submission step and no queue to grow.
     */
    public void concurrencyNeverExceedsTheWorkerCountAndUsesExactlyThoseThreads() throws Exception {
        int workers = 3;
        CountDownLatch entered = new CountDownLatch(workers);
        CountDownLatch gate = new CountDownLatch(1);
        FakeEngine engine = new FakeEngine(LocalDate.of(2024, 6, 15));
        engine.park(entered, gate);
        ScanCoordinator coordinator = new ScanCoordinator(
                settings(workers, Duration.ofSeconds(60)), REPOSITORY, () -> itemTypes(40), engine);
        try {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the scan starts");
            Assert.assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS),
                    "all " + workers + " workers must be inside a query, otherwise the peak is not saturated");

            // Give the scheduler every opportunity to over-schedule: if a fourth worker existed it would be
            // inside aggregate() by now, and the count would show it. No sleep is used - the assertion is on
            // state that only a real over-schedule could change.
            Assert.assertEquals(workers, engine.peakConcurrent(),
                    "exactly " + workers + " queries may run at once, but the peak was "
                            + engine.peakConcurrent());
            Assert.assertEquals(workers, engine.workerThreadNames().size(),
                    "the queries must run on exactly " + workers + " distinct threads: " + engine.workerThreadNames());
            for (String name : engine.workerThreadNames()) {
                Assert.assertTrue(name.startsWith(ScanCoordinator.THREAD_NAME_PREFIX),
                        "every scan thread must carry the documented prefix, so 'no thread left' is exact: "
                                + name);
            }

            engine.releaseParkedQueries();
            Assert.assertTrue(coordinator.awaitScanCompletion(WAIT), "the scan finishes");
            Assert.assertEquals(40, engine.aggregateCalls(),
                    "every one of the 40 frozen ItemTypes was visited exactly once");
            Assert.assertEquals(workers, engine.peakConcurrent(),
                    "and the peak never rose later in the scan");
            Assert.assertEquals(workers, engine.workerThreadNames().size(),
                    "nor did the thread set grow: 40 ItemTypes ran on " + workers + " threads, so there is no"
                            + " per-item thread and no queue behind them");
        } finally {
            coordinator.close();
        }
    }

    // ------------------------------------------------------------------ failure isolation

    /**
     * One ItemType failing does not stop the others, and the failure is recorded with a SANITIZED reason.
     *
     * <p>The engine throws a {@link StatisticsQueryException} whose cause carries the fake driver's raw text.
     * The snapshot must contain the fixed operation label and the SQLState, and must not contain the raw
     * marker - a test that only asserted "ERROR" would pass for an implementation that published the driver's
     * message.
     */
    public void oneItemFailureDoesNotStopTheOthersAndItsReasonIsSanitized() throws Exception {
        FakeEngine engine = new FakeEngine(LocalDate.of(2024, 6, 15));
        engine.failItemType("ItemType1");
        ScanCoordinator coordinator = new ScanCoordinator(settings(3, Duration.ofSeconds(30)), REPOSITORY,
                () -> itemTypes(6), engine);
        try {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the scan starts");
            Assert.assertTrue(coordinator.awaitScanCompletion(WAIT), "the scan must finish");

            Assert.assertEquals(6, engine.aggregateCalls(),
                    "every ItemType must be attempted, including the ones after the failure");
            StatisticsSnapshot snapshot = coordinator.snapshot().orElseThrow();
            Assert.assertEquals(6, snapshot.perItemType().size(),
                    "the published snapshot must cover the whole frozen list");

            ItemTypeStatistics failed = snapshot.itemType("ItemType1").orElseThrow();
            Assert.assertTrue(failed.failed(), "the failing ItemType is recorded as ERROR");
            Assert.assertFalse(failed.logicalItems().isAvailable(),
                    "and it carries NO number: an ERROR must never be rendered as a zero count");
            String reason = failed.errorMessage();
            Assert.assertTrue(reason.contains("logical item aggregate"),
                    "the reason must name the fixed operation label: " + reason);
            Assert.assertTrue(reason.contains("08S01"),
                    "and the SQLState the driver reported: " + reason);
            Assert.assertFalse(reason.contains(FakeJdbc.RAW_FAILURE_MARKER),
                    "but it must never reproduce raw driver text: " + reason);
            Assert.assertFalse(reason.contains("jdbc:db2:"),
                    "and never a JDBC URL: " + reason);

            for (int index = 0; index < 6; index++) {
                if (index == 1) {
                    continue;
                }
                ItemTypeStatistics ok = snapshot.itemType("ItemType" + index).orElseThrow();
                Assert.assertFalse(ok.failed(),
                        "ItemType" + index + " must still be measured; one failure must not abort the rest");
                Assert.assertTrue(ok.totalAvailable(), "and its total must be available");
            }
        } finally {
            coordinator.close();
        }
    }

    // ------------------------------------------------------------------ publication

    /**
     * A normal scan that ends with partial failures publishes ONE new snapshot ATOMICALLY, and the snapshot
     * reports the coverage so a subtotal is never presented as a complete total.
     */
    public void aNormalPartialFailureScanPublishesOneSnapshotWithCoverage() throws Exception {
        FakeEngine engine = new FakeEngine(LocalDate.of(2024, 6, 15));
        engine.failItemType("ItemType2");
        engine.answer("ItemType0", () -> aggregate(7L));
        ScanCoordinator coordinator = new ScanCoordinator(settings(2, Duration.ofSeconds(30)), REPOSITORY,
                () -> itemTypes(5), engine);
        try {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the scan starts");
            Assert.assertTrue(coordinator.awaitScanCompletion(WAIT), "the scan must finish");

            StatisticsSnapshot snapshot = coordinator.snapshot().orElseThrow();
            Assert.assertEquals(1, snapshot.partialFailureCount(),
                    "one ItemType failed, so the snapshot must report exactly one partial failure");
            Assert.assertFalse(snapshot.complete(),
                    "and it must not claim to be a complete measurement of the frozen list");
            Assert.assertFalse(snapshot.coverage().complete(),
                    "the coverage must say the same thing, so a subtotal cannot be read as a total");
            Assert.assertEquals(5, snapshot.coverage().requestedItemTypes(),
                    "the coverage is measured against the frozen list");
            Assert.assertEquals(7L, snapshot.itemType("ItemType0").orElseThrow().logicalItemsOrZero(),
                    "a successful ItemType carries the number the engine returned");
            Assert.assertEquals(ScanStatus.Phase.COMPLETED, coordinator.progress().phase(),
                    "the terminal phase of a scan that visited the whole frozen list is COMPLETED, even with"
                            + " a failed ItemType inside it");
        } finally {
            coordinator.close();
        }
    }

    /**
     * A cancellation KEEPS the previous completed snapshot; nothing is published for the cancelled scan.
     *
     * <p>This is the publication rule's other half, and the half that is easy to get wrong: it is tempting to
     * publish whatever was collected. The previous snapshot must remain the current answer, by identity.
     */
    public void catastrophicCancellationKeepsThePreviousSnapshot() throws Exception {
        FakeEngine engine = new FakeEngine(LocalDate.of(2024, 6, 15));
        ScanCoordinator coordinator = new ScanCoordinator(settings(2, Duration.ofSeconds(30)), REPOSITORY,
                () -> itemTypes(4), engine);
        try {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the first scan starts");
            Assert.assertTrue(coordinator.awaitScanCompletion(WAIT), "the first scan finishes");
            StatisticsSnapshot first = coordinator.snapshot().orElseThrow();
            Assert.assertEquals(ScanStatus.Phase.COMPLETED, coordinator.progress().phase(),
                    "the first scan completed and published");

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1);
            engine.park(entered, gate);
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the second scan starts");
            Assert.assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS),
                    "a query of the second scan must be in flight before it is cancelled");

            Assert.assertTrue(coordinator.cancelScan(), "the running scan must report that it was signalled");
            Assert.assertTrue(coordinator.awaitScanCompletion(WAIT),
                    "and the coordinator must finish the scan rather than leaving it in flight");

            Assert.assertTrue(coordinator.snapshot().orElse(null) == first,
                    "the cancelled scan must NOT replace the previous snapshot; the same object must still be"
                            + " the current answer");
            Assert.assertEquals(ScanStatus.Phase.CANCELLED, coordinator.progress().phase(),
                    "and the terminal phase must say it was cancelled, not completed");
            Assert.assertFalse(coordinator.progress().failureReason().isEmpty(),
                    "with a fixed reason an operator can act on");
        } finally {
            coordinator.close();
        }
    }

    // ------------------------------------------------------------------ close leaves no thread

    /**
     * Closing the coordinator cancels the running scan, joins its threads, and leaves no thread behind.
     *
     * <p>The parked query is released by the coordinator's own abort action, which is the production path
     * ({@code Statement.cancel()}) rather than a test-only escape hatch. Afterwards the thread check is
     * exact because every thread carries the documented prefix.
     */
    public void closeCancelsTheScanAndLeavesNoThreadBehind() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch gate = new CountDownLatch(1);
        FakeEngine engine = new FakeEngine(LocalDate.of(2024, 6, 15));
        engine.park(entered, gate);
        ScanCoordinator coordinator = new ScanCoordinator(settings(2, Duration.ofSeconds(30)), REPOSITORY,
                () -> itemTypes(8), engine);
        try {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the scan starts");
            Assert.assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS),
                    "both workers must be inside a query");
            Assert.assertFalse(scanThreads().isEmpty(),
                    "the scan's threads are alive while it runs: " + scanThreads());

            coordinator.close();

            Assert.assertTrue(scanThreads().isEmpty(),
                    "close() must join every thread it started, but these are still alive: " + scanThreads());
            Assert.assertTrue(coordinator.snapshot().isEmpty(),
                    "and a scan that was closed before visiting the whole list must publish nothing");
            Assert.assertEquals(ScanStatus.Phase.CANCELLED, coordinator.progress().phase(),
                    "the terminal phase records that the close cancelled the scan");
            Assert.assertEquals(ScanStartResult.CLOSED, coordinator.requestScan(),
                    "and a closed coordinator refuses to start another scan");
            Assert.assertFalse(coordinator.availability().available(),
                    "and it reports itself unavailable rather than pretending to work");
        } finally {
            coordinator.close();
        }
    }

    // ------------------------------------------------------------------ deadline

    /**
     * An overall-deadline abort publishes nothing and keeps the previous snapshot, exactly like a
     * cancellation.
     *
     * <p>Driven deterministically by parking the queries past the (short) configured scan timeout, so the
     * coordinator's own deadline logic decides the outcome - no sleep decides it.
     */
    public void anOverallDeadlineAbortKeepsThePreviousSnapshotAndReportsTimedOut() throws Exception {
        FakeEngine engine = new FakeEngine(LocalDate.of(2024, 6, 15));
        ScanCoordinator quick = new ScanCoordinator(settings(2, Duration.ofSeconds(2)), REPOSITORY,
                () -> itemTypes(3), engine);
        try {
            Assert.assertEquals(ScanStartResult.STARTED, quick.requestScan(), "the first scan starts");
            Assert.assertTrue(quick.awaitScanCompletion(Duration.ofSeconds(30)), "the first scan finishes");
            StatisticsSnapshot first = quick.snapshot().orElseThrow();

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1);
            engine.park(entered, gate);
            Assert.assertEquals(ScanStartResult.STARTED, quick.requestScan(), "the second scan starts");
            Assert.assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS),
                    "a query must be in flight for the deadline to bite");

            Assert.assertTrue(quick.awaitScanCompletion(Duration.ofSeconds(30)),
                    "the deadline must end the scan without external help");
            Assert.assertTrue(quick.snapshot().orElse(null) == first,
                    "a deadline abort must not replace the previous completed snapshot");
            Assert.assertEquals(ScanStatus.Phase.TIMED_OUT, quick.progress().phase(),
                    "and the phase must report the timeout");
        } finally {
            engine.releaseParkedQueries();
            quick.close();
        }
    }

    // ------------------------------------------------------------------ JDBC pool in the context

    /**
     * A LATE JDBC LEASE makes the repository context CLOSING and blocks the switch until it is returned.
     *
     * <p>Goal 03 section 3 requires the JDBC pool to participate in the SAME Goal 01C close-state semantics
     * as the accepted CM pool, so this drives the real thing: a real {@code BoundedPool<JdbcSession>} created
     * by the real factory over the fake driver, owned by a real {@link RepositoryContext}, activated through
     * a real {@link RepositoryManager}. Nothing here is a stand-in for the pool.
     *
     * <p>The decisive assertions are the same three the CM-pool suite uses: the next-context factory was
     * never invoked, the old physical connection is still live, and the reported state is pending - never
     * clean. A retry must not be able to bypass the pending context.
     */
    public void aLateJdbcLeaseMakesTheContextClosingAndBlocksTheSwitch() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            RepositoryProfile alphaProfile = TestSupport.profile("alpha");
            SecretResolver secrets = jdbcSecrets("alpha");
            JdbcSessionFactory factory = new JdbcSessionFactory(alphaProfile, secrets, 5);
            BoundedPool<JdbcSession> jdbcPool = factory.lazyPool("jdbc-analytics", 2,
                    Duration.ofSeconds(5), null, 0);
            Lease<JdbcSession> held = jdbcPool.borrow();
            Assert.assertEquals(1, fake.physicalLive(), "the lease holds one physical connection");

            List<String> factoryCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
            List<String> liveWhenBetaWasBuilt = new java.util.concurrent.CopyOnWriteArrayList<>();
            RepositoryContext[] alpha = new RepositoryContext[1];
            RepositoryManager manager = new RepositoryManager(requested -> {
                factoryCalls.add(requested.id());
                if ("beta".equals(requested.id())) {
                    liveWhenBetaWasBuilt.add("live=" + fake.physicalLive());
                }
                RepositoryContext context = "alpha".equals(requested.id())
                        ? new RepositoryContext(requested, List.of(jdbcPool))
                        : new RepositoryContext(requested);
                if ("alpha".equals(requested.id())) {
                    alpha[0] = context;
                }
                return context;
            });
            try {
                manager.switchTo(alphaProfile);
                Assert.assertEquals(CloseState.NOT_CLOSED, alpha[0].closeState(),
                        "an active context is not closing");

                RepositoryException refusal = Assert.assertThrows(RepositoryException.class,
                        () -> manager.switchTo(TestSupport.profile("beta")),
                        "a repository whose JDBC pool still holds a live leased connection must refuse the"
                                + " switch: otherwise the next repository opens connections beside it");
                Assert.assertTrue(refusal.getMessage().contains("outstanding"),
                        "the refusal names the outstanding physical resource: " + refusal.getMessage());

                Assert.assertEquals(List.of("alpha"), List.copyOf(factoryCalls),
                        "the next context's factory was NEVER invoked");
                Assert.assertThrows(RepositoryException.class,
                        () -> manager.switchTo(TestSupport.profile("beta")),
                        "and every retry is refused too, not only the first attempt");
                Assert.assertEquals(List.of("alpha"), List.copyOf(factoryCalls),
                        "no retry created anything");

                Assert.assertEquals(1, fake.physicalLive(),
                        "the leased JDBC connection is demonstrably still open");
                Assert.assertEquals(CloseState.CLOSING, jdbcPool.closeState(),
                        "the JDBC pool reports a pending shutdown, not a clean one");
                Assert.assertFalse(jdbcPool.closedWithUncertainResources(),
                        "an outstanding lease is pending, NOT uncertain");
                Assert.assertEquals(CloseState.CLOSING, alpha[0].closeState(),
                        "and the context derives the same pending state from the pool it owns");
                Assert.assertTrue(alpha[0].isDraining(), "the context knows it is still draining");
                Assert.assertEquals(RepositoryManager.Refusal.PENDING, manager.refusal().orElseThrow(),
                        "the refusal is classified as recoverable, not as a permanent leak");

                // The late return: the physical connection is released, and only NOW may a later switch run.
                held.close();
                Assert.assertEquals(CloseState.CLOSED_CLEAN, jdbcPool.closeState(),
                        "a late clean return takes the JDBC pool to terminal-clean");
                Assert.assertEquals(0, fake.physicalLive(), "and the physical connection is gone");
                Assert.assertEquals(CloseState.CLOSED_CLEAN, alpha[0].closeState(),
                        "which the context follows");

                manager.switchTo(TestSupport.profile("beta"));
                Assert.assertEquals(List.of("alpha", "beta"), List.copyOf(factoryCalls),
                        "the later switch proceeds once the JDBC resource is proven released");
                Assert.assertEquals(List.of("live=0"), List.copyOf(liveWhenBetaWasBuilt),
                        "and the next context's factory observed ZERO live JDBC connections: "
                                + liveWhenBetaWasBuilt);
            } finally {
                manager.close();
            }
        }
    }

    /**
     * A JDBC close/quarantine makes the context CLOSED_UNCERTAIN and permanently blocks an unsafe
     * replacement activation, exactly like the accepted CM-pool rule.
     *
     * <p>When the physical close throws, the exception proves nothing about the connection, so the slot stays
     * consumed and the context must report terminal uncertainty - and that latch must survive every retry.
     * A switch that proceeded here would open a new repository's connections while a connection whose fate
     * is unknown may still exist.
     */
    public void aJdbcQuarantineMakesTheContextPermanentlyBlockTheSwitch() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            RepositoryProfile alphaProfile = TestSupport.profile("alpha");
            JdbcSessionFactory factory = new JdbcSessionFactory(alphaProfile, jdbcSecrets("alpha"), 5);
            BoundedPool<JdbcSession> jdbcPool = factory.lazyPool("jdbc-analytics", 2,
                    Duration.ofSeconds(5), null, 0);
            Lease<JdbcSession> held = jdbcPool.borrow();

            List<String> factoryCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
            RepositoryContext[] alpha = new RepositoryContext[1];
            RepositoryManager manager = new RepositoryManager(requested -> {
                factoryCalls.add(requested.id());
                RepositoryContext context = "alpha".equals(requested.id())
                        ? new RepositoryContext(requested, List.of(jdbcPool))
                        : new RepositoryContext(requested);
                if ("alpha".equals(requested.id())) {
                    alpha[0] = context;
                }
                return context;
            });
            try {
                manager.switchTo(alphaProfile);
                Assert.assertThrows(RepositoryException.class,
                        () -> manager.switchTo(TestSupport.profile("beta")),
                        "the outstanding lease refuses the first switch");
                Assert.assertEquals(List.of("alpha"), List.copyOf(factoryCalls), "B was not created");

                // The physical close is now going to fail, which is the uncertain outcome.
                fake.failCloseWith(() -> FakeJdbc.rawFailure("close connection"));
                held.close();

                Assert.assertEquals(1, jdbcPool.metrics().quarantined(),
                        "a close that threw must quarantine the capacity slot");
                Assert.assertEquals(1, fake.physicalLive(),
                        "and the connection it could not prove gone is still counted as live");
                Assert.assertTrue(jdbcPool.closedWithUncertainResources(),
                        "the JDBC pool reports the uncertainty");
                Assert.assertEquals(CloseState.CLOSED_UNCERTAIN, jdbcPool.closeState(),
                        "terminal-uncertain, not clean");
                Assert.assertEquals(CloseState.CLOSED_UNCERTAIN, alpha[0].closeState(),
                        "and the context derives terminal uncertainty from the JDBC pool");

                RepositoryException retry = Assert.assertThrows(RepositoryException.class,
                        () -> manager.switchTo(TestSupport.profile("beta")),
                        "a quarantined JDBC shutdown must still refuse the retry");
                Assert.assertTrue(retry.getMessage().contains("unproven"),
                        "the refusal explains that the shutdown is unproven: " + retry.getMessage());
                Assert.assertThrows(RepositoryException.class,
                        () -> manager.switchTo(TestSupport.profile("beta")),
                        "and the refusal is permanent, not a one-off");
                Assert.assertEquals(List.of("alpha"), List.copyOf(factoryCalls),
                        "B's factory invocation count is still zero after three attempts");
                Assert.assertEquals(RepositoryManager.Refusal.UNCERTAIN, manager.refusal().orElseThrow(),
                        "the refusal is classified as permanent uncertainty");
                Assert.assertTrue(manager.diagnostics().stream().anyMatch(line -> line.contains("quarantined")),
                        "and the diagnostics name the quarantine: " + manager.diagnostics());
            } finally {
                manager.close();
            }
        }
    }

    /** The JDBC credential pair for a profile from {@link TestSupport}, resolved from an in-memory map. */
    private static SecretResolver jdbcSecrets(String repositoryId) {
        String envId = repositoryId.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
        return new SecretResolver(Map.of("JDBC_" + envId + "_USER", "jdbc-user",
                "JDBC_" + envId + "_PASSWORD", "jdbc-password"), null);
    }

    // ------------------------------------------------------------------ settings and defaults

    /**
     * The worker bound is validated against the JDBC pool size and REFUSED rather than clamped.
     *
     * <p>Not a coordinator behaviour but the same rule, and it belongs with it: a scan cannot run more
     * workers than there are connections, and reporting a parallelism the runtime cannot deliver is exactly
     * what the goal forbids. {@code StatisticsSettings.from} is the only place the pair is checked.
     */
    public void workersGreaterThanThePoolSizeIsRefusedNotClamped() throws Exception {
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty(StatisticsSettings.WORKERS_KEY, "8");
        com.mraibo.cminsight.config.AppConfig config =
                com.mraibo.cminsight.config.AppConfig.fromProperties(properties);

        com.mraibo.cminsight.config.ConfigException refusal =
                Assert.assertThrows(com.mraibo.cminsight.config.ConfigException.class,
                        () -> StatisticsSettings.from(config, 4),
                        "workers=8 over a pool of 4 must be refused");
        Assert.assertTrue(refusal.getMessage().contains(StatisticsSettings.WORKERS_KEY),
                "the refusal must name the workers key: " + refusal.getMessage());
        Assert.assertTrue(refusal.getMessage().contains("jdbc.pool.size"),
                "and the pool key that makes it impossible: " + refusal.getMessage());

        StatisticsSettings accepted = StatisticsSettings.from(config, 8);
        Assert.assertEquals(8, accepted.workers(),
                "the same configuration over a pool of 8 IS accepted, so the refusal is the pair and not the"
                        + " worker count alone");

        StatisticsSettings byDefault = StatisticsSettings.defaults(2);
        Assert.assertEquals(2, byDefault.workers(),
                "and an omitted worker count defaults to min(4, jdbc.pool.size) rather than to a refused 4");
        Assert.assertTrue(byDefault.enabled(), "statistics default to enabled");

        StatisticsSettings off = StatisticsSettings.disabled();
        Assert.assertFalse(off.enabled(), "the feature can be switched off explicitly");
        Assert.assertFalse(off.describe().contains("password"),
                "the printable settings line must not mention a credential");
    }

    /**
     * The loop above never notifies itself: a run with statistics disabled refuses a scan with UNAVAILABLE,
     * and the metadata path is untouched.
     */
    public void aDisabledFeatureRefusesAScanWithUnavailable() throws Exception {
        FakeEngine engine = new FakeEngine(LocalDate.of(2024, 6, 15));
        ScanCoordinator coordinator = new ScanCoordinator(StatisticsSettings.disabled(), REPOSITORY,
                () -> itemTypes(3), engine);
        try {
            Assert.assertEquals(ScanStartResult.UNAVAILABLE, coordinator.requestScan(),
                    "a scan request with the feature disabled is refused, not started");
            Assert.assertEquals(0, engine.anchorReads(),
                    "and nothing touches the database, so a disabled feature cannot break the repository");
            Assert.assertFalse(coordinator.availability().available(),
                    "the availability view says the feature is off");
            Assert.assertFalse(coordinator.availability().enabled(),
                    "and distinguishes 'disabled' from 'unavailable'");
        } finally {
            coordinator.close();
        }
    }
}
