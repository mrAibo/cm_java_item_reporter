package com.mraibo.cminsight.core;

import com.mraibo.cminsight.config.RepositoryProfile;

/**
 * Opens one physical CM session for a repository profile.
 *
 * <p>This is the seam between the IBM-independent core and an optional vendor adapter. The core never
 * names a vendor type, and the adapter never leaks one through this interface: a caller receives a
 * {@link com.mraibo.cminsight.connection.CmSession}, which is a vendor-neutral handle.
 *
 * <p>Implementations are the resource source of a
 * {@link com.mraibo.cminsight.connection.BoundedPool}: the adapter supplies a
 * {@link com.mraibo.cminsight.connection.ResourceFactory} built on top of one of these, which is what
 * makes "no session is created outside the pool for normal application work" structural rather than a
 * rule somebody has to remember.
 *
 * <p>Credentials are resolved by the caller from {@link RepositoryProfile} and the secret resolver. An
 * implementation must never log, print or embed a credential value, and must sanitise whatever a vendor
 * failure reports.
 */
public interface CmSessionFactory {

    /**
     * Opens one session.
     *
     * @throws com.mraibo.cminsight.connection.CreationFailure with
     *         {@link com.mraibo.cminsight.connection.CreationFailure.Cleanup#UNPROVEN} when the attempt
     *         may have left a physical session behind, so the pool quarantines the slot instead of
     *         releasing it
     */
    com.mraibo.cminsight.connection.CmSession open(RepositoryProfile profile) throws Exception;
}
