/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

import com.ibm.mm.sdk.logtool.DKLogMessageInserts;

/**
 * The SDK's "usage" failure type. Declared because {@code dkIterator.next()},
 * {@code dkCollection.retrieveElementAt(...)} and {@code DKRetrieveOptionsICM.createInstance(...)}
 * throw it in the real SDK.
 */
public class DKUsageError extends DKException implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    public DKUsageError() {
        super();
    }

    public DKUsageError(java.lang.String message) {
        super(message);
    }

    public DKUsageError(java.lang.String message, int errorCode) {
        super(message, errorCode);
    }

    public DKUsageError(java.lang.String message, int errorCode, int errorState) {
        super(message, errorCode);
    }

    public DKUsageError(java.lang.String message, int errorCode, java.lang.String errorStateName, int errorState) {
        super(message, errorCode);
    }

    public DKUsageError(java.lang.String message, DKLogMessageInserts inserts) {
        super(message, inserts);
    }

    public DKUsageError(java.lang.String message, int errorCode, DKLogMessageInserts inserts) {
        super(message, errorCode, inserts);
    }

    public DKUsageError(java.lang.String message, int errorCode, int errorState, DKLogMessageInserts inserts) {
        super(message, errorCode, inserts);
    }

    public DKUsageError(java.lang.String message, int errorCode, java.lang.String errorStateName, int errorState,
            DKLogMessageInserts inserts) {
        super(message, errorCode, inserts);
    }

    public java.lang.String name() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public short exceptionId() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }
}
