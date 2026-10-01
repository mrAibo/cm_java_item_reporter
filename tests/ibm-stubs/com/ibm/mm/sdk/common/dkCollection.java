/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * Return type of every {@code list*} call in the read chain ({@code listEntities(int)},
 * {@code listRetentionPolicies()}).
 *
 * <p>Trimmed to the read-only iteration surface. The real interface also declares
 * {@code addElement}, {@code addAllElements}, {@code insertElementAt}, {@code replaceElementAt},
 * {@code removeElementAt}, {@code removeAllElements}, {@code setOwner}, {@code getOwner},
 * {@code setName}, {@code getAssociatedAttrName} and {@code setAssociatedAttrName}; none of them is
 * declared here on purpose, so no collection mutation can even compile in {@code src/ibm/java}.
 */
public interface dkCollection {

    int cardinality();

    dkIterator createIterator();

    java.lang.Object retrieveElementAt(dkIterator iterator) throws DKUsageError;
}
