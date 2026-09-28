/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.server;

import com.ibm.mm.sdk.common.DKException;
import com.ibm.mm.sdk.common.dkCollection;
import com.ibm.mm.sdk.common.dkDatastoreDef;
import com.ibm.mm.sdk.common.dkDatastoreIntICM;

/**
 * The IBM CM 8.7 datastore. CORRECTION: it lives in {@code com.ibm.mm.sdk.server}, not in
 * {@code com.ibm.mm.sdk.common}. It is the only class this project needs from that package.
 *
 * <p>Facts reproduced deliberately:
 * <ul>
 * <li>both constructors declare {@code throws DKException, java.lang.Exception}, so construction must
 *     sit inside try/catch;</li>
 * <li>{@code isConnected()} declares NO throws clause, unlike the superclass declaration;</li>
 * <li>the teardown pair is {@code disconnect()} then {@code destroy()};</li>
 * <li>there is NO {@code close()} method anywhere in the SDK, so none is declared here;</li>
 * <li>there is no static {@code connect(...)} factory in cmbicmsdk81.jar, so none is declared
 *     here;</li>
 * <li>{@code connection()} is deliberately NOT declared - it hands back a native JDBC handle and
 *     native JDBC extraction is forbidden in this project.</li>
 * </ul>
 *
 * <p>Trimmed to the lifecycle and listing members the adapter uses. The real class also declares
 * {@code addObject(s)}, {@code updateObject(s)}, {@code deleteObject(s)}, {@code moveObject},
 * {@code commit}, {@code rollback}, {@code startTransaction}, {@code checkIn}, {@code checkOut},
 * {@code changePassword}, {@code setOption}, {@code writeEvent}, the SSL setters, the pool
 * ({@code turnOffPool}, {@code returnConnectionToPool}), {@code clearCache} and the
 * {@code createDDO} family; none is declared here, so a write or a native handle cannot compile in
 * {@code src/ibm/java}.
 */
public class DKDatastoreICM extends dkAbstractDatastore implements dkDatastoreIntICM {

    public DKDatastoreICM() throws DKException, java.lang.Exception {
        super();
    }

    public DKDatastoreICM(java.lang.String datastoreName) throws DKException, java.lang.Exception {
        super();
    }

    public void connect(java.lang.String datastoreName, java.lang.String userName, java.lang.String password,
            java.lang.String options) throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public void disconnect() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public void destroy() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    /** No throws clause on the concrete class, exactly as in the real SDK. */
    public boolean isConnected() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    /** Returns the base interface - an explicit cast to DKDatastoreDefICM is mandatory. */
    public dkDatastoreDef datastoreDef() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public dkCollection listEntities() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String[] listEntityNames() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public short validateConnection() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    /** Static API release of the SDK, e.g. {@code "0807000000"} at the 8.7 level. */
    public static java.lang.String getAPIVer() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    /** Static API release of the library server. */
    public static java.lang.String getAPILSVer() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }
}
