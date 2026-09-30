package com.mraibo.cminsight.statistics;

import com.mraibo.cminsight.core.CloseState;
import com.mraibo.cminsight.core.CloseStateAware;
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
 * check-then-act window in which two scans could both start. The flag is the ONE-SCAN GATE, and it is
 * released only when the last thread of the previous scan has actually terminated; see
 * "The gate is latched, not timed" below.
 *
 * <p>Concurrency is exactly {@code statistics.workers} {@link Thread} objects sharing ONE
 * {@link AtomicInteger} index over the frozen ItemType list. There is no {@code ExecutorService}, no task
 * queue and no submission step, so an unbounded queue is not merely avoided - it does not exist, and the
 * peak number of concurrent queries is structurally the worker count. The configured worker count is
 * validated against {@code jdbc.pool.size} at configuration time
 * ({@link StatisticsSettings#from(com.mraibo.cminsight.config.AppConfig, int)}), so the requested
 * parallelism is one the pool can actually serve instead of a number that silently exceeds it.
 *
 * <h2>The gate is latched, not timed</h2>
 *
 * <p>A deadline or a cancellation decides the scan's PUBLICATION and its terminal
 * {@link ScanStatus.Phase}; it does not prove that physical execution ended. So the gate is NOT released on
 * a terminal outcome. It is released by the last thread of the scan to actually exit, through one shared
 * owner ({@code gateReleased}) and one cleared-in-the-same-moment thread set:
 *
 * <ul>
 *   <li>threads exit before the deadline - whichever of the last worker and the supervisor exits last
 *       releases it, from that thread's {@code finally};</li>
 *   <li>a worker lingers past {@link #DRAIN_GRACE} because the driver ignored both the abort action and the
 *       interrupt - ONE per-scan observer thread waits for the tracked set to empty and releases it (see
 *       {@link #startLingeringWatcher});</li>
 *   <li>the supervisor is interrupted or dies of an {@link Error} - its own {@code finally} still runs, and
 *       a worker that outlives it finds the release path on its own exit, so the gate cannot be left latched
 *       forever.</li>
 * </ul>
 *
 * <p>While the gate is latched, {@link #isScanInFlight()} is true even when the terminal phase is already
 * {@code TIMED_OUT} or {@code CANCELLED}, {@link #isDraining()} names that fact explicitly, and
 * {@link #requestScan()} keeps refusing - so a second scan can never overlap the first one's JDBC lease.
 * The tracked thread references are never cleared while any of them is alive.
 *
 * <h2>One explicit absolute deadline, and the anchor inside it</h2>
 *
 * <p>Every scan owns ONE absolute deadline ({@link ActiveScan#deadlineNanos}) and ONE bounded daemon
 * watchdog thread that enforces it ({@link #watchdogLoop}). The watchdog waits for the earliest of the
 * deadline, the scan's cancellation signal and the scan's completion, and when the deadline wins it sets
 * the timeout fact, cancels the scan's {@link ScanCancellation} and interrupts the scan's threads. Because
 * the database anchor registers its abort action with that same {@link ScanCancellation} - see
 * {@link StatisticsEngine#databaseCurrentDate(ScanCancellation)} - the overall deadline, an explicit
 * cancellation and a context close all reach the anchor's {@code Statement.cancel()}, whether or not a
 * worker exists yet. {@code statistics.query.timeout.seconds} remains an additional per-statement cap and
 * is never the enforcement of the overall scan timeout: it may legitimately be LARGER than
 * {@code statistics.scan.timeout.seconds}, and this deadline wins regardless.</p>
 *
 * <h2>The two things read exactly once, at scan start</h2>
 *
 * <ul>
 *   <li><strong>the ItemType list</strong> - the {@link Supplier} is called once and the result copied with
 *       {@code List.copyOf}, so a metadata refresh during the scan cannot change the work in progress, and
 *       the snapshot's coverage is measured against a list that cannot move;</li>
 *   <li><strong>the database current date</strong> - {@link StatisticsEngine#databaseCurrentDate} is
 *       called once, before the first aggregate, and the resulting {@link ScanWindows} is shared by every
 *       ItemType. A scan that crosses midnight therefore cannot mix two calendars. It is never re-read as a
 *       retry or a fallback, and no JVM-local date is substituted for it.</li>
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
 * connection leased and its thread tracked.</p>
 *
 * <p>Because that join is BOUNDED, a stubborn scan thread can outlive {@link #close()} itself. This
 * coordinator therefore implements {@link CloseStateAware}: after close begins a still-live scan thread
 * means {@link CloseState#CLOSING}, and only once every tracked scan thread is dead may
 * {@link CloseState#CLOSED_CLEAN} be reported. It deliberately does NOT implement
 * {@code CloseOutcomeAware}: a slow thread is {@link CloseState#CLOSING} - which
 * {@code RepositoryManager} maps to a recoverable {@code Refusal.PENDING} - and never
 * {@link CloseState#CLOSED_UNCERTAIN}, which is terminal and would refuse every future repository switch
 * forever merely because a bound was hit.</p>
 *
 * <h2>Failure isolation and reason sanitation</h2>
 *
 * <p>One ItemType's failure never aborts another's: each item is measured inside its own try/catch and only
 * its own slot is marked ERROR. The reason recorded for a failure is
 * {@link StatisticsQueryException#sanitizedReasonWithState()} when the layer produced that type, and
 * otherwise the exception's CLASS NAME - never {@link Throwable#getMessage()}, so a driver message, a SQL
 * fragment, a URL or a credential cannot reach a snapshot through this path.
 */
public final class ScanCoordinator implements StatisticsRepository, CloseStateAware, AutoCloseable {

    /** Prefix of every thread this coordinator creates, so "no thread left" is an exact check. */
    public static final String THREAD_NAME_PREFIX = "cm-insight-scan-";

    /**
     * How long a cancelled scan's workers are given to finish their current query before the supervisor
     * stops waiting for them.
     *
     * <p>It is a WAIT bound, never a release: when it expires the scan stays draining with its gate latched
     * until the lingering worker really exits. See {@link #startLingeringWatcher}.
     */
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(10);

    /** Suffix of the supervisor thread, so it is both tracked and distinguishable from a worker. */
    private static final String SUPERVISOR_SUFFIX = "-supervisor";

    /** Suffix of the per-scan deadline watchdog thread; see {@link #watchdogLoop}. */
    private static final String WATCHDOG_SUFFIX = "-watchdog";

    /** Suffix of the per-scan lingering-worker watcher thread; see {@link #startLingeringWatcher}. */
    private static final String LINGERING_SUFFIX = "-lingering";

    /**
     * Ceiling on how long a watchdog parks before re-reading its conditions.
     *
     * <p>The watchdog is signalled whenever the scan finishes, is cancelled or is closed, so this is a
     * belt-and-braces bound rather than the mechanism: it is what guarantees the thread exits even if a
     * signal were ever missed, and it keeps the wait interruptible and cheap to reason about.
     */
    private static final long WATCHDOG_TICK_MILLIS = 250L;

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
    private final Condition scanProgressed = stateLock.newCondition();

    /** Guards {@link #threads} and {@link #lingeringWatcher}. */
    private final Object threadsLock = new Object();
    private final List<Thread> threads = new ArrayList<>();
    private Thread lingeringWatcher;

    /**
     * How many worker batches are currently being started, under {@link #threadsLock}.
     *
     * <p>While one is open, "no tracked thread is alive" is not proof that the scan is over: the next worker
     * does not exist yet but is about to. {@link #releaseGateIfDrained()} refuses for that whole window, so
     * the gate cannot open between two worker registrations.</p>
     */
    private int workerBatchesOpen;

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

    /**
     * The first terminal phase any thread decided for the CURRENT scan, or {@code null} meaning RUNNING.
     *
     * <p>An {@link AtomicReference} rather than a plain field because two threads can legitimately be first:
     * the supervisor ({@link #finishScan}) and the watchdog (which decides when the deadline reaches work no
     * worker was covering). See {@link #resolveTerminalPhase}.</p>
     */
    private final AtomicReference<ScanStatus.Phase> terminalPhase = new AtomicReference<>();
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
        // The phase is the terminal one as soon as ANY thread decided it (the supervisor when the workers
        // return, the watchdog when the deadline reaches work nothing else was covering), and RUNNING until
        // then. It is deliberately independent of isScanInFlight: a TIMED_OUT scan whose worker is still
        // alive reports exactly that pair, which is the distinction section A is about.
        ScanStatus.Phase current = terminalPhase.get();
        if (current == null) {
            current = ScanStatus.Phase.RUNNING;
        }
        return new ScanStatus(repositoryId, current, scanInFlight.get(), currentScanId, total,
                completed, failed, anchorDate, start, finish, durationMs, rate, currentItemType,
                new StatisticsCoverage(total, completed, failed, partial), failureReason);
    }

    @Override
    public boolean isScanInFlight() {
        // The GATE, not the phase: true while this scan's supervisor or any of its workers is still alive,
        // which is what keeps requestScan() refusing after a terminal outcome. See isDraining() for the
        // explicit "result is final, physical work is not" fact.
        return scanInFlight.get();
    }

    /**
     * True when the running scan's result is already final (TIMED_OUT, CANCELLED, FAILED or COMPLETED) but
     * at least one tracked scan thread is still alive.
     *
     * <p>This is the explicit draining fact section A requires: {@code isScanInFlight()} stays true and
     * {@link #requestScan()} keeps refusing, so a second scan can never overlap this one's JDBC lease, while
     * a reader can tell "the answer is decided" apart from "the process is gone" without a fifth
     * {@link ScanStatus.Phase} value changing what an existing terminal phase means.</p>
     */
    public boolean isDraining() {
        ActiveScan scan = active.get();
        return scan != null && scan.complete && lingeringScanThreadCount() > 0;
    }

    /**
     * How many coordinator threads that belong to a scan are still alive: workers plus the supervisor.
     *
     * <p>The two bookkeeping threads are deliberately excluded. The per-scan watchdog is the deadline
     * enforcer, not scan work; the lingering watcher is the thing that WAITS for scan work, and counting it
     * would make this number - and the draining fact built on it - report a lingering scan forever. Neither
     * change when the real work is over, so neither may be allowed to define it.</p>
     */
    public int lingeringScanThreadCount() {
        int alive = 0;
        for (Thread thread : threadsSnapshot()) {
            if (isScanWorkThread(thread) && thread.isAlive()) {
                alive++;
            }
        }
        return alive;
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

    // ------------------------------------------------------------------ CloseStateAware

    /**
     * The shutdown state, from the scan gate rather than from the JDBC pool, so a scan thread that outlived
     * a bounded {@link #close()} stays visible independently of who owns the connections.
     *
     * <p>Cheap, non-blocking and side-effect free by contract: it reads one atomic flag and walks a copied
     * list of thread references. A still-live scan thread after close began is
     * {@link CloseState#CLOSING} - provisional, and recoverable, which is exactly the
     * {@code Refusal.PENDING} a repository switch must get while it waits. Only once every tracked scan
     * thread is dead is {@link CloseState#CLOSED_CLEAN} reported, and {@link CloseState#CLOSED_UNCERTAIN} is
     * never invented: a merely slow thread is pending, not uncertain, and uncertainty here would refuse
     * every future repository switch forever.
     *
     * <p>Monotone by construction: a thread that has terminated never becomes alive again, so this answer
     * cannot flap from CLOSED_CLEAN back to CLOSING.
     */
    @Override
    public CloseState closeState() {
        if (!closed.get()) {
            return CloseState.NOT_CLOSED;
        }
        if (!scanInFlight.get()) {
            // The gate is released exactly when the last tracked scan thread exited and the references were
            // cleared in the same critical section, so a released gate IS the proof that nothing is left.
            return CloseState.CLOSED_CLEAN;
        }
        return CloseState.CLOSING;
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
            // No side effect whatsoever: the running scan keeps its frozen list, anchor and results. This is
            // also the answer while a previous scan is DRAINING - its result is final but a worker of it is
            // still alive, and isDraining() names that fact for a caller that wants the distinction.
            return ScanStartResult.ALREADY_RUNNING;
        }
        long scanId = scanSequence.incrementAndGet();
        long startNanos = System.nanoTime();
        // THE one absolute scan deadline, owned here and enforced by this scan's watchdog. It is derived
        // from statistics.scan.timeout.seconds ONLY: the per-query timeout is an additional statement cap
        // and never the enforcement of the overall deadline, so a queryTimeout larger than the scanTimeout
        // cannot delay it. The monitor is the clock, so a wall-clock adjustment cannot move the deadline.
        long deadlineNanos = startNanos + settings.scanTimeout().toNanos();
        ActiveScan scan = new ActiveScan(scanId, startNanos, deadlineNanos);
        currentScanId = scanId;
        anchorDate = null;
        currentItemType = "";
        failureReason = "";
        finishedAt = null;
        startedAt = Instant.now();
        terminalPhase.set(null);
        synchronized (countersLock) {
            totalItemTypes = 0;
            completedCount = 0;
            failedCount = 0;
            partialCount = 0;
        }
        active.set(scan);
        // The gate was released - and only dead references left behind - by the drain that preceded this
        // call, so nothing tracked here can be alive. Clearing the remainder is bookkeeping, not a release:
        // see releaseGateIfDrained(), which is the one place allowed to open the gate.
        synchronized (threadsLock) {
            threads.clear();
            lingeringWatcher = null;
        }
        boolean started = false;
        Thread supervisor = null;
        try {
            Thread watchdog = new Thread(() -> watchdogLoop(scan),
                    THREAD_NAME_PREFIX + repositoryId + WATCHDOG_SUFFIX + "-" + scanId);
            watchdog.setDaemon(true);
            registerThread(watchdog);
            scan.watchdog = watchdog;
            // The watchdog is started BEFORE the supervisor so the absolute deadline governs the anchor
            // read, which runs on the supervisor thread before any worker exists.
            watchdog.start();
            supervisor = new Thread(() -> runScan(scan),
                    THREAD_NAME_PREFIX + repositoryId + SUPERVISOR_SUFFIX);
            supervisor.setDaemon(true);
            registerThread(supervisor);
            supervisor.start();
            started = true;
        } catch (Throwable failure) {
            // A thread that cannot start must not leave the coordinator wedged in "one scan in flight".
            // Whatever was registered is deregistered first, so the gate can then be released by the
            // ordinary path instead of being held by a thread reference that will never run.
            deregisterThread(supervisor);
            deregisterThread(scan.watchdog);
            scan.complete = true;
            // No supervisor is running, so nothing will ever start a worker for this scan: sealing here is
            // what lets the gate be released instead of being held by a thread reference that never runs.
            scan.sealed = true;
            finishScan(scan, null, startNanos, failure);
            releaseGateIfDrained();
        }
        if (!started) {
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

    /**
     * Waits until the scan's declared result is final AND its physical work has actually stopped.
     *
     * <p>Both halves matter: a scan whose deadline fired but whose worker is still alive is still in flight
     * by {@link #isScanInFlight()}, so waiting only for the phase would report completion while a thread of
     * the scan still runs. This therefore waits for the same event the gate release signals, and returns
     * {@code false} if that has not happened within the bound.
     */
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
     * connection stays leased. What such a worker CANNOT do is disappear from the record: its references
     * stay tracked, the one-scan gate stays latched and {@link #closeState()} reports
     * {@link CloseState#CLOSING} until it really exits - so the owning repository context reports
     * {@code CLOSING} (a recoverable refusal) rather than a clean shutdown or a permanent uncertainty.
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
        // A stubborn worker may still be alive. From here the lingering watcher owns the release, because
        // this thread must not perform it: the gate is released by the last scan thread to actually exit.
        startLingeringWatcher();
        // Bounded by the same deadline, and the watchdog exits on its own because `closed` is set and the
        // scan is terminal: this join is only here so a healthy scan's close() leaves no thread behind.
        joinWatched(scan == null ? null : scan.watchdog, deadline);
        releaseGateIfDrained();
    }

    // ------------------------------------------------------------------ the scan

    private void runScan(ActiveScan scan) {
        List<Thread> workers = new ArrayList<>();
        List<ItemTypeStatistics> results = null;
        try {
            results = visitFrozenItemTypes(scan, workers);
        } catch (Throwable failure) {
            // The coordinator itself failed. Nothing is published, and the reason is sanitized.
            scan.failure = reasonOf(failure);
        }
        try {
            if (joinWorkers(workers, scan.deadlineNanos)) {
                if (results == null) {
                    // A query outlived the deadline, so at least one item is unvisited: abort it and give
                    // the workers a bounded grace period to return their leases. A cancellation is NOT a
                    // timeout - it interrupts this thread too - so the clock is only blamed when the scan
                    // was not already cancelled. The terminal phase is decided by finishScan below, which
                    // applies the TIMED_OUT-over-CANCELLED precedence in one place.
                    if (!scan.cancellation.isCancelled() && !closed.get()) {
                        scan.deadlineExceeded.set(true);
                    }
                    cancelInternal(scan);
                }
                // A BOUNDED WAIT, not a release: if a worker is still alive when this expires, the scan
                // stays draining with its gate latched and the lingering watcher below releases it on that
                // worker's real exit. The return value is no longer discarded - it decides whether anyone
                // else must wait for a thread this supervisor can no longer join.
                joinWorkers(workers, System.nanoTime() + DRAIN_GRACE.toNanos());
            }
            finishScan(scan, results, scan.startNanos, null);
        } finally {
            // Runs on EVERY exit - normal return, each early `return null` of visitFrozenItemTypes (a
            // cancelled scan, a closed context, a failed frozen list, a failed or null anchor read) and an
            // Error - and the ORDER below is what makes the gate's release correct:
            //
            //   1. this thread stops being tracked, so it is one thread fewer that must be dead;
            //   2. `complete` stops the watchdog, and `sealed` records that no worker can ever be started
            //      for this scan again, which is what turns "no tracked thread is alive" into a proof;
            //   3. only then is the release attempted, so a supervisor that is the LAST thread of its scan
            //      releases the gate itself. Attempting it before sealing is precisely how the gate was left
            //      latched forever on the ordinary fast paths.
            deregisterThread(Thread.currentThread());
            signalScanProgressed();
            if (scan.watchdog != null) {
                scan.watchdog.interrupt();
            }
            if (terminalPhase.get() == null) {
                // Only reachable when finishScan itself threw. Leaving the scan without a terminal phase
                // would report RUNNING forever, so a sanitized failure is recorded instead.
                scan.failure = scan.failure == null
                        ? "the scan ended without a terminal phase" : scan.failure;
                finishScan(scan, null, scan.startNanos, null);
            }
            scan.complete = true;
            scan.sealed = true;
            releaseGateIfDrained();
            // Only a worker that is STILL alive needs watching; when there is none, this releases the gate
            // (a no-op if the call above already did) and spawns no thread at all.
            startLingeringWatcher();
        }
    }

    /**
     * The per-scan deadline watchdog: ONE bounded daemon thread that enforces the scan's absolute deadline
     * over everything the scan does, including the database anchor read that runs before any worker exists.
     *
     * <p>It exists because a deadline must be able to reach work that no worker is covering: the coordinator
     * owns the absolute deadline, this thread waits for it, and on expiry it sets the timeout fact, cancels
     * the scan's {@link ScanCancellation} (which runs the anchor's and every query's registered
     * {@code Statement.cancel()} action) and interrupts the scan's threads.</p>
     *
     * <p>It is not a scheduler and not a loop with a queue: one thread per ACTIVE scan, with no submission
     * step, so the peak is one. Its lifetime is exactly its scan's - it returns as soon as the deadline
     * expires, the scan is cancelled or closed, or the supervisor signals that the scan stopped, and the
     * wait is additionally bounded by a tick so a missed signal cannot park it forever. The supervisor's
     * signal is sent from a {@code finally}, so it is sent on the catastrophic path too (an {@link Error} or
     * an abnormal exit), and its thread is tracked so {@link #close()} can join it.</p>
     *
     * <p>Nothing here reads {@code statistics.query.timeout.seconds}: that remains an additional
     * per-statement cap applied by the JDBC layer, and the overall deadline below wins whether or not it is
     * larger.</p>
     */
    private void watchdogLoop(ActiveScan scan) {
        boolean deadlineElapsed = false;
        stateLock.lock();
        try {
            while (true) {
                // This loop leaves when the deadline expires or when something ELSE already stopped the
                // scan - a normal completion signalled by the supervisor's finally, an explicit
                // cancellation, or a context close.
                if (scan.complete) {
                    return;
                }
                if (scan.cancellation.isCancelled() || closed.get()) {
                    // An EXTERNAL stop. The deadline is deliberately NOT blamed: cancelInternal() runs the
                    // scan's abort actions and sets this same signal, so treating it as a timeout here would
                    // report every deadline as a cancellation. finishScan decides this case, and it can tell
                    // the two apart because deadlineExceeded is only ever set by the deadline path below.
                    return;
                }
                long remaining = scan.deadlineNanos - System.nanoTime();
                if (remaining <= 0L) {
                    deadlineElapsed = true;
                    break;
                }
                scanProgressed.await(
                        Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(WATCHDOG_TICK_MILLIS)),
                        TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException interrupted) {
            // The supervisor's own deadline path cancels the scan, which INTERRUPTS this thread - possibly
            // in the instant before it would have noticed the deadline itself. The interrupt is therefore not
            // an exit reason: the deadline fact below is re-read from the clock, so whichever of the two
            // threads gets there first, the deadline is recorded exactly once.
            Thread.currentThread().interrupt();
        } finally {
            stateLock.unlock();
            // This thread is tracked like every other thread of the scan, so it must take part in the
            // release exactly like every other one: it is frequently the LAST thread left, because the
            // supervisor's exit can precede it by one tick. Deregistering and then attempting the release -
            // the same two steps the workers and the supervisor take - is what lets the gate open in that
            // case instead of staying latched with nothing alive to justify it.
            deregisterThread(Thread.currentThread());
            releaseGateIfDrained();
        }
        // The deadline is decided from the CLOCK, not from how this thread was woken and not from the
        // cancellation signal. Testing the signal here is how the deadline became unobservable: the
        // supervisor's deadline join cancels the scan and interrupts this thread in the same instant, so the
        // signal was already set by the time it was read.
        //
        // The externality is therefore re-checked once more, at the END of the sequence: a stop that is
        // genuinely external must never be recorded as a timeout, even in the fraction of a millisecond
        // between the loop's last test and this one.
        boolean externallyStopped = closed.get() || scan.cancellation.isCancelled();
        boolean deadlineReached = !externallyStopped
                && (deadlineElapsed || System.nanoTime() >= scan.deadlineNanos);
        if (deadlineReached && !scan.complete) {
            // Latched BEFORE the phase is resolved, and before the cancel below marks the signal, so the
            // supervisor's finishScan can tell a genuine timeout from the cancellation this path causes.
            scan.resolutionLost.set(true);
            scan.deadlineExceeded.set(true);
            resolveTerminalPhase(scan, ScanStatus.Phase.TIMED_OUT,
                    "the overall scan deadline of " + settings.scanTimeoutSeconds()
                            + "s expired before every ItemType was visited; the previous snapshot is"
                            + " unchanged");
        }
        // Cancel the scan, whatever the reason: this runs the registered abort actions (the anchor's
        // Statement.cancel() among them) and interrupts every thread of the scan. It is the SAME signal and
        // the SAME mechanism an explicit cancelScan() and close() use.
        cancelInternal(scan);
        signalScanProgressed();
    }

    /**
     * Freezes the list, reads the anchor once, runs the workers and returns their results - or {@code null}
     * when the scan must not publish anything.
     */
    private List<ItemTypeStatistics> visitFrozenItemTypes(ActiveScan scan,
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
            // ItemType through one ScanWindows instance. It is handed the scan's own cancellation signal,
            // so the SAME mechanism that aborts an ItemType query - and the same signal the watchdog
            // fires at the deadline - reaches this read's Statement.cancel(). No worker exists yet, so
            // without that registration nothing would govern this call.
            anchor = engine.databaseCurrentDate(scan.cancellation);
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
            // The frozen list was visited vacuously: an empty snapshot is a complete answer. No worker is
            // started; the seal that makes the gate releasable is set by runScan's finally on every path.
            return List.of();
        }

        ItemTypeStatistics[] slots = new ItemTypeStatistics[frozen.size()];
        AtomicInteger index = new AtomicInteger();
        int workerCount = Math.max(1, Math.min(settings.workers(), frozen.size()));
        try (WorkerBatch batch = new WorkerBatch(scan, workers)) {
            for (int i = 0; i < workerCount; i++) {
                Thread worker = new Thread(
                        () -> workerLoop(scan, frozen, windows, slots, index),
                        THREAD_NAME_PREFIX + repositoryId + "-" + i);
                worker.setDaemon(true);
                // Tracked by the batch, which registers each worker BEFORE it is started and refuses to
                // register one at all once the scan is sealed: see WorkerBatch for why both halves matter.
                batch.start(worker);
            }
        }

        if (joinWorkers(workers, scan.deadlineNanos)) {
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
            // Again a bounded wait, not a release; see runScan.
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
                            AtomicInteger index) {
        try {
            workLoop(scan, frozen, windows, slots, index);
        } finally {
            // This thread stops being a worker of this scan here. It is ONE of the threads that may release
            // the gate, and using the same method every other exit path uses is what makes "released exactly
            // once" structural: the release is a compare-and-set plus an emptiness check, so when several
            // threads exit at once exactly one of them acts and none of them can act twice.
            //
            // This is the path that matters for a supervisor that was interrupted or died: a worker that
            // outlives it still releases the gate when it finally exits, so the coordinator cannot be left
            // latched forever with no thread left to unlatch it.
            deregisterThread(Thread.currentThread());
            signalScanProgressed();
            releaseGateIfDrained();
        }
    }

    private void workLoop(ActiveScan scan,
                          List<ItemTypeSummary> frozen,
                          ScanWindows windows,
                          ItemTypeStatistics[] slots,
                          AtomicInteger index) {
        Thread self = Thread.currentThread();
        while (true) {
            if (closed.get() || scan.cancellation.isCancelled() || self.isInterrupted()) {
                return;
            }
            int position = index.getAndIncrement();
            if (position >= frozen.size()) {
                return;
            }
            if (System.nanoTime() >= scan.deadlineNanos) {
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
     * <p>It deliberately does NOT release the one-scan gate and does NOT clear the tracked threads: a
     * terminal outcome decides publication and status, and proves nothing about whether the scan's threads
     * have stopped. Both of those happen in {@link #releaseGateIfDrained()}, when the last thread of this
     * scan has actually exited.</p>
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
        if (results != null && scan.isPublishable(closed.get())) {
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
        resolveTerminalPhase(scan, terminal, reason);
        currentItemType = "";
    }

    /**
     * Records the scan's terminal phase EXACTLY ONCE, whichever thread learns it first.
     *
     * <p>Two threads can legitimately be first: the supervisor (via {@link #finishScan}) and the watchdog
     * (which decides TIMED_OUT/CANCELLED when the deadline reaches work nothing else was covering, such as
     * the anchor read). The compare-and-set makes the first writer authoritative, so the watchdog's early
     * verdict cannot be overwritten by a later join - which is what lets the phase be final while
     * {@code isScanInFlight()} is still true, instead of the two facts contradicting each other.</p>
     *
     * <p>It writes the SAME reference {@link #progress()} reads, through the very field, so there is no
     * second copy of the phase that could drift out of step with the published one. The per-scan
     * {@link ActiveScan} does not hold a phase at all: its {@code complete} fact is what tells a scan's own
     * threads that the outcome is decided.</p>
     */
    private void resolveTerminalPhase(ActiveScan scan, ScanStatus.Phase terminal, String reason) {
        if (terminalPhase.compareAndSet(null, terminal)) {
            failureReason = reason == null ? "" : reason;
        }
    }

    /**
     * Releases the one-scan gate when - and only when - this scan has no tracked thread left alive.
     *
     * <p>The structural owner of "exactly once": {@link AtomicBoolean#compareAndSet} lets exactly one caller
     * past, and that caller is the one that also clears the tracked references, holding {@link #threadsLock}
     * so the emptiness check and the clear are one critical section. The tracked references are therefore
     * never cleared while any tracked scan thread is alive, and the gate is never released twice.</p>
     *
     * <p>It is called by every scan thread on its way out (a worker's {@code finally}, the supervisor's
     * {@code finally}) and by the lingering watcher while a worker that ignored cancellation stays alive. It
     * is cheap when there is nothing to do.</p>
     *
     * @return true when this call released the gate
     */
    private boolean releaseGateIfDrained() {
        synchronized (threadsLock) {
            ActiveScan scan = active.get();
            if (scan != null && !scan.sealed) {
                // Something of this scan - the supervisor running the anchor read above all - may yet start
                // a worker, so an empty tracked set is not proof that this scan is over. The supervisor sets
                // the fact from its own finally on EVERY exit path and calls this again there.
                return false;
            }
            if (workerBatchesOpen > 0) {
                // A worker batch is being started right now. The worker that will exist in a moment is not
                // in the tracked set yet, so neither is its absent liveness evidence.
                return false;
            }
            for (Thread thread : threads) {
                if (thread.isAlive()) {
                    // Something of this scan is still running, so the gate stays latched and every tracked
                    // reference stays exactly where it is. A new scan cannot start.
                    return false;
                }
            }
            if (!scanInFlight.compareAndSet(true, false)) {
                // Already released, or no scan ever ran: idempotent by construction, so a release can
                // never happen twice even when several threads call this at once.
                return false;
            }
            // Only DEAD references are dropped. Nothing can be alive here - that is what the check above
            // proved - so this cannot clear a live scan thread's reference, and the invariant "never clear a
            // tracked reference while its thread is alive" holds by construction rather than by review.
            threads.removeIf(thread -> !thread.isAlive());
            lingeringWatcher = null;
            stateLock.lock();
            try {
                scanFinished.signalAll();
            } finally {
                stateLock.unlock();
            }
            return true;
        }
    }

    /**
     * Ensures a watcher exists while a scan thread that ignored cancellation and interruption stays alive,
     * so the gate is released by that thread's real exit rather than by a bound.
     *
     * <p>At most ONE per scan and none when nothing is lingering, which keeps the peak at one extra daemon
     * thread. Its lifetime is exactly the lingering window: it returns as soon as the gate is released or
     * the coordinator is closed. It is never the thing that decides a phase or publishes anything - it only
     * waits, and calls the same idempotent release path every other thread calls.</p>
     */
    private void startLingeringWatcher() {
        synchronized (threadsLock) {
            if (lingeringWatcher != null) {
                return;
            }
            threads.removeIf(thread -> !thread.isAlive());
            // Only SCAN WORK justifies a watcher. The watchdog is the deadline enforcer and exits on its own
            // within one tick once the scan is complete, so treating it as "something is lingering" would
            // spawn a watcher for every ordinary scan - a thread whose only job would be to wait for a thread
            // that is already on its way out.
            if (threads.stream().noneMatch(ScanCoordinator::isScanWorkThread)) {
                // Nothing to watch: if the gate is somehow still latched, release it here rather than
                // spawn a thread that would exit immediately.
                releaseGateIfDrained();
                return;
            }
            Thread watcher = new Thread(this::awaitLingeringThreads,
                    THREAD_NAME_PREFIX + repositoryId + LINGERING_SUFFIX);
            watcher.setDaemon(true);
            lingeringWatcher = watcher;
            threads.add(watcher);
            watcher.start();
        }
    }

    /** The lingering watcher's body: wait for every OTHER tracked scan thread to die, then release. */
    private void awaitLingeringThreads() {
        Thread self = Thread.currentThread();
        try {
            while (true) {
                if (!scanInFlight.get() || closed.get()) {
                    // The gate is already released, or the coordinator is going away: either way there is
                    // nothing left to wait for, and this thread must not outlive its scan.
                    return;
                }
                boolean anyAlive = false;
                synchronized (threadsLock) {
                    for (Thread thread : threads) {
                        // SELF IS SKIPPED. This thread is tracked like any other, so counting itself as
                        // live scan work would make the loop wait for its own termination and never
                        // release the gate - the "released never" failure mode, reached through the
                        // normal hostile-worker path.
                        if (thread != self && thread.isAlive()) {
                            anyAlive = true;
                            break;
                        }
                    }
                    if (!anyAlive) {
                        // No thread of this scan is left alive, so the gate can be released. The release
                        // is the same idempotent compare-and-set every other exit path uses.
                        break;
                    }
                    waitQuietly(threadsLock, WATCHDOG_TICK_MILLIS);
                }
            }
        } finally {
            // Both the break and the early return land here: deregistering this thread BEFORE releasing is
            // what lets the release see a genuinely empty tracked set, and doing it in a finally means no
            // exit path can skip it.
            deregisterThread(self);
            releaseGateIfDrained();
        }
    }

    /** Bounded, interrupt-tolerant wait on a monitor: never blocks a caller that needs the monitor. */
    private static void waitQuietly(Object monitor, long millis) {
        try {
            monitor.wait(Math.max(1L, millis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Holds a bounded join of one coordinator thread, or returns immediately when it is absent or dead. */
    private static void joinWatched(Thread thread, long deadlineNanos) {
        if (thread == null || thread == Thread.currentThread() || !thread.isAlive()) {
            return;
        }
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0L) {
            return;
        }
        try {
            thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Wakes the watchdog and everyone waiting on the scan's progression; never blocks the caller. */
    private void signalScanProgressed() {
        stateLock.lock();
        try {
            scanProgressed.signalAll();
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * True for a thread that performs the scan's work: its supervisor or one of its workers.
     *
     * <p>The watchdog and the lingering watcher are the coordinator's own bookkeeping threads; they are
     * tracked so that {@code close()} can join them and so that the gate's emptiness check waits for them to
     * finish, but they are not the scan's work and must never be reported as such.</p>
     */
    private static boolean isScanWorkThread(Thread thread) {
        String name = thread.getName();
        return !name.contains(WATCHDOG_SUFFIX) && !name.contains(LINGERING_SUFFIX);
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
     *
     * <p>This is ONE mechanism for three triggers - the overall deadline (the watchdog), an explicit
     * cancellation and a context close - so the database anchor read registers its abort action on the same
     * terms as every ItemType query and is reached by all three.</p>
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

    /**
     * Tracks and starts one batch of scan workers, holding a guard that makes the "no worker can appear after
     * the gate could open" invariant CHECKED rather than merely intended.
     *
     * <p>Two things have to be true together, and they are the two halves of the same window:</p>
     *
     * <ul>
     *   <li>each worker is registered BEFORE it is started, so it is already tracked as work of this scan
     *       during the instant between the two. Leaving that instant untracked would let the gate open while
     *       a worker was about to run;</li>
     *   <li>the guard is held for the whole batch, and {@link #releaseGateIfDrained()} refuses while any
     *       such guard is open. Without it the gate could be released between two registrations - when every
     *       registered thread happened to have exited and the next one did not exist yet - which is the same
     *       overlap reached one worker earlier.</li>
     * </ul>
     *
     * <p>Registering after the scan is sealed throws: at that point an empty tracked set is what releases the
     * gate, so a worker registered afterwards could be dispatched while a new scan was already starting. A
     * future edit that adds such a registration should break loudly rather than reintroduce the overlap.</p>
     */
    private final class WorkerBatch implements AutoCloseable {

        private final ActiveScan scan;
        private final List<Thread> published;
        private final List<Thread> started = new ArrayList<>();

        private WorkerBatch(ActiveScan scan, List<Thread> published) {
            this.scan = scan;
            this.published = published;
            synchronized (threadsLock) {
                if (scan.sealed) {
                    throw new IllegalStateException("a worker batch was opened after the scan was sealed: the"
                            + " one-scan gate could have been released while a worker was starting");
                }
                workerBatchesOpen++;
            }
        }

        private void start(Thread worker) {
            synchronized (threadsLock) {
                if (scan.sealed) {
                    throw new IllegalStateException("a scan worker was registered after the scan was sealed:"
                            + " the one-scan gate could have been released while this worker was starting");
                }
                threads.add(worker);
                started.add(worker);
            }
            worker.start();
        }

        @Override
        public void close() {
            synchronized (threadsLock) {
                workerBatchesOpen--;
                published.addAll(started);
            }
        }
    }

    private void deregisterThread(Thread thread) {
        if (thread == null) {
            return;
        }
        synchronized (threadsLock) {
            threads.remove(thread);
            threadsLock.notifyAll();
        }
    }

    private List<Thread> threadsSnapshot() {
        synchronized (threadsLock) {
            return List.copyOf(threads);
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
        /** When this scan started, from the monitor's clock; the snapshot's duration is measured from it. */
        private final long startNanos;
        /** THE absolute deadline of this scan, from the monitor's clock. Owned here, enforced once. */
        private final long deadlineNanos;
        /** Set when the deadline left at least one frozen ItemType unvisited. */
        private final AtomicBoolean deadlineExceeded = new AtomicBoolean();
        /**
         * Set when this scan's terminal phase was decided by an EXTERNAL stop - the deadline firing or the
         * coordinator being closed - rather than by the scan simply finishing. It is read in
         * {@link #isPublishable} so a results list that happened to be complete cannot be published by a
         * scan that was already declared over.
         */
        private final AtomicBoolean resolutionLost = new AtomicBoolean();
        /**
         * True once the supervisor has stopped handing work out - i.e. no worker will ever be started for
         * this scan again.
         *
         * <p>It closes the one window in which "no tracked thread is alive" would be a LIE: while the
         * supervisor is still creating its workers, a worker it has not started yet is work that is about to
         * happen, so the gate must not open in that instant.</p>
         *
         * <p>It is set from {@code runScan}'s {@code finally}, so it is set on EVERY exit path - the normal
         * one, each early {@code return null} of {@code visitFrozenItemTypes} (a cancelled scan, a closed
         * context, a failed frozen list, a failed or null anchor read) and an {@link Error}. That is
         * deliberate: a per-path marker would release the gate on today's paths and latch it forever on the
         * first new one, and "released never" bricks repository switching just as badly as "released too
         * early" overlaps two scans. Note that sealing does NOT release the gate by itself - the gate still
         * opens only when the tracked-thread set is empty, and the supervisor deregisters itself in the same
         * {@code finally} before sealing.</p>
         */
        private volatile boolean sealed;
        /** The deadline watchdog of this scan; registered with the scan's threads and tracked for close(). */
        private volatile Thread watchdog;
        /** True once the outcome has been decided; also the watchdog's reason to stop. */
        private volatile boolean complete;
        /** A sanitized catastrophic reason; written by one thread and read by the supervisor. */
        private volatile String failure;

        private ActiveScan(long scanId, long startNanos, long deadlineNanos) {
            this.scanId = scanId;
            this.startNanos = startNanos;
            this.deadlineNanos = deadlineNanos;
        }

        /**
         * Whether this scan may publish the results it collected, given the coordinator's closed flag.
         *
         * <p>A scan may publish only when it visited the whole frozen list AND nothing stopped it: not a
         * context close, not a cancellation, not the deadline, not a coordinator failure, and not an
         * outcome some other thread already declared over. Kept as one predicate so publication and the
         * terminal phase can never disagree.</p>
         */
        private boolean isPublishable(boolean closed) {
            return !closed
                    && !cancellation.isCancelled()
                    && !deadlineExceeded.get()
                    && !resolutionLost.get()
                    && failure == null;
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
