/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * Abstract superclass of {@code DKDatastoreDefICM}. Reproduced so the declared hierarchy matches the
 * real SDK; it declares no member here, so every {@code dkDatastoreDef} member is implemented (and
 * therefore visible) on {@code DKDatastoreDefICM} itself, exactly as the real javap output shows for
 * that class.
 *
 * <p>The real class additionally implements {@code createEntity}, {@code getEntity},
 * {@code listSearchableEntities} and the rest of the {@code dkDatastoreDef} surface; none of those is
 * part of the Goal 02 read path.
 */
public abstract class dkAbstractDatastoreDef implements dkDatastoreDef, DKMessageId, java.io.Serializable {

    private static final long serialVersionUID = 1L;

    public dkAbstractDatastoreDef() {
        // no state in a compile stub
    }

    public dkAbstractDatastoreDef(dkDatastore datastore) {
        // no state in a compile stub
    }
}
