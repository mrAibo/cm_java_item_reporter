package com.mraibo.cminsight.db;

import java.time.LocalDate;

/**
 * One result row, readable only while a {@link JdbcSession#query} callback is running and the JDBC lease
 * is held.
 *
 * <p>This is the deliberately small window the analytics layer gets onto a query result: typed column
 * access by 1-based index, and nothing else. No {@code java.sql} type appears here, no cursor can escape
 * the callback, and no method returns a live driver object, so a consumer - including a web handler or a
 * DTO - can never hold a {@code ResultSet} open past the lease.
 *
 * <p>A row becomes invalid the moment its query callback returns (or throws). Reading an invalidated row
 * fails with {@link JdbcAccessException} rather than returning stale or undefined data, so using a row
 * after its lease is a loud error instead of a silent one.
 *
 * <p>{@link #isNull(int)} exists because the primitive getters cannot express SQL {@code NULL}: a JDBC
 * {@code getLong} on a NULL column returns {@code 0}, which is a legitimate count. The reference getters
 * ({@link #getString(int)}, {@link #getLocalDate(int)}) return {@code null} for SQL {@code NULL}.
 * A driver-level conversion failure is reported as {@link JdbcAccessException}, and the owning session is
 * retired conservatively by the query path.
 */
public interface JdbcRow {

    /** Number of columns in this row. */
    int columnCount() throws JdbcAccessException;

    /** True when the column is SQL {@code NULL}. */
    boolean isNull(int column) throws JdbcAccessException;

    /** The column as a {@code long}; a SQL {@code NULL} reads as {@code 0} - check {@link #isNull(int)}. */
    long getLong(int column) throws JdbcAccessException;

    /** The column as a string, or {@code null} for SQL {@code NULL}. */
    String getString(int column) throws JdbcAccessException;

    /** The column as a date, or {@code null} for SQL {@code NULL}. */
    LocalDate getLocalDate(int column) throws JdbcAccessException;
}
