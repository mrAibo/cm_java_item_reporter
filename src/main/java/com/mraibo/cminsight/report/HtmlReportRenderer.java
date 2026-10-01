package com.mraibo.cminsight.report;

import com.mraibo.cminsight.history.HistoryItemType;
import com.mraibo.cminsight.history.HistoryMetric;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Renders the immutable model as one standalone, offline, fully escaped HTML document.
 *
 * <h2>Why this is JDK-only and self-contained</h2>
 *
 * <p>Goal 04 requires the operator Web UI to work with no CDN, no external fonts, no analytics scripts and
 * no runtime Internet access, and a report artifact is the same object one step further out: it is opened
 * later, possibly on a machine with no network at all. This renderer therefore emits a single file with an
 * inline style block, no {@code <script>}, no {@code <link>}, no {@code <img>}, no {@code url(...)} and no
 * entity that could load anything. There is nothing in the document that can make a request.
 *
 * <h2>Escaping, and what is not escaped because it is not written</h2>
 *
 * <p>ItemType names, business classifications, retention-policy names, captured failure reasons and the
 * capture-time warning all come from IBM CM, a customer's metadata or a database, so they are hostile by
 * assumption. Every one of them is written through {@link ReportText#html(String)} into an element this
 * class wrote, and no dynamic value is ever written into an attribute, a style block or a script - the three
 * places where escaping alone is not a guarantee. The style block is a compile-time constant.
 *
 * <p>A defensive {@code Content-Security-Policy} meta element is included even though the document contains
 * no script and no external reference: it costs one line and means the artifact is inert by policy as well as
 * by construction if it is ever served from the application itself.
 *
 * <h2>Absence is visible</h2>
 *
 * <p>A metric that was not measured prints {@code unavailable} (with its sanitized reason when it has one)
 * and a failed one prints {@code error}. Neither prints a number, because {@link HistoryMetric} has no number
 * to give: a zero in this table would be indistinguishable from a measurement, which is the one thing a
 * report must never do.
 */
public final class HtmlReportRenderer implements ReportRenderer {

    /** The complete stylesheet. No dynamic value can reach it, which is why it can be a constant. */
    private static final String STYLE = """
            :root { color-scheme: light dark; }
            body { font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
                   margin: 0; padding: 24px; line-height: 1.45; }
            main { max-width: 1100px; margin: 0 auto; }
            h1 { font-size: 1.5rem; margin: 0 0 4px; }
            h2 { font-size: 1.1rem; margin: 28px 0 8px; }
            p.subtitle { margin: 0 0 4px; opacity: .8; }
            table { border-collapse: collapse; width: 100%; margin-top: 4px; }
            th, td { border: 1px solid #b9b9b9; padding: 4px 8px; text-align: left; vertical-align: top; }
            th { background: rgba(127, 127, 127, .14); font-weight: 600; }
            table.kv { max-width: 720px; }
            table.kv th { width: 16rem; }
            td.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
            td.absent { color: #8a6d3b; font-style: italic; }
            .notice { border: 1px solid #c9a227; background: rgba(201, 162, 39, .12);
                      padding: 8px 12px; margin: 12px 0; border-radius: 4px; }
            .status-error { color: #a94442; font-weight: 600; }
            .status-partial { color: #8a6d3b; font-weight: 600; }
            footer { margin-top: 32px; font-size: .85rem; opacity: .75; }
            """;

    /** The document's fixed title; no captured value may become it. */
    private static final String DOCUMENT_TITLE = ReportModel.TITLE;

    public HtmlReportRenderer() {
    }

    @Override
    public ReportFormat format() {
        return ReportFormat.HTML;
    }

    @Override
    public byte[] render(ReportModel model) {
        Objects.requireNonNull(model, "model");
        StringBuilder document = new StringBuilder(16 * 1024);
        document.append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none';")
                .append(" style-src 'unsafe-inline'; img-src 'none'; object-src 'none'; base-uri 'none';")
                .append(" form-action 'none'; frame-ancestors 'none'\">\n")
                .append("<title>").append(ReportText.html(DOCUMENT_TITLE)).append("</title>\n")
                .append("<style>\n").append(STYLE).append("</style>\n")
                .append("</head>\n<body>\n<main>\n");

        document.append("<h1>").append(ReportText.html(DOCUMENT_TITLE)).append("</h1>\n")
                .append("<p class=\"subtitle\">Built from the ")
                .append(ReportText.html(model.source().label())).append(".</p>\n");

        appendNotices(document, model);
        appendProvenance(document, model);
        appendTotals(document, model);
        appendItemTypes(document, model);

        document.append("<footer><p>Generated by CM Insight at ")
                .append(ReportText.html(model.generatedAt().toString()))
                .append(". This document is static: it contains no scripts, no external resources and no")
                .append(" document content. Values this snapshot did not capture are shown as")
                .append(" <em>unavailable</em> rather than as zero.</p></footer>\n")
                .append("</main>\n</body>\n</html>\n");

        return document.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendNotices(StringBuilder document, ReportModel model) {
        if (!model.complete()) {
            document.append("<p class=\"notice\"><strong>Partial result.</strong> ")
                    .append(ReportText.html(Integer.toString(model.partialFailureCount())))
                    .append(" of ").append(ReportText.html(Integer.toString(model.itemTypeCount())))
                    .append(" ItemTypes were not measured completely. The total below covers only the")
                    .append(" ItemTypes that were measured, so it is a subtotal of the frozen list and not")
                    .append(" the repository's item count.</p>\n");
        }
        if (model.hasWarning()) {
            document.append("<p class=\"notice\"><strong>Captured warning.</strong> ")
                    .append(ReportText.html(model.warning())).append("</p>\n");
        }
    }

    private static void appendProvenance(StringBuilder document, ReportModel model) {
        document.append("<section>\n<h2>Provenance</h2>\n<table class=\"kv\">\n<tbody>\n");
        appendKeyValue(document, "Report source", model.source().label());
        appendKeyValue(document, "Repository", present(model.repositoryDisplayName()));
        if (!model.repositoryId().equals(model.repositoryDisplayName())) {
            appendKeyValue(document, "Repository id", present(model.repositoryId()));
        }
        appendKeyValue(document, "Database vendor", present(model.databaseVendor()));
        appendKeyValue(document, "Snapshot captured", model.capturedAt().toString());
        appendKeyValue(document, "Scan started", model.scanStartedAt().toString());
        appendKeyValue(document, "Scan duration", model.scanDurationMs() + " ms");
        appendKeyValue(document, "Window anchor", model.anchor().map(LocalDate::toString).orElse(ReportText.UNKNOWN));
        appendKeyValue(document, "Scan sequence", Long.toString(model.scanId()));
        appendKeyValue(document, "Report generated", model.generatedAt().toString());
        document.append("</tbody>\n</table>\n</section>\n");
    }

    private static void appendTotals(StringBuilder document, ReportModel model) {
        document.append("<section>\n<h2>Totals</h2>\n<table class=\"kv\">\n<tbody>\n");
        appendKeyValue(document, "ItemTypes in the frozen list", Integer.toString(model.itemTypeCount()));
        appendKeyValue(document, "ItemTypes not measured completely", Integer.toString(model.partialFailureCount()));
        appendKeyValue(document, "Complete coverage", model.complete() ? "yes" : "no");
        appendKeyValue(document, "Logical items (measured ItemTypes)",
                Long.toString(model.logicalItemsTotal()));
        appendKeyValue(document, "Versions", ReportText.NOT_MEASURED + " in this goal");
        appendKeyValue(document, "Parts", ReportText.NOT_MEASURED + " in this goal");
        document.append("</tbody>\n</table>\n</section>\n");
    }

    private static void appendItemTypes(StringBuilder document, ReportModel model) {
        document.append("<section>\n<h2>ItemTypes</h2>\n")
                .append("<table>\n<thead>\n<tr>")
                .append("<th scope=\"col\">Id</th>")
                .append("<th scope=\"col\">Name</th>")
                .append("<th scope=\"col\">Classification</th>")
                .append("<th scope=\"col\">Retention policy</th>")
                .append("<th scope=\"col\">Status</th>")
                .append("<th scope=\"col\">Logical items</th>")
                .append("<th scope=\"col\">Created today</th>")
                .append("<th scope=\"col\">Created last 7 days</th>")
                .append("<th scope=\"col\">Created last 30 days</th>")
                .append("<th scope=\"col\">Created current year</th>")
                .append("<th scope=\"col\">Duration (ms)</th>")
                .append("<th scope=\"col\">Note</th>")
                .append("</tr>\n</thead>\n<tbody>\n");
        List<HistoryItemType> rows = model.itemTypes();
        if (rows.isEmpty()) {
            document.append("<tr><td colspan=\"12\">This snapshot covered no ItemTypes.</td></tr>\n");
        }
        for (HistoryItemType row : rows) {
            document.append("<tr>")
                    .append(cell(Integer.toString(row.itemTypeId())))
                    .append(cell(present(row.itemTypeName())))
                    .append(cell(ReportText.html(row.classificationLabel())))
                    .append(cell(ReportText.html(ReportText.retentionOrNone(row.retentionPolicyName()))))
                    .append(statusCell(row))
                    .append(metricCell(row.logicalItems()))
                    .append(metricCell(row.createdToday()))
                    .append(metricCell(row.createdLast7Days()))
                    .append(metricCell(row.createdLast30Days()))
                    .append(metricCell(row.createdCurrentYear()))
                    .append(cell(Long.toString(row.durationMs())))
                    .append(cell(row.reason().isEmpty() ? "" : ReportText.html(row.reason())))
                    .append("</tr>\n");
        }
        document.append("</tbody>\n</table>\n</section>\n");
    }

    private static String statusCell(HistoryItemType row) {
        String style = switch (row.status()) {
            case OK -> "";
            case PARTIAL -> " class=\"status-partial\"";
            case ERROR -> " class=\"status-error\"";
        };
        return "<td" + style + ">" + ReportText.html(row.status().name()) + "</td>";
    }

    /**
     * One metric cell.
     *
     * <p>Only an available metric prints a number. Everything else prints an explicit marker, because the
     * whole point of the frozen metric shape is that an absent count must never look like a measured zero.
     */
    private static String metricCell(HistoryMetric metric) {
        if (metric.isAvailable()) {
            long value = metric.value();
            if (ReportText.isSafeSpreadsheetNumber(value)) {
                return "<td class=\"num\">" + ReportText.digits(value) + "</td>";
            }
            return "<td class=\"num\">" + ReportText.html(Long.toString(value)) + "</td>";
        }
        String marker = metric.state() == HistoryMetric.State.ERROR ? "error" : ReportText.NOT_MEASURED;
        String reason = metric.reason().isEmpty() ? "" : ": " + ReportText.html(metric.reason());
        return "<td class=\"num absent\">" + marker + reason + "</td>";
    }

    private static String cell(String escapedValue) {
        return "<td>" + escapedValue + "</td>";
    }

    /**
     * A captured text value, or the explicit unknown marker when it was not captured.
     *
     * <p>The marker is chosen by {@link ReportText#orUnknown(String)} so every format spells an uncaptured
     * value the same way; this method only adds the escaping that is specific to HTML.
     */
    private static String present(String value) {
        return ReportText.html(ReportText.orUnknown(value));
    }

    /**
     * One key/value row of a provenance table.
     *
     * <p>The value is already escaped by the caller, and the label is a constant this class wrote; no
     * dynamic value ever reaches an attribute, which is why this class writes no attribute at all.
     */
    private static void appendKeyValue(StringBuilder document, String label, String escapedValue) {
        document.append("<tr><th scope=\"row\">").append(ReportText.html(label)).append("</th><td>")
                .append(escapedValue).append("</td></tr>\n");
    }
}
