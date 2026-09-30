package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.AppConfig;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Goal 03A section B: the single database anchor query must be inside the scan's cancellation and deadline
 * domain.
 *
 * <h2>The defect this suite pins</h2>
 *
 * <p>The anchor used to run before any worker existed and with no cancellation input at all, so neither the
 * overall scan deadline, nor an explicit cancel, nor a context close could reach its
 * {@code Statement.cancel()} registration. It now takes the scan's own {@link ScanCancellation} and registers
 * its abort action on exactly the same terms as an ItemType query - <strong>the same signal, the same
 * mechanism, no new type and no clock in the engine</strong> - so all three triggers reach it through
 * {@code ScanCancellation.cancel()}.
 *
 * <h2>The anchor is hostile too, and the abort is OBSERVED, never inferred</h2>
 *
 * <p>{@link BlockingAnchorEngine} blocks indefinitely on a latch and ignores interruption, so the deadline is
 * the only thing that can decide anything. Its registered abort action counts every invocation, so "the
 * registered cancel action was invoked" is a measurement of a call that really happened rather than a reading
 * of the source. Every wait is a latch or a bounded condition poll.
 *
 * <h2>Two paths, deliberately kept distinct</h2>
 *
 * <p>A deadline and an explicit cancellation reach the SAME abort action but must NOT report the same phase:
 * a timed-out scan is not a cancelled one, and Goal 03 confused them once already. The deadline path can only
 * produce TIMED_OUT and the cancellation path only CANCELLED, because the coordinator latches the deadline
 * fact only when nothing else had already cancelled the scan. Both paths are pinned here, each against the
 * phase the other one would produce.
 *
 * <h2>The named configuration, and why it looks odd</h2>
 *
 * <p>{@code statistics.query.timeout.seconds} is set GREATER than {@code statistics.scan.timeout.seconds}:
 * the overall scan deadline is a scan policy and must fire first, without the per-query cap having any say in
 * it. The engine here reads no timeout at all, so the only thing that can end the scan is the deadline the
 * coordinator owns - which is exactly what makes this a test of the deadline rather than of a driver.
 */
public class AnchorDeadlineCancellationTest {

    private static final String REPOSITORY = "anchor-deadline";

    /** A generous failure deadline: it can only ever turn a hang into a failure. */
    private static final Duration WAIT = Duration.ofSeconds(20);

    /** The bound the hostile anchor may stay parked before it gives up, so no test can hang the build. */
    private static final Duration ENGINE_FAILURE_DEADLINE = Duration.ofSeconds(25);

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

    /** Polls a condition, with a bound that is only ever a FAILURE deadline; never a sequencing device. */
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
     * Waits on a latch while DELIBERATELY IGNORING interruption, so a driver that ignores
     * {@code Statement.cancel()} and an interrupt is modelled faithfully.
     *
     * @return true when the latch was released, false when the failure deadline expired
     */
    private static boolean awaitIgnoringInterrupts(CountDownLatch latch, Duration failureDeadline) {
        long deadline = System.nanoTime() + failureDeadline.toNanos();
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
                // HOSTILE: an interrupt is not obeyed. Thread.interrupted() has already cleared the flag.
            }
        }
    }

    // ------------------------------------------------------------------ the blocking anchor engine

    /**
     * An engine whose ONE anchor read blocks indefinitely, ignores interruption and registers a real abort
     * action with the scan's {@link ScanCancellation}.
     *
     * <p>It counts the anchor CALLS and the anchor READS separately, because the goal forbids both halves of
     * a fallback: no retry may call the query again, and no second database date may be read as a substitute.
     * It also records that its aborts were run, which is what "observe the call, do not infer it" means.
     */
    private static final class BlockingAnchorEngine implements StatisticsEngine {

        private final LocalDate anchor;
        private final AtomicInteger anchorEntries = new AtomicInteger();
        private final AtomicInteger anchorReads = new AtomicInteger();
        private final AtomicInteger anchorAbortCallbacks = new AtomicInteger();
        private final AtomicInteger aggregateEntries = new AtomicInteger();
        private final AtomicBoolean releasedByFailureDeadline = new AtomicBoolean();
        private final AtomicBoolean anchorReturned = new AtomicBoolean();

        private volatile boolean blockAnchor;
        private volatile CountDownLatch anchorEntered = new CountDownLatch(0);
        private volatile CountDownLatch anchorRelease = new CountDownLatch(0);
        /** The Runnable really registered with the scan's signal, so a test can assert on its identity. */
        private volatile Runnable registeredAbort;

        private BlockingAnchorEngine(LocalDate anchor) {
            this.anchor = anchor;
        }

        /** Makes the next anchor read block until {@link #releaseAnchor()} is counted down. */
        void parkAnchor() {
            this.blockAnchor = true;
            this.anchorEntered = new CountDownLatch(1);
            this.anchorRelease = new CountDownLatch(1);
        }

        /** The only thing that lets the blocked anchor operation return. */
        void releaseAnchor() {
            anchorRelease.countDown();
        }

        boolean anchorStillHolding() {
            return anchorRelease.getCount() > 0;
        }

        boolean awaitAnchorEntered(Duration timeout) {
            try {
                return anchorEntered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        int anchorEntries() {
            return anchorEntries.get();
        }

        int anchorReads() {
            return anchorReads.get();
        }

        int anchorAbortCallbacks() {
            return anchorAbortCallbacks.get();
        }

        /** How many ItemType queries started; the anchor tests require this to stay at zero. */
        int aggregateEntries() {
            return aggregateEntries.get();
        }

        Runnable registeredAbort() {
            return registeredAbort;
        }

        boolean anchorReturned() {
            return anchorReturned.get();
        }

        boolean releasedByFailureDeadline() {
            return releasedByFailureDeadline.get();
        }

        @Override
        public LocalDate databaseCurrentDate(ScanCancellation cancellation) throws Exception {
            anchorEntries.incrementAndGet();
            // THE production shape: the anchor registers its abort action through the scan's own
            // cancellation signal, exactly as an ItemType query does.
            Runnable abortAction = anchorAbortCallbacks::incrementAndGet;
            registeredAbort = abortAction;
            ScanCancellation.Registration registration = cancellation.register(abortAction);
            try {
                if (blockAnchor) {
                    anchorEntered.countDown();
                    if (!awaitIgnoringInterrupts(anchorRelease, ENGINE_FAILURE_DEADLINE)) {
                        releasedByFailureDeadline.set(true);
                    }
                }
                anchorReturned.set(true);
                anchorReads.incrementAndGet();
                return anchor;
            } finally {
                registration.close();
            }
        }

        @Override
        public ItemTypeAggregate aggregate(ItemTypeSummary itemType,
                                           ScanWindows windows,
                                           ScanCancellation cancellation) {
            aggregateEntries.incrementAndGet();
            return measured(1L);
        }
    }

    // ================================================================== the deadline path

    /**
     * A blocked anchor is reached by the OVERALL DEADLINE, blocks a new scan until it really returns, and then
     * lets the final draining finish - publishing nothing and reading the database date exactly once.
     */
    public void aBlockedAnchorIsReachedByTheOverallDeadlineAndRetainsScanOwnershipUntilItReturns()
            throws Exception {
        BlockingAnchorEngine engine = new BlockingAnchorEngine(LocalDate.of(2024, 6, 15));
        engine.parkAnchor();
        ScanCoordinator coordinator = new ScanCoordinator(settings(2, Duration.ofSeconds(1)), REPOSITORY,
                () -> itemTypes(2), engine);
        try {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the scan starts");
            Assert.assertTrue(engine.awaitAnchorEntered(WAIT),
                    "the anchor operation must genuinely be in progress before the deadline is asserted");
            Assert.assertEquals(1, engine.anchorEntries(), "the anchor is entered exactly once");
            Assert.assertNull(coordinator.progress().anchorDate(),
                    "and no anchor date exists yet, so no window can have been derived from one");

            Assert.assertTrue(awaitCondition(() -> coordinator.progress().phase() == ScanStatus.Phase.TIMED_OUT,
                            WAIT),
                    "the overall deadline must reach the anchor and end the scan; the phase was "
                            + coordinator.progress().phase());

            // The registered cancel action was INVOKED - observed as a count, not inferred from the source.
            Assert.assertEquals(1, engine.anchorAbortCallbacks(),
                    "the anchor's registered cancel action must have been INVOKED by the deadline, exactly"
                            + " once; it was invoked " + engine.anchorAbortCallbacks() + " time(s)");
            Assert.assertNotNull(engine.registeredAbort(),
                    "and there must be a registered action at all, otherwise the previous assertion would be"
                            + " vacuous");

            // Phase and publication.
            Assert.assertEquals(ScanStatus.Phase.TIMED_OUT, coordinator.progress().phase(),
                    "a deadline that reached the anchor still reports TIMED_OUT");
            Assert.assertTrue(coordinator.progress().failureReason().toLowerCase(java.util.Locale.ROOT)
                            .contains("deadline"),
                    "AND the recorded reason must agree with the phase: a TIMED_OUT scan has to explain the"
                            + " deadline, otherwise an operator goes looking for the wrong cause - the reason"
                            + " was '" + coordinator.progress().failureReason() + "'");
            Assert.assertTrue(coordinator.snapshot().isEmpty(),
                    "and NO NEW SNAPSHOT may be published for a timed-out scan");

            // Scan ownership is RETAINED until the anchor operation actually returns.
            Assert.assertTrue(engine.anchorStillHolding(),
                    "the hostile anchor must still be in progress: it ignores both cancellation and"
                            + " interruption, so nothing but the test may end it");
            Assert.assertTrue(coordinator.isScanInFlight(),
                    "so the scan's ownership must still be retained: its supervisor is inside the anchor call"
                            + " and no worker has run at all");
            Assert.assertEquals(ScanStartResult.ALREADY_RUNNING, coordinator.requestScan(),
                    "and a second refresh is refused meanwhile");
            Assert.assertEquals(1, engine.anchorEntries(),
                    "the refused refresh must not have caused a retry: the anchor was entered once");
            Assert.assertEquals(0, engine.aggregateEntries(),
                    "and no ItemType query may have started, because the anchor never returned");

            // Releasing the anchor lets the final draining finish.
            engine.releaseAnchor();
            Assert.assertTrue(coordinator.awaitScanCompletion(WAIT),
                    "once the anchor operation really returns, the scan must finish and release its gate");
            Assert.assertTrue(engine.anchorReturned(),
                    "the anchor really returned, so the drain was watching a real exit");
            Assert.assertFalse(coordinator.isScanInFlight(), "the gate is released");
            Assert.assertEquals(ScanStatus.Phase.TIMED_OUT, coordinator.progress().phase(),
                    "the terminal phase is still the deadline's, not overwritten by the drain");
            Assert.assertTrue(coordinator.snapshot().isEmpty(),
                    "and still nothing was published");

            // Exactly one anchor read, and no invented boundary.
            Assert.assertEquals(1, engine.anchorEntries(),
                    "the anchor query must be entered EXACTLY ONCE: no retry and no fallback");
            Assert.assertEquals(1, engine.anchorReads(),
                    "and exactly one database date must have been read");
            Assert.assertNull(coordinator.progress().anchorDate(),
                    "the date that arrived after the scan had already been cancelled must NOT be recorded:"
                            + " the coordinator must not invent a boundary or substitute a JVM-local date");
            Assert.assertFalse(engine.releasedByFailureDeadline(),
                    "the test released the anchor, so the engine's failure deadline must not have fired");
        } finally {
            engine.releaseAnchor();
            coordinator.close();
        }
    }

    // ================================================================== the cancellation path

    /**
     * An explicit cancellation reaches THE SAME anchor cancel action and reports CANCELLED, never TIMED_OUT.
     *
     * <p>The configuration gives the scan a generous deadline here, so the deadline cannot be the thing that
     * fired: the only trigger is {@code cancelScan()}. The phase is asserted against TIMED_OUT explicitly,
     * because confusing these two paths is the defect Goal 03 already had once.
     */
    public void anExplicitCancellationReachesTheSameAnchorActionAndReportsCancelledNotTimedOut()
            throws Exception {
        BlockingAnchorEngine engine = new BlockingAnchorEngine(LocalDate.of(2024, 6, 15));
        engine.parkAnchor();
        ScanCoordinator coordinator = new ScanCoordinator(settings(2, Duration.ofSeconds(30)), REPOSITORY,
                () -> itemTypes(2), engine);
        try {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the scan starts");
            Assert.assertTrue(engine.awaitAnchorEntered(WAIT), "the anchor operation is in progress");
            Assert.assertEquals(1, engine.anchorEntries(), "one anchor entry");

            Assert.assertTrue(coordinator.cancelScan(), "an explicit cancellation must be signalled");
            Assert.assertTrue(awaitCondition(() -> engine.anchorAbortCallbacks() == 1, WAIT),
                    "the SAME registered anchor cancel action must be reached by an explicit cancellation,"
                            + " exactly as the deadline reached it; it was invoked "
                            + engine.anchorAbortCallbacks() + " time(s)");
            Assert.assertEquals(1, engine.anchorAbortCallbacks(),
                    "and it is invoked exactly once, by the one cancel signal");

            // While the hostile anchor holds, the result is not decided yet, so the phase cannot be a
            // timeout - and the ownership must be retained.
            Assert.assertFalse(coordinator.progress().phase() == ScanStatus.Phase.TIMED_OUT,
                    "an explicit cancellation must never be reported as TIMED_OUT, not even while the anchor"
                            + " still holds; the phase was " + coordinator.progress().phase());
            Assert.assertTrue(coordinator.isScanInFlight(),
                    "the scan ownership is retained until the anchor operation actually returns");
            Assert.assertEquals(ScanStartResult.ALREADY_RUNNING, coordinator.requestScan(),
                    "and a second refresh is refused meanwhile");
            Assert.assertTrue(coordinator.snapshot().isEmpty(), "nothing has been published");

            engine.releaseAnchor();
            Assert.assertTrue(coordinator.awaitScanCompletion(WAIT),
                    "releasing the anchor lets the final draining finish");
            Assert.assertEquals(ScanStatus.Phase.CANCELLED, coordinator.progress().phase(),
                    "and the terminal phase reports CANCELLED");
            Assert.assertFalse(coordinator.progress().phase() == ScanStatus.Phase.TIMED_OUT,
                    "NOT TIMED_OUT: the two paths were confused once already and are pinned apart here");
            Assert.assertTrue(coordinator.progress().failureReason().toLowerCase(java.util.Locale.ROOT)
                            .contains("cancel"),
                    "and the reason must agree with the phase: a CANCELLED scan explains the cancellation,"
                            + " never the clock; the reason was '" + coordinator.progress().failureReason()
                            + "'");
            Assert.assertFalse(coordinator.progress().failureReason().isEmpty(),
                    "with a fixed reason an operator can act on");
            Assert.assertTrue(coordinator.snapshot().isEmpty(),
                    "a cancelled scan never replaces the previous snapshot - and nothing was collected here");

            Assert.assertEquals(1, engine.anchorEntries(), "the anchor was entered exactly once");
            Assert.assertEquals(1, engine.anchorReads(), "and read exactly once");
            Assert.assertEquals(0, engine.aggregateEntries(),
                    "no ItemType query may have started: the cancelled scan must not go on to read data");
            Assert.assertFalse(engine.releasedByFailureDeadline(),
                    "the test released the anchor, so its failure deadline must not have fired");
        } finally {
            engine.releaseAnchor();
            coordinator.close();
        }
    }

    // ================================================================== the named configuration

    /**
     * {@code statistics.query.timeout.seconds} GREATER than {@code statistics.scan.timeout.seconds}: the
     * overall scan deadline still triggers first.
     *
     * <p>This is the assertion that stops the scan deadline from silently delegating to the per-query cap.
     * The settings really come from the configuration surface (not from a hand-built record), so the two
     * documented keys and their ranges are exercised too - and the scan is then run with that configured
     * pair, where the only thing that can end it is the coordinator's own deadline.
     */
    public void anOverallDeadlineShorterThanThePerQueryCapStillWinsAsTheScanPolicy() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(StatisticsSettings.QUERY_TIMEOUT_KEY, "600");
        properties.setProperty(StatisticsSettings.SCAN_TIMEOUT_KEY, "1");
        AppConfig config = AppConfig.fromProperties(properties);
        StatisticsSettings configured = StatisticsSettings.from(config, 2);

        Assert.assertEquals(600, configured.queryTimeoutSeconds(),
                "the per-query cap really is 600s, the value the goal names as the dangerous one");
        Assert.assertEquals(1, configured.scanTimeoutSeconds(),
                "and the overall scan deadline really is 1s");
        Assert.assertTrue(configured.queryTimeout().compareTo(configured.scanTimeout()) > 0,
                "so this is the configuration the goal names explicitly: the per-query timeout is GREATER"
                        + " than the scan timeout");
        Assert.assertFalse(configured.describe().contains("password"),
                "and the printable settings line still carries no credential");

        BlockingAnchorEngine engine = new BlockingAnchorEngine(LocalDate.of(2024, 6, 15));
        engine.parkAnchor();
        ScanCoordinator coordinator = new ScanCoordinator(configured, REPOSITORY, () -> itemTypes(2), engine);
        try {
            Assert.assertEquals(ScanStartResult.STARTED, coordinator.requestScan(), "the scan starts");
            Assert.assertTrue(engine.awaitAnchorEntered(WAIT), "the anchor operation is in progress");

            Assert.assertTrue(awaitCondition(() -> coordinator.progress().phase() == ScanStatus.Phase.TIMED_OUT,
                            WAIT),
                    "the OVERALL deadline must fire first even though the per-query cap is 600 times larger;"
                            + " the phase was " + coordinator.progress().phase());
            Assert.assertEquals(1, engine.anchorAbortCallbacks(),
                    "and it reaches the anchor's registered cancel action");
            Assert.assertTrue(engine.anchorStillHolding(),
                    "while the hostile anchor is still holding, so no per-query timeout ended anything");
            Assert.assertTrue(coordinator.snapshot().isEmpty(), "nothing was published");
            Assert.assertTrue(coordinator.isScanInFlight(),
                    "and the scan still owns the gate until the anchor returns");

            engine.releaseAnchor();
            Assert.assertTrue(coordinator.awaitScanCompletion(WAIT), "releasing the anchor drains the scan");
            Assert.assertEquals(ScanStatus.Phase.TIMED_OUT, coordinator.progress().phase(),
                    "the terminal phase is the scan policy's TIMED_OUT, not a per-query outcome");
            Assert.assertEquals(1, engine.anchorReads(), "the anchor was read exactly once");
            Assert.assertFalse(coordinator.snapshot().isPresent(),
                    "and the previous-snapshot rule is unchanged: a timed-out scan publishes nothing");
        } finally {
            engine.releaseAnchor();
            coordinator.close();
        }
    }
}
