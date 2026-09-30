package com.mraibo.cminsight.db;

import com.mraibo.cminsight.config.DatabaseVendor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One borrowed analytics JDBC session: a thin, deliberately narrow wrapper around exactly one
 * {@link java.sql.Connection}.
 *
 * <h2>The connection never leaves the database package</h2>
 *
 * <p>The {@code Connection} is a private field and no public method of this class takes or returns a
 * {@code java.sql} type. That is not a convention anybody has to remember: there is no accessor to forget
 * to avoid, so a service, a DTO, a cache, a {@code StatisticsRepository} consumer or a web handler cannot
 * obtain a connection, a statement or a result set from here - the only way to use the connection is to
 * call the query methods below, which drain their results before returning.
 *
 * <h2>Read-only is structural, not a hint</h2>
 *
 * <p>{@code Connection.setReadOnly(true)} is attempted when the session is created, but the JDBC contract
 * defines it as a <em>hint</em> and many drivers ignore it, so it is defence in depth and explicitly not
 * the safety boundary. The boundary is this class: it only ever prepares a statement and executes a query
 * (never an update, never a batch, never a stored procedure, never a generic {@code execute}), and every
 * statement it prepares is admitted by {@link SqlAdmission} <em>before</em> the driver is touched. That rule
 * is not a prefix test on {@code SELECT}/{@code WITH}: it requires one single statement, no comment, no
 * string literal and no write or control keyword <em>anywhere</em> as a whole token, so a mutation nested
 * inside a read shape - a data-change table reference, a DML token inside a CTE, a second statement after a
 * separator, a token hidden behind a comment - cannot reach the driver. There is no
 * exception to that rule, not even for the scan's date read: every dialect expresses
 * {@link JdbcDialect#currentDateSql()} as its vendor's documented one-row <em>SELECT</em> form (DB2's
 * {@code SELECT CURRENT DATE FROM SYSIBM.SYSDUMMY1}, Oracle's {@code SELECT TRUNC(SYSDATE) FROM DUAL}), so
 * one refusal rule covers every statement this layer ever prepares. The committed
 * source guard in {@code tests/} enforces the same rule on the code, and the operator is expected to grant
 * the analytics account {@code SELECT}-only privileges as well - three independent layers, none of which
 * assumes the others.
 *
 * <h2>Failure is conservative</h2>
 *
 * <p>Any {@link SQLException} on the query path marks the session unusable <em>before</em> the lease is
 * returned, and {@link JdbcSessionFactory#isHealthy} then reports that to {@link
 * com.mraibo.cminsight.connection.BoundedPool}, which retires and closes the session. Losing one
 * connection is strictly safer than reusing transaction or driver state this layer cannot prove is
 * intact, and the goal explicitly allows retiring on every {@code SQLException}. A cancelled or timed-out
 * statement is treated the same way: cancellation is not evidence of reusability.
 *
 * <h2>Health is local</h2>
 *
 * <p>{@link #isUsable()} reads one volatile flag. It performs no JDBC call, no driver call and no I/O of
 * any kind, because the pool calls it while holding its internal lock. Nothing in this class or in
 * {@link JdbcSessionFactory} ever calls {@code Connection.isValid()}, issues a {@code SELECT 1}, reads
 * database metadata over the wire, or otherwise performs a probe on the health path.
 */
public final class JdbcSession implements AutoCloseable {

    /** The one physical connection this session owns. Never exposed, never copied, never logged. */
    private final Connection connection;

    private final int queryTimeoutSeconds;
    private final String vendorName;
    private final String label;

    /** Notified with the outcome of the physical close: only a normal return proves the connection gone. */
    private final JdbcConnectionLedger ledger;

    /** Guards the single {@code Connection.close()} call: a second close is a no-op, never a retry. */
    private final AtomicBoolean closeAttempted = new AtomicBoolean();

    /** The cheap local health state read by {@link JdbcSessionFactory#isHealthy}. Never a probe. */
    private volatile boolean usable = true;

    /**
     * The statement currently executing, so a scan cancellation can reach {@code Statement.cancel()} from
     * another thread. Set and cleared on the executing thread; read as a volatile reference.
     */
    private volatile Statement inFlight;

    JdbcSession(Connection connection,
                int queryTimeoutSeconds,
                DatabaseVendor vendor,
                String label,
                JdbcConnectionLedger ledger) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.vendorName = vendor == null ? "" : vendor.name();
        this.label = label == null || label.isBlank() ? "jdbc-session" : label;
        this.ledger = ledger == null ? closedCleanly -> { } : ledger;
    }

    /** Safe label for diagnostics and lease descriptions. Never a URL, a schema or a credential. */
    public String label() {
        return label;
    }

    /**
     * The cheap, local health state of this session.
     *
     * <p>Reads one volatile boolean: no JDBC call, no driver call, no lock, no I/O. This is what
     * {@link JdbcSessionFactory#isHealthy} returns, and the pool calls that while holding its lock.
     */
    public boolean isUsable() {
        return usable && !closeAttempted.get();
    }

    /**
     * Marks this session unusable, so the pool retires it at the next safe point.
     *
     * <p>Deliberately the only mechanism there is: {@link com.mraibo.cminsight.connection.Lease} cannot
     * invalidate a resource, so a failure has to be recorded on the resource and discovered by
     * {@code ResourceFactory.isHealthy} on the returning thread.
     */
    public void markUnusable() {
        usable = false;
    }

    /** True once {@code close()} has begun. Says nothing about whether the close succeeded. */
    public boolean isClosed() {
        return closeAttempted.get();
    }

    /**
     * Prepares and executes one parameterised {@code SELECT}, consuming every row through {@code handler}
     * while the lease is held.
     *
     * <p>The statement gets the configured query timeout on every execution, the parameters are bound in
     * the order {@link AggregateQuery} validated against its own marker count, and the result set is fully
     * drained and closed before this method returns - so no cursor outlives the lease and the pool always
     * gets the session back.
     *
     * @param query   the SQL text, its bound values and the fixed operation label used in diagnostics
     * @param handler consumes each row; valid only for the duration of this call
     * @throws JdbcAccessException on a driver failure, on a statement outside the admitted read-only
     *         language, or when the
     *         session is already closed or retired; the message never contains SQL text or driver text
     */
    public void query(AggregateQuery query, JdbcRowHandler handler) throws JdbcAccessException {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(handler, "handler");
        requireUsable();
        requireAdmittedStatement(query.sql());
        String operation = query.description();
        try {
            try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
                if (queryTimeoutSeconds > 0) {
                    statement.setQueryTimeout(queryTimeoutSeconds);
                }
                int index = 1;
                for (Object parameter : query.parameters()) {
                    statement.setObject(index++, parameter);
                }
                inFlight = statement;
                try (ResultSet rows = statement.executeQuery()) {
                    JdbcResultRow row = new JdbcResultRow(rows);
                    try {
                        while (rows.next()) {
                            handler.accept(row);
                        }
                    } finally {
                        row.invalidate();
                    }
                } finally {
                    inFlight = null;
                }
            }
        } catch (SQLException failure) {
            markUnusable();
            throw new JdbcAccessException(operation, JdbcSqlErrors.message(operation, failure),
                    failure.getSQLState(), failure.getErrorCode());
        } catch (JdbcAccessException failure) {
            // A row-access failure or a refused statement: retire conservatively. Nothing here proves the
            // driver state is reusable, and the goal explicitly blesses losing one connection over reusing
            // state that is unknown.
            markUnusable();
            throw failure;
        } catch (RuntimeException | Error failure) {
            markUnusable();
            throw failure;
        }
    }

    /**
     * Reads the database's current date through the selected dialect - the single calendar anchor of one
     * scan.
     *
     * <p>Runs through {@link #query}, so it inherits the query timeout, the read-only refusal and the drain
     * discipline. The statement must therefore be a {@code SELECT}/{@code WITH} query:
     * {@link JdbcDialect#currentDateSql()} implementations use the vendor's documented one-row SELECT form
     * (DB2's {@code SELECT CURRENT DATE FROM SYSIBM.SYSDUMMY1}, Oracle's {@code SELECT TRUNC(SYSDATE) FROM
     * DUAL}), which keeps exactly one refusal rule for every statement this layer prepares. No JVM-local
     * clock and no root-row timestamp is involved: the value returned is the database's own date, read once
     * per scan.
     *
     * <p>A valid-but-empty answer - no row, or a row whose column is SQL {@code NULL} - fails this call
     * but deliberately leaves the session usable: the statement executed successfully, so that is a result
     * anomaly rather than evidence of connection damage, and it is not one of the SQL failures the
     * conservative retirement rule is about.
     *
     * @throws JdbcAccessException when the query fails, returns no row or returns no date value, or when
     *         {@code dialect} belongs to a different database vendor than this session
     */
    public LocalDate queryCurrentDate(JdbcDialect dialect) throws JdbcAccessException {
        Objects.requireNonNull(dialect, "dialect");
        requireVendor(dialect);
        LocalDate[] value = new LocalDate[1];
        int[] rows = new int[1];
        query(new AggregateQuery(dialect.currentDateSql(), List.of(), "read database date"), row -> {
            rows[0]++;
            value[0] = row.getLocalDate(1);
        });
        if (rows[0] == 0) {
            throw new JdbcAccessException("read database date",
                    "the database current-date query returned no row");
        }
        if (value[0] == null) {
            throw new JdbcAccessException("read database date",
                    "the database current-date query returned no date value");
        }
        return value[0];
    }

    /**
     * Executes a complete existence/zero-row probe and discards whatever it returns.
     *
     * <p>Used to prove that every expected physical root segment exists and is readable: the probe SQL is
     * a whole statement supplied by the dialect, and a driver failure surfaces as
     * {@link JdbcAccessException} so the caller can fail that ItemType mapping instead of skipping a
     * missing segment.
     */
    public void probe(AggregateQuery probeQuery) throws JdbcAccessException {
        query(probeQuery, row -> { });
    }

    /**
     * The schema the connection currently resolves to, as reported by the driver.
     *
     * <p>Used only when no schema is configured. The value is returned unvalidated - the caller applies
     * the identifier rule and treats a {@code null}, blank or unsupported answer as statistics-unavailable
     * rather than guessing a default schema.
     *
     * <h2>The health verdict: exactly one benign failure</h2>
     *
     * <p>An unsupported metadata read is a statement about the driver's capabilities, not evidence that the
     * connection is damaged. That reasoning is correct for <em>one</em> case, and it is why this method has
     * a benign case at all: {@link SQLFeatureNotSupportedException}, or the documented
     * feature-not-supported SQLSTATE {@code 0A000}, means this driver cannot report a schema. The operation
     * is then refused as unavailable rather than answered, the session stays usable, and the connection
     * remains eligible for the next lease. {@code JdbcFeatureSupport} in this package is the single, named
     * classifier of that one case.
     *
     * <p>It does <strong>not</strong> apply to any other failure. Every other {@link SQLException} - and an
     * unexpected runtime driver failure - is read as damage to the connection, exactly like the query path:
     * {@link #markUnusable()} is called <em>before</em> the sanitized failure propagates, so the returning
     * lease retires the physical connection instead of handing it to the next query. Applying the
     * capability reading to a genuine failure is the defect this distinction repairs, so the two cases must
     * stay separate: do not "simplify" them back into one unconditional catch.
     *
     * <p>The failure never reproduces driver text: the message is built by
     * {@code JdbcSqlErrors.message(operation, failure)} from the fixed operation label
     * {@code "read current schema"}, and only the SQLSTATE and the vendor code - the two fields the goal
     * allows - are carried besides it.
     *
     * @throws JdbcAccessException when the driver cannot report the schema, or refuses or fails to report
     *         it, or when this session is already closed or retired
     */
    public String currentSchema() throws JdbcAccessException {
        requireUsable();
        try {
            return connection.getSchema();
        } catch (SQLException failure) {
            if (!JdbcFeatureSupport.isFeatureNotSupported(failure)) {
                // A live connection failed a real call: nothing here proves its driver state is reusable,
                // so retire the session before the sanitized failure propagates, exactly as every query
                // path does.
                markUnusable();
            }
            throw new JdbcAccessException("read current schema",
                    JdbcSqlErrors.message("read current schema", failure),
                    failure.getSQLState(), failure.getErrorCode());
        } catch (RuntimeException | Error failure) {
            // An unexpected driver failure is not a capability answer either: retire the session, then
            // propagate the failure unchanged - the same rule the query path applies.
            markUnusable();
            throw failure;
        }
    }

    /**
     * Best-effort cancellation of the statement currently executing on this session, callable from another
     * thread.
     *
     * <p>Also marks the session unusable: a cancelled statement leaves driver and transaction state that
     * this layer cannot prove reusable, so the lease return retires the session instead of handing it to
     * the next query. A scan that cancels therefore loses one connection and keeps its bound honest.
     */
    public void cancelInFlight() {
        markUnusable();
        Statement statement = inFlight;
        if (statement == null) {
            return;
        }
        try {
            statement.cancel();
        } catch (SQLException | RuntimeException ignored) {
            // Best effort by contract: the query itself reports the failure and the session is already
            // marked unusable, so there is nothing to add here.
        }
    }

    /**
     * Calls {@code Connection.close()} <strong>exactly once</strong>.
     *
     * <p>A second call is a no-op even when the first one failed: retrying could close a connection that
     * the driver already released, and "exactly once" is the contract the pool's accounting depends on. A
     * failure propagates instead of being tidied away - that is the intended signal to
     * {@link com.mraibo.cminsight.connection.BoundedPool}, which cannot prove the physical connection is
     * gone and therefore quarantines the capacity slot. No replacement is authorised while a close is
     * unproven, and there is no emergency or overflow connection anywhere in this layer.
     */
    @Override
    public void close() throws JdbcAccessException {
        if (!closeAttempted.compareAndSet(false, true)) {
            return;
        }
        usable = false;
        inFlight = null;
        try {
            connection.close();
        } catch (SQLException failure) {
            // The unproven outcome is reported before the exception propagates: the connection may still
            // exist, so it must keep consuming its share of the physical bound.
            ledger.connectionClosed(false);
            throw new JdbcAccessException("close connection",
                    JdbcSqlErrors.message("close connection", failure),
                    failure.getSQLState(), failure.getErrorCode());
        }
        ledger.connectionClosed(true);
    }

    @Override
    public String toString() {
        return "JdbcSession[" + label + ", closed=" + closeAttempted.get() + ", usable=" + isUsable() + "]";
    }

    // ---------------------------------------------------------------- internals

    private void requireUsable() throws JdbcAccessException {
        if (closeAttempted.get()) {
            throw new JdbcAccessException("use session", "the JDBC session is closed");
        }
        if (!usable) {
            throw new JdbcAccessException("use session",
                    "the JDBC session was retired after an earlier failure and must be replaced");
        }
    }

    /**
     * Refuses a statement that is not inside the read-only language this application generates.
     *
     * <p>A thin adapter over {@link SqlAdmission}, which is the single admission rule of this code base:
     * {@link #query} calls this before it prepares anything, so no other place may keep a second, weaker
     * reading of what a read query is. The rule is deliberately not a prefix test on
     * {@code SELECT}/{@code WITH} - that admitted any statement whose leading text happened to look like a
     * read, including a data-change table reference, a DML token inside a CTE, a second statement after a
     * separator and a token hidden behind a comment.
     *
     * <p>Defence in depth under the committed source guard: even if a write, a control statement or a
     * stored-procedure call reached this class, it would not reach the driver. The SQL text is never
     * reproduced in the failure message, and neither is anything derived from it.
     */
    private static void requireAdmittedStatement(String sql) throws JdbcAccessException {
        Optional<String> refused = SqlAdmission.refuse(sql);
        if (refused.isPresent()) {
            throw new JdbcAccessException("admit read-only statement",
                    "refused a statement outside the read-only SQL language this query surface generates"
                            + " (one SELECT or WITH statement, no comment, no literal, no write or control"
                            + " keyword): " + refused.get());
        }
    }

    private void requireVendor(JdbcDialect dialect) throws JdbcAccessException {
        String dialectId = dialect.id();
        if (vendorName.isEmpty() || dialectId == null || !dialectId.equalsIgnoreCase(vendorName)) {
            throw new JdbcAccessException("read database date",
                    "the selected SQL dialect does not belong to this session's database vendor; the"
                            + " statement was not executed");
        }
    }
}
