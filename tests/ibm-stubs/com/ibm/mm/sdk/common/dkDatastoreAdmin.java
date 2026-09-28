/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * Return type of {@code DKDatastoreDefICM.datastoreAdmin()}.
 *
 * <p>Declared empty on purpose, and load-bearing: the real interface has NO {@code policyMgmt()}.
 * Retention policy management is only reachable by casting the result to
 * {@code DKDatastoreAdminICM}, which is exactly what the adapter must do.
 *
 * <p>The real interface declares {@code setDatastore}, {@code getDatastore}, {@code accessControl},
 * {@code authorizationMgmt}, {@code userManagement}, {@code configurationManagement},
 * {@code workFlowManagement}, {@code adminDomainsMgmt} and {@code clearCache}; none is declared
 * here, so none can be called.
 */
public interface dkDatastoreAdmin {
}
