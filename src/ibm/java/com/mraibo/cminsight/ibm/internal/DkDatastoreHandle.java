package com.mraibo.cminsight.ibm.internal;

import com.ibm.mm.sdk.common.DKDatastoreAdminICM;
import com.ibm.mm.sdk.common.DKDatastoreDefICM;
import com.ibm.mm.sdk.common.DKException;
import com.ibm.mm.sdk.common.DKPolicyMgmtICM;
import com.ibm.mm.sdk.common.dkDatastoreAdmin;
import com.ibm.mm.sdk.common.dkDatastoreDef;
import com.ibm.mm.sdk.server.DKDatastoreICM;

/**
 * The production {@link IcmDatastore}: every method is a one-line delegation to {@code DKDatastoreICM}.
 *
 * <h2>Why the casts live here and not in the services</h2>
 *
 * <p>IBM's own API is unhelpfully wide in two places that every reader of it trips over:
 *
 * <ul>
 *   <li>{@code DKDatastoreICM.datastoreDef()} returns the base {@code dkDatastoreDef}, so
 *       {@code listEntities}/{@code retrieveEntity} are only reachable after an explicit cast to
 *       {@code DKDatastoreDefICM}.</li>
 *   <li>{@code DKDatastoreDefICM.datastoreAdmin()} returns the base {@code dkDatastoreAdmin}, which
 *       declares no {@code policyMgmt()} at all, so retention needs a second explicit cast to
 *       {@code DKDatastoreAdminICM}.</li>
 * </ul>
 *
 * <p>Keeping both casts in the vendor-facing layer means a fake {@link IcmDatastore} used by the test
 * suite never has to reproduce them, and a reader of the ItemType/retention mapping code never has to
 * hold IBM's type hierarchy in their head.
 *
 * <h2>Why this class is the only caller of {@code DKDatastoreICM}'s data methods</h2>
 *
 * <p>Writing, transaction and native-JDBC members are simply never referenced here. That is checked
 * twice: the committed source guard fails on the known mutating member names, and the test-only SDK stub
 * does not declare them, so a write cannot even compile in this source set.
 */
final class DkDatastoreHandle implements IcmDatastore {

    private final DKDatastoreICM datastore;

    DkDatastoreHandle(DKDatastoreICM datastore) {
        this.datastore = datastore;
    }

    @Override
    public dkDatastoreDef datastoreDef() throws Exception {
        return datastore.datastoreDef();
    }

    @Override
    public dkDatastoreAdmin datastoreAdmin() throws Exception {
        dkDatastoreDef definition = datastore.datastoreDef();
        if (definition instanceof DKDatastoreDefICM icmDefinition) {
            return icmDefinition.datastoreAdmin();
        }
        return null;
    }

    @Override
    public DKPolicyMgmtICM policyMgmt() throws Exception {
        dkDatastoreAdmin admin = datastoreAdmin();
        if (admin instanceof DKDatastoreAdminICM icmAdmin) {
            return icmAdmin.policyMgmt();
        }
        return null;
    }

    /**
     * The SDK's own connectivity answer, delegated unchanged.
     *
     * <p>The concrete {@code DKDatastoreICM} declares no throws clause, so this override narrows the wider
     * {@code throws Exception} of the base class - legal, and the reason {@link IcmDatastore#isConnected()}
     * carries no checked exception either. Nothing in the pool's health path may reach this method; see
     * {@link IcmDatastore#isConnected()}.
     */
    @Override
    public boolean isConnected() {
        return datastore.isConnected();
    }

    /**
     * Closes the logical session. Attempted at most once per close, and its failure is never swallowed:
     * {@link IbmCmSession} converts a thrown exception into an unproven teardown, which is what makes the
     * pool quarantine the capacity slot.
     */
    @Override
    public void disconnect() throws DKException, Exception {
        datastore.disconnect();
    }

    /**
     * Releases the local SDK object. Deliberately reachable even when {@code disconnect()} failed: the
     * adapter attempts every teardown step so a half-torn-down session cannot survive.
     */
    @Override
    public void destroy() throws DKException, Exception {
        datastore.destroy();
    }

    @Override
    public String toString() {
        return "DkDatastoreHandle[" + (datastore == null ? "null" : datastore.getClass().getSimpleName())
                + "]";
    }
}
