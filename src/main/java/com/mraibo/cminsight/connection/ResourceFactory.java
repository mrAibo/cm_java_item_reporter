package com.mraibo.cminsight.connection;

/**
 * Creates and validates the resources owned by a {@link BoundedPool}.
 *
 * <p>Implementations must never block indefinitely and must release whatever they allocated before
 * throwing.
 *
 * <h2>Why "release before throwing" is a hard requirement, not a nicety</h2>
 *
 * A throwing {@code create()} is indistinguishable, from the pool's side, from one that created
 * nothing: the pool never receives the resource, so it cannot account for it, and the slot is returned
 * to the capacity pool as if the attempt had been empty. That is fine only while the contract holds. If
 * an implementation opened a CM session or a JDBC connection and then threw without closing it, that
 * resource would be physically alive while the pool reported
 * {@link com.mraibo.cminsight.core.CloseState#CLOSED_CLEAN} - the one state a repository switch is
 * allowed to trust. The pool cannot detect the leak, so the obligation is the implementation's: close
 * what you opened, on every failure path.
 */
public interface ResourceFactory<T extends AutoCloseable> {

    /**
     * Creates one resource. A {@code null} return is rejected by the pool.
     *
     * <p>Contract: on failure, every resource this call allocated must already have been closed before
     * the exception leaves. The pool only accounts for what {@code create()} returns, so a resource
     * leaked here is invisible to it - see the class notes.
     */
    T create() throws Exception;

    /**
     * Cheap liveness check performed when a resource is taken from the idle set and when it is
     * returned. Returning {@code false} retires the resource without ever exceeding the configured
     * capacity.
     *
     * <p>Contract: this runs while {@link BoundedPool} holds its internal lock on the borrow path, on the
     * return path and during a rotation sweep, so it must be fast and must never block on I/O, sleeping
     * or acquiring another lock. A slow probe stalls every concurrent borrow, every {@code metrics()}
     * call AND - because {@link BoundedPool#closeState()} takes the same lock - the shutdown state a
     * repository switch reads before it decides whether to open new connections. This one method cannot
     * tell a borrow from a return, so keep it cheap and do any real round-trip validation in a separate
     * maintenance task rather than here.
     */
    default boolean isHealthy(T resource) {
        return resource != null;
    }

    /** Short label used in pool diagnostics. */
    default String describe() {
        return getClass().getSimpleName();
    }
}
