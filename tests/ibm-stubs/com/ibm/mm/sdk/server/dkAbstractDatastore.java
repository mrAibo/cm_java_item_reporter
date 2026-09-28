/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.server;

import com.ibm.mm.sdk.common.DKConstant;
import com.ibm.mm.sdk.common.DKException;
import com.ibm.mm.sdk.common.DKMessageId;
import com.ibm.mm.sdk.common.dkDatastore;
import com.ibm.mm.sdk.common.dkDatastoreDef;

/**
 * Direct superclass of {@code DKDatastoreICM}.
 *
 * <p>Note the documented difference that this hierarchy reproduces: here {@code isConnected()}
 * declares {@code throws java.lang.Exception}, while the {@code DKDatastoreICM} override declares no
 * throws clause at all. Calling through the concrete type therefore needs no try/catch; calling
 * through {@code dkDatastore} does.
 *
 * <p>Trimmed to the members {@code DKDatastoreICM} overrides or inherits on the read path. The real
 * class also declares {@code connectWithCredential}, {@code setOption}, {@code getOption},
 * {@code commit}, {@code rollback}, {@code startTransaction}, the object data family and the whole
 * {@code dkQueryManager} surface; none is declared here.
 */
public class dkAbstractDatastore implements dkDatastore, DKConstant, DKMessageId {

    public dkAbstractDatastore() throws DKException, java.lang.Exception {
        // no state in a compile stub
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

    public boolean isConnected() throws java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public dkDatastoreDef datastoreDef() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public short validateConnection() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }
}
