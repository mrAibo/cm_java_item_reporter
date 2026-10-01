package com.mraibo.cminsight.repository;

import java.util.Objects;
import java.util.Optional;

/**
 * Reports that a {@link RepositoryContextFactory} could not activate a repository, optionally carrying
 * the partially built context that must stay visible to {@link RepositoryManager}.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>{@link RepositoryContextFactory#create} is allowed to allocate before it fails: a production
 * factory opens a bounded CM session pool, initializes it, and only then builds the read services. If a
 * later step throws, the pool it already allocated is <em>physically live</em> and the manager has never
 * seen it - the manager can only close what a factory returned. Releasing the reference with the
 * exception would make that pool invisible, and the next activation attempt would open a second one on
 * top of resources the first still owns. That is the same class of defect Goal 01C removed from the
 * switch path, one layer further out.
 *
 * <p>So the factory hands the cleanup target back instead of dropping it. The manager retains it in the
 * same latch a switch uses, closes it through the same close-outcome path, and refuses every later
 * activation until it is proven terminal-clean. The three outcomes are therefore identical to a switch
 * away from a live repository:
 *
 * <ul>
 *   <li>{@link com.mraibo.cminsight.core.CloseState#CLOSED_CLEAN} - activation failed, but everything
 *       allocated is proven gone, so a later explicit activation may proceed;</li>
 *   <li>{@link com.mraibo.cminsight.core.CloseState#CLOSING} - a physical resource is still
 *       outstanding, so retries are refused as
 *       {@link RepositoryManager.Refusal#PENDING} until it is released;</li>
 *   <li>{@link com.mraibo.cminsight.core.CloseState#CLOSED_UNCERTAIN} - retries are refused as
 *       {@link RepositoryManager.Refusal#UNCERTAIN} permanently in this process.</li>
 * </ul>
 *
 * <p>There is deliberately no force-open path and no way to express "ignore the leftover": the whole
 * point is that the leftover cannot be forgotten.
 *
 * <p>A factory that allocated nothing simply throws its own exception, or passes no cleanup context, and
 * the manager's behaviour is unchanged.
 */
public class ActivationFailedException extends RepositoryException {

    private static final long serialVersionUID = 1L;

    private final transient RepositoryContext cleanupContext;

    /**
     * An activation failure with nothing to clean up: the factory allocated no resource it must hand
     * back.
     */
    public ActivationFailedException(String message) {
        this(message, null, null);
    }

    public ActivationFailedException(String message, Throwable cause) {
        this(message, null, cause);
    }

    /**
     * An activation failure that left resources the manager must be able to close.
     *
     * @param cleanupContext the partially built context, or {@code null} when there is nothing to release
     * @param cause          the underlying failure, or {@code null}
     */
    public ActivationFailedException(String message, RepositoryContext cleanupContext, Throwable cause) {
        super(message, cause);
        this.cleanupContext = cleanupContext;
    }

    /**
     * The partially built context the manager must retain and close, or empty when the failed attempt
     * allocated nothing it has to hand back.
     *
     * <p>Present does NOT mean the cleanup succeeded - it means the manager now owns the question, and
     * the context's own {@link com.mraibo.cminsight.core.CloseState} is what decides whether a later
     * activation may proceed.
     */
    public Optional<RepositoryContext> cleanupContext() {
        return Optional.ofNullable(cleanupContext);
    }

    /** True when this failure carries a context the manager must not lose. */
    public boolean hasCleanupContext() {
        return cleanupContext != null;
    }

    public ActivationFailedException withCleanupContext(RepositoryContext context) {
        Objects.requireNonNull(context, "context");
        return new ActivationFailedException(getMessage(), context, getCause());
    }
}
