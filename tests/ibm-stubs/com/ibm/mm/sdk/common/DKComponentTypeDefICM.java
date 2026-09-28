/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * Direct superclass of {@code DKItemTypeDefICM}. Declares the 32-bit item-type id accessor
 * {@code getIntId()} that the adapter must use instead of the lossy {@code short getId()}.
 *
 * <p>Trimmed to the read accessors. The real class also declares {@code clone}, {@code isRoot},
 * {@code isView}, {@code setId}, {@code setIntId}, {@code setComponentTypeId},
 * {@code setComponentTypeName}, {@code setItemTypeId}, {@code setItemTypeName} and the mutating
 * attribute and attribute-group families; none is declared here.
 */
public class DKComponentTypeDefICM extends dkAbstractEntityDef implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    public DKComponentTypeDefICM() {
        // no state in a compile stub
    }

    public DKComponentTypeDefICM(dkDatastore datastore) {
        // no state in a compile stub
    }

    public DKComponentTypeDefICM(DKComponentTypeDefICM source) {
        // no state in a compile stub
    }

    /** Legacy 16-bit id. Lossy for item types - use {@link #getIntId()}. */
    public short getId() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    /** The 32-bit item-type id. This is the accessor the adapter uses. */
    public int getIntId() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }
}
