package com.mraibo.cminsight.web;

import com.mraibo.cminsight.history.HistoryDetail;
import com.mraibo.cminsight.history.HistoryId;
import com.mraibo.cminsight.history.HistoryStore;
import com.mraibo.cminsight.history.HistorySummary;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The persistent-history facts the HTTP tier is allowed to publish, as one typed port.
 *
 * <h2>Why a port over {@link HistoryStore}</h2>
 *
 * <p>{@code HistoryStore} is already a typed, value-free boundary, so this interface deliberately adds almost
 * nothing: it adds the ONE fact the store cannot know - whether the history feature is switched on - and it
 * makes the "no store is wired into this runtime at all" case explicit rather than a {@code null} a handler
 * has to remember to check.
 *
 * <p>{@link State#DISABLED} and {@link State#UNAVAILABLE} are different answers on purpose. "The operator
 * switched history off" is a decision; "the local H2 driver is missing, so nothing can be stored" is a
 * fault. Both must leave repository activation, metadata, retention and live statistics fully working, and
 * both must be visible rather than rendered as a missing endpoint.
 *
 * <h2>Reads never start work</h2>
 *
 * <p>Every method here is a read of already-stored local data. Nothing in this interface can query IBM CM,
 * open a repository connection or perform a scan, so a page that lists history cannot cause database work.
 * A store implementation owns at most one physical connection and this port neither opens nor closes one.
 *
 * <h2>Availability is not judged here</h2>
 *
 * <p>A caller may call {@link #list(String, int)} on an unavailable port: the documented answer is an empty
 * list, not an exception. That keeps a viewer rendering the rest of its page, and the state/reason pair is
 * what tells the operator why the list is empty.
 */
public interface HistoryApi {

    /** Whether history can be read, is switched off, or cannot be read right now. */
    enum State {
        /** Storage is ready and history can be listed and opened. */
        AVAILABLE,
        /** {@code feature.history=false}: the operator switched the feature off. Nothing is wrong. */
        DISABLED,
        /** The feature is on but no store can be used right now (for example the H2 driver is absent). */
        UNAVAILABLE
    }

    /** The three-state capability, read from state the producer already holds. Never opens anything. */
    State state();

    /** A fixed, value-free explanation for a non-{@link State#AVAILABLE} state, or {@code ""}. */
    String reason();

    /** Convenience over {@link #state()}: true only when history can actually be read. */
    default boolean available() {
        return state() == State.AVAILABLE;
    }

    /**
     * Stored snapshots for one repository, newest first, bounded by {@code limit}.
     *
     * @param repositoryId the repository to list; never a path or a SQL value
     * @param limit        the maximum rows; bounded by the caller, never a caller-supplied unbounded value
     */
    List<HistorySummary> list(String repositoryId, int limit);

    /**
     * Stored snapshots strictly older than {@code before}, newest first, bounded by {@code limit}.
     *
     * <p>The cursor is a row the caller last displayed, so a page cannot skip or repeat a row while new
     * snapshots arrive at the head of the list.
     */
    List<HistorySummary> listAfter(String repositoryId, HistorySummary before, int limit);

    /** One stored snapshot in full, or empty when no entry has that identity. */
    Optional<HistoryDetail> find(HistoryId id);

    /** How many snapshots are stored for one repository, for a bounded row count. */
    long count(String repositoryId);

    /** The newest stored snapshot for one repository, or empty when it has none. */
    Optional<HistorySummary> latest(String repositoryId);

    /**
     * The seam used where the history feature is switched off, or where no store is wired at all.
     *
     * <p>Exists so every wiring path still INSTALLS the history routes: a route that is missing answers
     * {@code 404}, which reads like a path typo, while a route that reports the documented state tells the
     * operator what is actually true.
     *
     * @param state  {@link State#DISABLED} or {@link State#UNAVAILABLE}
     * @param reason a fixed, value-free sentence explaining the state
     */
    static HistoryApi of(State state, String reason) {
        final State fixedState = state == null ? State.UNAVAILABLE : state;
        final String fixedReason = reason == null ? "" : reason;
        return new HistoryApi() {

            @Override
            public State state() {
                return fixedState;
            }

            @Override
            public String reason() {
                return fixedReason;
            }

            @Override
            public List<HistorySummary> list(String repositoryId, int limit) {
                return List.of();
            }

            @Override
            public List<HistorySummary> listAfter(String repositoryId, HistorySummary before, int limit) {
                return List.of();
            }

            @Override
            public Optional<HistoryDetail> find(HistoryId id) {
                return Optional.empty();
            }

            @Override
            public long count(String repositoryId) {
                return 0L;
            }

            @Override
            public Optional<HistorySummary> latest(String repositoryId) {
                return Optional.empty();
            }

            @Override
            public String toString() {
                return "HistoryApi[" + fixedState + "]";
            }
        };
    }

    /** The documented "no history capability is wired into this runtime" seam. */
    static HistoryApi unavailable(String reason) {
        return of(State.UNAVAILABLE, reason);
    }

    /**
     * The port over one store, plus the feature decision the store itself cannot know.
     *
     * <p>The state is decided once, here, from two facts that are both cheap and local: the feature switch
     * and the store's own availability verdict. It never opens anything - the store's contract is that
     * {@code available()} reports a capability determined at open time - so this factory is safe to call on
     * a startup path that must not touch a database.
     *
     * @param store   the history store, or {@code null} when the runtime wired none
     * @param enabled {@code feature.history}
     */
    static HistoryApi of(HistoryStore store, boolean enabled) {
        if (!enabled) {
            return of(State.DISABLED,
                    "The history feature is switched off (feature.history=false); no snapshot is stored");
        }
        if (store == null) {
            return of(State.UNAVAILABLE, "No history store is wired into this runtime");
        }
        if (!store.available()) {
            return of(State.UNAVAILABLE, store.unavailableReason()
                    .orElse("The local history store is not usable"));
        }
        return new StoreHistoryApi(store);
    }

    /** The port over a store that is known to be available. */
    final class StoreHistoryApi implements HistoryApi {

        private final HistoryStore store;

        StoreHistoryApi(HistoryStore store) {
            this.store = Objects.requireNonNull(store, "store");
        }

        @Override
        public State state() {
            // Re-read on every call: a store can lose its storage after it was opened (for example when its
            // file disappears), and reporting a cached "AVAILABLE" would then be a claim the store no longer
            // supports. The store's own contract makes this read cheap and connection-free.
            return store.available() ? State.AVAILABLE : State.UNAVAILABLE;
        }

        @Override
        public String reason() {
            return store.available() ? "" : store.unavailableReason().orElse("The local history store is not"
                    + " usable");
        }

        @Override
        public List<HistorySummary> list(String repositoryId, int limit) {
            return store.list(repositoryId, limit);
        }

        @Override
        public List<HistorySummary> listAfter(String repositoryId, HistorySummary before, int limit) {
            return store.listAfter(repositoryId, before, limit);
        }

        @Override
        public Optional<HistoryDetail> find(HistoryId id) {
            return store.find(id);
        }

        @Override
        public long count(String repositoryId) {
            return store.count(repositoryId);
        }

        @Override
        public Optional<HistorySummary> latest(String repositoryId) {
            return store.latest(repositoryId);
        }

        @Override
        public String toString() {
            return "HistoryApi[store=" + store.schemaVersion() + ", openedAt=" + store.openedAt() + "]";
        }
    }
}
