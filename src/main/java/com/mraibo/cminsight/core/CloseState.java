package com.mraibo.cminsight.core;

/**
 * The shutdown state of a resource that owns other resources, in the terms a caller needs in order to
 * decide whether it may open new resources of the same kind.
 *
 * <h2>Why a boolean was not enough</h2>
 *
 * Goal 01B established that a container can return normally from {@code close()} while a member it
 * could not close is still physically alive. A single uncertainty flag cannot express that, because the
 * interesting distinction is not only "certain or not" but also "finished or not yet finished":
 *
 * <ul>
 *   <li>{@code close()} has been called but a leased resource is still out with its user, so its
 *       {@code close()} has not even been attempted. Nothing is uncertain yet - the resource is simply
 *       still alive and still legitimately in use.</li>
 *   <li>{@code close()} has been called and every member either proved itself closed or failed
 *       uncertainly. The shutdown is final.</li>
 * </ul>
 *
 * <p>Collapsing the first case into "clean" is the Goal 01B review finding F3 defect: a repository
 * switch would create the next context while the previous repository still owned a live connection.
 * Collapsing it into "uncertain" would be wrong too, and operationally worse: it would report a leak
 * where there is only an outstanding lease, and a quarantine is a state that never clears while a
 * draining pool is expected to clear.
 *
 * <h2>The four states</h2>
 *
 * <dl>
 *   <dt>{@link #NOT_CLOSED}</dt>
 *   <dd>Close has not been requested. Resources may be in use; nothing is claimed about them.</dd>
 *
 *   <dt>{@link #CLOSING}</dt>
 *   <dd>Close has been requested and is not finished: at least one physical resource is still
 *       outstanding (leased, being created, or retiring). This is a PROVISIONAL state - a later query may
 *       answer {@link #CLOSED_CLEAN} or {@link #CLOSED_UNCERTAIN} instead. It is never evidence of a
 *       clean shutdown.</dd>
 *
 *   <dt>{@link #CLOSED_CLEAN}</dt>
 *   <dd>Close is finished and every physical resource is proven gone. This is the only terminal state
 *       that permits a caller to treat the previous resources as released.</dd>
 *
 *   <dt>{@link #CLOSED_UNCERTAIN}</dt>
 *   <dd>Close is finished but at least one physical resource may still exist - a close threw, or a
 *       container quarantined a member. Terminal, and fail-closed forever: this never improves to
 *       {@link #CLOSED_CLEAN}, because nothing in the process can prove that the resource disappeared.</dd>
 * </dl>
 *
 * <h2>Vendor neutrality</h2>
 *
 * <p>This is deliberately a vocabulary, not a policy: it says what happened, never whether the caller
 * should proceed. No IBM CM and no JDBC type may appear in this enum or in the signatures that use it.
 */
public enum CloseState {

    /** Close has not been requested. */
    NOT_CLOSED(false, false),

    /**
     * Close has been requested and at least one physical resource is still outstanding.
     *
     * <p>Provisional by design: querying again later may report {@link #CLOSED_CLEAN} or
     * {@link #CLOSED_UNCERTAIN}.
     */
    CLOSING(false, false),

    /** Close is finished and every physical resource is proven gone. */
    CLOSED_CLEAN(true, true),

    /**
     * Close is finished but at least one physical resource may still exist.
     *
     * <p>Terminal and permanently fail-closed.
     */
    CLOSED_UNCERTAIN(true, false);

    private final boolean terminal;
    private final boolean clean;

    CloseState(boolean terminal, boolean clean) {
        this.terminal = terminal;
        this.clean = clean;
    }

    /**
     * True when the shutdown has finished, so no later query of the same resource can change the answer
     * between clean and uncertain.
     *
     * <p>Note that a terminal state is not necessarily a USABLE one: {@link #CLOSED_UNCERTAIN} is final
     * and is exactly the state a caller must refuse to build on.
     */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * True only for {@link #CLOSED_CLEAN}: the shutdown finished AND every physical resource is proven
     * gone. This is the single predicate a repository switch may rely on.
     */
    public boolean isTerminalClean() {
        return clean;
    }

    /** True for {@link #CLOSING}: close is under way and something physical is still outstanding. */
    public boolean isPending() {
        return this == CLOSING;
    }

    /**
     * True when a caller that is about to open new resources of the same kind must NOT do so.
     *
     * <p>Everything except {@link #CLOSED_CLEAN} refuses: an open resource is merely unproven rather than
     * certainly gone, and "unproven" is exactly what the hard physical bound cannot afford to gamble on.
     */
    public boolean refusesReuse() {
        return this != CLOSED_CLEAN;
    }
}
