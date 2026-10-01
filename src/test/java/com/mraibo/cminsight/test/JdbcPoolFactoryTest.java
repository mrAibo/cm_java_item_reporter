package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.DatabaseVendor;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CreationFailure;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.connection.PoolException;
import com.mraibo.cminsight.core.CloseState;
import com.mraibo.cminsight.db.AggregateQuery;
import com.mraibo.cminsight.db.JdbcAccessException;
import com.mraibo.cminsight.db.JdbcSession;
import com.mraibo.cminsight.db.JdbcSessionFactory;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Goal 03 section 6 and section 15 "Pool/factory": the create-verdict contract, session poisoning, close
 * quarantine, the local health probe and the hard physical bound.
 *
 * <h2>Why every case runs against a registered fake driver</h2>
 *
 * <p>The build and the normal test run must pass with <strong>zero</strong> DB2 or Oracle JARs present, so
 * the driver is {@link FakeJdbc}: a real {@link java.sql.Driver} registered through the real
 * {@link DriverManager}, named exactly like the vendor's own class so local readiness can be POSITIVE, and
 * instrumented so each assertion below is a measurement rather than a restatement of the code.
 *
 * <h2>The three questions this suite exists to answer</h2>
 *
 * <ol>
 *   <li><strong>Is a failure before the allocation boundary reported clean?</strong> "Clean" has to mean
 *       something observable, so it is checked as all three at once: the verdict is
 *       {@link CreationFailure.Cleanup#PROVEN_CLEAN}, the driver recorded <em>zero</em> physical opens, and
 *       the pool's quarantined count is still zero. A verdict that released nothing would still pass a
 *       one-line assertion.</li>
 *   <li><strong>Does a failure after the boundary pay for what it allocated?</strong> The same three-way
 *       check, inverted: the physical connection count the fake keeps must show whether the close really
 *       happened, and an unproven close must leave the pool degraded rather than free the slot.</li>
 *   <li><strong>Is the health probe free of driver calls?</strong> Proven by counting every method
 *       invocation on the driver, its connections, statements and result sets.</li>
 * </ol>
 *
 * <p>No test sleeps to sequence a thread: the concurrency cases use latches and release them explicitly.
 */
public class JdbcPoolFactoryTest {

    /** Distinctive environment variable names, so no host environment can satisfy them by accident. */
    private static final String ENV_USER = "CM_INSIGHT_TEST_T5_JDBC_USER";

    private static final String ENV_PASSWORD = "CM_INSIGHT_TEST_T5_JDBC_PASSWORD";

    /** A pool borrow timeout generous enough that a capacity wait never races the test's own assertions. */
    private static final Duration BORROW_TIMEOUT = Duration.ofSeconds(20);

    // ------------------------------------------------------------------ fixtures

    /** A DB2 repository profile whose four credentials all come from environment names. */
    private static RepositoryProfile profile(DatabaseVendor vendor, String jdbcUrl) {
        return new RepositoryProfile(
                "jdbtest",
                "JDBC test repository",
                "JDBT",
                vendor,
                jdbcUrl,
                "ICMADMIN",
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.CM_USER_KEY, ENV_USER),
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.CM_PASSWORD_KEY, ENV_PASSWORD),
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.JDBC_USER_KEY, ENV_USER),
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.JDBC_PASSWORD_KEY, ENV_PASSWORD),
                null,
                null);
    }

    /** A resolver that can resolve the JDBC pair, or one that cannot - the "credential disappears" case. */
    private static SecretResolver secrets(boolean jdbcCredentialAvailable) {
        return new SecretResolver(jdbcCredentialAvailable
                ? Map.of(ENV_USER, "test-jdbc-user", ENV_PASSWORD, "test-jdbc-password")
                : Map.of(), null);
    }

    /**
     * A registered fake DB2 driver plus the vendor-named driver class loaded.
     *
     * <p>The class has to be <em>loaded and initialised</em>, because readiness is "the class is present
     * AND a registered {@code Driver} serves this vendor". A class that is on the class path but never
     * initialised has not registered itself, which is exactly the "present but unusable" state
     * {@code JdbcDrivers.driverReady} is there to refuse.
     */
    private static FakeJdbc db2Driver() {
        FakeJdbc.loadVendorDriverClass(FakeJdbc.DB2_DRIVER_CLASS);
        return FakeJdbc.register("jdbc:db2:");
    }

    private static JdbcSessionFactory factory(RepositoryProfile profile, SecretResolver secrets) {
        return new JdbcSessionFactory(profile, secrets, 5);
    }

    /** One created session, for the cases that exercise the query surface directly. */
    private static JdbcSession session(FakeJdbc fake) throws Exception {
        return factory(profile(DatabaseVendor.DB2, fake.url()), secrets(true)).create();
    }

    private static BoundedPool<JdbcSession> pool(JdbcSessionFactory factory, int size) {
        return factory.lazyPool("jdbc-t5", size, BORROW_TIMEOUT, null, 0);
    }

    /** The {@link CreationFailure} a refused borrow reports, unwrapping the pool's wrapper. */
    private static CreationFailure borrowRefusal(BoundedPool<JdbcSession> pool, String what) throws Exception {
        PoolException refusal = Assert.assertThrows(PoolException.class, pool::borrow, what);
        Throwable cause = refusal.getCause();
        Assert.assertTrue(cause instanceof CreationFailure,
                what + ": the pool must report the factory's own CreationFailure verdict, but the cause was "
                        + (cause == null ? "null" : cause.getClass().getName()));
        return (CreationFailure) cause;
    }

    /**
     * Asserts that no driver text, credential or URL reached any message in the failure's chain.
     *
     * <p>The fake's raw failure text, its user name and its URL all carry markers only this suite can
     * produce, so an escape is detectable rather than a matter of opinion. The goal forbids returning the raw
     * {@code SQLException} text, so a failure message must not contain any of them.
     */
    private static void assertNothingLeaked(Throwable failure, String what) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            Assert.assertFalse(message.contains(FakeJdbc.RAW_FAILURE_MARKER),
                    what + ": the sanitized failure must not reproduce the driver's raw message, but "
                            + current.getClass().getSimpleName() + " carried it: " + message);
            Assert.assertFalse(message.contains(FakeJdbc.FAKE_USER),
                    what + ": the failure must not carry the database user name: " + message);
            Assert.assertFalse(message.contains("jdbc:db2://"),
                    what + ": the failure must not carry the JDBC URL: " + message);
        }
    }

    // ------------------------------------------------------------------ pre-allocation verdicts

    /**
     * A missing JDBC credential fails BEFORE any connection is requested: proven clean, zero driver
     * connects, no quarantine, and the same pool creates successfully once the credential is restored.
     *
     * <p>The recovery half is what makes it meaningful. A pool that leaked one capacity slot per failed
     * creation would still pass a test that only asserted the first verdict; with a size of 1, a single leaked
     * slot makes the recovery borrow impossible, which is exactly the regression Goal 02B fixed for the CM
     * path.
     */
    public void aMissingJdbcCredentialIsProvenCleanWithZeroConnectsAndRecovers() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            RepositoryProfile profile = profile(DatabaseVendor.DB2, fake.url());
            JdbcSessionFactory factory = factory(profile, secrets(false));
            BoundedPool<JdbcSession> pool = pool(factory, 1);

            CreationFailure refusal = borrowRefusal(pool,
                    "a borrow with no resolvable JDBC credential must fail");
            Assert.assertTrue(refusal.cleanupProven(),
                    "a credential that cannot be resolved happens before DriverManager is called, so nothing"
                            + " was allocated and the reserved slot must be released; the verdict was "
                            + refusal.cleanup());
            assertNothingLeaked(refusal, "missing JDBC credential");

            Assert.assertEquals(0, fake.connectRequests(),
                    "no physical connection may be requested when the credential is missing");
            Assert.assertEquals(0, fake.physicalOpens(),
                    "no physical connection may be opened when the credential is missing");
            Assert.assertEquals(0, pool.metrics().quarantined(),
                    "a pre-allocation credential failure must not quarantine a capacity slot");
            Assert.assertEquals(0L, pool.metrics().createQuarantineFailures(),
                    "and the pool's own counter must agree that nothing was quarantined");

            // Recovery: the SAME pool, now with a resolvable credential, must create a session.
            JdbcSessionFactory recovering = new JdbcSessionFactory(profile, secrets(true), 5);
            BoundedPool<JdbcSession> recoveringPool = pool(recovering, 1);
            try (Lease<JdbcSession> lease = recoveringPool.borrow()) {
                Assert.assertNotNull(lease.value(), "the recovered borrow must yield a session");
                Assert.assertEquals(1, fake.physicalOpens(),
                        "exactly one physical connection is opened for the recovered borrow");
            }
            Assert.assertEquals(0, recoveringPool.metrics().quarantined(),
                    "the recovered pool is not degraded");
            Assert.assertEquals(1, fake.physicalLive(),
                    "a HEALTHY returned lease goes back into the idle set, so its physical connection stays"
                            + " open for reuse; the pool deliberately does not close it on return");
            recoveringPool.close();
            Assert.assertEquals(0, fake.physicalLive(),
                    "and closing the pool is what releases its idle physical connections");
            pool.close();
        }
    }

    /**
     * A vendor/URL mismatch and a present-but-unregistered driver are both known-clean failures.
     *
     * <p>Two distinct pre-allocation conditions, one shared shape: the driver is never asked for a
     * connection, and the reserved slot is released. A mismatch must never be "tried anyway" - the wrong
     * driver cannot serve the URL, and the failure it would produce says less than the readiness verdict.
     */
    public void aMissingOrMismatchedDriverIsAKnownCleanFailure() throws Exception {
        // (a) The URL belongs to another vendor.
        try (FakeJdbc fake = db2Driver()) {
            RepositoryProfile mismatched = profile(DatabaseVendor.DB2, "jdbc:oracle:thin:@//fake.example:1521/XE");
            JdbcSessionFactory factory = factory(mismatched, secrets(true));
            Assert.assertFalse(factory.readiness().ready(),
                    "a DB2 profile with an Oracle URL is not ready, and must never be attempted");

            BoundedPool<JdbcSession> pool = pool(factory, 1);
            CreationFailure refusal = borrowRefusal(pool, "a vendor/URL mismatch must fail the creation");
            Assert.assertTrue(refusal.cleanupProven(),
                    "a mismatch is discovered before DriverManager is called, so the verdict is clean");
            Assert.assertEquals(0, fake.connectRequests(),
                    "a mismatched URL must never reach the driver");
            Assert.assertEquals(0, pool.metrics().quarantined(),
                    "and it must not cost the pool a capacity slot");
            assertNothingLeaked(refusal, "vendor/URL mismatch");
        }

        // (b) The driver class is present but nothing is registered for this vendor, so a
        //     DriverManager lookup could not be served.
        FakeJdbc.loadVendorDriverClass(FakeJdbc.DB2_DRIVER_CLASS);
        Assert.assertTrue(FakeJdbc.vendorDriverClassAvailable(FakeJdbc.DB2_DRIVER_CLASS),
                "this suite deliberately carries a real DB2 driver class so readiness can be POSITIVE");
        List<Driver> removed = deregisterDriversNamed(FakeJdbc.DB2_DRIVER_CLASS);
        try {
            try (FakeJdbc fake = FakeJdbc.register("jdbc:db2:")) {
                RepositoryProfile profile = profile(DatabaseVendor.DB2, fake.url());
                JdbcSessionFactory factory = factory(profile, secrets(true));
                Assert.assertFalse(factory.readiness().ready(),
                        "a driver class that is present but not registered with DriverManager cannot serve a"
                                + " connection, so readiness must report not ready");

                BoundedPool<JdbcSession> pool = pool(factory, 1);
                CreationFailure refusal = borrowRefusal(pool,
                        "an unregistered driver must fail the creation");
                Assert.assertTrue(refusal.cleanupProven(),
                        "an unregistered driver is a pre-allocation condition, so the verdict is clean");
                Assert.assertEquals(0, fake.connectRequests(),
                        "and nothing may be requested from the driver");
                Assert.assertEquals(0, pool.metrics().quarantined(),
                        "and no capacity slot may be quarantined for it");
                assertNothingLeaked(refusal, "unregistered driver");
            }
        } finally {
            for (Driver driver : removed) {
                try {
                    DriverManager.registerDriver(driver);
                } catch (SQLException failure) {
                    throw new AssertionError("cannot restore the fake DB2 driver registration", failure);
                }
            }
        }
    }

    /** Removes every registered driver with this class name, so "not registered" is testable. */
    private static List<Driver> deregisterDriversNamed(String className) throws SQLException {
        java.util.ArrayList<Driver> removed = new java.util.ArrayList<>();
        Enumeration<Driver> registered = DriverManager.getDrivers();
        while (registered.hasMoreElements()) {
            Driver driver = registered.nextElement();
            if (driver != null && className.equals(driver.getClass().getName())) {
                DriverManager.deregisterDriver(driver);
                removed.add(driver);
            }
        }
        return removed;
    }

    /**
     * A {@code DriverManager.getConnection} that throws before returning a Connection is clean under the
     * JDBC abstraction.
     *
     * <p>No application-owned connection exists on that path, so the verdict is {@code PROVEN_CLEAN} and the
     * slot is released. The sanitized cause is preserved for diagnostics, but the driver's raw message is
     * not reachable through it.
     */
    public void getConnectionSqlExceptionBeforeAConnectionIsReturnedIsClean() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            RepositoryProfile profile = profile(DatabaseVendor.DB2, fake.url());
            JdbcSessionFactory factory = factory(profile, secrets(true));
            fake.failConnectWithMessage("simulated driver refusal at connect time");

            CreationFailure refusal = Assert.assertThrows(CreationFailure.class, factory::create,
                    "a getConnection SQLException must be reported as a CreationFailure");
            Assert.assertTrue(refusal.cleanupProven(),
                    "no Connection was returned, so nothing application-owned exists and the verdict must be"
                            + " PROVEN_CLEAN; it was " + refusal.cleanup());
            Assert.assertEquals(0, fake.physicalOpens(),
                    "the driver must record no physical open for this attempt");
            Assert.assertTrue(fake.connectRequests() >= 1,
                    "and the request really was made, otherwise this test proves nothing");
            Assert.assertNotNull(refusal.getCause(),
                    "the sanitized SQLException must be preserved as the diagnostic cause");
            Assert.assertTrue(refusal.getCause() instanceof SQLException,
                    "and that cause must be an SQLException; it was "
                            + refusal.getCause().getClass().getName());
            assertNothingLeaked(refusal, "getConnection SQLException");
            Assert.assertTrue(refusal.getMessage().contains("open connection"),
                    "the message must name the fixed operation label so an operator can act: "
                            + refusal.getMessage());
        }
    }

    /** A post-open setup failure followed by a successful close is proven clean. */
    public void postOpenSetupFailureWithASuccessfulCloseIsClean() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            RepositoryProfile profile = profile(DatabaseVendor.DB2, fake.url());
            JdbcSessionFactory factory = factory(profile, secrets(true));
            fake.failSetUpWith(() -> FakeJdbc.rawFailure("set up session"));

            CreationFailure refusal = Assert.assertThrows(CreationFailure.class, factory::create,
                    "a setup failure after the connection was returned must be reported");
            Assert.assertTrue(refusal.cleanupProven(),
                    "the connection was returned and then closed normally, so the attempt is PROVEN_CLEAN");
            Assert.assertEquals(1, fake.physicalOpens(),
                    "exactly one physical connection was opened by this attempt");
            Assert.assertEquals(1, fake.closeAttempts(),
                    "and it was closed exactly once, because the allocation happened");
            Assert.assertEquals(0, fake.physicalLive(),
                    "the close returned normally, so the fake no longer counts it as live");
            Assert.assertEquals(0L, factory.liveConnections(),
                    "and the factory's live count agrees");
            Assert.assertEquals(1L, factory.closedCleanly(),
                    "the factory counted a clean close");
            assertNothingLeaked(refusal, "post-open setup failure");
        }
    }

    /**
     * A post-open setup failure whose close ALSO fails quarantines the capacity slot.
     *
     * <p>The exception from {@code close()} proves nothing about the physical connection, so the pool must
     * keep the slot consumed rather than authorise a replacement beside a connection that may still exist.
     */
    public void postOpenSetupFailureWithAFailedCloseIsQuarantined() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            RepositoryProfile profile = profile(DatabaseVendor.DB2, fake.url());
            JdbcSessionFactory factory = factory(profile, secrets(true));
            fake.failSetUpWith(() -> FakeJdbc.rawFailure("set up session"));
            fake.failCloseWith(() -> FakeJdbc.rawFailure("close connection"));

            BoundedPool<JdbcSession> pool = pool(factory, 1);
            CreationFailure refusal = borrowRefusal(pool,
                    "a setup failure with an unproven close must fail the borrow");
            Assert.assertFalse(refusal.cleanupProven(),
                    "the close threw, so the physical connection may still exist and the verdict must be"
                            + " UNPROVEN; it was " + refusal.cleanup());
            Assert.assertEquals(1, fake.physicalOpens(),
                    "one physical connection was opened");
            Assert.assertEquals(1, fake.physicalLive(),
                    "and the fake still counts it as live, because its close did not succeed");
            Assert.assertEquals(1, pool.metrics().quarantined(),
                    "the pool must quarantine the slot: no replacement may be created while the old"
                            + " connection may still exist");
            Assert.assertEquals(1L, pool.metrics().createQuarantineFailures(),
                    "and the quarantine came from the creation failure");
            Assert.assertEquals(1, fake.physicalLive(),
                    "the physical connection count is unchanged by the failed close");
            assertNothingLeaked(refusal, "setup failure with failed close");
        }
    }

    /** A driver that reports the read-only hint as unsupported is a safe warning, not a failed session. */
    public void anUnsupportedReadOnlyHintIsASafeWarningAndSessionStillWorks() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            RepositoryProfile profile = profile(DatabaseVendor.DB2, fake.url());
            JdbcSessionFactory factory = factory(profile, secrets(true));
            fake.readOnlyUnsupported();
            fake.answersWith(FakeResultTable.longs("V", 1L));

            JdbcSession session = factory.create();
            Assert.assertNotNull(session, "the session must still be created");
            Assert.assertTrue(factory.isHealthy(session),
                    "and it must be usable: the read-only hint is defence in depth, not the safety boundary");
            Assert.assertFalse(factory.warnings().isEmpty(),
                    "the unsupported hint must be recorded as a safe warning");
            for (String warning : factory.warnings()) {
                assertNothingLeaked(new AssertionError(warning), "read-only warning");
            }
            session.close();
        }
    }

    // ------------------------------------------------------------------ query and close failures

    /**
     * A query {@code SQLException} poisons the session BEFORE the lease is returned, and the pool then
     * retires it instead of handing it to the next borrower.
     *
     * <p>Both halves matter: the local flag must already be false while the lease is still held (otherwise
     * the pool cannot see the failure at all), and the returning thread must actually close and replace the
     * session. A test that only asserted the second would pass for an implementation that discovered the
     * failure much later.
     */
    public void aQuerySqlExceptionPoisonsTheSessionBeforeTheLeaseReturns() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            RepositoryProfile profile = profile(DatabaseVendor.DB2, fake.url());
            JdbcSessionFactory factory = factory(profile, secrets(true));
            BoundedPool<JdbcSession> pool = pool(factory, 1);

            Lease<JdbcSession> lease = pool.borrow();
            JdbcSession first = lease.value();
            Assert.assertTrue(factory.isHealthy(first), "a freshly created session is healthy");

            fake.failsQueriesWith(() -> FakeJdbc.rawFailure("execute select"));
            JdbcAccessException failure = Assert.assertThrows(JdbcAccessException.class,
                    () -> first.query(new AggregateQuery("SELECT 1 FROM ICMADMIN.ICMUT00001001", List.of(),
                            "probe"), row -> { }),
                    "a failing query must surface as a JdbcAccessException");
            assertNothingLeaked(failure, "failing query");

            Assert.assertFalse(factory.isHealthy(first),
                    "the session must already be marked unusable while the lease is still held, so the pool"
                            + " retires it on the returning thread");
            Assert.assertFalse(first.isUsable(),
                    "and the session's own local flag must agree");

            lease.close();
            Assert.assertEquals(0, pool.metrics().quarantined(),
                    "the retirement closed the connection normally, so nothing is quarantined");
            Assert.assertEquals(0, fake.physicalLive(),
                    "the poisoned session's physical connection must be released");

            // The replacement proves the pool really retired it rather than reusing it.
            fake.answersWith(FakeResultTable.longs("V", 1L));
            try (Lease<JdbcSession> second = pool.borrow()) {
                Assert.assertTrue(first != second.value(),
                        "the pool must hand out a REPLACEMENT session, never the poisoned one");
                Assert.assertEquals(2, fake.physicalOpens(),
                        "which means a second physical connection was opened");
                Assert.assertTrue(second.value().isUsable(),
                        "and the replacement is healthy, because it was never used for the failing query");
            }
            Assert.assertEquals(1, fake.physicalLive(),
                    "a healthy returned lease stays idle with its physical connection open for reuse; only a"
                            + " retirement closes a connection before the pool does");
            pool.close();
            Assert.assertEquals(0, fake.physicalLive(), "a pool close releases every idle connection");
        }
    }

    /**
     * A driver that reports the read-only hint as unsupported through its SQLSTATE rather than the dedicated
     * exception type is treated the same way.
     *
     * <p>A driver is not obliged to throw {@code SQLFeatureNotSupportedException}; some report SQLSTATE
     * {@code 0A000} on a plain {@link SQLException}. Both must produce a safe warning and a usable session,
     * because refusing to run a read-only query over a missing hint would trade a real capability for a
     * symbolic one - the structural SELECT-only surface is the boundary, not the hint.
     */
    public void aPlainFeatureNotSupportedSqlStateIsAlsoASafeWarning() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            RepositoryProfile profile = profile(DatabaseVendor.DB2, fake.url());
            JdbcSessionFactory factory = factory(profile, secrets(true));
            fake.failSetUpWith(() -> new SQLException(FakeJdbc.RAW_FAILURE_MARKER + " hint unsupported",
                    "0A000", 0));

            JdbcSession session = factory.create();
            Assert.assertNotNull(session, "the session must be created despite the unsupported hint");
            Assert.assertTrue(factory.isHealthy(session), "and it must be usable");
            Assert.assertFalse(factory.warnings().isEmpty(),
                    "the driver's refusal must be recorded as a safe warning");
            for (String warning : factory.warnings()) {
                assertNothingLeaked(new AssertionError(warning), "SQLSTATE 0A000 warning");
            }
            session.close();
        }
    }

    /**
     * A {@code WITH}-shaped aggregate is accepted by the read-only query surface, and a write is still
     * refused before the driver is touched.
     *
     * <p>Both halves matter: the aggregate this goal generates is a common-table expression, so a
     * SELECT-only rule that only accepted the literal word {@code SELECT} would refuse the product's own
     * query; and a rule that accepted anything would let a write through.
     */
    public void theQuerySurfaceAcceptsAWithShapedAggregateAndRefusesAWrite() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            fake.tables("ICMUT00001001");
            fake.answersWith(FakeResultTable.longs("TOTAL_ITEMS", 4L));
            JdbcSession session = session(fake);
            try {
                AggregateQuery aggregate = new AggregateQuery(
                        "WITH LOGICAL_ITEMS AS (SELECT ITEMID FROM ICMADMIN.ICMUT00001001)"
                                + " SELECT COUNT(DISTINCT ITEMID) AS TOTAL_ITEMS FROM LOGICAL_ITEMS",
                        List.of(), "logical item aggregate");
                long[] total = new long[1];
                session.query(aggregate, row -> total[0] = row.getLong(1));
                Assert.assertEquals(4L, total[0], "a WITH-shaped read must be executed and read");
                Assert.assertEquals(1, fake.calls().size(),
                        "and it really reached the driver: " + fake.executedSql());

                int callsBefore = fake.calls().size();
                JdbcAccessException refusal = Assert.assertThrows(JdbcAccessException.class,
                        () -> session.query(new AggregateQuery("DELETE FROM ICMADMIN.ICMUT00001001",
                                List.of(), "not a read"), row -> { }),
                        "a write statement must be refused by the query surface itself");
                Assert.assertEquals(callsBefore, fake.calls().size(),
                        "and the driver was never touched, which is what 'structurally read-only' means");
                Assert.assertEquals(0, fake.executeUpdateCalls(), "with no update attempted at all");
                Assert.assertFalse(refusal.getMessage().contains("DELETE"),
                        "and the refusal must not echo the SQL text: " + refusal.getMessage());
            } finally {
                session.close();
            }
        }
    }

    /**
     * A close that throws quarantines the slot, and the shutdown state says so.
     *
     * <p>This is the same rule the accepted CM pool follows: the capacity is deliberately lost rather than
     * freed, because the exception is no evidence that the physical connection is gone.
     */
    public void aFailedCloseQuarantinesAndReportsAnUncertainShutdown() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            RepositoryProfile profile = profile(DatabaseVendor.DB2, fake.url());
            JdbcSessionFactory factory = factory(profile, secrets(true));
            fake.answersWith(FakeResultTable.longs("V", 1L));
            BoundedPool<JdbcSession> pool = pool(factory, 2);

            try (Lease<JdbcSession> lease = pool.borrow()) {
                Assert.assertNotNull(lease.value(), "the borrow must produce a session");
            }
            Assert.assertEquals(1, fake.physicalLive(), "the returned session is idle and still live");

            fake.failCloseWith(() -> FakeJdbc.rawFailure("close connection"));
            pool.close();

            Assert.assertEquals(1, pool.metrics().quarantined(),
                    "a close that threw must quarantine the slot");
            Assert.assertEquals(CloseState.CLOSED_UNCERTAIN, pool.closeState(),
                    "and the pool's shutdown state must report the uncertainty, not a clean close");
            Assert.assertEquals(1, fake.physicalLive(),
                    "the fake keeps counting the connection as live, which is what 'unproven' means here");
            Assert.assertTrue(factory.closeFailures() >= 1, "the factory counted the close failure");
            Assert.assertTrue(pool.closedWithUncertainResources(),
                    "and the boolean uncertainty view agrees with the state");
        }
    }

    // ------------------------------------------------------------------ local health

    /**
     * {@code ResourceFactory.isHealthy} performs ZERO JDBC and driver calls.
     *
     * <p>The pool calls this while holding its internal lock, so a driver round trip here would stall every
     * concurrent borrow, every {@code metrics()} call, and the shutdown state a repository switch reads
     * before deciding whether to open new connections. The proof is a counter delta over the method
     * invocation itself, not a reading of the source: the fake counts every call on the driver, the
     * connection, its statements, result sets and metadata.
     */
    public void localIsHealthyPerformsZeroJdbcAndDriverCalls() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            RepositoryProfile profile = profile(DatabaseVendor.DB2, fake.url());
            JdbcSessionFactory factory = factory(profile, secrets(true));
            JdbcSession session = factory.create();

            int callsBefore = fake.driverMethodCalls();
            int connectsBefore = fake.connectRequests();
            boolean healthy = true;
            for (int index = 0; index < 1_000; index++) {
                healthy &= factory.isHealthy(session);
            }
            Assert.assertTrue(healthy, "the freshly created session reports healthy");

            Assert.assertEquals(0, fake.driverMethodCalls() - callsBefore,
                    "isHealthy must perform zero driver, Connection, Statement, ResultSet or metadata calls;"
                            + " it performed " + (fake.driverMethodCalls() - callsBefore));
            Assert.assertEquals(0, fake.connectRequests() - connectsBefore,
                    "and it must never request a connection");
            Assert.assertEquals(0, fake.isValidCalls(),
                    "Connection.isValid() is explicitly forbidden on the health path and must never be called");
            Assert.assertEquals(0, fake.metadataCalls(),
                    "and no metadata round trip may be performed there");

            // markUnusable is the only input the probe reads, and it must be visible immediately.
            session.markUnusable();
            int afterMark = fake.driverMethodCalls();
            Assert.assertFalse(factory.isHealthy(session),
                    "a session marked unusable must be reported unhealthy by the local flag alone");
            Assert.assertEquals(0, fake.driverMethodCalls() - afterMark,
                    "and even the unhealthy answer must cost zero driver calls");
            Assert.assertEquals(0, fake.isValidCalls(), "still no isValid() call");
            session.close();
        }
    }

    /** The pool object exists and holds its bounds without opening a single connection. */
    public void theLazyPoolOpensNoConnectionBeforeTheFirstBorrow() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            RepositoryProfile profile = profile(DatabaseVendor.DB2, fake.url());
            JdbcSessionFactory factory = factory(profile, secrets(true));
            BoundedPool<JdbcSession> pool = pool(factory, 4);

            Assert.assertEquals(4, pool.configuredSize(), "the configured bound is known immediately");
            Assert.assertEquals(0, fake.physicalOpens(),
                    "building the pool must not open a connection: making JDBC eager here is exactly the"
                            + " 'activation eagerly connects' behaviour Goal 03 section 3 forbids");
            Assert.assertEquals(0, fake.connectRequests(),
                    "and it must not even request one");
            Assert.assertEquals(0L, pool.metrics().createAttempts(),
                    "the pool has attempted no creation");
            Assert.assertEquals(CloseState.NOT_CLOSED, pool.closeState(),
                    "an open pool is not closing");

            try (Lease<JdbcSession> lease = pool.borrow()) {
                Assert.assertNotNull(lease.value(), "the first borrow creates lazily");
            }
            Assert.assertEquals(1, fake.physicalOpens(), "exactly one lazy connection");
            pool.close();
        }
    }

    // ------------------------------------------------------------------ the hard physical bound

    /**
     * The peak number of simultaneously live application-owned connections never exceeds the configured
     * size - measured, not asserted from the code.
     *
     * <p>The measurement point is the fake driver: it raises a live counter the instant a physical
     * {@code Connection} object is created and lowers it only when a {@code close()} returned normally, so
     * its peak is what a reviewer compares against {@code jdbc.pool.size}. The companion control below
     * proves the same measurement detects a real breach, so a peak that equals the bound is evidence rather
     * than a constant.
     */
    public void thePhysicalConnectionPeakNeverExceedsTheConfiguredSize() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            RepositoryProfile profile = profile(DatabaseVendor.DB2, fake.url());
            JdbcSessionFactory factory = factory(profile, secrets(true));
            fake.answersWith(FakeResultTable.longs("V", 1L));
            int size = 3;
            BoundedPool<JdbcSession> pool = pool(factory, size);

            int borrowers = 9;
            CountDownLatch ready = new CountDownLatch(size);
            CountDownLatch hold = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(borrowers);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            java.util.ArrayList<Thread> threads = new java.util.ArrayList<>(borrowers);
            for (int index = 0; index < borrowers; index++) {
                Thread worker = new Thread(() -> {
                    try {
                        Lease<JdbcSession> lease = pool.borrow();
                        try {
                            ready.countDown();
                            hold.await(20, TimeUnit.SECONDS);
                        } finally {
                            lease.close();
                        }
                    } catch (Throwable failure) {
                        failed.compareAndSet(null, failure);
                    } finally {
                        done.countDown();
                    }
                }, "jdbc-peak-" + index);
                worker.setDaemon(true);
                threads.add(worker);
            }
            for (Thread worker : threads) {
                worker.start();
            }

            // Wait until the pool's capacity is genuinely saturated, then observe the peak while every one
            // of those leases is still held. Releasing the gate is the only thing that lets a thread finish,
            // so no sleep is needed to sequence anything.
            Assert.assertTrue(ready.await(20, TimeUnit.SECONDS),
                    "the first " + size + " borrowers must obtain a lease, otherwise the peak is not"
                            + " saturated and the measurement would be vacuous");
            Assert.assertEquals(size, fake.physicalLive(),
                    "while the " + size + " leases are held, exactly the configured number of physical"
                            + " connections is live");
            Assert.assertTrue(fake.peakPhysicalLive() <= size,
                    "the physical peak must never exceed the configured size, but it reached "
                            + fake.peakPhysicalLive() + " with size " + size);
            Assert.assertTrue(factory.peakLiveConnections() <= size,
                    "and the factory's own peak counter must agree; it reported "
                            + factory.peakLiveConnections());

            hold.countDown();
            Assert.assertTrue(done.await(30, TimeUnit.SECONDS), "every borrower must finish");
            Assert.assertNull(failed.get(),
                    "no borrower may fail while waiting for capacity: " + failed.get());
            Assert.assertTrue(fake.peakPhysicalLive() <= size,
                    "the peak holds for the whole run, not only at the sample point: "
                            + fake.peakPhysicalLive());
            Assert.assertTrue(fake.physicalLive() <= size && fake.physicalLive() >= 1,
                    "the healthy returned leases are idle and still open, so the live count is between 1 and"
                            + " the configured size: " + fake.physicalLive());
            Assert.assertEquals(0, pool.metrics().quarantined(), "and nothing was quarantined");
            pool.close();
            Assert.assertEquals(0, fake.physicalLive(),
                    "every connection is released once the pool itself is closed");
        }
    }

    /**
     * The opposite control: the same peak measurement DOES detect a breach.
     *
     * <p>Without this, "the peak never exceeded the size" could be satisfied by a counter that is simply
     * never incremented. Here two connections are held through the pool and a third is opened directly,
     * outside it - which is exactly what an ad-hoc {@code DriverManager} call or an emergency connection
     * would look like - and the measurement must rise above the configured bound.
     */
    public void thePeakMeasurementDetectsADeliberateBreach() throws Exception {
        try (FakeJdbc fake = db2Driver()) {
            RepositoryProfile profile = profile(DatabaseVendor.DB2, fake.url());
            JdbcSessionFactory factory = factory(profile, secrets(true));
            fake.answersWith(FakeResultTable.longs("V", 1L));
            int size = 2;
            BoundedPool<JdbcSession> pool = pool(factory, size);

            Lease<JdbcSession> first = pool.borrow();
            Lease<JdbcSession> second = pool.borrow();
            Assert.assertEquals(size, fake.physicalLive(), "the pool holds exactly its configured bound");

            java.sql.Connection stray = DriverManager.getConnection(fake.url(), FakeJdbc.FAKE_USER,
                    FakeJdbc.FAKE_PASSWORD);
            try {
                Assert.assertEquals(size + 1, fake.physicalLive(),
                        "the stray connection is counted as live, so the measurement sees it");
                Assert.assertTrue(fake.peakPhysicalLive() > size,
                        "and the peak now EXCEEDS the configured bound, which is the breach this control"
                                + " exists to prove the measurement can report; peak="
                                + fake.peakPhysicalLive() + " size=" + size);
            } finally {
                stray.close();
            }
            first.close();
            second.close();
            Assert.assertEquals(size, fake.physicalLive(),
                    "the two returned leases were healthy, so both connections are idle and still open; a"
                            + " healthy return is deliberately not a close");
            pool.close();
            Assert.assertEquals(0, fake.physicalLive(),
                    "all connections are released once the pool itself is closed");
            Assert.assertTrue(fake.peakPhysicalLive() > size,
                    "and the breach the control produced is still visible in the peak: "
                            + fake.peakPhysicalLive());
        }
    }
}
