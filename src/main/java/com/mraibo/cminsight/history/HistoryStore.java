package com.mraibo.cminsight.history;

import com.mraibo.cminsight.statistics.StatisticsSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The typed boundary for persistent aggregate history.
 *
 * <h2>What history is, and what it is not</h2>
 *
 * <p>It is an application-local store of <strong>aggregate read-model data</strong>: the same numbers a
 * completed scan already published, recorded so an operator can look at yesterday's totals without a live
 * repository. It is not a second statistics cache - the published {@code StatisticsSnapshot} remains the
 * single authoritative in-memory value - and it is not a document store. Nothing that could carry a
 * credential, a connection string or document content has a field here, which is why the whole model is
 * reachable through this one interface.
 *
 * <h2>Identity</h2>
 *
 * <p>A {@link HistoryId} is owned by the STORE, not by the scan. The scan's own {@code scanId} is
 * monotonically increasing only within one repository context, so after a restart - or across two
 * repositories - it is not an identity at all. It is carried as diagnostic metadata and never as a key.
 *
 * <h2>Availability is a first-class answer</h2>
 *
 * <p>History is optional. When no local database driver is present the store is
 * {@link #unavailable(String)}, every read returns empty and every write is refused <em>without throwing
 * into the caller's activation path</em>. A repository whose metadata, retention and live statistics all
 * work must never fail to activate because history is missing, so the absence is reported as a state rather
 * than as an exception. Implementations must therefore be safe to call on a repository where they can do
 * nothing.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Implementations must be safe for concurrent callers and must own at most ONE physical connection at a
 * time - no per-request connection fan-out. This store never touches the repository's JDBC credentials or
 * its analytics pool; it is a local file-backed database and nothing else.
 */
public interface HistoryStore extends AutoCloseable {

    /**
     * True when this store can actually persist and read history right now.
     *
     * <p>Cheap and local: it reports a previously determined capability and must not open a connection or
     * perform I/O merely to answer.
     */
    boolean available();

    /** A fixed, value-free reason when {@link #available()} is false; empty when it is true. */
    Optional<String> unavailableReason();

    /**
     * Records one completed full snapshot for a repository.
     *
     * <p>Callers must only pass a detail built from a scan that reached normal terminal completion. A
     * timed-out, cancelled or catastrophic scan has no snapshot to record, and the store does not attempt to
     * judge that - the publication rule upstream already decided it.
     *
     * <p>Implementations must also enforce the configured retention bound after a successful insert, and
     * must be atomic: a failed write leaves no partially written snapshot visible to a later read.
     *
     * @param detail the immutable captured data; its identity is assigned by the store, so a caller does not
     *               supply one
     * @return the assigned identity, or empty when the store is unavailable or the write failed
     */
    Optional<HistoryId> record(HistoryDetail detail);

    /**
     * Lists stored snapshots for one repository, newest first, bounded by {@code limit}.
     *
     * <p>Deterministic ordering is part of the contract: two calls with the same arguments must return the
     * same order, so a paginated UI cannot skip or repeat a row.
     *
     * @param repositoryId the repository to list; never a path or a SQL value
     * @param limit        the maximum rows to return; implementations must reject a non-positive value
     */
    List<HistorySummary> list(String repositoryId, int limit);

    /**
     * Lists stored snapshots for one repository strictly older than {@code before}, newest first, bounded by
     * {@code limit}.
     *
     * <p>The cursor is the summary the caller last displayed, so paging is stable even though new snapshots
     * arrive at the head of the list. Ordering ties are broken by identity so a cursor cannot straddle two
     * rows captured in the same instant.
     */
    List<HistorySummary> listAfter(String repositoryId, HistorySummary before, int limit);

    /** One stored snapshot in full, or empty when no entry has that identity. */
    Optional<HistoryDetail> find(HistoryId id);

    /**
     * The number of stored snapshots for one repository, for coverage and for a bounded-UI row count.
     *
     * <p>A count is intentionally the only aggregate: history is a list of completed snapshots, and a
     * "statistics of statistics" query would be a second derived model nobody asked to keep consistent.
     */
    long count(String repositoryId);

    /** The newest stored snapshot for one repository, or empty when it has none. */
    Optional<HistorySummary> latest(String repositoryId);

    /**
     * Closes local storage resources.
     *
     * <p>Never throws a checked failure: a history store that cannot close must not be able to fail the
     * repository shutdown that is trying to release its real resources. An implementation reports the
     * problem through {@link #unavailableReason()} and, where it has an owner, through diagnostics.
     */
    @Override
    void close();

    /** The schema version this implementation writes, for an explicit migration check. */
    int schemaVersion();

    /** The instant the store was opened, for diagnostics. */
    Instant openedAt();
}
