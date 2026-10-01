package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.ConfigException;
import com.mraibo.cminsight.core.StatisticsSettings;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.statistics.Freshness;
import com.mraibo.cminsight.statistics.FreshnessThreshold;
import com.mraibo.cminsight.statistics.ItemTypeAggregate;
import com.mraibo.cminsight.statistics.MetricValue;
import com.mraibo.cminsight.statistics.ScanCancellation;
import com.mraibo.cminsight.statistics.ScanCoordinator;
import com.mraibo.cminsight.statistics.ScanStartResult;
import com.mraibo.cminsight.statistics.ScanWindows;
import com.mraibo.cminsight.statistics.StatisticsEngine;
import com.mraibo.cminsight.statistics.StatisticsService;
import com.mraibo.cminsight.statistics.StatisticsSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Goal 04 section 3 and section 12 "Cache/history": {@code cache.statistics.ttl.seconds} is a judgement
 * about age, never a second cache and never an implicit refresh.
 *
 * <h2>What the assertions measure</h2>
 *
 * <p>"A stale snapshot remains visible" and "a GET never causes database work" are not properties of a
 * boolean: they are properties of the fact that judging freshness did not touch the published snapshot and
 * did not start a scan. So every case here reads through the real {@link StatisticsService} over a real
 * {@link ScanCoordinator} whose engine COUNTS its anchor and aggregate calls, and the pass condition is
 * that the snapshot is still the same object while both counters stay where the completed scan left them.
 */
public class StatisticsFreshnessTest {

    private static final LocalDate ANCHOR = LocalDate.of(2024, 6, 15);

    // ------------------------------------------------------------------ staleness is a judgement

    /** A snapshot past its threshold is reported STALE and is still the value the service serves. */
    public void aStaleSnapshotRemainsVisibleAndStartsNoScan() throws Exception {
        CountingEngine engine = new CountingEngine();
        try (ScanCoordinator coordinator = coordinator(engine)) {
            StatisticsService service = StatisticsService.of("alpha", coordinator, null);
            StatisticsSnapshot published = runScan(coordinator);
            int anchorsAfterScan = engine.anchorCalls();
            int aggregatesAfterScan = engine.aggregateCalls();

            FreshnessThreshold threshold = FreshnessThreshold.ofSeconds(1);
            Instant later = published.capturedAt().plusSeconds(5);
            Freshness freshness = threshold.judge(service.snapshot(), later);

            Assert.assertTrue(freshness.known(), "the judgement has a capture instant to judge");
            Assert.assertTrue(freshness.stale(),
                    "a snapshot older than its threshold must be STALE, but the judgement was: "
                            + freshness.describe());
            Assert.assertTrue(service.snapshot().isPresent(),
                    "a stale snapshot must remain VISIBLE: nothing may be discarded because a threshold"
                            + " passed");
            Assert.assertTrue(service.snapshot().orElseThrow() == published,
                    "and it must be the very same published snapshot, not a replacement");
            Assert.assertEquals(1L, service.snapshot().orElseThrow().scanId(),
                    "with its original identity, so a reader can tell it was not rescanned");

            // Repeat the read the way a dashboard polling does. Freshness is a pure function, so none of
            // this may reach the database.
            for (int read = 0; read < 5; read++) {
                Assert.assertTrue(threshold.judge(service.snapshot(), Instant.now()).known(),
                        "every read still reports an age rather than an unknown");
                Assert.assertTrue(service.snapshot().isPresent(), "and still serves the snapshot");
            }
            Assert.assertEquals(anchorsAfterScan, engine.anchorCalls(),
                    "computing freshness must read NO database anchor: a stale read may never start database"
                            + " work implicitly");
            Assert.assertEquals(aggregatesAfterScan, engine.aggregateCalls(),
                    "and must run no aggregate either");
            Assert.assertFalse(coordinator.isScanInFlight(),
                    "and no scan may be in flight as a side effect of a read");
            Assert.assertEquals(1L, coordinator.progress().scanId(),
                    "the scan sequence must not have advanced, which is the observable form of 'no hidden"
                            + " refresh happened'");
        }
    }

    /** A threshold of zero means "any non-zero age is stale" - and still never discards the value. */
    public void aZeroThresholdNeverDiscardsTheSnapshot() throws Exception {
        CountingEngine engine = new CountingEngine();
        try (ScanCoordinator coordinator = coordinator(engine)) {
            StatisticsService service = StatisticsService.of("alpha", coordinator, null);
            StatisticsSnapshot published = runScan(coordinator);
            int anchorsAfterScan = engine.anchorCalls();

            FreshnessThreshold noFreshnessPeriod = FreshnessThreshold.ofSeconds(0);
            Assert.assertEquals(0L, noFreshnessPeriod.seconds(), "the threshold really is zero seconds");

            Freshness sameInstant = noFreshnessPeriod.judge(service.snapshot(), published.capturedAt());
            Assert.assertTrue(sameInstant.known(), "the snapshot is known at its own capture instant");
            Assert.assertFalse(sameInstant.stale(),
                    "a just-published result judged at its own capture instant is not stale: declaring it"
                            + " stale would make a fresh scan look expired the moment it arrives");

            Freshness oneMillisecondLater = noFreshnessPeriod.judge(service.snapshot(),
                    published.capturedAt().plusMillis(1));
            Assert.assertTrue(oneMillisecondLater.stale(),
                    "with no freshness period, any non-zero age is stale: " + oneMillisecondLater.describe());
            Assert.assertTrue(service.snapshot().isPresent(),
                    "and TTL=0 must NOT discard the snapshot: the value is still served");
            Assert.assertTrue(service.snapshot().orElseThrow() == published,
                    "the served snapshot is unchanged");
            Assert.assertEquals(anchorsAfterScan, engine.anchorCalls(),
                    "and a zero threshold reads no database anchor either");
        }
    }

    /** With no snapshot yet, freshness is UNKNOWN rather than stale, and still not an implicit scan. */
    public void noSnapshotMeansUnknownNotStaleAndStillNoScan() throws Exception {
        CountingEngine engine = new CountingEngine();
        try (ScanCoordinator coordinator = coordinator(engine)) {
            StatisticsService service = StatisticsService.of("alpha", coordinator, null);
            FreshnessThreshold threshold = FreshnessThreshold.ofSeconds(0);

            Freshness freshness = threshold.judge(service.snapshot(), Instant.now());
            Assert.assertFalse(freshness.known(),
                    "a repository that has never scanned has no age to report, and 'unknown' is not the same"
                            + " answer as 'stale'");
            Assert.assertFalse(freshness.stale(),
                    "so it must not raise a staleness warning about data that does not exist");
            Assert.assertTrue(freshness.ageMillis().isEmpty(), "and it reports no age");

            Freshness none = threshold.none();
            Assert.assertFalse(none.known(), "the explicit none() answers the same way");
            Assert.assertFalse(none.stale(), "and is not stale either");

            Assert.assertEquals(0, engine.anchorCalls(),
                    "asking about freshness must never read the database, not even once");
            Assert.assertFalse(coordinator.isScanInFlight(), "and must never start a scan");
            Assert.assertTrue(service.snapshot().isEmpty(),
                    "while the honest answer stays 'no snapshot yet' rather than a fabricated one");
        }
    }

    // ------------------------------------------------------------------ the configuration is validated

    /** The reserved setting is now read through the existing reader, with its documented bounds. */
    public void theFreshnessThresholdIsReadAndValidated() throws Exception {
        Assert.assertEquals("cache.statistics.ttl.seconds", FreshnessThreshold.KEY,
                "the freshness threshold must be the documented key");
        Assert.assertEquals(300L, FreshnessThreshold.from(AppConfig.empty()).seconds(),
                "and default to 300 seconds");

        Assert.assertEquals(0L, FreshnessThreshold.from(config("0")).seconds(),
                "0 is valid and means no freshness period");
        Assert.assertEquals(86_400L, FreshnessThreshold.from(config("86400")).seconds(),
                "the upper bound of 86400 is accepted");

        Assert.assertThrows(ConfigException.class, () -> FreshnessThreshold.from(config("-1")),
                "a negative threshold must be refused, not clamped");
        Assert.assertThrows(ConfigException.class, () -> FreshnessThreshold.from(config("86401")),
                "and so must a value above the documented maximum");
        Assert.assertThrows(ConfigException.class, () -> FreshnessThreshold.from(config("not-a-number")),
                "and a non-numeric value");

        Assert.assertEquals(300L, FreshnessThreshold.defaults().seconds(),
                "the defaults factory agrees with the config default");
    }

    /** Age is always reported, and a clock adjustment cannot produce a negative age. */
    public void ageIsAlwaysReportedAndAFutureCaptureIsClamped() throws Exception {
        Instant captured = Instant.parse("2024-06-15T10:00:00Z");
        Freshness judged = FreshnessThreshold.ofSeconds(60).judge(captured, captured.plusSeconds(90));
        Assert.assertTrue(judged.known(), "the age is reported");
        Assert.assertEquals(90_000L, judged.ageMillis().orElseThrow().longValue(),
                "as the exact elapsed time since capture");
        Assert.assertTrue(judged.stale(), "and the threshold is applied to it");

        Freshness future = FreshnessThreshold.ofSeconds(60).judge(captured, captured.minusSeconds(30));
        Assert.assertEquals(0L, future.ageMillis().orElseThrow().longValue(),
                "a capture instant in the future is clamped to an age of zero rather than reported as a"
                        + " negative age");
        Assert.assertFalse(future.stale(), "which leaves it fresh");
    }

    // ------------------------------------------------------------------ fixtures

    /** An engine that counts what the analytics layer actually asked the database to do. */
    private static final class CountingEngine implements StatisticsEngine {

        private final AtomicInteger anchorCalls = new AtomicInteger();
        private final AtomicInteger aggregateCalls = new AtomicInteger();

        @Override
        public LocalDate databaseCurrentDate(ScanCancellation cancellation) {
            anchorCalls.incrementAndGet();
            return ANCHOR;
        }

        @Override
        public ItemTypeAggregate aggregate(ItemTypeSummary itemType, ScanWindows windows,
                                           ScanCancellation cancellation) {
            aggregateCalls.incrementAndGet();
            return new ItemTypeAggregate(10L, MetricValue.available(1L), MetricValue.available(2L),
                    MetricValue.available(3L), MetricValue.available(4L));
        }

        int anchorCalls() {
            return anchorCalls.get();
        }

        int aggregateCalls() {
            return aggregateCalls.get();
        }
    }

    private static ScanCoordinator coordinator(StatisticsEngine engine) {
        return new ScanCoordinator(new StatisticsSettings(true, 1, Duration.ofSeconds(5),
                Duration.ofSeconds(20)), "alpha", () -> List.of(new ItemTypeSummary("ItemType0",
                "description", 4711, "RAW_SAP", "SAP", "")), engine);
    }

    private static StatisticsSnapshot runScan(ScanCoordinator coordinator) throws Exception {
        Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(),
                "the scan starts");
        Assert.assertTrue(coordinator.awaitScanCompletion(Duration.ofSeconds(20)),
                "the scan reaches a terminal state");
        return coordinator.snapshot().orElseThrow();
    }

    private static AppConfig config(String ttlSeconds) {
        Properties properties = new Properties();
        properties.setProperty(FreshnessThreshold.KEY, ttlSeconds);
        return AppConfig.fromProperties(properties);
    }
}


