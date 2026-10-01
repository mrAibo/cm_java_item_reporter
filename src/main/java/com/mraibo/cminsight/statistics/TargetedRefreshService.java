package com.mraibo.cminsight.statistics;

import com.mraibo.cminsight.core.CloseState;
import com.mraibo.cminsight.core.CloseStateAware;
import com.mraibo.cminsight.core.StatisticsSettings;
import com.mraibo.cminsight.metadata.ItemTypeSummary;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The targeted single-ItemType refresh: one ItemType measured under the SAME exclusive analytics-operation
 * gate a full scan uses, published as separate detail data.
 *
 * <h2>What one targeted refresh does, in order</h2>
 *
 * <ol>
 *   <li><strong>Admission first.</strong> {@link AnalyticsOperationGate#tryAcquire} runs before anything is
 *       read or changed - before the metadata list, before the in-flight registration, before any database
 *       call. A second caller is refused immediately with {@link TargetedRefreshResult.Outcome#REFUSED},
 *       never queued and with zero side effects. Admission is the shared gate and nothing else; this class
 *       owns no latch of its own.</li>
 *   <li><strong>The ItemType identity, from the ACTIVE repository metadata.</strong> The configured
 *       supplier is the context's metadata cache view, so the id is resolved against the same list the read
 *       API serves and no second metadata path exists. An id that list does not contain is
 *       {@link TargetedRefreshResult.Outcome#NOT_FOUND} and no database call happens.</li>
 *   <li><strong>Exactly ONE database anchor.</strong> {@link StatisticsEngine#databaseCurrentDate} is called
 *       once for this operation and the resulting {@link ScanWindows} is used by the one aggregate that
 *       follows. It is never re-read as a retry or a fallback and no JVM-local date is substituted.</li>
 *   <li><strong>One aggregate on the accepted path.</strong> {@link StatisticsEngine#aggregate} is the same
 *       call a scan worker makes, so logical item = one DISTINCT ItemID, deduplicated across versions and
 *       across every expected root segment 1..N, and a missing middle segment fails the measurement instead
 *       of counting a subset. The physical-schema resolver, the query builder, the per-statement query
 *       timeout and the JDBC lease all come from that one implementation: this class builds no SQL and
 *       borrows no connection of its own, so the peak JDBC capacity of a targeted refresh is the peak of
 *       the two sequential engine calls - one lease at a time, from the context's one pool.</li>
 *   <li><strong>One immutable publication, or none.</strong> A measured result becomes a
 *       {@link TargetedItemTypeDetail} keyed by the ItemType id and is stored in this context's
 *       {@link TargetedDetailCache}; a failure, a cancellation or a context close publishes nothing.</li>
 * </ol>
 *
 * <h2>The cardinal rule: a targeted refresh is detail data, never a snapshot change</h2>
 *
 * <p>This class holds no {@link java.util.concurrent.atomic.AtomicReference} of a published snapshot, no
 * totals, no coverage, no coordinator and no history store. Its only writes are an entry in its own
 * {@link TargetedDetailCache} and its own in-flight marker. So it cannot mutate, replace, recalculate or
 * even reach the published full {@link StatisticsSnapshot} or the dashboard totals, and it can never be
 * persisted as a full history snapshot: the history hook is the coordinator's single publication point,
 * which this code path never enters because it never builds a snapshot.
 *
 * <p>That is a structural property, not a convention, and it is what makes the acceptance criterion
 * "the full snapshot and its totals are untouched" provable by reference comparison around a targeted
 * refresh rather than by inspecting the database.
 *
 * <h2>Cancellation, timeouts and sanitized failures: the same rules as a full scan</h2>
 *
 * <p>Each attempt owns one {@link ScanCancellation} signal and hands it to both engine calls, so the
 * engine's registered abort action ({@code JdbcSession::cancelInFlight}, i.e. {@code Statement.cancel()})
 * is reachable from {@link #cancelInFlight()} and from {@link #close()} exactly as it is from a scan's
 * cancel path. The per-statement query timeout is applied by the same session layer that applies it to a
 * scan; this class adds no second timeout. A failure reason is
 * {@link ScanCoordinator#reasonOf(Throwable)} - the very function a scan uses - so a driver message, SQL
 * fragment, URL or credential cannot reach a published detail through this path either.
 *
 * <p>One deliberate difference from a scan, and it is about whose thread this is: a targeted refresh runs
 * on the CALLER's thread (an HTTP request thread, in production), so cancellation does not interrupt that
 * thread. The signal plus the engine's registered abort action is the mechanism that reaches the driver,
 * which is the same mechanism a scan relies on for its queries; interrupting a request thread would leave
 * the web layer's own I/O with a pending interrupt, which is a defect, not a stronger cancel.
 *
 * <h2>Ownership and lifecycle</h2>
 *
 * <p>One instance belongs to one {@code RepositoryContext} and is registered as one of its owned resources,
 * after the coordinator, so {@link #close()} runs BEFORE the coordinator's and the pool's: a repository
 * switch cancels and drains the targeted operation and discards this context's detail cache first, and the
 * gate is opened only by a holder that still owns it. Every release presents the attempt's own owner token,
 * so a late release from a replaced context is refused by the gate rather than opening it for whoever holds
 * it now.
 *
 * <p>Admission is the shared gate; {@link #isRefreshInFlight()} and {@link #closeState()} read a separate
 * in-flight MARKER that never admits or refuses anything. It exists for two honest questions - "is an
 * attempt still running" and "did shutdown really finish" - and is documented where it is declared.
 */
public final class TargetedRefreshService implements AutoCloseable, CloseStateAware {

    /** Prefix of every owner token this class presents to the shared gate, so a token names its operation. */
    public static final String OWNER_TOKEN_PREFIX = "targeted-refresh:";

    /**
     * Extra grace on top of the per-statement query timeout when {@link #close()} drains the in-flight
     * attempt.
     *
     * <p>The query timeout is the only bound the operation itself has, so it is the right scale for the
     * drain: a driver that honoured {@code Statement.cancel()} returns sooner, and one that ignored both
     * the abort action and the timeout is not something a longer wait would fix. When the bound expires the
     * attempt stays visibly in flight and {@link #closeState()} reports
     * {@link CloseState#CLOSING} - pending, which refuses the switch as recoverable - instead of the service
     * claiming a clean shutdown it cannot prove.
     */
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(5);

    private static final String CLOSED_REASON = "the repository context is closed";
    private static final String DISABLED_REASON =
            StatisticsSettings.ENABLED_KEY + "=false, so no analytics operation can run";
    private static final String NO_METADATA_LIST_REASON =
            "the active repository metadata returned no ItemType list";
    private static final String NO_ANCHOR_REASON =
            "the database current-date query returned no date";
    private static final String NO_AGGREGATE_REASON = "the aggregate engine returned no result";

    /**
     * The context's ONE exclusive analytics arbiter, shared with the coordinator.
     *
     * <p>Required, never null, and never substituted: a private fallback gate would look shared while being
     * a second latch that can disagree with the first, which is exactly what the goal forbids.
     */
    private final AnalyticsOperationGate gate;

    private final StatisticsSettings settings;
    private final String repositoryId;
    private final Supplier<List<ItemTypeSummary>> itemTypeSource;
    private final StatisticsEngine engine;
    private final FreshnessThreshold freshnessThreshold;
    private final TargetedDetailCache cache = new TargetedDetailCache();

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * The in-flight MARKER: the cancellation signal of the attempt currently running, or {@code null}.
     *
     * <p>This is deliberately not a gate and grants nothing: it never refuses a caller, never decides
     * admission and is never consulted to start work. Admission is {@link #gate}. What it does is let
     * {@link #cancelInFlight()} reach the running attempt's signal, let {@link #close()} drain and let
     * {@link #closeState()} answer whether shutdown physically finished. Because admission is exclusive,
     * at most one attempt can ever be registered here at a time.
     */
    private final AtomicReference<OperationCancellation> inFlight = new AtomicReference<>();

    /** Pairs the in-flight marker with {@link #awaitQuiescence}; see {@link #signalDrained()}. */
    private final ReentrantLock drainLock = new ReentrantLock();
    private final Condition drained = drainLock.newCondition();

    /**
     * Guards the publish-versus-discard decision of the detail cache.
     *
     * <p>Without it, a publish could pass its own {@code closed} check just before {@link #close()} discards
     * the cache and store its entry after the discard returned, leaving a closed context with live detail
     * data. Both operations take this lock, so after {@code close()} returned the cache is empty and stays
     * empty: every later publish sees the closed flag under the same lock.
     */
    private final Object publishLock = new Object();

    /**
     * @param gate              the context's ONE shared arbiter, normally the same instance the coordinator
     *                          was given; required
     * @param settings          the validated analytics bounds; {@code feature.statistics} is re-checked here
     * @param repositoryId      the repository this capability belongs to
     * @param itemTypeSource    the ACTIVE repository metadata supplier, called once per attempt; normally
     *                          the context's metadata cache view
     * @param engine            the accepted database half; the same implementation a scan uses
     * @param freshnessThreshold the configured freshness threshold; the published detail carries the
     *                          judgement it produces
     */
    public TargetedRefreshService(AnalyticsOperationGate gate,
                                  StatisticsSettings settings,
                                  String repositoryId,
                                  Supplier<List<ItemTypeSummary>> itemTypeSource,
                                  StatisticsEngine engine,
                                  FreshnessThreshold freshnessThreshold) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.repositoryId = repositoryId == null ? "" : repositoryId.trim();
        this.itemTypeSource = Objects.requireNonNull(itemTypeSource, "itemTypeSource");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.freshnessThreshold = Objects.requireNonNull(freshnessThreshold, "freshnessThreshold");
    }

    // ------------------------------------------------------------------ the refresh

    /**
     * Measures ONE ItemType under the shared gate and publishes its detail result.
     *
     * <p>Synchronous by design: this call returns when the measurement is published or refused, so a
     * caller - the refresh route - needs no polling route and no second source of truth for "did it
     * finish". It is bounded by the same per-statement query timeout a scan query is bounded by.
     *
     * @param itemTypeId the ItemType identity, resolved against the active repository metadata
     * @return the outcome; {@link TargetedRefreshResult#published()} is true only for a real measurement
     */
    public TargetedRefreshResult refresh(int itemTypeId) {
        // The token is built from no shared state at all - not even a counter - so nothing is read or
        // changed before admission. A fresh token per attempt is what makes a late release from a replaced
        // context unable to open the gate for whoever holds it now.
        String ownerToken = OWNER_TOKEN_PREFIX + repositoryId + ":" + itemTypeId + ":"
                + UUID.randomUUID();

        // THE admission decision. Non-blocking: the second caller is refused, never queued.
        Optional<AnalyticsOperationGate.Operation> holder =
                gate.tryAcquire(AnalyticsOperationGate.Operation.TARGETED_REFRESH, ownerToken);
        if (holder.isPresent()) {
            return TargetedRefreshResult.refused(itemTypeId, holder.get());
        }
        try {
            // From here this attempt is the gate's owner, and the finally below is its ONLY release point:
            // one try, one finally, one release call, on every path - normal, failed, cancelled, closed or
            // an Error thrown by the engine - and the token makes it impossible for any other caller to
            // release. Because the attempt runs on this thread and the engine returns its JDBC lease before
            // it returns, the release also happens after the physical work stopped, which is the property
            // the coordinator's reaper provides for scan generations.
            return attempt(itemTypeId);
        } finally {
            gate.release(ownerToken);
        }
    }

    /**
     * One attempt, with the gate already held: every read and every state change of this class lives here.
     */
    private TargetedRefreshResult attempt(int itemTypeId) {
        if (closed.get()) {
            return TargetedRefreshResult.closed(itemTypeId, CLOSED_REASON);
        }
        if (!settings.enabled()) {
            return TargetedRefreshResult.unavailable(itemTypeId, DISABLED_REASON);
        }
        if (itemTypeId <= 0) {
            return TargetedRefreshResult.notFound(itemTypeId);
        }

        final List<ItemTypeSummary> candidates;
        try {
            candidates = itemTypeSource.get();
        } catch (Throwable failure) {
            return TargetedRefreshResult.failed(itemTypeId, ScanCoordinator.reasonOf(failure));
        }
        if (candidates == null) {
            return TargetedRefreshResult.failed(itemTypeId, NO_METADATA_LIST_REASON);
        }
        ItemTypeSummary itemType = null;
        for (ItemTypeSummary candidate : candidates) {
            if (candidate != null && candidate.itemTypeId() == itemTypeId) {
                itemType = candidate;
                break;
            }
        }
        if (itemType == null) {
            return TargetedRefreshResult.notFound(itemTypeId);
        }
        if (closed.get()) {
            return TargetedRefreshResult.closed(itemTypeId, CLOSED_REASON);
        }

        // ONE cancellation domain for this attempt, handed to both engine calls so the abort action the
        // engine registers reaches Statement.cancel() exactly as it does for a scan query. The marker is
        // installed before the first database call, so cancelInFlight() and close() can reach the signal.
        OperationCancellation cancellation = new OperationCancellation();
        inFlight.set(cancellation);
        try {
            if (closed.get()) {
                // A close that began between the check above and this registration must still reach work
                // that has not started yet; the signal is cancelled before the first database call, and
                // registering after a cancel runs the abort action immediately (the engine's contract).
                cancellation.cancel();
            }
            if (cancellation.isCancelled()) {
                return TargetedRefreshResult.closed(itemTypeId, CLOSED_REASON);
            }

            Instant startedAt = Instant.now();
            long startNanos = System.nanoTime();

            final LocalDate anchor;
            try {
                // THE one database anchor of this operation.
                anchor = engine.databaseCurrentDate(cancellation);
            } catch (InterruptedException interrupted) {
                // An interrupted attempt is abandoned, never recorded as evidence about the data - the same
                // rule a scan's worker applies. The interrupt is kept, not swallowed, and the outcome still
                // names the CONTEXT close when that is what stopped the attempt.
                Thread.currentThread().interrupt();
                return stopped(itemTypeId);
            } catch (Throwable failure) {
                return failureOrStopped(itemTypeId, failure, cancellation);
            }
            if (anchor == null) {
                return TargetedRefreshResult.failed(itemTypeId, NO_ANCHOR_REASON);
            }
            if (stopped(cancellation)) {
                return stopped(itemTypeId);
            }

            final ItemTypeAggregate aggregate;
            try {
                // The accepted counting path: the aggregate engine resolves the physical root segments
                // (all of 1..N, mandatory) and counts DISTINCT ItemIDs. This class contributes an
                // ItemType, the one anchored window set and the cancellation signal - nothing else.
                aggregate = engine.aggregate(itemType, ScanWindows.anchoredAt(anchor), cancellation);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return stopped(itemTypeId);
            } catch (Throwable failure) {
                return failureOrStopped(itemTypeId, failure, cancellation);
            }
            if (aggregate == null) {
                return TargetedRefreshResult.failed(itemTypeId, NO_AGGREGATE_REASON);
            }
            if (stopped(cancellation)) {
                return stopped(itemTypeId);
            }

            Instant capturedAt = Instant.now();
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            ItemTypeStatistics statistics = ItemTypeStatistics.measured(repositoryId, itemType, startedAt,
                    capturedAt, durationMs, ItemTypeStatistics.SOURCE_JDBC, aggregate);
            TargetedItemTypeDetail detail = new TargetedItemTypeDetail(repositoryId, itemTypeId, capturedAt,
                    anchor, durationMs, statistics, freshnessThreshold.judge(capturedAt, capturedAt));

            return switch (publishDetail(detail)) {
                case PUBLISHED -> TargetedRefreshResult.refreshed(detail);
                case CONTEXT_CLOSED -> TargetedRefreshResult.closed(itemTypeId, CLOSED_REASON);
                case CACHE_FULL -> TargetedRefreshResult.failed(itemTypeId,
                        "the targeted-detail cache cannot hold another ItemType (bound "
                                + TargetedDetailCache.MAX_DETAILS + ")");
            };
        } finally {
            // Clear the marker BEFORE the caller's finally opens the gate: a drainer that has observed the
            // marker gone can then rely on the gate being released next, and never on the reverse.
            inFlight.compareAndSet(cancellation, null);
            signalDrained();
        }
    }

    /** What a publish decision produced. */
    private enum PublishOutcome {
        PUBLISHED,
        CONTEXT_CLOSED,
        CACHE_FULL
    }

    /**
     * Publishes one detail, or refuses because the context closed.
     *
     * <p>The decision and the store happen under one lock shared with {@link #close()}'s discard, so
     * "discarded with the context" is monotone: once close returned, no in-flight attempt can still put an
     * entry back.
     */
    private PublishOutcome publishDetail(TargetedItemTypeDetail detail) {
        synchronized (publishLock) {
            if (closed.get()) {
                return PublishOutcome.CONTEXT_CLOSED;
            }
            return cache.publish(detail) ? PublishOutcome.PUBLISHED : PublishOutcome.CACHE_FULL;
        }
    }

    // ------------------------------------------------------------------ reads

    /** The latest published targeted detail for one ItemType, or empty when there is none. */
    public Optional<TargetedItemTypeDetail> detail(int itemTypeId) {
        return cache.find(itemTypeId);
    }

    /** Every published targeted detail in this context, in ItemType id order. */
    public List<TargetedItemTypeDetail> details() {
        return cache.all();
    }

    /** How many ItemTypes have a published targeted detail in this context. */
    public int detailCount() {
        return cache.size();
    }

    /** True while a targeted attempt is running. A diagnostic question; it admits nothing. */
    public boolean isRefreshInFlight() {
        return inFlight.get() != null;
    }

    /** The shared arbiter this capability participates in, for diagnostics and for the coordinator wiring. */
    public AnalyticsOperationGate gate() {
        return gate;
    }

    /** The configured freshness threshold this capability judges its details with. */
    public FreshnessThreshold freshnessThreshold() {
        return freshnessThreshold;
    }

    // ------------------------------------------------------------------ cancellation and lifecycle

    /**
     * Cancels the in-flight attempt, best-effort.
     *
     * <p>Runs the same abort actions a scan cancellation runs - the engine's registered
     * {@code Statement.cancel()} among them - and abandons publication: a cancelled attempt publishes no
     * detail data and leaves any previously published detail for that ItemType in place.
     *
     * @return true when an attempt was in flight and was signalled; false when there was nothing to cancel
     */
    public boolean cancelInFlight() {
        OperationCancellation cancellation = inFlight.get();
        if (cancellation == null) {
            return false;
        }
        cancellation.cancel();
        return true;
    }

    /**
     * Waits until no targeted attempt is in flight, up to {@code timeout}.
     *
     * @return true when nothing is in flight any more
     */
    public boolean awaitQuiescence(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        drainLock.lock();
        try {
            while (inFlight.get() != null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                drained.awaitNanos(remaining);
            }
            return true;
        } finally {
            drainLock.unlock();
        }
    }

    /**
     * The shutdown state of this capability, from the in-flight marker rather than from the shared gate.
     *
     * <p>{@link CloseState#CLOSING} while an attempt is still running after close began: the attempt is
     * provisional, and the owning context reports it as pending - a recoverable refusal for a repository
     * switch - rather than as a clean shutdown. Once no attempt is in flight the answer is final and
     * monotone, because a closed service admits no new attempt.
     */
    @Override
    public CloseState closeState() {
        if (!closed.get()) {
            return CloseState.NOT_CLOSED;
        }
        return inFlight.get() == null ? CloseState.CLOSED_CLEAN : CloseState.CLOSING;
    }

    /**
     * Cancels the in-flight attempt, drains it within a bounded wait and DISCARDS this context's detail
     * cache.
     *
     * <p>Idempotent. The order is the lifecycle rule: the running operation is signalled and drained first,
     * then the details it may have belonged to are dropped; a detail measured against this repository's
     * database is never carried into the next repository context.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelInFlight();
        try {
            awaitQuiescence(settings.queryTimeout().plus(DRAIN_GRACE));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        synchronized (publishLock) {
            cache.discardAll();
        }
    }

    /** Wakes every drainer. Called after the in-flight marker was cleared; see {@link #awaitQuiescence}. */
    private void signalDrained() {
        drainLock.lock();
        try {
            drained.signalAll();
        } finally {
            drainLock.unlock();
        }
    }

    // ------------------------------------------------------------------ internals

    /** True when the attempt must not publish: it was cancelled, or its context closed. */
    private boolean stopped(ScanCancellation cancellation) {
        return cancellation.isCancelled() || closed.get();
    }

    /** The right stopping outcome for a cancelled or closed attempt. */
    private TargetedRefreshResult stopped(int itemTypeId) {
        if (closed.get()) {
            return TargetedRefreshResult.closed(itemTypeId, CLOSED_REASON);
        }
        return TargetedRefreshResult.cancelled(itemTypeId, "the targeted refresh was cancelled");
    }

    /**
     * A failure that is NOT a configured stop: recorded with the same sanitized reason a scan would record,
     * unless the attempt was stopped meanwhile, in which case the stop wins - a cancelled query is not
     * evidence about the data.
     */
    private TargetedRefreshResult failureOrStopped(int itemTypeId,
                                                   Throwable failure,
                                                   ScanCancellation cancellation) {
        return stopped(cancellation)
                ? stopped(itemTypeId)
                : TargetedRefreshResult.failed(itemTypeId, ScanCoordinator.reasonOf(failure));
    }

    /**
     * The cancellation signal of ONE targeted attempt.
     *
     * <p>It implements the very same contract a scan's signal implements, scoped to one operation instead of
     * one scan, and it is deliberately not a gate: it never admits or refuses work. Its one difference from
     * the coordinator's scan signal is a consequence of what it can hold - a targeted attempt runs its
     * engine calls strictly one at a time, so this holds the single registered abort action instead of a
     * per-query map. Registering after a cancel runs the action immediately, exactly as the engine's
     * contract requires, which is what closes the window between "close began" and "the query starts".
     */
    private static final class OperationCancellation implements ScanCancellation {

        private static final Registration NONE = () -> { };

        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Runnable> abortAction = new AtomicReference<>();

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public Registration register(Runnable action) {
            if (action == null) {
                return NONE;
            }
            if (cancelled.get()) {
                runQuietly(action);
                return NONE;
            }
            abortAction.set(action);
            if (cancelled.get() && abortAction.compareAndSet(action, null)) {
                runQuietly(action);
            }
            return () -> abortAction.compareAndSet(action, null);
        }

        private void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                Runnable action = abortAction.getAndSet(null);
                if (action != null) {
                    runQuietly(action);
                }
            }
        }

        private static void runQuietly(Runnable action) {
            try {
                action.run();
            } catch (RuntimeException | Error ignored) {
                // A failing abort action must not stop the operation from finishing through its normal path:
                // the statement is closed and the lease returned by the engine's own try-with-resources.
            }
        }
    }

    @Override
    public String toString() {
        return "TargetedRefreshService[repository=" + repositoryId
                + ", gate=" + gate.describe()
                + ", inFlight=" + isRefreshInFlight()
                + ", details=" + cache.size()
                + ", closed=" + closed.get() + "]";
    }
}
