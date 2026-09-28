/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

import com.ibm.mm.sdk.logtool.DKLogMessageInserts;

/**
 * Supertype of {@code DKNotExistException} in the real SDK - reproduced so that
 * {@code catch (DKNotExistException)} before {@code catch (DKException)} keeps the same subtype
 * relationship it has against the real JAR.
 */
public class DKDatastoreAccessError extends DKException implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    public DKDatastoreAccessError() {
        super();
    }

    public DKDatastoreAccessError(java.lang.String message) {
        super(message);
    }

    public DKDatastoreAccessError(java.lang.String message, int errorCode) {
        super(message, errorCode);
    }

    public DKDatastoreAccessError(java.lang.String message, int errorCode, int errorState) {
        super(message, errorCode);
    }

    public DKDatastoreAccessError(java.lang.String message, int errorCode, java.lang.String errorStateName,
            int errorState) {
        super(message, errorCode);
    }

    public DKDatastoreAccessError(java.lang.String message, DKLogMessageInserts inserts) {
        super(message, inserts);
    }

    public DKDatastoreAccessError(java.lang.String message, int errorCode, DKLogMessageInserts inserts) {
        super(message, errorCode, inserts);
    }

    public DKDatastoreAccessError(java.lang.String message, int errorCode, int errorState,
            DKLogMessageInserts inserts) {
        super(message, errorCode, inserts);
    }

    public DKDatastoreAccessError(java.lang.String message, int errorCode, java.lang.String errorStateName,
            int errorState, DKLogMessageInserts inserts) {
        super(message, errorCode, inserts);
    }

    public java.lang.String name() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public short exceptionId() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }
}
