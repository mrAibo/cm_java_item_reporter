package com.mraibo.cminsight.db;

/**
 * The single owner of the physical connections this layer has handed out, told how each one ended.
 *
 * <p>A connection stops being counted as possibly alive only when its {@code close()} returned normally.
 * A close that threw is an <em>unproven</em> outcome: the connection may still exist, and reporting it as
 * released would understate the physical figure a reviewer compares against the configured pool size.
 * That is why the notification carries the outcome instead of only being sent on success.
 *
 * <p>Package-private: this is an internal seam between {@link JdbcSession} and the factory that created
 * it, not an API. No consumer outside the database package can implement it, and therefore none can
 * influence the accounting.
 */
interface JdbcConnectionLedger {

    /**
     * Reports that one application-owned connection finished.
     *
     * @param closedCleanly true only when {@code Connection.close()} returned normally
     */
    void connectionClosed(boolean closedCleanly);
}
