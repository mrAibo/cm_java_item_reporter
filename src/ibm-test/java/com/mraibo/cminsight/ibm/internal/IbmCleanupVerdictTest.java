package com.mraibo.cminsight.ibm.internal;

import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CmSession;
import com.mraibo.cminsight.connection.PoolException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.mraibo.cminsight.ibm.internal.Assert.assertEquals;
import static com.mraibo.cminsight.ibm.internal.Assert.assertFalse;
import static com.mraibo.cminsight.ibm.internal.Assert.assertThrows;
import static com.mraibo.cminsight.ibm.internal.Assert.assertTrue;

/**
 * The two obligations {@link IbmCmSession} carries that a caller cannot check for itself.
 *
 * <h2>1. Teardown is attempted in full, and its outcome is never assumed</h2>
 *
 * <p>{@code close()} runs {@code disconnect()} and then {@code destroy()} - EVERY step, even when an
 * earlier one threw - and reports an unproven outcome by throwing {@link IbmCmCleanupFailure}. That throw is
 * not a nuisance: it is the signal {@code BoundedPool} converts into a quarantined capacity slot. A
 * {@code close()} that swallowed a failed teardown and returned normally would let the pool free the slot
 * while the physical session may still exist, and the next borrow would open a replacement beside it. The
 * hard physical bound is what section C protects, so "do not swallow failures" is a correctness requirement
 * rather than error handling.
 *
 * <p>It must also be idempotent: the pool and an activation-failure path can race to release the same
 * session, and a second teardown would re-issue SDK calls against a handle that has already been destroyed.
 *
 * <h2>2. The health probe must not touch the server</h2>
 *
 * <p>{@code BoundedPool} calls {@code isHealthy()} while holding its own lock - on the borrow path, the
 * return path and during a rotation sweep - and {@code closeState()} takes that same lock. A probe that made
 * a vendor call would stall every concurrent borrow AND the shutdown decision a repository switch depends
 * on. So the only acceptable implementation is a local read, and the assertion is a call COUNT on the fake
 * datastore: {@code isConnected()} must never be called from the health path.
 */
public final class IbmCleanupVerdictTest {

    /**
     * Only ONE teardown step may be attempted per session, even when it failed.
     *
     * <p>The failing step is asserted to have been attempted exactly once and the failing session to be
     * unhealthy, so a "retry the teardown" change is caught rather than silently issuing a second SDK call
     * against a destroyed handle.
     */
    public void aFailedTeardownIsAttemptedOnceAndThenReported() {
        IbmFakes.FakeDatastore datastore = new IbmFakes.FakeDatastore();
        datastore.onDisconnectThrow(new IllegalStateException("disconnect threw"));
        IbmCmSession session = IbmCmSession.live("lifecycle", datastore, null);

        assertThrows(IbmCmCleanupFailure.class, session::close,
                "C: a close whose teardown did not return normally MUST throw so the pool can quarantine the"
                        + " slot; returning normally would free capacity while the session may still be alive");

        assertEquals(1, datastore.disconnectCalls(), "C: the failing disconnect is attempted exactly once");
        assertEquals(1, datastore.destroyCalls(),
                "C: destroy must still be attempted - a half-torn-down session that is never destroyed is the"
                        + " resource the physical bound cannot account for");
        assertFalse(session.isHealthy(), "C: a closed session is never healthy");
    }

    /**
     * A second close is a no-op: no teardown step runs again, and no exception is thrown.
     *
     * <p>The first close FAILS in this test, which is the hard direction: idempotence must hold after a
     * failed teardown too, because the state it leaves is terminal and the physical outcome has already been
     * reported.
     */
    public void closeIsIdempotentAfterAFailedTeardown() {
        IbmFakes.FakeDatastore datastore = new IbmFakes.FakeDatastore();
        datastore.onDisconnectThrow(new IllegalStateException("disconnect threw"));
        IbmCmSession session = IbmCmSession.live("lifecycle", datastore, null);

        assertThrows(IbmCmCleanupFailure.class, session::close,
                "the first close must report the unproven teardown");
        int disconnectsAfterFirst = datastore.disconnectCalls();
        int destroysAfterFirst = datastore.destroyCalls();

        session.close();

        assertEquals(disconnectsAfterFirst, datastore.disconnectCalls(),
                "C: a second close must not attempt disconnect again");
        assertEquals(destroysAfterFirst, datastore.destroyCalls(),
                "C: a second close must not attempt destroy again");
    }

    /** The clean direction: every step returns, nothing is thrown, and the order is the proven one. */
    public void aProvenTeardownReportsNothing() {
        IbmFakes.FakeDatastore datastore = new IbmFakes.FakeDatastore();
        IbmCmSession session = IbmCmSession.live("lifecycle", datastore, null);

        session.close();

        assertEquals(1, datastore.disconnectCalls(), "C: a connected datastore is disconnected once");
        assertEquals(1, datastore.destroyCalls(), "C: and destroyed once");
        assertFalse(session.isHealthy(), "C: the session is no longer healthy after teardown");
        assertEquals(List.of("isConnected", "disconnect", "destroy"), new ArrayList<>(datastore.calls),
                "C: teardown order is isConnected, then disconnect, then destroy");
    }

    /**
     * A failure from {@code destroy()} alone is still an unproven teardown, and the step is named.
     *
     * <p>Kept separate from the disconnect case because the two produce different diagnostic text: an
     * operator has to be able to see WHICH step could not be proven.
     */
    public void aDestroyFailureIsReportedAsUnprovenWithItsStep() {
        IbmFakes.FakeDatastore datastore = new IbmFakes.FakeDatastore();
        datastore.onDestroyThrow(new IllegalStateException("destroy threw"));
        IbmCmSession session = IbmCmSession.live("lifecycle", datastore, null);

        IbmCmCleanupFailure failure = assertThrows(IbmCmCleanupFailure.class, session::close,
                "a destroy that threw leaves the physical outcome unproven and must be reported");

        assertEquals("destroy", failure.step(),
                "C: the reported step names the teardown step that could not be proven");
        assertEquals(1, datastore.disconnectCalls(), "C: disconnect still ran");
        assertEquals(1, datastore.destroyCalls(), "C: the failing destroy ran once");
    }

    /**
     * A datastore that reports itself NOT connected skips the disconnect but must still be destroyed.
     *
     * <p>This is the one place {@code isConnected()} is legitimately consulted; the unknown-answer case
     * below is its conservative counterpart.
     */
    public void aDisconnectedDatastoreSkipsDisconnectButIsStillDestroyed() {
        IbmFakes.FakeDatastore datastore = new IbmFakes.FakeDatastore();
        datastore.setConnected(false);
        IbmCmSession session = IbmCmSession.live("lifecycle", datastore, null);

        session.close();

        assertEquals(0, datastore.disconnectCalls(),
                "C: a datastore that is not connected must not be disconnected");
        assertEquals(1, datastore.destroyCalls(),
                "C: but the local SDK object is still destroyed - destroy is not conditional");
        assertTrue(datastore.calls.contains("isConnected"), "C: the connectedness question was asked");
    }

    /**
     * A datastore that cannot answer "are you connected?" is read as CONNECTED, so teardown is attempted.
     *
     * <p>The conservative direction: if the answer is unknown, skipping the disconnect would leave a live
     * session unreferenced, whereas attempting it and failing quarantines the slot - the safe direction.
     */
    public void anUnknownConnectednessReadsAsConnectedSoTeardownIsAttempted() {
        IbmFakes.FakeDatastore datastore = new IbmFakes.FakeDatastore();
        datastore.onIsConnectedThrow(new IllegalStateException("cannot tell"));
        IbmCmSession session = IbmCmSession.live("lifecycle", datastore, null);

        session.close();

        assertEquals(1, datastore.disconnectCalls(),
                "C: an unknown connectedness must attempt the disconnect rather than skip a needed teardown");
        assertEquals(1, datastore.destroyCalls(), "C: and destroy still runs");
    }

    /**
     * The health probe is a local read: it must never ask the SDK.
     *
     * <p>Asserted with a call count on the fake datastore, because "it is only a boolean field today" is
     * exactly the claim that decays. The cost of decay is a vendor call under the pool's lock, which stalls
     * concurrent borrows and the repository-switch decision.
     */
    public void theHealthProbeNeverAsksTheDatastore() {
        IbmFakes.FakeDatastore datastore = new IbmFakes.FakeDatastore();
        IbmCmSession session = IbmCmSession.live("lifecycle", datastore, null);

        assertTrue(session.isHealthy(), "C: a freshly connected session is healthy");
        assertEquals(0, datastore.isConnectedCalls(),
                "C: isHealthy() must not call the vendor's isConnected() - it runs while the pool holds its"
                        + " lock, so it has to be a local read");

        session.markUnusable("read failed");
        assertFalse(session.isHealthy(), "C: a marked-unusable session is never handed out again");
        assertEquals(0, datastore.isConnectedCalls(),
                "C: clearing the health flag must not consult the SDK either");

        session.markUnusable("second report");
        assertFalse(session.isHealthy(), "C: markUnusable() is idempotent");
        assertEquals(0, datastore.isConnectedCalls(), "C: and still does not consult the SDK");
    }

    /**
     * A session whose teardown could not be proven must NOT free the pool's slot.
     *
     * <p>This is the join between the two halves: the throw asserted above is only useful because the POOL
     * turns it into a quarantine, and that conversion is what keeps the configured size a hard physical
     * bound.
     *
     * <p>Driven through {@code pool.close()}, which is the one path that tears down an IDLE resource. A
     * returned-but-healthy session deliberately goes back to the idle set instead of being closed (the pool
     * only retires it when a probe says it is unusable), so driving teardown through a healthy return would
     * assert nothing.
     */
    public void anUnprovenTeardownQuarantinesThePoolSlot() {
        IbmFakes.FakeDatastore datastore = new IbmFakes.FakeDatastore();
        datastore.onDestroyThrow(new IllegalStateException("destroy threw"));
        IbmFakes.FakeConnections connections = new IbmFakes.FakeConnections();
        connections.failedDatastore = datastore;
        IbmCmSessionFactory sessions = IbmFakes.factory("lifecycle", connections);
        BoundedPool<CmSession> pool = new BoundedPool<>("cm", 1, Duration.ofMillis(200),
                IbmFakes.resourceFactory(IbmFakes.profile("lifecycle"), sessions));

        try (var lease = pool.borrow()) {
            assertEquals(0, pool.metrics().quarantined(), "nothing is quarantined while the session is in use");
            assertTrue(lease.value().isHealthy(), "the borrowed session is live");
        } catch (Exception failure) {
            Assert.fail("the borrow and its healthy return must not throw: " + failure);
            return;
        }

        assertEquals(0, pool.metrics().quarantined(),
                "a healthy return does not close anything, so nothing is quarantined yet");
        assertEquals(0, datastore.destroyCalls(), "and no teardown step has run yet");

        try {
            pool.close();
        } catch (Throwable fatal) {
            Assert.fail("shutdown must not propagate a failed session teardown - it accounts for it instead: "
                    + fatal);
            return;
        }

        assertEquals(1, datastore.destroyCalls(), "C: shutdown attempted the teardown");
        assertEquals(1, pool.metrics().closeAttempts(), "C: the teardown was attempted exactly once");
        assertEquals(1, pool.metrics().closeFailures(),
                "C: the throwing teardown is reported as a close failure");
        assertEquals(1, pool.metrics().quarantined(),
                "C: a teardown that could not be proven must quarantine the slot, never free it");
        assertEquals(1, pool.metrics().capacityInUse(), "C: the quarantined slot still consumes capacity");
    }
}
