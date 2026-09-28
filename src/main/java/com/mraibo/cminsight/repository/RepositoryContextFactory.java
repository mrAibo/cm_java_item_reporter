package com.mraibo.cminsight.repository;

import com.mraibo.cminsight.config.RepositoryProfile;

/**
 * Builds a fully initialized {@link RepositoryContext}.
 *
 * <p>This is the seam that keeps the Goal 01 core runtime free of any IBM CM SDK dependency: the
 * production implementation will attach the bounded CM and JDBC pools, while tests supply a fake that
 * can be made to succeed, fail, or fail after partially allocating resources.
 *
 * <p>Implementations must either return a completely initialized context or throw. They must not
 * return a partially initialized or already closed context, and they must not leak resources when
 * they fail.
 *
 * <p>That last point is a hard contract rather than an aspiration: the manager can only close what a
 * factory actually returned. If a factory allocates resources and then throws, those resources are
 * invisible to the manager and releasing them is entirely the factory's own responsibility.
 */
@FunctionalInterface
public interface RepositoryContextFactory {

    /**
     * Creates and initializes a context for the given profile.
     *
     * @throws Exception when initialization fails; the manager will publish nothing
     */
    RepositoryContext create(RepositoryProfile profile) throws Exception;
}
