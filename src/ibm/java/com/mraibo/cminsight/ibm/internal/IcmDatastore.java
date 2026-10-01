package com.mraibo.cminsight.ibm.internal;

import com.ibm.mm.sdk.common.DKException;
import com.ibm.mm.sdk.common.DKPolicyMgmtICM;
import com.ibm.mm.sdk.common.dkDatastoreAdmin;
import com.ibm.mm.sdk.common.dkDatastoreDef;

/**
 * The narrow view of one physical IBM CM datastore that the rest of the adapter is allowed to see.
 *
 * <h2>Why the adapter does not call {@code DKDatastoreICM} directly from its services</h2>
 *
 * <p>Two reasons, and the second is the important one.
 *
 * <p>First, isolation: this is the single seam between "the adapter" and "the vendor SDK". Everything
 * above it is ordinary Java, and the only classes that name a {@code com.ibm} type per operation are
 * {@link IbmCmApi}, {@link DkDatastoreHandle}, {@link IbmCmSession} and {@link IbmCmConnectionFactory}.
 *
 * <p>Second, and the reason this is an interface rather than a concrete wrapper: a real IBM CM 8.7
 * server is not available to CI, so a service that called the SDK directly could only ever be exercised
 * against the vendor's own runtime - which in practice means it would be untested. With this seam, the
 * test suite can drive the whole read chain - session lifecycle including an uncertain teardown, ItemType
 * mapping, the {@code UNKNOWN(...)} rule, the retention assignment reverse index and the cache age -
 * through a fake handle, and the vendor-facing part shrinks to the one-line delegations in
 * {@link DkDatastoreHandle}.
 *
 * <h2>Physical lifecycle lives here, and nowhere else</h2>
 *
 * <p>{@link #disconnect()}, {@link #destroy()} and {@link #isConnected()} are declared here because the
 * SDK distinguishes them and that distinction is load-bearing: {@code disconnect()} may fail while
 * {@code destroy()} still has to be attempted, and the outcome of each is what turns into "release the
 * capacity slot" or "quarantine it". No read service can reach them - a service is handed an already
 * connected, already live session - so teardown happens in exactly one place, {@link IbmCmSession}, which
 * is the only class that knows how to report an unproven outcome.
 *
 * <p>What is deliberately absent is as important as what is present: there is no {@code connect}, no
 * {@code commit}, no {@code add}, no {@code update} and no {@code del} on this interface. {@code connection()}
 * - the native JDBC handle - is absent for the same reason and is additionally refused by the committed
 * source guard.
 *
 * <h2>Raw SDK types, narrowly</h2>
 *
 * <p>This interface is package-private on purpose. It returns the SDK's own base types rather than adapter
 * DTOs because its whole job is to be the vendor-shaped layer that {@link IbmCmApi} reads through: the
 * mandatory ICM casts live in exactly one place there, so a fake implementation never has to reproduce
 * them.
 *
 * <h2>Exception declarations</h2>
 *
 * <p>{@link #disconnect()} and {@link #destroy()} declare the SDK's exact {@code DKException, Exception}
 * pair. That pair has to be reproduced rather than narrowed to a single type, because the real
 * {@code DKDatastoreICM} declares it and a fake driven by the test suite may throw a plain {@code Exception}.
 *
 * <p>{@link #isConnected()} declares NO throws clause, matching the concrete {@code DKDatastoreICM} rather
 * than the wider {@code throws java.lang.Exception} its base class {@code dkAbstractDatastore} carries. That
 * is the deliberate choice of the two: the concrete declaration is what the adapter actually calls, so
 * declaring the wider form would oblige the caller to catch a checked exception that this call can never
 * throw. A fake that needs to report "I could not tell" throws an unchecked exception instead, and
 * {@link IbmCmSession} reads any failure on this path conservatively as "connected", so a teardown is
 * attempted rather than skipped.
 */
interface IcmDatastore {

    /**
     * The datastore definition, the entry point of every read this adapter performs.
     *
     * <p>{@code DKDatastoreICM.datastoreDef()} is NOT narrowed in the SDK, so a caller must cast to
     * {@code DKDatastoreDefICM} before {@code listEntityNames}/{@code retrieveEntity} are reachable at all.
     */
    dkDatastoreDef datastoreDef() throws Exception;

    /**
     * The datastore administration entry point, used only to reach retention policy management.
     *
     * <p>Also not narrowed: {@code dkDatastoreAdmin} declares no {@code policyMgmt()}, so a caller must
     * cast to {@code DKDatastoreAdminICM}.
     */
    dkDatastoreAdmin datastoreAdmin() throws Exception;

    /**
     * Retention policy management.
     *
     * <p>Separate from {@link #datastoreAdmin()} so a fake can supply it directly. Its mutating members
     * ({@code add}, {@code update}, {@code del}, {@code clearCache}) are absent from the test-only SDK
     * stub, so a write cannot compile in this source set.
     */
    DKPolicyMgmtICM policyMgmt() throws Exception;

    /**
     * Whether the SDK currently considers this datastore connected.
     *
     * <p><strong>Not a health probe.</strong> This is a vendor call, so it is used in exactly one place -
     * {@link IbmCmSession#close()} - to decide whether {@code disconnect()} is attempted before
     * {@code destroy()} always is. The pool's liveness question is answered by
     * {@link IbmCmSession#isHealthy()}, which is a local volatile read, because
     * {@link com.mraibo.cminsight.connection.BoundedPool} asks it while holding its own lock.
     *
     * <p>An implementation that cannot tell should throw an unchecked exception rather than guess
     * {@code false}: the caller reads a failure as "assume connected", which attempts the teardown. Guessing
     * {@code false} would skip a needed disconnect and leave a live session unreferenced.
     */
    boolean isConnected();

    /**
     * Closes the logical CM session.
     *
     * <p>Declares the SDK's exact {@code DKException, Exception} pair, which the concrete
     * {@code DKDatastoreICM} also declares.
     */
    void disconnect() throws DKException, Exception;

    /**
     * Releases the local SDK object. Attempted even when {@link #disconnect()} threw, because a
     * half-torn-down session that is never destroyed is exactly the resource the physical bound cannot
     * account for.
     */
    void destroy() throws DKException, Exception;
}
