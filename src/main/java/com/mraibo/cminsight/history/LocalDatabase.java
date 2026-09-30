package com.mraibo.cminsight.history;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * The one class in this application that speaks JDBC to the <strong>local</strong> history database.
 *
 * <h2>Why it is separate from the store</h2>
 *
 * <p>{@link H2HistoryStore} owns the schema, the statement text, retention and ordering; this class owns
 * nothing but the mechanics of talking to a local file-backed database, and every JDBC handle it holds is
 * private. The split matters for a reader: after it, there is exactly one file in the application where a
 * {@code Connection} exists, and it is a package-private class with no accessor that returns one, so no
 * caller - least of all the web tier - can ever receive a handle. The store's own surface is the typed
 * {@link HistoryStore} boundary and nothing else.
 *
 * <h2>One connection, one writer, no pool</h2>
 *
 * <p>The instance wraps exactly ONE physical connection, opened by the caller's factory and held until
 * {@link #close()}. Autocommit is switched off in the same call that establishes it, so the store decides
 * where a transaction ends: any statement a caller issues participates in the open transaction until
 * {@link #commit()}, and a failure rolls the whole thing back. Nothing here reopens, reconnects or retries -
 * a store that cannot write reports it and the caller keeps the previous state.
 *
 * <h2>Structurally safe binding</h2>
 *
 * <p>Every statement is built from a fixed text with bound parameters; no caller-supplied text ever reaches
 * the statement, and {@code setObject} is used so the JDK types the store binds (identities, timestamps,
 * counts, labels) are converted by the driver rather than by string concatenation. That is the same rule the
 * analytics half follows, restated for the one place this application writes.
 */
final class LocalDatabase {

    private final Connection connection;

    private LocalDatabase(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens the one connection this instance will ever hold and switches autocommit off.
     *
     * <p>The driver is asked directly rather than through {@code DriverManager}, so no process-wide
     * registration state can decide whether the store opens. A driver that answers with {@code null} has not
     * accepted the URL, which is a refusal rather than a usable connection.
     *
     * @throws SQLException when the local database cannot be opened
     */
    static LocalDatabase connect(Driver driver, String jdbcUrl, Properties properties) throws SQLException {
        Connection connection = driver.connect(jdbcUrl, properties);
        if (connection == null) {
            throw new SQLException("the local history driver did not accept the local history URL");
        }
        connection.setAutoCommit(false);
        return new LocalDatabase(connection);
    }

    /**
     * Runs one statement that changes rows, returning how many it changed.
     *
     * <p>Used for DDL and for the retention delete; the caller keeps it inside the open transaction.
     */
    int update(String sql, List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    /**
     * Inserts one row and returns the identity value the database generated for it, or {@code -1} when the
     * database reported none.
     *
     * <p>The store-owned public identity is a separate column: a context-local scan id is not an identity
     * after a restart, so the row's technical key is the database's own sequence and never a caller's value.
     */
    long insertReturningKey(String sql, List<Object> parameters) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, parameters);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    /**
     * Runs one query and returns its rows as raw column values in select order.
     *
     * <p>Raw on purpose: this class converts nothing, so there is exactly one place in the store where a
     * column value becomes a domain value. The values are what the driver maps (strings, boxed numbers,
     * booleans and the JDK time types the schema declares), and the store is responsible for refusing a
     * shape it does not understand rather than guessing.
     *
     * @param maxRows a positive bound on the rows the driver will hand back, or {@code 0} for no bound
     */
    List<Object[]> query(String sql, List<Object> parameters, int maxRows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            if (maxRows > 0) {
                statement.setMaxRows(maxRows);
            }
            try (ResultSet results = statement.executeQuery()) {
                int columns = results.getMetaData().getColumnCount();
                List<Object[]> rows = new ArrayList<>();
                while (results.next()) {
                    Object[] row = new Object[columns];
                    for (int column = 0; column < columns; column++) {
                        row[column] = results.getObject(column + 1);
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    /** Commits the one open transaction. After this call every reader sees the complete new state. */
    void commit() throws SQLException {
        connection.commit();
    }

    /**
     * Rolls the open transaction back, reporting nothing.
     *
     * <p>The caller already has a failure to report and a rollback problem must not replace it; the state on
     * disk is safe either way, because an uncommitted transaction is invisible to every reader and is
     * discarded by the database when the connection closes.
     */
    void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Deliberately swallowed: see the method contract.
        }
    }

    /** True when the connection is closed or can no longer answer. */
    boolean closed() {
        try {
            return connection.isClosed();
        } catch (SQLException failure) {
            return true;
        }
    }

    /** Releases the one connection. The caller closes this exactly once. */
    void close() throws SQLException {
        connection.close();
    }

    /** Binds every parameter, converting {@code null} to a typed SQL NULL rather than a missing argument. */
    private static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        Objects.requireNonNull(parameters, "parameters");
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            if (value == null) {
                statement.setNull(index + 1, Types.NULL);
            } else {
                statement.setObject(index + 1, value);
            }
        }
    }
}
