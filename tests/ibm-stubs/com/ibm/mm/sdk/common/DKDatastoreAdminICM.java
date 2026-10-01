/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * ICM datastore administration. Reached as
 * {@code (DKDatastoreAdminICM) datastoreDef.datastoreAdmin()} - the cast is mandatory because
 * {@code datastoreAdmin()} returns {@code dkDatastoreAdmin}, which has no {@code policyMgmt()}.
 *
 * <p>Only {@code policyMgmt()} is declared, so the retention policy entry point is the single thing
 * an adapter can reach through this class. The real class also declares
 * {@code authorizationMgmt}, {@code userManagement}, {@code configurationManagement},
 * {@code adminDomainsMgmt}, {@code mimeTypeMgmt}, {@code quiesceStatus},
 * {@code getConnectionUserID}, {@code setDatastore}, {@code clearCache} and the quiesce/activate
 * pair; none is declared here.
 */
public class DKDatastoreAdminICM extends dkAbstractDatastoreAdmin {

    /** Present only to satisfy -Xlint:serial; the real class inherits Serializable from its superclass. */
    private static final long serialVersionUID = 1L;

    public DKDatastoreAdminICM(dkDatastore datastore) {
        // no state in a compile stub
    }

    public DKPolicyMgmtICM policyMgmt() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }
}
