package com.mraibo.cminsight.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * The one implementation of {@link JdbcRow}: a view over a live {@link ResultSet}, valid only inside the
 * query callback that owns it.
 *
 * <p>Package-private on purpose. The interface is what the analytics and scan layers see, so the driver
 * type stays inside the database package and no consumer can call anything on it that this class does not
 * expose.
 */
final class JdbcResultRow implements JdbcRow {

    private final ResultSet resultSet;
    private boolean valid = true;

    JdbcResultRow(ResultSet resultSet) {
        this.resultSet = resultSet;
    }

    /** Called by {@link JdbcSession#query} when the callback has finished, normally or by throwing. */
    void invalidate() {
        valid = false;
    }

    @Override
    public int columnCount() throws JdbcAccessException {
        requireValid();
        try {
            return resultSet.getMetaData().getColumnCount();
        } catch (SQLException failure) {
            throw wrap(failure);
        }
    }

    @Override
    public boolean isNull(int column) throws JdbcAccessException {
        requireValid();
        try {
            return resultSet.getObject(column) == null;
        } catch (SQLException failure) {
            throw wrap(failure);
        }
    }

    @Override
    public long getLong(int column) throws JdbcAccessException {
        requireValid();
        try {
            return resultSet.getLong(column);
        } catch (SQLException failure) {
            throw wrap(failure);
        }
    }

    @Override
    public String getString(int column) throws JdbcAccessException {
        requireValid();
        try {
            return resultSet.getString(column);
        } catch (SQLException failure) {
            throw wrap(failure);
        }
    }

    @Override
    public LocalDate getLocalDate(int column) throws JdbcAccessException {
        requireValid();
        try {
            java.sql.Date value = resultSet.getDate(column);
            return value == null ? null : value.toLocalDate();
        } catch (SQLException failure) {
            throw wrap(failure);
        }
    }

    private void requireValid() throws JdbcAccessException {
        if (!valid) {
            throw new JdbcAccessException("read query result",
                    "the result row is readable only inside the query callback, while the JDBC lease is"
                            + " held; this row was used after its callback returned");
        }
    }

    private static JdbcAccessException wrap(SQLException failure) {
        return new JdbcAccessException("read query result",
                JdbcSqlErrors.message("read query result", failure),
                failure.getSQLState(), failure.getErrorCode());
    }
}
