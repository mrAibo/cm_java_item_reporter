package com.mraibo.cminsight.repository;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.core.CloseState;

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
 *   <li>a still-closing previous context from an earlier failed switch is resolved FIRST - see
 *       {@link #closingContext()} - and while it is not terminal-clean the switch is refused without
 *       ever calling the factory;</li>
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
 *   <li>if the close merely left PHYSICAL RESOURCES OUTSTANDING - a pool still draining a lease that a
 *       caller has not returned - the switch is refused as well, as {@link Refusal#PENDING}. Nothing is
 *       uncertain yet, but the previous repository still demonstrably owns a live connection, so the
 *       next repository must not open one;</li>
 *   <li>a new context is created and published only after it is fully initialized and validated;</li>
 *   <li>if initialization fails, nothing is published: the manager reports {@link RepositoryState#FAILED}
 *       and the failure, and never keeps a half-initialized context.</li>
 * </ol>
 *
 * <h2>A refused switch is not forgotten</h2>
 *
 * <p>Refusing once is not enough, because a refusal must survive the retry that follows it. The previous
 * context is therefore retained on {@link #closingContext()} until it is proven terminal-clean - the
 * manager keeps a reference to a context it has unpublished, precisely so it cannot lose track of the
 * repository it is still tearing down. A retry re-checks that retained context instead of starting from
 * {@code previous == null}:
 *
 * <ul>
 *   <li>still {@link CloseState#CLOSING} - the retry is refused again, and the factory is still never
 *       called;</li>
 *   <li>{@link CloseState#CLOSED_UNCERTAIN} - the retry is refused again, permanently (see
 *       {@link #refusal()});</li>
 *   <li>{@link CloseState#CLOSED_CLEAN} - every physical resource of the old repository is proven gone,
 *       so the reference is dropped and the switch may proceed.</li>
 * </ul>
 *
 * <p>The retention is one context, not a list: a switch is serialized, so at most one previous context
 * can be unproven at a time, and clearing it the moment it is terminal-clean means the manager cannot
 * accumulate state across hundreds of switches.
 *
 * <p>Consequence of (3) to (7): a failed switch does not resurrect the previous repository. That is the
 * fail-closed behaviour the architecture asks for, and the diagnostics record why.
 *
 * <p>{@link #close()} is deliberately exempt from the refusal rules: shutdown must remain best-effort
 * and release every resource it can, so an uncertain or still-draining close is recorded and not
 * escalated. Nothing is activated after it in any case.
 */
public final class RepositoryManager implements AutoCloseable {

    /** How many lifecycle notes are retained; a long-lived console must not grow without bound. */
    private static final int MAX_DIAGNOSTICS = 512;

    private final RepositoryContextFactory factory;
    private final ReentrantLock switchLock = new ReentrantLock();
    private final AtomicReference<Lifecycle> lifecycle =
            new AtomicReference<>(new Lifecycle(RepositoryState.NONE, null));
    private final Deque<String> diagnostics = new ArrayDeque<>();

    /**
     * A repository context that is being torn down but is not proven gone.
     *
     * <p>Guarded by {@link #switchLock}. It holds a context the manager has already UNPUBLISHED - from
     * the published slot, so no other thread can reach it any more - which is the whole point: without
     * this reference a refused switch would drop its only handle on the previous repository, and the
     * retry would happily create the next one while the old pool was still draining a lease.
     *
     * <p>Invariant: while this is non-null, {@code switchTo} never calls the factory.
     */
    private RepositoryContext unpublishedClosing;

    private volatile String lastFailure;

    /**
     * Why the last switch was refused, or {@code null} when it was not refused.
     *
     * <p>{@link Refusal#PENDING} is recoverable - the outstanding resource may come back and the same
     * switch may later succeed - while {@link Refusal#UNCERTAIN} is permanent until the operator
     * replaces the process, because a quarantine can never be proven gone from inside it. Keeping the two
     * apart is what lets a caller or an operator tell "wait" from "give up", which the plain exception
     * message cannot express.
     */
    public enum Refusal {

        /** Close left resources whose physical shutdown is unproven; this never clears by itself. */
        UNCERTAIN("a resource may still be open after close"),

        /** Close left physical resources outstanding; a later attempt may succeed once they are returned. */
        PENDING("a physical resource of the previous repository is still outstanding"),

        /** The manager itself is closed. */
        CLOSED("the manager is closed"),

        /** The new context could not be created or validated. */
        ACTIVATION_FAILED("the new repository context could not be activated");

        private final String description;

        Refusal(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private volatile Refusal refusal;

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

    /** Why the last switch was refused, or empty when the last switch was not refused. */
    public Optional<Refusal> refusal() {
        return Optional.ofNullable(refusal);
    }

    /**
     * The context of a repository that is being torn down but is not proven terminal-clean, or empty
     * when the previous repository is fully released.
     *
     * <p>This is the manager's memory of a shutdown it has started and could not finish. While it is
     * present, {@link #switchTo(RepositoryProfile)} refuses to create a new context - on the first
     * attempt and on every retry - because the previous repository may still own a live physical
     * resource. It is the state a diagnostics endpoint or an operator should show for "switch is
     * blocked", and it is deliberately reachable even though the context is no longer published as
     * active.
     */
    public Optional<RepositoryContext> closingContext() {
        // Reading it needs the lock only to avoid a torn read of the field itself; the context's own
        // state query happens outside so a slow probe can never block a switch.
        RepositoryContext context;
        switchLock.lock();
        try {
            context = unpublishedClosing;
        } finally {
            switchLock.unlock();
        }
        return Optional.ofNullable(context);
    }

    /** The close state of the retained previous context, or empty when there is none. */
    public Optional<CloseState> closingState() {
        return closingContext().map(RepositoryManager::closeStateOf);
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
     * <p>Fails closed just as firmly when the previous close left physical resources merely OUTSTANDING:
     * a pool still draining a lease owns a live connection, so the new repository's connections must wait
     * for it. That refusal is repeatable - every later attempt re-checks the retained context and refuses
     * again until it is proven terminal-clean.
     *
     * @throws RepositoryException when the manager is closed, the previous context is not proven released
     *         with certainty, or activation fails
     */
    public void switchTo(RepositoryProfile profile) throws RepositoryException {
        Objects.requireNonNull(profile, "profile");
        switchLock.lock();
        try {
            ensureOpen();
            resolveUnpublishedClosing(profile);

            RepositoryContext previous = lifecycle.get().context();
            if (previous != null) {
                // Publish SWITCHING and clear the context in one store, so no reader can observe
                // ACTIVE together with nothing published. The context is retained on the way out: from
                // here on it is the manager's only handle on the repository it is tearing down.
                lifecycle.set(new Lifecycle(RepositoryState.SWITCHING, null));
                String previousId = previous.profileUnchecked().id();
                unpublishedClosing = previous;
                CloseOutcome outcome = closeContext(previous, "switch away from '" + previousId + "'");
                CloseState state = closeStateOf(previous);
                if (state == CloseState.CLOSED_UNCERTAIN || outcome.uncertain()) {
                    throw failClosed(profile, previousId, Refusal.UNCERTAIN, outcome);
                }
                if (state != CloseState.CLOSED_CLEAN) {
                    throw failClosed(profile, previousId, Refusal.PENDING, outcome);
                }
                // Proven released. Clearing the reference is what makes a later switch able to proceed -
                // the same release the retry path performs.
                releaseLatch(previous);
            }

            refusal = null;
            lifecycle.set(new Lifecycle(RepositoryState.INITIALIZING, null));
            RepositoryContext created = null;
            try {
                created = factory.create(profile);
                validate(created, profile);
                lastFailure = null;
                lifecycle.set(new Lifecycle(RepositoryState.ACTIVE, created));
                record("Activated repository '" + profile.id() + "' ("
                        + profile.databaseVendor() + ", SSID " + profile.ssid() + ").");
            } catch (Throwable e) {
                // Throwable, not Exception: an Error from the factory must not leave the manager stuck in
                // INITIALIZING with no recorded failure and a half-built context published nowhere. It is
                // recorded, the state becomes FAILED, and it still reaches the caller.
                if (created != null) {
                    // The context is unpublished and will never be activated: retain then close it, so a
                    // still-draining resource in a failed activation is refused by the same rule as a
                    // switch, instead of vanishing with the exception.
                    unpublishedClosing = created;
                    CloseOutcome outcome = closeContext(created, "failed activation of '" + profile.id() + "'");
                    maybeReleaseLatch(created, outcome);
                }
                lastFailure = describe(e);
                refusal = Refusal.ACTIVATION_FAILED;
                lifecycle.set(new Lifecycle(RepositoryState.FAILED, null));
                if (e instanceof Error error) {
                    throw error;
                }
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
     * The same applies to a close that leaves resources merely outstanding: {@code NONE} claims nothing
     * is active, so it may only be published once the context is terminal-clean.
     *
     * @return true when something was actually closed
     */
    public boolean deactivate() {
        switchLock.lock();
        try {
            if (lifecycle.get().state() == RepositoryState.CLOSED) {
                // A closed manager owns nothing to deactivate: close() already released, or recorded, the
                // context, and reporting "something was deactivated" here would be false.
                return false;
            }
            RepositoryContext active = lifecycle.get().context();
            if (active == null) {
                // A previous switch may have left a context it could not finish closing. Sweep it up
                // rather than reporting "nothing is active" over a live resource.
                RepositoryContext retained = unpublishedClosing;
                if (retained == null) {
                    return false;
                }
                lifecycle.set(new Lifecycle(RepositoryState.CLOSING, null));
                CloseOutcome outcome = closeContext(retained, "explicit deactivate of a pending context");
                reportDeactivateOutcome(retained, outcome);
                return true;
            }

            lifecycle.set(new Lifecycle(RepositoryState.SWITCHING, null));
            String previousId = active.profileUnchecked().id();
            unpublishedClosing = active;
            CloseOutcome outcome = closeContext(active, "explicit deactivate");
            if (lifecycle.get().state() != RepositoryState.CLOSED) {
                publishDeactivateState(active, previousId, outcome);
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
        Lifecycle current;
        RepositoryContext closing;
        // The published lifecycle and the retained latch are read in ONE lock section, so the snapshot
        // cannot pair a repositoryId from one instant with a closingRepositoryId from another. The close
        // state itself is still derived outside the lock, because reading it calls into owned resources.
        switchLock.lock();
        try {
            current = lifecycle.get();
            closing = unpublishedClosing;
        } finally {
            switchLock.unlock();
        }
        snapshot.put("state", current.state().name());
        snapshot.put("stateDescription", current.state().description());
        snapshot.put("repositoryId", current.context() == null
                ? "" : current.context().profileUnchecked().id());
        snapshot.put("lastFailure", lastFailure == null ? "" : lastFailure);
        snapshot.put("refusal", refusal == null ? "" : refusal.name());
        snapshot.put("closingRepositoryId", closing == null ? "" : closing.profileUnchecked().id());
        snapshot.put("closingState", closing == null ? "" : closeStateOf(closing).name());
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
                unpublishedClosing = previous;
                CloseOutcome outcome = closeContext(previous, "shutdown");
                // Best-effort by design: shutdown releases what it can and records the rest. It never
                // escalates and never waits, because there is nothing left to activate afterwards.
                recordOutstandingOnShutdown(previous, outcome);
            }
            RepositoryContext retained = unpublishedClosing;
            if (retained != null && retained != previous) {
                CloseOutcome outcome = closeContext(retained, "shutdown of a pending context");
                recordOutstandingOnShutdown(retained, outcome);
            }
            lifecycle.set(new Lifecycle(RepositoryState.CLOSED, null));
            record("RepositoryManager closed.");
        } finally {
            switchLock.unlock();
        }
    }

    // ---------------------------------------------------------------- internals

    /** Records, value-free, that shutdown left a repository's physical resources outstanding. */
    private void recordOutstandingOnShutdown(RepositoryContext context, CloseOutcome outcome) {
        CloseState state = closeStateOf(context);
        if (outcome.uncertain() || state == CloseState.CLOSED_CLEAN) {
            // Either the close was already reported as uncertain by closeContext(), or it was clean and
            // there is nothing outstanding to record.
            return;
        }
        record("Shutdown left physical resources of repository '" + context.profileUnchecked().id()
                + "' outstanding (" + state + "); they are closed by whoever returns them.");
    }

    private void ensureOpen() throws RepositoryException {
        if (lifecycle.get().state() == RepositoryState.CLOSED) {
            refusal = Refusal.CLOSED;
            throw new RepositoryException("RepositoryManager is closed and cannot activate a repository");
        }
    }

    /**
     * Deals with the context an earlier switch could not finish closing, BEFORE anything is created.
     *
     * <p>This is the latch that makes the fail-closed rule survive a retry. It is checked on every
     * switch, not only the first, which is exactly the difference between "refused once" and "refused
     * until the previous repository is provably gone".
     *
     * <h2>Why the state is read here, under the lock</h2>
     *
     * <p>Reporting a context's close state calls into its owned resources, so an implementation that
     * BLOCKS inside {@code closeState()} delays this switch - and, because the switch lock is held, any
     * other thread waiting on the lifecycle. That is a known, accepted dependence on adapter behaviour,
     * not an oversight: the alternative - sampling the state outside the lock and revalidating it here -
     * was implemented, measured and REVERTED. It fails a legitimate concurrent case: with several threads
     * queueing on the lock, each one's sample is invalidated by the switch ahead of it, and a bounded
     * number of re-samples turns an ordinary contended switch into a spurious failure. Refusing every
     * contended switch, or looping without bound, are both worse than the delay they avoid.
     *
     * <p>So the contract is placed where it belongs and is documented as a hard requirement:
     * {@link com.mraibo.cminsight.core.CloseStateAware#closeState()} must be cheap and must never block,
     * and {@link com.mraibo.cminsight.connection.ResourceFactory#isHealthy(Object)} must not block while
     * the pool holds its lock. Safety is unaffected either way - a blocked read can only ever delay or
     * refuse a switch, never let one through.
     *
     * @throws RepositoryException when the retained context is not terminal-clean
     */
    private void resolveUnpublishedClosing(RepositoryProfile requested) throws RepositoryException {
        RepositoryContext retained = unpublishedClosing;
        if (retained == null) {
            return;
        }
        String retainedId = retained.profileUnchecked().id();
        CloseState state = closeStateOf(retained);
        if (state == CloseState.CLOSED_CLEAN) {
            // Every physical resource of the previous repository is proven gone: the latch has done its
            // job and must be released, or a future switch would be refused forever.
            record("Previous repository '" + retainedId + "' is now fully released; the switch may proceed.");
            releaseLatch(retained);
            return;
        }

        boolean uncertain = state == CloseState.CLOSED_UNCERTAIN;
        Refusal reason = uncertain ? Refusal.UNCERTAIN : Refusal.PENDING;
        // FAILED, exactly as the FIRST refusal publishes (failClosed): the manager state must not depend
        // on which attempt happened to be refused, and CLOSING is reserved for the manager's own shutdown.
        lifecycle.set(new Lifecycle(RepositoryState.FAILED, null));
        String detail = uncertain
                ? "Switch to '" + requested.id() + "' refused again: the previous repository '" + retainedId
                        + "' still has resources whose physical shutdown is unproven (" + state + "); "
                        + outstandingDetail(retained)
                        + ". No new repository context was created; connections that refused to close may"
                        + " still be open, and this cannot clear by itself."
                : "Switch to '" + requested.id() + "' refused: the previous repository '" + retainedId
                        + "' is still shutting down with a physical resource outstanding; "
                        + outstandingDetail(retained)
                        + ". No new repository context was created; the previous repository still owns a"
                        + " live physical resource. Retry once it is released.";
        refusal = reason;
        lastFailure = detail;
        record(detail);
        throw new RepositoryException(detail);
    }

    /**
     * Drops the retained reference, and only ever when the context is genuinely terminal-clean.
     *
     * <p>Guarding on the state rather than trusting the caller is deliberate: this is the single place
     * where a previous repository is forgotten, and forgetting one that is not proven gone is precisely
     * the defect this goal removes. A caller that gets it wrong downgrades to "retained", never to
     * "lost".
     */
    private void releaseLatch(RepositoryContext context) {
        if (unpublishedClosing == context && closeStateOf(context) == CloseState.CLOSED_CLEAN) {
            unpublishedClosing = null;
            refusal = null;
        }
    }

    /** Releases the latch only when the close that just finished left the context terminal-clean. */
    private void maybeReleaseLatch(RepositoryContext context, CloseOutcome outcome) {
        if (outcome.uncertain()) {
            return;
        }
        releaseLatch(context);
    }

    /** Publishes the manager state after a deactivate-style close, never claiming a released repository. */
    private void reportDeactivateOutcome(RepositoryContext context, CloseOutcome outcome) {
        String id = context.profileUnchecked().id();
        CloseState state = closeStateOf(context);
        if (outcome.uncertain() || state == CloseState.CLOSED_UNCERTAIN) {
            refusal = Refusal.UNCERTAIN;
            lastFailure = uncertainDetail(id, "Deactivating the active repository", outcome);
            lifecycle.set(new Lifecycle(RepositoryState.FAILED, null));
            record(lastFailure);
            record("Deactivated repository '" + id + "'.");
            return;
        }
        if (state != CloseState.CLOSED_CLEAN) {
            refusal = Refusal.PENDING;
            lastFailure = "Deactivating the active repository: repository '" + id + "' is still shutting down; "
                    + outstandingDetail(context) + ". It is not reported as released.";
            lifecycle.set(new Lifecycle(RepositoryState.FAILED, null));
            record(lastFailure);
            record("Deactivated repository '" + id + "'.");
            return;
        }
        releaseLatch(context);
        lifecycle.set(new Lifecycle(RepositoryState.NONE, null));
    }

    private void publishDeactivateState(RepositoryContext context, String previousId, CloseOutcome outcome) {
        CloseState state = closeStateOf(context);
        if (outcome.uncertain() || state == CloseState.CLOSED_UNCERTAIN) {
            refusal = Refusal.UNCERTAIN;
            lastFailure = uncertainDetail(previousId, "Deactivating the active repository", outcome);
            lifecycle.set(new Lifecycle(RepositoryState.FAILED, null));
            record(lastFailure);
        } else if (state != CloseState.CLOSED_CLEAN) {
            refusal = Refusal.PENDING;
            lastFailure = "Deactivating the active repository: repository '" + previousId
                    + "' is still shutting down; " + outstandingDetail(context) + ". It is not reported as released.";
            lifecycle.set(new Lifecycle(RepositoryState.FAILED, null));
            record(lastFailure);
        } else {
            releaseLatch(context);
            lifecycle.set(new Lifecycle(RepositoryState.NONE, null));
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
    private RepositoryException failClosed(RepositoryProfile requested,
                                           String previousId,
                                           Refusal reason,
                                           CloseOutcome outcome) {
        String detail = reason == Refusal.PENDING
                ? pendingDetail(previousId, requested.id(), outcome)
                : uncertainDetail(previousId, "Switch to '" + requested.id() + "' refused", outcome);
        refusal = reason;
        lastFailure = detail;
        lifecycle.set(new Lifecycle(RepositoryState.FAILED, null));
        record(detail);
        return new RepositoryException(detail);
    }

    /** One message for a close that left a resource outstanding, whoever asked, so the paths agree. */
    private static String pendingDetail(String previousId, String requestedId, CloseOutcome outcome) {
        StringBuilder detail = new StringBuilder("Switch to '").append(requestedId)
                .append("' refused: closing the previous repository '").append(previousId)
                .append("' left physical resources outstanding, so the previous repository may still own a"
                        + " live connection. No new repository context was created.");
        if (!outcome.failures().isEmpty()) {
            detail.append(" (").append(outcome.failures().size()).append(" resource close failure(s): ")
                    .append(String.join("; ", outcome.failures())).append(')');
        }
        if (outcome.thrownDetail() != null) {
            detail.append(" [close() also threw: ").append(outcome.thrownDetail()).append(']');
        }
        return detail.append(" Retry once the outstanding resource is released.").toString();
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
     *
     * <p>Note what is deliberately NOT here: a context that is merely still draining is not folded into
     * {@code uncertain}. It is reported separately through {@link CloseState#CLOSING}, so a refusal can
     * say "still shutting down, retry" instead of mislabelling an outstanding lease as a leak.
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

    /**
     * The close state of a context, read defensively.
     *
     * <p>A context that cannot answer is NOT evidence of a clean shutdown, so it is treated as
     * uncertain: the fail-closed rule has to hold even for a broken or hostile implementation, and
     * {@code CLOSED_UNCERTAIN} is the only answer that refuses every future switch. A missing context is
     * treated the same way - "there is nothing to check" must never be the answer that unlocks a switch.
     */
    private static CloseState closeStateOf(RepositoryContext context) {
        if (context == null) {
            return CloseState.CLOSED_UNCERTAIN;
        }
        try {
            CloseState state = context.closeState();
            return state == null ? CloseState.CLOSED_UNCERTAIN : state;
        } catch (Exception e) {
            return CloseState.CLOSED_UNCERTAIN;
        } catch (Error e) {
            return CloseState.CLOSED_UNCERTAIN;
        }
    }

    /** Value-free description of what an owned context still has outstanding, for diagnostics. */
    private static String outstandingDetail(RepositoryContext context) {
        CloseState state = closeStateOf(context);
        StringBuilder detail = new StringBuilder("it is not terminal-clean (").append(state).append(')');
        List<String> reports = context.uncertainCloseReports();
        if (!reports.isEmpty()) {
            // Re-surfaced on EVERY refusal, not only on the first one: a later retry that is refused
            // because of a quarantine must still say WHY. Without this the only diagnostic naming the
            // quarantine would be the first refusal's, and an operator reading the newest entries would
            // see "unproven" with no cause.
            detail.append(", reported: ").append(String.join("; ", reports));
        }
        List<String> failures = context.closeFailures();
        if (!failures.isEmpty()) {
            detail.append(", ").append(failures.size()).append(" resource close failure(s): ")
                    .append(String.join("; ", failures));
        }
        return detail.toString();
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
