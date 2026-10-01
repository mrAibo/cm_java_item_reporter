/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * Cursor over a {@link dkCollection}. The real interface declares exactly these three members.
 */
public interface dkIterator {

    java.lang.Object next() throws DKUsageError;

    void reset();

    boolean more();
}
