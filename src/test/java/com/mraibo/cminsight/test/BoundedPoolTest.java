package com.mraibo.cminsight.test;

import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.connection.PoolMetrics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The heart of the goal: a hard-bounded pool must never overshoot, under real concurrency.
 *
 * <p>The bound is measured, not assumed. {@link FakePoolFactory} counts live resources and keeps the
 * highest count it ever saw, so an overshoot shows up as {@code peakLive() > configuredSize} and
 * fails the test. {@link #thePeakLiveDetectorWouldNoticeAnOvershoot()} proves the meter itself does
 * not saturate: it happily reports {@code size + 1} when more resources really are alive.
 */
public class BoundedPoolTest {

    private static final int SIZE = 4;
    private static final Duration GENEROUS = Duration.ofSeconds(10);

    public void capacityIsFullyUsableAndNeverExceedsTheConfiguredSize() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("capacity", SIZE, GENEROUS, factory);
        AtomicReferenceArray<Lease<FakeResource>> leases = new AtomicReferenceArray<>(SIZE);
        CountDownLatch held = new CountDownLatch(SIZE);
        CountDownLatch release = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Thread> holders = new ArrayList<>();

        for (int index = 0; index < SIZE; index++) {
            final int slot = index;
            Thread holder = new Thread(() -> {
                try {
                    leases.set(slot, pool.borrow());
                } catch (Throwable failure) {
                    failures.add(failure);
                } finally {
                    held.countDown();
                }
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "pool-holder-" + index);
            holder.setDaemon(true);
            holders.add(holder);
            holder.start();
        }

        try {
            Assert.assertTrue(held.await(20, TimeUnit.SECONDS), "every slot must be lendable within the timeout");
            Assert.assertTrue(failures.isEmpty(), "no holder failed: " + failures);
            Assert.assertEquals(SIZE, factory.liveCount(), "exactly one live resource per configured slot");
            Assert.assertEquals(SIZE, factory.peakLive(), "the peak reaches the bound and not one resource more");
            Assert.assertEquals(SIZE, pool.metrics().leased(), "every slot is accounted for as leased");
            Assert.assertEquals(0, pool.metrics().available(), "no idle resource is left");
            Assert.assertEquals(SIZE, pool.metrics().capacityInUse(), "capacity in use equals the configured size");
            // Goal 01A (A4): the pool no longer publishes "reconnect" counters - only the future CM/JDBC
            // adapter can say whether a creation was a reconnect. Initial population and replacement
            // creation are now counted separately and truthfully.
            Assert.assertEquals(SIZE, pool.metrics().created(), "every initial resource was created");
            Assert.assertEquals(pool.metrics().createAttempts(), pool.metrics().created(),
                    "every creation attempt succeeded here");
            Assert.assertEquals(0L, pool.metrics().createFailures(), "no creation failed");
        } finally {
            release.countDown();
            for (Thread holder : holders) {
                holder.join(20_000);
            }
            for (int index = 0; index < SIZE; index++) {
                Lease<FakeResource> lease = leases.get(index);
                if (lease != null) {
                    lease.close();
                }
            }
            pool.close();
        }
    }

    public void concurrentBorrowersNeverSeeMoreResourcesThanTheBound() throws Exception {
        int threads = 16;
        int iterations = 40;
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("contended", SIZE, GENEROUS, factory);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<FakeResource> inUse = ConcurrentHashMap.newKeySet();
        AtomicInteger doubleHandouts = new AtomicInteger();
        AtomicInteger holders = new AtomicInteger();
        AtomicInteger peakHolders = new AtomicInteger();
        AtomicInteger borrows = new AtomicInteger();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Thread> workers = new ArrayList<>();

        for (int index = 0; index < threads; index++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await(20, TimeUnit.SECONDS);
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        try (Lease<FakeResource> lease = pool.borrow()) {
                            FakeResource resource = lease.value();
                            if (!inUse.add(resource)) {
                                doubleHandouts.incrementAndGet();
                            }
                            lease.recordOperation();
                            borrows.incrementAndGet();
                            peakHolders.accumulateAndGet(holders.incrementAndGet(), Math::max);
                            // A short hold makes the sixteen threads genuinely pile up on four slots,
                            // so the wait/release hand-off is exercised instead of serialized.
                            Thread.sleep(0L, 300_000);
                            holders.decrementAndGet();
                            inUse.remove(resource);
                        }
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                } finally {
                    done.countDown();
                }
            }, "pool-worker-" + index);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }

        start.countDown();
        try {
            Assert.assertTrue(done.await(20, TimeUnit.SECONDS), "every borrower must finish");
            Assert.assertTrue(failures.isEmpty(), "no borrow failed under contention: " + failures);
            Assert.assertEquals(0, doubleHandouts.get(), "no resource was ever handed out twice");
            Assert.assertEquals(threads * iterations, borrows.get(), "every borrow succeeded");
            Assert.assertTrue(factory.peakLive() <= SIZE,
                    "the pool never exceeded its bound (peak " + factory.peakLive() + ")");
            Assert.assertTrue(peakHolders.get() <= SIZE,
                    "no more than the configured number of leases was ever held (peak " + peakHolders.get() + ")");
            Assert.assertEquals(0L, pool.metrics().borrowTimeoutCount(), "nobody timed out while others released");
            Assert.assertEquals(threads * iterations, pool.metrics().borrowCount(), "every borrow is counted");
            // Goal 01A (A3): a borrow/use/close cycle is one AUTOMATIC usage, the explicit
            // recordOperation() in the loop body is counted separately, and operations() is their sum.
            Assert.assertEquals(threads * iterations, pool.metrics().automaticUsages(),
                    "every borrow/use/close cycle is one automatic usage");
            Assert.assertEquals(threads * iterations, pool.metrics().explicitOperations(),
                    "every explicit recordOperation() is counted on its own");
            Assert.assertEquals(2L * threads * iterations, pool.metrics().operations(),
                    "the reported total is the sum of both usage sources");
            Assert.assertEquals(SIZE, factory.liveCount(), "after the storm one resource per slot is alive");
        } finally {
            for (Thread worker : workers) {
                worker.join(5_000);
            }
            pool.close();
        }
    }

    public void thePeakLiveDetectorWouldNoticeAnOvershoot() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        List<FakeResource> resources = new ArrayList<>();
        for (int index = 0; index < SIZE + 1; index++) {
            resources.add(factory.create());
        }
        Assert.assertEquals(SIZE + 1, factory.liveCount(), "all overshooting resources are counted as live");
        Assert.assertEquals(SIZE + 1, factory.peakLive(), "the meter reports the overshoot, it does not clamp");
        Assert.assertTrue(factory.peakLive() > SIZE,
                "an overshoot is therefore visible to the pool assertion");
        for (FakeResource resource : resources) {
            resource.close();
        }
        Assert.assertEquals(0, factory.liveCount(), "closing brings the live count back down");
        Assert.assertEquals(SIZE + 1, factory.peakLive(), "the peak remembers the historical maximum");
    }

    public void aFullyLeasedPoolAppliesBackpressureWithoutCreatingExtraResources() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("timeout", 1, Duration.ofMillis(150), factory);
        Lease<FakeResource> first = pool.borrow();
        FakeResource firstResource = first.value();
        Assert.assertEquals(1, factory.createAttempts(), "exactly one resource was created");
        Assert.assertEquals("timeout#1", first.description(), "the lease identifies its resource and pool");

        long start = System.nanoTime();
        Assert.assertThrows(TimeoutException.class, pool::borrow, "an exhausted pool must time out");
        long waitedMs = (System.nanoTime() - start) / 1_000_000L;
        Assert.assertTrue(waitedMs >= 120, "the borrow waited for the configured timeout (took " + waitedMs + " ms)");
        Assert.assertTrue(waitedMs < 10_000, "the wait is bounded (took " + waitedMs + " ms)");

        PoolMetrics metrics = pool.metrics();
        Assert.assertEquals(1, metrics.borrowTimeoutCount(), "the timeout is counted");
        Assert.assertEquals(2, metrics.borrowCount(), "both borrow attempts are counted");
        Assert.assertEquals(1, factory.createAttempts(), "no emergency resource was created");
        Assert.assertEquals(1, factory.liveCount(), "exactly one resource is alive");
        Assert.assertEquals(1, metrics.leased(), "the first lease is still out");
        Assert.assertEquals(0, metrics.available(), "nothing is idle");
        Assert.assertEquals(1, metrics.capacityInUse(), "capacity stays at the bound");
        Assert.assertTrue(metrics.maxBorrowWaitMs() >= 100.0,
                "the longest wait is reported: " + metrics.maxBorrowWaitMs());
        Assert.assertTrue(metrics.averageBorrowWaitMs() > 0.0,
                "the average wait is reported: " + metrics.averageBorrowWaitMs());

        first.close();
        Lease<FakeResource> second = pool.borrow();
        Assert.assertEquals(firstResource.id(), second.value().id(), "the released resource is reused");
        Assert.assertEquals(1, factory.createAttempts(), "a release does not trigger a creation");
        Assert.assertEquals(1, pool.metrics().borrowTimeoutCount(), "the timeout counter is unchanged");
        second.close();
        pool.close();
    }

    public void anInterruptedBorrowLeavesThePoolUsable() throws Exception {
        FakePoolFactory factory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = new BoundedPool<>("interrupt", 1, Duration.ofSeconds(30), factory);
        Lease<FakeResource> held = pool.borrow();

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            waiting.countDown();
            try {
                pool.borrow();
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        }, "pool-waiter");
        waiter.setDaemon(true);
        waiter.start();

        Assert.assertTrue(waiting.await(5, TimeUnit.SECONDS), "the waiter thread started");
        Thread.sleep(50L);
        waiter.interrupt();
        waiter.join(5_000L);

        Assert.assertTrue(thrown.get() instanceof InterruptedException,
                "an interrupted borrow must throw InterruptedException, got " + thrown.get());
        Assert.assertEquals(0L, pool.metrics().borrowTimeoutCount(), "an interrupt is not a timeout");
        Assert.assertEquals(1, factory.createAttempts(), "an interrupt creates nothing");

        held.close();
        Lease<FakeResource> afterWait = pool.borrow();
        Assert.assertNotNull(afterWait, "the pool is still usable after an interrupted borrow");
        afterWait.close();
        Assert.assertTrue(pool.awaitQuiescence(Duration.ofSeconds(5)), "the pool returns to quiescence");
        pool.close();
    }

    public void constructorRejectsUnusableConfiguration() {
        FakePoolFactory factory = new FakePoolFactory();
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new BoundedPool<FakeResource>("bad", 0, Duration.ofSeconds(1), factory),
                "a pool size of 0 is refused");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new BoundedPool<FakeResource>("bad", -1, Duration.ofSeconds(1), factory),
                "a negative size is refused");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new BoundedPool<FakeResource>("bad", 1, Duration.ZERO, factory),
                "a zero borrow timeout is refused");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new BoundedPool<FakeResource>("bad", 1, Duration.ofMillis(-5), factory),
                "a negative borrow timeout is refused");
        Assert.assertThrows(NullPointerException.class,
                () -> new BoundedPool<FakeResource>("bad", 1, Duration.ofSeconds(1), null),
                "a null factory is refused");

        BoundedPool<FakeResource> pool = new BoundedPool<>("  ", 2, GENEROUS, factory);
        Assert.assertEquals("pool", pool.name(), "a blank name falls back to a stable label");
        Assert.assertEquals(2, pool.configuredSize(), "the configured size is reported");
        Assert.assertFalse(pool.isClosed(), "a new pool is open");
    }
}
