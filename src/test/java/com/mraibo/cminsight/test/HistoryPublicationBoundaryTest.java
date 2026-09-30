package com.mraibo.cminsight.test;

import com.mraibo.cminsight.core.RepositoryServices;
import com.mraibo.cminsight.core.StatisticsSettings;
import com.mraibo.cminsight.history.HistoryDetail;
import com.mraibo.cminsight.history.HistoryItemType;
import com.mraibo.cminsight.history.HistoryRecorder;
import com.mraibo.cminsight.history.HistorySummary;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.repository.RepositoryState;
import com.mraibo.cminsight.statistics.ItemTypeAggregate;
import com.mraibo.cminsight.statistics.MetricValue;
import com.mraibo.cminsight.statistics.ScanCancellation;
import com.mraibo.cminsight.statistics.ScanCoordinator;
import com.mraibo.cminsight.statistics.ScanStartResult;
import com.mraibo.cminsight.statistics.ScanStatus;
import com.mraibo.cminsight.statistics.ScanWindows;
import com.mraibo.cminsight.statistics.StatisticsEngine;
import com.mraibo.cminsight.statistics.StatisticsService;
import com.mraibo.cminsight.statistics.StatisticsSnapshot;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Goal 04 section 12 "Cache/history": the publication boundary between a completed full scan and persistent
 * history.
 *
 * <h2>Why the boundary is asserted at the coordinator and not at a store</h2>
 *
 * <p>The goal states four rules that are really one rule about WHERE history is written from: only a full
 * scan that reached normal terminal completion and published a snapshot may be recorded, so a TIMED_OUT,
 * CANCELLED or catastrophic scan creates zero rows, while a completed scan with per-ItemType errors is
 * recorded with its exact coverage. Calling a recorder directly would restate the implementation; driving
 * the real {@link ScanCoordinator} through those outcomes and counting what a
 * {@link RecordingHistoryStore} received is what makes the rule observable.
 *
 * <p>The other half is containment: history is a local, optional feature, so neither an unavailable store
 * nor a throwing one may fail a scan, refuse a repository activation or replace a published snapshot.
 */
public class HistoryPublicationBoundaryTest {

    private static final LocalDate ANCHOR = LocalDate.of(2024, 6, 15);

    // ------------------------------------------------------------------ publication boundary

    /** A normally completed scan is recorded exactly once, with the identity it was captured under. */
    public void onlyACompletedFullScanReachesHistory() throws Exception {
        RecordingHistoryStore store = new RecordingHistoryStore();
        HistoryRecorder recorder = HistoryRecorder.forRepository(store, "alpha", "Repository alpha", "DB2");

        CompletedScan scan = runCompletedScan(recorder, "alpha", List.of(summary(1, "A", "SAP")), 420L);

        Assert.assertEquals(1, store.all().size(),
                "a completed full scan must be persisted exactly once, but the store holds "
                        + store.all().size() + " entr(ies)");
        Assert.assertEquals(1, store.recordCalls(), "and the store must be asked exactly once");

        HistoryDetail stored = store.all().get(0);
        HistorySummary row = stored.summary();
        Assert.assertEquals("alpha", row.repositoryId(), "the stored row belongs to the scanning repository");
        Assert.assertEquals("Repository alpha", row.repositoryDisplayName(),
                "and carries the safe display identity captured with it");
        Assert.assertEquals("DB2", row.databaseVendor(), "and the database vendor");
        Assert.assertEquals(1, row.itemTypeCount(), "and the frozen ItemType count");
        Assert.assertEquals(0, row.partialFailureCount(), "a complete scan has no partial failures");
        Assert.assertTrue(row.complete(), "and is stored as complete");
        Assert.assertEquals(420L, row.logicalItemsTotal(),
                "and carries the published total, not a re-derived one");
        Assert.assertEquals(scan.snapshot().capturedAt(), row.capturedAt(),
                "the capture instant is the snapshot's own, so a history row cannot be timed differently"
                        + " from the scan it describes");
        Assert.assertTrue(row.anchor().isPresent(), "and the single anchor is captured with it");
    }

    /** A scan that passes its overall deadline is recorded as NOTHING: no snapshot, no row. */
    public void aTimedOutScanCreatesNoHistoryRow() throws Exception {
        RecordingHistoryStore store = new RecordingHistoryStore();
        HistoryRecorder recorder = HistoryRecorder.forRepository(store, "alpha", "Repository alpha", "DB2");

        CountDownLatch gate = new CountDownLatch(1);
        StatisticsEngine blocking = new StatisticsEngine() {
            @Override
            public LocalDate databaseCurrentDate(ScanCancellation cancellation) {
                return ANCHOR;
            }

            @Override
            public ItemTypeAggregate aggregate(ItemTypeSummary itemType, ScanWindows windows,
                                               ScanCancellation cancellation) throws Exception {
                cancellation.register(gate::countDown);
                gate.await(30, TimeUnit.SECONDS);
                if (cancellation.isCancelled()) {
                    throw new InterruptedException("the scan deadline expired");
                }
                return measure(5L);
            }
        };
        StatisticsSettings shortDeadline = new StatisticsSettings(true, 1, Duration.ofSeconds(2),
                Duration.ofSeconds(1));
        try (ScanCoordinator coordinator = newCoordinator(shortDeadline, "alpha", List.of(summary(1, "A", "SAP")),
                blocking, recorder)) {
            coordinator.requestScan();
            Assert.assertTrue(coordinator.awaitScanCompletion(Duration.ofSeconds(25)),
                    "the unresponsive scan must be terminated by its deadline for this case to mean anything");
            Assert.assertEquals(ScanStatus.Phase.TIMED_OUT, coordinator.progress().phase(),
                    "the scan genuinely timed out: " + coordinator.progress());
            Assert.assertTrue(coordinator.snapshot().isEmpty(), "and published nothing");
        }
        Assert.assertEquals(0, store.recordCalls(),
                "a TIMED_OUT scan must create ZERO history rows, but the store was asked to record "
                        + store.recordCalls() + " time(s)");
        Assert.assertTrue(store.all().isEmpty(), "and must leave no row behind: " + store.all());
    }

    /** A cancelled scan (a switch, a shutdown or an operator) is recorded as NOTHING either. */
    public void aCancelledScanCreatesNoHistoryRow() throws Exception {
        RecordingHistoryStore store = new RecordingHistoryStore();
        HistoryRecorder recorder = HistoryRecorder.forRepository(store, "alpha", "Repository alpha", "DB2");

        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        StatisticsEngine blocking = new StatisticsEngine() {
            @Override
            public LocalDate databaseCurrentDate(ScanCancellation cancellation) {
                return ANCHOR;
            }

            @Override
            public ItemTypeAggregate aggregate(ItemTypeSummary itemType, ScanWindows windows,
                                               ScanCancellation cancellation) throws Exception {
                cancellation.register(gate::countDown);
                parked.countDown();
                gate.await(30, TimeUnit.SECONDS);
                if (cancellation.isCancelled()) {
                    throw new InterruptedException("the scan was cancelled");
                }
                return measure(5L);
            }
        };
        try (ScanCoordinator coordinator = newCoordinator(settings(1), "alpha", List.of(summary(1, "A", "SAP")),
                blocking, recorder)) {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the scan starts");
            Assert.assertTrue(parked.await(10, TimeUnit.SECONDS), "the query reached its parked state");
            Assert.assertTrue(coordinator.cancelScan(), "the running scan is signalled");
            Assert.assertTrue(coordinator.awaitScanCompletion(Duration.ofSeconds(25)),
                    "the cancelled scan reaches a terminal state");
            Assert.assertEquals(ScanStatus.Phase.CANCELLED, coordinator.progress().phase(),
                    "the scan is CANCELLED: " + coordinator.progress());
        }
        Assert.assertEquals(0, store.recordCalls(),
                "a CANCELLED scan must create ZERO history rows, but the store was asked to record "
                        + store.recordCalls() + " time(s)");
        Assert.assertTrue(store.all().isEmpty(), "and must leave no row behind: " + store.all());
    }

    /**
     * A completed scan with a failed ItemType IS recorded, with its exact coverage and a sanitized reason.
     *
     * <p>The goal allows this case explicitly and requires the coverage to travel with it, because a stored
     * partial total rendered later as a complete one is the defect that rule exists to prevent.
     */
    public void aPartialFailureCompletedScanPersistsItsExactCoverage() throws Exception {
        RecordingHistoryStore store = new RecordingHistoryStore();
        HistoryRecorder recorder = HistoryRecorder.forRepository(store, "alpha", "Repository alpha", "DB2");

        String secret = "password=hunter2 jdbc:db2://db.example:50000/ICMADMIN";
        StatisticsEngine engine = new StatisticsEngine() {
            @Override
            public LocalDate databaseCurrentDate(ScanCancellation cancellation) {
                return ANCHOR;
            }

            @Override
            public ItemTypeAggregate aggregate(ItemTypeSummary itemType, ScanWindows windows,
                                               ScanCancellation cancellation) {
                if (itemType.itemTypeId() == 3) {
                    throw new IllegalStateException(secret);
                }
                return measure(itemType.itemTypeId() == 1 ? 100L : 250L);
            }
        };

        StatisticsSnapshot snapshot;
        try (ScanCoordinator coordinator = newCoordinator(settings(3), "alpha",
                List.of(summary(1, "A", "SAP"), summary(2, "B", "SAP"), summary(3, "C", "SAP")),
                engine, recorder)) {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the scan starts");
            Assert.assertTrue(coordinator.awaitScanCompletion(Duration.ofSeconds(20)), "the scan ends");
            snapshot = coordinator.snapshot().orElseThrow();
            Assert.assertFalse(snapshot.complete(),
                    "the case under test is a COMPLETED scan with partial failures, so the snapshot must be"
                            + " an incomplete one");
        }

        Assert.assertEquals(1, store.all().size(), "the completed scan IS persisted");
        HistorySummary row = store.all().get(0).summary();
        Assert.assertEquals(3, row.itemTypeCount(), "with the exact frozen ItemType count");
        Assert.assertEquals(1, row.partialFailureCount(), "and the exact partial-failure count");
        Assert.assertFalse(row.complete(), "and it must NOT be stored as complete");
        Assert.assertEquals(snapshot.totals().logicalItemsTotal(), row.logicalItemsTotal(),
                "and the total it published, not a recomputed or completed one");
        Assert.assertEquals(350L, row.logicalItemsTotal(),
                "the measured ItemTypes contribute 100 + 250 and the failed one contributes nothing, so the"
                        + " stored total must be exactly 350 rather than a full-looking number");

        HistoryDetail stored = store.all().get(0);
        Assert.assertEquals(3, stored.itemTypes().size(),
                "every frozen ItemType has a stored row, failed ones included");
        HistoryItemType failed = stored.itemTypeById(3);
        Assert.assertNotNull(failed, "the failed ItemType must be stored, not omitted");
        Assert.assertTrue(failed.failed(), "and stored as failed");
        Assert.assertFalse(failed.reason().contains("hunter2"),
                "the stored reason must be sanitized: no exception message may reach storage. Reason: "
                        + failed.reason());
        Assert.assertFalse(failed.reason().contains("jdbc:db2"),
                "and no JDBC URL either: " + failed.reason());
    }

    // ------------------------------------------------------------------ repository isolation

    /**
     * Two repositories sharing one history store never see each other's rows.
     *
     * <p>History is application-local and outlives a repository switch (Goal 04 section 10), so a store that
     * ignored the repository identity would mix two repositories' snapshots into one timeline - and an
     * operator reading "yesterday's totals" could be shown another repository's numbers.
     */
    public void twoRepositoriesNeverCrossContaminateHistory() throws Exception {
        RecordingHistoryStore store = new RecordingHistoryStore();
        HistoryRecorder alpha = HistoryRecorder.forRepository(store, "alpha", "Repository alpha", "DB2");
        HistoryRecorder beta = HistoryRecorder.forRepository(store, "beta", "Repository beta", "ORACLE");

        runCompletedScan(alpha, "alpha", List.of(summary(1, "A", "SAP")), 111L);
        runCompletedScan(beta, "beta", List.of(summary(2, "B", "NON-SAP")), 222L);

        Assert.assertEquals(2, store.all().size(), "both completed scans are stored, but the store was asked to"
                + " record " + store.recordCalls() + " time(s) and holds " + store.all().size() + " row(s)"
                + " for repositories " + store.all().stream()
                .map(detail -> detail.summary().repositoryId()).toList());
        Assert.assertEquals(1, store.forRepository("alpha").size(), "alpha has exactly its own row");
        Assert.assertEquals(1, store.forRepository("beta").size(), "beta has exactly its own row");
        Assert.assertEquals("Repository alpha", store.forRepository("alpha").get(0).summary().displayName(),
                "and alpha's row carries alpha's display identity");
        Assert.assertEquals("ORACLE", store.forRepository("beta").get(0).summary().databaseVendor(),
                "and beta's row carries beta's vendor");
        Assert.assertEquals(111L, store.forRepository("alpha").get(0).summary().logicalItemsTotal(),
                "and alpha's own total, not the other repository's");
        Assert.assertEquals(222L, store.forRepository("beta").get(0).summary().logicalItemsTotal(),
                "and beta's own total");

        List<HistorySummary> alphaPage = store.list("alpha", 10);
        Assert.assertEquals(1, alphaPage.size(), "a repository-scoped list returns only that repository");
        Assert.assertTrue(alphaPage.get(0).belongsTo("alpha"),
                "and every row it returns belongs to it: " + alphaPage);
        Assert.assertEquals(1L, store.count("alpha"), "and the counted bound is per repository");
    }

    /**
     * A recorder bound to one repository REFUSES a snapshot of another repository.
     *
     * <p>This is the cross-contamination defence at its earliest point: before anything reaches storage, the
     * pairing "this recorder, that snapshot" is checked. Without it, a mis-wired context (a recorder built
     * once and reused across a switch) would file one repository's totals under another repository's rows -
     * and the store would faithfully keep them.
     */
    public void aRecorderRefusesASnapshotFromAnotherRepository() throws Exception {
        RecordingHistoryStore store = new RecordingHistoryStore();
        HistoryRecorder alpha = HistoryRecorder.forRepository(store, "alpha", "Repository alpha", "DB2");

        // Run a scan for BETA but hand its publication to ALPHA's recorder: the exact mis-wiring a switch
        // could produce.
        CompletedScan scan = runCompletedScan(alpha, "beta", List.of(summary(2, "B", "NON-SAP")), 222L);
        Assert.assertEquals("beta", scan.snapshot().repositoryId(),
                "the scan really produced a beta snapshot, so the case under test is a real mismatch");

        Assert.assertTrue(store.all().isEmpty(),
                "a snapshot of another repository must NOT be stored by this repository's recorder: "
                        + store.all());
        Assert.assertEquals(0, store.recordCalls(),
                "and the store must never even be asked to write it");
        Assert.assertThrows(IllegalArgumentException.class, () -> alpha.capture(scan.snapshot()),
                "the capture itself must refuse the mismatch loudly, which is what makes the silent refusal at"
                        + " the publication path safe rather than a swallowed wiring bug");
    }
    // ------------------------------------------------------------------ containment

    /**
     * An unavailable history store leaves a scan, a published snapshot and a repository activation working.
     *
     * <p>This is the goal's "missing H2" case at the level the application can observe: history reports
     * itself unavailable with a fixed reason, records nothing, and the statistics half - which is what a
     * repository needs in order to activate - is completely unaffected.
     */
    public void anUnavailableHistoryStoreNeverBreaksTheScanOrTheRepository() throws Exception {
        RecordingHistoryStore store = new RecordingHistoryStore()
                .unavailable("the local history database driver is not installed");
        HistoryRecorder recorder = HistoryRecorder.forRepository(store, "alpha", "Repository alpha", "DB2");

        Assert.assertFalse(store.available(), "the store must report itself unavailable");
        Assert.assertTrue(store.unavailableReason().isPresent(), "with a fixed reason");

        CompletedScan scan = runCompletedScan(recorder, "alpha", List.of(summary(1, "A", "SAP")), 77L);
        Assert.assertTrue(scan.snapshot().complete(), "the scan still completed normally");
        Assert.assertTrue(store.all().isEmpty(), "and history holds nothing, which is the honest state");
        Assert.assertTrue(store.list("alpha", 10).isEmpty(), "reading it answers empty rather than failing");
        Assert.assertEquals(0L, store.count("alpha"), "and the count is zero");

        // The repository itself must still activate, over the analytics service that would have run that
        // scan: an optional local feature may never become an activation requirement.
        try (ScanCoordinator coordinator = newCoordinator(settings(1), "alpha", List.of(summary(1, "A", "SAP")),
                completeEngine(), recorder)) {
            StatisticsService service = StatisticsService.of("alpha", coordinator, null);
            RepositoryManager manager = new RepositoryManager(requested -> new RepositoryContext(requested,
                    List.of(), new RepositoryServices(null, null, null, service)));
            try {
                manager.switchTo(TestSupport.profile("alpha"));
                Assert.assertEquals(RepositoryState.ACTIVE, manager.state(),
                        "a repository must activate even though history is unavailable: history is an"
                                + " optional local feature, not an activation requirement");
                Assert.assertTrue(manager.activeContext().isPresent(), "and its context is published");
                Assert.assertTrue(manager.activeContext().orElseThrow().services().statisticsRepository()
                                .isPresent(),
                        "with its analytics service still attached");
            } finally {
                manager.close();
            }
        }
    }

    /**
     * A history store that THROWS cannot fail a scan or replace a published snapshot.
     *
     * <p>History is written from the publication path, so an exception escaping it would turn a successful
     * scan into a failed one - converting a best-effort local feature into a failure of the core path.
     */
    public void aThrowingHistoryStoreCannotFailTheScan() throws Exception {
        RecordingHistoryStore store = new RecordingHistoryStore().throwingWrites();
        HistoryRecorder recorder = HistoryRecorder.forRepository(store, "alpha", "Repository alpha", "DB2");

        CompletedScan scan = runCompletedScan(recorder, "alpha", List.of(summary(1, "A", "SAP")), 33L);

        Assert.assertEquals(ScanStatus.Phase.COMPLETED, scan.phase(),
                "the scan completed and published normally: " + scan.phase());
        Assert.assertTrue(scan.snapshot().complete(), "with a complete snapshot");
        Assert.assertTrue(store.recordCalls() >= 1,
                "the store really was asked to write, so the failing path was exercised");
        Assert.assertTrue(store.all().isEmpty(), "and nothing became visible");
    }

    // ------------------------------------------------------------------ fixtures

    /** What one completed scan produced, so a case can assert on both the snapshot and its phase. */
    private record CompletedScan(StatisticsSnapshot snapshot, ScanStatus.Phase phase) {
    }

    private static StatisticsSettings settings(int workers) {
        return new StatisticsSettings(true, workers, Duration.ofSeconds(5), Duration.ofSeconds(20));
    }

    private static ScanCoordinator newCoordinator(StatisticsSettings settings, String repositoryId,
                                               List<ItemTypeSummary> itemTypes, StatisticsEngine engine,
                                               HistoryRecorder recorder) {
        return new ScanCoordinator(settings, repositoryId, () -> itemTypes, engine, null, "", recorder::record);
    }

    /** Runs one scan to normal completion and returns what it published. */
    private static CompletedScan runCompletedScan(HistoryRecorder recorder, String repositoryId,
                                                  List<ItemTypeSummary> itemTypes, long total)
            throws Exception {
        try (ScanCoordinator coordinator = newCoordinator(settings(Math.max(1, itemTypes.size())), repositoryId,
                itemTypes, completeEngine(total), recorder)) {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the scan starts");
            Assert.assertTrue(coordinator.awaitScanCompletion(Duration.ofSeconds(20)),
                    "the scan reaches a terminal state");
            Assert.assertEquals(ScanStatus.Phase.COMPLETED, coordinator.progress().phase(),
                    "the scan must complete normally for this case: " + coordinator.progress());
            return new CompletedScan(coordinator.snapshot().orElseThrow(), coordinator.progress().phase());
        }
    }

    private static StatisticsEngine completeEngine() {
        return completeEngine(10L);
    }

    private static StatisticsEngine completeEngine(long total) {
        return new StatisticsEngine() {
            @Override
            public LocalDate databaseCurrentDate(ScanCancellation cancellation) {
                return ANCHOR;
            }

            @Override
            public ItemTypeAggregate aggregate(ItemTypeSummary itemType, ScanWindows windows,
                                               ScanCancellation cancellation) {
                return measure(total);
            }
        };
    }

    private static ItemTypeAggregate measure(long total) {
        return new ItemTypeAggregate(total, MetricValue.available(1L), MetricValue.available(2L),
                MetricValue.available(3L), MetricValue.available(4L));
    }

    private static ItemTypeSummary summary(int id, String name, String classification) {
        return new ItemTypeSummary(name, "description of " + name, id, "RAW_" + classification,
                classification, "");
    }
}




