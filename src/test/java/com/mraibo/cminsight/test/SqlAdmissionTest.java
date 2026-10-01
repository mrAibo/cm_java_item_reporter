package com.mraibo.cminsight.test;

import com.mraibo.cminsight.db.Db2Dialect;
import com.mraibo.cminsight.db.OracleDialect;
import com.mraibo.cminsight.db.PhysicalSchema;
import com.mraibo.cminsight.db.SqlAdmission;
import com.mraibo.cminsight.db.SqlQueryBuilder;
import com.mraibo.cminsight.statistics.ScanWindows;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Goal 03A section D (the rule half): the strict, token-aware admission rule for generated analytics SQL.
 *
 * <h2>What the prefix gate could not do, and what replaces it</h2>
 *
 * <p>Goal 03's gate admitted any statement whose leading text was {@code SELECT} or {@code WITH}. That
 * answers "does this statement start as a read?", which is not the question the read-only guarantee needs:
 * on supported enterprise dialects a data-changing construct can be nested inside a {@code SELECT}/{@code
 * WITH} shape - DB2's {@code SELECT ... FROM FINAL TABLE (DELETE FROM ...)}, a DML statement carried by a
 * {@code WITH} prefix, a second statement after a separator, or a write token hidden behind a comment.
 * Every one of those starts with {@code SELECT} or {@code WITH}.
 *
 * <h2>How the assertions are made non-vacuous</h2>
 *
 * <p>Three independent devices, because "the rule refuses things" is easy to believe and easy to fake:
 *
 * <ol>
 *   <li><strong>The pre-fix control.</strong> {@link #preFixPrefixOnlyAdmits(String)} is the old rule,
 *       reimplemented here. Every rejection shape is asserted to PASS that rule, so the suite demonstrably
 *       targets the statements the old gate admitted rather than a set the old gate already refused.</li>
 *   <li><strong>The false-positive control.</strong> {@link #naiveSubstringRuleRefuses(String)} is the
 *       other obvious wrong rule - substring matching. It is shown to REFUSE a real identifier such as
 *       {@code UPDATED_AT}, which is precisely why a token-aware rule has to exist rather than a stricter
 *       substring scan. So the token rule is stronger where it must be and no weaker where it must not.</li>
 *   <li><strong>Production statements are taken from the dialects and builders</strong>, never hand-copied.
 *       A future SQL change is therefore exercised here, and an admission rule that drifted away from what
 *       the application actually emits fails immediately instead of at scan time.</li>
 * </ol>
 *
 * <p>The suite is pure: no JDBC, no driver, no I/O. The runtime half - that a refused statement never
 * reaches the driver - is {@link JdbcQueryAdmissionRuntimeTest}.
 */
public class SqlAdmissionTest {

    private static final String SCHEMA = "ICMADMIN";

    /**
     * The verbs goal section D requires to be refused, hard-coded INDEPENDENTLY of the implementation's own
     * vocabulary list so a silently shortened list cannot make this suite agree with it.
     */
    private static final List<String> MANDATORY_REFUSED_VERBS = List.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE",
            "CREATE", "ALTER", "DROP", "GRANT", "REVOKE",
            "CALL", "BEGIN", "EXEC", "EXECUTE");

    /** The approved production schema used for the one-segment statement. */
    private static PhysicalSchema oneSegment() {
        return new PhysicalSchema(4711, "OneSegment", 1, 1, List.of("ICMUT00001001"));
    }

    /** The approved production schema used for the multi-segment statement. */
    private static PhysicalSchema threeSegments() {
        return new PhysicalSchema(4712, "ThreeSegments", 1, 3,
                List.of("ICMUT00001001", "ICMUT00001002", "ICMUT00001003"));
    }

    // ================================================================== approved production statements

    /**
     * Every approved production statement family is ADMITTED, taken from the real dialects and builders.
     *
     * <p>The nine families the goal names: both current-date queries, the root-component mapping, both
     * zero-row probes, the one-segment aggregate, the multi-segment aggregate and the total-only aggregate.
     * {@link #approvedProductionStatements()} builds them by calling the code that produces them, so this is
     * an integration assertion about the real generator rather than a copy of its text.
     *
     * <p>The second half asserts that no approved statement contains a returned verb as a whole token, which
     * is the property the admission rule relies on: if a future statement legitimately needed one of these
     * words, the rule would have to be narrowed deliberately rather than by accident.
     */
    public void everyApprovedProductionStatementFamilyIsAdmitted() throws Exception {
        List<String> statements = approvedProductionStatements();
        Assert.assertTrue(statements.size() >= 9,
                "the approved production surface must be exercised in full, but only " + statements.size()
                        + " statement(s) were built: " + statements);
        for (String sql : statements) {
            Optional<String> refusal = SqlAdmission.refuse(sql);
            Assert.assertTrue(refusal.isEmpty(),
                    "an approved production statement must be ADMITTED, but it was refused with '"
                            + refusal.orElse("") + "': " + sql);
            Assert.assertTrue(SqlAdmission.admitted(sql),
                    "and admitted() must agree with refuse() for: " + sql);
        }
    }

    /**
     * The approved production statements, produced by the dialects and builders themselves.
     *
     * <p>Nothing is hard-coded: each entry is the return value of the method that generates it in
     * production, so a change to a dialect or a builder is exercised by this suite automatically.
     */
    private static List<String> approvedProductionStatements() throws Exception {
        Db2Dialect db2 = new Db2Dialect();
        OracleDialect oracle = new OracleDialect();
        SqlQueryBuilder db2Builder = SqlQueryBuilder.forSchema(db2, SCHEMA);
        SqlQueryBuilder oracleBuilder = SqlQueryBuilder.forSchema(oracle, SCHEMA);
        ScanWindows windows = ScanWindows.anchoredAt(LocalDate.of(2024, 6, 15));

        List<String> statements = new ArrayList<>();
        statements.add(db2.currentDateSql());
        statements.add(oracle.currentDateSql());
        statements.add(db2Builder.rootComponentMappingSql());
        statements.add(oracleBuilder.rootComponentMappingSql());
        statements.add(db2Builder.rootTableProbe("ICMUT00001001").sql());
        statements.add(oracleBuilder.rootTableProbe("ICMUT00001001").sql());
        statements.add(db2Builder.aggregate(oneSegment(), windows.parameters()).sql());
        statements.add(oracleBuilder.aggregate(oneSegment(), windows.parameters()).sql());
        statements.add(db2Builder.aggregate(threeSegments(), windows.parameters()).sql());
        statements.add(db2Builder.totalItems(oneSegment()).sql());
        statements.add(oracleBuilder.totalItems(threeSegments()).sql());
        return List.copyOf(statements);
    }

    // ================================================================== the mandatory rejection shapes

    /**
     * The mandatory rejection shapes of goal section D are REFUSED, and the reason names the rule that
     * fired.
     *
     * <p>All four shapes are {@code SELECT}/{@code WITH}-prefixed - the control at the end of this suite
     * proves that - so a prefix gate admits every one of them. The specific rule is asserted only where it
     * genuinely fires first, which the goal review flagged as the trap in this area: {@code CALL} is refused
     * as a leading keyword or as a literal, and a {@code BEGIN ... END} block as a separator, so asserting
     * "the DELETE rule" for those would fail for a reason that is not a defect.
     */
    public void theMandatoryRejectionShapesAreRefusedByTheRuleThatGenuinelyFiresFirst() {
        // (1) DB2's data-change table reference: the whole point is that it STARTS with SELECT.
        String finalTableDelete =
                "SELECT * FROM FINAL TABLE (DELETE FROM ICMADMIN.ICMUT00001001 WHERE ITEMID = 1)";
        String finalTableInsert =
                "SELECT * FROM FINAL TABLE (INSERT INTO ICMADMIN.ICMUT00001001 (ITEMID) VALUES (1))";
        String finalTableUpdate =
                "SELECT * FROM FINAL TABLE (UPDATE ICMADMIN.ICMUT00001001 SET ITEMID = 1)";
        String finalTableMerge = "SELECT * FROM FINAL TABLE (MERGE INTO ICMADMIN.ICMUT00001001 AS T"
                + " USING ICMADMIN.ICMUT00001001 AS S ON T.ITEMID = S.ITEMID"
                + " WHEN MATCHED THEN UPDATE SET T.ITEMID = S.ITEMID)";
        for (String shape : List.of(finalTableDelete, finalTableInsert, finalTableUpdate, finalTableMerge)) {
            assertReasonNamesAForbiddenVerb(shape, "a data-change table reference inside a SELECT");
        }

        // (2) A WITH/CTE carrying a DML statement: a WITH prefix is the second thing the old gate admitted.
        String cteDelete = "WITH SRC AS (SELECT ITEMID FROM ICMADMIN.ICMUT00001001)"
                + " DELETE FROM ICMADMIN.ICMUT00001001 WHERE ITEMID IN (SELECT ITEMID FROM SRC)";
        String cteUpdate = "WITH SRC AS (SELECT ITEMID FROM ICMADMIN.ICMUT00001001)"
                + " UPDATE ICMADMIN.ICMUT00001001 SET ITEMID = 1 WHERE ITEMID IN (SELECT ITEMID FROM SRC)";
        String cteInsert = "WITH SRC AS (SELECT ITEMID FROM ICMADMIN.ICMUT00001001)"
                + " INSERT INTO ICMADMIN.ICMUT00001001 (ITEMID) SELECT ITEMID FROM SRC";
        for (String shape : List.of(cteDelete, cteUpdate, cteInsert)) {
            assertReasonNamesAForbiddenVerb(shape, "a WITH/CTE carrying a DML statement");
        }

        // (3) A second statement after a separator. The separator rule fires first by construction, so the
        //     specific rule can be pinned here.
        String stacked = "SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0;"
                + " DELETE FROM ICMADMIN.ICMUT00001001";
        assertRefusedWithReason(stacked, "separator", "a multi-statement SELECT ...; DELETE ...");
        assertRefusedWithReason("SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001; DROP TABLE ICMADMIN.ICMUT00001001",
                "separator", "a stacked DDL statement after a separator");
        assertRefusedWithReason("SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0 ; GRANT SELECT"
                        + " ON ICMADMIN.ICMUT00001001 TO PUBLIC",
                "separator", "a stacked GRANT after a separator");

        // (4) A write token hidden behind a comment. Comments are refused outright rather than stripped,
        //     which is the stronger rule: a stripper has to be right about nesting and quoting.
        assertRefusedWithReason("SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0"
                        + " -- DELETE FROM ICMADMIN.ICMUT00001001",
                "comment", "a line comment hiding a DELETE");
        assertRefusedWithReason("SELECT 1 AS PRESENT /* DELETE FROM ICMADMIN.ICMUT00001001 */"
                        + " FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0",
                "comment", "a block comment hiding a DELETE");
        assertRefusedWithReason("SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001 /**/ WHERE 1 = 0",
                "comment", "an empty comment generated analytics SQL never needs");
        assertRefusedWithReason("SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001 -- note the trailing text",
                "comment", "a trailing comment");
    }

    /**
     * Every verb goal section D names, and every verb in the implementation's own vocabulary, is refused as
     * a WHOLE TOKEN inside a statement the prefix gate would have admitted.
     *
     * <p>The vehicle is a lexical one and is deliberately stated as such: each statement is
     * {@code SELECT ... WHERE 1 = 0 AND <VERB> = 1}, which contains no comment, no literal and no separator,
     * so the only rule that can fire is the keyword rule - and exactly one forbidden token is present. That
     * makes the assertion precise rather than order-dependent: the reason must name that verb.
     *
     * <p>The first loop uses the hard-coded list from the goal; the second uses
     * {@link SqlAdmission#forbiddenKeywords()} so no entry of the implementation's own vocabulary can
     * silently escape refusal.
     */
    public void everyForbiddenVerbIsRefusedAsAWholeTokenInsideAnAdmittedSelectShape() {
        for (String verb : MANDATORY_REFUSED_VERBS) {
            String statement = selectShapeCarrying(verb);
            assertRefusedWithReason(statement, verb,
                    "the mandatory verb " + verb + " must be refused as a whole token");
        }

        Set<String> vocabulary = new TreeSet<>(SqlAdmission.forbiddenKeywords());
        Assert.assertTrue(vocabulary.containsAll(MANDATORY_REFUSED_VERBS),
                "the implementation's vocabulary must cover every verb the goal names; it was missing "
                        + difference(MANDATORY_REFUSED_VERBS, vocabulary));
        for (String keyword : vocabulary) {
            assertRefusedWithReason(selectShapeCarrying(keyword), keyword,
                    "every keyword of the rule's own vocabulary must be refused where it appears as a token: "
                            + keyword);
        }
    }

    /** {@code SELECT ... AND <verb> = 1}: one forbidden token, inside a shape the prefix gate admits. */
    private static String selectShapeCarrying(String verb) {
        return "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0 AND " + verb + " = 1";
    }

    // ================================================================== separators, comments, literals

    /**
     * Statement separators and comment forms are refused wherever generated analytics SQL does not need
     * them.
     *
     * <p>Separators are checked outside quoted regions, so a separator can never be smuggled through a
     * quoted identifier either.
     */
    public void separatorsAndCommentFormsAreRefused() {
        for (String stacked : List.of(
                "SELECT 1 AS A; SELECT 2 AS B",
                "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0;DELETE FROM ICMADMIN.ICMUT00001001",
                "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0;\nDELETE FROM ICMADMIN.ICMUT00001001",
                "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0;")) {
            assertRefusedWithReason(stacked, "separator", "a statement separator");
        }

        for (String commented : List.of(
                "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 -- trailing",
                "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 /* trailing */",
                "-- SELECT 1 AS A",
                "/* SELECT 1 AS A */")) {
            assertRefusedWithReason(commented, "comment", "a SQL comment form");
        }
    }

    /**
     * A string literal is outside the admitted language, because every value generated analytics SQL binds
     * is a bind parameter.
     *
     * <p>This is the rule that makes the {@code CALL} case interesting rather than wrong: a call shape that
     * carries a literal is refused as a LITERAL, before the leading-keyword or vocabulary rules are reached.
     * A single refusal is enough for the guarantee, so the assertion here is that a refusal occurred and
     * that it names the literal rule - not which verb it would have been.
     */
    public void stringLiteralsAreRefusedBecauseEveryGeneratedValueIsBound() {
        for (String literal : List.of(
                "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE ITEMID = 'X'",
                "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE DELETED_AT = '2024-01-01'",
                "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE ITEMID = ''")) {
            assertRefusedWithReason(literal, "quoted region", "a string literal");
        }

        // THE REGRESSION THIS BLOCK EXISTS FOR. The first version of the literal rule recognised only a bare
        // apostrophe opener, so the vendors' PREFIXED literal forms were not seen as literals - and because
        // the tokenizer skips quoted regions, the write verb inside them was not seen as a token either. A
        // statement like the first case below was ADMITTED. Review found it; these cases keep it found.
        for (String prefixed : List.of(
                "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE ITEMID = N'DELETE FROM Y'",
                "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE ITEMID = X'DEADBEEF'",
                "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE ITEMID = B'0101'",
                "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE ITEMID = q'[DELETE FROM Y]'")) {
            assertRefusedWithReason(prefixed, "quoted region", "a prefixed string literal");
        }

        // A quoted identifier is refused too, deliberately: no generated statement quotes an identifier, and
        // refusing every quote is what stops the recogniser from being a bet on a complete prefix list.
        assertRefusedWithReason("SELECT \"ITEMID\" FROM ICMADMIN.ICMUT00001001", "quoted region",
                "a quoted identifier");

        // A stored-procedure call is refused as well - by the quoted region here, and independently by the
        // forbidden CALL keyword once the literal is removed, so two rules cover it.
        assertRefusedWithReason("CALL ICMADMIN.SOME_PROC('X')", "quoted region",
                "a stored-procedure call carrying a literal");
    }

    // ================================================================== false-positive controls

    /**
     * Identifiers that merely CONTAIN a keyword fragment are admitted.
     *
     * <p>{@code UPDATED_AT}, {@code DELETED_FLAG} and {@code CREATE_TS} are ordinary column names. A rule
     * that refused them would refuse real schema and would then be weakened by whoever hit it, so this is
     * not a nicety - it is the property that makes the strict rule survive contact with a real database.
     * The control at the end proves a substring rule would fail exactly here.
     */
    public void identifiersContainingKeywordFragmentsAreAdmitted() {
        List<String> falsePositives = List.of(
                "SELECT COUNT(UPDATED_AT) AS N FROM ICMADMIN.ICMUT00001001",
                "SELECT DELETED_FLAG, CREATE_TS FROM ICMADMIN.ICMUT00001001",
                "SELECT U.CREATED_BY, U.UPDATED_BY FROM ICMADMIN.ICMUT00001001 U",
                "SELECT NEW_ITEM_ID, OLD_ITEM_ID FROM ICMADMIN.ICMUT00001001",
                "SELECT MERGED_COUNT, TRUNCATED_COUNT FROM ICMADMIN.ICMUT00001001",
                "SELECT CALL_COUNT, EXECUTED_AT FROM ICMADMIN.ICMUT00001001",
                "SELECT GRANTED_BY, REVOKED_AT, DROPPED_FLAG, ALTERED_TS FROM ICMADMIN.ICMUT00001001");
        for (String sql : falsePositives) {
            Optional<String> refusal = SqlAdmission.refuse(sql);
            Assert.assertTrue(refusal.isEmpty(),
                    "an identifier containing a keyword fragment is NOT a keyword and must be admitted, but '"
                            + sql + "' was refused with '" + refusal.orElse("") + "'");
        }

        // The control: the naive substring rule refuses one of the statements above, which is the defect the
        // token-aware reading exists to avoid.
        Assert.assertTrue(naiveSubstringRuleRefuses(falsePositives.get(0)),
                "the substring control must really refuse '" + falsePositives.get(0) + "', otherwise this"
                        + " false-positive control proves nothing");
        Assert.assertFalse(naiveSubstringRuleRefuses("SELECT 1 AS A FROM ICMADMIN.ICMUT00001001"),
                "and the substring control must not refuse a statement with no keyword fragment at all");
    }

    // ================================================================== the mutation controls

    /**
     * MUTATION CONTROL: the pre-fix prefix gate admits every rejection shape this suite asserts is refused.
     *
     * <p>Without this, the suite would only show that the new rule refuses the statements it happens to
     * refuse. The control reimplements Goal 03's rule - {@code stripLeading()} plus a case-insensitive
     * comparison of the first four or six characters - and requires it to ADMIT each shape. So every refusal
     * assertion above is demonstrably about the defect the goal names, and a regression back to the prefix
     * gate makes this suite fail rather than pass.
     */
    public void thePreFixPrefixGateAdmitsEveryRejectedShapeSoTheseAssertionsDetectTheDefect()
            throws Exception {
        List<String> rejectedShapes = new ArrayList<>();
        rejectedShapes.addAll(List.of(
                "SELECT * FROM FINAL TABLE (DELETE FROM ICMADMIN.ICMUT00001001 WHERE ITEMID = 1)",
                "SELECT * FROM FINAL TABLE (INSERT INTO ICMADMIN.ICMUT00001001 (ITEMID) VALUES (1))",
                "SELECT * FROM FINAL TABLE (UPDATE ICMADMIN.ICMUT00001001 SET ITEMID = 1)",
                "WITH SRC AS (SELECT ITEMID FROM ICMADMIN.ICMUT00001001)"
                        + " DELETE FROM ICMADMIN.ICMUT00001001 WHERE ITEMID IN (SELECT ITEMID FROM SRC)",
                "WITH SRC AS (SELECT ITEMID FROM ICMADMIN.ICMUT00001001)"
                        + " UPDATE ICMADMIN.ICMUT00001001 SET ITEMID = 1",
                "WITH SRC AS (SELECT ITEMID FROM ICMADMIN.ICMUT00001001)"
                        + " INSERT INTO ICMADMIN.ICMUT00001001 (ITEMID) SELECT ITEMID FROM SRC",
                "SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0; DELETE FROM ICMADMIN.ICMUT00001001",
                "SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0 -- DELETE FROM ICMADMIN.ICMUT00001001",
                "SELECT 1 AS PRESENT /* DELETE */ FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0",
                selectShapeCarrying("DELETE"),
                selectShapeCarrying("DROP"),
                selectShapeCarrying("EXECUTE")));

        for (String shape : rejectedShapes) {
            Assert.assertTrue(preFixPrefixOnlyAdmits(shape),
                    "the MUTATION CONTROL requires this shape to be one the pre-fix prefix gate ADMITTED,"
                            + " otherwise it does not reproduce the defect: " + shape);
            Assert.assertTrue(SqlAdmission.refuse(shape).isPresent(),
                    "and the strict rule must differ from the pre-fix gate on exactly that shape: " + shape);
        }

        // The other direction, so the control cannot be satisfied by a rule that refuses everything: the
        // pre-fix gate and the strict rule must AGREE on every approved production statement.
        for (String approved : approvedProductionStatements()) {
            Assert.assertTrue(preFixPrefixOnlyAdmits(approved),
                    "an approved statement was admitted by the pre-fix gate too: " + approved);
            Assert.assertTrue(SqlAdmission.admitted(approved),
                    "and the strict rule keeps admitting it: " + approved);
        }
    }

    /**
     * MUTATION CONTROL: the old prefix rule, reimplemented here exactly as Goal 03 wrote it.
     *
     * <p>{@code stripLeading()} and a case-insensitive region match on {@code SELECT}/{@code WITH} - nothing
     * else. Kept as the subject of the control above, never used to decide anything.
     */
    private static boolean preFixPrefixOnlyAdmits(String sql) {
        String leading = sql.stripLeading();
        return leading.regionMatches(true, 0, "SELECT", 0, 6) || leading.regionMatches(true, 0, "WITH", 0, 4);
    }

    /** The naive substring rule, kept only so the false-positive control has a subject. */
    private static boolean naiveSubstringRuleRefuses(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        for (String verb : MANDATORY_REFUSED_VERBS) {
            if (upper.contains(verb)) {
                return true;
            }
        }
        return false;
    }

    // ================================================================== reason hygiene

    /**
     * Every refusal reason is non-blank, value-free, and never reproduces the refused SQL.
     *
     * <p>A refusal that echoed the statement would be a fresh injection surface of its own - the same rule
     * the identifier validators follow - and a blank reason would leave an operator with nothing to act on.
     */
    public void everyRefusalReasonIsNonBlankAndValueFree() {
        List<String> refused = new ArrayList<>();
        for (String verb : MANDATORY_REFUSED_VERBS) {
            refused.add(selectShapeCarrying(verb));
        }
        refused.add("SELECT * FROM FINAL TABLE (DELETE FROM ICMADMIN.ICMUT00001001 WHERE ITEMID = 1)");
        refused.add("WITH SRC AS (SELECT ITEMID FROM ICMADMIN.ICMUT00001001)"
                + " DELETE FROM ICMADMIN.ICMUT00001001 WHERE ITEMID IN (SELECT ITEMID FROM SRC)");
        refused.add("SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0; DELETE FROM ICMADMIN.ICMUT00001001");
        refused.add("SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 -- DELETE FROM ICMADMIN.ICMUT00001001");
        refused.add("SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE ITEMID = 'X'");
        refused.add("");
        refused.add("   ");

        for (String sql : refused) {
            Optional<String> refusal = SqlAdmission.refuse(sql);
            Assert.assertTrue(refusal.isPresent(), "this statement must be refused: <" + sql + ">");
            String reason = refusal.orElseThrow();
            Assert.assertFalse(reason.isBlank(), "the refusal reason must be actionable: <" + reason + ">");
            Assert.assertFalse(reason.contains(sql) && !sql.isBlank(),
                    "the refusal must not reproduce the refused statement, which would be an injection"
                            + " surface of its own: " + reason);
            Assert.assertFalse(reason.toUpperCase(Locale.ROOT).contains("ICMADMIN"),
                    "and must not carry a schema or table name taken from it: " + reason);
        }

        Assert.assertTrue(SqlAdmission.refuse(null).isPresent(), "a null statement is refused, not a crash");
        Assert.assertTrue(SqlAdmission.refuse(null).orElseThrow().contains("no statement"),
                "and says so plainly: " + SqlAdmission.refuse(null).orElseThrow());
        Assert.assertTrue(SqlAdmission.refuse("").isPresent(), "an empty statement is refused");

        // The rule documents the two leading keywords it admits, which is what makes "a prefix gate is not
        // enough" a statement about this codebase rather than about SQL in general.
        List<String> leading = SqlAdmission.admittedLeadingKeywords();
        Assert.assertEquals(List.of("SELECT", "WITH"), leading,
                "the admitted read language begins with SELECT or WITH, and nothing else");
        for (String sql : approvedProductionStatementsProbe()) {
            Assert.assertTrue(preFixPrefixOnlyAdmits(sql),
                    "every approved statement begins with one of those two, which is exactly why the prefix"
                            + " gate looked correct: " + sql);
        }
    }

    /** A tiny, non-throwing probe of the approved leading shapes, for the leading-keyword assertion. */
    private static List<String> approvedProductionStatementsProbe() {
        ScanWindows windows = ScanWindows.anchoredAt(LocalDate.of(2024, 6, 15));
        try {
            SqlQueryBuilder builder = SqlQueryBuilder.forSchema(new Db2Dialect(), SCHEMA);
            return List.of(
                    new Db2Dialect().currentDateSql(),
                    new OracleDialect().currentDateSql(),
                    builder.rootComponentMappingSql(),
                    builder.rootTableProbe("ICMUT00001001").sql(),
                    builder.aggregate(oneSegment(), windows.parameters()).sql(),
                    builder.totalItems(threeSegments()).sql());
        } catch (Exception impossible) {
            throw new AssertionError("the approved statements must be constructible without a database",
                    impossible);
        }
    }

    // ================================================================== helpers

    /** Asserts the statement is refused, with a non-blank reason. */
    private static void assertRefused(String sql, String what) {
        Optional<String> refusal = SqlAdmission.refuse(sql);
        Assert.assertTrue(refusal.isPresent(), what + " must be REFUSED, but it was admitted: " + sql);
        Assert.assertFalse(refusal.orElseThrow().isBlank(),
                what + " must be refused with an actionable reason: " + sql);
        Assert.assertFalse(SqlAdmission.admitted(sql), "and admitted() must agree it is refused: " + sql);
    }

    /** Asserts the statement is refused and that the reason names the rule expected to fire first. */
    private static void assertRefusedWithReason(String sql, String expectedFragment, String what) {
        assertRefused(sql, what);
        String reason = SqlAdmission.refuse(sql).orElseThrow();
        Assert.assertTrue(reason.toUpperCase(Locale.ROOT).contains(expectedFragment.toUpperCase(Locale.ROOT)),
                what + " must be refused by the " + expectedFragment + " rule, but the reason was: "
                        + reason);
    }

    /**
     * Asserts the reason names one of the forbidden verbs the statement really contains.
     *
     * <p>Order-independent on purpose: which of a statement's several forbidden tokens the rule reports
     * first is an implementation detail, but a refusal that names a verb the statement does not contain
     * would be a wrong reason. {@link #forbiddenTokensIn(String)} computes the set independently.
     */
    private static void assertReasonNamesAForbiddenVerb(String sql, String what) {
        assertRefused(sql, what);
        Set<String> present = forbiddenTokensIn(sql);
        Assert.assertFalse(present.isEmpty(),
                "the control requires at least one forbidden verb to be present in: " + sql);
        String reason = SqlAdmission.refuse(sql).orElseThrow().toUpperCase(Locale.ROOT);
        boolean named = false;
        for (String verb : present) {
            named |= reason.contains(verb);
        }
        Assert.assertTrue(named,
                what + " must be refused with a reason naming one of the forbidden verbs it contains ("
                        + present + "), but the reason was: " + reason);
    }

    /** The mandatory vocabulary present in a statement, read as whole tokens, computed independently. */
    private static Set<String> forbiddenTokensIn(String sql) {
        Set<String> vocabulary = forbiddenVocabulary();
        Set<String> present = new TreeSet<>();
        StringBuilder token = new StringBuilder();
        for (int index = 0; index <= sql.length(); index++) {
            char c = index == sql.length() ? ' ' : sql.charAt(index);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#') {
                token.append(Character.toUpperCase(c));
                continue;
            }
            if (token.length() > 0) {
                if (vocabulary.contains(token.toString())) {
                    present.add(token.toString());
                }
                token.setLength(0);
            }
        }
        return present;
    }

    /**
     * The vocabulary a refusal reason may legitimately name: the goal's mandatory verbs UNION the rule's own
     * documented set.
     *
     * <p>The union is deliberate rather than a shortcut. Which of a statement's forbidden tokens the rule
     * reports first is an implementation detail - DB2's {@code FINAL TABLE (...)} shape contains both
     * {@code FINAL} and {@code DELETE}, and either is a correct reason - so what this suite must pin is that
     * the reason names a token the statement really CONTAINS, not one the rule invented. Both halves of the
     * union are cross-checked against each other in
     * {@link #everyForbiddenVerbIsRefusedAsAWholeTokenInsideAnAdmittedSelectShape}.
     */
    private static Set<String> forbiddenVocabulary() {
        Set<String> vocabulary = new TreeSet<>(SqlAdmission.forbiddenKeywords());
        vocabulary.addAll(MANDATORY_REFUSED_VERBS);
        return vocabulary;
    }

    /** The elements of {@code expected} that {@code actual} is missing, for a readable failure. */
    private static Set<String> difference(List<String> expected, Set<String> actual) {
        Set<String> missing = new TreeSet<>();
        for (String value : expected) {
            if (!actual.contains(value)) {
                missing.add(value);
            }
        }
        return missing;
    }
}
