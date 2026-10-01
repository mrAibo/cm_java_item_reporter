/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * Retrieval options for reading a data object with the attributes a metadata read needs.
 *
 * <p>IMPORTANT: named in the Goal 02 deliverables, but the adapter chain in
 * GOAL_02_IMPLEMENTATION_SPEC.md section 1 does not have to use it (ItemType and retention metadata
 * are read from the {@code DKItemTypeDefICM} / {@code DKRetentionPolicyDefICM} accessors). It is
 * stubbed so the type exists for the read path, and it is trimmed to the read side: there is no
 * {@code toString(boolean)}, no link option, no parts option, and no check-out/version option -
 * {@code functionCheckOut}, {@code behaviorDoNotForceCheckout}, {@code behaviorRetrieveOnCheckoutError}
 * and {@code functionVersionLatest} are deliberately absent.
 *
 * <p>There is no public constructor in the real class - instances come from
 * {@code createInstance(dkDatastoreIntICM)} only.
 */
public class DKRetrieveOptionsICM {

    private DKRetrieveOptionsICM() {
        // not constructible: the real class exposes createInstance only
    }

    public static DKRetrieveOptionsICM createInstance(dkDatastoreIntICM datastore)
            throws DKUsageError, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public DKNVPair[] dkNVPair() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public void attributeFilters(DKProjectionListICM projections) {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public DKProjectionListICM attributeFilters() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public void baseAttributes(boolean enabled) {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public boolean baseAttributes() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public void behaviorIgnoreNoneFoundError(boolean enabled) {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public boolean behaviorIgnoreNoneFoundError() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String toString() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }
}
