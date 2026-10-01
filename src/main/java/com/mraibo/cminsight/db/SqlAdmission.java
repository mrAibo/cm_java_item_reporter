package com.mraibo.cminsight.db;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The admission rule for SQL this application generates: a deliberately narrow read-only language, checked
 * lexically and <strong>token-aware</strong>.
 *
 * <h2>Why a prefix check was not enough</h2>
 *
 * <p>The previous gate admitted any statement whose leading text was {@code SELECT} or {@code WITH}. That
 * proves the statement <em>starts</em> as a read and nothing else: on supported enterprise dialects a
 * data-changing construct can be nested inside a {@code SELECT}/{@code WITH} shape - a data-change table
 * reference, a DML token inside a common table expression, a second statement after a separator, or a write
 * token hidden behind a comment. So this class answers the stronger question: is the whole statement inside
 * the language this application actually emits?
 *
 * <h2>The admitted language</h2>
 *
 * <p>Every statement CM Insight generates is machine-built from the dialects and query builders. That
 * language has exactly these properties, and this class enforces exactly them:
 *
 * <ul>
 *   <li>a single statement, so a statement separator is refused;</li>
 *   <li>no comments, because generated SQL never needs them and a comment can hide a token;</li>
 *   <li>no string literals, because every value is a bind parameter - so the narrow surface can refuse
 *       literals outright rather than try to parse them;</li>
 *   <li>it begins with {@code SELECT} or {@code WITH};</li>
 *   <li>it contains no write or control keyword anywhere, as a whole token.</li>
 * </ul>
 *
 * <p>This is deliberately <strong>not</strong> a DB2 or Oracle parser. It is a refusal layer for one
 * known-good generator, which is why refusing a construct this application does not emit is the correct
 * answer even when that construct would be legal SQL.
 *
 * <h2>What "token-aware" means here, and why it matters</h2>
 *
 * <p>Keywords are matched as whole tokens, not as substrings. {@code UPDATED_AT}, {@code DELETED_FLAG} and
 * {@code CREATE_TS} are ordinary identifiers and are admitted, because a rule that refused them would refuse
 * real column names and would then be weakened by whoever hit it. Equally, the tokenizer must not be fooled
 * the other way: text inside a quoted region is not scanned for keywords, and a quoted region cannot be used
 * to smuggle a separator.
 *
 * <h2>Contract</h2>
 *
 * <p>Pure and side-effect free: no JDBC, no I/O, no state. {@link #refuse(String)} returns a fixed,
 * value-free reason naming the rule that refused, or empty when the statement is admitted. The reason never
 * reproduces the SQL, so it is safe to record or return.
 */
public final class SqlAdmission {

    /**
     * Keywords that may not appear as a whole token anywhere in an admitted statement.
     *
     * <p>Every entry is a write, a schema change, a privilege change, a stored-procedure call or a
     * transaction-control statement. They are also the words the committed source guard refuses, so the
     * runtime gate and the text guard name the same vocabulary rather than drifting apart.
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE",
            "CREATE", "ALTER", "DROP", "GRANT", "REVOKE",
            "CALL", "BEGIN", "EXEC", "EXECUTE",
            "COMMIT", "ROLLBACK", "SAVEPOINT",
            // Data-change table references are the shape a SELECT-wrapped mutation takes on DB2, and
            // FINAL TABLE / OLD TABLE / NEW TABLE are how it is spelled.
            "FINAL", "OLD", "NEW");

    /** The two leading keywords an admitted statement may have. */
    private static final List<String> ADMITTED_LEADING = List.of("SELECT", "WITH");

    private SqlAdmission() {
    }

    /** The fixed, value-free reason this statement is refused, or empty when it is admitted. */
    public static Optional<String> refuse(String sql) {
        if (sql == null) {
            return Optional.of("no statement was supplied");
        }
        String text = sql.strip();
        if (text.isEmpty()) {
            return Optional.of("the statement was empty");
        }

        // 1. Comments. Generated analytics SQL contains none, and a comment is the cheapest way to hide a
        //    token from a scanner, so they are refused rather than stripped. Refusing is also stronger than
        //    stripping: a stripper has to be right about nesting and quoting, a refusal does not.
        if (text.contains("--") || text.contains("/*") || text.contains("*/")) {
            return Optional.of("the statement contains a SQL comment, which generated analytics SQL never"
                    + " needs and which could hide a token");
        }

        // 2. Quoted regions of ANY kind. Generated analytics SQL binds every value and never quotes an
        //    identifier, so a quote means either a hand-written statement or an attempt to smuggle one - and
        //    it is also the one construct that can hide a write verb from the token rule below, because the
        //    tokenizer skips quoted regions. See hasQuotedRegion for why this refuses every quote rather than
        //    enumerating the vendor prefixes.
        if (hasQuotedRegion(text)) {
            return Optional.of("the statement contains a quoted region; every value in analytics SQL is a"
                    + " bind parameter and no identifier is quoted, so a quote is outside the admitted"
                    + " language");
        }

        // 3. Statement separators, outside quoted identifiers. More than one statement can therefore never
        //    reach the driver.
        if (hasSeparator(text)) {
            return Optional.of("the statement contains a statement separator, so it is more than one"
                    + " statement");
        }

        // 4. Leading keyword.
        List<String> tokens = tokens(text);
        if (tokens.isEmpty()) {
            return Optional.of("the statement contained no token");
        }
        String leading = tokens.get(0);
        if (!ADMITTED_LEADING.contains(leading)) {
            return Optional.of("the statement does not begin with SELECT or WITH, so it is not a read query");
        }

        // 5. Forbidden vocabulary, anywhere, as whole tokens.
        for (String token : tokens) {
            if (FORBIDDEN.contains(token)) {
                return Optional.of("the statement contains the write or control keyword " + token
                        + ", which is outside the admitted read-only language");
            }
        }
        return Optional.empty();
    }

    /** True when the statement is inside the admitted language. */
    public static boolean admitted(String sql) {
        return refuse(sql).isEmpty();
    }

    /**
     * The statement's whole tokens, upper-cased, with quoted regions excluded.
     *
     * <p>Quoted regions are skipped rather than tokenized so a keyword inside a quoted identifier cannot
     * trigger a refusal and, more importantly, so a quoted region cannot be used to hide a token that a
     * later stage would see as bare text.
     */
    private static List<String> tokens(String text) {
        List<String> tokens = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '"') {
                index = skipQuoted(text, index);
                flush(current, tokens);
                continue;
            }
            if (c == '\'') {
                // Unreachable for an admitted statement, because string literals are refused above. Handled
                // anyway so this tokenizer cannot be the thing that misfires if the order of checks changes.
                index = skipQuoted(text, index);
                flush(current, tokens);
                continue;
            }
            if (isTokenChar(c)) {
                current.append(c);
                index++;
                continue;
            }
            flush(current, tokens);
            index++;
        }
        flush(current, tokens);
        return tokens;
    }

    private static void flush(StringBuilder current, List<String> tokens) {
        if (current.length() > 0) {
            tokens.add(current.toString().toUpperCase(Locale.ROOT));
            current.setLength(0);
        }
    }

    /** True when {@code c} may appear inside an identifier or keyword token. */
    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    /** The index just past the quoted region opened at {@code start}, or the end of the text. */
    private static int skipQuoted(String text, int start) {
        char quote = text.charAt(start);
        int index = start + 1;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == quote) {
                // A doubled quote inside a quoted region is an escaped quote, not the end of the region.
                if (index + 1 < text.length() && text.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return text.length();
    }

    /** True when a statement separator appears outside a quoted identifier. */
    private static boolean hasSeparator(String text) {
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '"' || c == '\'') {
                index = skipQuoted(text, index);
                continue;
            }
            if (c == ';') {
                return true;
            }
            index++;
        }
        return false;
    }

    /**
     * True when the statement contains a QUOTE CHARACTER AT ALL, in any form the supported vendors accept.
     *
     * <p>This is deliberately broader than a scan for a bare {@code '} opener, and it exists because the first
     * version of the literal rule could be walked past. That rule recognised a bare {@code '} only, so a
     * literal introduced by one of the vendor's PREFIXED forms - DB2's {@code N'...'} and {@code X'...'} /
     * {@code B'...'}, and Oracle's {@code q'[...]'} alternate quoting - was not detected as a literal, and
     * because the tokenizer skips quoted regions the write verb inside it was never seen as a token either.
     * A statement such as {@code SELECT 1 AS A FROM T WHERE ID = N'DELETE FROM Y'} would therefore have been
     * ADMITTED. That was found by review rather than by a test, which is exactly why the rule is now stated
     * in terms of the quote character instead of in terms of one vendor's opening sequence.
     *
     * <p>Refusing every quote is the honest narrowing rather than a patch to the recogniser: generated
     * analytics SQL contains NO string literal (every value is a bind parameter) and no quoted identifier, so
     * every form this refuses is a form this application does not emit. Enumerating the prefixes instead would
     * be a bet that the list is complete, and a missed prefix is a hole with no test to catch it.
     */
    private static boolean hasQuotedRegion(String text) {
        return text.indexOf('\'') >= 0 || text.indexOf('"') >= 0 || text.indexOf('`') >= 0;
    }

    /** The forbidden vocabulary, for a guard or a diagnostic that must report the same list. */
    public static Set<String> forbiddenKeywords() {
        return FORBIDDEN;
    }

    /** The admitted leading keywords, for a guard or a diagnostic that must report the same list. */
    public static List<String> admittedLeadingKeywords() {
        return ADMITTED_LEADING;
    }
}
