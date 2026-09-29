package com.mraibo.cminsight.statistics;

import com.mraibo.cminsight.metadata.ItemTypeSummary;

import java.time.Instant;
import java.util.Objects;

/**
 * One ItemType's result inside one snapshot.
 *
 * <h2>Versions and Parts are structurally unavailable</h2>
 *
 * <p>Both are forced to {@link MetricValue#unavailable()} by the compact constructor, whatever a caller
 * passes (and there is no factory parameter for them at all). Goal 05 owns that research; until then a
 * number for either would be a guess, and a guess in this record would be indistinguishable from a
 * measurement once it reaches a report. Both keep the declared type {@link MetricValue}, because
 * {@code StatisticsContractTest} pins that shape so a bare guessed number cannot silently replace them.
 *
 * <h2>Status is derived, never asserted</h2>
 *
 * <p>{@link #status()} is computed by the factories below from the metrics themselves:
 * {@link Status#ERROR} when the total failed, {@link Status#PARTIAL} when the total is available but at
 * least one window metric is not, and {@link Status#OK} only when every metric this goal produces is
 * available. A producer therefore cannot label a partial item "OK", which is the failure mode that would
 * let an incomplete number be presented as complete.
 *
 * @param repositoryId           the repository this result belongs to
 * @param itemTypeId             the integer ItemType id, for cross-referencing the metadata list
 * @param itemTypeName           the ItemType name as IBM CM reports it
 * @param businessClassification the label already present in metadata; totals are grouped by it and this
 *                               class never re-derives SAP/NON-SAP
 * @param scanStartedAt          when the scan that produced this result started
 * @param capturedAt             when this ItemType's measurement finished
 * @param logicalItems           distinct ItemIDs, or the ERROR state with a sanitized reason
 * @param createdToday           items created today by ItemID date
 * @param createdLast7Days       items created in the last 7 days by ItemID date
 * @param createdLast30Days      items created in the last 30 days by ItemID date
 * @param createdCurrentYear     items created in the current calendar year by ItemID date
 * @param versions               always {@code UNAVAILABLE} in this goal
 * @param parts                  always {@code UNAVAILABLE} in this goal
 * @param durationMs             how long this ItemType's measurement took
 * @param source                 a fixed label for where the number came from (for example {@code "jdbc"})
 * @param status                 derived: OK, PARTIAL or ERROR
 * @param errorMessage           sanitized failure text, or an empty string
 */
public record ItemTypeStatistics(String repositoryId,
                                 int itemTypeId,
                                 String itemTypeName,
                                 String businessClassification,
                                 Instant scanStartedAt,
                                 Instant capturedAt,
                                 MetricValue logicalItems,
                                 MetricValue createdToday,
                                 MetricValue createdLast7Days,
                                 MetricValue createdLast30Days,
                                 MetricValue createdCurrentYear,
                                 MetricValue versions,
                                 MetricValue parts,
                                 long durationMs,
                                 String source,
                                 Status status,
                                 String errorMessage) {

    /** How completely this ItemType was measured. Derived from the metrics, never asserted. */
    public enum Status {
        /** Every metric this goal produces is available. */
        OK,
        /** The total is available but at least one window metric is not. */
        PARTIAL,
        /** The measurement failed; no metric of this ItemType is available. */
        ERROR
    }

    /** Fixed label for results measured by the JDBC analytics path. */
    public static final String SOURCE_JDBC = "jdbc";

    static final int MAX_MESSAGE_LENGTH = 200;
    static final int MAX_NAME_LENGTH = 128;

    public ItemTypeStatistics {
        repositoryId = repositoryId == null ? "" : repositoryId.trim();
        itemTypeName = SanitizedText.clean(itemTypeName, MAX_NAME_LENGTH);
        businessClassification = SanitizedText.clean(businessClassification, MAX_NAME_LENGTH);
        source = SanitizedText.clean(source, 32);
        errorMessage = SanitizedText.clean(errorMessage, MAX_MESSAGE_LENGTH);
        Objects.requireNonNull(logicalItems, "logicalItems");
        Objects.requireNonNull(createdToday, "createdToday");
        Objects.requireNonNull(createdLast7Days, "createdLast7Days");
        Objects.requireNonNull(createdLast30Days, "createdLast30Days");
        Objects.requireNonNull(createdCurrentYear, "createdCurrentYear");
        Objects.requireNonNull(status, "status");
        // Structural, not conventional: the two metrics this goal does not produce can never carry a
        // number, whoever built this record.
        versions = MetricValue.unavailable();
        parts = MetricValue.unavailable();
    }

    /**
     * A successfully measured ItemType, with the status derived from the metrics.
     *
     * <p>An item whose total is available but whose window boundaries were not representable comes out
     * {@link Status#PARTIAL} automatically - the caller cannot promote it.
     */
    public static ItemTypeStatistics measured(String repositoryId,
                                              ItemTypeSummary itemType,
                                              Instant scanStartedAt,
                                              Instant capturedAt,
                                              long durationMs,
                                              String source,
                                              ItemTypeAggregate aggregate) {
        Objects.requireNonNull(itemType, "itemType");
        Objects.requireNonNull(aggregate, "aggregate");
        MetricValue total = MetricValue.available(aggregate.logicalItems());
        MetricValue today = aggregate.createdToday();
        MetricValue last7 = aggregate.createdLast7Days();
        MetricValue last30 = aggregate.createdLast30Days();
        MetricValue year = aggregate.createdCurrentYear();
        Status status = today.isAvailable() && last7.isAvailable() && last30.isAvailable() && year.isAvailable()
                ? Status.OK
                : Status.PARTIAL;
        return new ItemTypeStatistics(repositoryId, itemType.itemTypeId(), itemType.name(),
                itemType.businessClassification(), scanStartedAt, capturedAt,
                total, today, last7, last30, year, null, null,
                durationMs, source, status, "");
    }

    /**
     * A failed ItemType: every metric of this goal is ERROR (never a zero) and the reason is sanitized.
     *
     * <p>Deliberately carries no number at all. A failure that reported {@code 0} items would be added to
     * the totals as data, which is exactly what the coverage/partial-failure accounting exists to prevent.
     *
     * <p>The ERROR state is built through the canonical constructor because {@link MetricValue} exposes
     * exactly two factories, both pinned by the contract test; see that type's javadoc.
     */
    public static ItemTypeStatistics failed(String repositoryId,
                                            ItemTypeSummary itemType,
                                            Instant scanStartedAt,
                                            Instant capturedAt,
                                            long durationMs,
                                            String source,
                                            String errorMessage) {
        Objects.requireNonNull(itemType, "itemType");
        MetricValue error = errorMetric(errorMessage);
        return new ItemTypeStatistics(repositoryId, itemType.itemTypeId(), itemType.name(),
                itemType.businessClassification(), scanStartedAt, capturedAt,
                error, error, error, error, error, null, null,
                durationMs, source, Status.ERROR, errorMessage);
    }

    /** The one place this package builds a failed metric, so the shape cannot drift between call sites. */
    static MetricValue errorMetric(String reason) {
        return new MetricValue(null, MetricValue.Availability.ERROR, reason);
    }

    /** An unavailable metric that states why, without adding a factory to the pinned {@link MetricValue}. */
    static MetricValue unavailableMetric(String reason) {
        return new MetricValue(null, MetricValue.Availability.UNAVAILABLE, reason);
    }

    /** True when this ItemType's measurement failed. */
    public boolean failed() {
        return status == Status.ERROR;
    }

    /** True when the total was measured, even if a window metric was not. */
    public boolean totalAvailable() {
        return logicalItems.isAvailable();
    }

    /** The measured distinct-ItemID total, or {@code 0} when there is none; never used for arithmetic. */
    public long logicalItemsOrZero() {
        return logicalItems.valueOrZero();
    }

    @Override
    public String toString() {
        return "ItemTypeStatistics[" + itemTypeName + " (#" + itemTypeId + "), " + status
                + ", logicalItems=" + logicalItems
                + (errorMessage.isEmpty() ? "" : ", reason=" + errorMessage) + "]";
    }
}
