package com.mraibo.cminsight.connection;

/**
 * Creates and validates the resources owned by a {@link BoundedPool}.
 *
 * <p>Implementations must never block indefinitely and must release whatever they allocated before
 * throwing.
 */
public interface ResourceFactory<T extends AutoCloseable> {

    /** Creates one resource. A {@code null} return is rejected by the pool. */
    T create() throws Exception;

    /**
     * Cheap liveness check performed when a resource is taken from the idle set and when it is
     * returned. Returning {@code false} retires the resource without ever exceeding the configured
     * capacity.
     *
     * <p>Contract: this runs while {@link BoundedPool} holds its internal lock on the borrow path and
     * during a rotation sweep, so it must be fast and must never block on I/O, sleeping or acquiring
     * another lock. A slow probe stalls every concurrent borrow and every {@code metrics()} call. This
     * one method cannot tell a borrow from a return, so keep it cheap and do any real round-trip
     * validation in a separate maintenance task rather than here.
     */
    default boolean isHealthy(T resource) {
        return resource != null;
    }

    /** Short label used in pool diagnostics. */
    default String describe() {
        return getClass().getSimpleName();
    }
}
