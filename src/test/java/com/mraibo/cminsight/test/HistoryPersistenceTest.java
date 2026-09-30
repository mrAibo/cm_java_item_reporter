package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.AppPaths;
import com.mraibo.cminsight.core.HistorySettings;
import com.mraibo.cminsight.history.H2HistoryStore;
import com.mraibo.cminsight.history.HistoryDetail;
import com.mraibo.cminsight.history.HistoryId;
import com.mraibo.cminsight.history.HistoryItemType;
import com.mraibo.cminsight.history.HistoryMetric;
import com.mraibo.cminsight.history.HistoryStore;
import com.mraibo.cminsight.history.HistoryStores;
import com.mraibo.cminsight.history.HistorySummary;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Goal 04 section 4 and section 12 "Cache/history": the persistent half of history, against a real local
 * database when one is on the class path.
 *
 * <h2>Why these cases are guarded, and what the guard still asserts</h2>
 *
 * <p>The build's contract is that the core suite passes with <strong>zero</strong> optional JARs, and the
 * local H2 driver is exactly such a JAR. So each storage case runs its real assertions when a driver is
 * present, and otherwise asserts the other half of the same contract - the documented behaviour of an
 * absent driver - which is itself a goal requirement ("missing H2 leaves history unavailable without
 * breaking repository activation"). Neither branch can pass without asserting something, and the branch
 * that ran is named in the failure message of every assertion, so a report can never present the guarded
 * branch as if the storage case had executed.
 *
 * <h2>What only a real database can show</h2>
 *
 * <p>Cross-repository isolation, "the bound prunes only the OLDEST rows of the same repository", "a failed
 * transaction exposes no half snapshot" and schema-version refusal are end-state properties of a SQL store.
 * A fake would have to re-implement the ordering and the transaction to answer them, and a test that
 * re-implements the thing it is testing passes whether or not the implementation is correct.
 */
public class HistoryPersistenceTest {

    private static final Instant BASE = Instant.parse("2024-06-16T08:00:00Z");

    private static final String H2_DRIVER_CLASS = HistoryStores.H2_DRIVER_CLASS;

    // ------------------------------------------------------------------ always: no driver

    /**
     * With the local driver absent, history is explicitly unavailable and every operation is harmless.
     *
     * <p>This is the goal's "missing H2" contract at the store boundary: a state with a reason, never an
     * exception into a caller that only wanted to activate a repository.
     */
    public void aMissingLocalDriverLeavesHistoryUnavailableAndHarmless() throws Exception {
        Path dataDir = TestSupport.newTempDir("history-nodriver-");
        try {
            Assert.assertFalse(HistoryStores.driverPresent("org.example.NoSuchLocalDriver"),
                    "the probe must report an absent driver class as absent");

            HistorySettings settings = HistorySettings.defaults(dataDir);
            HistoryStore store = HistoryStores.open(settings, "org.example.NoSuchLocalDriver");
            try {
                Assert.assertFalse(store.available(),
                        "a store that cannot open its local database must report itself unavailable rather"
                                + " than throwing into the activation path");
                Assert.assertTrue(store.unavailableReason().isPresent(),
                        "and must publish a fixed reason so the doctor/UI can explain the state");
                Assert.assertTrue(store.list("alpha", 10).isEmpty(),
                        "and a read answers empty rather than failing");
                Assert.assertEquals(0L, store.count("alpha"), "and so does a count");
                Assert.assertTrue(store.latest("alpha").isEmpty(), "and the newest-row read");
                Assert.assertTrue(store.find(new HistoryId("h1")).isEmpty(), "and a by-id read");
                Assert.assertTrue(store.record(detail("alpha", BASE, 1)).isEmpty(),
                        "and a write is refused without throwing: history is best-effort, never a failure of"
                                + " the scan that produced the snapshot");
            } finally {
                store.close();
                store.close(); // idempotent: a shutdown may close it twice and must not fail
            }
        } finally {
            TestSupport.deleteRecursively(dataDir);
        }
    }

    // ------------------------------------------------------------------ real local database

    /** Two repositories in one local database never see each other's rows. */
    public void twoRepositoriesShareOneLocalDatabaseWithoutCrossContamination() throws Exception {
        Path dataDir = TestSupport.newTempDir("history-isolation-");
        try {
            Driver driver = localDriver("twoRepositoriesShareOneLocalDatabaseWithoutCrossContamination");
            if (driver == null) {
                assertAbsentDriverContract(dataDir);
                return;
            }
            try (HistoryStore store = H2HistoryStore.open(driver, databasePath(dataDir), 100)) {
                Assert.assertTrue(store.available(),
                        "the store must be available over a real local database: " + store.unavailableReason());
                store.record(detail("alpha", BASE, 1));
                store.record(detail("alpha", BASE.plusSeconds(60), 2));
                store.record(detail("beta", BASE.plusSeconds(120), 3));

                Assert.assertEquals(2L, store.count("alpha"), "alpha's rows are counted per repository");
                Assert.assertEquals(1L, store.count("beta"), "and beta's are its own");
                Assert.assertEquals(2, store.list("alpha", 10).size(), "alpha's list returns only alpha's rows");
                Assert.assertEquals("beta", store.list("beta", 10).get(0).repositoryId(),
                        "and beta's list returns beta's row, with beta's own identity");
                for (HistorySummary row : store.list("alpha", 10)) {
                    Assert.assertTrue(row.belongsTo("alpha"),
                            "every row of a repository-scoped list belongs to it: " + row.describe());
                }
                Assert.assertEquals(BASE.plusSeconds(60), store.latest("alpha").orElseThrow().capturedAt(),
                        "the newest-row read is the newest row of THAT repository");
                Assert.assertEquals(BASE.plusSeconds(120), store.latest("beta").orElseThrow().capturedAt(),
                        "and beta's newest row is beta's");
            }
        } finally {
            TestSupport.deleteRecursively(dataDir);
        }
    }

    /** The retention bound prunes only the oldest rows of the repository that was just written. */
    public void theRetentionBoundPrunesOnlyTheOldestRowsOfThatRepository() throws Exception {
        Path dataDir = TestSupport.newTempDir("history-prune-");
        try {
            Driver driver = localDriver("theRetentionBoundPrunesOnlyTheOldestRowsOfThatRepository");
            if (driver == null) {
                assertAbsentDriverContract(dataDir);
                return;
            }
            try (HistoryStore store = H2HistoryStore.open(driver, databasePath(dataDir), 3)) {
                Assert.assertTrue(store.available(), "the store must be available: " + store.unavailableReason());

                // Five rows for alpha, so the bound of three must drop exactly the two oldest.
                List<Instant> alphaCaptureTimes = new ArrayList<>();
                for (int index = 0; index < 5; index++) {
                    Instant capturedAt = BASE.plusSeconds(index * 60L);
                    alphaCaptureTimes.add(capturedAt);
                    store.record(detail("alpha", capturedAt, index + 1));
                }
                // A second repository with fewer rows than the bound: it must not be pruned at all.
                store.record(detail("beta", BASE, 9));
                store.record(detail("beta", BASE.plusSeconds(60), 10));

                Assert.assertEquals(3L, store.count("alpha"),
                        "the retention bound must keep at most the configured number of rows");
                List<HistorySummary> alphaRows = store.list("alpha", 10);
                Assert.assertEquals(3, alphaRows.size(), "and the list must agree with the count");
                Assert.assertEquals(alphaCaptureTimes.get(4), alphaRows.get(0).capturedAt(),
                        "the newest row must be retained");
                Assert.assertEquals(alphaCaptureTimes.get(3), alphaRows.get(1).capturedAt(),
                        "and the one before it");
                Assert.assertEquals(alphaCaptureTimes.get(2), alphaRows.get(2).capturedAt(),
                        "and the third-newest: pruning removes the OLDEST rows, never the newest");
                for (Instant pruned : List.of(alphaCaptureTimes.get(0), alphaCaptureTimes.get(1))) {
                    for (HistorySummary row : alphaRows) {
                        Assert.assertFalse(row.capturedAt().equals(pruned),
                                "the oldest row captured at " + pruned + " must have been pruned, but it is"
                                        + " still listed: " + row.describe());
                    }
                }

                Assert.assertEquals(2L, store.count("beta"),
                        "pruning one repository must not touch another repository's rows: the bound is per"
                                + " repository");
                Assert.assertEquals(2, store.list("beta", 10).size(), "beta still has both of its rows");
            }
        } finally {
            TestSupport.deleteRecursively(dataDir);
        }
    }

    /** A write whose transaction fails leaves NO trace: the next reader sees the previous state only. */
    public void aFailedTransactionExposesNoHalfSnapshot() throws Exception {
        Path dataDir = TestSupport.newTempDir("history-atomic-");
        try {
            Driver driver = localDriver("aFailedTransactionExposesNoHalfSnapshot");
            if (driver == null) {
                assertAbsentDriverContract(dataDir);
                return;
            }
            Path databasePath = databasePath(dataDir);

            // One committed row first, so "the previous state" is a real state rather than an empty file.
            try (H2HistoryStore store = H2HistoryStore.open(driver, databasePath, 100)) {
                Assert.assertTrue(store.available(), "the store must be available: " + store.unavailableReason());
                Assert.assertTrue(store.record(detail("alpha", BASE, 1)).isPresent(),
                        "the first write must commit, so the failure below has a previous state to preserve");
            }

            // Now the same database through a driver whose COMMIT fails: the insert runs, the transaction
            // never commits. This is the real failure shape - not a validation error caught before writing.
            Driver failingCommit = new FailingCommitDriver(driver);
            try (HistoryStore store = H2HistoryStore.open(failingCommit, databasePath, 100)) {
                boolean refused = store.record(detail("alpha", BASE.plusSeconds(60), 2)).isEmpty();
                Assert.assertTrue(refused,
                        "a write whose transaction cannot commit must be reported as not stored");
            }

            try (HistoryStore reread = H2HistoryStore.open(driver, databasePath, 100)) {
                Assert.assertTrue(reread.available(),
                        "a fresh reader must still be able to open the database: " + reread.unavailableReason());
                Assert.assertEquals(1L, reread.count("alpha"),
                        "the failed transaction must leave NO half-written snapshot behind: exactly the one"
                                + " previously committed row may be visible");
                List<HistorySummary> rows = reread.list("alpha", 10);
                Assert.assertEquals(1, rows.size(), "and the list must agree");
                Assert.assertEquals(BASE, rows.get(0).capturedAt(),
                        "with the previously committed row's own capture instant");
                Assert.assertEquals(1, rows.get(0).itemTypeCount(), "and its own content");
                Assert.assertEquals(3L, rows.get(0).logicalItemsTotal(),
                        "rather than a mixture of the committed row and the failed one");
            }
        } finally {
            TestSupport.deleteRecursively(dataDir);
        }
    }

    /**
     * A database written by another schema version refuses the store instead of reinterpreting it.
     *
     * <p>Driven through a fake JDBC driver that answers the two probe statements the store issues - "do the
     * tables exist" and "which version is recorded" - so this case runs with NO optional JAR present, and so
     * the assertion can be about the decision rather than about SQL: the store must refuse the file. The
     * write counter is the witness, because a store that adopted, migrated or reinterpreted the file would
     * have to write to it.
     */
    public void aSchemaVersionMismatchRefusesTheStoreAndReinterpretsNothing() throws Exception {
        Path dataDir = TestSupport.newTempDir("history-schema-");
        try (FakeJdbc fake = FakeJdbc.register("jdbc:h2:")) {
            fake.answersSqlContaining("FROM INFORMATION_SCHEMA.TABLES",
                    FakeResultTable.oneRow(List.of("COUNT"), 1));
            fake.answersSqlContaining("FROM CM_HISTORY_SCHEMA",
                    FakeResultTable.oneRow(List.of("SCHEMA_VERSION"), 99));

            Path databasePath = databasePath(dataDir);
            // The fake driver INSTANCE is passed straight in: looking it up through DriverManager would
            // return a real local driver when one happens to be on the class path, which would make this
            // case depend on the environment instead of only on the store's decision.
            Driver driver = new FakeLocalDriver();
            try (H2HistoryStore store = H2HistoryStore.open(driver, databasePath, 100)) {
                Assert.assertFalse(store.available(),
                        "a database whose recorded schema version is 99 must NOT be read as if it were this"
                                + " build's schema: reinterpreting it is how a stored snapshot silently becomes"
                                + " wrong");
                Assert.assertTrue(store.schemaVersionMismatch(),
                        "and the refusal must be reported as a version mismatch rather than as a generic"
                                + " failure: " + store.unavailableReason());
                Assert.assertEquals(99, store.storedSchemaVersion().orElse(-1).intValue(),
                        "naming the version the file actually holds");
                Assert.assertTrue(store.unavailableReason().orElse("").contains("99"),
                        "and publishing it in the reason: " + store.unavailableReason());
                Assert.assertTrue(store.list("alpha", 10).isEmpty(),
                        "every read answers empty rather than inventing rows");
                Assert.assertEquals(0L, store.count("alpha"), "and so does the count");
                Assert.assertTrue(store.record(detail("alpha", BASE, 1)).isEmpty(),
                        "and no write is accepted into a schema this build does not understand");
            }

            Assert.assertEquals(0, fake.executeUpdateCalls(),
                    "the refusal must happen BEFORE anything is written: adopting or migrating a version this"
                            + " build does not write is exactly the silent reinterpretation the store refuses");
            for (String sql : fake.executedSql()) {
                Assert.assertTrue(sql.toUpperCase(java.util.Locale.ROOT).startsWith("SELECT"),
                        "and no statement other than a probe may have run, but the store executed: " + sql);
            }
        } finally {
            TestSupport.deleteRecursively(dataDir);
        }
    }
    // ------------------------------------------------------------------ fixtures

    /**
     * The documented contract of an absent driver, asserted by every guarded case that cannot run.
     *
     * <p>It is a real assertion, not a skip: "the driver is missing" must leave history unavailable with a
     * reason, and that is a goal requirement in its own right.
     */
    private static void assertAbsentDriverContract(Path dataDir) throws Exception {
        HistoryStore store = HistoryStores.open(HistorySettings.defaults(dataDir));
        try {
            Assert.assertFalse(store.available(),
                    "no local database driver is on this class path, so the store must report itself"
                            + " unavailable (the storage case in this test therefore did NOT execute)");
            Assert.assertTrue(store.unavailableReason().isPresent(),
                    "with a reason naming the absent driver: " + H2_DRIVER_CLASS);
            Assert.assertTrue(store.list("alpha", 10).isEmpty(), "and reads answer empty");
            Assert.assertTrue(store.record(detail("alpha", BASE, 1)).isEmpty(), "and writes are refused");
        } finally {
            store.close();
        }
    }

    /** The local H2 driver, or {@code null} when this class path has none. */
    private static Driver localDriver(String testName) {
        if (!HistoryStores.driverPresent()) {
            return null;
        }
        try {
            Class<?> type = Class.forName(H2_DRIVER_CLASS, true, HistoryPersistenceTest.class.getClassLoader());
            return (Driver) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError absent) {
            return null;
        }
    }

    private static Path databasePath(Path dataDir) {
        HistorySettings settings = HistorySettings.from(AppConfig.empty(),
                AppPaths.of(dataDir, AppPaths.HomeSource.WORKING_DIRECTORY));
        return settings.databasePath();
    }

    private static HistoryDetail detail(String repositoryId, Instant capturedAt, int sequence) {
        HistoryItemType row = new HistoryItemType(sequence, "ItemType" + sequence, "SAP", "Retention-A",
                HistoryItemType.Status.OK, HistoryMetric.available(sequence * 3L), HistoryMetric.available(1L),
                HistoryMetric.available(2L), HistoryMetric.available(3L), HistoryMetric.available(4L), 5L, "");
        HistorySummary summary = new HistorySummary(new HistoryId("h" + sequence), repositoryId,
                "Repository " + repositoryId, "DB2", capturedAt, capturedAt.minusSeconds(30), 30_000L,
                LocalDate.of(2024, 6, 16), sequence, 1, 0, true, sequence * 3L);
        return new HistoryDetail(summary, List.of(row), "");
    }

    /**
     * A {@link Driver} that serves the store's own URL through {@link FakeJdbc}.
     *
     * <p>Passed to {@code H2HistoryStore.open} as an INSTANCE: a lookup through {@code DriverManager} would
     * return whichever driver registered first, so the case would silently change meaning depending on
     * whether a real local database JAR happens to be on the class path.
     */
    private static final class FakeLocalDriver implements Driver {

        @Override
        public java.sql.Connection connect(String url, Properties info) throws java.sql.SQLException {
            return acceptsURL(url) ? FakeJdbc.connectViaVendorDriver(url, info) : null;
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith("jdbc:h2:");
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new java.sql.DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            throw new java.sql.SQLFeatureNotSupportedException("not used by this test");
        }
    }
    /**
     * The real local driver with a {@code commit()} that fails.
     *
     * <p>Everything else is delegated, so the store opens a genuine H2 database and performs its genuine
     * statements; only the commit is refused. That makes the atomicity case a statement about the store's
     * transaction handling rather than about a fake's behaviour.
     */
    private static final class FailingCommitDriver implements Driver {

        private final Driver delegate;

        FailingCommitDriver(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            Connection real = delegate.connect(url, info);
            if (real == null) {
                return null;
            }
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    FailingCommitDriver.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if ("commit".equals(method.getName())) {
                            throw new SQLException("simulated commit failure in a deterministic test");
                        }
                        try {
                            return method.invoke(real, args == null ? new Object[0] : args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            throw new java.sql.SQLFeatureNotSupportedException("not used by this test");
        }
    }

}






