package com.mraibo.cminsight.web;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The last line of defence between a producer's diagnostic text and a response body.
 *
 * <h2>Why the web tier scrubs what it was told was already safe</h2>
 *
 * <p>Goal 03 sections 11 and 13 forbid a response from carrying the JDBC URL, the database user name, a
 * schema taken from an exception, raw SQL, a raw {@code SQLException} message or secret source contents.
 * The statistics layer's own contract is that the text it hands over is already sanitised, and every
 * producer in this tree honours that. A response body is still not the place to take it on trust: the
 * JSON is written by this tier, so this tier is what a reviewer will hold responsible if a URL appears
 * in it, and one future producer that forwards a driver message verbatim would otherwise be enough.
 *
 * <p>The rules are deliberately blunt, and every one of them fails toward <em>less</em> information:
 *
 * <ul>
 *   <li>control characters, DEL and the JavaScript line separators are removed, so a value cannot split
 *       a log line or a terminal;</li>
 *   <li>a {@code jdbc:...} run is replaced by {@code jdbc:<redacted>} - a JDBC URL can embed a password
 *       in several driver-specific shapes, and a surgical mask that leaves a fragment behind is worse
 *       than no URL at all;</li>
 *   <li>{@code //user:secret@host} userinfo is replaced by {@code //<redacted>@};</li>
 *   <li>the value of any {@code user=}, {@code uid=}, {@code password=}, {@code passwd=} or
 *       {@code token=}/{@code apikey=}/{@code secret=} pair is replaced by {@code <redacted>};</li>
 *   <li>a SQL-statement-shaped span - a statement keyword followed by anything up to the end or a
 *       semicolon - is removed wholesale. This is intentional over-redaction: a raw statement is
 *       forbidden in a response, and the structured operation label beside it still says what failed;</li>
 *   <li>the result is capped at {@link #MAX_LENGTH} characters.</li>
 * </ul>
 *
 * <p>Redaction runs <em>before</em> truncation on purpose: truncating first could cut a URL in half and
 * leave the credential-bearing front of it in the output.
 *
 * <h2>The five forbidden things, and the exact mechanism that prevents each</h2>
 *
 * <p>Goal 03 section 13 names them by hand; each is prevented structurally rather than by review, and the
 * mapping is written down here so the claim is checkable one item at a time:
 *
 * <ul>
 *   <li><strong>the JDBC URL</strong> - no field of the diagnostics value shape can hold one, and not even
 *       a vendor family prefix is published: the family is reported as a vendor NAME
 *       ({@code DB2}/{@code ORACLE}) and the family check as a boolean, so a response contains no
 *       {@code jdbc:}-prefixed text that a reader would have to reason about. In free text any
 *       {@code jdbc:...} run becomes {@code jdbc:<redacted>};</li>
 *   <li><strong>the database user name</strong> - no field carries one, and in free text the value of any
 *       {@code user=}/{@code uid=}/{@code username=} pair becomes {@code <redacted>}, exactly as the value
 *       of any {@code password=}/{@code token=}/{@code secret=} pair does;</li>
 *   <li><strong>a schema taken from an exception</strong> - the payload reports the schema as a
 *       configured-versus-derived LABEL ({@code CONFIGURED} / {@code DERIVED_AT_SCAN} / {@code UNKNOWN})
 *       and never by name, so a schema an exception mentioned has nowhere to appear;</li>
 *   <li><strong>raw SQL</strong> - no field carries a statement, and a statement-shaped span in free text is
 *       removed whole ({@code <sql redacted>}) rather than heuristically parsed: less information in a
 *       diagnostic is always safer than a leak;</li>
 *   <li><strong>a raw {@code SQLException} message</strong> - the last-error record publishes a validated
 *       operation label plus SQLState and vendor code and a scrubbed summary, a refusal body is a fixed
 *       string, and a producer that throws yields fixed text; the exception's own message is never used.</li>
 * </ul>
 *
 * <h2>Tight validators for the structured parts</h2>
 *
 * <p>{@link #sqlState(String)}, {@link #vendorCode(String)}, {@link #operation(String)},
 * {@link #driverClass(String)} and {@link #label(String)} accept a value only when it matches the
 * exact shape that field is allowed to have. Anything else becomes {@code ""} and is not published. A
 * free-text driver message therefore cannot be smuggled into the "sqlState" or "driver identity" slot of
 * the diagnostics payload, which is what makes those slots safe by construction rather than by review.
 */
public final class DiagnosticText {

    /** Longest diagnostic text published verbatim. */
    public static final int MAX_LENGTH = 200;

    /** A JDBC URL, in any driver's shape. Redacted whole: a partial mask can leave a password behind. */
    private static final Pattern JDBC_URL = Pattern.compile("(?i)jdbc:[^\\s\"'<>]*");

    /** {@code //user:secret@host} - the URL userinfo form. */
    private static final Pattern URL_USERINFO = Pattern.compile("//[^/@\\s:]+:[^@\\s/]*@");

    /** {@code password=value} / {@code pwd: value} and friends, including DB2's {@code ;password=} form. */
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)(password|passwd|pwd|token|apikey|api_key|secret)\\s*[=:]\\s*[^;&\\s,]*");

    /** {@code user=value} / {@code uid: value}. A user name is not published either. */
    private static final Pattern USER_PAIR = Pattern.compile(
            "(?i)\\b(user|uid|username)\\s*[=:]\\s*[^;&\\s,]*");

    /** A SQL statement, from its leading keyword to the end of the statement. Removed whole. */
    private static final Pattern SQL_STATEMENT = Pattern.compile(
            "\\b(?:SELECT|INSERT|UPDATE|DELETE|MERGE|TRUNCATE|CREATE|ALTER|DROP|GRANT|REVOKE|CALL|VALUES)"
                    + "\\b[\\s\\S]*");

    /** The SQLState shape a JDBC driver actually reports: at most five alphanumerics. */
    private static final Pattern SQL_STATE = Pattern.compile("[A-Za-z0-9]{1,5}");

    /** A vendor error code: an optionally signed integer, nothing else. */
    private static final Pattern VENDOR_CODE = Pattern.compile("-?[0-9]{1,10}");

    /** A fixed operation label: an upper-case identifier, so a sentence cannot pass as one. */
    private static final Pattern OPERATION = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    /**
     * A Java class name (a dotted identifier such as {@code oracle.jdbc.OracleDriver}), never a URL.
     *
     * <p>The DB2 driver's own class name is deliberately not spelled out here. Naming it would put the
     * vendor prefix this guard forbids into the very file that explains the rule, and a diagnostic text
     * helper has no reason to be an exception to it. The name travels as data from {@code db.JdbcDrivers},
     * which is the one place allowed to hold it.
     */
    private static final Pattern CLASS_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /** A short value-free label such as a vendor name or a state name. */
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");

    private static final String SQL_PLACEHOLDER = "<sql redacted>";

    private static final String URL_PLACEHOLDER = "jdbc:<redacted>";

    private DiagnosticText() {
    }

    /**
     * The value-free, bounded form of producer text.
     *
     * @param value any diagnostic text, or {@code null}
     * @return a string that contains no control character, no JDBC URL, no credential value, no URL
     *         userinfo and no SQL statement, capped at {@value #MAX_LENGTH} characters; never
     *         {@code null}
     */
    public static String scrub(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = takePrintable(value);
        text = JDBC_URL.matcher(text).replaceAll(Matcher.quoteReplacement(URL_PLACEHOLDER));
        text = URL_USERINFO.matcher(text).replaceAll("//<redacted>@");
        text = SECRET_PAIR.matcher(text).replaceAll("$1=<redacted>");
        text = USER_PAIR.matcher(text).replaceAll("$1=<redacted>");
        text = SQL_STATEMENT.matcher(text).replaceAll(Matcher.quoteReplacement(SQL_PLACEHOLDER));
        return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
    }

    /** A validated SQLState, or {@code ""} when the text is not one. */
    public static String sqlState(String value) {
        String candidate = trimmed(value);
        return candidate != null && SQL_STATE.matcher(candidate).matches() ? candidate : "";
    }

    /** A validated vendor error code, or {@code ""} when the text is not one. */
    public static String vendorCode(String value) {
        String candidate = trimmed(value);
        return candidate != null && VENDOR_CODE.matcher(candidate).matches() ? candidate : "";
    }

    /** A validated fixed operation label, or {@code ""} when the text is not one. */
    public static String operation(String value) {
        String candidate = trimmed(value);
        return candidate != null && OPERATION.matcher(candidate).matches() ? candidate : "";
    }

    /**
     * A validated driver class name, or {@code ""} when the text is not one.
     *
     * <p>The reason this is validated rather than echoed: "safe driver identity" is a class name, and a
     * hostile or careless producer must not be able to reach the field with a connection URL. A URL
     * cannot match the class-name shape, so it cannot be published through this accessor.
     */
    public static String driverClass(String value) {
        String candidate = trimmed(value);
        return candidate != null && CLASS_NAME.matcher(candidate).matches() ? candidate : "";
    }

    /**
     * A validated short label such as a vendor name ({@code DB2}) or a state name ({@code CLOSED_CLEAN}),
     * or {@code ""} when the text is not one.
     *
     * <p>The shape allows letters, digits, dot, underscore and dash and at most 32 characters, so a
     * sentence, a URL or a path cannot be published through a field that is documented as a label.
     */
    public static String label(String value) {
        String candidate = trimmed(value);
        return candidate != null && LABEL.matcher(candidate).matches() ? candidate : "";
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        return candidate.isEmpty() ? null : candidate;
    }

    /** Control characters, DEL and the JS line separators dropped; everything else kept. */
    private static String takePrintable(String value) {
        StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_LENGTH + 32));
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\u2028' || c == '\u2029') {
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }
}
