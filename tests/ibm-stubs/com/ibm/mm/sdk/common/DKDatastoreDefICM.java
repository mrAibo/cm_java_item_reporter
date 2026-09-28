/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * ICM datastore definition. Reached as
 * {@code (DKDatastoreDefICM) datastore.datastoreDef()} - the cast is mandatory because
 * {@code datastoreDef()} is not narrowed in the real SDK.
 *
 * <p>One constructor only, and it does not throw - a no-argument constructor does not exist.
 *
 * <p>Trimmed to read members. The real class also declares {@code createEntity},
 * {@code createItemType}, {@code listEntities(DKNVPair[])}, {@code retrieveItemTypeView(int)},
 * {@code listItemTypesWithAutoLinkEnabled}, the item-type relation family and {@code clearCache};
 * none is declared here.
 */
public class DKDatastoreDefICM extends dkAbstractDatastoreDef implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    /** Item-type scope: user ItemTypes only. */
    public static final int DK_ICM_USER_ITEM_TYPES = 1;

    /** Item-type scope: every ItemType. */
    public static final int DK_ICM_ALL_ITEM_TYPES = 2;

    /** Item-type scope: system ItemTypes only. */
    public static final int DK_ICM_SYSTEM_ITEM_TYPES = 3;

    /** Item-type scope: document-part ItemTypes only. */
    public static final int DK_ICM_PARTS_ITEM_TYPES = 4;

    public DKDatastoreDefICM(dkDatastore datastore) {
        // no state in a compile stub
    }

    /** Returns {@code dkDatastoreAdmin}, which has no {@code policyMgmt()} - cast to DKDatastoreAdminICM. */
    public dkDatastoreAdmin datastoreAdmin() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    /** Returns {@code dkEntityDef} - cast to DKItemTypeDefICM to read ItemType metadata. */
    public dkEntityDef retrieveEntity(java.lang.String entityName) throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public dkEntityDef retrieveEntity(int entityId) throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public dkCollection listEntities() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public dkCollection listEntities(int scope) throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String[] listEntityNames() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String[] listEntityNames(int scope) throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public int getEntityIdByName(java.lang.String entityName) throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String getEntityNameById(int entityId) throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }
}
