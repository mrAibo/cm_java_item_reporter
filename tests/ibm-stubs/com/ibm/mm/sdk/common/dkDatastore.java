/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * The SDK datastore interface. Declared because {@code dkAbstractDatastore} implements it and
 * because {@code datastoreDef()} is reached through it.
 *
 * <p>Trimmed to the lifecycle and definition accessors the adapter uses. The real interface declares
 * ~90 members; in particular the whole object data family ({@code addObject}, {@code addObjects},
 * {@code updateObject(s)}, {@code deleteObject(s)}, {@code retrieveObject(s)}, {@code moveObject}),
 * {@code commit}, {@code rollback}, {@code startTransaction}, {@code changePassword},
 * {@code setOption}, {@code clearCache}, {@code registerMapping}, {@code addExtension},
 * {@code removeExtension} and the query/execute family are deliberately NOT declared, so a mutating
 * call cannot compile in {@code src/ibm/java}. The real interface also extends
 * {@code dkQueryManager} -> {@code dkQueryEvaluator}; that chain is not stubbed because the Goal 02
 * read path issues no query.
 *
 * <p>Parameter <em>names</em> do not exist in bytecode and are descriptive only; the proven working
 * order for {@code connect} in this project is (ssid, user, password, options).
 */
public interface dkDatastore {

    void connect(java.lang.String datastoreName, java.lang.String userName, java.lang.String password,
            java.lang.String options) throws DKException, java.lang.Exception;

    void disconnect() throws DKException, java.lang.Exception;

    void destroy() throws DKException, java.lang.Exception;

    boolean isConnected() throws java.lang.Exception;

    dkDatastoreDef datastoreDef() throws DKException, java.lang.Exception;

    short validateConnection() throws DKException, java.lang.Exception;
}
