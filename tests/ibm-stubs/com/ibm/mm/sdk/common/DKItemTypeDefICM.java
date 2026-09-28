/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * ICM ItemType definition. {@code DKDatastoreDefICM.retrieveEntity(String)} returns
 * {@code dkEntityDef}, so the adapter casts to this class to read ItemType metadata.
 *
 * <p>{@code getName()} and {@code getDescription()} are inherited from
 * {@code dkAbstractEntityDef} exactly as in the real SDK - this class does not re-declare them.
 * {@code getIntId()} is inherited from {@code DKComponentTypeDefICM}.
 *
 * <p>Trimmed to read accessors. The real class declares ~50 further members (the setters, the
 * text-index/auto-link/relation/view families, {@code getItemTypeFlag()}, {@code getPartIDByName},
 * the reindex and delete-expired-items enums, ...); none is declared here. In particular there is no
 * {@code getEntityId()} and no {@code getItemTypeId()} anywhere in the real SDK.
 */
public class DKItemTypeDefICM extends DKComponentTypeDefICM implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    /** Legacy retention unit constant: only YEAR exists as a {@code short}. */
    public static final short DK_ICM_RETENTION_UNIT_YEAR = 0;

    /** Legacy "no expiration" sentinel. The typo RETRENTION is preserved from the real API. */
    public static final short DK_ICM_ITEM_RETRENTION_NO_EXPIRE = 0;

    public DKItemTypeDefICM() {
        // no state in a compile stub
    }

    public DKItemTypeDefICM(dkDatastore datastore) {
        // no state in a compile stub
    }

    public DKItemTypeDefICM(DKItemTypeDefICM source) {
        // no state in a compile stub
    }

    public short getClassification() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public short getDefaultRMCode() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public short getDefaultCollCode() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public short getDefaultRetentionUnit() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public int getDefaultItemRetention() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public int getXDOClassID() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String getXDOClassName() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String getJavaXDOClassName() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public short getVersionControl() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public short getVersioningType() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public int getItemTypeRetentionPolicyId() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String getItemTypeRetentionPolicyName() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }
}
