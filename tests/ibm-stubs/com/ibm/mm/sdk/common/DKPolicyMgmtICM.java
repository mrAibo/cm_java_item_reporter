/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * Retention policy management - the entry point for the Goal 02 retention read service.
 *
 * <p>Only read members are declared. The real class also declares {@code add}, {@code update},
 * {@code del(DKRetentionPolicyDefICM)}, {@code del(int)}, {@code del(String)} and {@code clearCache};
 * they are intentionally absent so that no mutating retention call can compile in
 * {@code src/ibm/java}.
 *
 * <p>{@code listItemTypeNamesByRetentionPolicy(String)} is the one method here whose throws clause
 * leads with {@code DKNotExistException}.
 */
public class DKPolicyMgmtICM {

    public DKPolicyMgmtICM(dkDatastore datastore) throws DKException, java.lang.Exception {
        // no state in a compile stub
    }

    public dkCollection listRetentionPolicies() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String[] listRetentionPolicyNames() throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public java.lang.String[] listItemTypeNamesByRetentionPolicy(java.lang.String policyName)
            throws DKNotExistException, DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public dkCollection listItemTypesByRetentionPolicy(java.lang.String policyName)
            throws DKNotExistException, DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public DKRetentionPolicyDefICM retrieveRetentionPolicy(int policyId) throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }

    public DKRetentionPolicyDefICM retrieveRetentionPolicy(java.lang.String policyName)
            throws DKException, java.lang.Exception {
        throw new UnsupportedOperationException("IBM CM 8.7 compile stub: no implementation");
    }
}
