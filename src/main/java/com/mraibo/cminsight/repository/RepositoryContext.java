package com.mraibo.cminsight.repository;

import com.mraibo.cminsight.config.RepositoryProfile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything the modules of one active repository share: the profile and the resources that must be
 * released together when the repository is left.
 *
 * <p>Later phases attach the bounded CM session pool, the bounded JDBC pool and the metadata,
 * statistics and retention repositories here. Modules borrow from this context and never open an
 * ad-hoc connection of their own.
 *
 * <p>{@link #close()} is idempotent and closes resources in reverse acquisition order. A failing
 * resource never prevents the remaining ones from being released; failures are recorded on
 * {@link #closeFailures()} so a switch can report what went wrong.
 *
 * <p>A recorded failure also marks the close as uncertain ({@link #closedWithUncertainResources()}).
 * "Uncertain" is the honest description: a resource that refused to close may still hold a physical
 * connection, so a caller that is about to open new connections of the same kind must fail closed
 * instead of assuming the previous ones are gone. {@link RepositoryManager} is that caller.
 */
public final class RepositoryContext implements AutoCloseable {

    private final RepositoryProfile profile;
    private final List<AutoCloseable> resources;
    private final Instant createdAt = Instant.now();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile List<String> closeFailures = List.of();
    private volatile boolean closedWithUncertainResources;

    public RepositoryContext(RepositoryProfile profile) {
        this(profile, List.of());
    }

    public RepositoryContext(RepositoryProfile profile, List<? extends AutoCloseable> resources) {
        this.profile = Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(resources, "resources");
        List<AutoCloseable> copy = new ArrayList<>(resources.size());
        for (AutoCloseable resource : resources) {
            copy.add(Objects.requireNonNull(resource, "resource"));
        }
        this.resources = Collections.unmodifiableList(copy);
    }

    /**
     * The profile of this context.
     *
     * @throws IllegalStateException when the context is already closed
     */
    public RepositoryProfile profile() {
        if (closed.get()) {
            throw new IllegalStateException("RepositoryContext is closed");
        }
        return profile;
    }

    /** The profile even after the context was closed, for diagnostics and logging. */
    public RepositoryProfile profileUnchecked() {
        return profile;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Owned resources, in acquisition order. Exposed for diagnostics only. */
    public List<AutoCloseable> resources() {
        return resources;
    }

    /** Descriptions of resources that failed to close, if any. */
    public List<String> closeFailures() {
        return closeFailures;
    }

    /**
     * True when {@link #close()} released this context but at least one resource refused to close, so
     * the physical resources of this context are not proven gone.
     *
     * <p>Deliberately a separate question from {@link #isClosed()}: the context IS closed - its own
     * bookkeeping is final, {@code close()} is idempotent and the remaining resources were still
     * released - but the outcome is uncertain, and new connections of the same kind must therefore not
     * be opened on the strength of it.
     *
     * <p>False before {@code close()} runs, false after a fully successful close, and immutable once the
     * first {@code close()} call completed: a later {@code close()} is a no-op. An {@link Error} thrown
     * by a resource is recorded as a failure too, so the flag is visible even when {@code close()}
     * rethrows.
     */
    public boolean closedWithUncertainResources() {
        return closedWithUncertainResources;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<String> failures = new ArrayList<>(0);
        Error fatal = null;
        for (int i = resources.size() - 1; i >= 0; i--) {
            AutoCloseable resource = resources.get(i);
            try {
                resource.close();
            } catch (Exception e) {
                failures.add(describeFailure(resource, e.getMessage()));
            } catch (Error e) {
                // Record it, keep releasing the rest, and rethrow afterwards: one hostile resource
                // must not leave the others open, but an Error is still reported to the caller.
                failures.add(describeFailure(resource, e.getClass().getName()));
                if (fatal == null) {
                    fatal = e;
                }
            }
        }
        this.closeFailures = List.copyOf(failures);
        // Publish the outcome BEFORE a possible rethrow, so a caller that unwinds on the Error still
        // sees an accurate answer, and never AFTER the loop, so a partially released context is never
        // described as a clean one.
        this.closedWithUncertainResources = !failures.isEmpty();
        if (fatal != null) {
            throw fatal;
        }
    }

    private static String describeFailure(AutoCloseable resource, String detail) {
        return resource.getClass().getSimpleName()
                + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }

    @Override
    public String toString() {
        return "RepositoryContext[repository=" + profile.id()
                + ", resources=" + resources.size()
                + ", closed=" + closed.get() + "]";
    }
}
