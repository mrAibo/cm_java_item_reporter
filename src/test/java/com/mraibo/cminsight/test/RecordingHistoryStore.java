package com.mraibo.cminsight.test;

import com.mraibo.cminsight.history.HistoryDetail;
import com.mraibo.cminsight.history.HistoryId;
import com.mraibo.cminsight.history.HistoryStore;
import com.mraibo.cminsight.history.HistorySummary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-memory {@link HistoryStore} that records what the application asked it to persist.
 *
 * <h2>What it is for, and what it deliberately cannot prove</h2>
 *
 * <p>This is the measuring device for the <strong>application</strong> half of Goal 04 section 4: which
 * completed scans are offered to history at all, with what coverage and identity, and what the caller does
 * when storage refuses. Those are properties of the wiring, and the wiring is what this store observes.
 *
 * <p>It is NOT evidence about the persistent implementation - row ordering in a real table, a pruned
 * bound, or transaction atomicity. Those are properties of {@code H2HistoryStore} and are asserted against
 * a real embedded database in {@code HistoryPersistenceH2Test}, never against this double. A fake that
 * models the storage rules would pass whether or not the real store implements them, which is the defect
 * this separation exists to avoid.
 *
 * <p>Package-private on purpose: it is a fixture, not a suite, so {@code SelfTest}'s unregistered-suite
 * check must not treat it as one.
 */
final class RecordingHistoryStore implements HistoryStore {

    private final List<HistoryDetail> stored = new ArrayList<>();
    private final AtomicInteger recordCalls = new AtomicInteger();
    private final AtomicInteger listCalls = new AtomicInteger();
    private final Instant openedAt = Instant.now();

    /** When set, every {@link #record} fails the way a refused transaction would: nothing becomes visible. */
    private volatile boolean refuseWrites;

    /** When set, {@link #record} throws instead of answering - a broken local store, not a busy one. */
    private volatile boolean throwingWrites;

    /** When set, the store answers as unavailable without refusing an individual call. */
    private volatile String unavailableReason = "";

    @Override
    public boolean available() {
        return unavailableReason.isEmpty();
    }

    @Override
    public Optional<String> unavailableReason() {
        return unavailableReason.isEmpty() ? Optional.empty() : Optional.of(unavailableReason);
    }

    @Override
    public synchronized Optional<HistoryId> record(HistoryDetail detail) {
        recordCalls.incrementAndGet();
        if (throwingWrites) {
            throw new IllegalStateException("the local history store failed while writing");
        }
        if (!available() || refuseWrites) {
            return Optional.empty();
        }
        HistoryId id = new HistoryId("h" + (stored.size() + 1));
        HistoryDetail assigned = new HistoryDetail(withId(detail.summary(), id), detail.itemTypes(),
                detail.warning());
        stored.add(assigned);
        return Optional.of(id);
    }

    @Override
    public synchronized List<HistorySummary> list(String repositoryId, int limit) {
        listCalls.incrementAndGet();
        if (limit <= 0) {
            return List.of();
        }
        if (!available()) {
            return List.of();
        }
        return newestFirst(repositoryId).stream().limit(limit).toList();
    }

    @Override
    public synchronized List<HistorySummary> listAfter(String repositoryId, HistorySummary before, int limit) {
        if (limit <= 0 || before == null || !available()) {
            return List.of();
        }
        List<HistorySummary> rows = newestFirst(repositoryId);
        int start = 0;
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).id().sameAs(before.id())) {
                start = index + 1;
                break;
            }
        }
        return rows.subList(Math.min(start, rows.size()), rows.size()).stream().limit(limit).toList();
    }

    @Override
    public synchronized Optional<HistoryDetail> find(HistoryId id) {
        if (!available() || id == null) {
            return Optional.empty();
        }
        for (HistoryDetail detail : stored) {
            if (detail.id().sameAs(id)) {
                return Optional.of(detail);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized long count(String repositoryId) {
        if (!available()) {
            return 0L;
        }
        return newestFirst(repositoryId).size();
    }

    @Override
    public synchronized Optional<HistorySummary> latest(String repositoryId) {
        List<HistorySummary> rows = newestFirst(repositoryId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public Instant openedAt() {
        return openedAt;
    }

    @Override
    public void close() {
        // Nothing physical to release.
    }

    // ------------------------------------------------------------------ test-facing behaviour

    /** How many times {@link #record} was entered, including refused writes. */
    int recordCalls() {
        return recordCalls.get();
    }

    int listCalls() {
        return listCalls.get();
    }

    /** Every stored entry, in insertion order. */
    synchronized List<HistoryDetail> all() {
        return List.copyOf(stored);
    }

    synchronized List<HistoryDetail> forRepository(String repositoryId) {
        return stored.stream()
                .filter(detail -> detail.summary().belongsTo(repositoryId))
                .toList();
    }

    RecordingHistoryStore refusingWrites() {
        this.refuseWrites = true;
        return this;
    }

    RecordingHistoryStore throwingWrites() {
        this.throwingWrites = true;
        return this;
    }

    RecordingHistoryStore unavailable(String reason) {
        this.unavailableReason = reason == null ? "unavailable" : reason;
        return this;
    }

    private List<HistorySummary> newestFirst(String repositoryId) {
        return stored.stream()
                .map(HistoryDetail::summary)
                .filter(summary -> summary.belongsTo(repositoryId))
                .sorted(Comparator.comparing(HistorySummary::capturedAt).reversed()
                        .thenComparing(summary -> summary.id().value()))
                .toList();
    }

    private static HistorySummary withId(HistorySummary summary, HistoryId id) {
        return new HistorySummary(id, summary.repositoryId(), summary.repositoryDisplayName(),
                summary.databaseVendor(), summary.capturedAt(), summary.scanStartedAt(),
                summary.scanDurationMs(), summary.anchorDate(), summary.scanId(), summary.itemTypeCount(),
                summary.partialFailureCount(), summary.complete(), summary.logicalItemsTotal());
    }
}
