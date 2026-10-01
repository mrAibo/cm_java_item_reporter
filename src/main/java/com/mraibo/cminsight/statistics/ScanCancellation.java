package com.mraibo.cminsight.statistics;

import com.mraibo.cminsight.metadata.ItemTypeSummary;

/**
 * Scan-scoped cancellation signal handed to the query engine.
 *
 * <h2>Why the engine needs this object at all</h2>
 *
 * <p>A JDBC query blocked in the driver is NOT interrupted by {@link Thread#interrupt()} in the general
 * case, so an interrupt alone cannot make a cancelled scan stop promptly. The only portable lever is
 * {@link java.sql.Statement#cancel()}, and it belongs to whoever holds the statement - the database layer,
 * not the coordinator. So the coordinator hands each query a signal it can poll and a registration point
 * where the layer attaches its own abort action; when the scan is cancelled, the coordinator runs those
 * actions and interrupts the workers as well. Cancellation stays best-effort by design: the abort action is
 * a request, and the worker still finishes through the normal path (lease returned, statement closed).
 *
 * <p>The signal is scoped to ONE scan. A new scan gets a new object, so an abort action registered by a
 * previous scan can never cancel a query of the next one.
 *
 * <p>Registration returns a {@link Registration} which is closed when the query finishes. Closing it
 * deregisters the action, so a long scan cannot accumulate one dead action per query - the map is bounded
 * by the worker count, not by the number of items.
 */
public interface ScanCancellation {

    /** True once the scan this signal belongs to was cancelled, timed out or closed. */
    boolean isCancelled();

    /**
     * Registers a best-effort abort action for one in-flight query.
     *
     * <p>The action must be safe to run once from another thread, and must tolerate an already-finished
     * query. Close the returned registration when the query finishes; an action that is still registered
     * when the scan is cancelled is run exactly once.
     */
    Registration register(Runnable abortAction);

    /** Removes one previously registered action. Closing twice is a no-op. */
    interface Registration extends AutoCloseable {

        @Override
        void close();
    }
}
