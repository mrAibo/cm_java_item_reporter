package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.DatabaseVendor;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.db.AggregateQuery;
import com.mraibo.cminsight.db.Db2Dialect;
import com.mraibo.cminsight.db.JdbcAccessException;
import com.mraibo.cminsight.db.JdbcDialect;
import com.mraibo.cminsight.db.JdbcSession;
import com.mraibo.cminsight.db.JdbcSessionFactory;
import com.mraibo.cminsight.db.OracleDialect;
import com.mraibo.cminsight.db.PhysicalSchema;
import com.mraibo.cminsight.db.PhysicalSchemaResolver;
import com.mraibo.cminsight.db.SqlIdentifiers;
import com.mraibo.cminsight.db.SqlQueryBuilder;
import com.mraibo.cminsight.db.SqlUnavailableException;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.statistics.ItemIdDateKey;
import com.mraibo.cminsight.statistics.ItemTypeStatistics;
import com.mraibo.cminsight.statistics.ScanWindows;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Goal 03 sections 2, 8, 9 and 10 (and section 15 "SQL/schema"): the ItemID date key, the calendar
 * windows, the rewritten dialects, identifier safety, the physical-schema resolver and the ItemID
 * aggregate.
 *
 * <h2>Three kinds of assertion, deliberately different in strength</h2>
 *
 * <ol>
 *   <li><strong>Exact values.</strong> The date-key encoding and the window boundaries are computed here
 *       and compared character by character, so a change in the documented positions fails rather than
 *       being absorbed by a helper.</li>
 *   <li><strong>Structural properties.</strong> The dialects must expose COMPLETE statements, the
 *       generated SQL must be SELECT-only, the resolver's mapping query must BIND its ItemTypeID, and
 *       every identifier must have passed a validator before it reached the text.</li>
 *   <li><strong>Semantics evaluated against a physical model.</strong> The aggregate's counting rules -
 *       deduplicate across versions, deduplicate across segments, count nothing from an empty ItemType -
 *       live in SQL text that no test can execute without a database. So this suite evaluates the
 *       GENERATED statement against a physical row model with its own small evaluator
 *       ({@link #evaluateAggregate}) that reads the tables the SQL actually names and applies the
 *       documented eight-bind window comparison. If the implementation names only the current segment, or
 *       uses {@code UNION ALL} where it must collapse duplicates, the number changes and the test fails.
 *       A mutation control ({@link #theAggregateEvaluatorDetectsCountingOnlyTheCurrentSegment}) proves the
 *       evaluator can tell the two apart, so a passing equality is evidence rather than a tautology.</li>
 * </ol>
 *
 * <p>No DB2 or Oracle JAR is involved: every statement is executed against {@link FakeJdbc}, which refuses
 * a query that names a generated root table it was not told exists - the same failure a real database
 * reports for a missing table, and what makes "a missing middle segment must fail the mapping" testable.
 */
public class JdbcSqlDialectTest {

    private static final String SCHEMA = "ICMADMIN";

    /** The eight window bounds one segment binds, in the documented order. */
    private static final int WINDOW_BOUNDS_PER_SEGMENT = 8;

    // ================================================================== ItemIdDateKey

    /** The four documented boundaries encode to their exact six characters. */
    public void theDateKeyEncodesTheDocumentedBoundariesExactly() {
        assertKey(LocalDate.of(2000, 1, 1), "A00A01");
        assertKey(LocalDate.of(2099, 12, 31), "A99L31");
        assertKey(LocalDate.of(2100, 1, 1), "B00A01");
        assertKey(LocalDate.of(2199, 12, 31), "B99L31");

        Assert.assertEquals(6, ItemIdDateKey.LENGTH,
                "the encoded key is the fixed-width six characters of SUBSTR(ItemID, 9, 6)");
        Assert.assertEquals(9, ItemIdDateKey.ITEM_ID_OFFSET,
                "and it starts at the documented 1-based position 9 of ItemID");
        Assert.assertTrue(ItemIdDateKey.representable(LocalDate.of(2000, 1, 1)),
                "2000 is inside the documented range");
        Assert.assertTrue(ItemIdDateKey.representable(LocalDate.of(2199, 12, 31)),
                "2199 is the last year the documented century letters cover");
    }

    /**
     * Outside 2000-2199 the encoder answers "no key" instead of inventing a century.
     *
     * <p>An empty answer is a real, load-bearing result: it is how the caller learns that a window boundary
     * cannot be expressed and must report the affected time metrics as UNAVAILABLE.
     */
    public void theDateKeyRefusesToInventACenturyOutsideTheDocumentedRange() {
        for (LocalDate outside : List.of(LocalDate.of(1999, 12, 31), LocalDate.of(1999, 1, 1),
                LocalDate.of(2200, 1, 1), LocalDate.of(2200, 12, 31), LocalDate.of(1970, 6, 1))) {
            Assert.assertTrue(ItemIdDateKey.encode(outside).isEmpty(),
                    "the documented encoding covers 2000-2199 only, so " + outside
                            + " must produce no key rather than a guessed century");
            Assert.assertFalse(ItemIdDateKey.representable(outside),
                    "and representable() must agree for " + outside);
        }
        Assert.assertTrue(ItemIdDateKey.encode(null).isEmpty(),
                "a null date has no key either, and must not throw");
    }

    /**
     * The encoded keys sort lexicographically in the same order as their dates.
     *
     * <p>This is the property the whole window design rests on: the SQL compares {@code SUBSTR(ItemID, 9,
     * 6)} against a boundary as text, which is only correct if the text order is the chronological order.
     * Nothing else in the codebase proves it, so it is asserted here over month, year and century
     * boundaries.
     */
    public void theEncodedKeysSortInTheSameOrderAsTheirDates() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2000, 1, 1),
                LocalDate.of(2000, 1, 9),
                LocalDate.of(2000, 1, 10),
                LocalDate.of(2000, 2, 1),
                LocalDate.of(2000, 12, 31),
                LocalDate.of(2001, 1, 1),
                LocalDate.of(2009, 12, 31),
                LocalDate.of(2010, 1, 1),
                LocalDate.of(2099, 12, 31),
                LocalDate.of(2100, 1, 1),
                LocalDate.of(2199, 12, 31));

        for (int outer = 0; outer < dates.size(); outer++) {
            for (int inner = 0; inner < dates.size(); inner++) {
                LocalDate left = dates.get(outer);
                LocalDate right = dates.get(inner);
                String leftKey = ItemIdDateKey.encode(left).orElseThrow();
                String rightKey = ItemIdDateKey.encode(right).orElseThrow();
                int dateOrder = Integer.signum(left.compareTo(right));
                int keyOrder = Integer.signum(leftKey.compareTo(rightKey));
                Assert.assertEquals(dateOrder, keyOrder,
                        "the encoded key must sort like the date it encodes: " + left + " -> " + leftKey
                                + " vs " + right + " -> " + rightKey);
            }
        }
    }

    /**
     * Mutation control: the ordering assertion above can FAIL, so it is not vacuous.
     *
     * <p>A century-less encoding - the obvious first attempt, {@code yy + month + dd} - sorts {@code 2100}
     * before {@code 2099}, because {@code "00..."} precedes {@code "99..."}. That is exactly the bug the
     * documented century letters exist to prevent, and it is reproduced here so the assertion above is
     * demonstrably able to tell the two apart.
     */
    public void theOrderingAssertionRejectsACenturyLessEncoding() {
        String naive2099 = naiveTwoDigitYearKey(LocalDate.of(2099, 12, 31));
        String naive2100 = naiveTwoDigitYearKey(LocalDate.of(2100, 1, 1));
        Assert.assertTrue(naive2100.compareTo(naive2099) < 0,
                "the century-less encoding must be shown to sort WRONGLY (2100 before 2099), which is what"
                        + " makes the ordering assertion meaningful; got " + naive2100 + " and " + naive2099);

        String real2099 = ItemIdDateKey.encode(LocalDate.of(2099, 12, 31)).orElseThrow();
        String real2100 = ItemIdDateKey.encode(LocalDate.of(2100, 1, 1)).orElseThrow();
        Assert.assertTrue(real2100.compareTo(real2099) > 0,
                "and the real encoding must sort them the other way round: " + real2100 + " > " + real2099);
    }

    /** The obvious wrong encoding, kept only as the control's subject. */
    private static String naiveTwoDigitYearKey(LocalDate date) {
        return String.format("%02d", date.getYear() % 100)
                + (char) ('A' + date.getMonthValue() - 1)
                + String.format("%02d", date.getDayOfMonth());
    }

    private static void assertKey(LocalDate date, String expected) {
        Optional<String> key = ItemIdDateKey.encode(date);
        Assert.assertTrue(key.isPresent(), date + " must be representable");
        Assert.assertEquals(expected, key.get(), "the documented encoding of " + date);
        Assert.assertEquals(ItemIdDateKey.LENGTH, key.get().length(),
                "every encoded key is exactly " + ItemIdDateKey.LENGTH + " characters");
    }

    // ================================================================== ScanWindows

    /**
     * The four windows are anchored on ONE date and their boundaries are the documented offsets, checked
     * across a month boundary, a year boundary and a real leap day.
     *
     * <p>{@code parameters()} is asserted in its exact order, because that order is the dialect's binding
     * contract: today lo/hi, 7-day lo/hi, 30-day lo/hi, year lo/hi. A test here is what stops the window
     * producer and the SQL binder from drifting apart.
     */
    public void theFourWindowsAreAnchoredOnceAndCoverMonthYearAndLeapBoundaries() {
        // Month boundary: 2024-03-01. "today" ends on 2024-03-02, the 30-day window starts in February, and
        // 2024 is a leap year, so the 30-day start is 2024-02-01 and February really does have 29 days.
        assertWindows(LocalDate.of(2024, 3, 1),
                "A24C01", // today start
                "A24C02", // today end (tomorrow)
                "A24B24", // 7-day start = anchor - 6 days = 2024-02-24
                "A24B01", // 30-day start = anchor - 29 days = 2024-02-01
                "A24A01", // year start = 2024-01-01
                "A25A01"); // year end = 2025-01-01

        // Leap day itself: the anchor is 2024-02-29, so "tomorrow" is 2024-03-01.
        assertWindows(LocalDate.of(2024, 2, 29),
                "A24B29", "A24C01", "A24B23", "A24A31", "A24A01", "A25A01");

        // Year boundary: 2024-01-01, which is also a leap-year start. The 30-day window walks back into
        // December 2023, i.e. month letter L, not C.
        assertWindows(LocalDate.of(2024, 1, 1),
                "A24A01", "A24A02", "A23L26", "A23L03", "A24A01", "A25A01");

        // A leap-year trap: 2023-03-01 must NOT get 2023-02-29, which does not exist. 30-day start is
        // 2023-01-31 and today ends 2023-03-02.
        assertWindows(LocalDate.of(2023, 3, 1),
                "A23C01", "A23C02", "A23B23", "A23A31", "A23A01", "A24A01");
    }

    private static void assertWindows(LocalDate anchor,
                                      String todayStart,
                                      String todayEnd,
                                      String last7Start,
                                      String last30Start,
                                      String yearStart,
                                      String yearEnd) {
        ScanWindows windows = ScanWindows.anchoredAt(anchor);
        Assert.assertEquals(anchor, windows.anchor(), "the anchor is the database date it was built from");
        Assert.assertTrue(windows.fullyRepresentable(),
                anchor + " has every boundary inside the documented range");
        Assert.assertEquals(todayStart, windows.todayStart().orElseThrow(), "today start for " + anchor);
        Assert.assertEquals(todayEnd, windows.todayEnd().orElseThrow(), "today end for " + anchor);
        Assert.assertEquals(last7Start, windows.last7Start().orElseThrow(), "7-day start for " + anchor);
        Assert.assertEquals(last30Start, windows.last30Start().orElseThrow(), "30-day start for " + anchor);
        Assert.assertEquals(yearStart, windows.yearStart().orElseThrow(), "year start for " + anchor);
        Assert.assertEquals(yearEnd, windows.yearEnd().orElseThrow(), "year end for " + anchor);

        Assert.assertEquals(List.of(todayStart, todayEnd, last7Start, todayEnd, last30Start, todayEnd,
                        yearStart, yearEnd), windows.parameters(),
                "the eight bound values must be exactly today lo/hi, 7-day lo/hi, 30-day lo/hi, year lo/hi:"
                        + " that order is the dialect's binding contract");
        Assert.assertEquals(Optional.empty(), windows.unavailableReason(),
                "a fully representable anchor has no unavailability reason");
    }

    /**
     * An anchor whose boundaries leave 2000-2199 makes the time metrics UNAVAILABLE - and the total stays
     * available.
     *
     * <p>Note the subtle case: an anchor of 2199-12-31 is itself representable, but "tomorrow" is not, so
     * the windows are incomplete even though the anchor date encodes fine. That is the boundary a naive
     * check ("does the anchor encode?") would miss.
     */
    public void anUnrepresentableBoundaryMakesTheTimeMetricsUnavailableButKeepsTheTotal() throws Exception {
        ScanWindows outside = ScanWindows.anchoredAt(LocalDate.of(1999, 12, 31));
        Assert.assertFalse(outside.fullyRepresentable(),
                "an anchor before 2000 cannot be encoded, so the windows cannot be expressed");
        Assert.assertTrue(outside.unavailableReason().isPresent(),
                "and the reason must be stated rather than silently substituted");
        String reason = outside.unavailableReason().orElseThrow();
        Assert.assertFalse(reason.isBlank(), "the reason must be actionable: " + reason);
        Assert.assertFalse(reason.contains("password") || reason.contains("jdbc:db2"),
                "and must never carry a credential or a URL: " + reason);
        IllegalStateException parameterFailure = Assert.assertThrows(IllegalStateException.class,
                outside::parameters,
                "asking for the boundary values of unrepresentable windows is a caller error and must fail"
                        + " rather than silently substitute another century");
        Assert.assertFalse(parameterFailure.getMessage().isBlank(),
                "and the failure must explain why: " + parameterFailure.getMessage());

        // The year-end boundary of 2199 walks out of the range: the anchor itself is fine.
        ScanWindows yearEndOutside = ScanWindows.anchoredAt(LocalDate.of(2199, 12, 31));
        Assert.assertTrue(yearEndOutside.todayStart().isPresent(),
                "2199-12-31 itself encodes");
        Assert.assertTrue(yearEndOutside.todayEnd().isEmpty(),
                "but its 'tomorrow' is 2200-01-01, which the documented encoding does not cover, so the"
                        + " windows are incomplete even though the anchor is representable");
        Assert.assertFalse(yearEndOutside.fullyRepresentable(),
                "so the time metrics must be reported UNAVAILABLE rather than shifted into another century");

        // The total does not use the date key at all, so it stays available.
        PhysicalSchema schema = new PhysicalSchema(4711, "TestType", 1, 2,
                List.of("ICMUT00001001", "ICMUT00001002"));
        SqlQueryBuilder builder = SqlQueryBuilder.forSchema(new Db2Dialect(), SCHEMA);
        AggregateQuery total = builder.totalItems(schema);
        Assert.assertEquals(0, total.parameterCount(),
                "the total-only query binds nothing: it needs no window boundary");
        Assert.assertTrue(isSelectOnly(total.sql()),
                "and it is SELECT-only like every other generated statement: " + normalized(total.sql()));
    }

    // ================================================================== AggregateQuery

    /** A statement whose marker count and bound-value count disagree is refused at construction. */
    public void aParameterMarkerMismatchIsRefused() {
        IllegalArgumentException tooFew = Assert.assertThrows(IllegalArgumentException.class,
                () -> new AggregateQuery("SELECT ? FROM T WHERE X = ?", List.of(), "mismatch"),
                "a statement with two markers and no bound values must be refused");
        Assert.assertTrue(tooFew.getMessage().contains("2") && tooFew.getMessage().contains("0"),
                "the refusal must name both counts so the mismatch is obvious: " + tooFew.getMessage());

        Assert.assertThrows(IllegalArgumentException.class,
                () -> new AggregateQuery("SELECT 1 FROM T", List.of("extra"), "mismatch"),
                "a bound value with no marker must be refused too");

        AggregateQuery matching = new AggregateQuery("SELECT ? FROM T", List.of("one"), "matching");
        Assert.assertEquals(1, matching.parameterCount(), "a matching pair is accepted and counted");
    }

    // ================================================================== PhysicalSchema

    /**
     * A mapping that is NOT every root segment 1..N cannot be expressed at all.
     *
     * <p>This is the cheapest form of the "never count only the current segment" rule: the type's own
     * constructor refuses a short list, so the defect is not merely tested for - it is unrepresentable.
     */
    public void aMappingThatIsNotEverySegmentCannotBeExpressed() {
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new PhysicalSchema(4711, "T", 1, 3, List.of()),
                "an empty root-table list must be refused: an ItemType with no table is a failed mapping");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new PhysicalSchema(4711, "T", 1, 3,
                        List.of("ICMUT00001001", "ICMUT00001002")),
                "SegmentID 3 with only two tables must be refused: 'count only some segments' must not be"
                        + " expressible");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new PhysicalSchema(4711, "T", 1, 0, List.of("ICMUT00001001")),
                "SegmentID 0 is outside IBM's documented 1..36 range");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new PhysicalSchema(4711, "T", 1, 37, List.of("ICMUT00001001")),
                "SegmentID 37 is outside IBM's documented 1..36 range");

        PhysicalSchema one = new PhysicalSchema(4711, "T", 1, 1, List.of("ICMUT00001001"));
        Assert.assertEquals(1, one.segmentCount(), "a one-segment ItemType reports one segment");
        Assert.assertFalse(one.segmented(), "and is not segmented");

        PhysicalSchema three = new PhysicalSchema(4711, "T", 1, 3,
                List.of("ICMUT00001001", "ICMUT00001002", "ICMUT00001003"));
        Assert.assertEquals(3, three.segmentCount(), "a three-segment ItemType reports three");
        Assert.assertTrue(three.segmented(), "and is segmented");
        Assert.assertEquals(List.of("ICMUT00001001", "ICMUT00001002", "ICMUT00001003"), three.rootTables(),
                "the tables are carried in segment order, unqualified");
    }

    // ================================================================== dialects

    /**
     * DB2's current-date query is a COMPLETE one-row statement, and executing it through a real session
     * returns the database's date.
     *
     * <p>The execution half is what distinguishes a dialect from a string table: the query is run against
     * the fake driver through {@code JdbcSession.queryCurrentDate}, so a dialect whose text is not a valid
     * statement shape would not be read at all.
     */
    public void db2CurrentDateQueryIsACompleteStatementAndReads() throws Exception {
        Db2Dialect dialect = new Db2Dialect();
        Assert.assertEquals("DB2", dialect.id(), "the dialect id matches the configuration vendor name");
        Assert.assertEquals("jdbc:db2:", dialect.urlPrefix(), "DB2's only accepted URL prefix");
        Assert.assertEquals("com.ibm.db2.jcc.DB2Driver", dialect.driverClassName(),
                "the vendor driver class name, for local readiness only");
        Assert.assertTrue(dialect.supports("jdbc:db2://host:50000/DB"), "a DB2 URL is supported");
        Assert.assertFalse(dialect.supports("jdbc:oracle:thin:@host:1521/XE"),
                "and an Oracle URL is not: a vendor mismatch must never be attempted");

        String dateSql = dialect.currentDateSql();
        Assert.assertEquals("SELECT CURRENT DATE FROM SYSIBM.SYSDUMMY1", dateSql,
                "DB2's current-date query is a complete SELECT over IBM's documented one-row dummy table;"
                        + " it must start with SELECT, because the session's read-only guard accepts only"
                        + " SELECT and WITH - a VALUES form is refused there and the scan could never read"
                        + " its calendar anchor (that defect was found by this very test)");
        Assert.assertTrue(dateSql.toUpperCase(java.util.Locale.ROOT).contains("SYSIBM.SYSDUMMY1"),
                "DB2 needs a one-row source for a select list with no table: " + dateSql);
        Assert.assertTrue(isSelectOnly(dateSql), "it is a read-only statement: " + dateSql);
        Assert.assertFalse(dateSql.endsWith("1 ROW ONLY"),
                "it carries no row-limit fragment a caller would have to place correctly");

        try (FakeJdbc fake = db2Driver()) {
            fake.answersSqlContaining("VALUES CURRENT DATE",
                    FakeResultTable.oneRow(List.of("D"), LocalDate.of(2024, 3, 1)));
            fake.answersSqlContaining("FROM SYSIBM.SYSDUMMY1",
                    FakeResultTable.oneRow(List.of("D"), LocalDate.of(2024, 3, 1)));
            JdbcSession session = session(fake);
            try {
                Assert.assertEquals(LocalDate.of(2024, 3, 1), session.queryCurrentDate(dialect),
                        "the dialect's query must actually be readable through the session");
            } finally {
                session.close();
            }
        }
    }

    /**
     * Oracle's current-date query is a COMPLETE statement with {@code DUAL}, and it reads through a real
     * session.
     *
     * <p>{@code DUAL} is the whole reason the two dialects cannot share one string, and it is why the
     * replaced design's "same method, two different fragments" was wrong.
     */
    public void oracleCurrentDateQueryIsACompleteStatementWithDualAndReads() throws Exception {
        OracleDialect dialect = new OracleDialect();
        Assert.assertEquals("ORACLE", dialect.id(), "the dialect id matches the configuration vendor name");
        Assert.assertEquals("jdbc:oracle:", dialect.urlPrefix(), "Oracle's only accepted URL prefix");
        Assert.assertEquals("oracle.jdbc.OracleDriver", dialect.driverClassName(),
                "the preferred Oracle driver class name");
        Assert.assertTrue(dialect.supports("jdbc:oracle:thin:@host:1521/XE"), "an Oracle URL is supported");
        Assert.assertFalse(dialect.supports("jdbc:db2://host:50000/DB"),
                "and a DB2 URL is not");

        String dateSql = dialect.currentDateSql();
        Assert.assertTrue(dateSql.toUpperCase(java.util.Locale.ROOT).contains("FROM DUAL"),
                "Oracle requires FROM DUAL for a select list with no table: " + dateSql);
        Assert.assertTrue(dateSql.toUpperCase(java.util.Locale.ROOT).contains("SYSDATE"),
                "and the value comes from the database's own clock, not the JVM's: " + dateSql);
        Assert.assertTrue(isSelectOnly(dateSql), "it is a read-only statement: " + dateSql);
        Assert.assertFalse(dateSql.contains("ROWNUM"),
                "the row-limit shape belongs inside the zero-row probe, never here");

        try (FakeJdbc fake = oracleDriver()) {
            fake.answersSqlContaining("FROM DUAL",
                    FakeResultTable.oneRow(List.of("D"), LocalDate.of(2025, 1, 1)));
            JdbcSession session = session(fake);
            try {
                Assert.assertEquals(LocalDate.of(2025, 1, 1), session.queryCurrentDate(dialect),
                        "the Oracle statement must be readable through the session too");
            } finally {
                session.close();
            }
        }
    }

    /**
     * The context-sensitive {@code oneRowSuffix} design is GONE, structurally.
     *
     * <p>Three independent assertions: the old three-method interface no longer exists as a type; no type in
     * the database package declares a method whose name ends in {@code Suffix}; and every statement shape a
     * dialect produces is complete, so no caller can assemble an invalid combination out of valid-looking
     * pieces.
     */
    public void theContextSensitiveOneRowSuffixDesignIsGone() {
        ClassNotFoundException absent = Assert.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.mraibo.cminsight.db.DatabaseDialect"),
                "the replaced bootstrap interface must be deleted, not merely deprecated");
        Assert.assertNotNull(absent, "the absence is the expected failure");

        for (Class<?> type : List.of(Db2Dialect.class, OracleDialect.class, JdbcDialect.class)) {
            for (java.lang.reflect.Method method : type.getMethods()) {
                Assert.assertFalse(method.getName().toLowerCase(java.util.Locale.ROOT).endsWith("suffix"),
                        "no dialect may expose a context-dependent fragment again, but " + type.getSimpleName()
                                + " declares " + method.getName());
            }
        }

        // And every zero-row shape is a whole statement, not a clause.
        for (JdbcDialect dialect : List.of(new Db2Dialect(), new OracleDialect())) {
            String probe = dialect.zeroRowProbeSql(SCHEMA, "ICMUT00001001");
            Assert.assertTrue(probe.trim().toUpperCase(java.util.Locale.ROOT).startsWith("SELECT"),
                    dialect.id() + "'s zero-row probe must be a complete SELECT: " + probe);
            Assert.assertTrue(probe.contains("1 = 0") || probe.contains("1=0"),
                    "and it must be the statement that genuinely returns no rows: " + probe);
            Assert.assertTrue(probe.contains(SCHEMA + ".ICMUT00001001"),
                    "qualified with the validated schema and generated table: " + probe);
        }
    }

    /** Each dialect's zero-row probe uses its own vendor's row-limit shape, inside the statement. */
    public void eachDialectPlacesItsRowLimitInsideItsOwnStatement() {
        String db2 = new Db2Dialect().zeroRowProbeSql(SCHEMA, "ICMUT00001001");
        Assert.assertTrue(db2.toUpperCase(java.util.Locale.ROOT).contains("FETCH FIRST 1 ROW ONLY"),
                "DB2's row limit is a trailing clause of its own complete statement: " + db2);
        Assert.assertTrue(db2.toUpperCase(java.util.Locale.ROOT).contains("WHERE 1 = 0"),
                "and the statement carries its own WHERE clause, so the limit is valid: " + db2);

        String oracle = new OracleDialect().zeroRowProbeSql(SCHEMA, "ICMUT00001001");
        Assert.assertTrue(oracle.toUpperCase(java.util.Locale.ROOT).contains("ROWNUM <= 1"),
                "Oracle's row limit lives inside the statement's own WHERE clause: " + oracle);
        Assert.assertTrue(oracle.toUpperCase(java.util.Locale.ROOT).contains("WHERE 1 = 0"),
                "so it is valid without any caller-supplied clause: " + oracle);

        // The replaced design exposed exactly these two shapes as one method returning a fragment that was
        // valid only in a caller-supplied context. A trailing-clause dialect and an in-WHERE dialect cannot
        // both be served by one suffix, which is why this test exists rather than a text comparison.
        Assert.assertFalse(oracle.equals(db2), "the two vendors genuinely differ here");
    }

    // ================================================================== identifier safety

    /** A schema that cannot be proven to be an unquoted identifier is refused, never quoted into safety. */
    public void unsafeSchemaIdentifiersAreRefusedRatherThanRepaired() throws Exception {
        Assert.assertEquals("ICMADMIN", SqlIdentifiers.requireSafeSchema("ICMADMIN", "repository.jdbc.schema"),
                "a plain upper-case identifier is accepted");
        Assert.assertEquals("icmadmin", SqlIdentifiers.requireSafeSchema("icmadmin", "repository.jdbc.schema"),
                "and a lower-case one too: both vendors fold it, which is the only semantics claimed");

        for (String unsafe : List.of("ICM ADMIN", "\"ICMADMIN\"", "1ICMADMIN", "ICMADMIN.OTHER", "ICM-ADMIN",
                "ICMADMIN;", "ICM'ADMIN", "ICMADMIN\u00e9")) {
            SqlUnavailableException refusal = Assert.assertThrows(SqlUnavailableException.class,
                    () -> SqlIdentifiers.requireSafeSchema(unsafe, "repository.jdbc.schema"),
                    "the schema '" + unsafe + "' is not a proven unquoted identifier and must be refused");
            Assert.assertFalse(refusal.getMessage().contains(unsafe) && unsafe.contains("DROP"),
                    "the refusal must not become an injection vector by echoing the value into SQL");
        }
        Assert.assertThrows(SqlUnavailableException.class,
                () -> SqlIdentifiers.requireSafeSchema("   ", "repository.jdbc.schema"),
                "a blank schema is unavailable, never defaulted to a guessed ICMADMIN");

        String longest = "A".repeat(SqlIdentifiers.MAX_IDENTIFIER_LENGTH);
        Assert.assertEquals(longest, SqlIdentifiers.requireSafeSchema(longest, "repository.jdbc.schema"),
                "the documented maximum length is accepted");
        Assert.assertThrows(SqlUnavailableException.class,
                () -> SqlIdentifiers.requireSafeSchema("A".repeat(
                        SqlIdentifiers.MAX_IDENTIFIER_LENGTH + 1), "repository.jdbc.schema"),
                "and one character more is refused");
        Assert.assertFalse(SqlIdentifiers.isSafeUnquotedIdentifier(null),
                "a null schema is not safe by accident");
    }

    /** The configured schema wins; otherwise the session's answer is validated by the SAME rule. */
    public void theResolvedSchemaIsConfiguredFirstThenValidatedFromTheSession() throws Exception {
        Assert.assertEquals("CONFIGURED",
                SqlIdentifiers.resolveSchema("CONFIGURED", "SESSIONREPORTED"),
                "a configured schema wins over the driver's answer");
        Assert.assertEquals("SESSIONREPORTED", SqlIdentifiers.resolveSchema(null, "SESSIONREPORTED"),
                "with no configuration the driver's answer is used");
        Assert.assertEquals("SESSIONREPORTED", SqlIdentifiers.resolveSchema("  ", "SESSIONREPORTED"),
                "a blank configuration is treated as absent");
        Assert.assertThrows(SqlUnavailableException.class, () -> SqlIdentifiers.resolveSchema(null, null),
                "an absent configuration AND an absent driver answer is UNAVAILABLE: the schema is never"
                        + " guessed as ICMADMIN");
        Assert.assertThrows(SqlUnavailableException.class, () -> SqlIdentifiers.resolveSchema(null, "   "),
                "a blank driver answer is the same as none");
        Assert.assertThrows(SqlUnavailableException.class, () -> SqlIdentifiers.resolveSchema(null, "a b"),
                "and an unusable driver answer is refused by the same rule as a configured one");
        Assert.assertThrows(SqlUnavailableException.class,
                () -> SqlIdentifiers.resolveSchema("ICM ADMIN", "SESSIONREPORTED"),
                "an unsafe configured schema is refused rather than silently falling back to the session");
    }

    /** A generated root table name must match IBM's documented shape before it reaches a statement. */
    public void generatedTableNamesAreValidatedBeforeEmission() {
        Assert.assertTrue(SqlIdentifiers.isGeneratedRootTableName("ICMUT00001001"),
                "the documented ICMUT + 5 digits + 3 digits shape is accepted");
        Assert.assertTrue(SqlIdentifiers.isGeneratedRootTableName("ICMUT99999036"),
                "including the maximum component type and segment ids");

        for (String unsafe : List.of("ICMUT1", "ICMUT0000100", "ICMUT000010011", "icmut00001001",
                "ICMUT00007001; DROP TABLE X", "ICMUT00007001 UNION SELECT 1", "ICMSTCOMPDEFS", "ICMUTABCDE001",
                " ICMUT00001001", "ICMUT00001001 ")) {
            Assert.assertFalse(SqlIdentifiers.isGeneratedRootTableName(unsafe),
                    "'" + unsafe + "' is not a generated root table name and must never be interpolated");
        }
        Assert.assertThrows(SqlUnavailableException.class,
                () -> SqlIdentifiers.requireGeneratedRootTableName("ICMUT00007001; DROP TABLE X"),
                "a hostile name must be refused before emission, not escaped into safety");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> SqlIdentifiers.generatedRootTableName(0, 1),
                "ComponentTypeID 0 cannot produce a five-digit name");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> SqlIdentifiers.generatedRootTableName(1, 0),
                "SegmentID 0 is outside the documented range");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> SqlIdentifiers.generatedRootTableName(1, 37),
                "and SegmentID 37 is outside it too");

        Assert.assertEquals("ICMUT00001001", SqlIdentifiers.generatedRootTableName(1, 1),
                "the five-digit component type and three-digit segment are both zero padded");
        Assert.assertEquals("ICMUT12345036", SqlIdentifiers.generatedRootTableName(12345, 36),
                "and the padding holds across the whole range");
    }

    // ================================================================== the resolver query

    /**
     * The root resolver query keeps the proven CM_retention shape and BINDS its ItemTypeID.
     *
     * <p>The binding is the assertion that matters: an interpolated id would be the whole injection surface
     * of this layer, and "the SQL contains a marker and the value is in the parameter list, not the text" is
     * checkable exactly.
     */
    public void theRootResolverQueryBindsTheItemTypeIdAndKeepsTheProvenShape() throws Exception {
        SqlQueryBuilder builder = SqlQueryBuilder.forSchema(new Db2Dialect(), SCHEMA);
        int itemTypeId = 4711;
        AggregateQuery mapping = builder.rootComponentMapping(itemTypeId);

        String normalizedSql = normalized(mapping.sql());
        Assert.assertEquals("SELECT C.COMPONENTTYPEID, I.SEGMENTID FROM ICMADMIN.ICMSTCOMPDEFS C"
                        + " JOIN ICMADMIN.ICMSTITEMTYPEDEFS I ON I.ITEMTYPEID = C.ITEMTYPEID"
                        + " WHERE C.ITEMTYPEID = ? AND C.PARENTCOMPTYPEID = 0",
                normalizedSql.replaceAll("\\s+", " ").trim(),
                "the mapping query must keep the shape proven from CM_retention");
        Assert.assertTrue(normalizedSql.contains("PARENTCOMPTYPEID = 0"),
                "the root component row is selected by PARENTCOMPTYPEID = 0: " + normalizedSql);
        Assert.assertFalse(mapping.sql().contains(String.valueOf(itemTypeId)),
                "the ItemTypeID must NOT appear in the SQL text");
        Assert.assertEquals(List.of(itemTypeId), mapping.parameters(),
                "it must appear as the single bound value instead");
        Assert.assertEquals(1, mapping.parameterCount(), "one marker, one value");
        Assert.assertTrue(isSelectOnly(mapping.sql()), "and the statement is SELECT-only");

        // The metadata tables are addressed from a closed list, so no caller can smuggle a name through.
        Assert.assertTrue(SqlQueryBuilder.isMetadataTable("ICMSTCOMPDEFS"),
                "ICMSTCOMPDEFS is one of the fixed metadata tables");
        Assert.assertTrue(SqlQueryBuilder.isMetadataTable("ICMSTITEMTYPEDEFS"),
                "ICMSTITEMTYPEDEFS is the other");
        Assert.assertFalse(SqlQueryBuilder.isMetadataTable("ICMUT00001001"),
                "a generated root table is not a metadata table");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> builder.metadataTable("ICMSTCOMPDEFS; DROP TABLE X"),
                "and an arbitrary name is refused by the closed-list gate");
    }

    // ================================================================== the resolver

    /**
     * One segment resolves to exactly one table; three segments resolve to all three, in order.
     */
    public void theResolverMapsEverySegmentFromOneToNInOrder() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            fake.tables("ICMUT00001001", "ICMUT00001002", "ICMUT00001003");
            fake.answersSqlContaining("FROM ICMADMIN.ICMSTCOMPDEFS",
                    FakeResultTable.oneRow(List.of("COMPONENTTYPEID", "SEGMENTID"), 1, 3));
            fake.answersSqlContaining("WHERE 1 = 0", FakeResultTable.empty("PRESENT"));

            JdbcSession session = session(fake);
            try {
                PhysicalSchemaResolver resolver = new PhysicalSchemaResolver(new Db2Dialect(), SCHEMA);
                PhysicalSchema schema = resolver.resolve(session, 4711, "TestType");
                Assert.assertEquals(List.of("ICMUT00001001", "ICMUT00001002", "ICMUT00001003"),
                        schema.rootTables(),
                        "every segment 001..N must be present, in segment order - counting only the current"
                                + " table is the defect this exists to prevent");
                Assert.assertEquals(3, schema.segmentCount(), "the segment count is the current SegmentID");
                Assert.assertTrue(schema.segmented(), "and the mapping knows it is segmented");

                // A cache hit must not resolve again, and the cache is per instance.
                Assert.assertEquals(List.of("ICMUT00001001", "ICMUT00001002", "ICMUT00001003"),
                        resolver.resolve(session, 4711, "TestType").rootTables(),
                        "a second resolution of the same ItemType returns the same mapping");
                Assert.assertEquals(1, resolver.cachedMappingCount(),
                        "and it was cached rather than re-read");
                Assert.assertEquals(0, new PhysicalSchemaResolver(new Db2Dialect(), SCHEMA).cachedMappingCount(),
                        "while a NEW resolver shares nothing: there is no cross-repository static cache");
            } finally {
                session.close();
            }
        }

        // One segment.
        try (FakeJdbc fake = db2Driver()) {
            fake.tables("ICMUT00001001");
            fake.answersSqlContaining("FROM ICMADMIN.ICMSTCOMPDEFS",
                    FakeResultTable.oneRow(List.of("COMPONENTTYPEID", "SEGMENTID"), 1, 1));
            fake.answersSqlContaining("WHERE 1 = 0", FakeResultTable.empty("PRESENT"));
            JdbcSession session = session(fake);
            try {
                PhysicalSchema schema = new PhysicalSchemaResolver(new Db2Dialect(), SCHEMA)
                        .resolve(session, 4711, "TestType");
                Assert.assertEquals(List.of("ICMUT00001001"), schema.rootTables(),
                        "a single-segment ItemType maps to its one table");
                Assert.assertFalse(schema.segmented(), "and is not segmented");
            } finally {
                session.close();
            }
        }
    }

    /**
     * A missing MIDDLE segment fails the whole mapping with an actionable reason - it is never skipped.
     *
     * <p>The physical layout is {@code 001} and {@code 003} present with {@code 002} absent, and the current
     * SegmentID is 3. Counting the two tables that could be read would silently undercount every item whose
     * root lives in segment 2, so the mapping must fail instead.
     */
    public void aMissingMiddleSegmentFailsTheMappingInsteadOfBeingSkipped() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            fake.tables("ICMUT00001001", "ICMUT00001003");
            fake.answersSqlContaining("FROM ICMADMIN.ICMSTCOMPDEFS",
                    FakeResultTable.oneRow(List.of("COMPONENTTYPEID", "SEGMENTID"), 1, 3));
            fake.answersSqlContaining("WHERE 1 = 0", FakeResultTable.empty("PRESENT"));

            JdbcSession session = session(fake);
            try {
                PhysicalSchemaResolver resolver = new PhysicalSchemaResolver(new Db2Dialect(), SCHEMA);
                SqlUnavailableException refusal = Assert.assertThrows(SqlUnavailableException.class,
                        () -> resolver.resolve(session, 4711, "TestType"),
                        "a missing middle segment must fail the mapping, never produce a short table list");
                Assert.assertTrue(refusal.getMessage().contains("segment 2 of 3"),
                        "the refusal must name WHICH expected segment could not be verified: "
                                + refusal.getMessage());
                Assert.assertTrue(refusal.getMessage().contains("whole mapping fails"),
                        "and must say that the mapping fails rather than counting a subset: "
                                + refusal.getMessage());
                Assert.assertFalse(refusal.getMessage().contains(FakeJdbc.RAW_FAILURE_MARKER),
                        "while never reproducing the driver's raw text: " + refusal.getMessage());
                Assert.assertFalse(refusal.getMessage().contains("ICMUT"),
                        "and never leaking a table name into a message: " + refusal.getMessage());
                Assert.assertEquals(0, resolver.cachedMappingCount(),
                        "a failed mapping must not be cached, or the ItemType could never recover");
            } finally {
                session.close();
            }
        }
    }

    /** No root row and more than one root row both fail the mapping, rather than picking a row. */
    public void anAbsentOrAmbiguousRootRowFailsTheMapping() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            fake.tables("ICMUT00001001");
            fake.answersSqlContaining("FROM ICMADMIN.ICMSTCOMPDEFS", FakeResultTable.empty(
                    "COMPONENTTYPEID", "SEGMENTID"));
            JdbcSession session = session(fake);
            try {
                SqlUnavailableException none = Assert.assertThrows(SqlUnavailableException.class,
                        () -> new PhysicalSchemaResolver(new Db2Dialect(), SCHEMA)
                                .resolve(session, 4711, "TestType"),
                        "no root component row means the mapping cannot be determined");
                Assert.assertTrue(none.getMessage().contains("no root component row"),
                        "and the reason must say so: " + none.getMessage());
            } finally {
                session.close();
            }
        }

        try (FakeJdbc fake = db2Driver()) {
            fake.tables("ICMUT00001001", "ICMUT00002001");
            fake.answersSqlContaining("FROM ICMADMIN.ICMSTCOMPDEFS", FakeResultTable.of(
                    List.of("COMPONENTTYPEID", "SEGMENTID"),
                    List.of(new Object[] {1, 1}, new Object[] {2, 1})));
            JdbcSession session = session(fake);
            try {
                SqlUnavailableException many = Assert.assertThrows(SqlUnavailableException.class,
                        () -> new PhysicalSchemaResolver(new Db2Dialect(), SCHEMA)
                                .resolve(session, 4711, "TestType"),
                        "two root component rows make the mapping ambiguous, so it must fail rather than"
                                + " pick the first");
                Assert.assertTrue(many.getMessage().contains("more than one root component row"),
                        "and the reason must say the mapping is ambiguous: " + many.getMessage());
            } finally {
                session.close();
            }
        }
    }

    /** A root SegmentID or ComponentTypeID outside the documented range fails the mapping. */
    public void outOfRangeRootValuesFailTheMapping() throws Exception {
        assertRootRefusal(1, 0, "SEGMENTID 0");
        assertRootRefusal(1, 37, "SEGMENTID 37");
        assertRootRefusal(0, 1, "COMPONENTTYPEID 0");
        assertRootRefusal(100_000, 1, "COMPONENTTYPEID 100000");

        // And an unsafe schema is refused when the resolver is built, before any statement exists.
        SqlUnavailableException refusal = Assert.assertThrows(SqlUnavailableException.class,
                () -> new PhysicalSchemaResolver(new Db2Dialect(), "ICM ADMIN"),
                "an unprovable schema must be refused at construction");
        Assert.assertTrue(refusal.getMessage().contains("repository.jdbc.schema"),
                "naming the configuration key the operator has to change: " + refusal.getMessage());
    }

    private static void assertRootRefusal(int componentTypeId, int segmentId, String expectedDetail)
            throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            fake.tables("ICMUT00001001");
            fake.answersSqlContaining("FROM ICMADMIN.ICMSTCOMPDEFS",
                    FakeResultTable.oneRow(List.of("COMPONENTTYPEID", "SEGMENTID"), componentTypeId, segmentId));
            JdbcSession session = session(fake);
            try {
                SqlUnavailableException refusal = Assert.assertThrows(SqlUnavailableException.class,
                        () -> new PhysicalSchemaResolver(new Db2Dialect(), SCHEMA)
                                .resolve(session, 4711, "TestType"),
                        "a root value of " + componentTypeId + "/" + segmentId + " cannot be mapped");
                Assert.assertTrue(refusal.getMessage().contains(expectedDetail),
                        "the refusal must name the offending value (" + expectedDetail + "): "
                                + refusal.getMessage());
            } finally {
                session.close();
            }
        }
    }

    // ================================================================== the aggregate

    /**
     * The generated aggregate is SELECT-only, names every segment in order, collapses duplicates with
     * {@code UNION}, reads the ItemID date key and binds 8 values per segment.
     */
    public void theAggregateSqlNamesEverySegmentAndBindsEightValuesPerSegment() throws Exception {
        SqlQueryBuilder builder = SqlQueryBuilder.forSchema(new Db2Dialect(), SCHEMA);
        ScanWindows windows = ScanWindows.anchoredAt(LocalDate.of(2024, 6, 15));

        PhysicalSchema one = new PhysicalSchema(4711, "One", 1, 1, List.of("ICMUT00001001"));
        AggregateQuery single = builder.aggregate(one, windows.parameters());
        Assert.assertEquals(WINDOW_BOUNDS_PER_SEGMENT, single.parameterCount(),
                "a one-segment ItemType binds exactly the eight window boundaries");
        assertSelectOnlyShape(single.sql());
        Assert.assertTrue(normalized(single.sql()).contains("SUBSTR(ITEMID, 9, 6)"),
                "the date key is the documented SUBSTR(ItemID, 9, 6), never a parsed VersionID or CreateTS: "
                        + normalized(single.sql()));
        Assert.assertFalse(normalized(single.sql()).contains("VERSIONID"),
                "the aggregate must not read VersionID at all");
        Assert.assertFalse(normalized(single.sql()).contains("CREATETS"),
                "and must not derive the logical creation date from a root row's CreateTS");
        Assert.assertTrue(normalized(single.sql()).contains("COUNT(DISTINCT ITEMID)"),
                "the total is a distinct-ItemID count, never COUNT(*) over a root table: "
                        + normalized(single.sql()));
        Assert.assertFalse(Pattern.compile("COUNT\\(\\*\\)[^)]*FROM ICMUT").matcher(normalized(single.sql())).find(),
                "a bare COUNT(*) over a root table would count versions, so it must not appear");

        PhysicalSchema three = new PhysicalSchema(4712, "Three", 1, 3,
                List.of("ICMUT00001001", "ICMUT00001002", "ICMUT00001003"));
        AggregateQuery multi = builder.aggregate(three, windows.parameters());
        Assert.assertEquals(WINDOW_BOUNDS_PER_SEGMENT * 3, multi.parameterCount(),
                "a three-segment ItemType binds 24 values: the eight boundaries repeated once per segment");

        String normalized = normalized(multi.sql());
        int first = normalized.indexOf("ICMADMIN.ICMUT00001001");
        int second = normalized.indexOf("ICMADMIN.ICMUT00001002");
        int third = normalized.indexOf("ICMADMIN.ICMUT00001003");
        Assert.assertTrue(first > 0 && second > first && third > second,
                "every expected segment must appear, in segment order: " + normalized);
        Assert.assertTrue(normalized.contains(" UNION "),
                "the per-segment branches are combined with UNION so duplicates collapse across segments: "
                        + normalized);
        Assert.assertFalse(normalized.contains("UNION ALL"),
                "UNION ALL would keep a versioned item's duplicates and inflate the total: " + normalized);

        for (String column : List.of("TOTAL_ITEMS", "CREATED_TODAY", "CREATED_LAST_7_DAYS",
                "CREATED_LAST_30_DAYS", "CREATED_CURRENT_YEAR")) {
            Assert.assertTrue(normalized.contains(column),
                    "the aggregate must expose the column " + column + ": " + normalized);
        }
        Assert.assertTrue(normalized.contains("COALESCE(SUM("),
                "each window count is coalesced to a real zero, so 'no rows matched' cannot be mistaken for"
                        + " 'not measurable': " + normalized);

        // The parameter order is the contract, and it is the same eight values per segment.
        List<Object> expected = new ArrayList<>();
        for (int segment = 0; segment < 3; segment++) {
            expected.addAll(windows.parameters());
        }
        Assert.assertEquals(expected, multi.parameters(),
                "the bound values are the window tuple repeated once per segment, in segment order");
    }

    /**
     * The counting semantics, evaluated against a physical row model for one segment, several segments, a
     * versioned item and an empty ItemType.
     *
     * <p>Every number below is produced by {@link #evaluateAggregate} from the tables the generated SQL
     * actually names, so an implementation that forgets a segment or keeps duplicates returns a different
     * number and fails.
     */
    public void theAggregateCountsDistinctItemIdsAcrossVersionsAndSegments() throws Exception {
        LocalDate anchor = LocalDate.of(2024, 6, 15);
        SqlQueryBuilder builder = SqlQueryBuilder.forSchema(new Db2Dialect(), SCHEMA);
        ScanWindows windows = ScanWindows.anchoredAt(anchor);

        // ---- one segment: three distinct items, one of them created today.
        String today = itemId(LocalDate.of(2024, 6, 15), 1);
        String lastWeek = itemId(LocalDate.of(2024, 6, 10), 2);
        String lastYear = itemId(LocalDate.of(2023, 1, 31), 3);
        PhysicalSchema one = new PhysicalSchema(4711, "One", 1, 1, List.of("ICMUT00001001"));
        AggregateQuery single = builder.aggregate(one, windows.parameters());
        Map<String, List<String>> physical = new LinkedHashMap<>();
        physical.put("ICMUT00001001", List.of(today, lastWeek, lastYear));
        long[] singleCounts = evaluateAggregate(single, physical);
        Assert.assertEquals(3L, singleCounts[0], "three distinct ItemIDs is a total of three");
        Assert.assertEquals(1L, singleCounts[1], "exactly one of them was created today");
        Assert.assertEquals(2L, singleCounts[2], "two fall inside the last 7 days");
        Assert.assertEquals(2L, singleCounts[3], "and two inside the last 30 days");
        Assert.assertEquals(2L, singleCounts[4], "and two inside the current calendar year");

        // ---- duplicate ItemID across TWO VERSIONS of one item counts once, IN EVERY COLUMN.
        // This is the single most consequential shape: a single-segment ItemType is the common case, and
        // with only one branch UNION has nothing to deduplicate against - the per-branch SELECT DISTINCT is
        // the only thing collapsing the two versions. A regression that dropped it would double every
        // versioned item's count in all five columns, and the total assertion alone would not see it.
        physical.put("ICMUT00001001", List.of(today, today));
        long[] versions = evaluateAggregate(single, physical);
        Assert.assertEquals(1L, versions[0],
                "two root rows sharing one ItemID are ONE logical item, so the total is one - a COUNT(*)"
                        + " over the root table would say two");
        for (int window = 1; window < 5; window++) {
            Assert.assertEquals(1L, versions[window],
                    "and every window column must also say one, not two: the duplicate version row must be"
                            + " collapsed there too, because the flags are a pure function of the ItemID."
                            + " Column " + (window + 1) + " said " + versions[window]);
        }

        // ---- duplicate ItemID across TWO SEGMENTS counts once.
        PhysicalSchema three = new PhysicalSchema(4712, "Three", 1, 3,
                List.of("ICMUT00001001", "ICMUT00001002", "ICMUT00001003"));
        AggregateQuery multi = builder.aggregate(three, windows.parameters());
        Map<String, List<String>> across = new LinkedHashMap<>();
        across.put("ICMUT00001001", List.of(today, lastWeek));
        across.put("ICMUT00001002", List.of(today));
        across.put("ICMUT00001003", List.of(lastYear));
        long[] multiCounts = evaluateAggregate(multi, across);
        Assert.assertEquals(3L, multiCounts[0],
                "the same ItemID in two segments is still ONE logical item: the union must collapse it");

        // ---- an empty ItemType returns REAL ZEROS, not an unavailable metric.
        Map<String, List<String>> empty = new LinkedHashMap<>();
        empty.put("ICMUT00001001", List.of());
        long[] zero = evaluateAggregate(single, empty);
        for (int index = 0; index < zero.length; index++) {
            Assert.assertEquals(0L, zero[index],
                    "an ItemType with no rows reports a real zero in column " + (index + 1));
        }

        // ---- and those zeros reach a DTO as AVAILABLE zeros, never as a missing measurement.
        PhysicalSchema emptySchema = one;
        AggregateQuery emptyQuery = builder.aggregate(emptySchema, windows.parameters());
        try (FakeJdbc fake = db2Driver()) {
            fake.tables("ICMUT00001001");
            fake.answersSqlContaining("FROM ICMADMIN.ICMUT00001001", call -> {
                long[] counts = evaluateAggregate(emptyQuery, empty);
                return FakeResultTable.oneRow(List.of("TOTAL_ITEMS", "CREATED_TODAY", "CREATED_LAST_7_DAYS",
                        "CREATED_LAST_30_DAYS", "CREATED_CURRENT_YEAR"),
                        counts[0], counts[1], counts[2], counts[3], counts[4]);
            });
            JdbcSession session = session(fake);
            try {
                long[] read = runAggregate(session, emptyQuery);
                ItemTypeStatistics measured = ItemTypeStatistics.measured("scan-test",
                        new ItemTypeSummary("EmptyType", "", 4711, "SAP", "SAP", ""),
                        java.time.Instant.now(), java.time.Instant.now(), 1L,
                        ItemTypeStatistics.SOURCE_JDBC,
                        new com.mraibo.cminsight.statistics.ItemTypeAggregate(read[0],
                                com.mraibo.cminsight.statistics.MetricValue.available(read[1]),
                                com.mraibo.cminsight.statistics.MetricValue.available(read[2]),
                                com.mraibo.cminsight.statistics.MetricValue.available(read[3]),
                                com.mraibo.cminsight.statistics.MetricValue.available(read[4])));
                Assert.assertTrue(measured.totalAvailable(),
                        "an empty ItemType's total is a measured zero, not an unavailable metric");
                Assert.assertTrue(measured.createdToday().isAvailable(),
                        "and so is its created-today count");
                Assert.assertEquals(ItemTypeStatistics.Status.OK, measured.status(),
                        "so nothing about it is partial or failed");
                Assert.assertNull(measured.versions().value(),
                        "while versions stay unmeasured in this goal");
                Assert.assertNull(measured.parts().value(), "and so do parts");
            } finally {
                session.close();
            }
        }
    }

    /**
     * Mutation control: the evaluator CAN tell "every segment" from "only the current segment".
     *
     * <p>Without this, the equality assertions above could be satisfied by an evaluator that ignores the
     * SQL. Here the same physical model is evaluated twice: once with the generated three-segment statement
     * and once with a hand-built statement that names only the current segment. The two answers must differ,
     * and the short one must be the undercount - which is exactly what the goal forbids.
     */
    public void theAggregateEvaluatorDetectsCountingOnlyTheCurrentSegment() throws Exception {
        LocalDate anchor = LocalDate.of(2024, 6, 15);
        SqlQueryBuilder builder = SqlQueryBuilder.forSchema(new Db2Dialect(), SCHEMA);
        ScanWindows windows = ScanWindows.anchoredAt(anchor);
        PhysicalSchema three = new PhysicalSchema(4712, "Three", 1, 3,
                List.of("ICMUT00001001", "ICMUT00001002", "ICMUT00001003"));

        Map<String, List<String>> physical = new LinkedHashMap<>();
        physical.put("ICMUT00001001", List.of(itemId(LocalDate.of(2024, 1, 1), 1)));
        physical.put("ICMUT00001002", List.of(itemId(LocalDate.of(2024, 2, 1), 2)));
        physical.put("ICMUT00001003", List.of(itemId(LocalDate.of(2024, 3, 1), 3)));

        long allSegments = evaluateAggregate(builder.aggregate(three, windows.parameters()), physical)[0];
        Assert.assertEquals(3L, allSegments, "the generated statement accounts for all three segments");

        // A statement that names only the current segment - the exact defect the goal describes.
        String currentOnly = new Db2Dialect().aggregateSql(SCHEMA, List.of("ICMUT00001003"), "ITEMID");
        AggregateQuery shortQuery = new AggregateQuery(currentOnly, windows.parameters(), "current only");
        long onlyCurrent = evaluateAggregate(shortQuery, physical)[0];

        Assert.assertTrue(onlyCurrent < allSegments,
                "counting only the current table must be DETECTABLY smaller, otherwise the multi-segment"
                        + " assertions would be vacuous; short=" + onlyCurrent + " all=" + allSegments);
        Assert.assertEquals(1L, onlyCurrent, "the short statement sees exactly the one item in segment 3");
    }

    /**
     * Mutation control for the single-branch case: dropping the per-branch {@code SELECT DISTINCT} is
     * detected.
     *
     * <p>With one branch there is no {@code UNION} to collapse against, so the per-branch {@code DISTINCT} is
     * the only thing that stops two versions of one item counting as two. This control proves the evaluator
     * can see the difference, so the "all five columns say one" assertion above is evidence.
     */
    public void theAggregateEvaluatorDetectsADroppedPerBranchDistinct() throws Exception {
        ScanWindows windows = ScanWindows.anchoredAt(LocalDate.of(2024, 6, 15));
        String today = itemId(LocalDate.of(2024, 6, 15), 1);
        Map<String, List<String>> physical = new LinkedHashMap<>();
        physical.put("ICMUT00001001", List.of(today, today));

        String correct = new Db2Dialect().aggregateSql(SCHEMA, List.of("ICMUT00001001"), "ITEMID");
        Assert.assertTrue(normalized(correct).contains("SELECT DISTINCT"),
                "a single-branch statement must deduplicate inside the branch, because UNION has nothing to"
                        + " collapse against with one branch: " + normalized(correct));
        AggregateQuery correctQuery = new AggregateQuery(correct, windows.parameters(), "single segment");
        Assert.assertEquals(1L, evaluateAggregate(correctQuery, physical)[0],
                "the generated single-branch statement collapses the two versions");

        String mutated = correct.replace("SELECT DISTINCT", "SELECT");
        Assert.assertFalse(normalized(mutated).contains("SELECT DISTINCT"),
                "the control really removed the DISTINCT clause");
        AggregateQuery mutatedQuery = new AggregateQuery(mutated, windows.parameters(), "mutated");
        Assert.assertEquals(2L, evaluateAggregate(mutatedQuery, physical)[0],
                "with the per-branch DISTINCT dropped the same physical model must yield TWO, so the"
                        + " assertion above is evidence rather than a tautology");
    }

    /**
     * Every non-SELECT date/statement form is refused by the query surface itself, with the driver untouched.
     *
     * <p>This is the regression guard for the defect this suite found: DB2's anchor query was once
     * {@code VALUES CURRENT DATE}, which the read-only guard refuses - so the dialect text and the guard must
     * be pinned TOGETHER. A future "shorter spelling" that is not a SELECT must fail here rather than at
     * scan time on a customer's DB2.
     */
    public void theQuerySurfaceRefusesEveryNonSelectFormBeforeTheDriverIsTouched() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            JdbcSession session = session(fake);
            try {
                for (String refused : List.of("VALUES CURRENT DATE",
                        "DELETE FROM ICMADMIN.ICMUT00001001",
                        "UPDATE ICMADMIN.ICMUT00001001 SET ITEMID = 1",
                        "{call DO_SOMETHING(1)}")) {
                    int callsBefore = fake.calls().size();
                    Assert.assertThrows(JdbcAccessException.class,
                            () -> session.query(new AggregateQuery(refused, List.of(), "refused form"),
                                    row -> { }),
                            "'" + refused + "' must be refused by the read-only query surface");
                    Assert.assertEquals(callsBefore, fake.calls().size(),
                            "and the driver must not be touched at all for '" + refused + "'");
                }
                Assert.assertEquals(0, fake.executeUpdateCalls(),
                        "nothing was ever attempted as an update");
                Assert.assertEquals(0L, fake.commitCalls(), "and no commit was issued");
            } finally {
                session.close();
            }
        }
    }

    /** Every generated statement is SELECT-only, and the write path is refused at the driver too. */
    public void everyGeneratedStatementIsSelectOnlyAndTheWritePathIsRefused() throws Exception {
        SqlQueryBuilder builder = SqlQueryBuilder.forSchema(new OracleDialect(), SCHEMA);
        ScanWindows windows = ScanWindows.anchoredAt(LocalDate.of(2024, 6, 15));
        PhysicalSchema schema = new PhysicalSchema(4711, "T", 1, 2,
                List.of("ICMUT00001001", "ICMUT00001002"));

        List<String> statements = List.of(
                new Db2Dialect().currentDateSql(),
                new OracleDialect().currentDateSql(),
                new Db2Dialect().zeroRowProbeSql(SCHEMA, "ICMUT00001001"),
                new OracleDialect().zeroRowProbeSql(SCHEMA, "ICMUT00001001"),
                new Db2Dialect().aggregateSql(SCHEMA, schema.rootTables(), "ITEMID"),
                new OracleDialect().aggregateSql(SCHEMA, schema.rootTables(), "ITEMID"),
                builder.rootComponentMappingSql(),
                builder.aggregate(schema, windows.parameters()).sql(),
                builder.totalItems(schema).sql());

        Pattern writeKeyword = Pattern.compile(
                "\\b(INSERT|UPDATE|DELETE|MERGE|TRUNCATE|CREATE|ALTER|DROP|GRANT|REVOKE|CALL)\\b");
        for (String sql : statements) {
            assertSelectOnlyShape(sql);
            Assert.assertFalse(writeKeyword.matcher(sql.toUpperCase(java.util.Locale.ROOT)).find(),
                    "a generated statement must be a pure read: " + sql);
        }

        // The driver-level refusal: the fake refuses an update and counts the attempt, so the assertion is
        // about a real call rather than about the text. The stray connection is opened directly, outside the
        // analytics path, precisely so the counter can be shown to move.
        try (FakeJdbc fake = db2Driver()) {
            fake.answersWith(FakeResultTable.empty("V"));
            JdbcSession session = session(fake);
            try {
                Assert.assertEquals(0, fake.executeUpdateCalls(),
                        "the analytics path must never issue an update");
                Assert.assertEquals(0L, fake.commitCalls(),
                        "and no commit may ever be issued by the analytics path");
                Assert.assertEquals(0L, fake.rollbackCalls(), "nor a rollback");

                java.sql.Connection stray = java.sql.DriverManager.getConnection(fake.url(), "u", "p");
                try (java.sql.Statement statement = stray.createStatement()) {
                    java.sql.SQLException refused = Assert.assertThrows(java.sql.SQLException.class,
                            () -> statement.executeUpdate("DELETE FROM ICMUT00001001"),
                            "the fake driver refuses an update, as a SELECT-only account would");
                    Assert.assertTrue(refused.getMessage().contains(FakeJdbc.RAW_FAILURE_MARKER),
                            "this refusal is the driver's own, which is the point of the control");
                } finally {
                    stray.close();
                }
                Assert.assertEquals(1, fake.executeUpdateCalls(),
                        "so the zero above is a measurement: the same counter moves when an update really is"
                                + " attempted");
            } finally {
                session.close();
            }
        }
    }

    /** A statement the query surface would refuse is refused before the driver sees it. */
    public void theQuerySurfaceRefusesANonSelectStatementBeforeTheDriverSeesIt() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            JdbcSession session = session(fake);
            try {
                JdbcAccessException refusal = Assert.assertThrows(JdbcAccessException.class,
                        () -> session.query(new AggregateQuery("UPDATE ICMUT00001001 SET X = 1", List.of(),
                                "not a read"), row -> { }),
                        "the query surface must refuse a non-SELECT statement itself");
                Assert.assertFalse(refusal.getMessage().contains("UPDATE ICMUT"),
                        "and must not reproduce the refused SQL text: " + refusal.getMessage());
                Assert.assertEquals(0, fake.executeUpdateCalls(),
                        "the statement never reached the driver as an update");
                Assert.assertTrue(fake.calls().isEmpty() || !fake.calls().get(0).normalized().contains("UPDATE"),
                        "and no UPDATE was ever executed: " + fake.executedSql());
            } finally {
                session.close();
            }
        }
    }

    // ================================================================== the evaluator

    /**
     * Evaluates a generated aggregate statement against a physical row model.
     *
     * <p>This is the suite's own independent statement of the documented semantics, and it is deliberately
     * driven by the SQL the implementation actually produced: it reads the {@code ICMUTnnnnnsss} names out
     * of the text, applies {@code UNION} (distinct) only when the statement says so, and computes each
     * window from that segment's eight bound values using the same text comparison the SQL performs. A
     * statement that names one table too few, or keeps duplicates, therefore returns a different number.
     *
     * @return {@code [total, today, last7, last30, currentYear]}
     */
    private static long[] evaluateAggregate(AggregateQuery query, Map<String, List<String>> physicalRows) {
        String sql = normalized(query.sql());
        List<String> tables = new ArrayList<>();
        Matcher matcher = Pattern.compile(Pattern.quote(SCHEMA) + "\\.(ICMUT[0-9]{8})").matcher(sql);
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        Assert.assertFalse(tables.isEmpty(), "the aggregate must name at least one physical root table");

        List<Object> parameters = query.parameters();
        Assert.assertEquals(WINDOW_BOUNDS_PER_SEGMENT * tables.size(), parameters.size(),
                "the statement binds exactly eight values per table it names: " + sql);

        // Whether the statement collapses a duplicate ItemID, and where it does so, is read OUT of the SQL
        // rather than assumed. Two independent mechanisms do it:
        //   * UNION across two or more branches deduplicates the whole result;
        //   * a per-branch SELECT DISTINCT is what collapses two versions of one item when there is only
        //     ONE branch, where UNION has nothing to deduplicate against.
        // Modelled faithfully, because a regression that dropped the per-branch DISTINCT would double-count
        // every versioned item on the most common ItemType shape - a single-segment ItemType.
        boolean unionAll = sql.contains("UNION ALL");
        boolean acrossBranches = tables.size() > 1 && sql.contains(" UNION ") && !unionAll;
        boolean distinctWithinBranch = sql.contains("SELECT DISTINCT");
        boolean distinct = !unionAll && (acrossBranches || distinctWithinBranch);

        Set<String> seenGlobal = new LinkedHashSet<>();
        long rowCount = 0L;
        long[] windowCounts = new long[4];

        for (int segment = 0; segment < tables.size(); segment++) {
            String[] bounds = new String[WINDOW_BOUNDS_PER_SEGMENT];
            for (int index = 0; index < WINDOW_BOUNDS_PER_SEGMENT; index++) {
                bounds[index] = String.valueOf(parameters.get(segment * WINDOW_BOUNDS_PER_SEGMENT + index));
            }
            for (String itemId : physicalRows.getOrDefault(tables.get(segment), List.of())) {
                String key = itemId.substring(ItemIdDateKey.ITEM_ID_OFFSET - 1,
                        ItemIdDateKey.ITEM_ID_OFFSET - 1 + ItemIdDateKey.LENGTH);
                rowCount++;
                boolean counted = !distinct || seenGlobal.add(itemId);
                if (!counted) {
                    continue;
                }
                for (int window = 0; window < 4; window++) {
                    if (key.compareTo(bounds[window * 2]) >= 0 && key.compareTo(bounds[window * 2 + 1]) < 0) {
                        windowCounts[window]++;
                    }
                }
            }
        }
        long total = distinct ? seenGlobal.size() : rowCount;
        return new long[] {total, windowCounts[0], windowCounts[1], windowCounts[2], windowCounts[3]};
    }

    /** Runs the aggregate through a real session and reads its five columns. */
    private static long[] runAggregate(JdbcSession session, AggregateQuery query) throws Exception {
        long[] values = new long[5];
        session.query(query, row -> {
            for (int index = 0; index < 5; index++) {
                values[index] = row.getLong(index + 1);
            }
        });
        return values;
    }

    // ================================================================== helpers

    /**
     * A 26-character ItemID whose date key sits at the documented position 9.
     *
     * <p>The prefix and suffix are inert so the only thing a comparison can see is the encoded date.
     */
    private static String itemId(LocalDate date, int sequence) {
        String key = ItemIdDateKey.encode(date).orElseThrow();
        return "AAAAAAAA" + key + String.format("%010d", sequence) + "ZZ";
    }

    /**
     * True when a statement is a pure read.
     *
     * <p>Deliberately restricted to {@code SELECT} and {@code WITH}: that is the rule the session's own
     * read-only guard applies, so a dialect whose date query began with something else - {@code VALUES}, for
     * instance - would pass a looser check here and then fail at runtime, which is exactly the integration
     * defect this suite found.
     */
    private static boolean isSelectOnly(String sql) {
        String leading = sql.stripLeading().toUpperCase(java.util.Locale.ROOT);
        return leading.startsWith("SELECT") || leading.startsWith("WITH");
    }

    /** A statement with every run of whitespace collapsed and upper-cased, for stable fragment matching. */
    private static String normalized(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static void assertSelectOnlyShape(String sql) {
        Assert.assertTrue(isSelectOnly(sql), "a generated statement must be a complete read: " + sql);
        String upper = sql.toUpperCase(java.util.Locale.ROOT);
        Assert.assertFalse(upper.contains("EXECUTEUPDATE") || upper.contains("EXECUTE IMMEDIATE"),
                "and must never contain an execution escape: " + sql);
    }

    private static FakeJdbc db2Driver() {
        FakeJdbc.loadVendorDriverClass(FakeJdbc.DB2_DRIVER_CLASS);
        return FakeJdbc.register("jdbc:db2:");
    }

    private static FakeJdbc oracleDriver() {
        FakeJdbc.loadVendorDriverClass(FakeJdbc.ORACLE_DRIVER_CLASS);
        return FakeJdbc.register("jdbc:oracle:");
    }

    private static JdbcSession session(FakeJdbc fake) throws Exception {
        RepositoryProfile profile = new RepositoryProfile(
                "sql-test", "SQL test", "SQLT",
                DatabaseVendor.valueOf(fake.urlPrefix().equals("jdbc:db2:") ? "DB2" : "ORACLE"),
                fake.url(), SCHEMA,
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.CM_USER_KEY, "CM_INSIGHT_T5_SQL_U"),
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.CM_PASSWORD_KEY,
                        "CM_INSIGHT_T5_SQL_P"),
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.JDBC_USER_KEY,
                        "CM_INSIGHT_T5_SQL_U"),
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.JDBC_PASSWORD_KEY,
                        "CM_INSIGHT_T5_SQL_P"),
                null, null);
        SecretResolver secrets = new SecretResolver(
                Map.of("CM_INSIGHT_T5_SQL_U", "u", "CM_INSIGHT_T5_SQL_P", "p"), null);
        return new JdbcSessionFactory(profile, secrets, 5).create();
    }
}
