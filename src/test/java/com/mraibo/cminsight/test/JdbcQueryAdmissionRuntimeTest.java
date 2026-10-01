package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.db.AggregateQuery;
import com.mraibo.cminsight.db.Db2Dialect;
import com.mraibo.cminsight.db.JdbcAccessException;
import com.mraibo.cminsight.db.JdbcSession;
import com.mraibo.cminsight.db.JdbcSessionFactory;
import com.mraibo.cminsight.db.OracleDialect;
import com.mraibo.cminsight.db.PhysicalSchema;
import com.mraibo.cminsight.db.SqlAdmission;
import com.mraibo.cminsight.db.SqlQueryBuilder;
import com.mraibo.cminsight.statistics.ScanWindows;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Goal 03A section D (the runtime half): the strengthened admission rule governs the SQL the analytics
 * layer actually prepares, at the moment it prepares it.
 *
 * <h2>Why the rule's own suite is not enough</h2>
 *
 * <p>{@link SqlAdmissionTest} proves the rule refuses the right statements. That would still be compatible
 * with a defect where the query surface never CALLS it - the layer could keep its prefix check and the rule
 * suite would pass. So this suite drives the real {@link JdbcSession} over the real
 * {@link FakeJdbc} driver and measures two things that no amount of rule-testing can substitute for:
 *
 * <ul>
 *   <li>every mandatory rejection shape is refused <em>before the driver is touched</em>, measured as a
 *       zero delta on {@code prepareStatement} and on the recorded statement executions;</li>
 *   <li>every approved production statement is executed end to end, with the driver recording exactly the
 *       prepared-statement path the analytics layer is allowed to use - and never an update, a batch or a
 *       {@code createStatement}/{@code execute} escape.</li>
 * </ul>
 *
 * <h2>The control that makes "the driver was not touched" mean something</h2>
 *
 * <p>A refusal that happened because the fake driver cannot answer at all would prove nothing, so the same
 * representative statement is issued OUTSIDE the analytics surface on a stray connection: the driver serves
 * it and returns a row. So the zero on the analytics path is a measurement of the refusal, not of a driver
 * that refuses everything.
 *
 * <p>Nothing here sleeps, and no statement is hand-copied: the approved set is produced by the dialects and
 * builders, so a future SQL change is exercised here.
 */
public class JdbcQueryAdmissionRuntimeTest {

    private static final String SCHEMA = "ICMADMIN";

    // ------------------------------------------------------------------ fixtures

    private static FakeJdbc db2Driver() {
        FakeJdbc.loadVendorDriverClass(FakeJdbc.DB2_DRIVER_CLASS);
        return FakeJdbc.register("jdbc:db2:");
    }

    private static SecretResolver jdbcSecrets(String repositoryId) {
        String envId = repositoryId.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
        return new SecretResolver(Map.of("JDBC_" + envId + "_USER", "jdbc-user",
                "JDBC_" + envId + "_PASSWORD", "jdbc-password"), null);
    }

    private static JdbcSession session(FakeJdbc fake) throws Exception {
        RepositoryProfile profile = TestSupport.profile("admission-runtime");
        return new JdbcSessionFactory(profile, jdbcSecrets("admission-runtime"), 5).create();
    }

    /** One approved statement family, with the label used in a failure message. */
    private record Approved(String family, AggregateQuery query) {
    }

    // ------------------------------------------------------------------ the approved surface

    /**
     * Every approved production statement family is ACCEPTED by the runtime query surface and reaches the
     * driver as a prepared {@code SELECT}.
     *
     * <p>The driver-level accounting at the end is the strongest part: after running the whole approved
     * surface the fake must report one prepared statement per statement run, zero {@code createStatement}
     * calls, zero updates and zero batches. That is the behavioural statement "generic {@code execute(...)}
     * and {@code executeUpdate(...)} are not part of this surface" - asserted against a counter rather than
     * against the source text.
     */
    public void everyApprovedProductionStatementIsAcceptedAndExecutedThroughAPreparedSelect()
            throws Exception {
        List<Approved> approved = approvedStatements();
        try (FakeJdbc fake = db2Driver()) {
            fake.tables("ICMUT00001001", "ICMUT00001002", "ICMUT00001003");
            fake.answersWith(FakeResultTable.longs("V", 1L));
            JdbcSession session = session(fake);
            try {
                for (Approved statement : approved) {
                    // The rule and the runtime must agree, so both are asserted for every statement.
                    Assert.assertTrue(SqlAdmission.admitted(statement.query().sql()),
                            statement.family() + " must be ADMITTED by the rule: " + statement.query().sql());
                    int callsBefore = fake.calls().size();
                    long[] read = new long[1];
                    session.query(statement.query(), row -> read[0] = row.getLong(1));
                    Assert.assertEquals(callsBefore + 1, fake.calls().size(),
                            statement.family() + " must really reach the driver, but it did not: "
                                    + statement.query().sql());
                    Assert.assertEquals(1L, read[0],
                            statement.family() + " must be executed and its single row read");
                }

                Assert.assertEquals(approved.size(), fake.prepareStatementCalls(),
                        "every approved statement must go through the prepared-statement path exactly once");
                Assert.assertEquals(0, fake.createStatementCalls(),
                        "the analytics surface must never issue a plain createStatement/execute escape");
                Assert.assertEquals(0, fake.executeUpdateCalls(),
                        "and never an update: the read-only guarantee is structural, not a hint");
                Assert.assertEquals(0, fake.batchCalls(), "and never a batch write");
                Assert.assertEquals(0L, fake.commitCalls(), "and never a commit");
                Assert.assertEquals(0L, fake.rollbackCalls(), "nor a rollback");
            } finally {
                session.close();
            }
        }
    }

    /**
     * The approved statements, produced by the dialects and builders themselves - never hand-copied.
     */
    private static List<Approved> approvedStatements() throws Exception {
        Db2Dialect db2 = new Db2Dialect();
        OracleDialect oracle = new OracleDialect();
        SqlQueryBuilder db2Builder = SqlQueryBuilder.forSchema(db2, SCHEMA);
        SqlQueryBuilder oracleBuilder = SqlQueryBuilder.forSchema(oracle, SCHEMA);
        ScanWindows windows = ScanWindows.anchoredAt(LocalDate.of(2024, 6, 15));
        PhysicalSchema one = new PhysicalSchema(4711, "OneSegment", 1, 1, List.of("ICMUT00001001"));
        PhysicalSchema three = new PhysicalSchema(4712, "ThreeSegments", 1, 3,
                List.of("ICMUT00001001", "ICMUT00001002", "ICMUT00001003"));

        List<Approved> approved = new ArrayList<>();
        approved.add(new Approved("DB2 current-date SELECT", new AggregateQuery(db2.currentDateSql(),
                List.of(), "read database date")));
        approved.add(new Approved("Oracle current-date SELECT", new AggregateQuery(oracle.currentDateSql(),
                List.of(), "read database date")));
        approved.add(new Approved("root-component mapping", db2Builder.rootComponentMapping(4711)));
        approved.add(new Approved("root-table zero-row probe (DB2)",
                db2Builder.rootTableProbe("ICMUT00001001")));
        approved.add(new Approved("root-table zero-row probe (Oracle)",
                oracleBuilder.rootTableProbe("ICMUT00001001")));
        approved.add(new Approved("one-segment aggregate", db2Builder.aggregate(one, windows.parameters())));
        approved.add(new Approved("multi-segment aggregate", db2Builder.aggregate(three, windows.parameters())));
        approved.add(new Approved("multi-segment aggregate (Oracle)",
                oracleBuilder.aggregate(three, windows.parameters())));
        approved.add(new Approved("total-only aggregate", db2Builder.totalItems(one)));
        approved.add(new Approved("total-only aggregate (Oracle)", oracleBuilder.totalItems(three)));
        return List.copyOf(approved);
    }

    // ------------------------------------------------------------------ the mandatory refusals

    /**
     * Every mandatory rejection shape is REFUSED at runtime, before the driver is touched, and the control
     * proves the driver really would have served it.
     *
     * <p>Two independent measurements per shape: no statement was recorded by the driver, and no
     * {@code prepareStatement} was issued. The second is decisive, because {@code prepareStatement} is the
     * first driver call the query path makes - a surface that only refused after preparing would still have
     * handed the SQL to the driver.
     */
    public void everyMandatoryRejectionShapeIsRefusedBeforeTheDriverIsTouched() throws Exception {
        List<String> refused = mandatoryRejectionShapes();
        try (FakeJdbc fake = db2Driver()) {
            fake.tables("ICMUT00001001", "ICMUT00001002", "ICMUT00001003");
            fake.answersWith(FakeResultTable.longs("V", 1L));
            JdbcSession session = session(fake);
            try {
                for (String shape : refused) {
                    int callsBefore = fake.calls().size();
                    int preparesBefore = fake.prepareStatementCalls();
                    JdbcAccessException refusal = Assert.assertThrows(JdbcAccessException.class,
                            () -> session.query(new AggregateQuery(shape, List.of(), "refused shape"),
                                    row -> { }),
                            "this shape must be REFUSED by the query surface itself: " + shape);
                    Assert.assertFalse(refusal.getMessage() == null || refusal.getMessage().isBlank(),
                            "and the refusal must explain itself: " + refusal.getMessage());
                    Assert.assertFalse(refusal.getMessage().contains(shape),
                            "while never reproducing the refused SQL, which would be an injection surface of"
                                    + " its own: " + refusal.getMessage());
                    Assert.assertEquals(callsBefore, fake.calls().size(),
                            "the refused statement must never reach the driver: " + shape);
                    Assert.assertEquals(preparesBefore, fake.prepareStatementCalls(),
                            "and must be refused BEFORE prepareStatement, the first driver call on the query"
                                    + " path: " + shape);
                }

                // The control: issued outside the analytics surface, the representative shape really is
                // served by the same driver and returns a row. So the zeros above measure the refusal.
                String representative = refused.get(0);
                int callsBeforeControl = fake.calls().size();
                java.sql.Connection stray = DriverManager.getConnection(fake.url(), FakeJdbc.FAKE_USER,
                        FakeJdbc.FAKE_PASSWORD);
                try (java.sql.Statement statement = stray.createStatement()) {
                    try (java.sql.ResultSet rows = statement.executeQuery(representative)) {
                        Assert.assertTrue(rows.next(),
                                "the fake driver must really serve this statement, otherwise the analytics"
                                        + " refusal above is untested: " + representative);
                    }
                } finally {
                    stray.close();
                }
                Assert.assertEquals(callsBeforeControl + 1, fake.calls().size(),
                        "the control executed exactly one statement at the driver, so the analytics zeros"
                                + " above are measurements rather than an untested driver");
            } finally {
                session.close();
            }
        }
    }

    /**
     * The mandatory rejection shapes, as whole statements.
     *
     * <p>Every one of them begins with {@code SELECT} or {@code WITH}, which is the property
     * {@link SqlAdmissionTest} pins with its pre-fix control: the old prefix gate admitted all of them.
     */
    private static List<String> mandatoryRejectionShapes() {
        return List.of(
                // DB2 data-change table reference.
                "SELECT * FROM FINAL TABLE (DELETE FROM ICMADMIN.ICMUT00001001 WHERE ITEMID = 1)",
                "SELECT * FROM FINAL TABLE (INSERT INTO ICMADMIN.ICMUT00001001 (ITEMID) VALUES (1))",
                "SELECT * FROM FINAL TABLE (UPDATE ICMADMIN.ICMUT00001001 SET ITEMID = 1)",
                "SELECT * FROM FINAL TABLE (MERGE INTO ICMADMIN.ICMUT00001001 AS T"
                        + " USING ICMADMIN.ICMUT00001001 AS S ON T.ITEMID = S.ITEMID"
                        + " WHEN MATCHED THEN UPDATE SET T.ITEMID = S.ITEMID)",
                // WITH/CTE carrying DML.
                "WITH SRC AS (SELECT ITEMID FROM ICMADMIN.ICMUT00001001)"
                        + " DELETE FROM ICMADMIN.ICMUT00001001 WHERE ITEMID IN (SELECT ITEMID FROM SRC)",
                "WITH SRC AS (SELECT ITEMID FROM ICMADMIN.ICMUT00001001)"
                        + " UPDATE ICMADMIN.ICMUT00001001 SET ITEMID = 1",
                "WITH SRC AS (SELECT ITEMID FROM ICMADMIN.ICMUT00001001)"
                        + " INSERT INTO ICMADMIN.ICMUT00001001 (ITEMID) SELECT ITEMID FROM SRC",
                // A second statement after a separator.
                "SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0;"
                        + " DELETE FROM ICMADMIN.ICMUT00001001",
                "SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0;"
                        + " DROP TABLE ICMADMIN.ICMUT00001001",
                // Comment-obfuscated write tokens.
                "SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0"
                        + " -- DELETE FROM ICMADMIN.ICMUT00001001",
                "SELECT 1 AS PRESENT /* DELETE FROM ICMADMIN.ICMUT00001001 */"
                        + " FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0",
                // Control verbs carried inside an admitted SELECT shape.
                "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0 AND TRUNCATE = 1",
                "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0 AND GRANT = 1",
                "SELECT 1 AS A FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0 AND EXECUTE = 1");
    }
}
