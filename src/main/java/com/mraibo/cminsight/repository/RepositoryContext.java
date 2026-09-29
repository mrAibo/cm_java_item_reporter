package com.mraibo.cminsight.repository;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.core.CloseOutcomeAware;
import com.mraibo.cminsight.core.CloseState;
import com.mraibo.cminsight.core.CloseStateAware;
import com.mraibo.cminsight.core.CmPoolDiagnostics;
import com.mraibo.cminsight.core.RepositoryServices;
import com.mraibo.cminsight.metadata.MetadataRepository;
import com.mraibo.cminsight.retention.RetentionRepository;
import com.mraibo.cminsight.statistics.StatisticsDiagnostics;
import com.mraibo.cminsight.statistics.StatisticsRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * <h2>The close outcome is derived, never frozen</h2>
 *
 * {@link #close()} returns as soon as each owned resource's {@code close()} has returned - and for a
 * pool that is not the end of the story, because a resource that is still out on a lease is closed
 * later, by the thread that returns it. A context that snapshotted its outcome at the end of the first
 * {@code close()} call would therefore publish a permanent "clean" verdict about a lease that had not
 * even been closed yet, and {@link RepositoryManager} would create the next repository's connections on
 * the strength of it.
 *
 * <p>So the state is instead <em>derived on every read</em> from the owned close-aware resources plus
 * the facts this context recorded itself. Consequences, all deliberate:
 *
 * <ul>
 *   <li>an owned pool that is still draining reports {@link CloseState#CLOSING}, and this context
 *       reports it too - pending, not clean and not uncertain;</li>
 *   <li>a late lease that comes back quarantined moves the context to
 *       {@link CloseState#CLOSED_UNCERTAIN}, even though that happened long after {@code close()}
 *       returned;</li>
 *   <li>a late lease that comes back clean lets the context reach {@link CloseState#CLOSED_CLEAN}
 *       afterwards - a switch that was correctly refused earlier is allowed later, without anybody
 *       having to re-close anything;</li>
 *   <li>uncertainty is a LATCH, not merely a derived value: uncertainty seen by ANY read - recorded when
 *       {@code close()} ran, reported by an owned resource now, or observed at any point in between - is
 *       permanent, and no later clean answer can revoke it. This is deliberately stronger than trusting
 *       each resource to be monotone: a resource whose state transiently reads uncertain and then clean
 *       would otherwise be refused on one switch attempt and allowed through on the next, which is a
 *       fail-open window across retries. {@code BoundedPool}'s quarantine is already monotone, so this
 *       only ever makes the repository layer more conservative than the pool it owns.</li>
 * </ul>
 *
 * <p>The derivation is monotone in the safe direction - {@code NOT_CLOSED -> CLOSING ->
 * CLOSED_CLEAN} may each be followed by {@code CLOSED_UNCERTAIN}, and {@code CLOSED_UNCERTAIN} is
 * final. Every query is read-only with respect to the LIFECYCLE: it never closes, waits, retries, or
 * changes what will be released. It does latch uncertainty, which is the one deliberate exception, and
 * it is monotone and idempotent by construction.
 *
 * <p>This class is itself {@link CloseOutcomeAware} and {@link CloseStateAware}: a container that owns a
 * context (or an adapter that does) can ask the same questions of it and get the same vocabulary.
 */
public final class RepositoryContext implements CloseOutcomeAware, CloseStateAware {

    private final RepositoryProfile profile;
    private final List<AutoCloseable> resources;
    private final RepositoryServices services;
    private final Instant createdAt = Instant.now();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private volatile List<String> closeFailures = List.of();
    private volatile List<String> uncertainCloseReports = List.of();
    /**
     * The owned resources whose {@code close()} reported uncertainty, in close order.
     *
     * <p>Kept as resource REFERENCES, not only as text, so {@link #uncertainCloseReports()} can ask them
     * again later: a pool slot that becomes quarantined after {@code close()} returned is exactly the case
     * Goal 01C is about, and the explanation must follow the state.
     */
    private volatile List<AutoCloseable> uncertainAtClose = List.of();
    /**
     * Latched uncertainty that no later read may revoke: set when a resource failed to close, when a
     * report could not be read at all, or when a resource was ever observed to report an uncertain
     * shutdown. Volatile rather than lock-guarded because {@link #closeState()} must stay a cheap,
     * lock-free, side-effect-free read on a hot switch path.
     */
    private volatile boolean uncertainLatched;

    public RepositoryContext(RepositoryProfile profile) {
        this(profile, List.of(), RepositoryServices.NONE);
    }

    public RepositoryContext(RepositoryProfile profile, List<? extends AutoCloseable> resources) {
        this(profile, resources, RepositoryServices.NONE);
    }

    /**
     * A context that owns the given resources and exposes the given read services.
     *
     * <p>The two are deliberately independent. {@code resources} is what {@link #close()} releases, in
     * reverse order, and it is what {@link #closeState()} derives the shutdown answer from. {@code
     * services} is what modules read. A service that owns a closeable - the metadata cache does - is
     * passed in BOTH lists: once as a service so it can be read, and once as a resource so its lifetime
     * is tied to the context instead of leaking with it.
     */
    public RepositoryContext(RepositoryProfile profile,
                             List<? extends AutoCloseable> resources,
                             RepositoryServices services) {
        this.profile = Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(services, "services");
        List<AutoCloseable> copy = new ArrayList<>(resources.size());
        for (AutoCloseable resource : resources) {
            copy.add(Objects.requireNonNull(resource, "resource"));
        }
        this.resources = Collections.unmodifiableList(copy);
        this.services = services;
    }

    /**
     * The profile of this context.
     *
     * @throws IllegalStateException when the context is already closed
     */
    public RepositoryProfile profile() {
        if (closeStarted.get()) {
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

    /**
     * True once {@link #close()} has been called.
     *
     * <p>Deliberately says nothing about the PHYSICAL resources: a pool that is still waiting for a lease
     * to come back is closed to new borrows while a session it owns is still alive. Ask
     * {@link #closeState()} for that question - this flag answers only "has shutdown begun".
     */
    public boolean isClosed() {
        return closeStarted.get();
    }

    /** Owned resources, in acquisition order. Exposed for diagnostics only. */
    public List<AutoCloseable> resources() {
        return resources;
    }

    /**
     * Every read service this context owns, whether or not each one is available.
     *
     * <p>Safe after {@link #close()}: unlike {@link #profile()}, this does not throw for a closed
     * context, because a diagnostics page has to be able to describe a repository that is shutting down.
     * The services themselves are expected to refuse work once their pool is closed, and that refusal is
     * what a caller reports - not a hidden exception from the accessor.
     */
    public RepositoryServices services() {
        return services;
    }

    /** The read-only ItemType metadata service, or empty when this context has none. */
    public Optional<MetadataRepository> metadata() {
        return services.metadataService();
    }

    /** The read-only retention viewer service, or empty when this context has none. */
    public Optional<RetentionRepository> retention() {
        return services.retentionService();
    }

    /**
     * CM pool and adapter diagnostics, or empty when this context has no CM pool.
     *
     * <p>Present in core-only mode too, where there is no CM pool at all: a repository may be listed
     * without an adapter, and the honest answer there is "no pool", not a pool reporting zeros.
     */
    public Optional<CmPoolDiagnostics> cmPool() {
        return services.cmDiagnosticsService();
    }

    /**
     * The analytics read/refresh service, or empty when this repository has no analytics capability.
     *
     * <p>Goal 03's typed accessor, in the same shape as {@link #metadata()} and {@link #retention()} - the
     * project forbids a {@code Map<String,Object>} service locator, so a new capability gets a declared,
     * compile-checked accessor rather than a string key.
     *
     * <p>Empty is a normal state, not a broken activation: {@code feature.statistics=false}, an absent
     * DB2/Oracle driver, a missing JDBC credential and an unreachable database all leave the IBM CM
     * metadata and retention halves fully usable, and JDBC is never contacted while a repository is being
     * activated. Safe after {@link #close()} as well, so a diagnostics page can describe a repository that
     * is shutting down.
     */
    public Optional<StatisticsRepository> statistics() {
        return services.statisticsRepository();
    }

    /**
     * The analytics JDBC pool and scan facts as value-only numbers, or empty when this context has no
     * analytics capability to describe.
     *
     * <p>The mirror of {@link #cmPool()} for the JDBC half, with one deliberate difference: it returns a
     * plain record rather than an object holding a pool, because the goal forbids the web layer from
     * receiving a {@code Connection}, {@code Statement}, {@code ResultSet} or driver object - and a
     * diagnostics view that cannot hold a pool cannot leak one. No JDBC URL, user name, schema or raw
     * driver message exists in the returned type.
     */
    public Optional<StatisticsDiagnostics> jdbcPool() {
        return statistics().map(StatisticsRepository::diagnostics);
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
     *
     * <p>Like {@link #closeState()}, this is derived from the owned resources on every read rather than
     * frozen when {@code close()} returned. That is what keeps the explanation attached to the state: a
     * pool slot that only becomes quarantined when a late lease comes back would otherwise be reported as
     * {@code CLOSED_UNCERTAIN} with no reason text at all, and an operator would see "unproven" with no
     * cause. Reports for resources that were already accounted for during {@code close()} are not
     * duplicated - each owned resource contributes at most one line.
     */
    public List<String> uncertainCloseReports() {
        if (!closeStarted.get()) {
            return uncertainCloseReports;
        }
        // Ask the owned resources again whenever the outcome is not proven clean. This covers the case
        // Goal 01C exists for: a pool that was still draining when close() ran and only became
        // quarantined when a late lease came back. Its stored report is empty by then, and reporting
        // CLOSED_UNCERTAIN with no reason at all would send an operator looking for nothing.
        if (!uncertainLatched && closeState() != CloseState.CLOSED_UNCERTAIN) {
            return uncertainCloseReports;
        }
        List<String> current = collectUncertainReports(uncertainAtClose);
        if (current.isEmpty()) {
            return uncertainCloseReports;
        }
        List<String> merged = new ArrayList<>(uncertainCloseReports.size() + current.size());
        for (String report : uncertainCloseReports) {
            if (!merged.contains(report)) {
                merged.add(report);
            }
        }
        for (String report : current) {
            if (!merged.contains(report)) {
                merged.add(report);
            }
        }
        return List.copyOf(merged);
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
     * <p>False before {@code close()} runs, false after a fully successful close, and false while an
     * owned resource is merely still draining - draining is unfinished, not uncertain. An {@link Error}
     * thrown by a resource is recorded as a failure too, so the flag is visible even when {@code close()}
     * rethrows.
     *
     * <p>Derived from {@link #closeState()} rather than stored, so a quarantine that happens AFTER
     * {@code close()} returned (a lease coming back late to a pool this context owns) is still reported
     * here instead of leaving a stale clean answer behind.
     */
    @Override
    public boolean closedWithUncertainResources() {
        return closeState() == CloseState.CLOSED_UNCERTAIN;
    }

    /**
     * The shutdown state of this context, derived from its owned resources on every read.
     *
     * <p>The precedence is fixed and conservative:
     *
     * <ol>
     *   <li>uncertainty - recorded or currently reported by an owned resource - wins over everything,
     *       because it is terminal and because a physically outstanding resource must never be described
     *       as anything milder;</li>
     *   <li>then pending: close has begun and at least one owned close-aware resource has not finished,
     *       so a physical resource is still outstanding. This is the state that used to be reported as
     *       clean, and reporting it as clean is exactly the defect Goal 01C closes;</li>
     *   <li>then {@link CloseState#CLOSED_CLEAN}, only once close has been requested and nothing owned
     *       answers otherwise;</li>
     *   <li>otherwise {@link CloseState#NOT_CLOSED}.</li>
     * </ol>
     *
     * <p>An owned resource is only asked once its own {@code close()} has returned. Asking earlier would
     * be meaningless - an open pool answers {@code NOT_CLOSED} - and could misread a container that is
     * still being used.
     */
    @Override
    public CloseState closeState() {
        if (!closeStarted.get()) {
            return CloseState.NOT_CLOSED;
        }
        boolean pending = false;
        for (int i = resources.size() - 1; i >= 0; i--) {
            CloseState state = ownedState(resources.get(i));
            if (state == CloseState.CLOSED_UNCERTAIN) {
                // Latch it. Uncertainty observed on ANY read is permanent, not merely the uncertainty
                // recorded when close() ran: otherwise a resource that reported CLOSED_UNCERTAIN once and
                // CLOSED_CLEAN afterwards - a transient report from a future adapter whose closeState() is
                // not monotone, or a hostile one - would be refused on one switch attempt and then allowed
                // through on the next, which is a fail-open window across retries. The manager's fail-closed
                // rule must not depend on every implementation being monotone by itself.
                uncertainLatched = true;
                return CloseState.CLOSED_UNCERTAIN;
            }
            if (state == CloseState.CLOSING || state == CloseState.NOT_CLOSED) {
                // NOT_CLOSED after our close() returned can only mean the resource's own shutdown is not
                // finished, so the physical resource may still exist. Pending, never clean.
                pending = true;
            }
        }
        if (uncertainLatched) {
            return CloseState.CLOSED_UNCERTAIN;
        }
        return pending ? CloseState.CLOSING : CloseState.CLOSED_CLEAN;
    }

    /** True when close was requested and an owned physical resource is still outstanding. */
    public boolean isDraining() {
        return closeState() == CloseState.CLOSING;
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
        // The DERIVED reports, not the stored field: a quarantine that only appeared after close()
        // returned has no stored report, and this detail is the human-readable side of closeState(). It
        // must therefore describe the same instant the state does.
        List<String> reports = uncertainCloseReports();
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
        if (!closeStarted.compareAndSet(false, true)) {
            // Idempotent, and deliberately does NOT re-probe or re-close anything: a resource still
            // draining closure is drained by whoever owns it (the pool closes a returned lease itself),
            // and a second close() must never resurrect the uncertainty question. The DERIVED state is
            // what makes a later answer possible, so a no-op here loses nothing.
            return;
        }
        List<String> failures = new ArrayList<>(0);
        List<String> uncertain = new ArrayList<>(0);
        List<AutoCloseable> uncertainResources = new ArrayList<>(0);
        Error fatal = null;
        for (int i = resources.size() - 1; i >= 0; i--) {
            AutoCloseable resource = resources.get(i);
            try {
                resource.close();
                // A normal return is NOT the same statement as "proven closed". A close-aware container
                // (BoundedPool is the first) returns normally while a member it could not close stays
                // quarantined - or while a lease it handed out is still alive - so its own report has to
                // be consulted here. Without this, either case would look like a clean shutdown to the
                // switch rule.
                UncertainOutcome outcome = uncertainOutcomeOf(resource);
                if (outcome.detail() != null) {
                    uncertain.add(outcome.detail());
                    uncertainResources.add(resource);
                    // Latched here as well as recorded: the state must be able to answer
                    // CLOSED_UNCERTAIN even for a resource that stops reporting it later, and even
                    // while a DIFFERENT owned resource is still draining.
                    uncertainLatched = true;
                }
                if (outcome.fatal() != null && fatal == null) {
                    fatal = outcome.fatal();
                }
            } catch (Exception e) {
                failures.add(describeFailure(resource, e.getMessage()));
                uncertainLatched = true;
            } catch (Error e) {
                // Record it, keep releasing the rest, and rethrow afterwards: one hostile resource
                // must not leave the others open, but an Error is still reported to the caller.
                failures.add(describeFailure(resource, e.getClass().getName()));
                uncertainLatched = true;
                if (fatal == null) {
                    fatal = e;
                }
            }
        }
        this.closeFailures = List.copyOf(failures);
        this.uncertainCloseReports = List.copyOf(uncertain);
        this.uncertainAtClose = List.copyOf(uncertainResources);
        // No final "clean" verdict is published here on purpose. closeState() derives the answer from
        // the latch and from what the owned resources report NOW, so this context can move from
        // CLOSING to CLOSED_CLEAN when a late lease returns cleanly, and from CLOSING or
        // CLOSED_CLEAN to CLOSED_UNCERTAIN when one comes back quarantined. Writing a snapshot here
        // is precisely the stale-clean defect Goal 01C removes.
        if (fatal != null) {
            throw fatal;
        }
    }

    /**
     * Asks every owned resource that is currently uncertain for its value-free explanation, skipping the
     * ones already accounted for when {@code close()} ran.
     *
     * <p>Pure: it records nothing and changes no state. Package-visible for the tests that pin the
     * derivation.
     */
    private List<String> collectUncertainReports(List<AutoCloseable> alreadyAccountedFor) {
        List<String> collected = new ArrayList<>(0);
        for (AutoCloseable resource : resources) {
            if (alreadyAccountedFor.contains(resource)) {
                continue;
            }
            if (resource instanceof CloseOutcomeAware aware) {
                try {
                    if (aware.closedWithUncertainResources()) {
                        collected.add(describeUncertain(resource, aware.uncertainCloseDetail()));
                    }
                } catch (Exception e) {
                    collected.add(unreadableReport(resource, e.getClass().getName()));
                } catch (Error e) {
                    collected.add(unreadableReport(resource, e.getClass().getName()));
                }
            }
        }
        return collected;
    }

    /**
     * The state of one owned resource, as far as it can be observed.
     *
     * <p>An ordinary {@link AutoCloseable} cannot answer and is trusted, exactly as before: it is
     * treated as clean once its own {@code close()} returned. A {@link CloseStateAware} is asked
     * directly. A {@link CloseOutcomeAware} that cannot describe its state is mapped from the boolean
     * question it does answer, so Goal 01B's implementations keep working: uncertain stays uncertain,
     * and "not uncertain" is taken as finished.
     *
     * <p>A report that cannot be read at all is NOT evidence of a clean shutdown, so it is treated as
     * uncertain, which also latches. Reporting is done separately by
     * {@link #uncertainOutcomeOf(AutoCloseable)}; this method is the pure state projection and never
     * records anything.
     */
    private static CloseState ownedState(AutoCloseable resource) {
        if (resource instanceof CloseStateAware aware) {
            try {
                CloseState state = aware.closeState();
                return state == null ? CloseState.CLOSED_UNCERTAIN : state;
            } catch (Exception e) {
                return CloseState.CLOSED_UNCERTAIN;
            } catch (Error e) {
                return CloseState.CLOSED_UNCERTAIN;
            }
        }
        if (resource instanceof CloseOutcomeAware aware) {
            try {
                return aware.closedWithUncertainResources()
                        ? CloseState.CLOSED_UNCERTAIN
                        : CloseState.CLOSED_CLEAN;
            } catch (Exception e) {
                return CloseState.CLOSED_UNCERTAIN;
            } catch (Error e) {
                return CloseState.CLOSED_UNCERTAIN;
            }
        }
        return CloseState.CLOSED_CLEAN;
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
                + ", closed=" + closeStarted.get()
                + ", closeState=" + closeState() + "]";
    }
}
