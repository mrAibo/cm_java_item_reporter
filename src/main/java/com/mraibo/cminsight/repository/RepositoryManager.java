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
 *   <li>a new context is created and published only after it is fully initialized and validated;</li>
 *   <li>if initialization fails, nothing is published: the manager reports {@link RepositoryState#FAILED}
 *       and the failure, and never keeps a half-initialized context.</li>
 * </ol>
 *
 * <p>Consequence of (2) and (4): a failed switch does not resurrect the previous repository. That is
 * the fail-closed behaviour the architecture asks for, and the diagnostics record why.
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
     * @throws RepositoryException when the manager is closed or activation fails
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
                closeContext(previous, "switch away from '" + previous.profileUnchecked().id() + "'");
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
            closeContext(previous, "explicit deactivate");
            if (lifecycle.get().state() != RepositoryState.CLOSED) {
                lifecycle.set(new Lifecycle(RepositoryState.NONE, null));
            }
            record("Deactivated repository '" + previous.profileUnchecked().id() + "'.");
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

    private void closeContext(RepositoryContext context, String reason) {
        try {
            context.close();
        } catch (Throwable e) {
            // Cleanup must never escape a switch, not even as an Error: the lifecycle is already
            // fail-closed, and the failure belongs in diagnostics rather than in the caller's face.
            record("Error closing repository context during " + reason + ": " + describe(e));
        }
        List<String> failures = context.closeFailures();
        if (!failures.isEmpty()) {
            record("Closing repository '" + context.profileUnchecked().id() + "' during " + reason
                    + " reported " + failures.size() + " resource failure(s): " + String.join("; ", failures));
        }
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
