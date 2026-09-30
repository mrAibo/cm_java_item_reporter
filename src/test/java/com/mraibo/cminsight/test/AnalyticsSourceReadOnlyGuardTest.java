package com.mraibo.cminsight.test;

import com.mraibo.cminsight.db.SqlAdmission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Goal 03 section 7, hard requirement: the analytics JDBC source is structurally READ-ONLY, and this is
 * where that is asserted - together with section 15's "Guards" list.
 *
 * <h2>Why a source guard rather than a code review</h2>
 *
 * <p>{@code Connection.setReadOnly(true)} is a HINT under the JDBC contract: a driver may ignore it, and a
 * database account with write privileges would happily accept a {@code DELETE}. The only guarantee this
 * project can make is structural - the analytics source cannot express a write - and that is a property of a
 * tree that keeps growing, so it is checked rather than reviewed. The committed shell guard
 * {@code tests/shell/analytics_guard.sh} is the authority (it is the one a build step can run); this class
 * is the same rules expressed where the suite can assert them, including the mutation cases that make the
 * guard's silence on the real tree mean something.
 *
 * <h2>Four failure modes, each asserted rather than assumed</h2>
 *
 * <ul>
 *   <li><strong>A guard that scans nothing.</strong> A missing or empty analytics tree must FAIL, not pass:
 *       a rename that emptied it would otherwise disable the rule silently and forever.</li>
 *   <li><strong>A guard that cannot see a real violation.</strong> Positive cases plant a JDBC write call, a
 *       write-shaped statement literal and a stored-procedure escape into a temporary COPY of the tree - the
 *       real source set is never modified - and require each to be found and named.</li>
 *   <li><strong>A pattern table that quietly shrinks.</strong> The forbidden call table is parsed out of the
 *       committed shell guard, so a pattern deleted there fails {@link #forbiddenCallPatterns()} loudly
 *       instead of silently widening what the analytics layer may do.</li>
 *   <li><strong>The other direction - a rule so broad it cannot pass.</strong> Ordinary Java operations
 *       (list add/remove, an atomic set, a getter called {@code updateCount}) must NOT be refused. A guard
 *       that cannot pass is a guard that gets disabled, and this project has paid for that lesson once.</li>
 * </ul>
 *
 * <h2>The stronger half of the statement-literal rule lives here</h2>
 *
 * <p>The shell guard is line oriented, so a multi-line Java text block whose keyword is on its own line
 * escapes it. This class applies the same rule to the WHOLE file text with a multi-line pattern, and it runs
 * on every build, so the stronger check is the one that always runs.
 *
 * <h2>No relative paths</h2>
 *
 * <p>The tree is located from this class's own compiled location, not from {@code user.dir}: a suite that
 * only works from one working directory is a suite that silently scans the wrong tree when somebody runs it
 * another way - and scanning the wrong tree reports success.
 */
public class AnalyticsSourceReadOnlyGuardTest {

    /** The analytics JDBC source tree: the guard's subject, and required to be non-empty. */
    private static final String ANALYTICS_JDBC_SOURCE = "src/main/java/com/mraibo/cminsight/db";

    /** The scan coordinator's package: scanned as well, because a query is issued from here. */
    private static final String ANALYTICS_STATISTICS_SOURCE = "src/main/java/com/mraibo/cminsight/statistics";

    /** The committed shell guard whose tables and rules this suite mirrors. */
    private static final String SHELL_GUARD = "tests/shell/analytics_guard.sh";

    /** The whole core source tree, used for the "no driver type escapes" rule. */
    private static final String CORE_SOURCE = "src/main/java";

    /** The package that is allowed to hold a {@code java.sql} driver type at all. */
    private static final String DATABASE_PACKAGE = "src/main/java/com/mraibo/cminsight/db";

    /**
     * The application-local history package, the SECOND tree allowed to hold a {@code java.sql} type.
     *
     * <p>Goal 04 persists aggregate history into an embedded local H2 file, so this package necessarily names
     * {@code java.sql.Connection} and {@code PreparedStatement} - and, unlike the analytics path, it
     * legitimately issues {@code CREATE}, {@code INSERT}, {@code DELETE} and {@code commit} against ITS OWN
     * file. None of that reaches IBM CM or the repository database, which is what the rule protects.
     *
     * <p>This mirrors {@code HISTORY_EXEMPT_REL} in {@code tests/shell/analytics_guard.sh} exactly: same one
     * path, and the two are asserted to agree rather than kept in sync by hand. The exemption is a named
     * TREE and never a loosened pattern - widening the rule for all of {@code src/main/java} would delete the
     * guarantee for every future file in order to accommodate one package.
     */
    private static final String HISTORY_PACKAGE = "src/main/java/com/mraibo/cminsight/history";

    /**
     * The statement-literal rules of the analytics guard: a string literal carrying a write/control SQL
     * keyword as a WHOLE TOKEN, positioned where a statement can actually start it.
     *
     * <p>Each is anchored on the boundary a real statement keyword occupies - right after a quote, inside
     * parentheses, after a statement separator, or inside a Java text block - and each ends with a negated
     * identifier class so the keyword must be a whole token. That is what admits real identifiers such as
     * {@code UPDATED_AT}, {@code DELETED_FLAG}, {@code CREATE_TS} and {@code CREATED_TODAY}, which merely
     * CONTAIN one of these words. The shell guard writes that same class as the POSIX form
     * {@code [^[:alnum:]_]}, because its grep binds the ASCII-range spelling {@code [^A-Za-z0-9_]}
     * undefinedly; Java's regex engine needs no such workaround.
     *
     * <p>The quote-anchored rule additionally requires whitespace and then statement MATERIAL
     * ({@code [A-Za-z0-9_(]}) after the keyword. An earlier version also refused a literal whose ENTIRE
     * content was a verb ({@code "NEW"}, {@code "COMMIT"}), which could not tell a statement from an
     * ordinary one-word Java string; that rule was a false-positive machine for the most common literal
     * shape in a Java code base, so it was replaced rather than kept. The replacement is strictly better: it
     * catches every bare-verb STATEMENT ({@code "DELETE FROM X"}, {@code "COMMIT WORK"}) and admits a bare
     * verb WORD ({@code "DELETE"}), because a statement verb is always followed by the whitespace and the
     * token that a one-word string is not.
     *
     * <h2>Why this is no longer a prefix test</h2>
     *
     * <p>The old rule here - and the old shell rule - was "the literal begins with a write keyword", which is
     * exactly the mistake Goal 03A section D removes: it calls a SELECT-wrapped mutation clean because the
     * FIRST word of the literal is not a write verb. These patterns refuse a token wherever it really starts
     * a statement, so {@code "SELECT * FROM FINAL TABLE (DELETE FROM X)"}, a CTE carrying a DML token,
     * {@code "SELECT 1; DELETE FROM X"} and a token hidden behind a comment shape are all caught.
     *
     * <p>The rules are stated here rather than parsed out of the shell guard because the shell rules are ERE
     * written for whole lines and for grep, while this scanner reads a WHOLE FILE so it can see a text block
     * whose keyword is on the next line. The agreement that matters - the VOCABULARY both sides refuse - is
     * asserted against the shell guard and against {@code SqlAdmission} below, so a keyword removed from one
     * side fails here instead of drifting.
     */
    private static final List<Pattern> FORBIDDEN_LITERAL_PATTERNS = List.of(
            Pattern.compile("[\"'][ \t]*(" + SourceGuard.ANALYTICS_VERBS + ")[ \t]+[A-Za-z0-9_(]"),
            Pattern.compile("[(][ \t]*(" + SourceGuard.ANALYTICS_VERBS + ")[^A-Za-z0-9_]"),
            Pattern.compile("[;][ \t]*(" + SourceGuard.ANALYTICS_VERBS + ")[^A-Za-z0-9_]"),
            Pattern.compile("\"\"\"[ \t\r\n]*(" + SourceGuard.ANALYTICS_VERBS + ")"),
            // A comment form a generated statement never needs, which is the other way a token is hidden.
            Pattern.compile("[\"'][^\"']*([ \t][ \t]*--[ \t]*[A-Za-z]|/[*][ \t]*[A-Za-z])"));

    /** The JDBC stored-procedure escape, which only ever precedes a CALL. */
    private static final Pattern JDBC_CALL_ESCAPE = Pattern.compile("[{][ \t]*[Cc][Aa][Ll][Ll][^A-Za-z0-9_]");

    /**
     * The {@code java.sql} driver types that must never appear in the analytics layer's published surface
     * outside the database package.
     *
     * <p>Deliberately narrow: {@code SQLException} is not in the list, because a service may legitimately
     * translate one, and this rule is about the types that would hand a caller a live driver handle.
     */
    private static final Pattern DRIVER_HANDLE_TYPE = Pattern.compile(
            "java\\.sql\\.(Connection|Statement|PreparedStatement|CallableStatement|ResultSet)\\b");

    // ------------------------------------------------------------------ acceptance

    /** The committed analytics source holds no forbidden write call, no write literal and no CALL escape. */
    public void theCommittedAnalyticsSourceHoldsNoForbiddenWriteCall() {
        Path root = repositoryRoot();

        List<String> violations = findForbiddenWrites(root);

        Assert.assertTrue(violations.isEmpty(),
                "the analytics source must be structurally read-only, but found " + violations.size()
                        + " forbidden construct(s): " + violations + " (patterns applied: "
                        + describePatterns() + ")");
    }

    /** The guard has a real, non-empty subject, and the table it applies is real. */
    public void theGuardScansARealNonEmptyAnalyticsTree() {
        Path root = repositoryRoot();
        Path jdbc = root.resolve(ANALYTICS_JDBC_SOURCE);

        Assert.assertTrue(Files.isDirectory(jdbc),
                "the analytics JDBC source " + jdbc + " must exist for this guard to mean anything");
        List<Path> jdbcSources = javaFiles(jdbc);
        Assert.assertTrue(jdbcSources.size() >= 8,
                "the guard must see the analytics JDBC sources, but found only " + jdbcSources.size()
                        + " .java file(s) under " + jdbc + "; a guard scanning nothing reports success");
        List<Path> statisticsSources = javaFiles(root.resolve(ANALYTICS_STATISTICS_SOURCE));
        Assert.assertTrue(statisticsSources.size() >= 10,
                "and the scan coordinator's package too, but found only " + statisticsSources.size()
                        + " .java file(s)");

        Assert.assertTrue(forbiddenCallPatterns().size() >= 8,
                "the forbidden call table parsed to only " + forbiddenCallPatterns().size() + " pattern(s):"
                        + " the rules were weakened, or the table moved and this guard no longer reads it");
    }

    /**
     * The committed shell guard still carries the RULES, so the build step and this suite cannot drift.
     *
     * <h2>Pinned to the rule, not to a token name</h2>
     *
     * <p>This assertion used to demand the literal name {@code SQL_KEYWORD_PATTERN}. That is a pin on a
     * variable name rather than on the rule it carries, so it failed when the guard's literal rules were
     * split into their anchored forms even though every rule survived - a failure for a reason that is not a
     * defect. What is asserted now is the RULE: the guard must still refuse every keyword of the runtime
     * admission rule's own vocabulary, and the pattern name is free to change as long as the refusal is
     * still declared.
     *
     * <p>The vocabulary is read from {@code SqlAdmission.forbiddenKeywords()} rather than hardcoded here, so
     * this is one list with two readers instead of two hand-maintained copies of the same list.
     */
    public void theCommittedShellGuardStillCarriesTheRules() {
        Path script = repositoryRoot().resolve(SHELL_GUARD);
        Assert.assertTrue(Files.isRegularFile(script), "the committed shell guard " + SHELL_GUARD
                + " is the authority for these rules, and it is missing at " + script);
        String text = readString(script);

        // Structural rules: the call table, the literal-rule prologue and the fail-fast prologue.
        for (String required : List.of("FORBIDDEN_CALL_PATTERNS=(", "executeUpdate", "prepareCall", "commit",
                "rollback", "SQL_VERBS", "JDBC_CALL_ESCAPE_PATTERN", "EXEMPT_RELS", ANALYTICS_JDBC_SOURCE)) {
            Assert.assertTrue(text.contains(required),
                    "the committed guard must keep '" + required + "'; removing it would widen what the"
                            + " analytics layer may do while this suite reported success");
        }
        Assert.assertTrue(text.contains("set -euo pipefail"),
                "and it must keep its fail-fast prologue, or a failing grep would not stop it");

        // The VOCABULARY, read from the runtime rule rather than copied. Every keyword that rule refuses must
        // appear in the committed guard's text, or the two have drifted and one of them is now the weaker.
        List<String> missing = new ArrayList<>();
        for (String keyword : SqlAdmission.forbiddenKeywords()) {
            if (!text.contains(keyword)) {
                missing.add(keyword);
            }
        }
        Assert.assertTrue(missing.isEmpty(),
                "the committed shell guard no longer names " + missing + ", which the runtime admission rule"
                        + " refuses: the source guard and the runtime rule have drifted apart");

        // And the exemption is declared on the shell side exactly as it is on this side, in both directions.
        Assert.assertTrue(text.contains(SourceGuard.ANALYTICS_LITERAL_EXEMPT_RELATIVE),
                "the committed guard must declare the same exact-path literal exemption this suite applies ("
                        + SourceGuard.ANALYTICS_LITERAL_EXEMPT_RELATIVE + "), or the two guards disagree about"
                        + " which file the keyword-literal rule covers");
    }

    // ------------------------------------------------------------------ mutation cases

    /**
     * A planted write call IS found in a copied tree, so the clean result above means something.
     *
     * <p>Three plants, one per rule family: a JDBC write call, a statement literal that begins with a write
     * keyword, and the stored-procedure escape. Each must be reported and each report must NAME the file, so a
     * failure is actionable.
     */
    public void aPlantedWriteCallIsDetectedInACopiedTree() throws IOException {
        Path copy = copyAnalyticsTree();
        Path planted = copy.resolve(ANALYTICS_JDBC_SOURCE).resolve("PlantedViolation.java");
        Files.writeString(planted, """
                package com.mraibo.cminsight.db;

                /** Planted by AnalyticsSourceReadOnlyGuardTest: a forbidden JDBC write call. */
                final class PlantedViolation {
                    void writeThrough(java.sql.Statement statement) throws Exception {
                        statement.executeUpdate("DELETE FROM ICMUT00001001");
                    }
                }
                """, StandardCharsets.UTF_8);

        List<String> violations = findForbiddenWrites(copy);
        Assert.assertFalse(violations.isEmpty(),
                "the guard found nothing in a tree containing 'statement.executeUpdate(...)': the scanner"
                        + " does not bite, so its silence on the real tree proves nothing");
        Assert.assertTrue(violations.stream().anyMatch(entry -> entry.contains("PlantedViolation.java")),
                "and the violation must name the file that contains it, so a failure is actionable. Found: "
                        + violations);
    }

    /** A planted statement literal is found in a copied tree, including the multi-line text-block shape. */
    public void aPlantedWriteStatementLiteralIsDetectedInACopiedTree() throws IOException {
        Path copy = copyAnalyticsTree();
        Path planted = copy.resolve(ANALYTICS_JDBC_SOURCE).resolve("PlantedLiteral.java");
        Files.writeString(planted, """
                package com.mraibo.cminsight.db;

                /** Planted by AnalyticsSourceReadOnlyGuardTest: a write-shaped statement literal. */
                final class PlantedLiteral {
                    String statement() {
                        return \"\"\"
                                DELETE FROM ICMUT00001001
                                \"\"\";
                    }
                }
                """, StandardCharsets.UTF_8);

        List<String> violations = findForbiddenWrites(copy);
        Assert.assertTrue(violations.stream().anyMatch(entry -> entry.contains("PlantedLiteral.java")),
                "a write-shaped literal - here in a text block whose keyword is on the NEXT line, which a"
                        + " line-oriented scan cannot see - must be refused and named. Found: " + violations);
    }

    /**
     * A planted SELECT-WRAPPED write is found in a copied tree, one plant per mandatory shape.
     *
     * <p>This is the mutation control for Goal 03A section D. Every plant below starts with {@code SELECT}
     * or {@code WITH}, or hides its write token behind a comment - so a guard whose rule is "the literal
     * begins with a write verb" calls all of them clean. They must each be refused and named, which is what
     * makes the guard pair's structural claim about the real tree mean something.
     */
    public void plantedSelectWrappedWritesAreDetectedInACopiedTree() throws IOException {
        List<String> plants = List.of(
                "SELECT * FROM FINAL TABLE (DELETE FROM ICMADMIN.ICMUT00001001)",
                "WITH D AS (DELETE FROM ICMADMIN.ICMUT00001001) SELECT COUNT(*) FROM D",
                "WITH U AS (UPDATE ICMADMIN.ICMUT00001001 SET ITEMID = 1) SELECT COUNT(*) FROM U",
                "SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001; DELETE FROM ICMADMIN.ICMUT00001001",
                "SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001 -- DELETE FROM ICMADMIN.ICMUT00001001",
                "SELECT 1 AS PRESENT /* DELETE */ FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0");

        int index = 0;
        for (String plant : plants) {
            index++;
            Path copy = copyAnalyticsTree();
            int plantIndex = index;
            Path planted = copy.resolve(ANALYTICS_JDBC_SOURCE).resolve("Wrapped" + plantIndex + ".java");
            Files.writeString(planted, """
                    package com.mraibo.cminsight.db;

                    /** Planted by AnalyticsSourceReadOnlyGuardTest: a SELECT-wrapped write shape. */
                    final class Wrapped%d {
                        String statement() {
                            return "%s";
                        }
                    }
                    """.formatted(plantIndex, plant), StandardCharsets.UTF_8);

            List<String> violations = findForbiddenWrites(copy);
            Assert.assertTrue(violations.stream().anyMatch(entry -> entry.contains("Wrapped" + plantIndex
                            + ".java")),
                    "the SELECT-wrapped shape <" + plant + "> must be refused and named, because a rule that"
                            + " only checks the literal's FIRST word calls it clean. Found: " + violations);
        }
    }

    /** The JDBC CALL escape is found in a copied tree. */
    public void aPlantedStoredProcedureCallIsDetectedInACopiedTree() throws IOException {
        Path copy = copyAnalyticsTree();
        Path planted = copy.resolve(ANALYTICS_JDBC_SOURCE).resolve("PlantedCall.java");
        Files.writeString(planted, """
                package com.mraibo.cminsight.db;

                /** Planted by AnalyticsSourceReadOnlyGuardTest: a stored-procedure escape. */
                final class PlantedCall {
                    String statement() {
                        return "{call DO_SOMETHING(1)}";
                    }
                }
                """, StandardCharsets.UTF_8);

        List<String> violations = findForbiddenWrites(copy);
        Assert.assertTrue(violations.stream().anyMatch(entry -> entry.contains("PlantedCall.java")),
                "the JDBC CALL escape must be refused and named. Found: " + violations);
    }

    /**
     * The pre-plant control: a copy of the UNMODIFIED tree passes.
     *
     * <p>Without it, a scanner that refuses everything would satisfy every mutation case above while making
     * the acceptance case meaningless.
     */
    public void aCopyOfTheUnmodifiedTreePassesSoThePlantsAreTheCause() throws IOException {
        Path copy = copyAnalyticsTree();
        List<String> violations = findForbiddenWrites(copy);
        Assert.assertTrue(violations.isEmpty(),
                "an unmodified copy of the analytics tree must pass, otherwise the planted cases above prove"
                        + " only that the scanner refuses everything: " + violations);
    }

    /**
     * Ordinary Java operations are NOT refused.
     *
     * <p>The assertion that stops the next person from "fixing" a false positive by deleting the guard - and
     * the assertion that keeps the SQL-keyword rule anchored on the opening quote, so English prose and a
     * getter called {@code updateCount()} stay legal.
     */
    public void ordinaryJavaOperationsAreNotRefused() throws IOException {
        Path copy = copyAnalyticsTree();
        Files.writeString(copy.resolve(ANALYTICS_JDBC_SOURCE).resolve("LocalOnly.java"), """
                package com.mraibo.cminsight.db;

                import java.util.ArrayList;
                import java.util.HashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.atomic.AtomicBoolean;

                /**
                 * Scratch fixture: a commit is never issued here, and a rollback is not either - the words
                 * appear in prose on purpose, so the guard must be anchored on a call parenthesis.
                 */
                final class LocalOnly {
                    private final List<String> names = new ArrayList<>();
                    private final Map<String, Long> counters = new HashMap<>();
                    private final AtomicBoolean closed = new AtomicBoolean(false);

                    int resembling() {
                        return names.size() + counters.size() + (closed.get() ? 1 : 0);
                    }

                    String selectOnly() {
                        return "SELECT COUNT(DISTINCT ITEMID) FROM ICMADMIN.ICMUT00001001";
                    }

                    // The TOKEN-AWARE controls: every one of these is a real column name or a real
                    // statement that merely CONTAINS a keyword fragment. A rule that refused them would
                    // refuse real schema and would then be deleted by whoever hit it first.
                    String tokenAware() {
                        return "SELECT UPDATED_AT, DELETED_FLAG, CREATE_TS FROM ICMADMIN.ICMUT00001001";
                    }

                    String moreTokenAware() {
                        return "SELECT NEW_ITEM_ID, OLD_ITEM_ID, CREATED_TODAY FROM X";
                    }

                    // One-word ordinary strings. "NEW" is a state label and "COMMIT" is a log key, not a
                    // statement; a rule that refused every one-word literal would refuse this shape
                    // everywhere in a Java code base and would then be deleted by whoever hit it first.
                    String bareWordLiterals() {
                        return "NEW" + "COMMIT" + "reads";
                    }

                    String concatenatedLiterals() {
                        return "SELECT " + "COUNT(DISTINCT ITEMID) FROM ICMADMIN.ICMUT00001001";
                    }

                    String diagnosticProse() {
                        return "ItemType 1 has no physical root table; every segment 1..N must be present";
                    }

                    String notAComment() {
                        return "a/b*c and 1--2 are not SQL comments";
                    }

                    java.sql.ResultSet theOneQueryPath(java.sql.PreparedStatement ps) throws Exception {
                        return ps.executeQuery();
                    }

                    void bookkeeping() {
                        names.add("itemType");
                        names.remove(0);
                        names.clear();
                        counters.put("reads", 1L);
                        counters.remove("reads");
                        closed.set(true);
                    }
                }
                """, StandardCharsets.UTF_8);

        List<String> violations = findForbiddenWrites(copy);
        Assert.assertTrue(violations.isEmpty(),
                "ordinary java.util and wrapper operations, a SELECT-only literal, English prose and the"
                        + " token-aware column names UPDATED_AT/DELETED_FLAG/CREATE_TS/NEW_ITEM_ID must not"
                        + " be refused; a guard that cannot pass gets disabled. Found: " + violations);
    }

    // ------------------------------------------------------------------ the exemption and its controls

    /**
     * The exempt file EXISTS, so a stale exemption is reported instead of silently widening the rule.
     *
     * <p>An exemption for a path that no longer exists is the quiet failure mode: the rule then covers
     * everything, every file passes, and nobody learns that the file the exemption was written for moved.
     */
    public void theLiteralExemptionNamesAFileThatExists() {
        Path exempt = repositoryRoot().resolve(SourceGuard.ANALYTICS_LITERAL_EXEMPT_RELATIVE);
        Assert.assertTrue(Files.isRegularFile(exempt),
                "the literal exemption names " + SourceGuard.ANALYTICS_LITERAL_EXEMPT_RELATIVE + ", which does"
                        + " not exist: the exemption is stale, or the file moved and the exemption now covers"
                        + " nothing while the guard reports a clean tree");

        Path root = repositoryRoot();
        Assert.assertTrue(SourceGuard.isAnalyticsLiteralExempt(root, exempt),
                "the exemption must recognise the exact repository-relative path it names");
        Assert.assertFalse(SourceGuard.isAnalyticsLiteralExempt(root,
                        exempt.resolveSibling("SqlIdentifiers.java")),
                "and must not recognise any other file in the same package");
        Assert.assertFalse(SourceGuard.isAnalyticsLiteralExempt(root,
                        root.resolve("src/main/java/com/mraibo/cminsight/statistics/review/SqlAdmission.java")),
                "and a same-basename sibling in another analytics package must not inherit the exemption");
    }

    /**
     * CONTROL: a file combining the exempt vocabulary with a REAL offending construct is still refused.
     *
     * <p>This is the control that makes the exemption honest. It plants a copy of the exempt file with a
     * genuine {@code executeUpdate} call appended, so the file carries both the exempt vocabulary and a real
     * violation. The scanner must still report it - which fails if the exemption is implemented by skipping
     * the whole file, and is exactly how an exemption becomes a hole.
     */
    public void aPlantCombiningTheExemptVocabularyWithARealWriteIsStillRefused() throws IOException {
        Path copy = copyAnalyticsTree();
        Path planted = copy.resolve(SourceGuard.ANALYTICS_LITERAL_EXEMPT_RELATIVE);
        Assert.assertTrue(Files.isRegularFile(planted),
                "the control needs the exempt file to exist in the copied tree, but it is missing at " + planted);
        String original = Files.readString(planted, StandardCharsets.UTF_8);
        Assert.assertTrue(original.contains("DELETE") || original.contains("INSERT"),
                "the control requires the exempt file to really carry the write/control vocabulary, otherwise"
                        + " it proves nothing about the exemption");

        String plantedText = original
                + "\n/** Planted by AnalyticsSourceReadOnlyGuardTest: a real write-capable call. */\n"
                + "final class PlantedWriteIntoTheExemptFile {\n"
                + "    void writeThrough(java.sql.Statement statement) throws Exception {\n"
                + "        statement.executeUpdate(\"DELETE FROM ICMUT00001001\");\n"
                + "    }\n"
                + "}\n";
        Files.writeString(planted, plantedText, StandardCharsets.UTF_8);

        List<String> violations = findForbiddenWrites(copy);
        Assert.assertFalse(violations.isEmpty(),
                "the exemption covers the KEYWORD-LITERAL rule only; a file that also carries a real"
                        + " executeUpdate call must still be refused, or the exemption is a hole through which"
                        + " the admission layer could acquire a write path");
        Assert.assertTrue(violations.stream().anyMatch(entry -> entry.contains("SqlAdmission.java")),
                "and the refusal must name the exempt file, so the finding is actionable. Found: " + violations);
    }

    /**
     * Same-basename siblings receive NO exemption: only the canonical repository-relative path is exempt.
     */
    public void sameBasenameSiblingsAreNotExemptFromLiteralRules() throws IOException {
        for (String relative : List.of(
                "src/main/java/com/mraibo/cminsight/statistics/review/SqlAdmission.java",
                "src/main/java/com/mraibo/cminsight/db/review/SqlAdmission.java")) {
            Path copy = copyAnalyticsTree();
            Path planted = copy.resolve(relative);
            Files.createDirectories(planted.getParent());
            Files.writeString(planted, """
                    package review;
                    final class SqlAdmission {
                        String statement() { return "DELETE FROM ICMUT00001001"; }
                    }
                    """, StandardCharsets.UTF_8);

            List<String> violations = findForbiddenWrites(copy);
            Assert.assertTrue(violations.stream().anyMatch(entry -> entry.replace('\\', '/').contains(relative)),
                    "a same-basename sibling must not inherit the canonical literal exemption: " + relative
                            + "; found " + violations);
        }
    }

    /** A missing canonical exempt path is a stale contract and must fail the Java guard loudly. */
    public void removingTheCanonicalLiteralExemptPathFailsTheGuard() throws IOException {
        Path copy = copyAnalyticsTree();
        Path exempt = copy.resolve(SourceGuard.ANALYTICS_LITERAL_EXEMPT_RELATIVE);
        Assert.assertTrue(Files.deleteIfExists(exempt), "the copied canonical exempt file must exist");
        AssertionError failure = Assert.assertThrows(AssertionError.class,
                () -> findForbiddenWrites(copy),
                "a stale exact-path exemption must fail instead of silently covering nothing");
        Assert.assertTrue(failure.getMessage().contains(SourceGuard.ANALYTICS_LITERAL_EXEMPT_RELATIVE),
                "the stale-exemption failure must name the exact path: " + failure.getMessage());
    }

    /**
     * The committed shell guard and this suite refuse the SAME vocabulary, read from the runtime rule.
     *
     * <p>The agreement is asserted rather than maintained by hand, so the next person to change one side
     * cannot leave the other behind - the reason this class exists beside a shell script at all.
     */
    public void theTwoGuardsRefuseTheSameVocabulary() {
        Set<String> vocabulary = new TreeSet<>(SqlAdmission.forbiddenKeywords());
        Assert.assertFalse(vocabulary.isEmpty(),
                "the runtime admission rule must name its vocabulary; an empty list would make this agreement"
                        + " assertion vacuous");

        String shellText = readString(repositoryRoot().resolve(SHELL_GUARD));
        List<String> missingFromShell = new ArrayList<>();
        for (String keyword : vocabulary) {
            if (!shellText.contains(keyword)) {
                missingFromShell.add(keyword);
            }
        }
        Assert.assertTrue(missingFromShell.isEmpty(),
                "the committed shell guard does not name " + missingFromShell + ", which the runtime admission"
                        + " rule refuses: the text guard and the runtime rule disagree about the vocabulary");

        String javaVerbs = SourceGuard.ANALYTICS_VERBS;
        List<String> missingFromJava = new ArrayList<>();
        for (String keyword : vocabulary) {
            if (!javaVerbs.contains(keyword)) {
                missingFromJava.add(keyword);
            }
        }
        Assert.assertTrue(missingFromJava.isEmpty(),
                "this suite's literal rules do not name " + missingFromJava + ", which the runtime admission"
                        + " rule refuses: the Java twin would be the weaker guard");
    }

    /**
     * A tree with no analytics source FAILS the guard instead of passing.
     *
     * <p>The failure mode that would disable the rule without anybody noticing: a rename, a moved source set,
     * or a build that only compiles the core. Reporting success for an unscanned tree is the one outcome this
     * guard must never produce.
     */
    public void aMissingAnalyticsTreeFailsInsteadOfPassing() throws IOException {
        Path copy = copyAnalyticsTree();
        deleteRecursively(copy.resolve(ANALYTICS_JDBC_SOURCE));

        AssertionError failure = Assert.assertThrows(AssertionError.class,
                () -> findForbiddenWrites(copy),
                "scanning a tree with no " + ANALYTICS_JDBC_SOURCE + " must fail loudly, not report a clean"
                        + " result");
        Assert.assertTrue(failure.getMessage().replace('\\', '/').contains(ANALYTICS_JDBC_SOURCE),
                "and the failure must name the directory it could not scan: " + failure.getMessage());
    }

    // ------------------------------------------------------------------ the API boundary

    /**
     * Section 7: no public/service/web method exposes {@code Connection}, {@code Statement} or
     * {@code ResultSet}.
     *
     * <p>Asserted as a property of the tree: outside the database package, no CODE line may name one of those
     * types. A javadoc {@code {@link}} is not a published type - it hands nobody a handle - so purely
     * documentary mentions are excluded, and the rule stays narrow enough to be satisfiable.
     */
    public void noJdbcDriverHandleTypeEscapesTheDatabasePackage() {
        Path root = repositoryRoot();
        Path core = root.resolve(CORE_SOURCE);
        Assert.assertTrue(Files.isDirectory(core),
                "the boundary guard must scan " + core + ", which does not exist; an unscanned tree is a"
                        + " layout failure, not a clean result");

        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(core)) {
            String relative = relative(root, file);
            if (relative.startsWith(DATABASE_PACKAGE) || relative.startsWith(HISTORY_PACKAGE)) {
                continue;
            }
            List<String> lines = readLines(file);
            for (int index = 0; index < lines.size(); index++) {
                if (isCommentLine(lines.get(index))) {
                    continue;
                }
                if (DRIVER_HANDLE_TYPE.matcher(lines.get(index)).find()) {
                    violations.add(relative + ":" + (index + 1) + ": " + lines.get(index).trim());
                }
            }
        }
        Assert.assertTrue(violations.isEmpty(),
                "no code outside " + DATABASE_PACKAGE + " and " + HISTORY_PACKAGE + " may name a JDBC driver"
                        + " handle type, or the web layer could receive one: " + violations);
    }

    /**
     * The history exemption is real, non-empty, and cannot be widened.
     *
     * <p>Three things are asserted, and each exists because of a way an exemption rots. The tree must EXIST
     * and hold at least one source file, or the exemption has outlived its subject while still suppressing
     * the rule. The committed shell guard must name the SAME path, or the two guards disagree about which
     * tree is special - which has already happened twice in this project. And the exemption must be scoped to
     * the handle-type rule for that one tree: a plant carrying a driver handle type in the SCANNED sibling
     * tree must still be refused, which is what stops "exempt the history package" from quietly becoming
     * "exempt everything".
     */
    public void theHistoryExemptionIsRealNonEmptyAndCannotBeWidened() throws IOException {
        Path root = repositoryRoot();
        Path history = root.resolve(HISTORY_PACKAGE);
        Assert.assertTrue(Files.isDirectory(history),
                "the history exemption names " + HISTORY_PACKAGE + ", which does not exist. An exemption with"
                        + " no tree behind it suppresses a rule for nothing");

        List<Path> historyFiles = javaFiles(history);
        Assert.assertFalse(historyFiles.isEmpty(),
                "the history exemption must cover a real, non-empty source tree; an empty one means the"
                        + " exception outlived the thing it was written for");

        String script = readString(SourceGuard.analyticsShellGuard());
        Assert.assertTrue(script.contains("HISTORY_EXEMPT_REL"),
                "the committed shell guard must declare the same named history exemption, or the two guards"
                        + " disagree about which tree is special");
        Assert.assertTrue(script.contains(HISTORY_PACKAGE),
                "the shell guard's exemption must name the same path '" + HISTORY_PACKAGE + "' that this suite"
                        + " exempts; a different value means one of them is enforcing a rule the other cannot"
                        + " see");

        // The other direction: the same construct OUTSIDE the exempt tree is still refused.
        Path copy = copyAnalyticsTree();
        try {
            Path planted = copy.resolve(ANALYTICS_STATISTICS_SOURCE).resolve("PlantedDriverHandle.java");
            Files.createDirectories(planted.getParent());
            Files.writeString(planted, """
                    package com.mraibo.cminsight.statistics;

                    import java.sql.Connection;

                    final class PlantedDriverHandle {
                        Connection handle;
                    }
                    """, java.nio.charset.StandardCharsets.UTF_8);

            List<String> escaped = new ArrayList<>();
            for (Path file : javaFiles(copy.resolve(CORE_SOURCE))) {
                String relative = relative(copy, file);
                if (relative.startsWith(DATABASE_PACKAGE) || relative.startsWith(HISTORY_PACKAGE)) {
                    continue;
                }
                for (String line : readLines(file)) {
                    if (!isCommentLine(line) && DRIVER_HANDLE_TYPE.matcher(line).find()) {
                        escaped.add(relative);
                    }
                }
            }
            Assert.assertTrue(escaped.stream().anyMatch(entry -> entry.contains("PlantedDriverHandle")),
                    "a driver handle type planted OUTSIDE the exempt tree must still be refused, or the"
                            + " history exemption has widened into a hole. Found: " + escaped);
        } catch (java.io.IOException failure) {
            throw new AssertionError("could not build the exemption control: " + failure, failure);
        } finally {
            deleteRecursively(copy);
        }
    }

    /** True for a line that is entirely a comment or javadoc continuation, which publishes no type. */
    private static boolean isCommentLine(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*");
    }

    // ------------------------------------------------------------------ tracked artefacts

    /**
     * Zero proprietary DB driver JARs are tracked, and zero exist in the working tree.
     *
     * <p>Goal 03 section 5: vendor JARs are an OPTIONAL runtime input and are never downloaded or committed.
     * Both halves are checked, because they fail for different reasons: a tracked jar is a repository defect,
     * while a jar present but untracked is a local artefact that `.gitignore` must already cover.
     */
    public void noProprietaryDatabaseDriverJarIsTrackedOrPresent() throws Exception {
        Path root = repositoryRoot();
        List<String> tracked = gitLines(root, "ls-files");
        Assert.assertFalse(tracked.isEmpty(),
                "the tracked-file list came back empty, so this check cannot distinguish 'nothing tracked'"
                        + " from 'git did not answer'");

        List<String> trackedJars = new ArrayList<>();
        for (String entry : tracked) {
            if (entry.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                trackedJars.add(entry);
            }
        }
        Assert.assertTrue(trackedJars.isEmpty(),
                "no JAR may be committed, least of all a proprietary database driver: " + trackedJars);

        // Scoped to lib/ on purpose: an unscoped `--ignored` walks the whole working tree, including the
        // build output, and that took over 25 seconds here - long enough to risk the suite's per-test
        // timeout and turn a green guard into a red build for the wrong reason.
        List<String> ignored = gitLines(root, "status", "--porcelain", "--ignored", "--", "lib");
        for (String entry : ignored) {
            String path = entry.length() > 3 ? entry.substring(3).trim() : entry.trim();
            String lower = path.toLowerCase(Locale.ROOT);
            Assert.assertFalse(lower.endsWith(".jar") && (lower.contains("db2") || lower.contains("oracle")
                            || lower.contains("jdbc") || lower.contains("lib/")),
                    "a vendor driver JAR must not even exist in the working tree as an ignored artefact;"
                            + " proprietary drivers are placed by the operator at runtime: " + path);
        }

        for (String vendorDirectory : List.of("lib/db2", "lib/oracle")) {
            Path directory = root.resolve(vendorDirectory);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(directory)) {
                List<String> jars = entries.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .sorted()
                        .toList();
                Assert.assertTrue(jars.isEmpty(),
                        vendorDirectory + " must hold no driver JAR in this checkout: " + jars);
            }
        }
    }

    /** The output lines of a git command run in the repository root. */
    private static List<String> gitLines(Path root, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        Assert.assertEquals(0, exit, "git " + String.join(" ", arguments) + " failed, so the tracked-artefact"
                + " check has no subject; output: " + output.replace('\n', ' ').trim());
        List<String> lines = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------ the scanner

    /**
     * Every forbidden construct under the analytics trees, as {@code path:line: text} entries.
     *
     * <h2>The one exemption, and why it is a SUB-rule</h2>
     *
     * <p>{@code SqlAdmission.java} is exempt from the STATEMENT-LITERAL rules only, because it is the layer
     * that refuses that vocabulary and a refusal layer must be able to name what it refuses: without the
     * exemption the rule forbids its own enforcement mechanism and the guard could not pass at all.
     *
     * <p>The exemption deliberately does NOT skip the JDBC call rules for that file, and it is keyed on the
     * exact repository-relative PATH so a copied tree gets the same answer without exempting same-named
     * siblings. So a file that carries both the exempt vocabulary
     * and a real offending construct is still refused - which is the control
     * {@link #aPlantCombiningTheExemptVocabularyWithARealWriteIsStillRefused} proves, because an exemption
     * implemented by skipping the whole file would pass a weaker test and hide a real hole.
     *
     * @param root the tree to scan, which may be a temporary copy
     */
    private static List<String> findForbiddenWrites(Path root) {
        Path exempt = root.resolve(SourceGuard.ANALYTICS_LITERAL_EXEMPT_RELATIVE);
        Assert.assertTrue(Files.isRegularFile(exempt),
                "the analytics literal exemption is stale: expected the exact path "
                        + SourceGuard.ANALYTICS_LITERAL_EXEMPT_RELATIVE + " under " + root
                        + "; a missing exempt target is a guard failure, not a clean tree");
        Path jdbc = root.resolve(ANALYTICS_JDBC_SOURCE);
        Assert.assertTrue(Files.isDirectory(jdbc),
                "the analytics read-only guard must scan " + jdbc + ", which does not exist. A missing source"
                        + " directory is a LAYOUT failure, not a clean result: a guard that scans nothing"
                        + " reports success forever");
        List<Path> directories = new ArrayList<>();
        directories.add(jdbc);
        Path statistics = root.resolve(ANALYTICS_STATISTICS_SOURCE);
        if (Files.isDirectory(statistics)) {
            directories.add(statistics);
        }

        List<Pattern> patterns = new ArrayList<>();
        for (String raw : forbiddenCallPatterns()) {
            try {
                patterns.add(Pattern.compile("(" + raw + ")"));
            } catch (PatternSyntaxException failure) {
                throw new AssertionError("the shell guard pattern '" + raw + "' is not usable as a Java"
                        + " regex, so this test cannot enforce it: " + failure.getMessage(), failure);
            }
        }

        List<String> violations = new ArrayList<>();
        for (Path directory : directories) {
            for (Path file : javaFiles(directory)) {
                String relative = relative(root, file);
                List<String> lines = readLines(file);
                String text = String.join("\n", lines);

                // The call rules apply to EVERY file, exempt or not: skipping them would turn the exemption
                // into a hole through which a write-capable call could be added to the admission layer.
                for (int index = 0; index < lines.size(); index++) {
                    for (Pattern pattern : patterns) {
                        Matcher matcher = pattern.matcher(lines.get(index));
                        if (matcher.find()) {
                            violations.add(relative + ":" + (index + 1) + ": " + lines.get(index).trim());
                            break;
                        }
                    }
                }
                violations.addAll(matchWholeFile(relative, text, JDBC_CALL_ESCAPE,
                        "the JDBC stored-procedure CALL escape"));

                // The literal rules, which the one documented file is exempt from.
                if (SourceGuard.isAnalyticsLiteralExempt(root, file)) {
                    continue;
                }
                for (Pattern pattern : FORBIDDEN_LITERAL_PATTERNS) {
                    violations.addAll(matchWholeFile(relative, text, pattern,
                            "a statement literal carrying a write/control SQL keyword as a whole token"));
                }
            }
        }
        return violations;
    }

    /**
     * Whole-file matches, reported with their line number.
     *
     * <p>Deliberately not line-based: a Java text block puts the keyword on a line of its own, and a
     * line-oriented scan cannot see it. This is the stronger half of the rule.
     */
    private static List<String> matchWholeFile(String relative, String text, Pattern pattern, String what) {
        List<String> violations = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int line = 1;
            for (int index = 0; index < matcher.start() && index < text.length(); index++) {
                if (text.charAt(index) == '\n') {
                    line++;
                }
            }
            violations.add(relative + ":" + line + ": " + what);
        }
        return violations;
    }

    /**
     * The forbidden call patterns, parsed from the committed shell guard's own table.
     *
     * <p>One table, one place to change it: a pattern added to the shell guard is enforced here immediately,
     * and a pattern deleted there makes this method fail loudly rather than silently shrinking the rule.
     */
    static List<String> forbiddenCallPatterns() {
        Path script = repositoryRoot().resolve(SHELL_GUARD);
        Assert.assertTrue(Files.isRegularFile(script),
                "the committed shell guard " + SHELL_GUARD + " is the authority for the forbidden call table,"
                        + " and it is missing at " + script);
        List<String> patterns = new ArrayList<>();
        boolean inTable = false;
        for (String line : readLines(script)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("FORBIDDEN_CALL_PATTERNS=(")) {
                inTable = true;
                continue;
            }
            if (inTable && trimmed.startsWith(")")) {
                inTable = false;
                continue;
            }
            if (!inTable || trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            Matcher entry = Pattern.compile("^'(.+)'$").matcher(trimmed);
            if (entry.matches()) {
                patterns.add(entry.group(1));
            }
        }
        Assert.assertFalse(patterns.isEmpty(),
                "the forbidden call table in " + SHELL_GUARD + " parsed to zero patterns; this guard would"
                        + " then enforce nothing while reporting success");
        return patterns;
    }

    /** What the guard covers, for a failure message: an operator reading it must know which table applied. */
    private static String describePatterns() {
        Set<String> described = new TreeSet<>();
        for (String pattern : forbiddenCallPatterns()) {
            described.add(pattern.replace("[[:space:]]*\\(", "()").replace("\\(", "()"));
        }
        described.add("statement literals: " + SourceGuard.ANALYTICS_VERBS.replace("|", "/"));
        described.add("SQL comment forms inside a statement literal");
        described.add("the JDBC {call ...} escape");
        described.add("literal exemption (keyword rule only): " + SourceGuard.ANALYTICS_LITERAL_EXEMPT_RELATIVE);
        return String.join(", ", described);
    }

    // ------------------------------------------------------------------ filesystem helpers

    /** The repository root, located from this class's own compiled location. */
    static Path repositoryRoot() {
        try {
            java.security.CodeSource codeSource =
                    AnalyticsSourceReadOnlyGuardTest.class.getProtectionDomain().getCodeSource();
            Assert.assertNotNull(codeSource,
                    "the test classes must have a code source; without one the source tree cannot be located,"
                            + " and a guard that cannot locate its subject would have to guess");
            Path classes = java.nio.file.Paths.get(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
            Path current = classes;
            while (current != null) {
                if (Files.isDirectory(current.resolve("src"))
                        && Files.isRegularFile(current.resolve(SHELL_GUARD))) {
                    return current;
                }
                current = current.getParent();
            }
            throw new AssertionError("could not locate the repository root above " + classes
                    + ": expected an ancestor holding src/ and " + SHELL_GUARD);
        } catch (java.net.URISyntaxException failure) {
            throw new AssertionError("the code source location is not a usable path: " + failure, failure);
        }
    }

    private static Path copyAnalyticsTree() throws IOException {
        Path source = repositoryRoot();
        Path copy = TestSupport.newTempDir("analytics-guard-copy");
        for (String relative : List.of(ANALYTICS_JDBC_SOURCE, ANALYTICS_STATISTICS_SOURCE)) {
            copyDirectory(source.resolve(relative), copy.resolve(relative));
        }
        return copy;
    }

    private static void copyDirectory(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(from)) {
            for (Path path : stream.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    /** Every {@code .java} file under a directory, in stable order. */
    private static List<Path> javaFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new AssertionError("the guard must scan " + directory + ", which does not exist; an"
                    + " unscanned tree is a layout failure, not a clean result");
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException failure) {
            throw new AssertionError("cannot walk " + directory + ": " + failure, failure);
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("cannot read " + file + ": " + failure, failure);
        }
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("cannot read " + file + ": " + failure, failure);
        }
    }
}
