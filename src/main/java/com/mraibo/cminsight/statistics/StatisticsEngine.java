package com.mraibo.cminsight.statistics;

import com.mraibo.cminsight.metadata.ItemTypeSummary;

import java.time.LocalDate;

/**
 * The database half of one scan, as the coordinator needs it: the anchor date once, then one aggregate per
 * ItemType.
 *
 * <h2>The seam, and why it is this narrow</h2>
 *
 * <p>Everything that knows about SQL, drivers, schemas and physical root tables lives behind this
 * interface (the DB2/Oracle dialect, the PhysicalSchemaResolver and the aggregate query builder). The
 * coordinator knows only what the goal makes it responsible for: freeze the ItemType list, read the
 * database date once, run at most {@code statistics.workers} queries at a time, time them out, isolate
 * their failures, and publish an immutable snapshot. That split is what lets the whole coordinator be
 * tested deterministically with a fake engine and no database, and it keeps scan policy from drifting into
 * the SQL layer.
 *
 * <p>{@link ScanWindows} is the lead-owned type that encodes the four calendar windows from the ONE anchor
 * date. This package has no second window implementation on purpose: an aggregate that re-derived its own
 * boundaries could disagree with the snapshot it belongs to.
 *
 * <h2>Obligations of an implementation</h2>
 *
 * <ol>
 *   <li><strong>{@link #databaseCurrentDate(ScanCancellation)} is called exactly ONCE per scan</strong>,
 *       before any aggregate call. It must be the DATABASE's current date, not the JVM's, and it must not
 *       cache a stale value across scans: each scan re-reads it so a long-running process does not report
 *       windows anchored on the day it started.</li>
 *   <li><strong>The anchor is inside the scan's cancellation domain</strong>, exactly like an aggregate:
 *       it receives the SAME {@link ScanCancellation} instance and must register the session's abort
 *       action ({@code JdbcSession::cancelInFlight}) through {@link ScanCancellation#register}, closing
 *       the registration when the read finishes. The coordinator signals that one signal when the overall
 *       scan deadline expires, when the scan is explicitly cancelled and when the context is closed, so
 *       one mechanism reaches the anchor and every ItemType query alike. There is no separate deadline
 *       parameter and no clock on this interface: the engine is told <em>that</em> it was cancelled, never
 *       asked to police a time bound itself. A read that ignores the abort action must still return
 *       exactly once; the engine must not retry it and must not substitute a JVM-local date.</li>
 *   <li><strong>{@link #aggregate} counts DISTINCT ItemIDs</strong>, deduplicated across versions and
 *       across every expected root segment - never {@code COUNT(*)} over one ICMUT root table. A missing
 *       middle segment must FAIL that ItemType (throw), not be skipped: a silently smaller union is a
 *       wrong number.</li>
 *   <li><strong>One JDBC lease per call</strong>, held for the whole query and returned before the method
 *       returns - including on failure, timeout and cancellation. The method runs on the caller's (worker)
 *       thread and must not migrate to another thread.</li>
 *   <li><strong>Apply the per-query timeout</strong> through {@code Statement.setQueryTimeout}; a timeout
 *       must surface as a throw (the coordinator records ERROR for that ItemType, and the session layer
 *       retires the poisoned connection) rather than as a zero count.</li>
 *   <li><strong>Honour cancellation</strong>: poll {@link ScanCancellation#isCancelled()} and register the
 *       session's abort action ({@code JdbcSession::cancelInFlight}) via
 *       {@link ScanCancellation#register}, closing the registration when the query finishes.</li>
 *   <li><strong>Failures carry no raw driver text</strong>: throw {@link StatisticsQueryException} with a
 *       fixed operation label plus SQLState/vendor code. The coordinator stores only
 *       {@link StatisticsQueryException#sanitizedReasonWithState()}, or an exception's class name for
 *       anything else, so a raw message can never reach a snapshot.</li>
 *   <li><strong>The windows are used as given.</strong> The engine binds {@link ScanWindows#parameters()}
 *       against the {@code SUBSTR(ItemID, 9, 6)} date key; it never derives a creation date from a root
 *       row's {@code CreateTS} and never invents a boundary. When
 *       {@link ScanWindows#fullyRepresentable()} is false, the four window metrics are UNAVAILABLE while a
 *       provable total stays AVAILABLE.</li>
 * </ol>
 *
 * <p>An implementation is NOT required to be thread-safe beyond this: the coordinator calls it from several
 * worker threads simultaneously (up to the configured worker count), so an implementation that shares
 * mutable state must synchronise it itself. A JDBC bridge that only borrows from the pool and copies the
 * resolved schema needs none - a pool borrow is atomic.
 */
public interface StatisticsEngine {

    /**
     * The database's current date, read once per scan and inside the scan's cancellation domain.
     *
     * <p>The abort action registered with {@code cancellation} is what lets the overall scan deadline,
     * an explicit cancellation and a context close reach the statement of this read: the coordinator
     * signals the one {@link ScanCancellation} of the scan, and this method's registration is run from
     * it exactly like an aggregate's. Running the statement on the caller's (supervisor) thread and
     * registering before the statement executes is what makes that reach possible; the registration must
     * be closed when the read finishes so the scan's action map stays bounded.
     *
     * @param cancellation the scan's cancellation signal; never {@code null}
     * @throws StatisticsQueryException when the database date query fails, or the dialect cannot express it
     */
    LocalDate databaseCurrentDate(ScanCancellation cancellation) throws Exception;

    /**
     * One ItemType's distinct-ItemID aggregate over the scan's anchored windows.
     *
     * @param itemType     the frozen metadata entry to measure; never {@code null}
     * @param windows      the scan's anchored windows, identical for every ItemType of the scan
     * @param cancellation the scan's cancellation signal
     * @throws StatisticsQueryException when the aggregate fails or an expected root segment is missing
     */
    ItemTypeAggregate aggregate(ItemTypeSummary itemType,
                                ScanWindows windows,
                                ScanCancellation cancellation) throws Exception;
}
