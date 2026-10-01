package com.mraibo.cminsight.statistics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One ItemType's targeted refresh result: SEPARATE detail data with its own anchor, never part of a full
 * snapshot.
 *
 * <h2>The rule this type exists to make structural</h2>
 *
 * <p>A targeted single-ItemType refresh MUST NOT mutate the published full {@link StatisticsSnapshot},
 * recalculate or replace the dashboard totals, or make a mixed-anchor result look internally consistent.
 * The shape above is the first line of that defence: this is not a {@code StatisticsSnapshot}, it holds no
 * totals, no coverage and no per-ItemType list, so there is nothing here a dashboard could sum. It carries
 * exactly one ItemType's measured metrics keyed by that ItemType's identity, plus its own
 * {@link #capturedAt()}, its own {@link #anchorDate()} and its own {@link #freshness()} - the three facts
 * that let a reader tell "measured in this targeted operation, anchored on this database date" apart from
 * "measured by the full scan that the dashboard totals cite".
 *
 * <h2>Why the anchor is carried, and why it is never used to update a snapshot</h2>
 *
 * <p>The anchor is the database's current date, read once for this one operation. The full snapshot has its
 * own, possibly different anchor from its own scan. Keeping both anchors visible is what stops two results
 * measured against different calendars from being presented as one internally consistent picture: a reader
 * that wants to compare them can see that the anchors differ, and no code in this package ever merges them.
 *
 * <h2>Only measured data becomes detail data</h2>
 *
 * <p>The compact constructor refuses an {@link ItemTypeStatistics.Status#ERROR} statistics record. A failed
 * targeted attempt is reported by the {@link TargetedRefreshResult} of that attempt and never replaces the
 * detail data of a previous successful one, so this type can never hold a row that says "measured" while
 * carrying no measurement.
 *
 * <h2>Versions and Parts</h2>
 *
 * <p>They remain {@link MetricValue#UNAVAILABLE} exactly as they are inside a snapshot, because
 * {@link ItemTypeStatistics} forces that whatever a producer passes. A targeted refresh does not unlock
 * Goal 05's research gate, and this type adds no way to express a number for either.
 *
 * @param repositoryId the repository this detail belongs to
 * @param itemTypeId   the ItemType identity this detail is keyed by, from the active repository metadata
 * @param capturedAt   when this targeted measurement finished
 * @param anchorDate   the ONE database current date this targeted operation was anchored on
 * @param durationMs   how long the measurement took
 * @param statistics   the measured per-ItemType result, in the same shape a full scan publishes
 * @param freshness    the age judgement of this detail at publication, carrying the configured threshold
 */
public record TargetedItemTypeDetail(String repositoryId,
                                     int itemTypeId,
                                     Instant capturedAt,
                                     LocalDate anchorDate,
                                     long durationMs,
                                     ItemTypeStatistics statistics,
                                     Freshness freshness) {

    /**
     * A fixed discriminator a renderer can publish beside the data, so "targeted detail" is never inferred
     * from which fields happen to be present.
     */
    public static final String DETAIL_KIND = "targeted-detail";

    public TargetedItemTypeDetail {
        repositoryId = repositoryId == null ? "" : repositoryId.trim();
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(anchorDate, "anchorDate");
        Objects.requireNonNull(statistics, "statistics");
        Objects.requireNonNull(freshness, "freshness");
        if (statistics.itemTypeId() != itemTypeId) {
            throw new IllegalArgumentException("the detail is keyed by ItemType id " + itemTypeId
                    + " but carries the measurement of ItemType id " + statistics.itemTypeId());
        }
        if (!capturedAt.equals(statistics.capturedAt())) {
            // One capture instant, not two that can drift: the age a reader judges is the age of the
            // measurement that is actually published here.
            throw new IllegalArgumentException("the detail capture instant " + capturedAt
                    + " does not match the measurement's own capture instant " + statistics.capturedAt());
        }
        if (statistics.status() == ItemTypeStatistics.Status.ERROR) {
            throw new IllegalArgumentException("a failed measurement is not targeted detail data: report it"
                    + " as the refresh outcome instead of publishing it as a detail result");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative but was " + durationMs);
        }
        if (!freshness.known() || !capturedAt.equals(freshness.capturedAt())) {
            throw new IllegalArgumentException("the detail must carry a known freshness judgement of its own"
                    + " capture instant");
        }
    }

    /** The ItemType name as IBM CM reports it, taken from the measurement itself. */
    public String itemTypeName() {
        return statistics.itemTypeName();
    }

    /** The classification label the metadata list carries; never re-derived here. */
    public String businessClassification() {
        return statistics.businessClassification();
    }

    /** {@link #DETAIL_KIND}: the fixed label a payload or a UI can render beside this data. */
    public String kind() {
        return DETAIL_KIND;
    }

    /** Distinct ItemIDs across versions and across every expected root segment. */
    public MetricValue logicalItems() {
        return statistics.logicalItems();
    }

    public MetricValue createdToday() {
        return statistics.createdToday();
    }

    public MetricValue createdLast7Days() {
        return statistics.createdLast7Days();
    }

    public MetricValue createdLast30Days() {
        return statistics.createdLast30Days();
    }

    public MetricValue createdCurrentYear() {
        return statistics.createdCurrentYear();
    }

    /** Always {@code UNAVAILABLE} in this goal. */
    public MetricValue versions() {
        return statistics.versions();
    }

    /** Always {@code UNAVAILABLE} in this goal. */
    public MetricValue parts() {
        return statistics.parts();
    }

    /** The derived measurement status: OK or PARTIAL, never ERROR (see the compact constructor). */
    public ItemTypeStatistics.Status status() {
        return statistics.status();
    }

    /**
     * This detail judged at {@code now}, using the threshold it was published with.
     *
     * <p>The stored {@link #freshness()} is the judgement at publication (age zero, therefore not stale)
     * and exists so the age and the threshold travel with the data. A renderer that wants "how old is this
     * now" calls this method instead, which is the same computation the live snapshot gets - one notion of
     * freshness, applied to two independent results.
     */
    public Freshness freshnessAt(Instant now) {
        return Freshness.of(capturedAt, freshness.threshold(), now);
    }

    /** Whole-millisecond age at publication, for a payload that reports age beside the value. */
    public long ageMillisAtPublication() {
        return freshness.age().toMillis();
    }

    @Override
    public String toString() {
        return "TargetedItemTypeDetail[repository=" + repositoryId
                + ", itemType=" + itemTypeName() + " (#" + itemTypeId + ")"
                + ", captured=" + capturedAt
                + ", anchor=" + anchorDate
                + ", " + status() + ", logicalItems=" + logicalItems() + "]";
    }
}
