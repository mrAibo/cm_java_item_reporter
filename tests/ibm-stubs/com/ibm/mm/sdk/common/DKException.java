/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 *
 * The real class is com.ibm.mm.sdk.common.DKException extends
 * com.ibm.mm.sdk.logtool.DKLogException implements com.ibm.mm.sdk.common.DKConstant,
 * java.io.Serializable - which is why the logtool pair must be stubbed as well.
 */
package com.ibm.mm.sdk.common;

import com.ibm.mm.sdk.logtool.DKLogException;
import com.ibm.mm.sdk.logtool.DKLogMessageInserts;

/**
 * The SDK's checked failure type. Every adapter catch of a CM failure catches this type.
 *
 * <p>Constructors delegate to a super constructor and do nothing else; the accessors throw, because
 * an error code is server state that a stub cannot have. Parameter <em>names</em> are not present in
 * bytecode and are descriptive only.
 */
public class DKException extends DKLogException implements DKConstant, java.io.Serializable {

    private static final long serialVersionUID = 1L;

    public DKException() {
        super();
    }

    public DKException(java.lang.String message) {
        super(message);
    }

    public DKException(java.lang.String message, int errorCode) {
        super(message, errorCode);
    }

    public DKException(java.lang.String message, int errorCode, int errorState) {
        super(message, errorCode);
    }

    public DKException(java.lang.String message, int errorCode, java.lang.String errorStateName, int errorState) {
        super(message, errorCode);
    }

    public DKException(DKException source) {
        super(source.getMessage());
    }

    public DKException(java.lang.String message, DKLogMessageInserts inserts) {
        super(message, inserts);
    }

    public DKException(java.lang.String message, int errorCode, DKLogMessageInserts inserts) {
        super(message, errorCode, inserts);
    }

    public DKException(java.lang.String message, int errorCode, int errorState, DKLogMessageInserts inserts) {
        super(message, errorCode, inserts);
    }

    public DKException(java.lang.String message, int errorCode, java.lang.String errorStateName, int errorState,
            DKLogMessageInserts inserts) {
        super(message, errorCode, inserts);
    }

    public java.lang.String errorState() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public int errorCode() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String name() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public int getErrorId() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public short exceptionId() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }
}
