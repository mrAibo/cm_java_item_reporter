package com.mraibo.cminsight.report;

import com.mraibo.cminsight.history.HistoryItemType;
import com.mraibo.cminsight.history.HistoryMetric;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Renders the immutable model as RFC 4180 CSV that a spreadsheet can open and sum.
 *
 * <h2>Shape</h2>
 *
 * <p>One file, CRLF line endings, three parts in order: a block of {@code key,value} provenance rows (what
 * this report is, which snapshot it came from and how complete that snapshot was), one empty line, and then
 * the ItemType table - a header row followed by one row per captured ItemType. The provenance block is there
 * because a CSV that does not say what it describes is an anonymous list of numbers once it has been moved,
 * mailed or opened a week later; the blank line is there so a reader can tell the two parts apart.
 *
 * <p>No byte-order mark is written. A BOM is a presentation workaround, and it would make the first field of
 * the first line not the first field of the first line for every parser that is not a spreadsheet.
 *
 * <h2>Formula injection</h2>
 *
 * <p>Text is defended by {@link ReportText#csvField(String, char)}: a value whose first character is
 * {@code =}, {@code +}, {@code -}, {@code @}, tab or carriage return is prefixed with a single quote and
 * always quoted, so a hostile ItemType name or retention-policy name cannot become a live formula when the
 * file is opened. A measured metric does not go through that path at all - it is written by
 * {@link ReportText#digits(long)} as bare digits, because the point of the metric columns is that an
 * operator can sum them, and a number cannot begin with a formula character.
 *
 * <h2>Absence</h2>
 *
 * <p>A metric that was not measured is the text {@code UNAVAILABLE} and a failed one is {@code ERROR}, each
 * followed by {@code : <sanitized reason>} when the metric carries one. Never {@code 0}: an unmeasured count
 * rendered as zero would be summed as data by the very spreadsheet this format exists to support.
 */
public final class CsvReportRenderer implements ReportRenderer {

    /** The RFC 4180 delimiter. Kept as a constant so quoting and writing cannot disagree. */
    public static final char DELIMITER = ',';

    /** RFC 4180 line ending. */
    private static final String LINE_ENDING = "\r\n";

    /** The table header, in the same order the row writer below emits values. */
    private static final List<String> COLUMNS = List.of(
            "itemTypeId",
            "itemTypeName",
            "businessClassification",
            "retentionPolicy",
            "status",
            "logicalItems",
            "createdToday",
            "createdLast7Days",
            "createdLast30Days",
            "createdCurrentYear",
            "durationMs",
            "reason");

    public CsvReportRenderer() {
    }

    @Override
    public ReportFormat format() {
        return ReportFormat.CSV;
    }

    @Override
    public byte[] render(ReportModel model) {
        Objects.requireNonNull(model, "model");
        StringBuilder csv = new StringBuilder(8 * 1024);
        appendProvenance(csv, model);
        csv.append(LINE_ENDING);
        appendHeader(csv);
        appendRows(csv, model.itemTypes());
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendProvenance(StringBuilder csv, ReportModel model) {
        row(csv, text("report"), text(model.title()));
        row(csv, text("source"), text(model.source().label()));
        row(csv, text("generatedAt"), text(model.generatedAt().toString()));
        row(csv, text("repository"), text(model.repositoryId()));
        row(csv, text("repositoryName"), text(model.repositoryDisplayName()));
        row(csv, text("databaseVendor"), text(ReportText.orUnknown(model.databaseVendor())));
        row(csv, text("capturedAt"), text(model.capturedAt().toString()));
        row(csv, text("scanStartedAt"), text(model.scanStartedAt().toString()));
        row(csv, text("scanDurationMs"), number(model.scanDurationMs()));
        row(csv, text("anchor"), text(model.anchor().map(LocalDate::toString).orElse(ReportText.UNKNOWN)));
        row(csv, text("scanId"), number(model.scanId()));
        row(csv, text("itemTypeCount"), number(model.itemTypeCount()));
        row(csv, text("partialFailureCount"), number(model.partialFailureCount()));
        row(csv, text("complete"), text(model.complete() ? "yes" : "no"));
        row(csv, text("logicalItemsTotal"), number(model.logicalItemsTotal()));
        if (model.hasWarning()) {
            row(csv, text("warning"), text(model.warning()));
        }
    }

    private static void appendHeader(StringBuilder csv) {
        StringBuilder header = new StringBuilder();
        for (String column : COLUMNS) {
            if (!header.isEmpty()) {
                header.append(DELIMITER);
            }
            header.append(ReportText.csvField(column, DELIMITER));
        }
        csv.append(header).append(LINE_ENDING);
    }

    private static void appendRows(StringBuilder csv, List<HistoryItemType> rows) {
        for (HistoryItemType row : rows) {
            StringBuilder line = new StringBuilder(128);
            appendField(line, Integer.toString(row.itemTypeId()));
            appendField(line, ReportText.csvField(ReportText.orUnknown(row.itemTypeName()), DELIMITER));
            appendField(line, ReportText.csvField(row.classificationLabel(), DELIMITER));
            appendField(line, ReportText.csvField(ReportText.retentionOrNone(row.retentionPolicyName()),
                    DELIMITER));
            appendField(line, ReportText.csvField(row.status().name(), DELIMITER));
            appendField(line, metric(row.logicalItems()));
            appendField(line, metric(row.createdToday()));
            appendField(line, metric(row.createdLast7Days()));
            appendField(line, metric(row.createdLast30Days()));
            appendField(line, metric(row.createdCurrentYear()));
            appendField(line, ReportText.digits(row.durationMs()));
            appendField(line, ReportText.csvField(row.reason(), DELIMITER));
            csv.append(line).append(LINE_ENDING);
        }
    }

    /**
     * One metric field: bare digits when it was measured, an explicit marker when it was not.
     *
     * <p>This is the single place the CSV renderer decides between a number and a word, so "the unavailable
     * case is never numeric" cannot drift between the five metric columns.
     */
    private static String metric(HistoryMetric metric) {
        if (metric.isAvailable()) {
            long value = metric.value();
            return ReportText.isSafeSpreadsheetNumber(value)
                    ? ReportText.digits(value)
                    // A negative measurement is not a count and would begin with '-': it is written as text
                    // (and therefore fenced), so no spreadsheet can read it as a formula.
                    : ReportText.csvField(Long.toString(value), DELIMITER);
        }
        String marker = metric.state() == HistoryMetric.State.ERROR ? "ERROR" : "UNAVAILABLE";
        return ReportText.csvField(metric.reason().isEmpty() ? marker : marker + ": " + metric.reason(),
                DELIMITER);
    }

    private static void row(StringBuilder csv, String key, String value) {
        csv.append(key).append(DELIMITER).append(value).append(LINE_ENDING);
    }

    private static void appendField(StringBuilder line, String alreadyEscaped) {
        if (!line.isEmpty()) {
            line.append(DELIMITER);
        }
        line.append(alreadyEscaped);
    }

    private static String text(String value) {
        return ReportText.csvField(value, DELIMITER);
    }

    private static String number(long value) {
        return ReportText.digits(value);
    }
}
