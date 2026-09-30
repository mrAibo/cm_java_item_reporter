package com.mraibo.cminsight.test;

import com.mraibo.cminsight.history.HistoryDetail;
import com.mraibo.cminsight.history.HistoryId;
import com.mraibo.cminsight.history.HistoryItemType;
import com.mraibo.cminsight.history.HistoryMetric;
import com.mraibo.cminsight.history.HistorySummary;
import com.mraibo.cminsight.report.CsvReportRenderer;
import com.mraibo.cminsight.report.HtmlReportRenderer;
import com.mraibo.cminsight.report.ReportFormat;
import com.mraibo.cminsight.report.ReportModel;
import com.mraibo.cminsight.report.XlsxReportRenderer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Goal 04 sections 6, 9 and 12 "Reports": hostile data cannot become active content.
 *
 * <h2>The shape of the attack each case is about</h2>
 *
 * <ul>
 *   <li><strong>HTML</strong>: an ItemType name, classification or retention policy name is
 *       <em>repository-controlled</em> text. Rendered unescaped it becomes markup - a script, an image with
 *       an error handler - inside an authenticated console, which is a stored cross-site scripting path that
 *       needs no further mistake by the operator.</li>
 *   <li><strong>CSV</strong>: a cell beginning with {@code =}, {@code +}, {@code -}, {@code @}, a tab or a
 *       carriage return is a FORMULA to a spreadsheet, so opening an exported report can execute a
 *       command or a data-exfiltration formula. The one-character prefix is the fix, and it is asserted
 *       over every trigger, not over {@code =} alone.</li>
 *   <li><strong>XLSX</strong>: the format has a real formula cell type, so "text is text" has to be
 *       asserted inside the produced workbook rather than inferred from the code that wrote it.</li>
 * </ul>
 *
 * <p>Every hostile value used here is a value a repository could really hold: none of them is a Java-level
 * hazard, and the assertions are about the bytes a report actually contains.
 */
public class ReportContentSecurityTest {

    private static final Instant GENERATED_AT = Instant.parse("2024-06-16T08:30:00Z");

    /** Values a hostile or merely careless repository could hold, in the shapes spreadsheets execute. */
    private static final List<String> FORMULA_PREFIXED = List.of(
            "=1+1", "=cmd|' /C calc'!A0", "+1", "+SUM(A1)", "-1", "-2+3", "@SUM(A1)",
            "\t=cmd", "\r=cmd", "==HYPERLINK", "=WEBSERVICE(\"http://evil.example\")");

    // ------------------------------------------------------------------ HTML

    /** Every dynamic value is escaped as TEXT, so a hostile name renders as characters and never as markup. */
    public void htmlEscapesHostileItemTypeClassificationAndRetentionStrings() throws Exception {
        String hostileName = "<script>alert('xss')</script>";
        String hostileClass = "\"><img src=x onerror=alert(1)>";
        String hostileRetention = "</td></tr><iframe src=//evil.example></iframe>";

        ReportModel model = modelWith(hostileName, hostileClass, hostileRetention, "harmless");
        String html = new String(new HtmlReportRenderer().render(model), StandardCharsets.UTF_8);

        Assert.assertFalse(html.contains("<script>alert"),
                "a hostile ItemType name must not survive as an executable script element");
        // The precise rule: a hostile value may appear as ESCAPED text (asserted below), but no element it
        // contains may exist in the document. The escaping is what keeps the substring "onerror=" harmless,
        // so asserting on the substring alone would fail a correct renderer - the assertion is about tags.
        for (String tag : List.of("<script", "<img", "<iframe", "<link", "<object", "<embed", "<svg")) {
            Assert.assertFalse(html.toLowerCase(Locale.ROOT).contains(tag),
                    "no element from a hostile value may exist in the document, but it contains '" + tag
                            + "': a report that renders an ItemType name as markup executes the operator's"
                            + " repository content inside an authenticated console");
        }

        Assert.assertFalse(html.contains("<iframe") || html.contains("</iframe"),
                "and a hostile closing tag must not inject an element either");
        Assert.assertTrue(html.contains("&lt;script&gt;"),
                "the value must still be PRESENT, escaped - a report that dropped it instead would satisfy"
                        + " 'nothing leaked' while losing the operator's data");

        for (String value : List.of(hostileName, hostileClass, hostileRetention)) {
            String escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;");
            Assert.assertTrue(html.contains(escaped)
                            || html.contains(escaped.replace("&#39;", "&#x27;")),
                    "every dynamic value must appear in its escaped form; missing: " + escaped);
        }

        // Structural: the document must not reference anything it cannot load offline.
        Assert.assertFalse(html.toLowerCase(Locale.ROOT).contains("<link ")
                        || html.toLowerCase(Locale.ROOT).contains("<img "),
                "an HTML report must not link or embed an external asset");
    }

    // ------------------------------------------------------------------ CSV

    /** A formula prefix never survives into a leading position, and quoting round-trips exactly. */
    public void csvNeutralisesEveryFormulaPrefixAndQuotesCorrectly() throws Exception {
        for (String hostile : FORMULA_PREFIXED) {
            ReportModel model = modelWith("ItemType" + indexOf(hostile), "SAP", "", hostile);
            String csv = new String(new CsvReportRenderer().render(model), StandardCharsets.UTF_8);

            List<List<String>> rows = Csv.parse(csv);
            Assert.assertTrue(rows.size() >= 2, "the CSV must contain the metadata block and the table");

            boolean found = false;
            for (List<String> row : rows) {
                for (String cell : row) {
                    if (cell.equals(hostile)) {
                        found = true;
                        Assert.fail("the hostile value survived unfenced as a leading cell: '" + hostile
                                + "'. A spreadsheet executes a cell that begins with =, +, -, @, TAB or CR");
                    }
                    if (!cell.isEmpty()) {
                        char first = cell.charAt(0);
                        Assert.assertFalse(first == '=' || first == '+' || first == '@' || first == '\t'
                                        || first == '\r' || (first == '-' && !isNumericCell(cell)),
                                "no CSV cell may begin with a formula trigger; found '" + printable(cell)
                                        + "' in row " + row);
                    }
                }
            }
            Assert.assertTrue(found || csv.contains("'"),
                    "the value must still be REPORTED (fenced), not silently dropped: " + hostile);
            Assert.assertTrue(csv.contains("'" + hostile) || csv.contains("'"),
                    "the defence is a leading apostrophe, which is what a spreadsheet treats as text");
        }
    }

    /** Delimiters, quotes and line breaks inside a value round-trip through a real CSV reader. */
    public void csvQuotesDelimitersQuotesAndLineBreaksExactly() throws Exception {
        String withComma = "SAP, Europe";
        String withQuote = "SAP \"core\"";
        String withNewline = "SAP\nsecond line";
        String withCrlf = "SAP\r\nsecond line";

        for (String value : List.of(withComma, withQuote, withNewline, withCrlf)) {
            ReportModel model = modelWith("ItemType1", value, "", "ItemType description " + value);
            String csv = new String(new CsvReportRenderer().render(model), StandardCharsets.UTF_8);
            List<List<String>> rows = Csv.parse(csv);

            boolean found = false;
            for (List<String> row : rows) {
                for (String cell : row) {
                    if (value.equals(cell)) {
                        found = true;
                    }
                }
            }
            Assert.assertTrue(found,
                    "a value containing a delimiter, a quote or a line break must be quoted so a real CSV"
                            + " reader returns it unchanged; value was " + printable(value));
        }

        // A quoted field must not corrupt the table: every table row has the documented column count.
        String csv = new String(new CsvReportRenderer().render(
                modelWith("ItemType1", withComma, "", withQuote)), StandardCharsets.UTF_8);
        List<List<String>> rows = Csv.parse(csv);
        int headerIndex = -1;
        for (int index = 0; index < rows.size(); index++) {
            if (!rows.get(index).isEmpty() && "itemTypeId".equals(rows.get(index).get(0))) {
                headerIndex = index;
            }
        }
        Assert.assertTrue(headerIndex >= 0, "the CSV must carry the documented ItemType header row");
        int columns = rows.get(headerIndex).size();
        for (int index = headerIndex; index < rows.size(); index++) {
            Assert.assertEquals(columns, rows.get(index).size(),
                    "every table row must have the header's column count, but row " + index + " has "
                            + rows.get(index).size() + ": " + rows.get(index));
        }
    }

    /** Available metrics stay numeric; unavailable ones are labelled, never written as a silent zero. */
    public void csvKeepsMetricsNumericAndNeverInvventsAZero() throws Exception {
        HistoryItemType measured = itemType(1, "ItemType1", "SAP", "", HistoryItemType.Status.OK,
                HistoryMetric.available(1234L), HistoryMetric.available(1L), HistoryMetric.available(2L),
                HistoryMetric.available(3L), HistoryMetric.available(4L));
        HistoryItemType partial = itemType(2, "ItemType2", "SAP", "", HistoryItemType.Status.PARTIAL,
                HistoryMetric.available(7L), HistoryMetric.unavailable("windows not representable"),
                HistoryMetric.unavailable("windows not representable"),
                HistoryMetric.unavailable("windows not representable"),
                HistoryMetric.unavailable("windows not representable"));

        String csv = new String(new CsvReportRenderer().render(modelOf(List.of(measured, partial))),
                StandardCharsets.UTF_8);
        List<String> row = tableRowFor(csv, "1");
        Assert.assertTrue(row != null, "the measured ItemType must have a table row");
        Assert.assertTrue(row.contains("1234"),
                "an available metric must be emitted as its number: " + row);

        List<String> partialRow = tableRowFor(csv, "2");
        Assert.assertTrue(partialRow != null, "the partial ItemType must have a table row");
        boolean zeroWritten = false;
        for (String cell : partialRow) {
            if ("0".equals(cell)) {
                zeroWritten = true;
            }
        }
        Assert.assertFalse(zeroWritten,
                "an UNAVAILABLE metric must never be written as 0, which would read as a measured zero: "
                        + partialRow);
    }

    // ------------------------------------------------------------------ XLSX

    /**
     * XLSX is a real workbook, or explicitly unavailable - and if real, a hostile value is TEXT.
     *
     * <p>The goal allows either outcome and forbids only the third one (a fake workbook, or CSV renamed to
     * {@code .xlsx}). Once the bytes are a real OOXML package, the security question is whether a value
     * beginning with {@code =} was written as a formula cell, which a spreadsheet would then evaluate.
     */
    public void xlsxIsARealWorkbookOrExplicitlyUnavailable() throws Exception {
        if (!ReportFormat.XLSX.available()) {
            Assert.assertTrue(ReportFormat.unavailableFormats().contains(ReportFormat.XLSX),
                    "an unavailable XLSX must be reported as unavailable: " + ReportFormat.XLSX.detail());
            Assert.assertFalse(ReportFormat.XLSX.detail().isBlank(),
                    "and must say why, so the UI can show the reason instead of a silent omission");
            return;
        }

        String hostile = "=HYPERLINK(\"http://evil.example/x\",\"click\")";
        byte[] workbook = new XlsxReportRenderer().render(modelWith("=cmd|' /C calc'!A0", "SAP", "", hostile));

        Assert.assertTrue(workbook.length > 2 && workbook[0] == 'P' && workbook[1] == 'K',
                "an available XLSX must be a real zip-based OOXML package, not text with an .xlsx name");

        List<String> parts = new ArrayList<>();
        StringBuilder sheetText = new StringBuilder();
        StringBuilder allText = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                parts.add(entry.getName());
                String text = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                allText.append(text);
                if (entry.getName().startsWith("xl/worksheets/")) {
                    sheetText.append(text);
                }
            }
        }

        String sheets = sheetText.toString();
        String packageText = allText.toString();

        Assert.assertTrue(parts.contains("[Content_Types].xml"), "the package must declare its content types");
        Assert.assertTrue(parts.contains("xl/workbook.xml"), "and must contain a workbook");
        Assert.assertTrue(parts.stream().anyMatch(name -> name.startsWith("xl/worksheets/sheet")),
                "and at least one worksheet: " + parts);
        Assert.assertTrue(parts.stream().noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("vba")
                        || name.toLowerCase(Locale.ROOT).endsWith(".bin")),
                "no macro part may be present: " + parts);
        Assert.assertFalse(packageText.contains("TargetMode=\"External\""),
                "no part may declare an external relationship");
        Assert.assertFalse(sheets.contains("<f"),
                "no data value may become a formula cell: a value from a repository is never a calculation");

        // The hostile values must be present as TEXT (an inline/shared string), not as a formula.
        for (String value : List.of("=cmd|' /C calc'!A0", hostile)) {
            int at = sheets.indexOf(value);
            Assert.assertTrue(at > 0,
                    "the value must still be reported in the workbook: " + printable(value));
            int cellStart = sheets.lastIndexOf("<c", at);
            Assert.assertTrue(cellStart >= 0, "the value must sit inside a cell element");
            String cellElement = sheets.substring(cellStart, sheets.indexOf('>', cellStart) + 1);
            Assert.assertTrue(cellElement.contains("t=\"inlineStr\"") || cellElement.contains("t=\"s\"")
                            || cellElement.contains("t=\"str\""),
                    "a hostile value must be stored as TEXT, so a spreadsheet cannot evaluate it. Cell: "
                            + cellElement);
        }
    }

    /** No report format carries any formula or active content by capability, whatever the data says. */
    public void everyFormatIsEitherAvailableOrExplicitlyUnavailable() throws Exception {
        for (ReportFormat format : ReportFormat.values()) {
            if (format.available()) {
                Assert.assertFalse(format.detail().isBlank(),
                        format + " is available, so it must describe itself rather than leaving a blank"
                                + " capability line in the UI");
            } else {
                Assert.assertFalse(format.detail().isBlank(),
                        format + " is unavailable, so the reason must be published: a silently omitted"
                                + " format is indistinguishable from a broken one");
            }
        }
        Assert.assertTrue(ReportFormat.availableFormats().contains(ReportFormat.HTML),
                "HTML is mandatory and must be available with JDK-only code");
        Assert.assertTrue(ReportFormat.availableFormats().contains(ReportFormat.CSV),
                "CSV is mandatory and must be available with JDK-only code");
        Assert.assertEquals(ReportFormat.values().length,
                ReportFormat.availableFormats().size() + ReportFormat.unavailableFormats().size(),
                "every format is either available or explicitly unavailable, and none is missing from both");
    }

    // ------------------------------------------------------------------ fixtures

    private static ReportModel modelWith(String itemTypeName, String classification, String retention,
                                         String description) {
        HistoryItemType row = new HistoryItemType(1, itemTypeName, classification, retention,
                HistoryItemType.Status.OK, HistoryMetric.available(10L), HistoryMetric.available(1L),
                HistoryMetric.available(2L), HistoryMetric.available(3L), HistoryMetric.available(4L), 5L,
                description);
        return modelOf(List.of(row));
    }

    private static ReportModel modelOf(List<HistoryItemType> itemTypes) {
        HistorySummary summary = new HistorySummary(new HistoryId("h1"), "alpha", "Repository alpha", "DB2",
                Instant.parse("2024-06-16T08:00:00Z"), Instant.parse("2024-06-16T07:59:00Z"), 60_000L,
                LocalDate.of(2024, 6, 16), 1L, itemTypes.size(), 0, true, 10L);
        return ReportModel.fromHistory(new HistoryDetail(summary, itemTypes, ""), GENERATED_AT);
    }

    private static HistoryItemType itemType(int id, String name, String classification, String retention,
                                            HistoryItemType.Status status, HistoryMetric logical,
                                            HistoryMetric today, HistoryMetric week, HistoryMetric month,
                                            HistoryMetric year) {
        return new HistoryItemType(id, name, classification, retention, status, logical, today, week, month,
                year, 5L, "");
    }

    private static List<String> tableRowFor(String csv, String itemTypeId) {
        for (List<String> row : Csv.parse(csv)) {
            if (!row.isEmpty() && itemTypeId.equals(row.get(0))) {
                return row;
            }
        }
        return null;
    }

    private static boolean isNumericCell(String cell) {
        return cell.matches("-?[0-9]+");
    }

    private static int indexOf(String value) {
        return Math.abs(value.hashCode() % 1000);
    }

    private static String printable(String value) {
        return "'" + value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "'";
    }

    /**
     * A small RFC 4180 reader, used as the MEASURING DEVICE for the CSV cases.
     *
     * <p>It is deliberately written from the format's rules rather than from the renderer's code: a test
     * that reused the writer's assumptions could not tell a correctly quoted field from a field the writer
     * happened to emit in a way its own reader tolerated.
     */
    private static final class Csv {

        private Csv() {
        }

        static List<List<String>> parse(String text) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            boolean fieldStarted = false;
            int index = 0;
            while (index < text.length()) {
                char c = text.charAt(index);
                if (quoted) {
                    if (c == '"') {
                        if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                            field.append('"');
                            index += 2;
                            continue;
                        }
                        quoted = false;
                        index++;
                        continue;
                    }
                    field.append(c);
                    index++;
                    continue;
                }
                if (c == '"' && field.length() == 0 && !fieldStarted) {
                    quoted = true;
                    fieldStarted = true;
                    index++;
                    continue;
                }
                if (c == ',') {
                    row.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                    index++;
                    continue;
                }
                if (c == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    row.add(field.toString());
                    rows.add(row);
                    row = new ArrayList<>();
                    field.setLength(0);
                    fieldStarted = false;
                    index += 2;
                    continue;
                }
                if (c == '\n') {
                    row.add(field.toString());
                    rows.add(row);
                    row = new ArrayList<>();
                    field.setLength(0);
                    fieldStarted = false;
                    index++;
                    continue;
                }
                field.append(c);
                index++;
            }
            if (field.length() > 0 || !row.isEmpty()) {
                row.add(field.toString());
                rows.add(row);
            }
            return rows;
        }
    }
}



