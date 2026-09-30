package com.mraibo.cminsight.report;

import com.mraibo.cminsight.history.HistoryDetail;
import com.mraibo.cminsight.history.HistoryId;
import com.mraibo.cminsight.history.HistoryItemType;
import com.mraibo.cminsight.history.HistoryMetric;
import com.mraibo.cminsight.history.HistorySummary;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.statistics.ItemTypeStatistics;
import com.mraibo.cminsight.statistics.MetricValue;
import com.mraibo.cminsight.statistics.StatisticsSnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The ONE immutable report input, and the only thing a renderer may read.
 *
 * <h2>One model, one captured shape</h2>
 *
 * <p>A report is built from either the current completed full snapshot or one stored history snapshot
 * (Goal 04 section 6). Both paths produce this record, and its content is the frozen
 * {@link HistoryDetail} - the same immutable captured shape persistence uses - plus the two facts that say
 * what the artifact is: which {@link ReportSource} produced it and when it was generated. That choice is
 * deliberate rather than incidental:
 *
 * <ul>
 *   <li><strong>A format cannot see anything this model does not carry.</strong> Every renderer signature
 *       takes a {@code ReportModel} and nothing else. There is no store, no snapshot holder, no session and
 *       no repository handle anywhere in the type, so "render" has no seam through which a hidden read
 *       could occur.</li>
 *   <li><strong>Absence stays absent.</strong> A row's metrics are {@link HistoryMetric}s, whose canonical
 *       constructor refuses to carry a number unless the state is {@code AVAILABLE}. A report therefore
 *       cannot physically render an unmeasured metric as {@code 0} - the zero would have to exist in the
 *       model first, and the model cannot hold it.</li>
 *   <li><strong>A live report and a historical report cannot disagree about coverage.</strong> Both carry a
 *       {@link HistorySummary}, whose counts travel with the total they qualify.</li>
 * </ul>
 *
 * <h2>Where a live model's values come from</h2>
 *
 * <p>{@link #fromSnapshot} maps an already published {@code StatisticsSnapshot} plus an
 * already captured {@link ReportContext}. Every component is copied from one of those two arguments: no
 * component is derived by a query, and nothing is filled in later. See that method for the exact mapping.
 *
 * <p>The live path carries a {@link HistoryId} that names nothing in the store. It exists because the
 * captured shape requires an identity and inventing a second summary type to avoid it would be a worse
 * trade; {@link ReportSource#LIVE_SNAPSHOT} states plainly that the snapshot was never persisted, and no
 * code anywhere treats that identity as a store key.
 *
 * @param source      where the captured values came from
 * @param generatedAt when this report model was captured, fixed for the life of the artifact
 * @param detail      the immutable captured snapshot: identity, timing, coverage, totals and rows
 */
public record ReportModel(ReportSource source, Instant generatedAt, HistoryDetail detail) {

    /** The fixed title every artifact states; no dynamic value may become the title. */
    public static final String TITLE = "CM Insight ItemType report";

    public ReportModel {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(detail, "detail");
    }

    /**
     * Captures the current completed full snapshot as a report input.
     *
     * <p>The mapping, component by component - every one of them a copy, none of them a query:
     *
     * <ul>
     *   <li>repository id, captured/scan-start instants, scan duration, anchor date, scan id, partial-failure
     *       count, completeness and the logical-items total come from the
     *       {@link StatisticsSnapshot} and its published totals. They are the snapshot's own answers, so a
     *       report cannot contradict the dashboard's.</li>
     *   <li>repository display name and database vendor come from the caller's {@link ReportContext}: the
     *       snapshot does not carry them and this method must not look them up.</li>
     *   <li>each row comes from one {@link ItemTypeStatistics} in the snapshot's frozen order, with the
     *       ItemType name, business classification and retention-policy name as the scan captured them; the
     *       matching {@link ItemTypeSummary} in the context is consulted only when the captured row left one
     *       of those empty, and an entry that is absent there simply stays empty - "not captured", never a
     *       lookup.</li>
     *   <li>each metric is copied from the live {@link MetricValue}, preserving the state exactly.</li>
     * </ul>
     *
     * @param snapshot    the latest completed snapshot; the caller must not pass a scan that has not
     *                    published one
     * @param context     the frozen safe metadata context captured with that snapshot
     * @param generatedAt the instant this report is being generated
     */
    public static ReportModel fromSnapshot(StatisticsSnapshot snapshot, ReportContext context,
                                           Instant generatedAt) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(generatedAt, "generatedAt");

        Map<Integer, ItemTypeSummary> metadataById = new HashMap<>();
        for (ItemTypeSummary summary : context.metadata()) {
            metadataById.put(summary.itemTypeId(), summary);
        }

        List<HistoryItemType> rows = new ArrayList<>(snapshot.perItemType().size());
        for (ItemTypeStatistics result : snapshot.perItemType()) {
            rows.add(toRow(result, metadataById.get(result.itemTypeId())));
        }

        HistorySummary summary = new HistorySummary(
                liveIdentity(generatedAt),
                snapshot.repositoryId(),
                context.repositoryDisplayName(),
                context.databaseVendor(),
                snapshot.capturedAt(),
                snapshot.scanStartedAt(),
                snapshot.scanDurationMs(),
                snapshot.anchorDate(),
                snapshot.scanId(),
                rows.size(),
                snapshot.partialFailureCount(),
                snapshot.complete(),
                snapshot.totals().logicalItemsTotal());

        return new ReportModel(ReportSource.LIVE_SNAPSHOT, generatedAt, new HistoryDetail(summary, rows, ""));
    }

    /**
     * Captures one stored history snapshot as a report input, with no live read of any kind.
     *
     * <p>This is the whole reason history exists: the detail already carries the repository, its display
     * name, the vendor, the timing, the coverage, the optional warning and every row, so a historical report
     * is a pure function of a value that is already in memory. The parameter list is the proof - there is no
     * store to call. A field the snapshot did not capture therefore renders as unknown, because there is
     * nothing in this method that could go and find it.
     */
    public static ReportModel fromHistory(HistoryDetail detail, Instant generatedAt) {
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(generatedAt, "generatedAt");
        return new ReportModel(ReportSource.HISTORY, generatedAt, detail);
    }

    /** The repository the captured values belong to. */
    public String repositoryId() {
        return detail.summary().repositoryId();
    }

    /** The safe display name captured with the snapshot, falling back to the repository id. */
    public String repositoryDisplayName() {
        return detail.summary().displayName();
    }

    /** The database vendor captured with the snapshot, or empty when it was not captured. */
    public String databaseVendor() {
        return detail.summary().databaseVendor();
    }

    /** When the scan published the snapshot this report describes. */
    public Instant capturedAt() {
        return detail.summary().capturedAt();
    }

    /** When the scan that produced the snapshot started. */
    public Instant scanStartedAt() {
        return detail.summary().scanStartedAt();
    }

    /** How long that scan took. */
    public long scanDurationMs() {
        return detail.summary().scanDurationMs();
    }

    /** The single database anchor the windows were computed against, when one was captured. */
    public Optional<LocalDate> anchor() {
        return detail.summary().anchor();
    }

    /** The context-local scan sequence, diagnostic metadata only and never an identity. */
    public long scanId() {
        return detail.summary().scanId();
    }

    /** How many ItemTypes the frozen list contained. */
    public int itemTypeCount() {
        return detail.summary().itemTypeCount();
    }

    /** How many ItemTypes were partial or failed. */
    public int partialFailureCount() {
        return detail.summary().partialFailureCount();
    }

    /** True only when every frozen ItemType was measured completely. */
    public boolean complete() {
        return detail.summary().complete();
    }

    /** The distinct-ItemID total over the measured ItemTypes; only a complete total when {@link #complete()}. */
    public long logicalItemsTotal() {
        return detail.summary().logicalItemsTotal();
    }

    /** The captured rows, in the frozen order the scan used. */
    public List<HistoryItemType> itemTypes() {
        return detail.itemTypes();
    }

    /** A sanitized, value-free note recorded at capture time, or empty. */
    public String warning() {
        return detail.warning();
    }

    /** True when a capture-time warning must be shown in the artifact. */
    public boolean hasWarning() {
        return detail.hasWarning();
    }

    /** True when this report describes a stored snapshot rather than the current one. */
    public boolean historical() {
        return source.historical();
    }

    /** The fixed artifact title. */
    public String title() {
        return TITLE;
    }

    private static HistoryItemType toRow(ItemTypeStatistics result, ItemTypeSummary metadata) {
        String name = result.itemTypeName().isEmpty() && metadata != null
                ? metadata.name()
                : result.itemTypeName();
        String classification = result.businessClassification().isEmpty() && metadata != null
                ? metadata.businessClassification()
                : result.businessClassification();
        // The scan freezes the retention policy alongside the result, so that is the first source; the captured
        // metadata context is the fallback for a snapshot whose producer had no metadata list at all. Both are
        // CAPTURED text - neither is a lookup, which is why an ItemType that was never classified renders as
        // none rather than being re-derived from live metadata that may have changed since the scan.
        String retentionPolicy = result.retentionPolicyName();
        if (retentionPolicy.isEmpty() && metadata != null) {
            retentionPolicy = metadata.retentionPolicyName();
        }
        return new HistoryItemType(
                result.itemTypeId(),
                name,
                classification,
                retentionPolicy,
                statusOf(result.status()),
                metric(result.logicalItems()),
                metric(result.createdToday()),
                metric(result.createdLast7Days()),
                metric(result.createdLast30Days()),
                metric(result.createdCurrentYear()),
                Math.max(result.durationMs(), 0L),
                result.errorMessage());
    }

    private static HistoryItemType.Status statusOf(ItemTypeStatistics.Status status) {
        return switch (status) {
            case OK -> HistoryItemType.Status.OK;
            case PARTIAL -> HistoryItemType.Status.PARTIAL;
            case ERROR -> HistoryItemType.Status.ERROR;
        };
    }

    /**
     * Copies a live metric into the captured shape, state for state.
     *
     * <p>The one behaviour worth stating: an {@code AVAILABLE} metric is the only branch that reads a
     * number, so a non-available metric keeps its absence and its sanitized reason and cannot arrive in a
     * report as a zero.
     */
    private static HistoryMetric metric(MetricValue value) {
        return switch (value.availability()) {
            case AVAILABLE -> HistoryMetric.available(value.value());
            case UNAVAILABLE -> HistoryMetric.unavailable(value.reason());
            case ERROR -> HistoryMetric.error(value.reason());
        };
    }

    /**
     * A caller-scoped placeholder identity for a snapshot that was never persisted.
     *
     * <p>Derived only from {@code generatedAt}, so it is deterministic for a given capture instant and
     * unique enough to tell two live reports apart. It is not a store key and nothing looks it up.
     */
    private static HistoryId liveIdentity(Instant generatedAt) {
        return new HistoryId("live" + Long.toString(Math.max(generatedAt.toEpochMilli(), 0L), 36));
    }
}
