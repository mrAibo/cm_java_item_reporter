package com.mraibo.cminsight.report;

import com.mraibo.cminsight.history.HistoryItemType;
import com.mraibo.cminsight.history.HistoryMetric;

import java.util.List;
import java.util.Objects;

/**
 * Renders the immutable model as a real OOXML workbook, using {@link OoxmlWorkbook}.
 *
 * <h2>The deliverable this class answers</h2>
 *
 * <p>Goal 04 requires XLSX to be a genuine workbook or an explicitly unavailable format, never CSV renamed to
 * {@code .xlsx} and never a fake archive. This is the genuine one: two sheets, {@code Overview} (provenance,
 * coverage and the two totals this goal can state) and {@code ItemTypes} (the captured rows), written as a
 * minimal but complete OOXML package by {@link OoxmlWorkbook}.
 *
 * <h2>The four properties a workbook must not violate</h2>
 *
 * <ul>
 *   <li><strong>Text is text.</strong> An ItemType name, classification, retention policy or captured reason
 *       becomes an inline string cell. A hostile value starting with {@code =} is therefore stored as text,
 *       because there is no cell shape in this renderer that could hold a formula.</li>
 *   <li><strong>Metrics are numbers.</strong> A measured, non-negative metric becomes a numeric cell with the
 *       {@code #,##0} format, so a spreadsheet user can sum a column. The one non-negative rule is shared with
 *       the CSV renderer through {@link ReportText#isSafeSpreadsheetNumber(long)}, so the two formats cannot
 *       disagree about one value.</li>
 *   <li><strong>No formulas and no macros.</strong> {@link OoxmlWorkbook} has no method that writes an
 *       {@code <f>} element and writes no macro-enabled part.</li>
 *   <li><strong>No external relationships.</strong> Every part the workbook references is inside the
 *       package.</li>
 * </ul>
 *
 * <h2>Absence</h2>
 *
 * <p>A metric that was not measured is the text {@code UNAVAILABLE}; a failed one is {@code ERROR}; each
 * carries its sanitized reason when it has one. A number is written only when the frozen metric shape actually
 * produced one, so no cell in this workbook can present an unmeasured count as zero.
 */
public final class XlsxReportRenderer implements ReportRenderer {

    private static final String SHEET_OVERVIEW = "Overview";
    private static final String SHEET_ITEM_TYPES = "ItemTypes";

    private static final List<String> COLUMNS = List.of(
            "ItemType id",
            "ItemType name",
            "Business classification",
            "Retention policy",
            "Status",
            "Logical items",
            "Created today",
            "Created last 7 days",
            "Created last 30 days",
            "Created current year",
            "Duration (ms)",
            "Note");

    public XlsxReportRenderer() {
    }

    @Override
    public ReportFormat format() {
        return ReportFormat.XLSX;
    }

    @Override
    public byte[] render(ReportModel model) {
        Objects.requireNonNull(model, "model");
        OoxmlWorkbook workbook = new OoxmlWorkbook();
        appendOverview(workbook.sheet(SHEET_OVERVIEW), model);
        appendItemTypes(workbook.sheet(SHEET_ITEM_TYPES), model);
        return workbook.toBytes();
    }

    private static void appendOverview(OoxmlWorkbook.Sheet sheet, ReportModel model) {
        sheet.row(bold("Report"), text(model.title()));
        sheet.row(bold("Source"), text(model.source().label()));
        sheet.row(bold("Generated at"), text(model.generatedAt().toString()));
        sheet.row(bold("Repository"), text(model.repositoryId()));
        sheet.row(bold("Repository name"), text(model.repositoryDisplayName()));
        sheet.row(bold("Database vendor"), text(ReportText.orUnknown(model.databaseVendor())));
        sheet.row(bold("Snapshot captured"), text(model.capturedAt().toString()));
        sheet.row(bold("Scan started"), text(model.scanStartedAt().toString()));
        sheet.row(bold("Scan duration (ms)"), number(model.scanDurationMs()));
        sheet.row(bold("Window anchor"),
                text(model.anchor().map(java.time.LocalDate::toString).orElse(ReportText.UNKNOWN)));
        sheet.row(bold("Scan sequence"), number(model.scanId()));
        sheet.row(bold("ItemTypes in the frozen list"), number(model.itemTypeCount()));
        sheet.row(bold("ItemTypes not measured completely"), number(model.partialFailureCount()));
        sheet.row(bold("Complete coverage"), text(model.complete() ? "yes" : "no"));
        sheet.row(bold("Logical items (measured ItemTypes)"), number(model.logicalItemsTotal()));
        sheet.row(bold("Versions"), text(ReportText.NOT_MEASURED + " in this goal"));
        sheet.row(bold("Parts"), text(ReportText.NOT_MEASURED + " in this goal"));
        if (model.hasWarning()) {
            sheet.row(bold("Captured warning"), text(model.warning()));
        }
    }

    private static void appendItemTypes(OoxmlWorkbook.Sheet sheet, ReportModel model) {
        OoxmlWorkbook.Cell[] header = new OoxmlWorkbook.Cell[COLUMNS.size()];
        for (int index = 0; index < COLUMNS.size(); index++) {
            header[index] = bold(COLUMNS.get(index));
        }
        sheet.row(header);

        if (model.itemTypes().isEmpty()) {
            sheet.row(text("This snapshot covered no ItemTypes."));
            return;
        }
        for (HistoryItemType row : model.itemTypes()) {
            sheet.row(
                    number(row.itemTypeId()),
                    text(ReportText.orUnknown(row.itemTypeName())),
                    text(row.classificationLabel()),
                    text(ReportText.retentionOrNone(row.retentionPolicyName())),
                    text(row.status().name()),
                    metric(row.logicalItems()),
                    metric(row.createdToday()),
                    metric(row.createdLast7Days()),
                    metric(row.createdLast30Days()),
                    metric(row.createdCurrentYear()),
                    number(row.durationMs()),
                    text(row.reason()));
        }
    }

    /**
     * One metric cell: a numeric cell when a number was measured, a text cell otherwise.
     *
     * <p>A measured but negative value is written as text rather than as a number, matching the CSV renderer
     * exactly: a metric is a count, and both spreadsheet formats must treat one value the same way.
     */
    private static OoxmlWorkbook.Cell metric(HistoryMetric metric) {
        if (metric.isAvailable()) {
            long value = metric.value();
            return ReportText.isSafeSpreadsheetNumber(value)
                    ? number(value)
                    : text(Long.toString(value));
        }
        String marker = metric.state() == HistoryMetric.State.ERROR ? "ERROR" : "UNAVAILABLE";
        return text(metric.reason().isEmpty() ? marker : marker + ": " + metric.reason());
    }

    private static OoxmlWorkbook.Cell text(String value) {
        return OoxmlWorkbook.Cell.text(value);
    }

    private static OoxmlWorkbook.Cell bold(String value) {
        return OoxmlWorkbook.Cell.bold(value);
    }

    private static OoxmlWorkbook.Cell number(long value) {
        return OoxmlWorkbook.Cell.number(value);
    }
}
