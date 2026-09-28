/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * Superclass of {@code DKDatastoreAdminICM}. With {@link dkDatastoreAdmin} left empty, this class has
 * no member to implement, which is what keeps {@code policyMgmt()} available only on the ICM
 * subclass.
 *
 * <p>The real class additionally implements {@code setDatastore}, {@code getDatastore},
 * {@code accessControl}, {@code authorizationMgmt}, {@code userManagement},
 * {@code configurationManagement}, {@code workFlowManagement}, {@code adminDomainsMgmt} and
 * {@code clearCache}; none is declared here.
 */
public class dkAbstractDatastoreAdmin
        implements dkDatastoreAdmin, DKMessageId, DKConstant, java.io.Serializable {

    private static final long serialVersionUID = 1L;

    public dkAbstractDatastoreAdmin() {
        // no state in a compile stub
    }

    public dkAbstractDatastoreAdmin(dkDatastore datastore) {
        // no state in a compile stub
    }
}
