package com.mraibo.cminsight.repository;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.core.CloseOutcomeAware;

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
 *
 * <p>A resource that can report its own outcome implements {@link CloseOutcomeAware}, and its report is
 * consulted <em>after</em> its {@code close()} returned normally - because a normal return is not the
 * same statement as "proven closed". {@code BoundedPool} is the case this exists for: it swallows the
 * exception of a pooled resource it could not close, quarantines that slot and returns normally, so an
 * exception-only check would call the shutdown clean while a physical session may still exist. Such a
 * report marks this context uncertain exactly like a thrown close and is recorded on
 * {@link #uncertainCloseReports()}.
 *
 * <p>This class is itself {@link CloseOutcomeAware}: a container that owns a context (or an adapter that
 * does) can ask the same question of it and get the same vocabulary.
 */
public final class RepositoryContext implements CloseOutcomeAware {

    private final RepositoryProfile profile;
    private final List<AutoCloseable> resources;
    private final Instant createdAt = Instant.now();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile List<String> closeFailures = List.of();
    private volatile List<String> uncertainCloseReports = List.of();
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
     * Reports of owned resources whose {@code close()} returned normally but which could not prove their
     * physical shutdown, if any.
     *
     * <p>Deliberately separate from {@link #closeFailures()}: the resource <em>did</em> close, so calling
     * it a close failure would describe the wrong thing and send an operator looking for a broken
     * resource instead of an unproven one. The text comes from
     * {@link CloseOutcomeAware#uncertainCloseDetail()} and is expected to name counts and sources only -
     * never a credential, a URL userinfo or a file content.
     */
    public List<String> uncertainCloseReports() {
        return uncertainCloseReports;
    }

    /**
     * True when {@link #close()} released this context but the physical resources of this context are
     * not proven gone: at least one resource refused to close, or a {@link CloseOutcomeAware} resource
     * returned normally and reported itself uncertain (a quarantined pool slot, for example).
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
    @Override
    public boolean closedWithUncertainResources() {
        return closedWithUncertainResources;
    }

    /**
     * Value-free summary of what made the shutdown uncertain, for a container that owns this context.
     *
     * <p>Empty before {@code close()} ran and after a certain shutdown. It publishes nothing beyond what
     * {@link #closeFailures()} and {@link #uncertainCloseReports()} already contain, so no new detail -
     * and in particular no credential - can appear here.
     */
    @Override
    public String uncertainCloseDetail() {
        List<String> failures = closeFailures;
        List<String> reports = uncertainCloseReports;
        if (failures.isEmpty() && reports.isEmpty()) {
            return "";
        }
        StringBuilder detail = new StringBuilder();
        if (!failures.isEmpty()) {
            detail.append(failures.size()).append(" resource(s) did not close: ")
                    .append(String.join("; ", failures));
        }
        if (!reports.isEmpty()) {
            if (detail.length() > 0) {
                detail.append("; ");
            }
            detail.append(reports.size()).append(" resource(s) reported an uncertain shutdown: ")
                    .append(String.join("; ", reports));
        }
        return detail.toString();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<String> failures = new ArrayList<>(0);
        List<String> uncertain = new ArrayList<>(0);
        Error fatal = null;
        for (int i = resources.size() - 1; i >= 0; i--) {
            AutoCloseable resource = resources.get(i);
            try {
                resource.close();
                // A normal return is NOT the same statement as "proven closed". A close-aware container
                // (BoundedPool is the first) returns normally while a member it could not close stays
                // quarantined, so its own report has to be consulted here - without this, a pool
                // quarantine would look like a clean shutdown to the switch rule.
                UncertainOutcome outcome = uncertainOutcomeOf(resource);
                if (outcome.detail() != null) {
                    uncertain.add(outcome.detail());
                }
                if (outcome.fatal() != null && fatal == null) {
                    fatal = outcome.fatal();
                }
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
        this.uncertainCloseReports = List.copyOf(uncertain);
        // Publish the outcome BEFORE a possible rethrow, so a caller that unwinds on the Error still
        // sees an accurate answer, and never AFTER the loop, so a partially released context is never
        // described as a clean one.
        this.closedWithUncertainResources = !failures.isEmpty() || !uncertain.isEmpty();
        if (fatal != null) {
            throw fatal;
        }
    }

    /**
     * What consulting one close-aware resource after its {@code close()} returned produced.
     *
     * @param detail a diagnostic line when the shutdown is uncertain, or {@code null} when it is proven
     *               certain
     * @param fatal  an {@link Error} the report itself threw, to be rethrown once every resource has been
     *               released; an unreadable report must be neither swallowed nor mistaken for a clean one
     */
    private record UncertainOutcome(String detail, Error fatal) {

        private static final UncertainOutcome CERTAIN = new UncertainOutcome(null, null);
    }

    /**
     * Asks an owned resource whether the {@code close()} that just returned normally actually proved its
     * physical shutdown.
     *
     * <p>An ordinary {@link AutoCloseable} has no way to answer and is therefore trusted, so existing
     * callers are unaffected. A {@link CloseOutcomeAware} is asked whether it is uncertain and, only
     * then, for a value-free explanation. A report that cannot be read at all is <em>not</em> evidence of
     * a clean shutdown, so it is treated as uncertain as well: that keeps the fail-closed rule true even
     * for a hostile or broken implementation.
     */
    private static UncertainOutcome uncertainOutcomeOf(AutoCloseable resource) {
        if (!(resource instanceof CloseOutcomeAware aware)) {
            return UncertainOutcome.CERTAIN;
        }
        try {
            if (!aware.closedWithUncertainResources()) {
                return UncertainOutcome.CERTAIN;
            }
            return new UncertainOutcome(describeUncertain(resource, aware.uncertainCloseDetail()), null);
        } catch (Exception e) {
            return new UncertainOutcome(unreadableReport(resource, e.getClass().getName()), null);
        } catch (Error e) {
            return new UncertainOutcome(unreadableReport(resource, e.getClass().getName()), e);
        }
    }

    private static String describeUncertain(AutoCloseable resource, String detail) {
        if (detail == null || detail.isBlank()) {
            return resource.getClass().getSimpleName()
                    + ": close() returned normally but reported an uncertain shutdown";
        }
        return resource.getClass().getSimpleName() + ": " + detail;
    }

    private static String unreadableReport(AutoCloseable resource, String type) {
        return resource.getClass().getSimpleName() + ": close outcome could not be read (" + type + ")";
    }

    private static String describeFailure(AutoCloseable resource, String detail) {
        return resource.getClass().getSimpleName()
                + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }

    @Override
    public String toString() {
        return "RepositoryContext[repository=" + profile.id()
                + ", resources=" + resources.size()
                + ", closed=" + closed.get()
                + ", uncertain=" + closedWithUncertainResources + "]";
    }
}
