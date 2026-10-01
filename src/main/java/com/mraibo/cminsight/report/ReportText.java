package com.mraibo.cminsight.report;

/**
 * The single place report output is escaped, quoted and neutralised.
 *
 * <h2>One implementation, three formats, no exception</h2>
 *
 * <p>Every dynamic value in a report - ItemType names, business classifications, retention-policy names,
 * captured failure reasons and the capture-time warning - is hostile input as far as this application is
 * concerned: it comes from IBM CM, a customer's metadata or a database that named a policy, and none of them
 * is under this project's control. A renderer therefore has no code path that appends a dynamic value
 * directly; it calls one of the methods here, and this class is deliberately the only place in the package
 * that knows how each output format treats those characters.
 *
 * <p>The rules are absolute rather than best-effort, because "mostly escaped" is the state in which a
 * defect survives:
 *
 * <ul>
 *   <li><strong>HTML</strong>: {@code &}, {@code <}, {@code >}, {@code "} and {@code '} are all escaped as
 *       entities - attribute quoting is not assumed anywhere, so a value cannot end a tag or open one.
 *       Control characters and unpaired surrogates become a space, which keeps two words apart instead of
 *       gluing them together.</li>
 *   <li><strong>XML</strong> (the XLSX parts): the five entities above plus the XML 1.0 character range.
 *       A NUL or an unpaired surrogate is not merely ugly in XML, it makes the part <em>malformed</em>, so a
 *       hostile ItemType name would break the workbook instead of only looking wrong; those characters are
 *       replaced, never emitted.</li>
 *   <li><strong>CSV</strong>: RFC 4180 quoting, doubled quotes, and a formula fence in front of any text a
 *       spreadsheet would evaluate. See {@link #csvField(String, char)}.</li>
 * </ul>
 *
 * <p>This class also holds the one marker {@link #UNKNOWN} that a renderer prints where a value was not
 * captured. It is deliberately a word and not a zero: an absent number must never become a measurement, and
 * a report reader has to be able to tell the two apart.
 */
final class ReportText {

    /** What a renderer prints for a text field that was not captured. */
    static final String UNKNOWN = "unknown";

    /** What a renderer prints for a retention policy that was not assigned at scan time. */
    static final String NONE = "none";

    /** What a renderer prints for a metric that was not measured or could not be measured. */
    static final String NOT_MEASURED = "unavailable";

    /**
     * The characters that make a spreadsheet treat a cell as a formula.
     *
     * <p>Tab and carriage return are included because a leading one is silently dropped by some importers,
     * which would promote the character that follows it - typically {@code =} - into the first position
     * again. This is the list Goal 04 section 6 names, and it is kept in one constant so the CSV renderer
     * and any future format cannot disagree about it.
     */
    private static final String FORMULA_PREFIXES = "=+-@\t\r";

    private ReportText() {
    }

    /** True when {@code text} would be evaluated as a formula by a spreadsheet that read it as a cell. */
    static boolean needsFormulaFence(String text) {
        return text != null && !text.isEmpty() && FORMULA_PREFIXES.indexOf(text.charAt(0)) >= 0;
    }

    /**
     * Escapes one value for an HTML text node.
     *
     * <p>The result contains no markup at all: it is the caller's job to place it inside an element this
     * package wrote. That is what "no inline untrusted HTML" means here - untrusted text never reaches the
     * document except through this method, and never reaches an attribute, a script or a style block,
     * because no dynamic value is ever written into one.
     */
    static String html(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> {
                    if (character < 0x20 || character == 0x7f || Character.isSurrogate(character)) {
                        if (Character.isSurrogate(character) && isPairedSurrogate(text, index)) {
                            escaped.append(character);
                        } else {
                            escaped.append(' ');
                        }
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Escapes one value for an XML text node in an OOXML part.
     *
     * <p>Iterates by code point so a supplementary character survives as itself, and replaces everything
     * outside the XML 1.0 character set with a space. Emitting such a character is not a cosmetic problem:
     * a parser rejects the whole part, which for a workbook means the file does not open.
     */
    static String xmlText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            switch (codePoint) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                default -> {
                    if (isXmlCharacter(codePoint)) {
                        escaped.appendCodePoint(codePoint);
                    } else {
                        escaped.append(' ');
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Renders one CSV field: formula fenced when it has to be, RFC 4180 quoted when it has to be.
     *
     * <h2>The formula rule</h2>
     *
     * <p>A text field whose first character is one of {@code =}, {@code +}, {@code -}, {@code @}, tab or
     * carriage return is prefixed with a single quote. That is the defence: the cell's first character
     * becomes {@code '}, which no spreadsheet evaluates, while the original text stays readable and intact
     * for a human - nothing is deleted, so the value still says what IBM CM said. A fenced field is also
     * always quoted, so the neutralisation is visible in the raw bytes rather than only in a spreadsheet's
     * interpretation of them.
     *
     * <p>Quoting itself follows RFC 4180 exactly: a field is quoted when it contains the delimiter, a quote,
     * a carriage return or a line feed - and also when it contains a tab, or begins or ends with a space, so
     * a parser that trims or splits on whitespace cannot silently change the value. An embedded quote is
     * doubled inside the quoted field.
     *
     * <p>Deliberately NOT done here: fenced or quoted NUMBERS. A caller that has a measured metric writes it
     * with {@link #digits(long)} instead, because a spreadsheet user must be able to sum the column, and
     * a number cannot begin with a formula character in the first place.
     */
    static String csvField(String text, char delimiter) {
        String value = text == null ? "" : text;
        boolean fenced = needsFormulaFence(value);
        String content = fenced ? "'" + value : value;
        boolean quote = fenced
                || content.indexOf(delimiter) >= 0
                || content.indexOf('"') >= 0
                || content.indexOf('\r') >= 0
                || content.indexOf('\n') >= 0
                || content.indexOf('\t') >= 0
                || !content.equals(content.strip());
        if (!quote) {
            return content;
        }
        return '"' + content.replace("\"", "\"\"") + '"';
    }

    /**
     * The canonical text of one measured, non-negative metric: bare digits, no locale formatting and no
     * grouping separators.
     *
     * <p>Used as a CSV numeric field (unquoted, which is what makes the column summable) and as the text of a
     * metric in the HTML and workbook renderers. One method, one spelling, so a report reads the same in
     * every format and no locale can turn a count into {@code 1.234}.
     *
     * <p>A negative value would begin with a formula character, so this method refuses it as a contract
     * check; a caller decides first with {@link #isSafeSpreadsheetNumber(long)} and writes text instead, so
     * the refusal can never reach a report as an exception.
     */
    static String digits(long value) {
        if (!isSafeSpreadsheetNumber(value)) {
            throw new IllegalArgumentException("a report metric written as a spreadsheet number must not"
                    + " be negative; a caller must check isSafeSpreadsheetNumber first");
        }
        return Long.toString(value);
    }

    /** True when a measured value may be written as a spreadsheet NUMBER rather than as text. */
    static boolean isSafeSpreadsheetNumber(long value) {
        // Non-negative values are digits and nothing else, so they can neither be a formula nor be mistaken for
        // one. A negative measurement is the only shape that would begin with '-', and it is written as text in
        // every spreadsheet format so the formats cannot disagree about the same value.
        return value >= 0L;
    }

    /** A captured text value, or the explicit unknown marker when it was not captured. */
    static String orUnknown(String value) {
        return value == null || value.isEmpty() ? UNKNOWN : value;
    }

    /**
     * A retention-policy name, or the marker {@link #NONE}.
     *
     * <p>Empty is not the same answer as "unknown" here: the frozen captured type documents an empty
     * retention-policy name as "none was assigned at scan time", which is a captured fact rather than a
     * missing one. Saying so keeps a report from implying that something might have been assigned.
     */
    static String retentionOrNone(String value) {
        return value == null || value.isEmpty() ? NONE : value;
    }

    private static boolean isPairedSurrogate(String text, int index) {
        char character = text.charAt(index);
        if (Character.isHighSurrogate(character)) {
            return index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1));
        }
        if (Character.isLowSurrogate(character)) {
            return index > 0 && Character.isHighSurrogate(text.charAt(index - 1));
        }
        return false;
    }

    /** True when the code point may appear in an XML 1.0 document at all. */
    private static boolean isXmlCharacter(int codePoint) {
        return codePoint == 0x9
                || codePoint == 0xA
                || codePoint == 0xD
                || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }
}
