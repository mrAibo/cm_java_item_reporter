package com.mraibo.cminsight.ibm.internal;

import java.util.List;

import static com.mraibo.cminsight.ibm.internal.Assert.assertEquals;
import static com.mraibo.cminsight.ibm.internal.Assert.assertFalse;
import static com.mraibo.cminsight.ibm.internal.Assert.assertThrows;
import static com.mraibo.cminsight.ibm.internal.Assert.assertTrue;

/**
 * Goal 02A section B: the cleanup verdict of a FAILED connect follows IBM's documented
 * {@code DKDatastoreICM} lifecycle, with a successful {@code destroy()} as the final cleanup proof.
 *
 * <h2>The defect this suite pins</h2>
 *
 * <p>The production connection factory used to call {@code disconnect()} unconditionally after a failed
 * {@code connect()} and to require BOTH {@code disconnect()} and {@code destroy()} to return normally
 * before it reported cleanup proven. IBM documents that {@code isConnected()} is true only when a
 * {@code connect()} completed, and that {@code destroy()} destroys the datastore and performs the cleanup
 * <em>if needed</em>. So an ordinary failed login could become a permanent physical-capacity quarantine -
 * and calling {@code disconnect()} on a datastore that was never connected only manufactured a second,
 * misleading error.
 *
 * <h2>How the rule is reached</h2>
 *
 * <p>{@code IbmCmCleanupFailure.releaseQuietly(IcmDatastore)} is the ONE implementation of the documented
 * order - ask {@code isConnected()}, disconnect only when that says connected (or cannot be read), always
 * destroy - and the production factory and {@link IbmCmSession#close()} both go through it. Driving it here
 * therefore exercises the production decision, not a copy of it.
 *
 * <h2>Assertions are on the CALL LOG and the verdict</h2>
 *
 * <p>"Every cleanup step that should run is still attempted" is a requirement, and a step that is silently
 * skipped cannot be seen in a verdict. So each case asserts the ordered call log on the fake datastore as
 * well as {@code destroyProven()} and the sanitized diagnostics.
 */
public final class IbmConnectCleanupRuleTest {

    /**
     * Failed connect, datastore reports itself not connected, destroy succeeds: PROVEN_CLEAN and
     * {@code disconnect()} is NOT called.
     *
     * <p>This is the case that used to quarantine a slot for no reason. Nothing was connected, so there is
     * nothing to disconnect, and manufacturing that call would put a second, misleading failure into the
     * diagnostic.
     */
    public void aNotConnectedDatastoreIsNotDisconnectedAndDestroyProvesCleanup() {
        IbmFakes.FakeDatastore datastore = new IbmFakes.FakeDatastore();
        datastore.setConnected(false);

        IbmCmCleanupFailure.Teardown teardown = IbmCmCleanupFailure.releaseQuietly(datastore);

        assertTrue(teardown.destroyProven(),
                "B: destroy() returned normally and IBM documents it as the datastore cleanup, so the"
                        + " cleanup is PROVEN");
        assertEquals(0, datastore.disconnectCalls(),
                "B: a datastore that reports itself not connected must NOT be disconnected - there is nothing"
                        + " to disconnect and the attempt only manufactures a second error");
        assertEquals(1, datastore.destroyCalls(),
                "B: but destroy() is not conditional: it releases the local SDK object and is ALWAYS attempted");
        assertEquals(List.of("isConnected", "destroy"), new java.util.ArrayList<>(datastore.calls),
                "B: the documented order for this case is the connectedness question, then destroy");
        assertEquals("", teardown.diagnostics(),
                "B: nothing went wrong, so the sanitized diagnostics are empty rather than carrying the"
                        + " absence of a disconnect as if it were a problem");
    }

    /** Failed connect, datastore says connected, both steps succeed: PROVEN_CLEAN in document order. */
    public void aConnectedDatastoreIsDisconnectedThenDestroyedAndIsProvenClean() {
        IbmFakes.FakeDatastore datastore = new IbmFakes.FakeDatastore();

        IbmCmCleanupFailure.Teardown teardown = IbmCmCleanupFailure.releaseQuietly(datastore);

        assertTrue(teardown.destroyProven(), "B: every step returned normally, so the cleanup is proven");
        assertEquals(1, datastore.disconnectCalls(), "B: a connected datastore is disconnected once");
        assertEquals(1, datastore.destroyCalls(), "B: and destroyed once");
        assertEquals(List.of("isConnected", "disconnect", "destroy"), new java.util.ArrayList<>(datastore.calls),
                "B: the normal lifecycle order is connect -> disconnect -> destroy, so teardown is"
                        + " isConnected, disconnect, destroy");
        assertEquals("", teardown.diagnostics(), "B: and there is nothing to report");
    }

    /**
     * Failed connect, {@code disconnect()} FAILS, {@code destroy()} succeeds: still PROVEN_CLEAN, with the
     * disconnect problem retained in SANITIZED diagnostics.
     *
     * <p>The heart of section B. Before this correction the disconnect failure alone forced an UNPROVEN
     * verdict, which permanently consumed a capacity slot even though IBM's documented destroy had already
     * performed the cleanup. The problem is not discarded - it stays in the diagnostics so an operator can
     * see what happened - but it must not become a permanent quarantine.
     *
     * <p>The message is asserted to be sanitized in the same test, because a diagnostic that carries a
     * vendor message is a leak: the vendor's text may name a user id, a database alias or a statement, and
     * this string ends up in {@code lastAdapterError} and on an operator page.
     */
    public void aDisconnectFailureDoesNotPreventACleanVerdictWhenDestroySucceeds() {
        IbmFakes.FakeDatastore datastore = new IbmFakes.FakeDatastore();
        datastore.onDisconnectThrow(new IllegalStateException("user=ICMADMIN ssid=ICMCRM failed"));

        IbmCmCleanupFailure.Teardown teardown = IbmCmCleanupFailure.releaseQuietly(datastore);

        assertTrue(teardown.destroyProven(),
                "B: destroy() returned normally, so cleanup IS proven even though the disconnect did not"
                        + " return - quarantining the slot here is the bound-conserving mistake that costs"
                        + " physical capacity for no physical reason");
        assertEquals(1, datastore.disconnectCalls(), "B: the failing disconnect was attempted once");
        assertEquals(1, datastore.destroyCalls(), "B: and destroy was still attempted");
        assertEquals(List.of("isConnected", "disconnect", "destroy"), new java.util.ArrayList<>(datastore.calls),
                "B: a failing disconnect must not skip destroy - that is how a half-torn-down session"
                        + " survives");
        assertFalse(teardown.diagnostics().isEmpty(),
                "B: the disconnect problem is retained in the diagnostics, not swallowed");
        assertTrue(teardown.diagnostics().contains("disconnect"),
                "B: the diagnostic names the step that failed: " + teardown.diagnostics());
        assertTrue(teardown.diagnostics().contains("IllegalStateException"),
                "B: and the sanitized category/type, which is what an operator can act on: "
                        + teardown.diagnostics());
        assertFalse(teardown.diagnostics().contains("ICMADMIN")
                        || teardown.diagnostics().contains("ICMCRM")
                        || teardown.diagnostics().contains("failed"),
                "B: but NEVER the raw vendor message: it is server-chosen free text that can name a user or a"
                        + " repository, and this string reaches an operator page and lastAdapterError. Was: "
                        + teardown.diagnostics());
    }

    /**
     * A connectedness query that FAILS reads as connected: the disconnect is attempted anyway, and a
     * succeeding destroy still proves the cleanup.
     *
     * <p>The conservative direction, asserted in both halves: an unreadable answer must not be read as "not
     * connected" (which would skip a needed disconnect and let a live session be forgotten), and it must not
     * be read as a cleanup failure either.
     */
    public void anUnknownConnectednessStillAttemptsDisconnectAndStaysProvenClean() {
        IbmFakes.FakeDatastore datastore = new IbmFakes.FakeDatastore();
        datastore.onIsConnectedThrow(new IllegalStateException("cannot tell"));

        IbmCmCleanupFailure.Teardown teardown = IbmCmCleanupFailure.releaseQuietly(datastore);

        assertTrue(teardown.destroyProven(),
                "B: destroy() returned normally, so the cleanup is proven");
        assertEquals(1, datastore.disconnectCalls(),
                "B: an unknown connectedness takes the conservative path and attempts the disconnect rather"
                        + " than skipping a teardown that may be needed");
        assertEquals(1, datastore.destroyCalls(), "B: and destroy still runs");
        assertEquals(List.of("isConnected", "disconnect", "destroy"), new java.util.ArrayList<>(datastore.calls),
                "B: the order is unchanged for the unknown case");
    }

    /**
     * A FAILED {@code destroy()} is UNPROVEN regardless of what the disconnect did.
     *
     * <p>Both directions are asserted in one test so neither can pass by accident: the disconnect SUCCEEDED
     * in the first half and was NOT ATTEMPTED in the second, and both must end in an unproven cleanup. If a
     * change made the verdict follow the disconnect result, one of the two halves would fail.
     */
    public void aFailedDestroyIsUnprovenWhateverTheDisconnectDid() {
        IbmFakes.FakeDatastore connected = new IbmFakes.FakeDatastore();
        connected.onDestroyThrow(new IllegalStateException("destroy failed"));

        IbmCmCleanupFailure.Teardown afterCleanDisconnect = IbmCmCleanupFailure.releaseQuietly(connected);

        assertFalse(afterCleanDisconnect.destroyProven(),
                "B: destroy did not return normally, so the physical cleanup is UNPROVEN and the slot must be"
                        + " quarantined - a successful disconnect is not cleanup proof");
        assertEquals(1, connected.disconnectCalls(), "B: the disconnect was attempted and succeeded");
        assertEquals(1, connected.destroyCalls(), "B: the failing destroy was attempted once");
        assertFalse(afterCleanDisconnect.diagnostics().isEmpty(),
                "B: and the failure is reported: " + afterCleanDisconnect.diagnostics());

        IbmFakes.FakeDatastore disconnected = new IbmFakes.FakeDatastore();
        disconnected.setConnected(false);
        disconnected.onDestroyThrow(new IllegalStateException("destroy failed"));

        IbmCmCleanupFailure.Teardown withoutDisconnect = IbmCmCleanupFailure.releaseQuietly(disconnected);

        assertFalse(withoutDisconnect.destroyProven(),
                "B: an unproven destroy stays unproven when no disconnect was attempted either");
        assertEquals(0, disconnected.disconnectCalls(),
                "B: this half really had no disconnect, which is what makes the pair meaningful");
        assertEquals(1, disconnected.destroyCalls(), "B: destroy was still attempted, and it failed");
        assertFalse(withoutDisconnect.diagnostics().isEmpty(), "B: and it is reported");
    }

    /**
     * A normal LIVE session close follows the same final-destroy rule.
     *
     * <p>Asserts the rule where a live session is actually released, not only on the failed-connect path: a
     * disconnect failure with a successful destroy no longer throws {@link IbmCmCleanupFailure}, while a
     * failed destroy always does - and names {@code destroy} as the step that could not be proven.
     */
    public void aLiveSessionCloseFollowsTheSameFinalDestroyProofRule() {
        IbmFakes.FakeDatastore recoverable = new IbmFakes.FakeDatastore();
        recoverable.onDisconnectThrow(new IllegalStateException("disconnect threw"));
        IbmCmSession session = IbmCmSession.live("cleanup-rule", recoverable, null);

        session.close();

        assertEquals(1, recoverable.disconnectCalls(), "B: the failing disconnect was attempted");
        assertEquals(1, recoverable.destroyCalls(), "B: destroy succeeded and proves the cleanup");
        assertFalse(session.isHealthy(), "B: a closed session is never healthy");

        IbmFakes.FakeDatastore unproven = new IbmFakes.FakeDatastore();
        unproven.onDestroyThrow(new IllegalStateException("destroy threw"));
        IbmCmSession doomed = IbmCmSession.live("cleanup-rule", unproven, null);

        IbmCmCleanupFailure failure = assertThrows(IbmCmCleanupFailure.class, doomed::close,
                "B: a destroy that did not return normally leaves the physical outcome unproven, so the pool"
                        + " must be told by an exception");
        assertEquals("destroy", failure.step(),
                "B: with the final-destroy rule the unproven step is always destroy");
        assertEquals(1, unproven.disconnectCalls(), "B: the disconnect before it was still attempted");
        assertEquals(1, unproven.destroyCalls(), "B: and the failing destroy ran once");
    }
}
