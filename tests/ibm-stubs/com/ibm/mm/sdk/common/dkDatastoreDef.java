/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * Return type of {@code DKDatastoreICM.datastoreDef()}.
 *
 * <p>CRITICAL: the real SDK does NOT narrow this return type. {@code datastoreDef()} hands back
 * {@code dkDatastoreDef} on the interface, on the superclass and on {@code DKDatastoreICM}, so
 * reaching any ICM-only method requires an explicit cast to {@code DKDatastoreDefICM}.
 *
 * <p>Trimmed to the read members the adapter uses. The real interface also declares
 * {@code getEntity(String)}, {@code createEntity()}, {@code listEntities(DKNVPair[])},
 * {@code listSearchable*}, {@code add}, {@code del}, {@code deleteEntity}, {@code clearCache} and
 * the attribute/search-template families; none is declared here.
 */
public interface dkDatastoreDef {

    dkEntityDef retrieveEntity(java.lang.String entityName) throws DKException, java.lang.Exception;

    dkCollection listEntities() throws DKException, java.lang.Exception;

    dkCollection listEntities(int scope) throws DKException, java.lang.Exception;

    java.lang.String[] listEntityNames() throws DKException, java.lang.Exception;

    java.lang.String[] listEntityNames(int scope) throws DKException, java.lang.Exception;

    dkDatastoreAdmin datastoreAdmin() throws DKException, java.lang.Exception;
}
