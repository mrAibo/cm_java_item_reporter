/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 *
 * Signatures only. There is no implementation and there never may be one: this file is
 * never packaged, never shipped and never on the production class path. It exists so that
 * src/ibm/java typechecks in CI with zero proprietary JARs.
 *
 * Authority: harness/GOAL_02_IMPLEMENTATION_SPEC.md section 1/3 and the reconnaissance report
 * IBM_CM87_SDK_API_SURFACE.md, both derived from real javap output against cmbicmsdk81.jar.
 * A compile against a real cmbicmsdk81.jar is authoritative over this file.
 *
 * Required by com.ibm.mm.sdk.common.DKException, which extends this class.
 */
package com.ibm.mm.sdk.logtool;

public class DKLogException extends java.lang.Exception implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    public DKLogException() {
        super();
    }

    public DKLogException(java.lang.String message) {
        super(message);
    }

    public DKLogException(java.lang.String message, int eventId) {
        super(message);
    }

    public DKLogException(java.lang.String message, DKLogMessageInserts inserts) {
        super(message);
    }

    public DKLogException(java.lang.String message, int eventId, DKLogMessageInserts inserts) {
        super(message);
    }

    public java.lang.String getErrorEventIdString() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }
}
