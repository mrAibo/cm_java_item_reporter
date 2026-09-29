package com.mraibo.cminsight.ibm.internal;

import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CmSession;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.core.CloseState;

import java.util.Optional;

import static com.mraibo.cminsight.ibm.internal.Assert.assertEquals;
import static com.mraibo.cminsight.ibm.internal.Assert.assertFalse;
import static com.mraibo.cminsight.ibm.internal.Assert.assertNotNull;
import static com.mraibo.cminsight.ibm.internal.Assert.assertThrows;
import static com.mraibo.cminsight.ibm.internal.Assert.assertTrue;

/**
 * Goal 02A section E: every failure classified backend-unusable STRUCTURALLY marks the borrowed session
 * unusable before the Lease is returned to {@code BoundedPool}.
 *
 * <h2>The invariant, and why "classified" was not enough</h2>
 *
 * <p>{@code IbmCmFailure.backendUnusable} already said the right thing; several paths did not act on it.
 * A read could fail, the failure could travel to the caller as a clean sanitized error, and the Lease could
 * still return a session with {@code healthy=true} - so the pool put it back in the idle set and the next
 * borrower was handed a connection the adapter had just declared broken. The requirement is therefore
 * about the SESSION's state at the moment the lease is returned, not about the exception's type.
 *
 * <h2>What is benign and what is poison</h2>
 *
 * <p>{@code DKNotExistException} is the explicit benign control: it is a healthy server answering "there is
 * no such thing", so the session stays reusable. Everything else is conservative - {@code DKSystemError},
 * {@code DKUsageError}, {@code DKDatastoreAccessError}, an unexpected plain {@code RuntimeException}, an
 * {@link Error}, a wrong vendor object type, and a failing retention-policy-name getter - because serving
 * wrong data from a wedged connection is worse than recreating one session.
 *
 * <h2>Driven through the production wrappers</h2>
 *
 * <p>Every case goes through {@link IbmCmApi} or {@link CmMetadataService} - the code the services actually
 * call - rather than through {@code markUnusable()} directly, because a test that marks the session itself
 * would assert the fakes and not the adapter. The final case drives the whole pool so that "a subsequent
 * borrow never receives the poisoned session" is measured on the pool's own decision.
 */
public final class IbmSessionPoisoningTest {

    /** A {@code DKNotExistException} is an answer, not a fault: the session stays reusable. */
    public void aNotFoundAnswerDoesNotPoisonTheSession() {
        IbmFakes.FakeReadDatastore datastore = new IbmFakes.FakeReadDatastore();
        IbmCmSession session = IbmCmSession.live("poison", datastore, null);

        Object absent = IbmCmApi.readOrAbsent(session, "retrieveEntity", () -> {
            throw new com.ibm.mm.sdk.common.DKNotExistException("no such item type");
        });
        assertTrue(absent == null, "E: a not-found answer is returned as an absence, not as a failure");
        assertTrue(session.isHealthy(),
                "E: DKNotExist is the documented BENIGN control - a healthy server saying 'no such thing' - so"
                        + " the session must remain reusable. Poisoning it here would recreate a session for"
                        + " every miss");

        Object found = IbmCmApi.read(session, "read", () -> "value");
        assertEquals("value", found, "E: and the same session still serves an ordinary read");
        assertTrue(session.isHealthy(), "E: which leaves it healthy");
        assertTrue(session.isLive(), "E: and still holding its physical datastore");
    }

    /** Every other SDK failure class retires the session: system, usage and datastore-access errors. */
    public void aVendorErrorRetiresTheSession() {
        assertRetired("DKSystemError", () -> {
            throw new com.ibm.mm.sdk.common.DKSystemError("system");
        }, "cm-system");
        assertRetired("DKUsageError", () -> {
            throw new com.ibm.mm.sdk.common.DKUsageError("usage");
        }, "cm-usage");
        assertRetired("DKDatastoreAccessError", () -> {
            throw new com.ibm.mm.sdk.common.DKDatastoreAccessError("access");
        }, "cm-access");
    }

    /**
     * An unexpected plain {@code RuntimeException} from a vendor call also retires the session.
     *
     * <p>This is the review finding stated directly: {@code backendUnusable()} used to treat an ordinary
     * RuntimeException as reusable, so a vendor call that blew up in an unexpected way returned its session
     * to the idle set as healthy.
     */
    public void anUnexpectedRuntimeExceptionRetiresTheSession() {
        IbmFakes.FakeReadDatastore datastore = new IbmFakes.FakeReadDatastore();
        IbmCmSession session = IbmCmSession.live("poison", datastore, null);

        IbmCmFailure failure = assertThrows(IbmCmFailure.class,
                () -> IbmCmApi.read(session, "read", () -> {
                    throw new IllegalStateException("unexpected runtime failure");
                }),
                "E: an unexpected runtime failure still travels as the adapter's own sanitized failure");

        assertTrue(failure.backendUnusable(),
                "E: an unexpected RuntimeException carries no evidence that the session is sound, so it is"
                        + " classified backend-unusable");
        assertFalse(session.isHealthy(),
                "E: and - the actual requirement - the session is marked unusable BEFORE the lease can be"
                        + " returned, so the pool retires it instead of handing it to the next borrower");
    }

    /**
     * An {@link Error} marks the session unusable BEFORE the Error propagates, and is not swallowed.
     *
     * <p>"Before" is asserted the only way it can be: the Error is caught by the test, and at that instant
     * {@code isHealthy()} is already false. An implementation that marked the session in a finally block of
     * some later step would leave a window in which the lease return could observe a healthy session.
     */
    public void anErrorMarksTheSessionUnusableBeforeItPropagates() {
        IbmFakes.FakeReadDatastore datastore = new IbmFakes.FakeReadDatastore();
        IbmCmSession session = IbmCmSession.live("poison", datastore, null);

        PoisonError fatal = assertThrows(PoisonError.class,
                () -> IbmCmApi.read(session, "read", () -> {
                    throw new PoisonError("fatal vendor failure");
                }),
                "E: an Error from a vendor call must be rethrown as that same Error, never wrapped away");

        assertEquals("fatal vendor failure", fatal.getMessage(), "E: the Error is unchanged");
        assertFalse(session.isHealthy(),
                "E: and by the time the Error is observable the session is already unusable, so no lease"
                        + " return can put it back in the idle set");
        assertTrue(session.isLive(),
                "E: it is still LIVE - marking unusable does not close it, the lease return does - so its"
                        + " capacity slot stays accounted for until then");
    }

    /**
     * A wrong vendor object type retires the session, and so does an absent one.
     *
     * <p>A datastore that hands back something that is not an ICM definition means this adapter is connected
     * to something it does not understand. The failure is backend-unusable, and the review found that it was
     * classified but never applied to the session.
     */
    public void aWrongVendorDatastoreDefinitionOrAdminRetiresTheSession() {
        IbmFakes.FakeReadDatastore wrongDefinition = new IbmFakes.FakeReadDatastore();
        wrongDefinition.setDefinition(foreignDefinition());
        IbmCmSession definitionSession = IbmCmSession.live("poison", wrongDefinition, null);

        IbmCmFailure definitionFailure = assertThrows(IbmCmFailure.class,
                () -> IbmCmApi.datastoreDef(definitionSession),
                "E: a datastore whose definition is not an ICM definition cannot be read through");

        assertTrue(definitionFailure.backendUnusable(), "E: the failure is classified backend-unusable");
        assertFalse(definitionSession.isHealthy(),
                "E: and the session the call ran on is retired before the failure leaves");

        IbmFakes.FakeReadDatastore absentDefinition = new IbmFakes.FakeReadDatastore();
        absentDefinition.setDefinition(null);
        IbmCmSession nullSession = IbmCmSession.live("poison", absentDefinition, null);
        assertThrows(IbmCmFailure.class, () -> IbmCmApi.datastoreDef(nullSession),
                "E: a null definition is a failure, not an absence to be returned");
        assertFalse(nullSession.isHealthy(), "E: and that session is retired too");

        IbmFakes.FakeReadDatastore wrongAdmin = new IbmFakes.FakeReadDatastore();
        wrongAdmin.setPolicyMgmt(null);
        wrongAdmin.setAdmin(foreignAdmin());
        IbmCmSession adminSession = IbmCmSession.live("poison", wrongAdmin, null);

        IbmCmFailure adminFailure = assertThrows(IbmCmFailure.class,
                () -> IbmCmApi.policyMgmt(adminSession),
                "E: a datastore whose administration interface is not the ICM one cannot supply policy"
                        + " management");

        assertTrue(adminFailure.backendUnusable(), "E: classified backend-unusable");
        assertFalse(adminSession.isHealthy(), "E: and the session is retired");
    }

    /**
     * A failing retention-policy-name getter retires the session; a not-found answer does not.
     *
     * <p>{@code CmMetadataService.retentionPolicyNameOf} is the production read point the ItemType mapping
     * calls, so this is the real path rather than a parallel one. The benign direction is asserted beside it
     * because the two are one line apart in the implementation: a getter that answers "absent" must not be
     * turned into a session retirement any more than into an empty policy name.
     */
    public void aRetentionPolicyNameGetterFailureRetiresTheSession() {
        IbmFakes.FakeReadDatastore datastore = new IbmFakes.FakeReadDatastore();
        IbmCmSession session = IbmCmSession.live("poison", datastore, null);
        IbmFakes.FakeItemTypeDef absentPolicy = new IbmFakes.FakeItemTypeDef("Zpolicy")
                .failRetentionPolicyNameWith(new com.ibm.mm.sdk.common.DKNotExistException("no policy"));

        assertEquals("", CmMetadataService.retentionPolicyNameOf(session, absentPolicy),
                "E: an absent policy is an empty name, not a failure");
        assertTrue(session.isHealthy(), "E: and the session survives it - a miss is not a fault");

        IbmFakes.FakeItemTypeDef unreadablePolicy = new IbmFakes.FakeItemTypeDef("Zpolicy")
                .failRetentionPolicyNameWith(new com.ibm.mm.sdk.common.DKSystemError("policy table broken"));

        IbmCmFailure failure = assertThrows(IbmCmFailure.class,
                () -> CmMetadataService.retentionPolicyNameOf(session, unreadablePolicy),
                "E: an unreadable policy table must be reported, because silently reporting 'no policy'"
                        + " would tell an operator that nothing is retained when the opposite may be true");

        assertTrue(failure.backendUnusable(), "E: the failure is classified backend-unusable");
        assertFalse(session.isHealthy(),
                "E: and the borrowed session is marked unusable before the failure leaves, so the lease"
                        + " return retires it instead of returning it to the idle set");
    }

    /**
     * A poisoned session is never handed out again: the pool retires it on the lease return and the next
     * borrow receives a different session.
     *
     * <p>This is the end-to-end form of the invariant, measured on the pool rather than on the session.
     * Without the marking, the returned session would be healthy, go back to the idle set, and be handed to
     * the next borrower - which is exactly the defect section E exists to remove.
     */
    public void aPoisonedSessionIsNeverHandedOutAgain() throws Exception {
        IbmFakes.FakeReadDatastore poisonedDatastore = new IbmFakes.FakeReadDatastore();
        IbmFakes.FakeConnections connections = new IbmFakes.FakeConnections();
        connections.failedDatastore = poisonedDatastore;
        IbmFakes.FakeConnections reconnecting = new IbmFakes.FakeConnections();
        IbmCmSessionFactory sessions = IbmFakes.factory("poison", connections);
        BoundedPool<CmSession> pool = IbmFakes.pool("poison", sessions);

        IbmCmSession first;
        try (Lease<CmSession> lease = pool.borrow()) {
            first = IbmCmSessionFactory.icmSession(lease);
            assertTrue(first.isHealthy(), "E: the borrowed session is live");
            assertThrows(IbmCmFailure.class,
                    () -> IbmCmApi.read(first, "read", () -> {
                        throw new com.ibm.mm.sdk.common.DKSystemError("wedged");
                    }),
                    "E: the read fails on the vendor error");
            assertFalse(first.isHealthy(),
                    "E: and marks the session unusable while it is still leased, which is the only moment at"
                            + " which that can be done");
        }

        assertEquals(0, pool.metrics().leased(), "E: the lease was returned");
        assertEquals(0, pool.metrics().quarantined(),
                "E: the datastore's own teardown returned normally, so nothing is quarantined - the session is"
                        + " retired, not suspected");
        assertEquals(0, pool.metrics().available(),
                "E: and the poisoned session is NOT in the idle set, so it cannot be handed out again");
        assertTrue(poisonedDatastore.destroyCalls() >= 1,
                "E: the retired session was actually torn down through the documented path");

        // The next borrow must create a fresh session rather than reuse the poisoned one.
        connections.failedDatastore = new IbmFakes.FakeReadDatastore();
        try (Lease<CmSession> lease = pool.borrow()) {
            IbmCmSession second = IbmCmSessionFactory.icmSession(lease);
            assertNotNull(second, "E: the pool recovers by creating a replacement");
            assertFalse(second == first,
                    "E: and the replacement is a DIFFERENT session - handing the poisoned one back would be"
                            + " the exact reuse this section forbids");
            assertTrue(second.isHealthy(), "E: the replacement is live");
        }
        assertEquals(2, connections.connectAttempts(),
                "E: exactly one replacement session was created, so the pool did not reuse the retired one");
        pool.close();
        assertEquals(CloseState.CLOSED_CLEAN, pool.closeState(),
                "E: and the pool shuts down cleanly, because every physical outcome was proven");
    }

    // ------------------------------------------------------------------ harness

    /** Runs one vendor failure through the production read wrapper and asserts the session is retired. */
    private static void assertRetired(String label, ThrowingRead read, String expectedCategory) {
        IbmFakes.FakeReadDatastore datastore = new IbmFakes.FakeReadDatastore();
        IbmCmSession session = IbmCmSession.live("poison", datastore, null);

        IbmCmFailure failure = assertThrows(IbmCmFailure.class,
                () -> IbmCmApi.read(session, "read", () -> {
                    read.run();
                    return null;
                }),
                "E: " + label + " must surface as the adapter's sanitized failure");

        assertTrue(failure.backendUnusable(), "E: " + label + " is classified backend-unusable");
        assertFalse(session.isHealthy(),
                "E: " + label + " must retire the session before the lease is returned");
        assertEquals(expectedCategory, failure.category(),
                "E: " + label + " keeps its own sanitized category, so an operator can tell the layers apart");
    }

    /** The vendor failures used by {@link #assertRetired}. */
    private interface ThrowingRead {
        void run() throws Exception;
    }

    /**
     * A definition that is NOT an ICM definition.
     *
     * <p>Built as a dynamic proxy rather than an anonymous implementation, and that is a correctness
     * requirement of this test tree rather than a style choice: the committed stubs are narrower than the
     * real IBM CM 8.7 SDK, so an anonymous implementation of a vendor interface compiles against the stubs
     * and fails against the real jars - which is how this suite first broke the {@code --require-ibm} path
     * (the real {@code dkDatastoreDef} also declares {@code clearCache()}). A proxy names no member, so it
     * is identical under both. See {@link IbmFakes#foreignVendorType(Class)}.
     */
    private static com.ibm.mm.sdk.common.dkDatastoreDef foreignDefinition() {
        return IbmFakes.foreignVendorType(com.ibm.mm.sdk.common.dkDatastoreDef.class);
    }

    /** An administration interface that is NOT the ICM one; a proxy for the same reason as above. */
    private static com.ibm.mm.sdk.common.dkDatastoreAdmin foreignAdmin() {
        return IbmFakes.foreignVendorType(com.ibm.mm.sdk.common.dkDatastoreAdmin.class);
    }

    /** An {@link Error} from a vendor call. */
    private static final class PoisonError extends Error {

        private static final long serialVersionUID = 1L;

        PoisonError(String message) {
            super(message);
        }
    }
}
