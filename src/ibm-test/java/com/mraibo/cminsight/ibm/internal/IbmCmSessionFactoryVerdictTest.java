package com.mraibo.cminsight.ibm.internal;

import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CmSession;
import com.mraibo.cminsight.connection.CreationFailure;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.connection.PoolException;
import com.mraibo.cminsight.core.CmSessionFactory;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mraibo.cminsight.ibm.internal.Assert.assertEquals;
import static com.mraibo.cminsight.ibm.internal.Assert.assertFalse;
import static com.mraibo.cminsight.ibm.internal.Assert.assertNotNull;
import static com.mraibo.cminsight.ibm.internal.Assert.assertThrows;
import static com.mraibo.cminsight.ibm.internal.Assert.assertTrue;

/**
 * Goal 02 section C, production half: the verdict an IBM CM session factory reports for a FAILED creation.
 *
 * <h2>Why this is the highest-risk untested decision in the goal</h2>
 *
 * <p>{@code BoundedPool} frees a reserved capacity slot when a creation attempt proves that it released
 * everything it allocated, and quarantines the slot - permanently, for the life of the pool - when it does
 * not. The pool cannot see the physical world; the ONLY input to that decision is the
 * {@link CreationFailure.Cleanup} verdict the factory reports. Connecting to CM is a two-step physical
 * operation (allocate a datastore, then connect it), so a failed {@code connect} does not prove that no
 * session exists, and its cleanup can itself fail.
 *
 * <p>Every committed pool test drives {@code FakePoolFactory}, which is HANDED a verdict. None of them can
 * see whether the ADAPTER computes the right one. An inverted split here - reporting PROVEN_CLEAN after a
 * cleanup that did not return normally - would free a slot while a physical session may still be live, and
 * the next borrow would open a replacement on top of it. The configured pool size is a hard physical bound,
 * so that is a bound breach, not a bookkeeping slip: it is precisely what section C exists to prevent.
 *
 * <p>The mapping under test is {@link IbmCmSessionFactory#create()}:
 *
 * <pre>
 *   IbmCmCleanupFailure (a teardown step threw) -> CreationFailure(UNPROVEN)   -> slot quarantined
 *   IbmCmFailure        (cleanup returned)      -> CreationFailure(PROVEN_CLEAN) -> slot released
 * </pre>
 *
 * <p>Every assertion below is on the VERDICT and on what the pool then does with it, never merely on "an
 * exception was thrown" - a missing verdict and an inverted one are both failures, and only asserting the
 * value tells them apart. The two directions are asserted against each other in one test
 * ({@link #theVerdictFollowsTheFailureTypeAndNotTheMereFactOfFailure}), so neither can pass by accident.
 */
public final class IbmCmSessionFactoryVerdictTest {

    private static final Duration PATIENT = Duration.ofSeconds(5);
    private static final Duration IMPATIENT = Duration.ofMillis(200);

    /**
     * An unproven cleanup must be reported as UNPROVEN, record that the attempt left resources behind, and
     * keep the original failure as the cause.
     */
    public void anUnprovenCleanupIsReportedAsUnproven() {
        IbmFakes.FakeConnections connections = new IbmFakes.FakeConnections();
        IbmCmSessionFactory factory = IbmFakes.factory("verdict", connections);
        connections.failNext(IbmFakes.UnexpectedFailure.cleanupUnproven("disconnect threw"));

        CreationFailure failure = assertThrows(CreationFailure.class, factory::create,
                "a failed creation must always be reported as a CreationFailure so the pool can account for it");

        assertEquals(CreationFailure.Cleanup.UNPROVEN, failure.cleanup(),
                "C: a teardown that did not return normally MUST be reported UNPROVEN - reporting PROVEN_CLEAN"
                        + " here authorises a replacement session beside one that may still be alive");
        assertFalse(failure.cleanupProven(), "C: cleanupProven() must agree with the UNPROVEN verdict");
        assertTrue(factory.lastAttemptLeftResources(),
                "C: the attempt must be recorded as having left resources behind");
        assertNotNull(failure.getCause(),
                "C: the original IbmCmCleanupFailure must survive as the cause so an operator can see which"
                        + " teardown step failed");
        assertTrue(failure.getCause() instanceof IbmCmCleanupFailure,
                "C: the cause is the cleanup failure itself, not a wrapper that hides the step: "
                        + failure.getCause());
    }

    /**
     * A cleanup that returned normally must be reported as PROVEN_CLEAN, and must not claim to have left
     * anything behind.
     */
    public void aProvenCleanupIsReportedAsProvenClean() {
        IbmFakes.FakeConnections connections = new IbmFakes.FakeConnections();
        IbmCmSessionFactory factory = IbmFakes.factory("verdict", connections);
        connections.failNext(IbmFakes.UnexpectedFailure.provenClean("connect refused"));

        CreationFailure failure = assertThrows(CreationFailure.class, factory::create,
                "a failed creation must always be reported as a CreationFailure so the pool can account for it");

        assertEquals(CreationFailure.Cleanup.PROVEN_CLEAN, failure.cleanup(),
                "C: a cleanup that returned normally proves nothing is left behind, so the slot may be"
                        + " released - reporting UNPROVEN would permanently lose capacity for no reason");
        assertTrue(failure.cleanupProven(), "C: cleanupProven() must agree with the PROVEN_CLEAN verdict");
        assertFalse(factory.lastAttemptLeftResources(),
                "C: an attempt whose cleanup was proven must not be recorded as having left resources behind");
        assertTrue(failure.getCause() instanceof IbmCmFailure,
                "C: the cause is the sanitised IbmCmFailure that produced the verdict: " + failure.getCause());
    }

    /**
     * The verdict must follow the failure TYPE, not the mere fact that something was thrown.
     *
     * <p>This is the anti-inversion case stated directly: one physical situation - a connect that failed
     * after allocating - produces opposite pool outcomes depending on one type. Both directions are asserted
     * together and are asserted to differ, so a collapsed or swapped split fails here.
     */
    public void theVerdictFollowsTheFailureTypeAndNotTheMereFactOfFailure() {
        IbmFakes.FakeConnections unprovenConnections = new IbmFakes.FakeConnections();
        unprovenConnections.failNext(IbmFakes.UnexpectedFailure.cleanupUnproven("disconnect threw"));
        CreationFailure unproven = assertThrows(CreationFailure.class,
                IbmFakes.factory("verdict", unprovenConnections)::create,
                "the unproven direction must throw");
        IbmFakes.FakeConnections provenConnections = new IbmFakes.FakeConnections();
        provenConnections.failNext(IbmFakes.UnexpectedFailure.provenClean("connect refused"));
        CreationFailure proven = assertThrows(CreationFailure.class,
                IbmFakes.factory("verdict", provenConnections)::create,
                "the proven direction must throw");

        assertEquals(CreationFailure.Cleanup.UNPROVEN, unproven.cleanup(),
                "C: IbmCmCleanupFailure is the only evidence of an unproven teardown and must map to UNPROVEN");
        assertEquals(CreationFailure.Cleanup.PROVEN_CLEAN, proven.cleanup(),
                "C: IbmCmFailure means cleanup returned, so it must map to PROVEN_CLEAN");
        assertFalse(unproven.cleanup() == proven.cleanup(),
                "C: the two failure types must NOT produce the same verdict - an inverted or collapsed split"
                        + " would make the pool free a slot it must quarantine");
    }

    /** A checked exception that is not one of the adapter's two failure types must travel unchanged. */
    public void anOrdinaryFailureIsNotSilentlyConvertedIntoAVerdict() {
        IbmFakes.FakeConnections connections = new IbmFakes.FakeConnections();
        IbmCmSessionFactory factory = IbmFakes.factory("verdict", connections);
        connections.failNext(IbmFakes.UnexpectedFailure.ordinaryException("no verdict here"));

        Exception failure = assertThrows(Exception.class, factory::create, "the failure must still surface");
        assertFalse(failure instanceof CreationFailure,
                "C: a failure that carries no cleanup evidence must not be relabelled as a verdict. The pool's"
                        + " Goal 02A default for anything that is not a CreationFailure is to QUARANTINE the"
                        + " reserved slot, so inventing a PROVEN_CLEAN here would release capacity the adapter"
                        + " never proved it released, and inventing UNPROVEN would lose capacity for a failure"
                        + " that says nothing at all. Got: " + failure);
    }

    /** One unproven creation must quarantine the pool's only slot, and no replacement may be created. */
    public void anUnprovenCreationVerdictQuarantinesThePoolSlot() throws Exception {
        IbmFakes.FakeConnections connections = new IbmFakes.FakeConnections();
        IbmCmSessionFactory sessions = IbmFakes.factory("verdict", connections);
        BoundedPool<CmSession> pool = new BoundedPool<>("cm", 1, IMPATIENT,
                IbmFakes.resourceFactory(IbmFakes.profile("verdict"), sessions));
        connections.failNext(IbmFakes.UnexpectedFailure.cleanupUnproven("destroy threw"));

        PoolException surfaced = assertThrows(PoolException.class, pool::borrow,
                "the borrow must fail with the pool's own failure type");
        assertTrue(surfaced.getCause() instanceof CreationFailure,
                "C: the pool must surface the adapter's own verdict as the cause, so the cleanup outcome is"
                        + " still readable: " + surfaced.getCause());
        assertEquals(CreationFailure.Cleanup.UNPROVEN,
                ((CreationFailure) surfaced.getCause()).cleanup(),
                "C: the verdict must survive to the caller unchanged");

        assertEquals(1, pool.metrics().quarantined(),
                "C: an UNPROVEN creation verdict must quarantine the reserved slot");
        assertEquals(1L, pool.metrics().createQuarantineFailures(),
                "C: the quarantine must be counted so the lost slot shows up in diagnostics");
        assertEquals(1, pool.metrics().capacityInUse(), "C: the quarantined slot still consumes capacity");
        assertTrue(pool.metrics().degraded(), "C: a quarantined slot makes the pool report itself degraded");

        int attemptsBefore = connections.connectAttempts();
        assertThrows(TimeoutException.class, pool::borrow,
                "C: a quarantined slot must not authorise a replacement session");
        assertEquals(attemptsBefore, connections.connectAttempts(),
                "C: no physical connection may be attempted on top of a session that may still be alive");
    }

    /** A proven-clean creation must release the slot, so the next borrow can create a replacement. */
    public void aProvenCleanCreationVerdictReleasesThePoolSlot() throws Exception {
        IbmFakes.FakeConnections connections = new IbmFakes.FakeConnections();
        IbmCmSessionFactory sessions = IbmFakes.factory("verdict", connections);
        BoundedPool<CmSession> pool = new BoundedPool<>("cm", 1, PATIENT,
                IbmFakes.resourceFactory(IbmFakes.profile("verdict"), sessions));
        connections.failNext(IbmFakes.UnexpectedFailure.provenClean("connect refused"));

        assertThrows(PoolException.class, pool::borrow, "the borrow must fail");

        assertEquals(0, pool.metrics().quarantined(), "C: a proven-clean verdict must not quarantine anything");
        assertEquals(0L, pool.metrics().createQuarantineFailures(),
                "C: no quarantine may be counted for a proven-clean failure");
        assertEquals(0, pool.metrics().capacityInUse(), "C: the reserved slot must be released");
        assertFalse(pool.metrics().degraded(),
                "C: the pool is not degraded by a failure that released everything");

        try (Lease<CmSession> lease = pool.borrow()) {
            assertNotNull(lease.value(), "C: the pool recovers once the physical layer works again");
            assertTrue(lease.value().isHealthy(), "C: a connected session is healthy");
        }
        pool.close();
    }

    /**
     * Goal 02B section A, the OPPOSITE CONTROL: an UNKNOWN failure whose physical outcome is genuinely
     * unknown - thrown AFTER the allocation boundary - must still QUARANTINE.
     *
     * <h2>Why this control exists beside the credential-loss regression</h2>
     *
     * <p>Section A moves profile/credential failures that provably happen before any datastore exists onto
     * the explicit {@code PROVEN_CLEAN} path, so a credential that disappears after activation cannot burn a
     * pool slot. That is only correct because THOSE failures know no physical resource was allocated. The
     * danger the fix must not introduce is a blanket relabel: catching everything and calling it clean would
     * free the slot of an attempt that may have left a live session behind, which is a bound breach rather
     * than a capacity-tuning mistake. The credential-loss regression (owned by the section A work itself)
     * proves the known-clean direction; this test proves the OTHER direction, on the same production seam -
     * the real {@link IbmCmSessionFactory} inside a real {@code BoundedPool}.
     *
     * <h2>What "unknown after the allocation boundary" means here</h2>
     *
     * <p>A {@link IbmCmConnectionFactory} allocates its datastore and then throws a plain
     * {@link IllegalStateException}: no typed verdict, no cleanup evidence at all. The production factory
     * builds its handle BEFORE calling {@code connect()}, so this is exactly the shape in which the
     * datastore exists while the caller knows nothing about it. The adapter must not invent a verdict from
     * it, and the pool must not free the reserved slot - the factory's own diagnostics must say the attempt
     * may have left resources behind.
     */
    public void anUnknownFailureAfterTheAllocationBoundaryStillQuarantinesTheSlot() throws Exception {
        AtomicInteger connectAttempts = new AtomicInteger();
        IbmFakes.FakeReadDatastore allocated = new IbmFakes.FakeReadDatastore();
        IbmCmConnectionFactory allocatesThenFailsUnknown = new IbmCmConnectionFactory() {
            @Override
            public IcmDatastore connect(String ssid, String user, String password) {
                connectAttempts.incrementAndGet();
                // The datastore physically exists - this fake holds it, exactly as the production factory
                // holds its handle - and the failure that follows carries no information about whether it was
                // cleaned up. Nothing below pretends otherwise: no destroy/disconnect is attempted, which is
                // why the attempt cannot be reported as clean.
                return failAfterAllocation(allocated);
            }
        };
        IbmCmSessionFactory sessions = IbmFakes.factory("control", allocatesThenFailsUnknown);
        BoundedPool<CmSession> pool = new BoundedPool<>("cm", 1, PATIENT,
                IbmFakes.resourceFactory(IbmFakes.profile("control"), sessions));

        PoolException surfaced = assertThrows(PoolException.class, pool::borrow,
                "the borrow must surface the pool's own failure type for an unknown creation outcome");
        assertTrue(surfaced.getCause() instanceof IllegalStateException,
                "A: the untyped failure reaches the caller unchanged - a factory that relabelled it as a"
                        + " verdict would hide that nothing about the physical outcome is known: "
                        + surfaced.getCause());
        assertFalse(surfaced.getCause() instanceof CreationFailure,
                "A: and it must NOT become a CreationFailure at all, because the only relabelling available"
                        + " here would be PROVEN_CLEAN - which would authorise a replacement session beside"
                        + " one that may still be alive. Got: " + surfaced.getCause());

        assertEquals(0, allocated.destroyCalls(),
                "A: no cleanup was evidenced for the allocated datastore - the failure says nothing about it,"
                        + " so 'nothing is left behind' is not a claim this attempt can make");
        assertEquals(1, pool.metrics().quarantined(),
                "A: an UNKNOWN post-allocation failure must still quarantine the reserved slot");
        assertEquals(1L, pool.metrics().createQuarantineFailures(),
                "A: and the lost slot must be visible as a creation quarantine in diagnostics");
        assertEquals(1, pool.metrics().capacityInUse(), "A: the quarantined slot still consumes capacity");
        assertEquals(0, pool.metrics().creating(), "A: the reservation is resolved, not left in flight");
        assertTrue(pool.metrics().degraded(), "A: the pool reports the capacity it could not prove released");
        assertTrue(sessions.lastAttemptLeftResources(),
                "A: the attempt must be recorded as possibly having left resources behind - an unknown"
                        + " outcome is the conservative/unproven reading, never 'left nothing'");

        int attemptsBefore = connectAttempts.get();
        assertThrows(TimeoutException.class, pool::borrow,
                "A: the quarantined slot must not authorise a replacement physical connection");
        assertEquals(attemptsBefore, connectAttempts.get(),
                "A: and no further connect was attempted on top of the unknown outcome");
        pool.close();
    }

    /**
     * Allocates the datastore and then fails with a plain, untyped exception - the shape that carries no
     * cleanup evidence and therefore cannot be reported as clean.
     *
     * <p>Never returns: the {@code IcmDatastore} return type exists only so the fake satisfies the
     * interface's own signature.
     */
    private static IcmDatastore failAfterAllocation(IcmDatastore allocated) {
        throw new IllegalStateException("simulated untyped failure after the allocation boundary: "
                + allocated.getClass().getSimpleName() + " exists and nothing cleaned it up");
    }

    /** A successful creation must return a live session bound to its repository, with nothing left behind. */
    public void aSuccessfulCreationReturnsALiveSession() {
        IbmFakes.FakeConnections connections = new IbmFakes.FakeConnections();
        IbmCmSessionFactory factory = IbmFakes.factory("verdict", connections);

        CmSessionFactory seam = factory;
        CmSession opened;
        try {
            opened = seam.open(IbmFakes.profile("verdict"));
        } catch (Exception failure) {
            Assert.fail("a successful creation must not throw: " + failure);
            return;
        }

        assertNotNull(opened, "C: a successful creation returns a session");
        assertTrue(opened.isHealthy(), "C: a session is LIVE once connect returned");
        assertEquals("verdict", opened.repositoryId(), "C: the session is bound to its repository");
        assertEquals(1, connections.connectAttempts(), "C: exactly one physical connection was made");
        assertFalse(factory.lastAttemptLeftResources(), "C: a successful attempt left nothing behind");
        opened.close();
    }
}
