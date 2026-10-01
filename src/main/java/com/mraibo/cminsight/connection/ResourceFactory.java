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
 *
 * <h2>The health question is explicit for the same reason</h2>
 *
 * <p>{@link #isHealthy} used to carry a permissive default ("non-null means healthy"), which was the same
 * fail-open shape on the retirement path: a composition root that forgot to override it reported an
 * already-unusable resource as healthy and the pool kept handing it out. Since Goal 02B the method is
 * abstract, so every implementation must state what "healthy" means for its own resource type - see its
 * contract for what such a check may and may not do. Both obligations therefore live in this type instead
 * of in the memory of whoever wired the pool.
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
     * Cheap liveness check performed when a resource is taken from the idle set, when it is returned and
     * during a rotation sweep. Returning {@code false} retires the resource without ever exceeding the
     * configured capacity.
     *
     * <h2>Stating a health policy is mandatory - this method has no default</h2>
     *
     * <p>Until Goal 02B this was a {@code default} method answering {@code resource != null}. That made
     * safe retirement depend on every composition root remembering to override it, and the omission was
     * silent: a factory that forgot reported every non-null resource - including one already marked
     * unusable - as healthy, so the pool kept handing out a dead session until some later call failed on
     * it. There is no truthful generic answer here; only the resource type knows whether it has a local
     * health state at all. The method is therefore <strong>abstract on purpose</strong>, which is a
     * compile-breaking change for every implementation: an omission is now a compile error instead of a
     * runtime correctness bug. No convenience overload, no "always healthy" policy object and no other
     * inheritable default replaces it - reintroducing one would restore exactly the defect.
     *
     * <h2>What a health check must be</h2>
     *
     * <p>This runs while {@link BoundedPool} holds its internal lock on the borrow path, on the return path
     * and during a rotation sweep, so it must be <strong>cheap, local and non-blocking</strong>: a volatile
     * or atomically-read flag, or an equivalent in-memory state test. It must never perform network or
     * vendor I/O, never sleep, never acquire another lock and never open a round trip. A slow probe stalls
     * every concurrent borrow, every {@code metrics()} call AND - because {@link BoundedPool#closeState()}
     * takes the same lock - the shutdown state a repository switch reads before it decides whether to open
     * new connections. Any real round-trip validation belongs in a separate maintenance task, never here.
     *
     * <p>Each implementation must either make that local check, or state a constant
     * <em>deliberately</em> - with a comment saying why this resource type has no stronger local health
     * state - so the choice is visible in the code rather than inherited by accident. A {@code null}
     * resource is never passed by {@link BoundedPool} (a null creation is rejected outright), but defensive
     * handling is still allowed.
     *
     * @param resource the live resource the pool is about to hand out or keep; never {@code null}
     * @return {@code false} to retire the resource at the next safe point, {@code true} to keep it
     */
    boolean isHealthy(T resource);

    /** Short label used in pool diagnostics. */
    default String describe() {
        return getClass().getSimpleName();
    }
}
