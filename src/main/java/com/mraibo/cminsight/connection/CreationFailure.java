package com.mraibo.cminsight.connection;

/**
 * The vendor-neutral outcome of a failed {@link ResourceFactory#create()} attempt, in the terms
 * {@link BoundedPool} needs in order to decide whether the reserved capacity slot may be released.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>A {@code create()} that throws is indistinguishable, from the pool's side, from one that created
 * nothing: the pool never receives a resource, so it cannot account for one. Until this type existed the
 * pool therefore always returned the reserved slot on failure, and the obligation "close what you opened
 * before you throw" was documented on {@link ResourceFactory} as the implementation's problem.
 *
 * <p>That obligation is unenforceable from inside the pool, and for a real IBM CM adapter it is also not
 * always satisfiable. Connecting is a two-step physical operation - allocate a {@code DKDatastoreICM},
 * then {@code connect} it - and the cleanup of a half-built session can itself fail. When that happens the
 * physical session may still exist while the pool believes the slot is free, and the next borrow opens a
 * replacement on top of it. The configured pool size is a hard physical bound, so that is a bound breach,
 * not an accounting inaccuracy.
 *
 * <h2>The two outcomes</h2>
 *
 * <dl>
 *   <dt>{@link Cleanup#PROVEN_CLEAN}</dt>
 *   <dd>Nothing was allocated, or everything that was allocated was closed and that close returned
 *       normally. The reserved slot is released and a later borrow may create a replacement.</dd>
 *
 *   <dt>{@link Cleanup#UNPROVEN}</dt>
 *   <dd>Something may have been allocated and its removal was not proven. The reserved slot is
 *       <strong>quarantined</strong>: it stays consumed for the lifetime of the pool, exactly like a slot
 *       whose {@code close()} was uncertain, so no replacement can be authorised while the old resource
 *       may still exist. This deliberately degrades the pool rather than risk exceeding the bound.</dd>
 * </dl>
 *
 * <p>An implementation must choose {@code UNPROVEN} whenever it cannot prove the cleanup succeeded. The
 * safe direction is always to under-claim: reporting {@code PROVEN_CLEAN} for a cleanup that did not
 * actually complete is the one mistake this class exists to prevent.
 *
 * <h2>Relationship to a plain exception</h2>
 *
 * <p>A {@code create()} that throws something which is <em>not</em> a {@code CreationFailure} keeps the
 * historical reading: the slot is released. That keeps every Goal 01 behaviour and every existing
 * {@code ResourceFactory} implementation working unchanged, and it is why this is a distinct type rather
 * than a flag on a generic exception. A factory that can allocate a physical resource and then fail
 * uncertainly - the CM adapter is the first - must throw this type to say so. The residual risk is
 * recorded in {@code STATUS.md}: a factory that opens a resource, fails to clean it up and then throws an
 * ordinary exception is still invisible to the pool.
 */
public class CreationFailure extends Exception {

    private static final long serialVersionUID = 1L;

    /** Whether the failing {@code create()} proved that it released everything it allocated. */
    public enum Cleanup {

        /**
         * The attempt allocated nothing, or closed every resource it allocated and that close returned
         * normally. The reserved capacity slot may be released.
         */
        PROVEN_CLEAN,

        /**
         * The attempt may have left a physical resource behind and could not prove otherwise. The
         * reserved capacity slot is quarantined and no replacement is authorised.
         */
        UNPROVEN
    }

    private final Cleanup cleanup;

    public CreationFailure(Cleanup cleanup, String message) {
        super(message);
        this.cleanup = java.util.Objects.requireNonNull(cleanup, "cleanup");
    }

    public CreationFailure(Cleanup cleanup, String message, Throwable cause) {
        super(message, cause);
        this.cleanup = java.util.Objects.requireNonNull(cleanup, "cleanup");
    }

    /** The cleanup outcome the failing factory reported. Never {@code null}. */
    public Cleanup cleanup() {
        return cleanup;
    }

    /**
     * True when the factory proved that it released everything it allocated, so the pool may release the
     * reserved slot. This is the only reading that permits a replacement to be created.
     */
    public boolean cleanupProven() {
        return cleanup == Cleanup.PROVEN_CLEAN;
    }
}
