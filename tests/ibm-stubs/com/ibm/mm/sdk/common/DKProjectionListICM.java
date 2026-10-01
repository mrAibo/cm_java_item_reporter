/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * Projection list used with retrieval options. Included because {@code DKRetrieveOptionsICM}
 * declares {@code attributeFilters(DKProjectionListICM)}.
 *
 * <p>Trimmed to the construction and read side. The real class also declares
 * {@code removeProjection(String)} and the two {@code getProjectionsMap/getProjectionsDKNVPair}
 * exports; they are not part of the Goal 02 read path.
 *
 * <p>There is no public constructor in the real class - instances come from
 * {@code createInstance(dkDatastoreIntICM)} only.
 */
public class DKProjectionListICM {

    private DKProjectionListICM() {
        // not constructible: the real class exposes createInstance only
    }

    public static DKProjectionListICM createInstance(dkDatastoreIntICM datastore)
            throws DKUsageError, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public void addProjection(java.lang.String entityName, java.lang.String[] attributeNames)
            throws DKUsageError, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public void addProjection(java.lang.String entityName, java.lang.String attributeName)
            throws DKUsageError, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String toString() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }
}
