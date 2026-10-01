package com.mraibo.cminsight.db;

import com.mraibo.cminsight.config.ConfigException;
import com.mraibo.cminsight.config.DatabaseVendor;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CreationFailure;
import com.mraibo.cminsight.connection.ResourceFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The resource source of the analytics JDBC pool: creates and validates {@link JdbcSession} instances on
 * demand, and is the only class in this codebase that calls
 * {@link DriverManager#getConnection(String, String, String)}.
 *
 * <h2>The pool exists before any connection does</h2>
 *
 * <p>The constructor opens nothing and contacts nothing. {@link #lazyPool} builds the
 * {@link BoundedPool} object - the bounds are known, the counters are zero, no physical connection exists -
 * and the first {@code borrow()} is what creates a session. That is what keeps JDBC optional to IBM CM
 * repository activation (goal section 3): a missing driver, a missing credential, an unusable analytics
 * schema or an unreachable database all leave the repository selectable and its metadata and retention
 * views fully usable.
 *
 * <p><strong>Never call {@link BoundedPool#initialize()} on this pool.</strong> That method is eager by
 * design - it creates {@code size} resources immediately - which is exactly the "activation eagerly
 * connects to JDBC" behaviour this goal forbids. {@link #lazyPool} documents the same thing at the call
 * site, and the factory exposes no eager path of its own.
 *
 * <h2>THE CREATE VERDICT CONTRACT (goal section 6.2)</h2>
 *
 * <p>The allocation boundary is the successful <em>return</em> of a {@link Connection} from
 * {@code DriverManager.getConnection(...)}. Everything on one side of it has a different verdict, and the
 * banner inside {@link #create()} marks the crossing.
 *
 * <dl>
 *   <dt>Before the boundary</dt>
 *   <dd>No application-owned JDBC resource exists, because the call that would have created one either was
 *       never made or threw without returning a connection. Every failure here is therefore explicitly
 *       {@link CreationFailure.Cleanup#PROVEN_CLEAN}: an unsupported or wrong-vendor URL, a driver that is
 *       absent, a driver class that is present but registered so no {@code DriverManager} lookup can serve
 *       it, an invalid analytics configuration, and a missing, unconfigured or unreadable JDBC credential -
 *       including one that <em>disappears</em> between resolution and the connection attempt. The reserved
 *       pool slot is released, no capacity is quarantined, and a later borrow can succeed once the
 *       condition is fixed. A {@link SQLException} thrown by {@code getConnection} itself is reported the
 *       same way: under the JDBC abstraction no connection was returned, so there is nothing to leak. Its
 *       <em>sanitized</em> copy is preserved as the failure's cause; its raw message never is.</dd>
 *
 *   <dt>After the boundary</dt>
 *   <dd>A physical connection now exists and is application-owned. If session setup fails, that exact
 *       connection is closed and the verdict is read from the close, not guessed: close returned normally
 *       means nothing was left behind ({@code PROVEN_CLEAN}); close threw, or the outcome is otherwise
 *       unknown, means the connection may still exist and the slot is quarantined
 *       ({@link CreationFailure.Cleanup#UNPROVEN}). The conservative default of {@link BoundedPool} is
 *       never weakened, and no catch-all here relabels an unknown outcome as clean.</dd>
 * </dl>
 *
 * <h2>{@code isHealthy} is mandatory, and local</h2>
 *
 * <p>{@link #isHealthy(JdbcSession)} reads the session's own volatile flag and nothing else. The pool calls
 * it on the borrow path, the return path and during a rotation sweep - while holding its internal lock -
 * so it must never perform a probe: no {@code Connection.isValid()}, no {@code SELECT 1}, no metadata
 * round trip, no driver call of any kind. A session that failed a query has already marked itself
 * unusable, which is the only signal the pool needs.
 *
 * <h2>Credentials are re-resolved per attempt</h2>
 *
 * <p>{@link #create()} resolves the JDBC credential pair through
 * {@link RepositoryProfile#resolveJdbcCredentials(SecretResolver)} on <em>every</em> attempt, including
 * replacements for retired sessions, so a rotated, revoked or removed credential takes effect at the next
 * connection instead of pinning whatever existed at activation time. The pair is read only through
 * {@link SecretResolver#resolve(com.mraibo.cminsight.config.SecretRef)}, and no credential value is ever
 * logged, embedded in a message or attached to an exception.
 */
public final class JdbcSessionFactory implements ResourceFactory<JdbcSession> {

    /** The SQLSTATE JDBC uses for "feature not supported" when a driver does not use the dedicated type. */
    private static final String FEATURE_NOT_SUPPORTED = "0A000";

    private final RepositoryProfile profile;
    private final SecretResolver secrets;
    private final int queryTimeoutSeconds;
    private final DatabaseVendor vendor;

    /**
     * The cached local readiness verdict.
     *
     * <p>Deliberately lazy: constructing the factory must not even load a driver class, so readiness is
     * evaluated on the first question asked and then reused. Class visibility and driver registration are
     * properties of a running JVM's class path, which does not change under this factory.
     */
    private volatile JdbcDrivers.Readiness readiness;

    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final AtomicReference<JdbcSafeError> lastSafeError = new AtomicReference<>();

    private final AtomicLong createAttempts = new AtomicLong();
    private final AtomicLong preAllocationFailures = new AtomicLong();
    private final AtomicLong openedConnections = new AtomicLong();
    private final AtomicLong closedCleanly = new AtomicLong();
    private final AtomicLong closeFailures = new AtomicLong();
    private final AtomicInteger liveConnections = new AtomicInteger();
    private final AtomicInteger peakLiveConnections = new AtomicInteger();

    /**
     * @param profile             the repository whose vendor, URL and credential references drive every
     *                            connection attempt
     * @param secrets             the resolver the JDBC credential pair is read through, per attempt
     * @param queryTimeoutSeconds the timeout applied to every statement, as
     *                            {@code statistics.query.timeout.seconds} validated by the settings layer
     */
    public JdbcSessionFactory(RepositoryProfile profile, SecretResolver secrets, int queryTimeoutSeconds) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        if (queryTimeoutSeconds < 1) {
            throw new IllegalArgumentException("The analytics query timeout must be at least 1 second but was "
                    + queryTimeoutSeconds + "; the configured range is enforced by the settings layer and a"
                    + " zero or negative timeout would silently disable the per-query guard");
        }
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.vendor = profile.databaseVendor();
    }

    /**
     * The local driver/vendor readiness verdict.
     *
     * <p>Computed once, from class loading and {@code DriverManager} inspection only - no connection, no
     * credential read and no network. A loadable driver is explicitly NOT evidence of a reachable database.
     */
    public JdbcDrivers.Readiness readiness() {
        JdbcDrivers.Readiness cached = readiness;
        if (cached == null) {
            cached = JdbcDrivers.readiness(vendor, profile.jdbcUrl());
            readiness = cached;
        }
        return cached;
    }

    /**
     * Builds the hard-bounded pool this factory feeds - <strong>without creating a single connection</strong>.
     *
     * <p>The pool object, its name and its bounds exist immediately so an activation can own and later
     * close a real pool, while every physical connection is created lazily by the first borrow that needs
     * one. Call {@code borrow()} and let the pool create; do <strong>not</strong> call
     * {@link BoundedPool#initialize()}, which populates the pool eagerly and would make repository
     * activation depend on a working database.
     *
     * @param poolName       label used in pool diagnostics
     * @param size           hard maximum number of live connections
     * @param borrowTimeout  how long a borrow waits before failing with backpressure
     * @param maxAge         rotate a connection once it is older than this
     * @param maxOperations  rotate a connection after this many operations, or 0 for unlimited
     */
    public BoundedPool<JdbcSession> lazyPool(String poolName,
                                             int size,
                                             Duration borrowTimeout,
                                             Duration maxAge,
                                             long maxOperations) {
        return new BoundedPool<>(poolName, size, borrowTimeout, this, maxAge, maxOperations);
    }

    @Override
    public JdbcSession create() throws Exception {
        createAttempts.incrementAndGet();

        // ============================================================================================
        // BEFORE THE ALLOCATION BOUNDARY. No connection has been requested yet, so nothing is allocated
        // and every refusal below is explicitly PROVEN_CLEAN: the reserved pool slot is released.
        // ============================================================================================
        JdbcDrivers.Readiness current = readiness();
        if (!current.ready()) {
            // One verdict source for all three pre-allocation conditions: a URL outside the vendor family,
            // an absent driver, and a driver class that is present but unregistered. The reason is fixed
            // text that never contains the URL.
            preAllocationFailures.incrementAndGet();
            throw refuse("jdbc driver", current.reason(), "", 0, null);
        }

        final String user;
        final String password;
        try {
            RepositoryProfile.JdbcCredentials credentials = profile.resolveJdbcCredentials(secrets);
            user = secrets.resolve(credentials.jdbcUser());
            password = secrets.resolve(credentials.jdbcPassword());
        } catch (ConfigException failure) {
            // Resolved fresh on every attempt, so a credential that vanished is a known-clean
            // pre-allocation failure - the same regression Goal 02B fixed for the CM session factory. The
            // ConfigException text is deliberately not reproduced even though it names no value.
            preAllocationFailures.incrementAndGet();
            throw refuse("jdbc credential",
                    "the JDBC credential required for statistics could not be resolved (missing,"
                            + " unconfigured or unreadable); statistics are unavailable and the metadata"
                            + " path is unaffected", "", 0, null);
        }
        if (user == null || password == null) {
            preAllocationFailures.incrementAndGet();
            throw refuse("jdbc credential",
                    "the JDBC credential disappeared between resolution and the connection attempt; the"
                            + " reserved pool slot is released", "", 0, null);
        }

        // ============================================================================================
        // THE ALLOCATION BOUNDARY: DriverManager.getConnection returning a Connection. A throw here means
        // no connection was returned, so nothing application-owned exists and the verdict stays CLEAN.
        // ============================================================================================
        final Connection connection;
        try {
            connection = DriverManager.getConnection(profile.jdbcUrl(), user, password);
        } catch (SQLException failure) {
            preAllocationFailures.incrementAndGet();
            String text = JdbcSqlErrors.message("open connection", failure);
            throw refuse("open connection", text, failure.getSQLState(), failure.getErrorCode(),
                    JdbcSqlErrors.sanitized("open connection", failure));
        }
        if (connection == null) {
            // A driver is not allowed to return null here; if one does, no checked-in connection exists.
            preAllocationFailures.incrementAndGet();
            throw refuse("open connection",
                    "the driver reported success without returning a connection", "", 0, null);
        }

        // ============================================================================================
        // PAST THE BOUNDARY. Exactly one physical, application-owned connection exists now and must be
        // accounted for until its close is PROVEN. Setup failures close that exact connection and take
        // their verdict from the close, never from a guess.
        // ============================================================================================
        openedConnections.incrementAndGet();
        peakLiveConnections.accumulateAndGet(liveConnections.incrementAndGet(), Math::max);

        try {
            configure(connection);
        } catch (Exception setupFailure) {
            String detail = setupDetail(setupFailure);
            boolean released = closeAllocatedConnection(connection);
            String text = released
                    ? "the session could not be prepared after the driver returned a connection: " + detail
                            + "; that connection was closed, so it is proven released"
                    : "the session could not be prepared after the driver returned a connection: " + detail
                            + "; closing that connection did not return normally, so the physical"
                            + " connection may still exist";
            String prefix = "statistics JDBC resource could not be created [session setup]: ";
            CreationFailure verdict = released
                    ? new CreationFailure(CreationFailure.Cleanup.PROVEN_CLEAN, prefix + text)
                    : new CreationFailure(CreationFailure.Cleanup.UNPROVEN, prefix + text);
            lastSafeError.set(new JdbcSafeError("session setup", "", 0, verdict.getMessage()));
            throw verdict;
        } catch (Error fatal) {
            // An Error is not papered over: the exact connection is still closed, and the Error itself
            // propagates unchanged. BoundedPool quarantines the slot for any failure that is not an
            // explicit PROVEN_CLEAN verdict, which is the conservative reading this case wants.
            closeAllocatedConnection(connection);
            throw fatal;
        }

        return new JdbcSession(connection, queryTimeoutSeconds, vendor,
                "jdbc-session[" + vendor.name() + "]", this::connectionClosed);
    }

    /**
     * The mandatory, local health check.
     *
     * <p>Performs <strong>zero</strong> JDBC and driver calls: it reads the session's own volatile flag
     * and nothing else. {@link BoundedPool} invokes this while holding its internal lock - on the borrow
     * path, on the return path and during a rotation sweep - so any round trip here would stall every
     * concurrent borrow, every {@code metrics()} call and the shutdown state a repository switch reads
     * before it decides whether to open new connections. All real validation happens where it belongs: a
     * failed query marks the session unusable, and the pool retires it here.
     */
    @Override
    public boolean isHealthy(JdbcSession session) {
        return session != null && session.isUsable();
    }

    @Override
    public String describe() {
        return "jdbc-session-factory[" + vendor.name() + "]";
    }

    /** Counters and warnings of this factory's physical connections, safe to publish. */
    public long openedConnections() {
        return openedConnections.get();
    }

    /** Application-owned connections believed alive right now; a failed close deliberately keeps its count. */
    public int liveConnections() {
        return liveConnections.get();
    }

    /**
     * The highest number of application-owned connections ever alive at once.
     *
     * <p>This is the physical figure a reviewer compares against {@code jdbc.pool.size}: a physical peak
     * above the configured bound is a bound breach, and no counter here can hide one because a connection
     * only stops counting once its {@code close()} returned normally.
     */
    public int peakLiveConnections() {
        return peakLiveConnections.get();
    }

    /** Connections whose {@code close()} returned normally, i.e. proven physically released. */
    public long closedCleanly() {
        return closedCleanly.get();
    }

    /** Connections whose {@code close()} threw, i.e. physically unproven and quarantined by the pool. */
    public long closeFailures() {
        return closeFailures.get();
    }

    /** Attempts made, for diagnostics. */
    public long createAttempts() {
        return createAttempts.get();
    }

    /** Attempts refused before any connection was requested, all of them proven clean. */
    public long preAllocationFailures() {
        return preAllocationFailures.get();
    }

    /** Safe, value-free warnings, for example a driver that does not support the read-only hint. */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** The most recent sanitized connection-time failure, or empty when none occurred. */
    public Optional<JdbcSafeError> lastSafeError() {
        return Optional.ofNullable(lastSafeError.get());
    }

    // ---------------------------------------------------------------- internals

    /**
     * Attempts {@code setReadOnly(true)} as defence in depth.
     *
     * <p>The JDBC contract defines read-only as a <em>hint</em>, so this is not the read-only security
     * boundary - the structural SELECT-only surface is. A driver that explicitly reports the feature as
     * unsupported (the dedicated exception, or SQLSTATE {@code 0A000}) produces a safe warning and the
     * session is used anyway, because refusing to run a read-only query over a missing hint would trade a
     * real capability for a symbolic one. Any <em>other</em> setup failure propagates and follows the
     * cleanup verdict documented on {@link #create()}.
     */
    private void configure(Connection connection) throws SQLException {
        try {
            connection.setReadOnly(true);
        } catch (SQLFeatureNotSupportedException unsupported) {
            warnReadOnlyUnsupported();
        } catch (SQLException failure) {
            if (FEATURE_NOT_SUPPORTED.equalsIgnoreCase(failure.getSQLState())) {
                warnReadOnlyUnsupported();
                return;
            }
            throw failure;
        }
    }

    private void warnReadOnlyUnsupported() {
        String warning = "the " + vendor.name() + " JDBC driver does not support the read-only connection"
                + " hint; read-only access relies on the structural SELECT-only query surface and on"
                + " SELECT-only database privileges";
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    /**
     * Closes a connection that was already allocated, reporting whether the close is proven.
     *
     * @return true only when {@code close()} returned normally, which is the single piece of evidence that
     *         the physical connection is gone
     */
    private boolean closeAllocatedConnection(Connection connection) {
        try {
            connection.close();
        } catch (SQLException | RuntimeException failure) {
            closeFailures.incrementAndGet();
            return false;
        }
        closedCleanly.incrementAndGet();
        liveConnections.decrementAndGet();
        return true;
    }

    /** Callback from {@link JdbcSession#close()} reporting the outcome of the physical close. */
    private void connectionClosed(boolean closedCleanly) {
        if (closedCleanly) {
            this.closedCleanly.incrementAndGet();
            liveConnections.decrementAndGet();
        } else {
            closeFailures.incrementAndGet();
        }
    }

    private CreationFailure refuse(String operation,
                                   String detail,
                                   String sqlState,
                                   int vendorCode,
                                   SQLException sanitizedCause) {
        String text = "statistics JDBC resource could not be created [" + operation + "]: " + detail;
        lastSafeError.set(new JdbcSafeError(operation, sqlState, vendorCode, text));
        return sanitizedCause == null
                ? new CreationFailure(CreationFailure.Cleanup.PROVEN_CLEAN, text)
                : new CreationFailure(CreationFailure.Cleanup.PROVEN_CLEAN, text, sanitizedCause);
    }

    /**
     * A safe description of a setup failure: for an {@link SQLException} the sanitized operation/SQLSTATE/
     * vendor-code form; for anything else its class name, never its message, because a driver's own message
     * is not provably free of credentials.
     */
    private static String setupDetail(Throwable failure) {
        if (failure instanceof SQLException sqlFailure) {
            return JdbcSqlErrors.message("prepare session", sqlFailure);
        }
        String type = failure.getClass().getSimpleName();
        return "the session preparation failed with "
                + (type.isEmpty() ? "an unnamed driver failure" : type)
                + " (its message is not reproduced, because a driver message is not provably free of"
                + " credentials)";
    }

    /**
     * A sanitized connection-time failure, safe to store and to publish.
     *
     * @param operation  fixed label of the failed operation; never driver text
     * @param sqlState   the SQLSTATE the driver reported, or {@code null} when it reported none
     * @param vendorCode the driver error code, or {@code 0} when it reported none
     * @param text       the sanitized sentence, assembled from fixed parts only
     */
    public record JdbcSafeError(String operation, String sqlState, int vendorCode, String text) {

        public JdbcSafeError {
            operation = operation == null || operation.isBlank() ? "jdbc operation" : operation.trim();
            sqlState = sqlState == null ? "" : sqlState.trim();
            text = text == null ? "" : text.trim();
        }

        /** The sanitized text, for a diagnostics line. */
        public String describe() {
            return text;
        }
    }
}
