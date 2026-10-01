/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * Superclass of {@code DKComponentTypeDefICM} and therefore of {@code DKItemTypeDefICM}.
 *
 * <p>Trimmed to the members the read chain resolves through this class. The real class also declares
 * {@code setDatastore}, {@code getDatastore}, {@code datastoreName}, {@code datastoreType},
 * {@code setName}, {@code setType}, {@code setId}, {@code setDescription} and the whole mutating
 * sub-entity/attribute surface; none is declared here.
 */
public class dkAbstractEntityDef implements dkEntityDef, DKMessageId, java.io.Serializable {

    private static final long serialVersionUID = 1L;

    public dkAbstractEntityDef() {
        // no state in a compile stub
    }

    public dkAbstractEntityDef(dkDatastore datastore) {
        // no state in a compile stub
    }

    public java.lang.String getName() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String getDescription() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public short getId() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public short getType() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }
}
