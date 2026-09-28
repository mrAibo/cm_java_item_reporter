package com.mraibo.cminsight.core;

/**
 * A closeable resource that can report whether its shutdown finished with UNCERTAIN physical
 * resources.
 *
 * <h2>Why an ordinary {@link AutoCloseable} is not enough</h2>
 *
 * A container can return normally from {@code close()} while one of its members refused to close: the
 * failure was handled inside the container and the member was quarantined instead of freed. To a caller
 * that only watches for a thrown exception, that shutdown looks clean - which silently defeats every
 * fail-closed rule built on top of it. The one that matters most here is the rule that a repository
 * switch must not create the next context while the previous one may still hold live CM sessions or
 * JDBC connections.
 *
 * <p>Reporting the outcome through an interface rather than through pool-specific methods keeps that
 * rule generic: a {@code RepositoryContext} can own a pool, a future adapter, or a plain resource and
 * still ask the same question, and no vendor type has to appear in the repository layer.
 *
 * <h2>What an implementation promises</h2>
 *
 * <p>It <em>reports</em>; it does not decide policy. The caller decides what an uncertain shutdown
 * means - typically, refusing to open the next resource. An implementation must be safe to query after
 * {@code close()} has returned, and it must never expose a secret: the detail string is for operator
 * diagnostics and is expected to name counts and sources, never values.
 *
 * <h2>Vendor neutrality</h2>
 *
 * No IBM CM and no JDBC class may appear in this contract or in the signatures of its implementations.
 */
public interface CloseOutcomeAware extends AutoCloseable {

    /**
     * Closes the resource.
     *
     * <p>The inherited {@code throws Exception} is deliberately narrowed to nothing. A resource that can
     * <em>report</em> an uncertain shutdown should not also fail with a checked exception: the outcome is
     * carried by {@link #closedWithUncertainResources()} and {@link #uncertainCloseDetail()}, and the
     * caller decides what it means. Narrowing it also keeps the contract explicit rather than leaving
     * callers to guess whether an interrupt can escape a close.
     */
    @Override
    void close();

    /**
     * Reports whether the last shutdown left physical resources in an unknown state.
     *
     * @return true when {@code close()} completed but at least one physical resource may still exist,
     *         so its capacity must not be reused and must not be assumed free
     */
    boolean closedWithUncertainResources();

    /**
     * Value-free explanation of what is uncertain, for operator diagnostics and logs.
     *
     * @return a short description naming counts and sources, or an empty string when the shutdown was
     *         certain; never a credential, a URL userinfo, or a file content
     */
    default String uncertainCloseDetail() {
        return "";
    }
}
