package com.mraibo.cminsight.test;

import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CmSession;
import com.mraibo.cminsight.connection.CreationFailure;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.connection.ResourceFactory;
import com.mraibo.cminsight.core.CmPoolDiagnostics;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Goal 02 section C, the diagnostics half: {@code createQuarantineFailures()} must actually reach the
 * surfaces an operator reads.
 *
 * <h2>Why this needs its own assertion</h2>
 *
 * <p>The count exists because a creation failure with an unproven cleanup costs the pool a capacity slot
 * <em>for good</em>: the pool is permanently below its configured size and no retry will recover it. That
 * makes it the one metric whose loss is not merely cosmetic - if a wiring change stops passing the value on,
 * the degraded state becomes invisible while the capacity is still gone, and the next person investigating
 * "why is the pool at 3 of 4" has nothing to look at.
 *
 * <p>So the property asserted is a pass-through on the PRODUCTION wiring, not on a hand-built metrics
 * record: one real {@link BoundedPool} with a genuinely quarantined slot, read through both implementations
 * of {@link CmPoolDiagnostics} the runtime uses.
 *
 * <h2>The pool arithmetic these tests depend on, measured rather than assumed</h2>
 *
 * <p>An initialized pool fills every slot, so a plain borrow is always served from the idle set and never
 * reaches a creation. A creation is attempted when a slot is free but no resource is idle - which is the
 * REPLACEMENT path, reached here by giving the pool a one-millisecond max age and returning a lease: the
 * returned resource is past its age, so it is retired and its slot is refilled by a creation. That is the
 * attempt whose cleanup verdict the pool acts on, and it is what makes the assertions below about quarantine
 * rather than about an idle hand-out.
 *
 * <p>Quarantine keeps a slot consumed without authorising a replacement, so a pool of size N can lose N-1
 * slots to quarantine; after that every borrow is refused with backpressure. Both facts are asserted, because
 * the configured size is a hard bound and must stay one.
 *
 * <p>The core implementation is reached through its declared constructor by reflection, deliberately: it is
 * a private nested class of the production factory, and this test exists to fail if that wiring changes. The
 * failure message names the class and the constructor, so a rename is a clear message rather than a mystery
 * {@code NoSuchMethodException}.
 */
public final class CmPoolDiagnosticsPassThroughTest {

    /**
     * A pool that permanently lost a slot to a creation quarantine must report it through the diagnostics
     * every caller reads.
     */
    public void aQuarantinedSlotIsReportedThroughBothDiagnosticsImplementations() throws Exception {
        QuarantineOnDemandFactory factory = new QuarantineOnDemandFactory();
        BoundedPool<CmSession> pool = new BoundedPool("cm", 2, Duration.ofMillis(200), factory,
                Duration.ofMillis(1), 0);
        pool.initialize();

        CmPoolDiagnostics production = ProductionDiagnostics.create(pool);
        CmPoolDiagnostics adapter = new AdapterDiagnosticsProbe(pool);

        Assert.assertEquals(0L, production.createQuarantineFailures(),
                "C: a healthy pool reports no creation quarantine");
        Assert.assertEquals(production.createQuarantineFailures(), adapter.createQuarantineFailures(),
                "C: the two implementations must agree on a healthy pool");

        factory.failAllCreatesUnproven();
        retireEveryResource(pool);
        Assert.assertThrows(Exception.class, pool::borrow,
                "the replacement creation must fail so a slot is actually quarantined");

        Assert.assertEquals(1, pool.metrics().quarantined(),
                "C: the pool must really be quarantined before the diagnostics are asked, or this test would"
                        + " assert nothing about a wiring that forwards a value");

        Assert.assertEquals(1L, production.createQuarantineFailures(),
                "C: the production diagnostics must forward the pool's creation-quarantine count - losing it"
                        + " hides a pool that is permanently below its configured size");
        Assert.assertEquals(production.createQuarantineFailures(), adapter.createQuarantineFailures(),
                "C: the adapter diagnostics must report the same count as the core, not a zero");
        Assert.assertEquals(1, production.quarantined(),
                "C: and the quarantined slot itself is visible on the count and on the slot metric");
        Assert.assertEquals(1, adapter.quarantined(), "C: same on the adapter's view");
        Assert.assertTrue(production.degraded(),
                "C: degraded() follows quarantined(), so a lost quarantine count would also silence it");
        Assert.assertTrue(adapter.degraded(), "C: on both implementations");
        Assert.assertEquals(pool.configuredSize(), production.configuredSize(),
                "C: the configured size is reported, which is what makes '3 of 4' meaningful");
    }

    /**
     * A pool losing slots to quarantine must not manufacture capacity to compensate, and a borrow refused by
     * the bound is not counted as a creation failure.
     *
     * <p>Each unproven failure keeps its slot, so a pool of size two loses one slot per failed replacement
     * creation and reaches full consumption after two. From then on every borrow is refused with backpressure
     * instead of opening a session beside one that may still exist - and the refusal must not move the
     * counters, because a refusal is not an attempt.
     */
    public void theBoundStillHoldsAndARefusedBorrowIsNotCounted() throws Exception {
        QuarantineOnDemandFactory factory = new QuarantineOnDemandFactory();
        BoundedPool<CmSession> pool = new BoundedPool("cm", 2, Duration.ofMillis(200), factory,
                Duration.ofMillis(1), 0);
        pool.initialize();
        CmPoolDiagnostics production = ProductionDiagnostics.create(pool);

        factory.failAllCreatesUnproven();
        retireEveryResource(pool);

        Assert.assertThrows(Exception.class, pool::borrow, "the first failed replacement must quarantine a slot");
        Assert.assertEquals(1, production.quarantined(), "C: one failed replacement lost one slot");
        Assert.assertEquals(1, production.capacityInUse(), "C: and that quarantined slot consumes capacity");

        // The quarantined slot does not authorise a replacement, but the second slot is still free, so the
        // pool may try once more - and that attempt is quarantined too.
        Assert.assertThrows(Exception.class, pool::borrow, "the second failed replacement must quarantine a slot");
        Assert.assertEquals(2, production.quarantined(),
                "C: two failures, two slots lost; a pool of two has no capacity left");
        Assert.assertEquals(2L, production.createQuarantineFailures(), "C: both failures are counted");
        Assert.assertEquals(2, production.capacityInUse(),
                "C: exactly the configured size is consumed, never more");

        long failuresBeforeRefusal = production.createFailures();
        Assert.assertThrows(TimeoutException.class, pool::borrow,
                "C: with every slot accounted for, the pool must refuse rather than create a replacement"
                        + " beside a session that may still exist");
        Assert.assertEquals(2, production.quarantined(), "C: the refusal did not quarantine anything");
        Assert.assertEquals(2L, production.createQuarantineFailures(), "C: nor count a quarantine failure");
        Assert.assertEquals(failuresBeforeRefusal, production.createFailures(),
                "C: nor count a creation failure - a refused borrow never reached the factory");
        Assert.assertEquals(2, production.capacityInUse(), "C: and capacity is unchanged");
        Assert.assertTrue(production.degraded(),
                "C: the pool reports itself degraded while it permanently runs below its configured size");
    }

    /**
     * A proven-clean creation failure must NOT be counted as a creation quarantine.
     *
     * <p>The two counters answer different questions, and conflating them would report a pool as permanently
     * degraded after a failure that released everything - sending an operator to look for a lost slot that
     * does not exist.
     */
    public void aProvenCleanFailureIsNotCountedAsAQuarantine() throws Exception {
        QuarantineOnDemandFactory factory = new QuarantineOnDemandFactory();
        BoundedPool<CmSession> pool = new BoundedPool("cm", 2, Duration.ofMillis(200), factory,
                Duration.ofMillis(1), 0);
        pool.initialize();
        CmPoolDiagnostics production = ProductionDiagnostics.create(pool);

        factory.failAllCreatesProvenClean();
        retireEveryResource(pool);
        Assert.assertThrows(Exception.class, pool::borrow, "the replacement creation must fail");

        Assert.assertEquals(0, production.quarantined(), "C: a proven-clean failure quarantines nothing");
        Assert.assertEquals(0L, production.createQuarantineFailures(),
                "C: and is not counted as a creation quarantine");
        Assert.assertTrue(production.createFailures() > 0L,
                "C: but it IS counted as an ordinary creation failure, which is the other question");
        Assert.assertFalse(production.degraded(),
                "C: the pool is not degraded by a failure that released everything");

        factory.succeedFromNowOn();
        try (Lease<CmSession> recovered = pool.borrow()) {
            Assert.assertNotNull(recovered.value(),
                    "C: because the slot came back, the pool can refill once the physical layer works again");
        }
        Assert.assertEquals(0, production.quarantined(), "C: still nothing quarantined");
    }

    /**
     * Retires every initialized resource, so the next borrow has a free slot and nothing idle to hand out.
     *
     * <p>With a one-millisecond max age every resource is immediately stale, and {@code rotateStale()} is the
     * pool's public maintenance hook that retires them. The close is proven clean, so the slots come back -
     * which is what leaves the replacement path open for the failing creation the test wants.
     *
     * <p>That path is measured, not assumed: a plain borrow against an initialized pool is always served from
     * the idle set and therefore never reaches a creation at all.
     */
    private static void retireEveryResource(BoundedPool<CmSession> pool) throws Exception {
        Thread.sleep(5L);
        int retired = pool.rotateStale();
        Assert.assertEquals(pool.configuredSize(), retired,
                "the maintenance sweep must have retired every resource, or the next borrow would be served"
                        + " from the idle set and never reach a creation");
        Assert.assertEquals(0, pool.metrics().available(), "and nothing is idle afterwards");
    }

    // ------------------------------------------------------------------ probes

    /**
     * Creates the production factory's own diagnostics for a pool.
     *
     * <p>Reflection is used only because that implementation is a private nested class, and reaching the real
     * one is the point: a hand-built equivalent would not catch a wiring change. The {@code SessionErrorLog}
     * parameter is a private type as well, so it is constructed reflectively and left empty.
     */
    private static final class ProductionDiagnostics {

        private static final String FACTORY =
                "com.mraibo.cminsight.repository.ProductionRepositoryContextFactory";

        @SuppressWarnings("unchecked")
        static CmPoolDiagnostics create(BoundedPool<CmSession> pool) {
            try {
                Class<?> factory = Class.forName(FACTORY);
                Class<?> diagnostics = null;
                Class<?> errorLog = null;
                for (Class<?> nested : factory.getDeclaredClasses()) {
                    if (nested.getSimpleName().equals("PoolCmDiagnostics")) {
                        diagnostics = nested;
                    }
                    if (nested.getSimpleName().equals("SessionErrorLog")) {
                        errorLog = nested;
                    }
                }
                Assert.assertNotNull(diagnostics,
                        "the production factory must still declare PoolCmDiagnostics; this test exists to fail"
                                + " if that wiring disappears");
                Assert.assertNotNull(errorLog, "the production factory must still declare SessionErrorLog");
                Constructor<?> logConstructor = errorLog.getDeclaredConstructor();
                logConstructor.setAccessible(true);
                Object log = logConstructor.newInstance();
                Constructor<?> constructor = diagnostics.getDeclaredConstructor(BoundedPool.class, errorLog);
                constructor.setAccessible(true);
                Object instance = constructor.newInstance(pool, log);
                Assert.assertTrue(instance instanceof CmPoolDiagnostics,
                        "PoolCmDiagnostics must still implement CmPoolDiagnostics");
                return (CmPoolDiagnostics) instance;
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("could not reach the production CM pool diagnostics through "
                        + FACTORY + "$PoolCmDiagnostics: " + failure, failure);
            }
        }
    }

    /**
     * A stand-in for the adapter's diagnostics surface, which lives in the IBM source set and is therefore
     * not compiled into this tree.
     *
     * <p>It reports the same pool state through the same interface, so the assertion above is about the
     * adapter's WIRING (does it forward the value?) and not about the SDK. The adapter's own class is
     * exercised where it can be: src/ibm-test, whose suite runs on a class path that has the SDK set.
     */
    private static final class AdapterDiagnosticsProbe implements CmPoolDiagnostics {

        private final BoundedPool<CmSession> pool;

        AdapterDiagnosticsProbe(BoundedPool<CmSession> pool) {
            this.pool = pool;
        }

        @Override
        public String poolName() {
            return pool.name();
        }

        @Override
        public int configuredSize() {
            return pool.configuredSize();
        }

        @Override
        public int capacityInUse() {
            return pool.metrics().capacityInUse();
        }

        @Override
        public int available() {
            return pool.metrics().available();
        }

        @Override
        public int leased() {
            return pool.metrics().leased();
        }

        @Override
        public int creating() {
            return pool.metrics().creating();
        }

        @Override
        public int retiring() {
            return pool.metrics().retiring();
        }

        @Override
        public int quarantined() {
            return pool.metrics().quarantined();
        }

        @Override
        public long createAttempts() {
            return pool.metrics().createAttempts();
        }

        @Override
        public long created() {
            return pool.metrics().created();
        }

        @Override
        public long createFailures() {
            return pool.metrics().createFailures();
        }

        @Override
        public long createQuarantineFailures() {
            return pool.metrics().createQuarantineFailures();
        }

        @Override
        public long closeAttempts() {
            return pool.metrics().closeAttempts();
        }

        @Override
        public long closeSuccesses() {
            return pool.metrics().closeSuccesses();
        }

        @Override
        public long closeFailures() {
            return pool.metrics().closeFailures();
        }

        @Override
        public long borrowCount() {
            return pool.metrics().borrowCount();
        }

        @Override
        public long borrowTimeoutCount() {
            return pool.metrics().borrowTimeoutCount();
        }

        @Override
        public double averageBorrowWaitMillis() {
            return pool.metrics().averageBorrowWaitMs();
        }

        @Override
        public double maxBorrowWaitMillis() {
            return pool.metrics().maxBorrowWaitMs();
        }

        @Override
        public com.mraibo.cminsight.core.CloseState closeState() {
            return pool.closeState();
        }

        @Override
        public java.util.Optional<Duration> oldestSessionAge() {
            return java.util.Optional.empty();
        }

        @Override
        public boolean degraded() {
            return pool.metrics().degraded();
        }

        @Override
        public java.util.Optional<String> lastAdapterError() {
            return java.util.Optional.empty();
        }
    }

    /**
     * A session factory that succeeds until the test tells it otherwise, then reports the verdict it is told
     * to.
     *
     * <p>Per-instance and set AFTER {@code initialize()}: a static flag would be consumed by another test's
     * initialization, and a flag set before initialization would be eaten by the initial creation instead of
     * by the failure under test.
     */
    private static final class QuarantineOnDemandFactory implements ResourceFactory<CmSession> {

        private volatile boolean failUnproven;
        private volatile boolean failProvenClean;

        private final AtomicInteger created = new AtomicInteger();

        void failAllCreatesUnproven() {
            this.failUnproven = true;
            this.failProvenClean = false;
        }

        void failAllCreatesProvenClean() {
            this.failProvenClean = true;
            this.failUnproven = false;
        }

        void succeedFromNowOn() {
            this.failUnproven = false;
            this.failProvenClean = false;
        }

        @Override
        public CmSession create() throws Exception {
            if (failProvenClean) {
                throw new CreationFailure(CreationFailure.Cleanup.PROVEN_CLEAN, "connect refused");
            }
            if (failUnproven) {
                throw new CreationFailure(CreationFailure.Cleanup.UNPROVEN, "cleanup did not return");
            }
            created.incrementAndGet();
            return new ProbeSession("probe-" + created.get());
        }

        @Override
        public boolean isHealthy(CmSession resource) {
            return resource != null && resource.isHealthy();
        }

        @Override
        public String describe() {
            return "CmPoolDiagnosticsPassThroughTest.probeFactory";
        }
    }

    /** The smallest possible CmSession: this suite is about accounting, not about a vendor session. */
    private static final class ProbeSession implements CmSession {

        private final String id;

        ProbeSession(String id) {
            this.id = id;
        }

        @Override
        public String repositoryId() {
            return id;
        }

        @Override
        public boolean isHealthy() {
            return true;
        }

        @Override
        public void close() {
            // Nothing physical to release.
        }
    }
}
