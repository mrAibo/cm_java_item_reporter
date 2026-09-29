package com.mraibo.cminsight.db;

/**
 * Consumes each row of one SELECT while the JDBC lease is held.
 *
 * <p>The callback runs inline on the calling thread, inside the query, between the driver's cursor and
 * the aggregation the caller accumulates into. That is deliberate: the row data is drained while the
 * lease is held, so a caller cannot accidentally keep a result set alive after the lease - and therefore
 * after the pool has taken the session back.
 *
 * <p>A callback may throw {@link JdbcAccessException} (a row-access failure or a rejected value). The
 * owning session retires itself conservatively in that case, exactly as it does for a driver-level SQL
 * failure, because nothing in that situation proves the driver state is reusable.
 */
@FunctionalInterface
public interface JdbcRowHandler {

    /** Consumes one row. */
    void accept(JdbcRow row) throws JdbcAccessException;
}
