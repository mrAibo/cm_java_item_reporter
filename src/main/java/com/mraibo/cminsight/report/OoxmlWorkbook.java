package com.mraibo.cminsight.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A minimal, dependency-free OOXML workbook writer: enough of the format to be a real workbook and nothing
 * more.
 *
 * <h2>Why this exists instead of a library</h2>
 *
 * <p>Goal 04 accepts XLSX only as a real workbook or as an explicitly unavailable format, and it forbids the
 * middle: a CSV renamed {@code .xlsx} or a fake archive. No XLSX library is available locally here, and
 * coupling this offline, JDK-only build to one would mean a download the project does not do. What the format
 * actually requires for a report is small and fully specified, so this class writes that subset with
 * {@code java.util.zip} and nothing else: six parts, three styles, two sheets.
 *
 * <h2>What it deliberately cannot express</h2>
 *
 * <ul>
 *   <li><strong>No formula cell.</strong> There is no method that writes an {@code <f>} element. Data becomes
 *       either an inline string or a number, so a hostile value beginning with {@code =} is text in the
 *       workbook, not an expression. This is a property of the API, not of the caller's discipline.</li>
 *   <li><strong>No macro.</strong> No part is written with a macro-enabled content type and no
 *       {@code vbaProject.bin} part exists.</li>
 *   <li><strong>No external relationship.</strong> Every relationship is a relative path inside the package
 *       and none carries {@code TargetMode="External"}, so the workbook cannot load anything.</li>
 *   <li><strong>No shared-string table, theme, docProps or hyperlinks.</strong> Text is inline, which removes
 *       the one part where a hostile value would be deduplicated and referenced indirectly; the cost is a
 *       slightly larger file, which for a report of hundreds of rows is irrelevant.</li>
 * </ul>
 *
 * <p>Zip entries carry a fixed timestamp rather than "now", so the same model renders to the same bytes and a
 * report artifact does not leak when it was built through metadata nobody asked for.
 */
final class OoxmlWorkbook {

    /** Style index 0: ordinary text. */
    static final int STYLE_TEXT = 0;
    /** Style index 1: bold, used for headers and labels. */
    static final int STYLE_BOLD = 1;
    /** Style index 2: the {@code #,##0} integer number format. */
    static final int STYLE_NUMBER = 2;

    private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String DOCUMENT_REL_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String CONTENT_TYPES_NS =
            "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String RELATIONSHIP_TYPE_BASE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    /** 1980-01-01T00:00:00Z, the earliest instant the DOS timestamp in a zip entry can express. */
    private static final long DOS_EPOCH_MILLIS = 315_532_800_000L;

    /** One cell: escaped text, or a number, plus the style it is written with. */
    record Cell(String text, Long number, int style) {

        static Cell text(String value) {
            return new Cell(value == null ? "" : value, null, STYLE_TEXT);
        }

        static Cell bold(String value) {
            return new Cell(value == null ? "" : value, null, STYLE_BOLD);
        }

        static Cell number(long value) {
            return new Cell(null, value, STYLE_NUMBER);
        }

        Cell {
            if (text == null && number == null) {
                throw new IllegalArgumentException("a workbook cell must carry text or a number");
            }
            if (text != null && number != null) {
                throw new IllegalArgumentException("a workbook cell must not carry both text and a number");
            }
        }

        boolean isNumber() {
            return number != null;
        }
    }

    /** One worksheet: a name and the rows written into it, in order. */
    static final class Sheet {

        private final String name;
        private final List<List<Cell>> rows = new ArrayList<>();

        private Sheet(String name) {
            this.name = Objects.requireNonNull(name, "name");
            if (name.isEmpty() || name.length() > 31) {
                throw new IllegalArgumentException("a worksheet name must hold 1 to 31 characters");
            }
        }

        Sheet row(Cell... cells) {
            rows.add(List.of(cells));
            return this;
        }

        int rowCount() {
            return rows.size();
        }
    }

    private final List<Sheet> sheets = new ArrayList<>();

    /** Starts a new worksheet. */
    Sheet sheet(String name) {
        for (Sheet existing : sheets) {
            if (existing.name.equals(name)) {
                throw new IllegalArgumentException("a workbook cannot hold two sheets with one name");
            }
        }
        Sheet sheet = new Sheet(name);
        sheets.add(sheet);
        return sheet;
    }

    /** True when nothing was written at all. */
    boolean isEmpty() {
        return sheets.isEmpty();
    }

    /** The complete package as zip bytes. */
    byte[] toBytes() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            // [Content_Types].xml is written first: a package reader is allowed to require the content-type
            // map before it can interpret any other part.
            writePart(zip, "[Content_Types].xml", contentTypes());
            writePart(zip, "_rels/.rels", packageRelationships());
            writePart(zip, "xl/workbook.xml", workbook());
            writePart(zip, "xl/_rels/workbook.xml.rels", workbookRelationships());
            writePart(zip, "xl/styles.xml", styles());
            for (int index = 0; index < sheets.size(); index++) {
                writePart(zip, "xl/worksheets/sheet" + (index + 1) + ".xml", worksheet(sheets.get(index)));
            }
        } catch (IOException impossibleForAnInMemoryBuffer) {
            // Deliberately swallowed: the destination is a byte array, so an IOException here means the JDK
            // itself failed, and the report contract still has to be a fixed, value-free failure.
            throw new ReportException(ReportException.Reason.WRITE_FAILED);
        }
        return buffer.toByteArray();
    }

    private static void writePart(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(DOS_EPOCH_MILLIS);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String contentTypes() {
        StringBuilder xml = declaration();
        xml.append("<Types xmlns=\"").append(CONTENT_TYPES_NS).append("\">")
                .append("<Default Extension=\"rels\" ContentType=\"")
                .append("application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"")
                .append("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"")
                .append("application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int index = 1; index <= sheets.size(); index++) {
            xml.append("<Override PartName=\"/xl/worksheets/sheet").append(index)
                    .append(".xml\" ContentType=\"")
                    .append("application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return xml.append("</Types>").toString();
    }

    private static String packageRelationships() {
        return declaration()
                + "<Relationships xmlns=\"" + PACKAGE_REL_NS + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + RELATIONSHIP_TYPE_BASE + "officeDocument\""
                + " Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    private String workbook() {
        StringBuilder xml = declaration();
        xml.append("<workbook xmlns=\"").append(MAIN_NS)
                .append("\" xmlns:r=\"").append(DOCUMENT_REL_NS).append("\"><sheets>");
        for (int index = 1; index <= sheets.size(); index++) {
            xml.append("<sheet name=\"").append(ReportText.xmlText(sheets.get(index - 1).name))
                    .append("\" sheetId=\"").append(index).append("\" r:id=\"rId").append(index)
                    .append("\"/>");
        }
        return xml.append("</sheets></workbook>").toString();
    }

    private String workbookRelationships() {
        StringBuilder xml = declaration();
        xml.append("<Relationships xmlns=\"").append(PACKAGE_REL_NS).append("\">");
        for (int index = 1; index <= sheets.size(); index++) {
            xml.append("<Relationship Id=\"rId").append(index).append("\" Type=\"")
                    .append(RELATIONSHIP_TYPE_BASE).append("worksheet\" Target=\"worksheets/sheet")
                    .append(index).append(".xml\"/>");
        }
        return xml.append("<Relationship Id=\"rId").append(sheets.size() + 1).append("\" Type=\"")
                .append(RELATIONSHIP_TYPE_BASE).append("styles\" Target=\"styles.xml\"/>")
                .append("</Relationships>")
                .toString();
    }

    /**
     * The three styles this workbook uses.
     *
     * <p>{@code fills} carries the two mandatory entries ({@code none} and {@code gray125}) because the format
     * requires the first two fill records to be exactly those, even when no cell uses them. Colours are
     * explicit RGB rather than theme references, which is what lets the package omit a theme part entirely.
     */
    private static String styles() {
        return declaration()
                + "<styleSheet xmlns=\"" + MAIN_NS + "\">"
                + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0\"/></numFmts>"
                + "<fonts count=\"2\">"
                + "<font><sz val=\"11\"/><color rgb=\"FF000000\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
                + "<font><b/><sz val=\"11\"/><color rgb=\"FF000000\"/><name val=\"Calibri\"/>"
                + "<family val=\"2\"/></font>"
                + "</fonts>"
                + "<fills count=\"2\">"
                + "<fill><patternFill patternType=\"none\"/></fill>"
                + "<fill><patternFill patternType=\"gray125\"/></fill>"
                + "</fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"3\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\""
                + " applyNumberFormat=\"1\"/>"
                + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>";
    }

    private static String worksheet(Sheet sheet) {
        StringBuilder xml = declaration();
        xml.append("<worksheet xmlns=\"").append(MAIN_NS).append("\"><sheetData>");
        int rowNumber = 1;
        for (List<Cell> row : sheet.rows) {
            xml.append("<row r=\"").append(rowNumber).append("\">");
            int column = 0;
            for (Cell cell : row) {
                appendCell(xml, cell, column, rowNumber);
                column++;
            }
            xml.append("</row>");
            rowNumber++;
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    private static void appendCell(StringBuilder xml, Cell cell, int column, int rowNumber) {
        String reference = columnName(column) + rowNumber;
        if (cell.isNumber()) {
            xml.append("<c r=\"").append(reference).append("\" s=\"").append(STYLE_NUMBER)
                    .append("\"><v>").append(cell.number()).append("</v></c>");
            return;
        }
        // Every non-numeric value is an INLINE STRING: `t="inlineStr"` with an <is><t> child. Never `t="str"`
        // (which is a formula's cached result) and never a cell with an <f> child, so a value that begins with
        // '=' is stored as text this application wrote, exactly as a label is.
        xml.append("<c r=\"").append(reference).append("\" t=\"inlineStr\" s=\"").append(cell.style())
                .append("\"><is><t xml:space=\"preserve\">")
                .append(ReportText.xmlText(cell.text()))
                .append("</t></is></c>");
    }

    /** The A1-style column name of a zero-based column index. */
    private static String columnName(int index) {
        StringBuilder name = new StringBuilder(2);
        int remaining = index;
        while (true) {
            name.append((char) ('A' + remaining % 26));
            remaining = remaining / 26 - 1;
            if (remaining < 0) {
                break;
            }
        }
        return name.reverse().toString();
    }

    private static StringBuilder declaration() {
        return new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
    }
}
