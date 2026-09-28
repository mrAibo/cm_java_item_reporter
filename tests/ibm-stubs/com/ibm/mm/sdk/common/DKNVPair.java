/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * Name/value pair used by the SDK option APIs. Reproduced as a read-only value holder: the
 * constructor plus the two getters. The real class also declares {@code setName},
 * {@code setValue} and {@code set(String,Object)}; they are omitted so that no {@code set*} member
 * exists anywhere in this stub set (see tests/ibm-stubs/README.md). No behaviour is implemented.
 */
public class DKNVPair implements DKMessageId, java.io.Serializable {

    private static final long serialVersionUID = 1L;

    public DKNVPair(java.lang.String name, java.lang.Object value) {
        // no state in a compile stub
    }

    public java.lang.String getName() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.Object getValue() {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }
}
