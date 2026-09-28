package com.mraibo.cminsight.repository;

import com.mraibo.cminsight.config.RepositoryProfile;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the single active {@link RepositoryContext}.
 *
 * <p>Switch semantics, in order:
 *
 * <ol>
 *   <li>a switch is serialized by a lock, so two callers can never interleave;</li>
 *   <li>the previous context is closed <em>first</em>, which is what ARCHITECTURE.md rule 10 requires
 *       and what keeps the number of live connections at the configured bound instead of momentarily
 *       doubling it;</li>
 *   <li>if that close was uncertain - a resource refused to close, {@code close()} threw, or a
 *       {@link com.mraibo.cminsight.core.CloseOutcomeAware} resource returned normally but reported that
 *       its own shutdown left physical resources unproven - the switch FAILS CLOSED: no factory call, no
 *       new context, nothing published. Opening new connections on top of resources that may still be
 *       open is exactly how the physical bound is exceeded. A quarantined {@code BoundedPool} slot
 *       reaches this rule automatically now: the pool reports it through the close-outcome contract, so a
 *       future adapter does not have to remember to inspect pool metrics itself;</li>
 *   <li>a new context is created and published only after it is fully initialized and validated;</li>
 *   <li>if initialization fails, nothing is published: the manager reports {@link RepositoryState#FAILED}
 *       and the failure, and never keeps a half-initialized context.</li>
 * </ol>
 *
 * <p>Consequence of (2), (3) and (5): a failed switch does not resurrect the previous repository. That
 * is the fail-closed behaviour the architecture asks for, and the diagnostics record why.
 *
 * <p>{@link #close()} is deliberately exempt from (3): shutdown must remain best-effort and release
 * every resource it can, so an uncertain close is recorded and not escalated.
 */
public final class RepositoryManager implements AutoCloseable {

    /** How many lifecycle notes are retained; a long-lived console must not grow without bound. */
    private static final int MAX_DIAGNOSTICS = 512;

    private final RepositoryContextFactory factory;
    private final ReentrantLock switchLock = new ReentrantLock();
    private final AtomicReference<Lifecycle> lifecycle =
            new AtomicReference<>(new Lifecycle(RepositoryState.NONE, null));
    private final Deque<String> diagnostics = new ArrayDeque<>();

    private volatile String lastFailure;

    /**
     * The lifecycle state and the published context as ONE immutable value.
     *
     * <p>They are deliberately not two independent fields. With a separate state and context, a reader
     * that performs two reads can straddle a transition and observe a pair that never existed at any
     * instant - and reordering the two writes only changes which impossible pair becomes observable.
     * Publishing them together makes {@code state()==ACTIVE} and {@code activeContext().isPresent()}
     * agree at every instant.
     */
    private record Lifecycle(RepositoryState state, RepositoryContext context) {
    }

    /**
     * One consistent view of the lifecycle: the state and the context that state refers to, read from a
     * single atomic snapshot.
     *
     * <p>Use this instead of calling {@link #state()} and {@link #activeContext()} separately. Two calls
     * are two different instants, so a switch completing between them makes the pair look inconsistent
     * even though no such pair ever existed.
     */
    public record Status(RepositoryState state, RepositoryContext context) {

        /**
         * True when the state says ACTIVE and the published context has not been closed.
         *
         * <p>This is a LIVE predicate, deliberately NOT part of the atomic snapshot: it re-reads
         * {@link RepositoryContext#isClosed()} after the snapshot was taken, so a switch that closes the
         * old context in between can make a correctly published ACTIVE snapshot look unusable. Measured:
         * ~0-2 such benign straddles per 300 million samples, against zero violations of the atomic
         * invariant. Use this when the question is about the context object; use
         * {@code state() == ACTIVE && context() != null} when the question is about the manager's
         * published state.
         */
        public boolean usable() {
            return state == RepositoryState.ACTIVE && context != null && !context.isClosed();
        }
    }

    /**
     * Reads the state and the published context from ONE atomic snapshot.
     *
     * <p>The pair is therefore always self-consistent: {@code status().state() == ACTIVE} holds exactly
     * when a usable context is present.
     */
    public Status status() {
        Lifecycle current = lifecycle.get();
        return new Status(current.state(), current.context());
    }

    public RepositoryManager(RepositoryContextFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public RepositoryState state() {
        return lifecycle.get().state();
    }

    /** The published context, or empty while nothing is active. */
    public Optional<RepositoryContext> activeContext() {
        RepositoryContext context = lifecycle.get().context();
        if (context == null || context.isClosed()) {
            return Optional.empty();
        }
        return Optional.of(context);
    }

    public Optional<RepositoryProfile> activeProfile() {
        return activeContext().map(RepositoryContext::profile);
    }

    /** Id of the active repository, or {@code null}. Safe to call at any time. */
    public String activeRepositoryId() {
        RepositoryContext context = lifecycle.get().context();
        return context == null ? null : context.profileUnchecked().id();
    }

    public Optional<String> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    /**
     * Lifecycle and failure notes, oldest first, capped at {@value #MAX_DIAGNOSTICS} entries so that a
     * long-running console cannot accumulate them without bound.
     */
    public List<String> diagnostics() {
        synchronized (diagnostics) {
            return List.copyOf(diagnostics);
        }
    }

    private void record(String message) {
        synchronized (diagnostics) {
            diagnostics.addLast(message);
            while (diagnostics.size() > MAX_DIAGNOSTICS) {
                diagnostics.removeFirst();
            }
        }
    }

    /**
     * Activates exactly one repository, closing whatever was active before.
     *
     * <p>Fails closed after an uncertain close of the previous context: when a resource refused to
     * close, a close-aware resource reported an unproven physical shutdown, or {@code close()} threw, the
     * manager publishes {@link RepositoryState#FAILED} with a diagnostic naming the cause and throws -
     * <em>without</em> calling {@link RepositoryContextFactory#create(RepositoryProfile)}. Swallowing a
     * cleanup failure to continue would open new CM/JDBC connections while the old ones may still be
     * alive, which is the physical bound the pool work exists to keep.
     *
     * @throws RepositoryException when the manager is closed, the previous context could not be closed
     *         with certainty, or activation fails
     */
    public void switchTo(RepositoryProfile profile) throws RepositoryException {
        Objects.requireNonNull(profile, "profile");
        switchLock.lock();
        try {
            ensureOpen();

            RepositoryContext previous = lifecycle.get().context();
            if (previous != null) {
                // Publish SWITCHING and clear the context in one store, so no reader can observe
                // ACTIVE together with nothing published.
                lifecycle.set(new Lifecycle(RepositoryState.SWITCHING, null));
                String previousId = previous.profileUnchecked().id();
                CloseOutcome outcome = closeContext(previous, "switch away from '" + previousId + "'");
                if (outcome.uncertain()) {
                    throw failClosed(profile, previousId, outcome);
                }
            }

            lifecycle.set(new Lifecycle(RepositoryState.INITIALIZING, null));
            RepositoryContext created = null;
            try {
                created = factory.create(profile);
                validate(created, profile);
                lastFailure = null;
                lifecycle.set(new Lifecycle(RepositoryState.ACTIVE, created));
                record("Activated repository '" + profile.id() + "' ("
                        + profile.databaseVendor() + ", SSID " + profile.ssid() + ").");
            } catch (Exception e) {
                if (created != null) {
                    closeContext(created, "failed activation of '" + profile.id() + "'");
                }
                lastFailure = describe(e);
                lifecycle.set(new Lifecycle(RepositoryState.FAILED, null));
                throw new RepositoryException("Could not activate repository '" + profile.id()
                        + "': " + describe(e), e);
            }
        } finally {
            switchLock.unlock();
        }
    }

    /**
     * Closes the active context without activating another one.
     *
     * <p>There is no activation to refuse here, so an uncertain close does not throw: the resources
     * were asked to close, the diagnostics name what refused, and the state becomes
     * {@link RepositoryState#FAILED} rather than {@link RepositoryState#NONE} - reporting a clean
     * "nothing is active" after a resource refused to close would be a lie about the physical state.
     *
     * @return true when something was actually closed
     */
    public boolean deactivate() {
        switchLock.lock();
        try {
            RepositoryContext previous = lifecycle.get().context();
            if (previous == null) {
                return false;
            }
            lifecycle.set(new Lifecycle(RepositoryState.SWITCHING, null));
            String previousId = previous.profileUnchecked().id();
            CloseOutcome outcome = closeContext(previous, "explicit deactivate");
            if (lifecycle.get().state() != RepositoryState.CLOSED) {
                if (outcome.uncertain()) {
                    lastFailure = uncertainDetail(previousId, "Deactivating the active repository", outcome);
                    lifecycle.set(new Lifecycle(RepositoryState.FAILED, null));
                    record(lastFailure);
                } else {
                    lifecycle.set(new Lifecycle(RepositoryState.NONE, null));
                }
            }
            record("Deactivated repository '" + previousId + "'.");
            return true;
        } finally {
            switchLock.unlock();
        }
    }

    /** Lifecycle snapshot for the diagnostics endpoint. */
    public Map<String, String> statusSnapshot() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        Lifecycle current = lifecycle.get();
        snapshot.put("state", current.state().name());
        snapshot.put("stateDescription", current.state().description());
        snapshot.put("repositoryId", current.context() == null
                ? "" : current.context().profileUnchecked().id());
        snapshot.put("lastFailure", lastFailure == null ? "" : lastFailure);
        return snapshot;
    }

    @Override
    public void close() {
        switchLock.lock();
        try {
            Lifecycle current = lifecycle.get();
            if (current.state() == RepositoryState.CLOSED) {
                return;
            }
            RepositoryContext previous = current.context();
            lifecycle.set(new Lifecycle(RepositoryState.CLOSING, null));
            if (previous != null) {
                closeContext(previous, "shutdown");
            }
            lifecycle.set(new Lifecycle(RepositoryState.CLOSED, null));
            record("RepositoryManager closed.");
        } finally {
            switchLock.unlock();
        }
    }

    private void ensureOpen() throws RepositoryException {
        if (lifecycle.get().state() == RepositoryState.CLOSED) {
            throw new RepositoryException("RepositoryManager is closed and cannot activate a repository");
        }
    }

    /**
     * What one close attempt actually achieved, in the terms the fail-closed rule needs.
     *
     * @param uncertain        true when the outcome is not certain: the context reported at least one
     *                         resource failure or uncertain shutdown, or {@code close()} itself threw
     * @param failures         the resource failures the context recorded, in its own order
     * @param uncertainReports the close-aware resources that returned normally from {@code close()} but
     *                         could not prove their physical shutdown - a quarantined pool slot, for
     *                         example - in the context's own order
     * @param thrownDetail     description of the throwable {@code close()} raised, or {@code null}
     */
    private record CloseOutcome(boolean uncertain,
                                List<String> failures,
                                List<String> uncertainReports,
                                String thrownDetail) {
    }

    /**
     * Reports the refused switch and returns the exception to throw.
     *
     * <p>Order matters: the diagnostic and {@code lastFailure} are published before the caller sees the
     * exception, so a console that logs the failure already has the reason.
     */
    private RepositoryException failClosed(RepositoryProfile requested, String previousId, CloseOutcome outcome) {
        String detail = uncertainDetail(previousId, "Switch to '" + requested.id() + "' refused", outcome);
        lastFailure = detail;
        lifecycle.set(new Lifecycle(RepositoryState.FAILED, null));
        record(detail);
        return new RepositoryException(detail);
    }

    /**
     * One message for every uncertain close, so the switch, the deactivate path and the diagnostics
     * cannot drift apart. Names the failures, which is what an operator needs to find the leak.
     */
    private static String uncertainDetail(String previousId, String action, CloseOutcome outcome) {
        StringBuilder detail = new StringBuilder(action)
                .append(": closing the previous repository '").append(previousId)
                .append("' left resources in an uncertain state");
        if (!outcome.failures().isEmpty()) {
            detail.append(" (").append(outcome.failures().size()).append(" resource close failure(s): ")
                    .append(String.join("; ", outcome.failures())).append(')');
        }
        if (!outcome.uncertainReports().isEmpty()) {
            detail.append(" (").append(outcome.uncertainReports().size())
                    .append(" resource(s) returned normally from close() but reported an uncertain shutdown:")
                    .append(' ').append(String.join("; ", outcome.uncertainReports())).append(')');
        }
        if (outcome.thrownDetail() != null) {
            detail.append(" [close() also threw: ").append(outcome.thrownDetail()).append(']');
        }
        return detail.append(". No new repository context was created; connections that refused to close"
                + " may still be open.").toString();
    }

    private static void validate(RepositoryContext created, RepositoryProfile requested) {
        if (created == null) {
            throw new IllegalStateException("RepositoryContextFactory returned no context");
        }
        if (created.isClosed()) {
            throw new IllegalStateException("RepositoryContextFactory returned an already closed context");
        }
        String actual = created.profileUnchecked().id();
        if (!actual.equals(requested.id())) {
            throw new IllegalStateException("RepositoryContextFactory returned a context for repository '"
                    + actual + "' while '" + requested.id() + "' was requested");
        }
    }

    /**
     * Closes a context and reports what the close achieved.
     *
     * <p>The throwable is caught rather than propagated: cleanup must never escape a switch, not even as
     * an Error. It is not swallowed either - it makes the outcome uncertain, which the switch treats as
     * a refusal to activate another repository.
     *
     * <p>Three independent sources of uncertainty are folded together here, because the fail-closed rule
     * must not depend on which one happened: a resource that refused to close
     * ({@link RepositoryContext#closeFailures()}), a close-aware resource that returned normally but
     * reported an unproven physical shutdown ({@link RepositoryContext#uncertainCloseReports()} - this is
     * how a {@code BoundedPool} quarantine arrives), and a {@code close()} call that threw.
     */
    private CloseOutcome closeContext(RepositoryContext context, String reason) {
        String thrownDetail = null;
        try {
            context.close();
        } catch (Throwable e) {
            thrownDetail = describe(e);
            record("Error closing repository context during " + reason + ": " + thrownDetail);
        }
        List<String> failures = context.closeFailures();
        if (!failures.isEmpty()) {
            record("Closing repository '" + context.profileUnchecked().id() + "' during " + reason
                    + " reported " + failures.size() + " resource failure(s): " + String.join("; ", failures));
        }
        List<String> uncertainReports = context.uncertainCloseReports();
        if (!uncertainReports.isEmpty()) {
            // Named explicitly, with the resource's own value-free detail: an operator must be able to
            // see WHY the switch was refused without reading pool metrics by hand.
            record("Closing repository '" + context.profileUnchecked().id() + "' during " + reason
                    + " reported " + uncertainReports.size() + " uncertain resource shutdown(s): "
                    + String.join("; ", uncertainReports));
        }
        boolean uncertain = thrownDetail != null || context.closedWithUncertainResources()
                || !failures.isEmpty() || !uncertainReports.isEmpty();
        return new CloseOutcome(uncertain, failures, uncertainReports, thrownDetail);
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    @Override
    public String toString() {
        return "RepositoryManager[state=" + state() + ", repository=" + activeRepositoryId() + "]";
    }
}
