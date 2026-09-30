package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.ConfigException;
import com.mraibo.cminsight.config.DatabaseVendor;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.core.JdbcPoolSettings;
import com.mraibo.cminsight.core.StatisticsSettings;
import com.mraibo.cminsight.db.Db2Dialect;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.statistics.ItemIdDateKey;
import com.mraibo.cminsight.statistics.ItemTypeAggregate;
import com.mraibo.cminsight.statistics.ItemTypeStatistics;
import com.mraibo.cminsight.statistics.MetricValue;
import com.mraibo.cminsight.statistics.ScanCancellation;
import com.mraibo.cminsight.statistics.ScanCoordinator;
import com.mraibo.cminsight.statistics.ScanStartResult;
import com.mraibo.cminsight.statistics.ScanStatus;
import com.mraibo.cminsight.statistics.ScanWindows;
import com.mraibo.cminsight.statistics.StatisticsCapability;
import com.mraibo.cminsight.statistics.StatisticsCoverage;
import com.mraibo.cminsight.statistics.StatisticsEngine;
import com.mraibo.cminsight.statistics.StatisticsQueryException;
import com.mraibo.cminsight.statistics.StatisticsService;
import com.mraibo.cminsight.statistics.StatisticsSnapshot;
import com.mraibo.cminsight.statistics.StatisticsTotals;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Goal 03 sections 11 and 12: the statistics DTOs, the coverage/partial-failure accounting, the atomic
 * publication rule, and JDBC being optional to repository activation.
 *
 * <p>Deliberately disjoint from {@code ScanCoordinatorTest}, which owns the concurrency, cancellation and
 * context-lifetime properties. What is pinned here is the part a caller reads:
 *
 * <ul>
 *   <li>a metric can never silently become a zero - an ERROR or UNAVAILABLE metric carries no number at
 *       all, even when a producer passes one;</li>
 *   <li>a total always travels with the coverage and partial-failure state that says how complete it is;</li>
 *   <li>the totals are grouped by the classification label metadata already carries;</li>
 *   <li>a snapshot cannot be built from a partial scan, and a scan that cannot read its anchor does not
 *       replace the previous snapshot;</li>
 *   <li>a failure reason stored in a snapshot is sanitized, never a raw exception message or URL;</li>
 *   <li>activating analytics on a repository that cannot support it creates no JDBC resource at all.</li>
 * </ul>
 */
public class StatisticsPublicationTest {

    private static final Instant STARTED = Instant.parse("2024-05-06T10:00:00Z");
    private static final Instant CAPTURED = Instant.parse("2024-05-06T10:00:02Z");
    private static final LocalDate ANCHOR = LocalDate.of(2024, 5, 6);

    // ------------------------------------------------------------------ MetricValue

    /** A metric is a number or an honest absence - never a zero standing in for one of them. */
    public void metricsNeverInventANumber() {
        Assert.assertEquals(7L, MetricValue.available(7L).value().longValue(),
                "an available metric carries exactly its measured number");
        Assert.assertEquals(0L, MetricValue.available(0L).value().longValue(),
                "a legitimate zero is representable and stays distinguishable from 'no data'");
        Assert.assertNull(MetricValue.unavailable().value(), "an unavailable metric carries no number at all");
        Assert.assertEquals(MetricValue.Availability.ERROR,
                MetricValue.Availability.valueOf("ERROR"),
                "a failed measurement has its own state instead of a number");
        Assert.assertEquals(3, MetricValue.Availability.values().length,
                "the availability vocabulary is closed: no ESTIMATED and no GUESSED state");

        // ERROR is reached through the canonical constructor, because the public factory set is pinned to
        // {available, unavailable} by StatisticsContractTest.
        MetricValue error = new MetricValue(null, MetricValue.Availability.ERROR, "logical item aggregate");
        Assert.assertFalse(error.isAvailable(), "an error metric is not available");
        Assert.assertTrue(error.isError(), "an error metric reports its own state");
        Assert.assertNull(error.value(), "an error metric carries no number");
        Assert.assertEquals(0L, error.valueOrZero(),
                "valueOrZero is a display convenience only; validation must use isAvailable()");

        MetricValue smuggled = new MetricValue(42L, MetricValue.Availability.ERROR, "");
        Assert.assertNull(smuggled.value(),
                "a number passed together with ERROR is discarded, so it can never be summed as data");
        MetricValue smuggledUnavailable = new MetricValue(42L, MetricValue.Availability.UNAVAILABLE, "");
        Assert.assertNull(smuggledUnavailable.value(),
                "a number passed together with UNAVAILABLE is discarded as well");

        Assert.assertThrows(IllegalArgumentException.class,
                () -> new MetricValue(null, MetricValue.Availability.AVAILABLE, ""),
                "an AVAILABLE metric without a number is refused rather than left ambiguous");
    }

    /** A stored reason is a single sanitized line: no control characters, and a bounded length. */
    public void storedReasonsAreSanitized() {
        MetricValue noisy = new MetricValue(null, MetricValue.Availability.ERROR,
                "first line\nsecond\tline \u0007 end");
        Assert.assertFalse(noisy.reason().contains("\n"), "a newline is stripped: " + noisy.reason());
        Assert.assertFalse(noisy.reason().contains("\t"), "a tab is stripped: " + noisy.reason());
        Assert.assertFalse(noisy.reason().contains("\u0007"), "a control character is stripped");
        Assert.assertTrue(noisy.reason().startsWith("first line second line"),
                "the words survive in one line: " + noisy.reason());

        MetricValue huge = new MetricValue(null, MetricValue.Availability.ERROR, "x".repeat(5000));
        Assert.assertTrue(huge.reason().length() <= 200,
                "a reason is bounded so one failure cannot inflate a snapshot, was " + huge.reason().length());
    }

    // ------------------------------------------------------------------ totals and coverage

    /** Totals are grouped by the classification label already present in metadata. */
    public void totalsGroupByTheMetadataClassification() {
        List<ItemTypeStatistics> results = List.of(
                measured(1, "A", "SAP", 10L),
                measured(2, "B", "SAP", 5L),
                measured(3, "C", "NON-SAP", 7L),
                ItemTypeStatistics.failed("repo", summary(4, "D", "NON-SAP"), STARTED, CAPTURED, 3L,
                        ItemTypeStatistics.SOURCE_JDBC, "logical item aggregate"));

        StatisticsTotals totals = StatisticsTotals.from(results);
        Assert.assertEquals(22L, totals.logicalItemsTotal(), "the measured totals add up");
        Assert.assertEquals(3L, totals.countedItemTypes(),
                "only ItemTypes whose total was measured contribute to the number");
        Assert.assertEquals(1L, totals.errorItemTypes(), "the failed ItemType is counted as a failure");
        Assert.assertEquals(2L, totals.forClassification("SAP").itemTypes(), "SAP has two ItemTypes");
        Assert.assertEquals(15L, totals.forClassification("SAP").logicalItems(), "SAP contributes 15");
        Assert.assertTrue(totals.forClassification("SAP").complete(), "the SAP entry is fully measured");
        Assert.assertEquals(1L, totals.forClassification("NON-SAP").errorItemTypes(),
                "the NON-SAP entry carries the failure");
        Assert.assertFalse(totals.forClassification("NON-SAP").complete(),
                "a class with a failed ItemType is not complete");
        Assert.assertFalse(totals.complete(),
                "the overall totals are INCOMPLETE, so the number must not be presented as a full total");
        Assert.assertEquals(0L, totals.forClassification("NOT-PRESENT").itemTypes(),
                "an absent label reports zeros rather than failing");
        Assert.assertEquals(MetricValue.Availability.UNAVAILABLE, totals.versions().availability(),
                "versions stay unavailable in the totals");
        Assert.assertEquals(MetricValue.Availability.UNAVAILABLE, totals.parts().availability(),
                "parts stay unavailable in the totals");
    }

    /** A snapshot reports how much of the frozen list it covers, so a subtotal is never a claim of totality. */
    public void snapshotReportsCoverageAndPartialFailures() {
        List<ItemTypeStatistics> results = List.of(
                measured(1, "A", "SAP", 10L),
                partial(2, "B", "SAP", 5L),
                ItemTypeStatistics.failed("repo", summary(3, "C", "NON-SAP"), STARTED, CAPTURED, 4L,
                        ItemTypeStatistics.SOURCE_JDBC, "logical item aggregate"));

        StatisticsSnapshot snapshot = StatisticsSnapshot.of("repo", 7L, CAPTURED, STARTED, 120L, ANCHOR,
                3, results);
        StatisticsCoverage coverage = snapshot.coverage();
        Assert.assertEquals(3, coverage.requestedItemTypes(), "the frozen list is covered in full");
        Assert.assertEquals(3, coverage.completedItemTypes(), "every ItemType was attempted");
        Assert.assertEquals(1, coverage.failedItemTypes(), "one ItemType failed");
        Assert.assertEquals(1, coverage.partialItemTypes(), "one ItemType is partial");
        Assert.assertEquals(1, coverage.succeededItemTypes(), "one ItemType is fully measured");
        Assert.assertFalse(coverage.complete(), "the coverage is not complete");
        Assert.assertEquals(2, snapshot.partialFailureCount(),
                "failed + partial is the partial-failure count");
        Assert.assertEquals(7L, snapshot.scanId(), "the scan id is part of the snapshot");
        Assert.assertEquals(ANCHOR, snapshot.anchorDate(), "the snapshot carries its one anchor date");
        Assert.assertEquals(3, snapshot.perItemType().size(), "one result per frozen ItemType");
        Assert.assertEquals(MetricValue.Availability.UNAVAILABLE,
                snapshot.itemType("B").orElseThrow().versions().availability(),
                "versions stay unavailable in every per-ItemType result");
        Assert.assertEquals(MetricValue.Availability.ERROR,
                snapshot.itemType("C").orElseThrow().logicalItems().availability(),
                "a failed ItemType is ERROR, not UNAVAILABLE - and never a zero");
        Assert.assertNull(snapshot.itemType("C").orElseThrow().logicalItems().value(),
                "and it carries no number at all, so it cannot be summed as zero items");
        Assert.assertTrue(snapshot.itemType("C").orElseThrow().failed(), "the failed result says so");
        Assert.assertTrue(snapshot.itemType("MISSING").isEmpty(), "an uncovered ItemType is absent");
        Assert.assertTrue(snapshot.coverageDescription().contains("1 partial")
                        && snapshot.coverageDescription().contains("1 failed"),
                "the description states the incomplete counts: " + snapshot.coverageDescription());
    }

    /** The publication rule's structural guard: a partial scan cannot become a snapshot. */
    public void snapshotRefusesAPartialItemTypeList() {
        List<ItemTypeStatistics> oneOfThree = List.of(measured(1, "A", "SAP", 10L));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> StatisticsSnapshot.of("repo", 1L, CAPTURED, STARTED, 10L, ANCHOR, 3, oneOfThree),
                "a scan that did not visit every frozen ItemType must not become a snapshot");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new StatisticsCoverage(3, 2, 2, 1),
                "impossible coverage arithmetic is refused rather than reported");
    }

    // ------------------------------------------------------------------ windows

    /** The four windows come from the ONE database anchor, and the host clock plays no part. */
    public void windowsAreAnchoredOnTheDatabaseDateOnly() {
        LocalDate anchor = LocalDate.of(2011, 3, 1);
        ScanWindows windows = ScanWindows.anchoredAt(anchor);
        Assert.assertEquals(anchor, windows.anchor(), "the anchor is exactly the database date");
        Assert.assertTrue(windows.fullyRepresentable(), "a 2011 anchor is inside the documented range");

        List<Object> parameters = windows.parameters();
        Assert.assertEquals(8, parameters.size(), "eight boundary values, one tuple per segment");
        String tomorrow = ItemIdDateKey.encode(anchor.plusDays(1)).orElseThrow();
        Assert.assertEquals(tomorrow, parameters.get(1), "today ends at tomorrow");
        Assert.assertEquals(tomorrow, parameters.get(3), "the 7-day window shares the same end");
        Assert.assertEquals(tomorrow, parameters.get(5), "the 30-day window shares the same end");
        Assert.assertEquals(ItemIdDateKey.encode(anchor.minusDays(6)).orElseThrow(), parameters.get(2),
                "the 7-day window starts 6 days before the anchor");
        Assert.assertEquals(ItemIdDateKey.encode(anchor.minusDays(29)).orElseThrow(), parameters.get(4),
                "the 30-day window starts 29 days before the anchor");
        Assert.assertEquals(ItemIdDateKey.encode(LocalDate.of(2011, 1, 1)).orElseThrow(), parameters.get(6),
                "the current-year window starts on January 1 of the anchor's year");
        Assert.assertEquals(ItemIdDateKey.encode(LocalDate.of(2012, 1, 1)).orElseThrow(), parameters.get(7),
                "and ends on January 1 of the next year");
        // The host clock is not the anchor: the "today" window starts on the DATABASE's date, and the host's
        // own date differs from it by construction (the anchor is fixed in 2011).
        Assert.assertFalse(ItemIdDateKey.encode(LocalDate.now()).orElse("").equals(parameters.get(0)),
                "the host's own date is not what the today window starts at");
    }

    /** An unrepresentable anchor is reported, and no boundary is ever invented for it. */
    public void anUnrepresentableAnchorIsReportedNotApproximated() {
        ScanWindows outsideRange = ScanWindows.anchoredAt(LocalDate.of(1999, 12, 31));
        Assert.assertFalse(outsideRange.fullyRepresentable(),
                "1999 is outside the documented 2000-2199 ItemID date encoding");
        Assert.assertTrue(outsideRange.unavailableReason().isPresent(),
                "an unrepresentable anchor produces a fixed reason");
        Assert.assertFalse(outsideRange.unavailableReason().orElse("").contains("\n"),
                "the reason is a single sanitized line, not a value dump");
        Assert.assertThrows(IllegalStateException.class, outsideRange::parameters,
                "boundaries are never invented for an unrepresentable window");
        Assert.assertTrue(ItemIdDateKey.encode(LocalDate.of(2199, 12, 31)).isPresent(),
                "the documented range ends at 2199");
        Assert.assertTrue(ItemIdDateKey.encode(LocalDate.of(2200, 1, 1)).isEmpty(),
                "2200 has no documented century mapping and is refused");
    }

    // ------------------------------------------------------------------ settings

    /** More workers than JDBC connections is refused with both keys named, never silently clamped. */
    public void workersGreaterThanTheJdbcPoolAreRefused() {
        Properties conflicting = new Properties();
        conflicting.setProperty("statistics.workers", "8");
        conflicting.setProperty("jdbc.pool.size", "2");
        AppConfig config = AppConfig.fromProperties(conflicting);
        ConfigException refusal = Assert.assertThrows(ConfigException.class,
                () -> StatisticsSettings.from(config, JdbcPoolSettings.from(config)),
                "a worker count above the pool size must be refused");
        Assert.assertTrue(refusal.getMessage().contains("statistics.workers"),
                "the refusal names the worker key: " + refusal.getMessage());
        Assert.assertTrue(refusal.getMessage().contains("jdbc.pool.size"),
                "the refusal names the pool key it conflicts with: " + refusal.getMessage());

        Properties smallPool = new Properties();
        smallPool.setProperty("jdbc.pool.size", "2");
        AppConfig poolOfTwo = AppConfig.fromProperties(smallPool);
        Assert.assertEquals(2, StatisticsSettings.from(poolOfTwo, JdbcPoolSettings.from(poolOfTwo)).workers(),
                "the default is min(4, jdbc.pool.size), so a small pool is never refused");

        Properties outOfRange = new Properties();
        outOfRange.setProperty("jdbc.pool.size", "0");
        AppConfig invalid = AppConfig.fromProperties(outOfRange);
        Assert.assertThrows(ConfigException.class, () -> JdbcPoolSettings.from(invalid),
                "an out-of-range pool size is a configuration error, not a clamped number");
    }

    // ------------------------------------------------------------------ optional to activation

    /** A repository that cannot support analytics stays fully usable: no pool, no coordinator, no resource. */
    public void anUnusableAnalyticsConfigurationCreatesNoJdbcResource() {
        SecretResolver secrets = new SecretResolver(Map.of(), null);
        List<AutoCloseable> resources = new ArrayList<>();

        StatisticsService disabled = StatisticsCapability.activate(
                profile(DatabaseVendor.DB2, "jdbc:db2://db.example:50000/ICMADMIN"),
                secrets, new Db2Dialect(), JdbcPoolSettings.defaults(), StatisticsSettings.disabled(),
                List::of, resources::add);
        Assert.assertFalse(disabled.available(), "a disabled feature reports itself unavailable");
        Assert.assertFalse(disabled.availability().enabled(), "and says the feature is switched off");
        Assert.assertEquals(0, resources.size(), "a disabled feature creates no pool and no coordinator");
        Assert.assertEquals(ScanStartResult.UNAVAILABLE, disabled.requestScan(),
                "no scan can be requested when nothing was constructed");
        Assert.assertTrue(disabled.snapshot().isEmpty(), "there is no snapshot to read");
        Assert.assertFalse(disabled.progress().running(), "and nothing is running");
        Assert.assertEquals("repo", disabled.repositoryId(), "the service still knows its repository");

        // Enabled, but the configured URL does not belong to the repository's vendor family. Readiness is
        // local only, so this is refused without touching a database.
        StatisticsService mismatched = StatisticsCapability.activate(
                profile(DatabaseVendor.DB2, "jdbc:oracle:thin:@db.example:1521:ICM"),
                secrets, new Db2Dialect(), JdbcPoolSettings.defaults(), StatisticsSettings.defaults(4),
                List::of, resources::add);
        Assert.assertFalse(mismatched.available(), "a vendor/URL mismatch leaves analytics unavailable");
        Assert.assertTrue(mismatched.availability().enabled(),
                "the feature itself is still enabled; only the analytics half is unusable");
        Assert.assertTrue(mismatched.unavailableReason().contains("URL"),
                "the reason names the URL mismatch: " + mismatched.unavailableReason());
        Assert.assertEquals(0, resources.size(),
                "an unusable analytics configuration leaves the CM repository untouched: no pool exists");
        Assert.assertTrue(mismatched.diagnostics().lastError().isEmpty(),
                "an unusable configuration reports no driver error text");
        Assert.assertTrue(mismatched.snapshot().isEmpty(), "and no snapshot is fabricated");
    }

    // ------------------------------------------------------------------ failure sanitation

    /** A failing ItemType store only a sanitized reason - never a raw exception message or a JDBC URL. */
    public void aFailedItemTypeStoresOnlyASanitizedReason() throws Exception {
        String secret = "password=hunter2 jdbc:db2://db.example:50000/ICMADMIN";
        StatisticsEngine leaking = new StatisticsEngine() {
            @Override
            public LocalDate databaseCurrentDate(ScanCancellation cancellation) {
                return ANCHOR;
            }

            @Override
            public ItemTypeAggregate aggregate(ItemTypeSummary itemType, ScanWindows windows,
                                               ScanCancellation cancellation) {
                throw new IllegalStateException(secret);
            }
        };
        StatisticsSnapshot snapshot;
        try (ScanCoordinator coordinator = new ScanCoordinator(settings(3), "repo",
                () -> List.of(summary(1, "LEAKY", "SAP")), leaking)) {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the scan starts");
            Assert.assertTrue(coordinator.awaitScanCompletion(Duration.ofSeconds(20)),
                    "the scan reaches a terminal state");
            snapshot = coordinator.snapshot().orElseThrow();
        }
        Assert.assertEquals(1, snapshot.coverage().failedItemTypes(), "the ItemType is recorded as failed");
        String reason = snapshot.perItemType().get(0).errorMessage();
        Assert.assertFalse(reason.contains("hunter2"), "no exception message reaches the snapshot: " + reason);
        Assert.assertFalse(reason.contains("jdbc:db2"), "and no JDBC URL either: " + reason);
        Assert.assertTrue(reason.contains("IllegalStateException"),
                "the failure TYPE is reported instead: " + reason);
    }

    /** A database failure contributes its fixed operation label and SQLState, not the driver's message. */
    public void aDatabaseFailureContributesOnlyItsOperationAndSqlState() throws Exception {
        String driverText = "DB2 SQL Error: SQLCODE=-204, table ICMADMIN.ICMUT01234001 has password=hunter2";
        StatisticsEngine failing = new StatisticsEngine() {
            @Override
            public LocalDate databaseCurrentDate(ScanCancellation cancellation) {
                return ANCHOR;
            }

            @Override
            public ItemTypeAggregate aggregate(ItemTypeSummary itemType, ScanWindows windows,
                                               ScanCancellation cancellation) throws StatisticsQueryException {
                throw new StatisticsQueryException("logical item aggregate", "42704", -204,
                        new RuntimeException(driverText));
            }
        };
        StatisticsSnapshot snapshot;
        try (ScanCoordinator coordinator = new ScanCoordinator(settings(2), "repo",
                () -> List.of(summary(1, "A", "SAP")), failing)) {
            coordinator.requestScan();
            Assert.assertTrue(coordinator.awaitScanCompletion(Duration.ofSeconds(20)), "the scan finishes");
            snapshot = coordinator.snapshot().orElseThrow();
        }
        String reason = snapshot.perItemType().get(0).errorMessage();
        Assert.assertTrue(reason.contains("logical item aggregate"),
                "the sanitized reason names the fixed operation: " + reason);
        Assert.assertTrue(reason.contains("SQLState 42704"),
                "and carries the SQLState: " + reason);
        Assert.assertTrue(reason.contains("vendor code -204"), "and the vendor code: " + reason);
        Assert.assertFalse(reason.contains("hunter2"), "but never the driver's message: " + reason);
        Assert.assertFalse(reason.contains("ICMUT"), "and never a table name taken from it: " + reason);
    }

    /**
     * A scan that cannot read its database date publishes NOTHING, so the previous snapshot stays current.
     *
     * <p>This is the publication rule for the cases the goal names - a catastrophic failure, an
     * overall-timeout abort, a switch or a cancellation: the last COMPLETED snapshot is never replaced by an
     * incomplete object.
     */
    public void aScanThatFailsBeforeItsItemsKeepsThePreviousSnapshot() throws Exception {
        AtomicBoolean failAnchor = new AtomicBoolean();
        AtomicInteger aggregateCalls = new AtomicInteger();
        StatisticsEngine engine = new StatisticsEngine() {
            @Override
            public LocalDate databaseCurrentDate(ScanCancellation cancellation)
                    throws StatisticsQueryException {
                if (failAnchor.get()) {
                    throw new StatisticsQueryException("database current date", "08003", 0,
                            new IllegalStateException("connection is closed"));
                }
                return ANCHOR;
            }

            @Override
            public ItemTypeAggregate aggregate(ItemTypeSummary itemType, ScanWindows windows,
                                               ScanCancellation cancellation) {
                aggregateCalls.incrementAndGet();
                return completeAggregate(10L);
            }
        };
        try (ScanCoordinator coordinator = new ScanCoordinator(settings(2), "repo",
                () -> List.of(summary(1, "A", "SAP"), summary(2, "B", "SAP")), engine)) {
            coordinator.requestScan();
            Assert.assertTrue(coordinator.awaitScanCompletion(Duration.ofSeconds(20)), "the first scan ends");
            StatisticsSnapshot published = coordinator.snapshot().orElseThrow();
            Assert.assertTrue(published.complete(), "the first snapshot is complete");
            Assert.assertEquals(2, published.perItemType().size(), "both ItemTypes were measured");

            failAnchor.set(true);
            aggregateCalls.set(0);
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "a second scan starts");
            Assert.assertTrue(coordinator.awaitScanCompletion(Duration.ofSeconds(20)), "the second scan ends");

            Assert.assertEquals(0, aggregateCalls.get(),
                    "no ItemType was measured once the anchor could not be read");
            Assert.assertEquals(published, coordinator.snapshot().orElseThrow(),
                    "the previous completed snapshot is STILL the current answer");
            Assert.assertEquals(1L, coordinator.snapshot().orElseThrow().scanId(),
                    "and it is still the first scan's snapshot, not a half-built second one");
            Assert.assertTrue(coordinator.progress().terminal(), "the failed scan is terminal");
            Assert.assertFalse(coordinator.progress().published(),
                    "and it did not publish anything");
        }
    }

    /**
     * A cancelled scan is reported as CANCELLED, never as a timeout.
     *
     * <p>Regression for a real defect: cancelling interrupts the supervisor as well, so its worker join
     * returns early and looks exactly like a deadline that ran out. The first version recorded the deadline
     * from that path and told the operator (and the switch) that a slow query had timed out when in fact a
     * cancel, a switch or a context close had stopped the scan.
     */
    public void aCancelledScanIsReportedAsCancelledNotTimedOut() throws Exception {
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
                // Exactly how the JDBC bridge hooks Statement.cancel(): the abort action releases the query.
                cancellation.register(gate::countDown);
                parked.countDown();
                gate.await(30, TimeUnit.SECONDS);
                if (cancellation.isCancelled()) {
                    throw new InterruptedException("the scan was cancelled");
                }
                return completeAggregate(5L);
            }
        };
        try (ScanCoordinator coordinator = new ScanCoordinator(settings(1), "repo",
                () -> List.of(summary(1, "A", "SAP")), blocking)) {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the scan starts");
            Assert.assertTrue(parked.await(10, TimeUnit.SECONDS), "the query reached its parked state");
            Assert.assertTrue(coordinator.cancelScan(), "the running scan is signalled");
            Assert.assertTrue(coordinator.awaitScanCompletion(Duration.ofSeconds(25)),
                    "the cancelled scan reaches a terminal state");
            Assert.assertEquals(ScanStatus.Phase.CANCELLED, coordinator.progress().phase(),
                    "a cancelled scan is CANCELLED, not TIMED_OUT: " + coordinator.progress());
            Assert.assertTrue(coordinator.snapshot().isEmpty(),
                    "a cancelled scan publishes nothing, so no snapshot is fabricated");
        }
    }

    /**
     * A scan that genuinely passes its overall deadline is reported as TIMED_OUT and publishes nothing.
     *
     * <p>The distinction from {@link #aCancelledScanIsReportedAsCancelledNotTimedOut()} is the whole point of
     * keeping the two facts apart: the deadline path aborts its own query, so it must not be mistaken for an
     * external cancellation either.
     */
    public void aScanThatPassesItsDeadlineIsReportedAsTimedOut() throws Exception {
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
                return completeAggregate(5L);
            }
        };
        StatisticsSettings shortDeadline = new StatisticsSettings(true, 1, Duration.ofSeconds(2),
                Duration.ofSeconds(1));
        try (ScanCoordinator coordinator = new ScanCoordinator(shortDeadline, "repo",
                () -> List.of(summary(1, "A", "SAP")), blocking)) {
            coordinator.requestScan();
            Assert.assertTrue(coordinator.awaitScanCompletion(Duration.ofSeconds(25)),
                    "the unresponsive scan is terminated by its deadline");
            Assert.assertEquals(ScanStatus.Phase.TIMED_OUT, coordinator.progress().phase(),
                    "an expired overall deadline is TIMED_OUT: " + coordinator.progress());
            Assert.assertTrue(coordinator.progress().failureReason().contains("deadline"),
                    "and the reason names the deadline: " + coordinator.progress().failureReason());
            Assert.assertTrue(coordinator.snapshot().isEmpty(),
                    "a timed-out scan publishes nothing rather than a partial snapshot");
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static StatisticsSettings settings(int workers) {
        return new StatisticsSettings(true, workers, Duration.ofSeconds(5), Duration.ofSeconds(20));
    }

    private static ItemTypeSummary summary(int id, String name, String classification) {
        return new ItemTypeSummary(name, "description of " + name, id, "RAW_" + classification,
                classification, "");
    }

    private static ItemTypeAggregate completeAggregate(long total) {
        return new ItemTypeAggregate(total, MetricValue.available(1L), MetricValue.available(2L),
                MetricValue.available(3L), MetricValue.available(4L));
    }

    private static ItemTypeStatistics measured(int id, String name, String classification, long total) {
        return ItemTypeStatistics.measured("repo", summary(id, name, classification), STARTED, CAPTURED, 5L,
                ItemTypeStatistics.SOURCE_JDBC, completeAggregate(total));
    }

    /** A measured total whose window boundaries were not representable: PARTIAL, never a silent zero. */
    private static ItemTypeStatistics partial(int id, String name, String classification, long total) {
        return ItemTypeStatistics.measured("repo", summary(id, name, classification), STARTED, CAPTURED, 5L,
                ItemTypeStatistics.SOURCE_JDBC,
                new ItemTypeAggregate(total, MetricValue.unavailable(), MetricValue.unavailable(),
                        MetricValue.unavailable(), MetricValue.unavailable()));
    }

    /** A DB2 profile whose JDBC URL is supplied by the test, with credentials by reference only. */
    private static RepositoryProfile profile(DatabaseVendor vendor, String jdbcUrl) {
        return new RepositoryProfile(
                "repo",
                "Repository repo",
                "SSIDREPO",
                vendor,
                jdbcUrl,
                "ICMADMIN",
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.CM_USER_KEY, "CM_REPO_USER"),
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.CM_PASSWORD_KEY,
                        "CM_REPO_PASSWORD"),
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.JDBC_USER_KEY, "JDBC_REPO_USER"),
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.JDBC_PASSWORD_KEY,
                        "JDBC_REPO_PASSWORD"),
                "https://icn.example/icn",
                null);
    }
}
