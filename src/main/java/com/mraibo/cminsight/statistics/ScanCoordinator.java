package com.mraibo.cminsight.statistics;

import com.mraibo.cminsight.core.StatisticsSettings;
import com.mraibo.cminsight.metadata.ItemTypeSummary;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The bounded scan coordinator: one scan at a time, a fixed number of workers, one atomic publication.
 *
 * <h2>One scan at a time, without a queue</h2>
 *
 * <p>{@link #requestScan()} wins an {@link AtomicBoolean} compare-and-set before anything else happens, so a
 * second caller gets {@link ScanStartResult#ALREADY_RUNNING} and no side effect at all - there is no
 * check-then-act window in which two scans could both start. The flag is released only when the scan's
 * supervisor thread has finished, which means the next scan cannot begin while any worker of the previous
 * one is still alive.
 *
 * <p>Concurrency is exactly {@code statistics.workers} {@link Thread} objects sharing ONE
 * {@link AtomicInteger} index over the frozen ItemType list. There is no {@code ExecutorService}, no task
 * queue and no submission step, so an unbounded queue is not merely avoided - it does not exist, and the
 * peak number of concurrent queries is structurally the worker count. The configured worker count is
 * validated against {@code jdbc.pool.size} at configuration time
 * ({@link StatisticsSettings#from(com.mraibo.cminsight.config.AppConfig, int)}), so the requested
 * parallelism is one the pool can actually serve instead of a number that silently exceeds it.
 *
 * <h2>The two things read exactly once, at scan start</h2>
 *
 * <ul>
 *   <li><strong>the ItemType list</strong> - the {@link Supplier} is called once and the result copied with
 *       {@code List.copyOf}, so a metadata refresh during the scan cannot change the work in progress, and
 *       the snapshot's coverage is measured against a list that cannot move;</li>
 *   <li><strong>the database current date</strong> - {@link StatisticsEngine#databaseCurrentDate()} is
 *       called once, before the first aggregate, and the resulting {@link ScanWindows} is shared by every
 *       ItemType. A scan that crosses midnight therefore cannot mix two calendars.</li>
 * </ul>
 *
 * <h2>Atomic publication, and what never replaces a snapshot</h2>
 *
 * <p>Workers write their per-ItemType results into a private array; the supervisor decides the outcome after
 * joining them. Only in the normal terminal case - the whole frozen list visited - is ONE
 * {@link StatisticsSnapshot} built and stored with a single {@link AtomicReference#set}, so a reader sees
 * either the previous completed snapshot or the new complete one, never a half-filled object. A failed
 * ItemType does not prevent publication: it is recorded as ERROR and counted in
 * {@link StatisticsSnapshot#partialFailureCount()} and the coverage, which is what stops a partial total
 * from being presented as a complete one.
 *
 * <p>An overall-timeout abort, a cancellation, a repository close or a catastrophic coordinator failure
 * publishes NOTHING, so the previous completed snapshot remains the current answer. Cancellation wins even
 * over a scan that had already filled every slot: a scan that was asked to stop must not publish.
 *
 * <h2>Cancellation and shutdown</h2>
 *
 * <p>Cancellation is best-effort and uses everything available: the scan's {@link ScanCancellation} runs the
 * abort actions registered by the query layer (for example {@code JdbcSession::cancelInFlight}, i.e.
 * {@code Statement.cancel()}), and every thread of the scan is interrupted. Workers then finish normally -
 * a cancelled item writes no slot at all, and the lease the query holds is returned by the normal
 * {@code try}-with-resources path, so a cancelled query retires its connection rather than leaking it.
 *
 * <p>{@link #close()} sets the closed flag, cancels the running scan and joins every thread this coordinator
 * started, up to the configured scan timeout. A repository context must therefore register this coordinator
 * as an owned resource AFTER the JDBC pool: {@code RepositoryContext} closes its resources in reverse order,
 * so the scan is always drained before the pool it borrows from is closed. Threads are daemon threads, so a
 * worker that ignores both cancellation and interruption can never block JVM exit; it can only keep its
 * connection leased, which is exactly what makes the owning context report
 * {@code CloseState.CLOSING} instead of claiming a clean shutdown.
 *
 * <h2>Failure isolation and reason sanitation</h2>
 *
 * <p>One ItemType's failure never aborts another's: each item is measured inside its own try/catch and only
 * its own slot is marked ERROR. The reason recorded for a failure is
 * {@link StatisticsQueryException#sanitizedReasonWithState()} when the layer produced that type, and
 * otherwise the exception's CLASS NAME - never {@link Throwable#getMessage()}, so a driver message, a SQL
 * fragment, a URL or a credential cannot reach a snapshot through this path.
 */
public final class ScanCoordinator implements StatisticsRepository, AutoCloseable {

    /** Prefix of every thread this coordinator creates, so "no thread left" is an exact check. */
    public static final String THREAD_NAME_PREFIX = "cm-insight-scan-";

    /** How long a cancelled scan's workers are given to finish their current query before it is abandoned. */
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(10);

    private final StatisticsSettings settings;
    private final String repositoryId;
    private final Supplier<List<ItemTypeSummary>> itemTypeSource;
    private final StatisticsEngine engine;

    private final AtomicBoolean scanInFlight = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<StatisticsSnapshot> published = new AtomicReference<>();
    private final AtomicReference<ActiveScan> active = new AtomicReference<>();
    private final AtomicLong scanSequence = new AtomicLong();

    private final ReentrantLock stateLock = new ReentrantLock();
    private final Condition scanFinished = stateLock.newCondition();
    private final Object threadsLock = new Object();
    private final List<Thread> threads = new ArrayList<>();

    /**
     * Guards the four progress counters as ONE consistent tuple.
     *
     * <p>They are read together by {@link #progress()}, and that read must never observe a combination the
     * scan could not have had - a new frozen total next to the previous scan's completion count would make
     * {@link StatisticsCoverage} reject the tuple and turn a status request into an exception. One lock for
     * all four makes the tuple atomic instead of relying on the reader's field order.
     */
    private final Object countersLock = new Object();
    private int totalItemTypes;
    private int completedCount;
    private int failedCount;
    private int partialCount;

    private volatile ScanStatus.Phase phase = ScanStatus.Phase.IDLE;
    private volatile long currentScanId;
    private volatile LocalDate anchorDate;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String currentItemType = "";
    private volatile String failureReason = "";

    /**
     * @param settings       validated bounds: worker count, query timeout and the overall scan deadline
     * @param repositoryId   the repository the scan belongs to, used in thread names and the snapshot
     * @param itemTypeSource called EXACTLY ONCE per scan to freeze the ItemType list
     * @param engine         the database half; see {@link StatisticsEngine} for its obligations
     */
    public ScanCoordinator(StatisticsSettings settings,
                           String repositoryId,
                           Supplier<List<ItemTypeSummary>> itemTypeSource,
                           StatisticsEngine engine) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.repositoryId = repositoryId == null ? "" : repositoryId.trim();
        this.itemTypeSource = Objects.requireNonNull(itemTypeSource, "itemTypeSource");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public StatisticsSettings settings() {
        return settings;
    }

    public String repositoryId() {
        return repositoryId;
    }

    // ------------------------------------------------------------------ StatisticsRepository

    @Override
    public StatisticsAvailability availability() {
        if (closed.get()) {
            return StatisticsAvailability.unavailable("the repository context is closed");
        }
        if (!settings.enabled()) {
            return StatisticsAvailability.featureDisabled(StatisticsSettings.ENABLED_KEY + "=false");
        }
        return StatisticsAvailability.ready();
    }

    @Override
    public Optional<StatisticsSnapshot> snapshot() {
        return Optional.ofNullable(published.get());
    }

    @Override
    public ScanStatus progress() {
        Instant start = startedAt;
        Instant finish = finishedAt;
        long durationMs;
        if (start == null) {
            durationMs = 0L;
        } else {
            durationMs = Duration.between(start, finish == null ? Instant.now() : finish).toMillis();
        }
        final int total;
        final int completed;
        final int failed;
        final int partial;
        synchronized (countersLock) {
            // Read as one tuple: total, completed, failed and partial always describe the same instant.
            total = totalItemTypes;
            completed = completedCount;
            failed = failedCount;
            partial = partialCount;
        }
        double rate = durationMs <= 0L ? 0.0 : (completed * 1000.0) / durationMs;
        return new ScanStatus(repositoryId, phase, scanInFlight.get(), currentScanId, total,
                completed, failed, anchorDate, start, finish, durationMs, rate, currentItemType,
                new StatisticsCoverage(total, completed, failed, partial), failureReason);
    }

    @Override
    public boolean isScanInFlight() {
        return scanInFlight.get();
    }

    /**
     * The coordinator owns no JDBC pool: it borrows through the engine. The pool view belongs to the typed
     * service that owns both, so a bare coordinator answers "usable, but I have no pool to describe" rather
     * than inventing zeros or claiming the analytics half is unavailable.
     */
    @Override
    public StatisticsDiagnostics diagnostics() {
        return StatisticsDiagnostics.withoutPool(availability(),
                "this scan coordinator owns no JDBC pool; the pool view is published by the repository"
                        + " statistics service");
    }

    @Override
    public ScanStartResult requestScan() {
        if (closed.get()) {
            return ScanStartResult.CLOSED;
        }
        if (!settings.enabled()) {
            return ScanStartResult.UNAVAILABLE;
        }
        if (!scanInFlight.compareAndSet(false, true)) {
            // No side effect whatsoever: the running scan keeps its frozen list, anchor and results.
            return ScanStartResult.ALREADY_RUNNING;
        }
        long scanId = scanSequence.incrementAndGet();
        ActiveScan scan = new ActiveScan(scanId);
        currentScanId = scanId;
        anchorDate = null;
        currentItemType = "";
        failureReason = "";
        finishedAt = null;
        startedAt = Instant.now();
        phase = ScanStatus.Phase.RUNNING;
        synchronized (countersLock) {
            totalItemTypes = 0;
            completedCount = 0;
            failedCount = 0;
            partialCount = 0;
        }
        active.set(scan);
        synchronized (threadsLock) {
            threads.clear();
        }
        Thread supervisor = new Thread(() -> runScan(scan), THREAD_NAME_PREFIX + repositoryId + "-supervisor");
        supervisor.setDaemon(true);
        registerThread(supervisor);
        try {
            supervisor.start();
        } catch (Throwable failure) {
            // A thread that cannot start must not leave the coordinator wedged in "one scan in flight".
            finishScan(scan, null, System.nanoTime(), failure);
            return ScanStartResult.UNAVAILABLE;
        }
        return ScanStartResult.STARTED;
    }

    @Override
    public boolean cancelScan() {
        ActiveScan scan = active.get();
        if (scan == null || !scanInFlight.get()) {
            return false;
        }
        cancelInternal(scan);
        return true;
    }

    @Override
    public boolean awaitScanCompletion(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        stateLock.lock();
        try {
            while (scanInFlight.get()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                scanFinished.awaitNanos(remaining);
            }
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Cancels the running scan and joins every thread this coordinator started.
     *
     * <p>Idempotent. The wait is bounded by the configured scan timeout: a worker that ignores both the
     * abort action and the interrupt cannot make shutdown hang, because it is a daemon thread and its
     * connection stays leased - which the owning context reports as {@code CLOSING} rather than as a clean
     * shutdown.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ActiveScan scan = active.get();
        if (scan != null) {
            cancelInternal(scan);
        }
        long deadline = System.nanoTime() + settings.scanTimeout().toNanos();
        for (Thread thread : threadsSnapshot()) {
            if (thread == Thread.currentThread()) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            try {
                thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        clearThreads();
    }

    // ------------------------------------------------------------------ the scan

    private void runScan(ActiveScan scan) {
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + settings.scanTimeout().toNanos();
        List<Thread> workers = new ArrayList<>();
        List<ItemTypeStatistics> results = null;
        try {
            results = visitFrozenItemTypes(scan, deadlineNanos, workers);
        } catch (Throwable failure) {
            // The coordinator itself failed. Nothing is published, and the reason is sanitized.
            scan.failure = reasonOf(failure);
        }
        boolean lingering = joinWorkers(workers, deadlineNanos);
        if (lingering) {
            if (results == null) {
                // A query outlived the deadline, so at least one item is unvisited: abort it and give the
                // workers a bounded grace period to return their leases before this scan is declared over.
                // As above, a cancellation is NOT a timeout - it interrupts this thread too - so the clock is
                // only blamed when the scan was not cancelled.
                if (!scan.cancellation.isCancelled() && !closed.get()) {
                    scan.deadlineExceeded.set(true);
                }
                cancelInternal(scan);
            }
            joinWorkers(workers, System.nanoTime() + DRAIN_GRACE.toNanos());
        }
        finishScan(scan, results, startNanos, null);
    }

    /**
     * Freezes the list, reads the anchor once, runs the workers and returns their results - or {@code null}
     * when the scan must not publish anything.
     */
    private List<ItemTypeStatistics> visitFrozenItemTypes(ActiveScan scan,
                                                          long deadlineNanos,
                                                          List<Thread> workers) {
        final List<ItemTypeSummary> frozen;
        try {
            List<ItemTypeSummary> supplied = itemTypeSource.get();
            if (supplied == null) {
                scan.failure = "the ItemType source returned no list";
                return null;
            }
            // THE freeze: one read, one immutable copy, for the whole scan.
            frozen = List.copyOf(supplied);
        } catch (Throwable failure) {
            scan.failure = "the frozen ItemType list could not be read: " + reasonOf(failure);
            return null;
        }
        synchronized (countersLock) {
            // The frozen size and the (already reset) counters are published as one tuple, so a status read
            // cannot pair this scan's total with the previous scan's counts.
            totalItemTypes = frozen.size();
        }
        if (closed.get() || scan.cancellation.isCancelled()) {
            return null;
        }

        final LocalDate anchor;
        try {
            // THE one database date of this scan, read before the first aggregate and shared by every
            // ItemType through one ScanWindows instance.
            anchor = engine.databaseCurrentDate();
        } catch (Throwable failure) {
            scan.failure = reasonOf(failure);
            return null;
        }
        if (anchor == null) {
            scan.failure = "the database current-date query returned no date";
            return null;
        }
        if (closed.get() || scan.cancellation.isCancelled()) {
            return null;
        }
        anchorDate = anchor;
        ScanWindows windows = ScanWindows.anchoredAt(anchor);

        if (frozen.isEmpty()) {
            // The frozen list was visited vacuously: an empty snapshot is a complete answer.
            return List.of();
        }

        ItemTypeStatistics[] slots = new ItemTypeStatistics[frozen.size()];
        AtomicInteger index = new AtomicInteger();
        int workerCount = Math.max(1, Math.min(settings.workers(), frozen.size()));
        for (int i = 0; i < workerCount; i++) {
            Thread worker = new Thread(
                    () -> workerLoop(scan, frozen, windows, slots, index, deadlineNanos),
                    THREAD_NAME_PREFIX + repositoryId + "-" + i);
            worker.setDaemon(true);
            registerThread(worker);
            workers.add(worker);
            worker.start();
        }

        if (joinWorkers(workers, deadlineNanos)) {
            // The deadline cut the scan short: an item is unvisited, so nothing may be published.
            //
            // UNLESS the scan was cancelled, and the distinction is not cosmetic: cancelling interrupts this
            // supervisor as well, so a cancellation makes the join above return early and look exactly like a
            // deadline that ran out. Recording deadlineExceeded here would report a cancelled scan - an
            // explicit cancel, a repository switch or a context close - as a TIMED_OUT one, and an operator
            // would go looking for a slow query instead of the switch that stopped it. The clock is only the
            // reason when nothing else stopped the scan.
            if (!scan.cancellation.isCancelled() && !closed.get()) {
                scan.deadlineExceeded.set(true);
            }
            cancelInternal(scan);
            joinWorkers(workers, System.nanoTime() + DRAIN_GRACE.toNanos());
            return null;
        }
        if (closed.get() || scan.cancellation.isCancelled()) {
            return null;
        }
        if (scan.deadlineExceeded.get()) {
            return null;
        }
        if (scan.failure != null) {
            return null;
        }
        for (ItemTypeStatistics slot : slots) {
            if (slot == null) {
                // Defensive: with every worker joined and no deadline trip, this cannot happen. If it ever
                // does, publishing a short list would misreport coverage, so it is a failure instead.
                scan.failure = "an ItemType was never visited by any worker";
                return null;
            }
        }
        return List.of(slots);
    }

    /** One worker: claim an index from the shared counter, measure that ItemType, repeat until done. */
    private void workerLoop(ActiveScan scan,
                            List<ItemTypeSummary> frozen,
                            ScanWindows windows,
                            ItemTypeStatistics[] slots,
                            AtomicInteger index,
                            long deadlineNanos) {
        Thread self = Thread.currentThread();
        while (true) {
            if (closed.get() || scan.cancellation.isCancelled() || self.isInterrupted()) {
                return;
            }
            int position = index.getAndIncrement();
            if (position >= frozen.size()) {
                return;
            }
            if (System.nanoTime() >= deadlineNanos) {
                // This ItemType is left unvisited, so no snapshot of this scan may be published.
                scan.deadlineExceeded.set(true);
                return;
            }
            ItemTypeSummary itemType = frozen.get(position);
            currentItemType = itemType.name();
            long itemStartNanos = System.nanoTime();
            // Not a blank final: the catch below legitimately assigns it too, and Java's definite-assignment
            // analysis cannot know that the try's assignment is its last statement.
            ItemTypeStatistics result;
            try {
                ItemTypeAggregate aggregate = engine.aggregate(itemType, windows, scan.cancellation);
                if (aggregate == null) {
                    throw new IllegalStateException("the aggregate engine returned no result");
                }
                result = ItemTypeStatistics.measured(repositoryId, itemType, startedAt, Instant.now(),
                        millisSince(itemStartNanos), ItemTypeStatistics.SOURCE_JDBC, aggregate);
            } catch (InterruptedException interrupted) {
                // The scan is being cancelled: this item is abandoned rather than failed, because a
                // cancelled query is not evidence about the data.
                self.interrupt();
                return;
            } catch (Throwable failure) {
                if (closed.get() || scan.cancellation.isCancelled() || self.isInterrupted()) {
                    return;
                }
                // ONE failure never aborts the other ItemTypes: the remaining workers keep claiming
                // indexes, and this item is recorded as ERROR with a sanitized reason.
                result = ItemTypeStatistics.failed(repositoryId, itemType, startedAt, Instant.now(),
                        millisSince(itemStartNanos), ItemTypeStatistics.SOURCE_JDBC, reasonOf(failure));
            }
            slots[position] = result;
            recordCompletion(result);
            currentItemType = "";
        }
    }

    /**
     * Decides the outcome and, in exactly one case, publishes ONE new immutable snapshot.
     *
     * @param crash an exception the coordinator itself threw, or {@code null}
     */
    private void finishScan(ActiveScan scan,
                            List<ItemTypeStatistics> results,
                            long startNanos,
                            Throwable crash) {
        Instant finished = Instant.now();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        if (crash != null && scan.failure == null) {
            scan.failure = reasonOf(crash);
        }
        ScanStatus.Phase terminal;
        String reason = "";
        if (results != null && !closed.get() && !scan.cancellation.isCancelled()) {
            // The normal terminal case: the whole frozen list was visited. ONE atomic store publishes it;
            // the previous snapshot stays visible until this instant and is never touched again. The frozen
            // count is the result count by construction - StatisticsSnapshot.of refuses any other pairing.
            StatisticsSnapshot snapshot = StatisticsSnapshot.of(repositoryId, scan.scanId, finished,
                    startedAt, durationMs, anchorDate, results.size(), results);
            published.set(snapshot);
            terminal = ScanStatus.Phase.COMPLETED;
        } else if (scan.deadlineExceeded.get()) {
            // Checked BEFORE cancellation, and the order matters: the deadline path aborts its own in-flight
            // queries, which sets the cancellation signal too. Testing cancellation first would therefore
            // report every genuine timeout as a cancellation. The two facts are kept distinct at the source
            // instead - deadlineExceeded is only set when nothing external had already cancelled the scan.
            terminal = ScanStatus.Phase.TIMED_OUT;
            reason = "the overall scan deadline of " + settings.scanTimeoutSeconds()
                    + "s expired before every ItemType was visited; the previous snapshot is unchanged";
        } else if (scan.cancellation.isCancelled() || closed.get()) {
            terminal = ScanStatus.Phase.CANCELLED;
            reason = closed.get()
                    ? "the repository context was closed while the scan was running"
                    : "the scan was cancelled";
        } else {
            terminal = ScanStatus.Phase.FAILED;
            reason = scan.failure == null ? "the scan did not visit every ItemType" : scan.failure;
        }
        failureReason = reason;
        finishedAt = finished;
        phase = terminal;
        currentItemType = "";
        active.compareAndSet(scan, null);
        // Released LAST, and only after the outcome is final: no second scan may start while a worker of
        // this one could still be running, and no reader may see the flag clear before the phase is set.
        scanInFlight.set(false);
        stateLock.lock();
        try {
            scanFinished.signalAll();
        } finally {
            stateLock.unlock();
        }
        clearThreads();
    }

    private void recordCompletion(ItemTypeStatistics result) {
        synchronized (countersLock) {
            completedCount++;
            if (result.status() == ItemTypeStatistics.Status.ERROR) {
                failedCount++;
            } else if (result.status() == ItemTypeStatistics.Status.PARTIAL) {
                partialCount++;
            }
        }
    }

    /**
     * Marks the scan cancelled, runs every abort action the query layer registered and interrupts the scan's
     * threads. Best-effort by design: the abort action is a request, and a worker still finishes normally.
     */
    private void cancelInternal(ActiveScan scan) {
        scan.cancellation.cancel();
        Thread current = Thread.currentThread();
        for (Thread thread : threadsSnapshot()) {
            if (thread != current) {
                thread.interrupt();
            }
        }
    }

    /** Joins every worker, up to the given absolute deadline. Returns true when one is still alive. */
    private boolean joinWorkers(List<Thread> workers, long deadlineNanos) {
        boolean alive = false;
        for (Thread worker : workers) {
            if (worker.isAlive()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining > 0L) {
                    try {
                        worker.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            if (worker.isAlive()) {
                alive = true;
            }
        }
        return alive;
    }

    private void registerThread(Thread thread) {
        synchronized (threadsLock) {
            threads.add(thread);
        }
    }

    private List<Thread> threadsSnapshot() {
        synchronized (threadsLock) {
            return List.copyOf(threads);
        }
    }

    private void clearThreads() {
        synchronized (threadsLock) {
            threads.clear();
        }
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * The sanitized reason for a failure: the fixed operation label plus SQLState/vendor code when the
     * database layer produced {@link StatisticsQueryException}, and otherwise the exception's CLASS NAME.
     *
     * <p>{@link Throwable#getMessage()} is deliberately never used. A driver message, a SQL fragment, a
     * JDBC URL or a credential must not be able to reach a snapshot through this path, and "the message
     * happened to be safe" is not something this layer can verify.
     */
    static String reasonOf(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof StatisticsQueryException query) {
                return query.sanitizedReasonWithState();
            }
        }
        return failure.getClass().getSimpleName();
    }

    @Override
    public String toString() {
        return "ScanCoordinator[repository=" + repositoryId + ", " + progress() + "]";
    }

    /** The mutable state of one scan, visible only to that scan's threads. */
    private static final class ActiveScan {

        private final long scanId;
        private final ScanCancellationImpl cancellation = new ScanCancellationImpl();
        /** Set when the deadline left at least one frozen ItemType unvisited. */
        private final AtomicBoolean deadlineExceeded = new AtomicBoolean();
        /** A sanitized catastrophic reason; written by one thread and read by the supervisor. */
        private volatile String failure;

        private ActiveScan(long scanId) {
            this.scanId = scanId;
        }
    }

    /**
     * The scan-scoped cancellation signal, plus the abort actions the query layer registers.
     *
     * <p>The actions are keyed by a per-registration token and removed when the query finishes, so the map
     * holds at most one entry per in-flight query - bounded by the worker count, not by the number of
     * ItemTypes. Registering after cancellation runs the action immediately: a query that started in the
     * instant between the cancel and its own registration must still be abortable.
     */
    private static final class ScanCancellationImpl implements ScanCancellation {

        private static final Registration NONE = () -> { };

        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Map<Object, Runnable> actions = new ConcurrentHashMap<>();

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public Registration register(Runnable abortAction) {
            if (abortAction == null) {
                return NONE;
            }
            if (cancelled.get()) {
                runQuietly(abortAction);
                return NONE;
            }
            Object token = new Object();
            actions.put(token, abortAction);
            if (cancelled.get() && actions.remove(token) != null) {
                runQuietly(abortAction);
            }
            return () -> actions.remove(token);
        }

        private void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                for (Runnable action : actions.values()) {
                    runQuietly(action);
                }
                actions.clear();
            }
        }

        private static void runQuietly(Runnable action) {
            try {
                action.run();
            } catch (RuntimeException | Error ignored) {
                // A failing abort action must not stop the remaining ones, and the worker still finishes
                // through its normal path (statement closed, lease returned).
            }
        }
    }
}
