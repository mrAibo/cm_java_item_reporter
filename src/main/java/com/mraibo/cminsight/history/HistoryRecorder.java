package com.mraibo.cminsight.history;

import com.mraibo.cminsight.statistics.ItemTypeStatistics;
import com.mraibo.cminsight.statistics.MetricValue;
import com.mraibo.cminsight.statistics.StatisticsSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The capture seam: turns one PUBLISHED {@link StatisticsSnapshot} into the immutable stored model and hands
 * it to a {@link HistoryStore}.
 *
 * <h2>Where it is called from, and why that matters</h2>
 *
 * <p>The scan coordinator owns exactly one publication point and invokes a listener there, after the snapshot
 * is visible and only for a scan that reached normal terminal completion. A timed-out, cancelled or
 * catastrophic scan never reaches that point, so "only a completed full scan becomes history" is a property
 * of WHERE this is called rather than a rule each caller has to remember. A targeted single-ItemType refresh
 * publishes no full snapshot at all and cannot reach it either.
 *
 * <p>Because that listener runs on the publication path of a scan that has already succeeded,
 * {@link #record(StatisticsSnapshot)} never throws and never blocks on anything but the local store: a
 * best-effort durable copy must not be able to fail the scan it copies.
 *
 * <h2>What the snapshot does not carry</h2>
 *
 * <p>Two facts a stored row needs are not on {@link StatisticsSnapshot}: the repository's display identity
 * and the database vendor. Both belong to the repository context this recorder is created for, so they are
 * supplied once through {@link #forRepository(HistoryStore, String, String, String)} rather than looked up
 * per row. The retention-policy name travels with the scan's own per-ItemType result; a producer that did not
 * capture one can supply it through {@link #withRetentionPolicyNames(Map)}, and an ItemType that has neither
 * stores an EMPTY name, which a report renders as unknown - never invented, and never a placeholder that could
 * be mistaken for a real policy.
 *
 * <p>The captured detail's identity is a fresh token of the right shape, and it is a PLACEHOLDER:
 * {@link HistoryStore#record(HistoryDetail)} assigns the store-owned identity and ignores the one it is given,
 * because a value derived from the context-local scan id is not an identity after a restart.
 * {@link #capture(StatisticsSnapshot)} exists so the mapping can be asserted with no store at all.
 */
public final class HistoryRecorder {

    private final HistoryStore store;
    private final String repositoryId;
    private final String repositoryDisplayName;
    private final String databaseVendor;
    private final Map<Integer, String> retentionPolicyNamesByItemTypeId;

    private HistoryRecorder(HistoryStore store,
                            String repositoryId,
                            String repositoryDisplayName,
                            String databaseVendor,
                            Map<Integer, String> retentionPolicyNamesByItemTypeId) {
        this.store = Objects.requireNonNull(store, "store");
        this.repositoryId = repositoryId == null ? "" : repositoryId.trim();
        this.repositoryDisplayName = repositoryDisplayName == null ? "" : repositoryDisplayName.trim();
        this.databaseVendor = databaseVendor == null ? "" : databaseVendor.trim();
        this.retentionPolicyNamesByItemTypeId = Map.copyOf(retentionPolicyNamesByItemTypeId);
    }

    /**
     * A recorder that stores snapshots of one repository.
     *
     * @param store                  the history store, available or explicitly unavailable
     * @param repositoryId           the repository the snapshots belong to; must match each snapshot's own id
     * @param repositoryDisplayName  the safe display name captured at scan time, or empty
     * @param databaseVendor         the vendor name captured at scan time, or empty
     */
    public static HistoryRecorder forRepository(HistoryStore store,
                                               String repositoryId,
                                               String repositoryDisplayName,
                                               String databaseVendor) {
        return new HistoryRecorder(store, repositoryId, repositoryDisplayName, databaseVendor, Map.of());
    }

    /**
     * The same recorder with the retention-policy names captured from metadata at scan time.
     *
     * <p>Keyed by ItemType id; a missing entry stores an empty name rather than a guess.
     */
    public HistoryRecorder withRetentionPolicyNames(Map<Integer, String> namesByItemTypeId) {
        return new HistoryRecorder(store, repositoryId, repositoryDisplayName, databaseVendor,
                namesByItemTypeId == null ? Map.of() : namesByItemTypeId);
    }

    /**
     * Captures and records one published snapshot.
     *
     * <p>Never throws, for any input: this runs from the publication listener of a scan whose own result is
     * already visible and durable, so a best-effort durable copy must not be able to fail the scan it copies.
     * An unavailable store, a refusal by the store (a version mismatch, a failed write), a wiring defect and
     * even a null argument all report as an empty result.
     */
    public Optional<HistoryId> record(StatisticsSnapshot snapshot) {
        if (snapshot == null || !store.available()) {
            return Optional.empty();
        }
        try {
            return store.record(capture(snapshot));
        } catch (RuntimeException failure) {
            // Deliberately swallowed: see the method contract. A store that refused the write has already
            // recorded its own value-free reason, and the scan's result is unaffected either way.
            return Optional.empty();
        }
    }

    /**
     * The pure mapping, with no store involved.
     *
     * @throws IllegalArgumentException when the snapshot belongs to another repository, which is a wiring
     *         defect rather than data: storing it would produce a row that names one repository and carries
     *         another one's totals
     */
    public HistoryDetail capture(StatisticsSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!repositoryId.isEmpty() && !repositoryId.equals(snapshot.repositoryId())) {
            throw new IllegalArgumentException("a history recorder for repository '" + repositoryId
                    + "' must not capture a snapshot of repository '" + snapshot.repositoryId()
                    + "': the stored row would name one repository and carry another one's totals");
        }

        List<HistoryItemType> itemTypes = new ArrayList<>(snapshot.perItemType().size());
        long logicalItemsTotal = 0L;
        for (ItemTypeStatistics result : snapshot.perItemType()) {
            itemTypes.add(itemType(result));
            // Absent measurements contribute nothing to the total; the coverage and the partial-failure count
            // travel with it, so an incomplete total can never be read as a complete one.
            logicalItemsTotal += result.logicalItems().valueOrZero();
        }

        HistorySummary summary = new HistorySummary(
                // A placeholder of the right shape: the store assigns the authoritative identity.
                HistoryStores.newId(),
                snapshot.repositoryId(),
                repositoryDisplayName,
                databaseVendor,
                snapshot.capturedAt(),
                snapshot.scanStartedAt(),
                snapshot.scanDurationMs(),
                snapshot.anchorDate(),
                snapshot.scanId(),
                itemTypes.size(),
                snapshot.partialFailureCount(),
                snapshot.complete(),
                logicalItemsTotal);
        return new HistoryDetail(summary, itemTypes, warning(snapshot));
    }

    /** The store this recorder writes to; never null, and it may be explicitly unavailable. */
    public HistoryStore store() {
        return store;
    }

    /** The repository this recorder captures for. */
    public String repositoryId() {
        return repositoryId;
    }

    /** A short value-free description for diagnostics. */
    public String describe() {
        return "history recorder[repository=" + repositoryId
                + ", store=" + (store.available() ? "available" : "unavailable")
                + ", captured retention names=" + retentionPolicyNamesByItemTypeId.size() + "]";
    }

    @Override
    public String toString() {
        return describe();
    }

    private HistoryItemType itemType(ItemTypeStatistics result) {
        return new HistoryItemType(
                result.itemTypeId(),
                result.itemTypeName(),
                result.businessClassification(),
                retentionPolicyName(result),
                status(result.status()),
                metric(result.logicalItems()),
                metric(result.createdToday()),
                metric(result.createdLast7Days()),
                metric(result.createdLast30Days()),
                metric(result.createdCurrentYear()),
                result.durationMs(),
                result.errorMessage());
    }

    /**
     * The retention-policy name to store for one ItemType.
     *
     * <p>The name the SCAN captured with the result wins, because it belongs to that row. The map supplied
     * through {@link #withRetentionPolicyNames(Map)} is the fallback for a producer that did not capture one,
     * and an ItemType that appears in neither stores EMPTY - which a report renders as unknown. A placeholder
     * would be indistinguishable from a real policy name once it is in a stored report.
     */
    private String retentionPolicyName(ItemTypeStatistics result) {
        String captured = result.retentionPolicyName();
        if (!captured.isEmpty()) {
            return captured;
        }
        return retentionPolicyNamesByItemTypeId.getOrDefault(result.itemTypeId(), "");
    }

    private static HistoryItemType.Status status(ItemTypeStatistics.Status status) {
        return switch (status) {
            case OK -> HistoryItemType.Status.OK;
            case PARTIAL -> HistoryItemType.Status.PARTIAL;
            case ERROR -> HistoryItemType.Status.ERROR;
        };
    }

    /**
     * One metric, restated in the stored vocabulary.
     *
     * <p>The state travels with the number, and only {@code AVAILABLE} carries one: an UNAVAILABLE or ERROR
     * measurement becomes an absent stored metric with its sanitized reason, so a count nobody measured can
     * never come back from storage looking like a measurement of zero.
     */
    private static HistoryMetric metric(MetricValue value) {
        return switch (value.availability()) {
            // valueOrZero() is the measured number here: Availability.AVAILABLE is the only state that
            // carries one, which MetricValue enforces in its own constructor.
            case AVAILABLE -> HistoryMetric.available(value.valueOrZero());
            case UNAVAILABLE -> HistoryMetric.unavailable(value.reason());
            case ERROR -> HistoryMetric.error(value.reason());
        };
    }

    /**
     * A sanitized, value-free note recorded at capture time.
     *
     * <p>Empty for a complete snapshot. For an incomplete one it states the COVERAGE - how many ItemTypes were
     * requested and how they ended - and never a value, a name or a failure message from the database.
     */
    private static String warning(StatisticsSnapshot snapshot) {
        return snapshot.complete() ? "" : "stored with partial coverage: " + snapshot.coverageDescription();
    }
}
