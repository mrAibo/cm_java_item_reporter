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
 */
public final class RepositoryContext implements AutoCloseable {

    private final RepositoryProfile profile;
    private final List<AutoCloseable> resources;
    private final Instant createdAt = Instant.now();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile List<String> closeFailures = List.of();

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
