package com.mraibo.cminsight.test;

import com.mraibo.cminsight.core.StatisticsSettings;
import com.mraibo.cminsight.history.HistoryRecorder;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.statistics.AnalyticsOperationGate;
import com.mraibo.cminsight.statistics.FreshnessThreshold;
import com.mraibo.cminsight.statistics.ItemTypeAggregate;
import com.mraibo.cminsight.statistics.MetricValue;
import com.mraibo.cminsight.statistics.ScanCancellation;
import com.mraibo.cminsight.statistics.ScanCoordinator;
import com.mraibo.cminsight.statistics.ScanStartResult;
import com.mraibo.cminsight.statistics.ScanStatus;
import com.mraibo.cminsight.statistics.ScanWindows;
import com.mraibo.cminsight.statistics.StatisticsEngine;
import com.mraibo.cminsight.statistics.StatisticsSnapshot;
import com.mraibo.cminsight.statistics.TargetedItemTypeDetail;
import com.mraibo.cminsight.statistics.TargetedRefreshResult;
import com.mraibo.cminsight.statistics.TargetedRefreshService;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Goal 04 section 5 and section 12 "Targeted refresh": one exclusive analytics operation, one anchor, and a
 * detail result that can never become a snapshot.
 *
 * <h2>Why the strongest assertion here is a reference comparison</h2>
 *
 * <p>"A targeted refresh never changes the full snapshot, its totals or history" is easy to assert against
 * the code - and that would prove nothing, because the interesting failure is a targeted value quietly
 * folded into the dashboard. The assertion is therefore a direct before/after comparison of the PUBLISHED
 * object: the same {@link StatisticsSnapshot} reference, the same totals object, the same scan identity and
 * the same history row, with the targeted value published only in its own detail cache.
 *
 * <h2>No sleeps</h2>
 *
 * <p>Every concurrency case parks a worker on a latch so an interleaving is deterministic, and every await
 * is bounded only as a failure deadline.
 */
public class TargetedRefreshConcurrencyTest {

    private static final LocalDate ANCHOR = LocalDate.of(2024, 6, 15);

    // ------------------------------------------------------------------ mutual exclusion

    /** A targeted refresh cannot overlap a full scan: the shared gate holds for the scan's whole drain. */
    public void aTargetedRefreshCannotOverlapAFullScan() throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Two engines on purpose: the scan's own workers keep running concurrently with the refused attempt,
        // so "the refused attempt ran no aggregate" has to be read from the targeted side's OWN instrument.
        // A shared counter would race with the second scan worker - measured, not assumed.
        CountingEngine scanEngine = new CountingEngine().parkAggregate(parked, release, null);
        CountingEngine targetedEngine = new CountingEngine();
        AnalyticsOperationGate gate = new AnalyticsOperationGate();

        try (ScanCoordinator coordinator = gatedCoordinator(gate, scanEngine);
             TargetedRefreshService targeted = targetedService(gate, targetedEngine)) {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the scan starts");
            Assert.assertTrue(parked.await(10, TimeUnit.SECONDS), "the scan's query is parked");
            Assert.assertTrue(coordinator.isScanInFlight(), "and the scan is genuinely in flight");

            TargetedRefreshResult refused = targeted.refresh(1);
            Assert.assertEquals(TargetedRefreshResult.Outcome.REFUSED, refused.outcome(),
                    "a targeted refresh must be refused while a full scan holds the analytics gate, but the"
                            + " attempt reported " + refused.outcome() + " (" + refused.reason() + ")");
            Assert.assertEquals(Optional.of(AnalyticsOperationGate.Operation.FULL_SCAN),
                    refused.refusedByOperation(),
                    "and the refusal must name the operation that holds the gate");
            Assert.assertTrue(targeted.detail(1).isEmpty(), "nothing may be published by a refused attempt");
            Assert.assertTrue(targetedEngine.aggregateCalls.isEmpty(),
                    "and a refused attempt must run NO aggregate: the gate decides admission before any"
                            + " database work. It ran " + targetedEngine.aggregateCalls);
            Assert.assertEquals(0, targetedEngine.anchorCalls.get(),
                    "and must not even read a database anchor");

            release.countDown();
            Assert.assertTrue(coordinator.awaitScanCompletion(Duration.ofSeconds(20)),
                    "the parked scan is released and finishes");
            Assert.assertEquals(ScanStatus.Phase.COMPLETED, coordinator.progress().phase(), "and completes");

            // Positive control: the gate really is free again, so the refusal above was the gate and not a
            // service that refuses everything.
            TargetedRefreshResult admitted = targeted.refresh(1);
            Assert.assertEquals(TargetedRefreshResult.Outcome.REFRESHED, admitted.outcome(),
                    "once the scan has drained, the same targeted refresh must be admitted: " + admitted);
            Assert.assertEquals(1, targetedEngine.anchorCalls.get(),
                    "and it reads its own single anchor, on its own engine");
        }
    }

    /** Two targeted refreshes cannot overlap each other either. */
    public void twoTargetedRefreshesCannotOverlap() throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountingEngine engine = new CountingEngine().parkAggregate(parked, release, null);
        AnalyticsOperationGate gate = new AnalyticsOperationGate();

        try (TargetedRefreshService targeted = targetedService(gate, engine)) {
            AtomicReference<TargetedRefreshResult> first = new AtomicReference<>();
            Thread worker = new Thread(() -> first.set(targeted.refresh(1)), "targeted-refresh-test");
            worker.setDaemon(true);
            worker.start();

            Assert.assertTrue(parked.await(10, TimeUnit.SECONDS), "the first refresh is parked in its query");
            int aggregatesBefore = engine.aggregateCalls.size();

            TargetedRefreshResult second = targeted.refresh(2);
            Assert.assertEquals(TargetedRefreshResult.Outcome.REFUSED, second.outcome(),
                    "a second targeted refresh must not overlap the first, but it reported " + second.outcome());
            Assert.assertEquals(Optional.of(AnalyticsOperationGate.Operation.TARGETED_REFRESH),
                    second.refusedByOperation(),
                    "and the refusal names the targeted refresh holding the gate");
            Assert.assertTrue(targeted.detail(2).isEmpty(),
                    "the refused attempt must publish no detail for its own ItemType");
            Assert.assertEquals(aggregatesBefore, engine.aggregateCalls.size(),
                    "and must run no aggregate of its own");

            release.countDown();
            worker.join(20_000L);
            Assert.assertFalse(worker.isAlive(), "the first refresh must finish once its query is released");
            Assert.assertNotNull(first.get(), "the first refresh must have produced a result");
            Assert.assertEquals(TargetedRefreshResult.Outcome.REFRESHED, first.get().outcome(),
                    "the admitted refresh completes normally: " + first.get());
            Assert.assertTrue(targeted.detail(1).isPresent(),
                    "and publishes exactly its own detail result");
            Assert.assertEquals(1, targeted.detailCount(), "with nothing else published");
        }
    }

    // ------------------------------------------------------------------ one anchor, accepted counting

    /** A targeted refresh reads exactly one anchor and runs exactly one aggregate for the one ItemType. */
    public void aTargetedRefreshUsesOneAnchorAndTheAcceptedCountingSemantics() throws Exception {
        CountingEngine engine = new CountingEngine();
        AnalyticsOperationGate gate = new AnalyticsOperationGate();

        try (TargetedRefreshService targeted = targetedService(gate, engine)) {
            TargetedRefreshResult result = targeted.refresh(2);

            Assert.assertEquals(TargetedRefreshResult.Outcome.REFRESHED, result.outcome(),
                    "the attempt must succeed: " + result.reason());
            Assert.assertEquals(1, engine.anchorCalls.get(),
                    "a targeted refresh reads exactly ONE database anchor, but it read " + engine.anchorCalls);
            Assert.assertEquals(List.of(2), List.copyOf(engine.aggregatedItemTypeIds),
                    "and runs exactly one aggregate, for exactly the requested ItemType: "
                            + engine.aggregatedItemTypeIds);

            TargetedItemTypeDetail detail = result.detailOption().orElseThrow();
            Assert.assertEquals(2, detail.itemTypeId(), "the published detail is keyed by the ItemType id");
            Assert.assertEquals("repo", detail.repositoryId(), "and belongs to the active repository");
            Assert.assertEquals(ANCHOR, detail.anchorDate(), "and carries the one anchor it was measured on");
            Assert.assertEquals("targeted-detail", detail.kind(),
                    "and declares itself targeted detail rather than leaving a renderer to infer it");
            Assert.assertTrue(detail.statistics().logicalItems().isAvailable(),
                    "with the measured logical-item total");
            Assert.assertEquals(1234L, detail.statistics().logicalItems().valueOrZero(),
                    "which is the engine's distinct-ItemID count, not a recomputed number");
            Assert.assertFalse(detail.statistics().versions().isAvailable(),
                    "and Versions/Parts stay UNAVAILABLE exactly as they are in a full scan");
            Assert.assertEquals(1, targeted.detailCount(), "exactly one detail is published");
            Assert.assertTrue(targeted.detail(2).isPresent(), "and it is addressable by ItemType id");
            Assert.assertFalse(gate.isHeld(), "and the gate is released when the attempt returns");
        }

        // A second attempt for a different ItemType reads its own single anchor.
        try (TargetedRefreshService targeted = targetedService(gate, engine)) {
            Assert.assertEquals(TargetedRefreshResult.Outcome.REFRESHED, targeted.refresh(3).outcome(),
                    "a second attempt succeeds");
            Assert.assertEquals(2, engine.anchorCalls.get(),
                    "and reads one more anchor for its own operation: one anchor PER operation, not one"
                            + " cached anchor reused across operations");
            Assert.assertEquals(List.of(2, 3), List.copyOf(engine.aggregatedItemTypeIds),
                    "measuring only the requested ItemType each time");
        }
    }

    // ------------------------------------------------------------------ the cardinal rule

    /**
     * The published full snapshot, its totals, its scan identity and history are untouched by a targeted
     * refresh - compared directly, before and after.
     */
    public void aTargetedResultNeverChangesTheFullSnapshotItsTotalsOrHistory() throws Exception {
        RecordingHistoryStore history = new RecordingHistoryStore();
        HistoryRecorder recorder = HistoryRecorder.forRepository(history, "repo", "Repository repo", "DB2");
        AnalyticsOperationGate gate = new AnalyticsOperationGate();
        CountingEngine engine = new CountingEngine();

        try (ScanCoordinator coordinator = gatedCoordinator(gate, engine, recorder);
             TargetedRefreshService targeted = targetedService(gate, engine)) {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the full scan starts");
            Assert.assertTrue(coordinator.awaitScanCompletion(Duration.ofSeconds(20)), "and ends");

            StatisticsSnapshot before = coordinator.snapshot().orElseThrow();
            Assert.assertTrue(before.complete(), "the full snapshot is complete");
            long totalsBefore = before.totals().logicalItemsTotal();
            int historyRowsBefore = history.all().size();
            long historyTotalBefore = history.all().get(0).summary().logicalItemsTotal();
            Assert.assertEquals(1, historyRowsBefore, "the completed scan is the one history row");

            // The targeted measurement returns a deliberately different number, so a leak into the totals
            // would change the observed value rather than merely being possible.
            engine.targetedLogicalItems = 999_999L;
            TargetedRefreshResult result = targeted.refresh(2);
            Assert.assertEquals(TargetedRefreshResult.Outcome.REFRESHED, result.outcome(),
                    "the targeted refresh must run for this case to mean anything: " + result.reason());

            StatisticsSnapshot after = coordinator.snapshot().orElseThrow();
            Assert.assertTrue(after == before,
                    "a targeted refresh must NOT replace the published snapshot: the published object must"
                            + " be the very same reference before and after");
            Assert.assertTrue(after.totals() == before.totals(),
                    "and must not recalculate the totals: the totals object is unchanged");
            Assert.assertEquals(totalsBefore, after.totals().logicalItemsTotal(),
                    "so the dashboard total is still the full scan's");
            Assert.assertEquals(before.scanId(), after.scanId(),
                    "and the scan identity is unchanged, so the snapshot still says which scan produced it");
            Assert.assertEquals(1L, after.scanId(), "which is the first scan, not a synthetic second one");
            Assert.assertFalse(coordinator.isScanInFlight(),
                    "and no scan was started as a side effect of the targeted refresh");
            Assert.assertEquals(1, history.all().size(),
                    "a targeted result is NEVER persisted as a full history snapshot");
            Assert.assertEquals(historyTotalBefore, history.all().get(0).summary().logicalItemsTotal(),
                    "and the stored history row is untouched");
            Assert.assertEquals(before.capturedAt(), history.all().get(0).summary().capturedAt(),
                    "with its original capture instant");

            TargetedItemTypeDetail detail = result.detailOption().orElseThrow();
            Assert.assertEquals(999_999L, detail.statistics().logicalItems().valueOrZero(),
                    "the targeted value is published - in its OWN detail result");
            Assert.assertTrue(detail.capturedAt().isAfter(before.capturedAt())
                            || !detail.capturedAt().isBefore(before.capturedAt()),
                    "which carries its own capture instant rather than borrowing the snapshot's");
            Assert.assertTrue(targeted.detail(2).isPresent(), "and is addressable as targeted detail data");
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Closing the context cancels an in-flight targeted refresh, drains it, and frees the gate. */
    public void aRepositoryCloseCancelsAndDrainsATargetedRefresh() throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch abortReached = new CountDownLatch(1);
        AnalyticsOperationGate gate = new AnalyticsOperationGate();
        CountingEngine engine = new CountingEngine()
                .parkAggregate(parked, null, abortReached);

        TargetedRefreshService targeted = targetedService(gate, engine);
        AtomicReference<TargetedRefreshResult> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> outcome.set(targeted.refresh(1)), "targeted-close-test");
        worker.setDaemon(true);
        worker.start();

        Assert.assertTrue(parked.await(10, TimeUnit.SECONDS), "the refresh is parked inside its query");
        Assert.assertTrue(gate.isHeld(), "and it holds the shared gate while it runs");

        targeted.close();
        Assert.assertTrue(abortReached.await(10, TimeUnit.SECONDS),
                "closing the context must reach the engine's registered abort action, which is how a driver"
                        + " statement is cancelled");
        worker.join(20_000L);
        Assert.assertFalse(worker.isAlive(), "and the attempt must finish rather than outlive the close");
        Assert.assertNotNull(outcome.get(), "the attempt must return a result rather than hang");
        Assert.assertFalse(outcome.get().outcome() == TargetedRefreshResult.Outcome.REFRESHED,
                "a cancelled attempt must not publish a successful result: " + outcome.get());
        Assert.assertEquals(0, targeted.detailCount(), "and must publish no detail");
        Assert.assertFalse(gate.isHeld(),
                "the gate must be released by the attempt that held it, so the next repository can acquire"
                        + " it");

        TargetedRefreshResult afterClose = targeted.refresh(1);
        Assert.assertEquals(TargetedRefreshResult.Outcome.CLOSED, afterClose.outcome(),
                "and a refresh requested after the close is refused as CLOSED rather than starting work");
    }

    /**
     * A result from a replaced repository context cannot publish into the new one.
     *
     * <p>Goal 04 section 10: on a switch the old context's detail cache is discarded and no old-context
     * refresh may reach the new context. The new context owns a NEW gate and a NEW service, so the old
     * attempt has nothing shared left to publish into - and this asserts exactly that, rather than trusting
     * that it is so.
     */
    public void aStaleOldGenerationResultCannotPublishIntoANewContext() throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch abortReached = new CountDownLatch(1);
        AnalyticsOperationGate oldGate = new AnalyticsOperationGate();
        CountingEngine oldEngine = new CountingEngine().parkAggregate(parked, null, abortReached);

        TargetedRefreshService oldContext = targetedService(oldGate, oldEngine);
        AtomicReference<TargetedRefreshResult> oldOutcome = new AtomicReference<>();
        Thread oldWorker = new Thread(() -> oldOutcome.set(oldContext.refresh(1)), "old-context-refresh");
        oldWorker.setDaemon(true);
        oldWorker.start();
        Assert.assertTrue(parked.await(10, TimeUnit.SECONDS), "the old-context refresh is parked");

        // The switch: the old context is closed and replaced, exactly as Goal 03B orders it.
        oldContext.close();
        Assert.assertTrue(abortReached.await(10, TimeUnit.SECONDS), "the old attempt is aborted");

        AnalyticsOperationGate newGate = new AnalyticsOperationGate();
        CountingEngine newEngine = new CountingEngine();
        try (TargetedRefreshService newContext = targetedService(newGate, newEngine)) {
            oldWorker.join(20_000L);
            Assert.assertFalse(oldWorker.isAlive(), "the old attempt finishes");
            Assert.assertFalse(oldOutcome.get().outcome() == TargetedRefreshResult.Outcome.REFRESHED,
                    "an attempt whose context was replaced must not report a published result: : " + oldOutcome.get());

            Assert.assertEquals(0, newContext.detailCount(),
                    "and nothing from the old generation may appear in the new context's detail cache");
            Assert.assertTrue(newContext.detail(1).isEmpty(),
                    "neither by ItemType id nor by enumeration");
            Assert.assertTrue(newContext.details().isEmpty(), "the new context's cache is empty");
            Assert.assertFalse(newGate.isHeld(),
                    "and the old attempt must not have left the NEW context's gate held: one gate per context"
                            + " is what makes a stale release harmless");
            Assert.assertTrue(newGate.currentOperation().isEmpty(),
                    "with no operation recorded on it either");
            Assert.assertTrue(newEngine.aggregateCalls.isEmpty(),
                    "and the new context's engine was never asked to measure anything by the old attempt");

            Assert.assertEquals(TargetedRefreshResult.Outcome.REFRESHED, newContext.refresh(1).outcome(),
                    "while the new context can serve its own refresh normally");
            Assert.assertEquals(1, newContext.detailCount(), "publishing into its own cache");
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** An engine that records what it was asked to do and can park a query on a latch. */
    private static final class CountingEngine implements StatisticsEngine {

        private final List<Integer> aggregatedItemTypeIds =
                Collections.synchronizedList(new ArrayList<>());
        private final java.util.concurrent.atomic.AtomicInteger anchorCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        private final List<Integer> aggregateCalls = Collections.synchronizedList(new ArrayList<>());

        private volatile long targetedLogicalItems = 1234L;

        private CountDownLatch parked;
        private CountDownLatch wake;
        private CountDownLatch abortReached;

        CountingEngine parkAggregate(CountDownLatch parked, CountDownLatch release,
                                     CountDownLatch abortReached) {
            this.parked = parked;
            this.abortReached = abortReached;
            // Exactly one latch to wake on: the test releases the query, or the abort action does.
            this.wake = release != null ? release : abortReached;
            return this;
        }

        @Override
        public LocalDate databaseCurrentDate(ScanCancellation cancellation) {
            anchorCalls.incrementAndGet();
            return ANCHOR;
        }

        @Override
        public ItemTypeAggregate aggregate(ItemTypeSummary itemType, ScanWindows windows,
                                           ScanCancellation cancellation) throws Exception {
            aggregateCalls.add(itemType.itemTypeId());
            aggregatedItemTypeIds.add(itemType.itemTypeId());
            if (parked != null) {
                if (abortReached != null) {
                    // Exactly how the JDBC bridge hooks Statement.cancel(), and it is also what releases
                    // this parked query: the await below is a failure deadline, never a sequence.
                    cancellation.register(() -> {
                        abortReached.countDown();
                        wake.countDown();
                    });
                }
                parked.countDown();
                wake.await(30, TimeUnit.SECONDS);
                if (cancellation.isCancelled()) {
                    throw new InterruptedException("cancelled while parked");
                }
            }
            long value = parked == null ? targetedLogicalItems : 25L;
            return new ItemTypeAggregate(value, MetricValue.available(1L), MetricValue.available(2L),
                    MetricValue.available(3L), MetricValue.available(4L));
        }
    }

    private static ScanCoordinator gatedCoordinator(AnalyticsOperationGate gate, StatisticsEngine engine) {
        return gatedCoordinator(gate, engine, null);
    }

    private static ScanCoordinator gatedCoordinator(AnalyticsOperationGate gate, StatisticsEngine engine,
                                                    HistoryRecorder recorder) {
        return new ScanCoordinator(settings(2), "repo",
                () -> List.of(summary(1, "A", "SAP"), summary(2, "B", "SAP")), engine, gate, "scan-owner",
                recorder == null ? snapshot -> { } : recorder::record);
    }

    private static TargetedRefreshService targetedService(AnalyticsOperationGate gate,
                                                          StatisticsEngine engine) {
        return new TargetedRefreshService(gate, settings(1), "repo",
                () -> List.of(summary(1, "A", "SAP"), summary(2, "B", "SAP"), summary(3, "C", "SAP")), engine,
                FreshnessThreshold.ofSeconds(300));
    }

    private static StatisticsSettings settings(int workers) {
        return new StatisticsSettings(true, workers, Duration.ofSeconds(5), Duration.ofSeconds(20));
    }

    private static ItemTypeSummary summary(int id, String name, String classification) {
        return new ItemTypeSummary(name, "description of " + name, id, "RAW_" + classification,
                classification, "");
    }
}


