package com.mraibo.cminsight.core;

/**
 * A closeable resource that can describe the <em>state</em> of its shutdown, not merely whether the
 * shutdown was uncertain.
 *
 * <h2>Why this is separate from {@link CloseOutcomeAware}</h2>
 *
 * {@link CloseOutcomeAware} answers one question - "did the close that returned leave physical resources
 * unproven?" - and a caller that only asks it cannot tell a pool that finished from a pool that is still
 * draining a lease that somebody is using. That distinction is the whole of Goal 01C: a repository
 * switch can overlap an outstanding old lease exactly as easily as it can overlap a quarantined
 * connection, and only the second one was visible before.
 *
 * <p>It is a separate interface rather than a change to {@link CloseOutcomeAware} on purpose. The binary
 * question remains the right contract for a simple resource that closes synchronously, and every
 * existing implementation and caller keeps working unchanged. A container that owns a pool - which
 * genuinely has a draining phase - additionally implements this one, and callers that care about
 * quiescence ask the richer question.
 *
 * <p>Implementations should also implement {@link CloseOutcomeAware}: a state of
 * {@link CloseState#CLOSED_UNCERTAIN} has to remain visible to the existing fail-closed chain, and
 * {@link CloseOutcomeAware#uncertainCloseDetail()} is where the value-free explanation lives.
 *
 * <h2>What an implementation promises</h2>
 *
 * <ul>
 *   <li>the answer must be safe to read at any time, including concurrently with {@code close()} and
 *       after it returned;</li>
 *   <li>the answer must be CHEAP and must never block. It is read while a caller holds a lifecycle lock -
 *       {@code RepositoryManager} asks the previous repository's context whether its shutdown is finished,
 *       and refuses to create the next repository until it is. An implementation that waits on I/O, sleeps
 *       or acquires a contended lock inside {@code closeState()} therefore stalls a repository switch;
 *       report what you know now instead of waiting for a better answer. A caller that needs to WAIT uses
 *       its own bounded wait around this call, never a blocking implementation;</li>
 *   <li>{@link CloseState#NOT_CLOSED} before close is requested, {@link CloseState#CLOSING} while
 *       physical resources are still outstanding after close began, and only
 *       {@link CloseState#CLOSED_CLEAN} once every one of them is proven gone;</li>
 *   <li>{@link CloseState#CLOSED_UNCERTAIN} is terminal: a close outcome that is uncertain never
 *       becomes certain later, because nothing in this process can prove the physical resource is
 *       gone. An implementation whose state could flap back to {@link CloseState#CLOSED_CLEAN} would
 *       create a fail-open window in every caller that retries, so this is a hard requirement of the
 *       contract rather than a property each caller may assume;</li>
 *   <li>a resource that is still physically outstanding must never be reported as
 *       {@link CloseState#CLOSED_CLEAN};</li>
 *   <li>the answer carries no secret - {@link CloseOutcomeAware#uncertainCloseDetail()} names counts and
 *       sources, never values.</li>
 * </ul>
 *
 * <h2>Vendor neutrality</h2>
 *
 * <p>No IBM CM and no JDBC class may appear in this contract or in the signatures of its
 * implementations.
 */
public interface CloseStateAware {

    /**
     * The current shutdown state.
     *
     * <p>Read-only and side-effect free: it reports, it never triggers a close, a wait or a retry.
     *
     * @return the state, never {@code null}; {@link CloseState#NOT_CLOSED} before close is requested
     */
    CloseState closeState();
}
