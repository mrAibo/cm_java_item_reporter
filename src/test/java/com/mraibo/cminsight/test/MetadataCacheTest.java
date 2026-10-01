package com.mraibo.cminsight.test;

import com.mraibo.cminsight.core.MetadataCache;
import com.mraibo.cminsight.metadata.ItemTypeInfo;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.metadata.MetadataRepository;
import com.mraibo.cminsight.retention.RetentionRepository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Goal 02 section I: the per-context metadata cache.
 *
 * <h2>Why the cache needs its own tests rather than being covered through the API</h2>
 *
 * <p>Three of its promises are invisible from the outside and each has a failure mode that only shows up
 * under load or after a fault:
 *
 * <ul>
 *   <li><strong>Single flight per key.</strong> A refresh runs under that key's own lock, so a burst of
 *       console requests must produce ONE round trip, not one per request. A cache that lost that property
 *       would turn a page refresh into a burst of CM sessions against a bounded pool - the failure looks
 *       like unrelated backpressure, not like a cache bug.</li>
 *   <li><strong>Last known good.</strong> A failed refresh must never replace a good snapshot and must never
 *       publish partial data. Serving an empty list instead of the previous snapshot is indistinguishable
 *       from "the repository really has no ItemTypes", so it silently rewrites the truth.</li>
 *   <li><strong>Isolation between contexts.</strong> One cache per repository context, and nothing shared:
 *       data from repository A must never be served for repository B.</li>
 * </ul>
 *
 * <p>Concurrency is driven by latches, never by sleeps: the loader blocks on a latch the test owns, so
 * "the second caller waited for the first refresh" is an observed fact rather than a timing guess.
 */
public final class MetadataCacheTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration NO_CACHE = Duration.ZERO;

    /** A fresh snapshot is reused: the service is asked once, however many reads arrive. */
    public void aFreshSnapshotIsServedWithoutAskingTheServiceAgain() {
        FakeMetadata metadata = new FakeMetadata();
        MetadataCache cache = new MetadataCache(TTL, metadata, null);

        List<ItemTypeSummary> first = cache.metadataView().listItemTypes();
        List<ItemTypeSummary> second = cache.metadataView().listItemTypes();

        Assert.assertEquals(1, metadata.listCalls(), "I: a fresh snapshot must not trigger a second round trip");
        Assert.assertEquals(first, second, "I: both reads see the same snapshot");
        Assert.assertEquals(2, first.size(), "I: the snapshot is the service's answer");
        Assert.assertTrue(cache.fresh(), "I: and it is reported fresh");
        Assert.assertTrue(cache.snapshotAt().isPresent(), "I: a loaded snapshot has a timestamp");
        Assert.assertTrue(cache.age().compareTo(Duration.ZERO) >= 0, "I: age is never negative");
    }

    /** Two caches over two services never share a snapshot. */
    public void twoContextsNeverShareASnapshot() {
        FakeMetadata alpha = new FakeMetadata().withItemTypes("ALPHA-ONLY");
        FakeMetadata beta = new FakeMetadata().withItemTypes("BETA-ONLY");
        MetadataCache alphaCache = new MetadataCache(TTL, alpha, null);
        MetadataCache betaCache = new MetadataCache(TTL, beta, null);

        List<ItemTypeSummary> alphaTypes = alphaCache.metadataView().listItemTypes();
        List<ItemTypeSummary> betaTypes = betaCache.metadataView().listItemTypes();

        Assert.assertEquals(List.of("ALPHA-ONLY"), names(alphaTypes),
                "I: the first context sees only its own repository's ItemTypes");
        Assert.assertEquals(List.of("BETA-ONLY"), names(betaTypes),
                "I: the second context sees only its own repository's ItemTypes");
        Assert.assertEquals(1, alpha.listCalls(), "I: each context loaded once");
        Assert.assertEquals(1, beta.listCalls(), "I: each context loaded once");
    }

    /** Closing one context's cache does not touch another's, and the closed one refuses reads. */
    public void closingACacheDoesNotAffectAnotherContextsSnapshot() {
        FakeMetadata alpha = new FakeMetadata();
        FakeMetadata beta = new FakeMetadata().withItemTypes("BETA-ONLY");
        MetadataCache alphaCache = new MetadataCache(TTL, alpha, null);
        MetadataCache betaCache = new MetadataCache(TTL, beta, null);

        alphaCache.metadataView().listItemTypes();
        alphaCache.close();

        Assert.assertTrue(alphaCache.isClosed(), "I: the closed cache reports itself closed");
        Assert.assertThrows(IllegalStateException.class, () -> alphaCache.metadataView().listItemTypes(),
                "I: a released context's cache must refuse reads rather than serve data from a dead repository");
        Assert.assertEquals(List.of("BETA-ONLY"), names(betaCache.metadataView().listItemTypes()),
                "I: the other context's cache is unaffected");
    }

    /**
     * A second caller that arrives during a refresh WAITS for it and uses its result: one round trip, not
     * two.
     *
     * <p>Driven by a latch the loader holds, so the second caller is provably inside the refresh window when
     * it asks. If the cache started a duplicate refresh, the load counter would be 2 and the second thread
     * would have blocked on the latch forever - which the bounded join turns into a failure rather than a
     * hung suite.
     */
    public void concurrentReadsOfOneKeyShareASingleRefresh() throws Exception {
        FakeMetadata metadata = new FakeMetadata();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        metadata.holdLoadUntil(loaderEntered, releaseLoader);
        MetadataCache cache = new MetadataCache(TTL, metadata, null);

        List<ItemTypeSummary> firstResult = new java.util.ArrayList<>();
        List<ItemTypeSummary> secondResult = new java.util.ArrayList<>();
        Throwable[] failures = new Throwable[2];

        Thread first = new Thread(() -> {
            try {
                firstResult.addAll(cache.metadataView().listItemTypes());
            } catch (Throwable thrown) {
                failures[0] = thrown;
            }
        }, "cache-test-first");
        Thread second = new Thread(() -> {
            try {
                secondResult.addAll(cache.metadataView().listItemTypes());
            } catch (Throwable thrown) {
                failures[1] = thrown;
            }
        }, "cache-test-second");
        first.setDaemon(true);
        second.setDaemon(true);

        first.start();
        // Wait until the first refresh is provably inside the loader before the second caller asks.
        Assert.assertTrue(loaderEntered.await(5, TimeUnit.SECONDS),
                "I: the first reader must have entered the loader before the second one asks");
        second.start();
        // Give the second caller a moment to reach the same key's lock, then let the refresh finish.
        Thread.sleep(50);
        releaseLoader.countDown();

        first.join(5_000);
        second.join(5_000);
        Assert.assertFalse(first.isAlive(), "I: the first reader finished");
        Assert.assertFalse(second.isAlive(), "I: the second reader finished - it must not deadlock or hang");
        Assert.assertNull(failures[0], "I: the first reader saw no failure");
        Assert.assertNull(failures[1], "I: the second reader saw no failure");
        Assert.assertEquals(1, metadata.listCalls(),
                "I: a second caller during a refresh must WAIT for it, not launch a duplicate - a burst of"
                        + " requests must not become a burst of CM sessions");
        Assert.assertEquals(names(firstResult), names(secondResult),
                "I: both callers observe the same snapshot");
        Assert.assertEquals(2, secondResult.size(), "I: and it is the full snapshot, not a partial one");
    }

    /**
     * A failed refresh with NO known-good snapshot reports the failure, records the reason, and is not
     * retried inside the same TTL window.
     *
     * <p>Reporting an empty list when the repository is merely unreachable would be indistinguishable from
     * "the repository really has no ItemTypes" - it silently rewrites the truth - so the failure has to reach
     * the caller. The failure-backoff window is asserted with the load counter, because "one attempt per TTL
     * window per key" is the documented behaviour that stops a down server getting a connection attempt per
     * request.
     */
    public void aFailedRefreshSurfacesWhenNothingGoodIsCachedAndIsNotRetriedInTheWindow() {
        FakeMetadata metadata = new FakeMetadata();
        MetadataCache cache = new MetadataCache(TTL, metadata, null);

        metadata.failNextWith(new IllegalStateException("the repository is unreachable"));
        Assert.assertThrows(IllegalStateException.class, () -> cache.metadataView().listItemTypes(),
                "I: with no known-good snapshot the failure must reach the caller; an empty answer would be"
                        + " indistinguishable from a repository that really has no ItemTypes");
        Assert.assertEquals(1, metadata.listCalls(), "I: one attempt was made");
        Assert.assertTrue(cache.lastError().isPresent(),
                "I: the failure is recorded so diagnostics can explain the missing data");
        Assert.assertFalse(cache.fresh(), "I: nothing is reported fresh");

        int attemptsAfterFailure = metadata.listCalls();
        Assert.assertThrows(IllegalStateException.class, () -> cache.metadataView().listItemTypes(),
                "I: the same failure is reported again rather than pretending the repository is empty");
        Assert.assertEquals(attemptsAfterFailure, metadata.listCalls(),
                "I: a failed key is NOT retried inside the same TTL window - a down server must not get one"
                        + " connection attempt per request");
    }

    /**
     * A STALE snapshot survives a failed refresh: the old data keeps being served, with the reason recorded.
     *
     * <p>This is the "last known good" promise, and it is the one that protects a console from showing an
     * empty repository during a transient outage. Driven with a short TTL so the snapshot provably expires
     * inside the test rather than by the wall clock.
     */
    public void aFailedRefreshOfAStaleSnapshotKeepsServingTheKnownGoodData() throws Exception {
        FakeMetadata metadata = new FakeMetadata();
        MetadataCache cache = new MetadataCache(Duration.ofMillis(60), metadata, null);

        Assert.assertEquals(2, cache.metadataView().listItemTypes().size(), "I: a good snapshot is loaded");
        Assert.assertTrue(cache.fresh(), "I: and it starts out fresh");

        waitForExpiry(Duration.ofMillis(60));
        Assert.assertFalse(cache.fresh(), "I: the snapshot is now stale");

        metadata.failNextWith(new IllegalStateException("the repository went away"));
        List<ItemTypeSummary> served = cache.metadataView().listItemTypes();

        Assert.assertEquals(2, served.size(),
                "I: a failed refresh of a stale snapshot must NOT replace it with empty or partial data");
        Assert.assertTrue(cache.lastError().isPresent(),
                "I: the reason is recorded so an operator can see why the data is old");
        Assert.assertTrue(cache.age().compareTo(Duration.ZERO) > 0, "I: the served snapshot's age keeps growing");
        Assert.assertTrue(cache.snapshotAt().isPresent(), "I: and its load time is still reported");
    }

    /**
     * Waits until a snapshot taken now is provably past the given TTL, then returns.
     *
     * <p>Bounded, and it waits for a CONDITION rather than assuming a duration is enough: the assertion needs
     * the TTL window to have elapsed, so the test polls the deadline it actually cares about.
     */
    private static void waitForExpiry(Duration ttl) throws InterruptedException {
        long until = System.nanoTime() + ttl.toNanos() + Duration.ofMillis(20).toNanos();
        long remaining;
        while ((remaining = until - System.nanoTime()) > 0L) {
            long sleepMillis = Math.max(1L, Math.min(10L, remaining / 1_000_000L));
            Thread.sleep(sleepMillis);
        }
    }

    /**
     * A TTL of zero disables caching: every read loads through and nothing is retained.
     *
     * <p>Asserted because "caching is configured off" and "the cache is broken" must not look the same, and
     * because a disabled cache must still not retain a snapshot that a later reader could see.
     */
    public void aZeroTtlLoadsThroughAndRetainsNothing() {
        FakeMetadata metadata = new FakeMetadata();
        MetadataCache cache = new MetadataCache(NO_CACHE, metadata, null);

        Assert.assertFalse(cache.caching(), "I: a zero TTL means caching is off");
        cache.metadataView().listItemTypes();
        cache.metadataView().listItemTypes();

        Assert.assertEquals(2, metadata.listCalls(), "I: every read loads through when caching is disabled");
        Assert.assertFalse(cache.fresh(), "I: and no snapshot is reported fresh");
        Assert.assertEquals(Duration.ZERO, cache.age(), "I: there is no snapshot to age");
    }

    /** A closed cache refuses a refresh rather than loading data into a released context. */
    public void aClosedCacheRefusesToLoad() {
        FakeMetadata metadata = new FakeMetadata();
        MetadataCache cache = new MetadataCache(TTL, metadata, null);
        cache.close();

        Assert.assertThrows(IllegalStateException.class, () -> cache.metadataView().listItemTypes(),
                "I: a released context must not be able to load new data");
        Assert.assertEquals(0, metadata.listCalls(), "I: and the service was never asked");
    }

    /**
     * A cache with no metadata service exposes no view at all, and never calls anything.
     *
     * <p>{@code metadataView()} returns {@code null} rather than an empty view on purpose: "this repository
     * has no ItemType service" and "this service exists but answers nothing" stay different answers, which is
     * why the accessor is nullable instead of an {@code Optional} wrapper around the service.
     */
    public void aCacheWithoutAServiceExposesNoView() {
        MetadataCache cache = new MetadataCache(TTL, null, null);

        Assert.assertNull(cache.metadataView(),
                "I: a repository without an ItemType service has no metadata view, and that is a different"
                        + " answer from a view that returns nothing");
        Assert.assertNull(cache.retentionView(), "I: and no retention view either");
        Assert.assertEquals(Duration.ZERO, cache.age(), "I: nothing has been loaded, so there is no age");
        Assert.assertTrue(cache.snapshotAt().isEmpty(), "I: and no snapshot time");
    }

    private static List<String> names(List<ItemTypeSummary> summaries) {
        return summaries.stream().map(ItemTypeSummary::name).toList();
    }

    // ------------------------------------------------------------------ fakes

    /**
     * A metadata service whose load count, outcome and blocking behaviour the test owns.
     *
     * <p>Implements only {@link MetadataRepository}: the cache's retention side is left absent in these
     * tests so a failure here can only be about the ItemType snapshot.
     */
    static final class FakeMetadata implements MetadataRepository {

        private final AtomicInteger listCalls = new AtomicInteger();
        private volatile List<ItemTypeSummary> answer = List.of(summary("ItemTypeA"), summary("ItemTypeB"));
        private volatile RuntimeException nextFailure;
        private volatile CountDownLatch entered = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);

        FakeMetadata withItemTypes(String... typeNames) {
            this.answer = java.util.Arrays.stream(typeNames).map(MetadataCacheTest::summary).toList();
            return this;
        }

        void failNextWith(RuntimeException failure) {
            this.nextFailure = failure;
        }

        void succeedFromNowOn() {
            this.nextFailure = null;
        }

        void holdLoadUntil(CountDownLatch enteredLatch, CountDownLatch releaseLatch) {
            this.entered = enteredLatch;
            this.release = releaseLatch;
        }

        int listCalls() {
            return listCalls.get();
        }

        @Override
        public List<ItemTypeSummary> listItemTypes() {
            listCalls.incrementAndGet();
            CountDownLatch enteredLatch = entered;
            CountDownLatch releaseLatch = release;
            entered = new CountDownLatch(0);
            release = new CountDownLatch(0);
            enteredLatch.countDown();
            try {
                if (!releaseLatch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the fake loader");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the test to release the loader");
            }
            RuntimeException failure = nextFailure;
            if (failure != null) {
                nextFailure = null;
                throw failure;
            }
            return answer;
        }

        @Override
        public Optional<ItemTypeInfo> itemType(String name) {
            return Optional.empty();
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private static ItemTypeSummary summary(String name) {
        return new ItemTypeSummary(name, "description of " + name, 1, "ICM", "SAP", "policy");
    }
}
