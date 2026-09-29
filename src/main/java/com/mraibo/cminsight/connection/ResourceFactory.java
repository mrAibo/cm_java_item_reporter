package com.mraibo.cminsight.connection;

/**
 * Creates and validates the resources owned by a {@link BoundedPool}.
 *
 * <p>Implementations must never block indefinitely, and must tell the pool the truth about a failed
 * attempt. Reporting the outcome is mandatory, not optional: since Goal 02A a failed creation releases
 * its reserved capacity slot <strong>only</strong> when the factory explicitly proves a clean cleanup by
 * throwing {@link CreationFailure} with {@link CreationFailure.Cleanup#PROVEN_CLEAN}. A partial cleanup
 * is never reported as a clean one, and an undeclared outcome is treated as the unsafe one.
 *
 * <h2>Why the failed attempt's outcome has to be declared</h2>
 *
 * A throwing {@code create()} is invisible to the pool: the pool never receives a resource, so it cannot
 * account for one. Whether that attempt left something physically alive is knowledge only the factory
 * has, and it is not optional information - the configured pool size is a hard <em>physical</em> bound.
 * If an implementation opened a CM session or a JDBC connection and then threw without closing it, that
 * resource would be alive while the pool believed the attempt was empty, and the next borrow would open a
 * replacement on top of it. The pool cannot detect that, so the factory must say what happened.
 *
 * <p>The rule, and the outcome to throw:
 *
 * <ul>
 *   <li><strong>Release the slot - explicitly:</strong> the attempt allocated nothing, or it closed
 *       everything it allocated and that close returned normally. Throw
 *       {@code new CreationFailure(Cleanup.PROVEN_CLEAN, message[, cause])}. This is the <em>only</em>
 *       outcome that lets the pool create a replacement in that slot.</li>
 *   <li><strong>Quarantine - the conservative default:</strong> anything else. An explicit
 *       {@code CreationFailure(UNPROVEN)} is the honest declaration of it, and a plain {@link Exception},
 *       a {@link RuntimeException}, an {@link Error} or an {@link InterruptedException} thrown out of
 *       {@code create()} is read the same way. The slot then stays consumed for the lifetime of the pool,
 *       so no replacement is created while the old resource may still exist. Capacity is deliberately
 *       lost rather than the physical hard bound risked.</li>
 * </ul>
 *
 * <p>This default is deliberately the <em>opposite</em> of the pre-Goal-02A contract, where an ordinary
 * exception meant "this attempt left nothing behind" and released the slot. That reading required every
 * adapter author to remember a special exception to keep the bound honest - a fail-open dependency on the
 * caller - and the Goal 02 architecture review rejected it. The practical consequence is narrow: a factory
 * that knows it failed before allocating anything (argument validation, a missing vendor SDK, a closed
 * configuration) should now say {@code PROVEN_CLEAN} instead of throwing a plain failure, otherwise it
 * costs the pool one capacity slot for good. Being wrong in the conservative direction only degrades the
 * pool; being wrong in the other direction breaches the physical bound.
 */
public interface ResourceFactory<T extends AutoCloseable> {

    /**
     * Creates one resource. A {@code null} return is rejected by the pool.
     *
     * <p>Contract: on failure the caller must be told what happened to this attempt's allocation, because
     * the pool releases the reserved capacity slot only for an explicit
     * {@link CreationFailure.Cleanup#PROVEN_CLEAN}. Close everything you allocated and throw that outcome;
     * if that is not provable, throw {@link CreationFailure.Cleanup#UNPROVEN} (or let the ordinary failure
     * propagate, which the pool reads the same way). A resource leaked here is invisible to the pool - see
     * the class notes.
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
