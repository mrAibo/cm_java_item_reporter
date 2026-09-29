package com.mraibo.cminsight.test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A dependency-free fake JDBC driver: the measuring device for every Goal 03 JDBC suite.
 *
 * <h2>Why this exists instead of a real driver</h2>
 *
 * <p>The build and the normal test run must pass with <strong>zero</strong> DB2/Oracle JARs present, so a
 * proprietary driver is an optional runtime input and can never be a compile-time requirement. More
 * importantly, the facts Goal 03 has to prove are facts about <em>what the application asks the driver to
 * do</em>: how many physical connections it opens, whether it ever exceeds its configured hard bound,
 * whether the local health probe touches the driver at all, which SQL it sends, and whether a credential
 * that disappears is released as clean. A real database cannot answer those questions - it answers
 * questions about data. This fake answers them, and it answers them deterministically.
 *
 * <p>It is registered through the real {@link DriverManager}, so production code paths that call
 * {@code DriverManager.getConnection(...)}, consult {@code DriverManager.getDriver(url)}, or check driver
 * readiness see a genuinely registered driver with the exact URL family under test. Nothing in
 * {@code src/main/java} knows this class exists.
 *
 * <h2>What it can do</h2>
 *
 * <ul>
 *   <li>return a fake {@link Connection} (a {@link Proxy}, so no vendor type is compiled in);</li>
 *   <li>count how many times a physical connection was <em>requested</em> ({@link #connectRequests()})
 *       and how many were actually <em>opened</em> ({@link #physicalOpens()}), plus the live count and its
 *       running peak ({@link #peakPhysicalLive()}) - the hard-bound measurement;</li>
 *   <li>simulate {@code getConnection} throwing a {@link SQLException} before any Connection exists;</li>
 *   <li>simulate an open that succeeds and whose post-open setup then fails ({@code setReadOnly} throwing,
 *       or a schema read throwing);</li>
 *   <li>simulate a {@code close()} that throws, which leaves the physical connection counted as still
 *       alive - the honest model of an uncertain close;</li>
 *   <li>record {@code Statement.setQueryTimeout} and optionally fail it;</li>
 *   <li>throw from a query, either for every query or only for SQL containing a fragment;</li>
 *   <li>return controlled result rows for the aggregate query, keyed on the SQL text and on the bind
 *       parameters that were really set;</li>
 *   <li>count every driver and {@code java.sql} method invocation ({@link #driverMethodCalls()}), which is
 *       the only honest way to prove the local health probe performs ZERO driver calls;</li>
 *   <li>answer {@link DatabaseMetaData#getTables} from a configured set of tables, so a schema resolver
 *       that verifies table existence can be tested without a database;</li>
 *   <li>hold a connect, a query or a close on a latch, so a test can drive a specific interleaving
 *       explicitly. Nothing here sleeps to sequence two threads.</li>
 * </ul>
 *
 * <h2>Sentinel values that make the leakage tests bite</h2>
 *
 * <p>{@link #url()}, {@link #userName()} and the raw {@link SQLException} messages this fake throws all
 * carry distinctive markers. A test that asserts "no JDBC URL, user, raw SQL or raw exception text appears
 * in any response" can therefore search for those markers and fail when one escapes - the assertion does
 * not restate the implementation, it looks for a value only this fake can produce.
 *
 * <p>Every proxy method the fake does not model throws {@link UnsupportedOperationException} naming the
 * method. That is deliberate: silently returning {@code null} or {@code 0} from an unmodelled driver call
 * would hide the fact that the production path reached a driver operation this suite never considered.
 *
 * <h2>How a vendor-named driver reaches this fake</h2>
 *
 * <p>Local driver readiness resolves a driver by the vendor's real class name - {@code
 * com.ibm.db2.jcc.DB2Driver} for DB2, {@code oracle.jdbc.OracleDriver} (with the legacy {@code
 * oracle.jdbc.driver.OracleDriver} fallback) for Oracle - with {@code Class.forName}. A dynamically
 * registered proxy could never answer that, so the committed test tree carries three tiny {@link Driver}
 * classes with those exact names, each of which delegates to {@link #connectViaVendorDriver}. The active
 * {@link FakeJdbc} instances are held in a small registry, and a vendor driver with no active instance for
 * the URL returns {@code null}, which is exactly how {@link DriverManager} concludes "no suitable driver".
 */
public final class FakeJdbc implements AutoCloseable {

    /** Marker used as the database user, so a response that leaks it is detectable. */
    static final String FAKE_USER = "FAKEJDBCUSER";

    /** Marker used as the database password. */
    static final String FAKE_PASSWORD = "FAKEJDBCPASSWORD";

    /** Marker embedded in every raw failure message, so leaked exception text is detectable. */
    static final String RAW_FAILURE_MARKER = "RAW-VENDOR-FAILURE-TEXT-MUST-NOT-ESCAPE";

    /** An IBM-generated component-root table name, as it appears inside a statement. */
    private static final Pattern ROOT_TABLE_REFERENCE = Pattern.compile("ICMUT[0-9]{8}");

    /** One recorded statement execution. */
    static final class SqlCall {

        private final String sql;
        private final List<Object> parameters;
        private final int queryTimeoutSeconds;
        private final boolean prepared;

        SqlCall(String sql, List<Object> parameters, int queryTimeoutSeconds, boolean prepared) {
            this.sql = sql;
            this.parameters = List.copyOf(parameters);
            this.queryTimeoutSeconds = queryTimeoutSeconds;
            this.prepared = prepared;
        }

        String sql() {
            return sql;
        }

        List<Object> parameters() {
            return parameters;
        }

        int queryTimeoutSeconds() {
            return queryTimeoutSeconds;
        }

        boolean prepared() {
            return prepared;
        }

        /** The SQL with every run of whitespace collapsed and upper-cased, for stable fragment matching. */
        String normalized() {
            return sql == null ? "" : sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        }

        @Override
        public String toString() {
            return "SqlCall[prepared=" + prepared + ", timeout=" + queryTimeoutSeconds + ", params="
                    + parameters + ", sql=" + normalized() + "]";
        }
    }

    /** Supplies the rows one query answers with, or fails it. */
    @FunctionalInterface
    interface QueryResponder {
        FakeResultTable respond(SqlCall call) throws SQLException;
    }

    private static final class Rule {
        private final String fragment;
        private final QueryResponder responder;

        private Rule(String fragment, QueryResponder responder) {
            this.fragment = fragment == null ? null : fragment.replaceAll("\\s+", " ").trim()
                    .toUpperCase(Locale.ROOT);
            this.responder = responder;
        }

        private boolean matches(SqlCall call) {
            return fragment == null || call.normalized().contains(fragment);
        }
    }

    /**
     * Every live fake, so the vendor-named driver classes can find the control for a URL.
     *
     * <p>A plain registry rather than a {@link java.util.WeakHashMap}: a suite registers a handful of fakes
     * and closes them, and a weak map would let an active fake disappear while a pool still holds a
     * connection to it.
     */
    private static final List<FakeJdbc> ACTIVE = new CopyOnWriteArrayList<>();

    /** The driver class name the DB2 vendor readiness check loads. */
    public static final String DB2_DRIVER_CLASS = "com.ibm.db2.jcc.DB2Driver";

    /** The preferred Oracle driver class name. */
    public static final String ORACLE_DRIVER_CLASS = "oracle.jdbc.OracleDriver";

    /** The legacy Oracle driver class name, which readiness must still accept. */
    public static final String ORACLE_LEGACY_DRIVER_CLASS = "oracle.jdbc.driver.OracleDriver";

    /**
     * What a vendor-named driver class calls to reach the active fake for a URL.
     *
     * @return the connection, or {@code null} when no fake accepts that URL - the JDBC contract for "this
     *         driver does not serve it", which lets {@link DriverManager} produce its own no-suitable-driver
     *         failure for the missing-driver cases
     */
    public static Connection connectViaVendorDriver(String url, Properties info) throws SQLException {
        if (url == null) {
            return null;
        }
        FakeJdbc best = null;
        for (FakeJdbc candidate : ACTIVE) {
            if (candidate.driver.acceptsURL(url)
                    && (best == null || candidate.urlPrefix.length() > best.urlPrefix.length())) {
                best = candidate;
            }
        }
        if (best == null) {
            return null;
        }
        return best.driver.connect(url, info);
    }

    /** True when a vendor-named driver class declared by this suite is reachable on this class path. */
    public static boolean vendorDriverClassAvailable(String className) {
        try {
            Class.forName(className, false, FakeJdbc.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /**
     * Loads and initialises a vendor driver class, which is what registers it with {@link DriverManager}.
     *
     * <p>Needed because the production factory only loads the driver while checking readiness: a suite that
     * calls {@code create()} directly must load it the same way the production path would.
     */
    public static void loadVendorDriverClass(String className) {
        try {
            Class.forName(className, true, FakeJdbc.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            throw new AssertionError("the committed test-tree vendor driver " + className
                    + " is missing, so local driver readiness cannot be satisfied in this suite", absent);
        }
    }

    /**
     * A {@link ClassLoader} that finds nothing, for the "driver absent" cases.
     *
     * <p>The committed test tree deliberately carries real vendor-named driver classes so readiness can be
     * POSITIVE, which means "no driver installed" can no longer be arranged by picking a vendor: every
     * vendor's class is present. It is arranged with a loader that resolves no class at all, which is
     * exactly what readiness does when a driver JAR is missing from {@code lib/db2} or {@code lib/oracle}.
     */
    public static ClassLoader loaderThatFindsNoDriver() {
        return new ClassLoader(null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                throw new ClassNotFoundException(name);
            }
        };
    }

    // ------------------------------------------------------------------ driver identity

    private final String urlPrefix;
    private final String url;
    private final String user;
    private final String password;
    private final DriverImpl driver = new DriverImpl();

    private volatile boolean registered;

    // ------------------------------------------------------------------ physical-connection accounting

    private final AtomicInteger connectRequests = new AtomicInteger();
    private final AtomicInteger physicalOpens = new AtomicInteger();
    private final AtomicInteger closeAttempts = new AtomicInteger();
    private final AtomicInteger physicalCloses = new AtomicInteger();
    private final AtomicInteger physicalLive = new AtomicInteger();
    private final AtomicInteger peakPhysicalLive = new AtomicInteger();
    private final AtomicInteger connectionsEverCreated = new AtomicInteger();

    // ------------------------------------------------------------------ driver-call accounting

    private final AtomicInteger driverMethodCalls = new AtomicInteger();
    private final AtomicInteger isValidCalls = new AtomicInteger();
    private final AtomicInteger metadataCalls = new AtomicInteger();
    private final AtomicInteger commitCalls = new AtomicInteger();
    private final AtomicInteger rollbackCalls = new AtomicInteger();
    private final AtomicInteger executeUpdateCalls = new AtomicInteger();
    private final AtomicInteger batchCalls = new AtomicInteger();
    private final AtomicInteger cancelCalls = new AtomicInteger();
    private final AtomicInteger queryTimeoutSetCalls = new AtomicInteger();
    private final AtomicInteger createStatementCalls = new AtomicInteger();
    private final AtomicInteger prepareStatementCalls = new AtomicInteger();

    // ------------------------------------------------------------------ configured behaviour

    private volatile Supplier<SQLException> connectFailure;
    private volatile Supplier<SQLException> closeFailure;
    private volatile Supplier<SQLException> setUpFailure;
    private volatile Supplier<SQLException> schemaFailure;
    private volatile Supplier<SQLException> queryTimeoutFailure;
    private volatile boolean readOnlyUnsupported;
    private volatile String schema;
    private volatile boolean getSchemaReturnsNull;
    private volatile boolean metadataSupported = true;
    private volatile boolean prepareStatementUnsupported;
    private volatile boolean validateTableReferences = true;

    private final Set<String> existingTables =
            Collections.synchronizedSet(new TreeSet<>(String.CASE_INSENSITIVE_ORDER));

    private final List<Rule> responders = new CopyOnWriteArrayList<>();
    private final List<Rule> failures = new CopyOnWriteArrayList<>();
    private volatile QueryResponder defaultResponder = call -> {
        throw new SQLException(RAW_FAILURE_MARKER + ": FakeJdbc has no query responder installed, so it cannot"
                + " answer '" + call.normalized() + "'. Install one with answers(...) - a fake that guesses"
                + " rows would make the test meaningless.");
    };

    private final List<SqlCall> calls = Collections.synchronizedList(new ArrayList<>());

    // ------------------------------------------------------------------ gates (deterministic interleaving)

    private volatile CountDownLatch connectGate;
    private volatile CountDownLatch connectEntered;
    private volatile CountDownLatch queryGate;
    private volatile CountDownLatch queryEntered;
    private volatile CountDownLatch closeGate;
    private volatile CountDownLatch closeEntered;

    private FakeJdbc(String urlPrefix, String user, String password) {
        this.urlPrefix = Objects.requireNonNull(urlPrefix, "urlPrefix");
        this.url = urlPrefix + "//fake.example:50000/FAKEDB";
        this.user = user == null ? FAKE_USER : user;
        this.password = password == null ? FAKE_PASSWORD : password;
    }

    /**
     * Creates and registers a fake driver for a URL family, exactly as a real driver registers itself.
     *
     * <p>The caller must {@link #close()} it, which deregisters the driver so a later test that wants "no
     * driver found" is not answered by a leftover fake.
     */
    static FakeJdbc register(String urlPrefix) {
        return register(urlPrefix, FAKE_USER, FAKE_PASSWORD);
    }

    static FakeJdbc register(String urlPrefix, String user, String password) {
        FakeJdbc fake = new FakeJdbc(urlPrefix, user, password);
        if (!ACTIVE.contains(fake)) {
            ACTIVE.add(fake);
        }
        fake.install();
        return fake;
    }

    private void install() {
        try {
            DriverManager.registerDriver(driver);
            registered = true;
        } catch (SQLException failure) {
            throw new AssertionError("cannot register the fake JDBC driver for " + urlPrefix + ": " + failure,
                    failure);
        }
    }

    /** Registers the driver again after {@link #deregister()}, for a "driver absent, then restored" test. */
    void reinstall() {
        install();
    }

    /** Deregisters the driver while keeping the fake usable, so "driver absent" is testable. */
    void deregister() {
        if (registered) {
            try {
                DriverManager.deregisterDriver(driver);
            } catch (SQLException failure) {
                throw new AssertionError("cannot deregister the fake JDBC driver: " + failure, failure);
            }
            registered = false;
        }
    }

    /**
     * Ends this fake's life: the driver is deregistered AND no longer answers a vendor-named driver.
     *
     * <p>Both halves matter. Leaving the fake in {@link #ACTIVE} would let a later test that expects "no
     * suitable driver" be answered by this one, which is the same class of defect as a leaked global
     * registration.
     */
    @Override
    public void close() {
        deregister();
        ACTIVE.remove(this);
    }

    /** The URL the fake advertises, so a test can configure genuinely mismatched vendors. */
    String url() {
        return url;
    }

    String urlPrefix() {
        return urlPrefix;
    }

    String userName() {
        return user;
    }

    String password() {
        return password;
    }

    // ------------------------------------------------------------------ counters

    /** How many times {@code Driver.connect} was entered: a physical connection was REQUESTED. */
    int connectRequests() {
        return connectRequests.get();
    }

    /** How many physical Connection objects the driver actually created and handed out. */
    int physicalOpens() {
        return physicalOpens.get();
    }

    /** Physical connections the fake believes are still open (a close that threw does not decrement). */
    int physicalLive() {
        return physicalLive.get();
    }

    /** The highest number of simultaneously open physical connections ever observed. */
    int peakPhysicalLive() {
        return peakPhysicalLive.get();
    }

    int physicalCloses() {
        return physicalCloses.get();
    }

    int closeAttempts() {
        return closeAttempts.get();
    }

    int connectionsEverCreated() {
        return connectionsEverCreated.get();
    }

    /**
     * Every method invocation on the driver, the Connection, its Statements, ResultSets and metadata.
     *
     * <p>This is the counter the {@code isHealthy}-performs-zero-driver-calls test reads. It excludes
     * {@link Object}'s own methods, which the {@link Proxy} machinery and logging call rather than the
     * application's database path.
     */
    int driverMethodCalls() {
        return driverMethodCalls.get();
    }

    /** {@code Connection.isValid(...)} invocations: the Goal 03 section 6.1 explicitly forbidden probe. */
    int isValidCalls() {
        return isValidCalls.get();
    }

    int metadataCalls() {
        return metadataCalls.get();
    }

    /** {@code Connection.commit()} invocations - a write path that must never be reached by analytics. */
    int commitCalls() {
        return commitCalls.get();
    }

    int rollbackCalls() {
        return rollbackCalls.get();
    }

    /** {@code executeUpdate}/{@code executeLargeUpdate} invocations: the forbidden write path. */
    int executeUpdateCalls() {
        return executeUpdateCalls.get();
    }

    int batchCalls() {
        return batchCalls.get();
    }

    int cancelCalls() {
        return cancelCalls.get();
    }

    int queryTimeoutSetCalls() {
        return queryTimeoutSetCalls.get();
    }

    int createStatementCalls() {
        return createStatementCalls.get();
    }

    int prepareStatementCalls() {
        return prepareStatementCalls.get();
    }

    /** Every statement execution the fake saw, in order. */
    List<SqlCall> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    SqlCall lastCall() {
        synchronized (calls) {
            return calls.isEmpty() ? null : calls.get(calls.size() - 1);
        }
    }

    /** The SQL text of every execution, in order. */
    List<String> executedSql() {
        List<String> sql = new ArrayList<>();
        for (SqlCall call : calls()) {
            sql.add(call.sql());
        }
        return sql;
    }

    /** The recorded query timeouts, in execution order. */
    List<Integer> queryTimeouts() {
        List<Integer> timeouts = new ArrayList<>();
        for (SqlCall call : calls()) {
            timeouts.add(call.queryTimeoutSeconds());
        }
        return timeouts;
    }

    /** Every bind parameter the production path really set, flattened in execution order. */
    List<Object> boundParameters() {
        List<Object> parameters = new ArrayList<>();
        for (SqlCall call : calls()) {
            parameters.addAll(call.parameters());
        }
        return parameters;
    }

    // ------------------------------------------------------------------ configured behaviour

    /** Makes every subsequent {@code Driver.connect} throw the supplied failure. Pass {@code null} to clear. */
    FakeJdbc failConnectWith(Supplier<SQLException> failure) {
        this.connectFailure = failure;
        return this;
    }

    /** Convenience: a {@link SQLException} whose raw message carries {@link #RAW_FAILURE_MARKER}. */
    FakeJdbc failConnectWithMessage(String message) {
        return failConnectWith(() -> new SQLException(RAW_FAILURE_MARKER + ": " + message, "08001", 4499));
    }

    /** Makes every subsequent {@code Connection.close()} throw, leaving the connection counted as live. */
    FakeJdbc failCloseWith(Supplier<SQLException> failure) {
        this.closeFailure = failure;
        return this;
    }

    /** Makes every subsequent post-open setup call fail, which models "opened, then setup blew up". */
    FakeJdbc failSetUpWith(Supplier<SQLException> failure) {
        this.setUpFailure = failure;
        return this;
    }

    /** Makes {@code setReadOnly} report {@link SQLFeatureNotSupportedException}, the documented hint case. */
    FakeJdbc readOnlyUnsupported() {
        this.readOnlyUnsupported = true;
        return this;
    }

    FakeJdbc failSchemaReadWith(Supplier<SQLException> failure) {
        this.schemaFailure = failure;
        return this;
    }

    FakeJdbc failQueryTimeoutWith(Supplier<SQLException> failure) {
        this.queryTimeoutFailure = failure;
        return this;
    }

    /** Makes {@code prepareStatement} report {@link SQLFeatureNotSupportedException}. */
    FakeJdbc prepareStatementUnsupported() {
        this.prepareStatementUnsupported = true;
        return this;
    }

    /** The schema {@code Connection.getSchema()} answers with, or {@code null} for "the driver has none". */
    FakeJdbc schema(String value) {
        this.schema = value;
        this.getSchemaReturnsNull = false;
        return this;
    }

    /** {@code Connection.getSchema()} returns {@code null}, the blank/unsupported answer. */
    FakeJdbc schemaUnsupportedByDriver() {
        this.getSchemaReturnsNull = true;
        return this;
    }

    /** Declares the table names {@link DatabaseMetaData#getTables} will report. */
    FakeJdbc tables(String... names) {
        synchronized (existingTables) {
            existingTables.clear();
            for (String name : names) {
                existingTables.add(name);
            }
        }
        return this;
    }

    FakeJdbc addTables(String... names) {
        synchronized (existingTables) {
            for (String name : names) {
                existingTables.add(name);
            }
        }
        return this;
    }

    FakeJdbc withoutTables(String... names) {
        synchronized (existingTables) {
            for (String name : names) {
                existingTables.remove(name);
            }
        }
        return this;
    }

    Set<String> existingTables() {
        synchronized (existingTables) {
            return new LinkedHashSet<>(existingTables);
        }
    }

    FakeJdbc metadataUnsupported() {
        this.metadataSupported = false;
        return this;
    }

    /**
     * Stops refusing a query that names a table outside {@link #tables(String...)}.
     *
     * <p>By default the fake models the fact that a database cannot read a table that does not exist: any
     * query naming an {@code ICMUTnnnnnsss} table that was not declared fails with SQLSTATE {@code 42704}.
     * That default is what makes a schema resolver's "every expected segment must be proven present" rule
     * testable without a database, and what makes a missing MIDDLE segment fail rather than be skipped. It is
     * switched off only by a test that deliberately wants a query to succeed against an undeclared table.
     */
    FakeJdbc allowQueriesAgainstUndeclaredTables() {
        this.validateTableReferences = false;
        return this;
    }

    // ------------------------------------------------------------------ query answering

    /** Answers every query with this table, replacing the default responder. */
    FakeJdbc answersWith(FakeResultTable table) {
        this.defaultResponder = call -> table;
        return this;
    }

    /** Answers every query through this responder, replacing the default responder. */
    FakeJdbc answers(QueryResponder responder) {
        this.defaultResponder = Objects.requireNonNull(responder, "responder");
        return this;
    }

    /**
     * Answers SQL whose normalised text contains {@code fragment} with this table.
     *
     * <p>Fragments are matched on whitespace-collapsed, upper-cased SQL, so a test states
     * {@code "FROM ICMUT00001001"} and does not depend on the implementation's line breaks.
     */
    FakeJdbc answersSqlContaining(String fragment, FakeResultTable table) {
        responders.add(new Rule(fragment, call -> table));
        return this;
    }

    FakeJdbc answersSqlContaining(String fragment, QueryResponder responder) {
        responders.add(new Rule(fragment, responder));
        return this;
    }

    /** Fails every query with the supplied failure. */
    FakeJdbc failsQueriesWith(Supplier<SQLException> failure) {
        this.defaultResponder = call -> {
            throw failure.get();
        };
        return this;
    }

    /** Fails only queries whose normalised SQL contains {@code fragment}. */
    FakeJdbc failsQueriesContaining(String fragment, Supplier<SQLException> failure) {
        failures.add(new Rule(fragment, call -> {
            throw failure.get();
        }));
        return this;
    }

    /**
     * A raw vendor failure carrying {@link #RAW_FAILURE_MARKER}, for the sanitisation tests.
     *
     * <p>The marker is what makes those tests real: a response that leaks the driver's text contains a
     * string only this fake can produce, so the assertion cannot be satisfied by accident.
     */
    static SQLException rawFailure(String operation) {
        return new SQLException(RAW_FAILURE_MARKER + " " + operation + " at " + "jdbc:fake:" + FAKE_USER,
                "08S01", 4061);
    }

    // ------------------------------------------------------------------ gates

    /** Parks every subsequent connect after the physical connection exists. */
    FakeJdbc gateConnects(CountDownLatch entered, CountDownLatch gate) {
        this.connectEntered = entered;
        this.connectGate = gate;
        return this;
    }

    /** Parks every subsequent query inside the fake statement. */
    FakeJdbc gateQueries(CountDownLatch entered, CountDownLatch gate) {
        this.queryEntered = entered;
        this.queryGate = gate;
        return this;
    }

    /** Parks every subsequent physical close. */
    FakeJdbc gateCloses(CountDownLatch entered, CountDownLatch gate) {
        this.closeEntered = entered;
        this.closeGate = gate;
        return this;
    }

    // ------------------------------------------------------------------ proxy plumbing

    private void countCall(Method method) {
        if (method.getDeclaringClass() != Object.class) {
            driverMethodCalls.incrementAndGet();
        }
    }

    private static void awaitGate(CountDownLatch entered, CountDownLatch gate) {
        if (gate == null) {
            return;
        }
        if (entered != null) {
            entered.countDown();
        }
        try {
            gate.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Object[] args(Object[] supplied) {
        return supplied == null ? new Object[0] : supplied;
    }

    private static String stringArg(Object[] supplied, int index) {
        Object[] values = args(supplied);
        return index < values.length && values[index] instanceof String text ? text : null;
    }

    private static int intArg(Object[] supplied, int index, int fallback) {
        Object[] values = args(supplied);
        if (index < values.length && values[index] instanceof Integer value) {
            return value;
        }
        return fallback;
    }

    // ------------------------------------------------------------------ Connection

    private Connection newConnection() {
        InvocationHandler handler = (proxy, method, supplied) -> {
            countCall(method);
            String name = method.getName();
            Object[] values = args(supplied);
            switch (name) {
                case "close":
                    closeAttempts.incrementAndGet();
                    awaitGate(closeEntered, closeGate);
                    Supplier<SQLException> closeFailing = closeFailure;
                    if (closeFailing != null) {
                        // Deliberately NOT decrementing physicalLive: the exception proves nothing about the
                        // physical connection, which is exactly why BoundedPool must quarantine the slot.
                        throw closeFailing.get();
                    }
                    if (physicalLive.get() > 0) {
                        physicalLive.decrementAndGet();
                        physicalCloses.incrementAndGet();
                    }
                    return null;
                case "isClosed":
                    return physicalLive.get() <= 0;
                case "setReadOnly":
                    if (readOnlyUnsupported) {
                        throw new SQLFeatureNotSupportedException(
                                RAW_FAILURE_MARKER + ": setReadOnly is not supported by this driver");
                    }
                    Supplier<SQLException> setUp = setUpFailure;
                    if (setUp != null) {
                        throw setUp.get();
                    }
                    return null;
                case "isReadOnly":
                    Supplier<SQLException> setUpNow = setUpFailure;
                    if (setUpNow != null) {
                        throw setUpNow.get();
                    }
                    return Boolean.TRUE;
                case "getSchema":
                    Supplier<SQLException> schemaReading = schemaFailure;
                    if (schemaReading != null) {
                        throw schemaReading.get();
                    }
                    return getSchemaReturnsNull ? null : schema;
                case "setSchema":
                case "setCatalog":
                case "clearWarnings":
                case "setAutoCommit":
                case "setTransactionIsolation":
                case "setHoldability":
                case "setNetworkTimeout":
                case "beginRequest":
                case "endRequest":
                case "setClientInfo":
                    return null;
                case "getCatalog":
                    return null;
                case "getAutoCommit":
                    return Boolean.TRUE;
                case "getTransactionIsolation":
                    return Connection.TRANSACTION_READ_COMMITTED;
                case "getHoldability":
                    return java.sql.ResultSet.HOLD_CURSORS_OVER_COMMIT;
                case "getWarnings":
                    return null;
                case "getNetworkTimeout":
                    return 0;
                case "isValid":
                    // Counted separately as well: this is the probe Goal 03 section 6.1 forbids from
                    // ResourceFactory.isHealthy, and a test must be able to name the exact call.
                    isValidCalls.incrementAndGet();
                    return Boolean.TRUE;
                case "commit":
                    commitCalls.incrementAndGet();
                    return null;
                case "rollback":
                    rollbackCalls.incrementAndGet();
                    return null;
                case "abort":
                    physicalLive.decrementAndGet();
                    return null;
                case "getMetaData":
                    return newDatabaseMetaData();
                case "getTypeMap":
                    return new LinkedHashMap<String, Class<?>>();
                case "setTypeMap":
                    return null;
                case "getClientInfo":
                    return new Properties();
                case "createStatement":
                    createStatementCalls.incrementAndGet();
                    return newStatement(null, false);
                case "prepareStatement":
                case "prepareCall":
                    prepareStatementCalls.incrementAndGet();
                    if (prepareStatementUnsupported) {
                        throw new SQLFeatureNotSupportedException(RAW_FAILURE_MARKER
                                + ": prepareStatement is not supported by this fake driver");
                    }
                    return newStatement(stringArg(values, 0), true);
                case "nativeSQL":
                    return stringArg(values, 0);
                case "unwrap":
                    return proxy;
                case "isWrapperFor":
                    return Boolean.TRUE;
                case "toString":
                    return "FakeJdbcConnection[" + url + "]";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == values[0];
                default:
                    throw new UnsupportedOperationException("FakeJdbc does not implement Connection."
                            + name + "(" + describeTypes(method) + "); extend the fake rather than letting a"
                            + " production path call an unmodelled driver method");
            }
        };
        return (Connection) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                new Class<?>[] {Connection.class}, handler);
    }

    // ------------------------------------------------------------------ Statement / PreparedStatement

    private Object newStatement(String sql, boolean prepared) {
        List<Object> parameters = Collections.synchronizedList(new ArrayList<>());
        int[] queryTimeout = new int[1];
        InvocationHandler handler = (proxy, method, supplied) -> {
            countCall(method);
            String name = method.getName();
            Object[] values = args(supplied);
            switch (name) {
                case "setQueryTimeout":
                    queryTimeoutSetCalls.incrementAndGet();
                    Supplier<SQLException> timeoutFailing = queryTimeoutFailure;
                    if (timeoutFailing != null) {
                        throw timeoutFailing.get();
                    }
                    queryTimeout[0] = intArg(values, 0, 0);
                    return null;
                case "getQueryTimeout":
                    return queryTimeout[0];
                case "setFetchSize":
                case "setMaxRows":
                case "setMaxFieldSize":
                case "setPoolable":
                case "setCursorName":
                case "setEscapeProcessing":
                case "clearWarnings":
                    return null;
                case "getFetchSize":
                case "getMaxRows":
                    return 0;
                case "getWarnings":
                    return null;
                case "getConnection":
                    throw new UnsupportedOperationException("FakeJdbc does not expose its Connection through"
                            + " Statement.getConnection(): the analytics path must never obtain a raw"
                            + " java.sql.Connection from a statement");
                case "close":
                    return null;
                case "cancel":
                    cancelCalls.incrementAndGet();
                    return null;
                case "isClosed":
                    return Boolean.FALSE;
                case "setLong":
                case "setInt":
                case "setShort":
                case "setString":
                case "setObject":
                case "setNull":
                case "setBigDecimal":
                case "setDouble":
                case "setFloat":
                case "setBoolean":
                case "setBytes":
                case "setDate":
                case "setTimestamp":
                case "setTime":
                    parameters.add(values.length > 1 ? values[1] : null);
                    return null;
                case "clearParameters":
                    parameters.clear();
                    return null;
                case "executeQuery":
                    return executeQuery(prepared ? sql : stringArg(values, 0), parameters, queryTimeout[0],
                            prepared);
                case "execute":
                    return Boolean.TRUE;
                case "getResultSet":
                    return null;
                case "getUpdateCount":
                    return -1;
                case "executeUpdate":
                case "executeLargeUpdate":
                    executeUpdateCalls.incrementAndGet();
                    throw new SQLException(RAW_FAILURE_MARKER + ": the fake driver refuses executeUpdate;"
                            + " Goal 03 analytics is structurally SELECT-only");
                case "addBatch":
                case "executeBatch":
                case "executeLargeBatch":
                    batchCalls.incrementAndGet();
                    throw new SQLException(RAW_FAILURE_MARKER + ": the fake driver refuses a batch write;"
                            + " Goal 03 analytics is structurally SELECT-only");
                case "unwrap":
                    return proxy;
                case "isWrapperFor":
                    return Boolean.TRUE;
                case "toString":
                    return "FakeJdbcStatement[prepared=" + prepared + ", sql=" + sql + "]";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == values[0];
                default:
                    throw new UnsupportedOperationException("FakeJdbc does not implement Statement."
                            + name + "(" + describeTypes(method) + ")");
            }
        };
        Class<?> type = prepared ? PreparedStatement.class : Statement.class;
        return Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private java.sql.ResultSet executeQuery(String sql, List<Object> parameters, int queryTimeout,
                                            boolean prepared) throws SQLException {
        SqlCall call = new SqlCall(sql, copyOf(parameters), queryTimeout, prepared);
        calls.add(call);
        awaitGate(queryEntered, queryGate);
        SQLException undeclared = undeclaredTableFailure(call);
        if (undeclared != null) {
            throw undeclared;
        }
        for (Rule failureRule : failures) {
            if (failureRule.matches(call)) {
                failureRule.responder.respond(call);
                // A failure rule that returns instead of throwing would silently answer with rows, so the
                // absence of a throw is reported as a test configuration error rather than tolerated.
                throw new SQLException(RAW_FAILURE_MARKER + ": a failure rule was installed for '"
                        + failureRule.fragment + "' but did not throw");
            }
        }
        for (Rule rule : responders) {
            if (rule.matches(call)) {
                return newResultSet(rule.responder.respond(call));
            }
        }
        return newResultSet(defaultResponder.respond(call));
    }

    private static List<Object> copyOf(List<Object> source) {
        synchronized (source) {
            return new ArrayList<>(source);
        }
    }

    /**
     * The failure a database would raise for a query naming a table that does not exist.
     *
     * <p>Models only the generated component-root tables, which is the one table family the analytics layer
     * names: an {@code ICMUTnnnnnsss} reference that {@link #tables(String...)} did not declare cannot be
     * read, and the SQLSTATE a real DB2/Oracle reports for a missing table is what a caller sees. That is
     * exactly how "every expected root segment must be proven present, or the mapping fails" becomes a
     * deterministic test.
     */
    private SQLException undeclaredTableFailure(SqlCall call) {
        if (!validateTableReferences || call.sql() == null) {
            return null;
        }
        String upper = call.sql().toUpperCase(Locale.ROOT);
        java.util.Set<String> known = existingTables();
        java.util.regex.Matcher matcher = ROOT_TABLE_REFERENCE.matcher(upper);
        while (matcher.find()) {
            if (!known.contains(matcher.group())) {
                return new SQLException(RAW_FAILURE_MARKER + ": table " + matcher.group()
                        + " does not exist or is not accessible", "42704", -204);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ ResultSet

    private java.sql.ResultSet newResultSet(FakeResultTable table) {
        int[] cursor = {-1};
        InvocationHandler handler = (proxy, method, supplied) -> {
            countCall(method);
            String name = method.getName();
            Object[] values = args(supplied);
            switch (name) {
                case "next":
                    cursor[0]++;
                    return cursor[0] < table.rowCount();
                case "close":
                    return null;
                case "isClosed":
                    return Boolean.FALSE;
                case "wasNull":
                    return Boolean.FALSE;
                case "getMetaData":
                    return newResultSetMetaData(table);
                case "getRow":
                    return cursor[0] + 1;
                case "getStatement":
                    return null;
                case "isBeforeFirst":
                    return cursor[0] < 0;
                case "isAfterLast":
                    return cursor[0] >= table.rowCount();
                case "getLong":
                    return table.longAt(columnIndex(table, values), cursor[0]);
                case "getInt":
                    return (int) table.longAt(columnIndex(table, values), cursor[0]);
                case "getShort":
                    return (short) table.longAt(columnIndex(table, values), cursor[0]);
                case "getByte":
                    return (byte) table.longAt(columnIndex(table, values), cursor[0]);
                case "getObject":
                    return table.valueAt(columnIndex(table, values), cursor[0]);
                case "getDate":
                    return table.dateAt(columnIndex(table, values), cursor[0]);
                case "getTimestamp": {
                    java.sql.Date date = table.dateAt(columnIndex(table, values), cursor[0]);
                    return date == null ? null : new java.sql.Timestamp(date.getTime());
                }
                case "getString":
                    Object cell = table.valueAt(columnIndex(table, values), cursor[0]);
                    return cell == null ? null : cell.toString();
                case "getBoolean":
                    Object flag = table.valueAt(columnIndex(table, values), cursor[0]);
                    if (flag instanceof Boolean bool) {
                        return bool;
                    }
                    return flag != null;
                case "getDouble": {
                    Object number = table.valueAt(columnIndex(table, values), cursor[0]);
                    return number instanceof Number n ? n.doubleValue() : 0.0d;
                }
                case "getFloat": {
                    Object number = table.valueAt(columnIndex(table, values), cursor[0]);
                    return number instanceof Number n ? (float) n.doubleValue() : 0.0f;
                }
                case "findColumn":
                    return table.columnIndex(String.valueOf(values[0]));
                case "unwrap":
                    return proxy;
                case "isWrapperFor":
                    return Boolean.TRUE;
                case "toString":
                    return "FakeJdbcResultSet[" + table + "]";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == values[0];
                default:
                    throw new UnsupportedOperationException("FakeJdbc does not implement ResultSet."
                            + name + "(" + describeTypes(method) + ")");
            }
        };
        return (java.sql.ResultSet) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                new Class<?>[] {java.sql.ResultSet.class}, handler);
    }

    private static int columnIndex(FakeResultTable table, Object[] values) {
        Object selector = args(values)[0];
        if (selector instanceof Integer index) {
            return index;
        }
        return table.columnIndex(String.valueOf(selector));
    }

    private java.sql.ResultSetMetaData newResultSetMetaData(FakeResultTable table) {
        InvocationHandler handler = (proxy, method, supplied) -> {
            countCall(method);
            String name = method.getName();
            Object[] values = args(supplied);
            switch (name) {
                case "getColumnCount":
                    return table.columnCount();
                case "getColumnLabel":
                case "getColumnName":
                    return table.label(intArg(values, 0, 1));
                case "getColumnType":
                    return java.sql.Types.NUMERIC;
                case "getColumnTypeName":
                    return "DECIMAL";
                case "getColumnClassName":
                    return Long.class.getName();
                case "isSigned":
                    return Boolean.TRUE;
                case "getPrecision":
                case "getScale":
                case "getColumnDisplaySize":
                    return 0;
                case "isNullable":
                    return java.sql.ResultSetMetaData.columnNullable;
                case "getTableName":
                case "getSchemaName":
                case "getCatalogName":
                    return "";
                case "unwrap":
                    return proxy;
                case "isWrapperFor":
                    return Boolean.TRUE;
                case "toString":
                    return "FakeJdbcResultSetMetaData[" + table.columns() + "]";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == values[0];
                default:
                    throw new UnsupportedOperationException("FakeJdbc does not implement ResultSetMetaData."
                            + name);
            }
        };
        return (java.sql.ResultSetMetaData) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                new Class<?>[] {java.sql.ResultSetMetaData.class}, handler);
    }

    // ------------------------------------------------------------------ DatabaseMetaData

    private DatabaseMetaData newDatabaseMetaData() {
        InvocationHandler handler = (proxy, method, supplied) -> {
            countCall(method);
            metadataCalls.incrementAndGet();
            if (!metadataSupported) {
                throw new SQLFeatureNotSupportedException(RAW_FAILURE_MARKER + ": getMetaData is not"
                        + " supported by this fake driver");
            }
            String name = method.getName();
            Object[] values = args(supplied);
            switch (name) {
                case "getTables":
                    return newResultSet(tablesMatching(stringArg(values, 2)));
                case "getColumns":
                    return newResultSet(columnsMatching(stringArg(values, 2)));
                case "getDatabaseProductName":
                    return "FAKE-DB";
                case "getDatabaseProductVersion":
                    return "1.0-fake";
                case "getDriverName":
                    return "FakeJdbcDriver";
                case "getDriverVersion":
                    return "1.0";
                case "getURL":
                    return url;
                case "getUserName":
                    return user;
                case "getIdentifierQuoteString":
                    return " ";
                case "storesUpperCaseIdentifiers":
                    return Boolean.TRUE;
                case "supportsTransactions":
                    return Boolean.FALSE;
                case "getSchemaTerm":
                    return "schema";
                case "getCatalogTerm":
                    return "catalog";
                case "getSQLStateType":
                    return 1;
                case "getMaxTableNameLength":
                    return 128;
                case "getMaxColumnNameLength":
                    return 128;
                case "unwrap":
                    return proxy;
                case "isWrapperFor":
                    return Boolean.TRUE;
                case "toString":
                    return "FakeJdbcDatabaseMetaData[" + url + "]";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == values[0];
                default:
                    throw new UnsupportedOperationException("FakeJdbc does not implement DatabaseMetaData."
                            + name + "; the analytics path may only use getTables/getColumns and the identity"
                            + " getters the fake models");
            }
        };
        return (DatabaseMetaData) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class}, handler);
    }

    private FakeResultTable tablesMatching(String tablePattern) {
        List<Object[]> rows = new ArrayList<>();
        for (String table : matchingTables(tablePattern)) {
            rows.add(new Object[] {null, schema, table, "TABLE", null});
        }
        return FakeResultTable.of(
                List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS"), rows);
    }

    private FakeResultTable columnsMatching(String tablePattern) {
        List<Object[]> rows = new ArrayList<>();
        for (String table : matchingTables(tablePattern)) {
            rows.add(new Object[] {null, schema, table, "ITEMID", java.sql.Types.BIGINT, "BIGINT"});
        }
        return FakeResultTable.of(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE",
                "TYPE_NAME"), rows);
    }

    /** The configured tables whose names match a JDBC metadata pattern ({@code %} and {@code _}). */
    private List<String> matchingTables(String tablePattern) {
        List<String> matched = new ArrayList<>();
        for (String table : existingTables()) {
            if (tablePattern == null || tablePattern.isEmpty() || likeMatches(tablePattern, table)) {
                matched.add(table);
            }
        }
        return matched;
    }

    private static boolean likeMatches(String pattern, String value) {
        StringBuilder regex = new StringBuilder();
        for (char character : pattern.toCharArray()) {
            if (character == '%') {
                regex.append(".*");
            } else if (character == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(character)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE).matcher(value).matches();
    }

    private static String describeTypes(Method method) {
        StringBuilder text = new StringBuilder();
        Class<?>[] types = method.getParameterTypes();
        for (int index = 0; index < types.length; index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(types[index].getSimpleName());
        }
        return text.toString();
    }

    // ------------------------------------------------------------------ driver

    /**
     * The registered {@link Driver}.
     *
     * <p>A named class rather than a {@link Proxy}, because {@link DriverManager} keeps a strong reference
     * to it and a named class produces a readable failure message.
     */
    private final class DriverImpl implements Driver {

        @Override
        public Connection connect(String candidateUrl, Properties info) throws SQLException {
            driverMethodCalls.incrementAndGet();
            if (!acceptsURL(candidateUrl)) {
                // Returning null is the JDBC contract for "this driver does not accept that URL", and it is
                // what lets DriverManager produce its own "no suitable driver" failure for the
                // missing-driver test rather than a fake-specific message.
                return null;
            }
            connectRequests.incrementAndGet();
            Supplier<SQLException> failing = connectFailure;
            if (failing != null) {
                // Thrown BEFORE any Connection exists: the allocation boundary is not crossed, so this is
                // the "no application-owned Connection was created" case the pool must call clean.
                throw failing.get();
            }
            Connection connection = newConnection();
            physicalOpens.incrementAndGet();
            connectionsEverCreated.incrementAndGet();
            int live = physicalLive.incrementAndGet();
            peakPhysicalLive.accumulateAndGet(live, Math::max);
            // Park AFTER the physical connection exists, so a test can observe the live count while the
            // factory is still inside its lazy creation - the window a pool close must handle.
            awaitGate(connectEntered, connectGate);
            return connection;
        }

        @Override
        public boolean acceptsURL(String candidateUrl) {
            return candidateUrl != null && candidateUrl.startsWith(urlPrefix);
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String candidateUrl, Properties info) {
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
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger("FakeJdbcDriver");
        }

        @Override
        public String toString() {
            return "FakeJdbcDriver[" + urlPrefix + "]";
        }
    }

    @Override
    public String toString() {
        return "FakeJdbc[urlPrefix=" + urlPrefix + ", opens=" + physicalOpens.get() + ", live="
                + physicalLive.get() + ", peak=" + peakPhysicalLive.get() + ", driverCalls="
                + driverMethodCalls.get() + "]";
    }
}
