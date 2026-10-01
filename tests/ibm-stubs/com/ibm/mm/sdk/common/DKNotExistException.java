/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

import com.ibm.mm.sdk.logtool.DKLogMessageInserts;

/**
 * Thrown by {@code DKPolicyMgmtICM.listItemTypeNamesByRetentionPolicy(String)} - the first of the
 * three exceptions in that throws clause. Note it is a subtype of {@code DKDatastoreAccessError},
 * not of {@code DKUsageError}.
 */
public class DKNotExistException extends DKDatastoreAccessError implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    public DKNotExistException() {
        super();
    }

    public DKNotExistException(java.lang.String message) {
        super(message);
    }

    public DKNotExistException(java.lang.String message, int errorCode) {
        super(message, errorCode);
    }

    public DKNotExistException(java.lang.String message, int errorCode, int errorState) {
        super(message, errorCode);
    }

    public DKNotExistException(java.lang.String message, int errorCode, java.lang.String errorStateName,
            int errorState) {
        super(message, errorCode);
    }

    public DKNotExistException(java.lang.String message, DKLogMessageInserts inserts) {
        super(message, inserts);
    }

    public DKNotExistException(java.lang.String message, int errorCode, DKLogMessageInserts inserts) {
        super(message, errorCode, inserts);
    }

    public DKNotExistException(java.lang.String message, int errorCode, int errorState,
            DKLogMessageInserts inserts) {
        super(message, errorCode, inserts);
    }

    public DKNotExistException(java.lang.String message, int errorCode, java.lang.String errorStateName,
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
