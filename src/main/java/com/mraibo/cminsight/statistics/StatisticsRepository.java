package com.mraibo.cminsight.statistics;

import com.mraibo.cminsight.metadata.ItemTypeSummary;

import java.time.Duration;
import java.util.Optional;

/**
 * The read/refresh contract of the analytics half, as the API layer and any other consumer sees it.
 *
 * <h2>Read means "what is known now", never "start something"</h2>
 *
 * <p>{@link #snapshot()}, {@link #progress()} and {@link #diagnostics()} are cheap, non-blocking reads.
 * Starting work is a separate, explicit act ({@link #requestScan()}) because a refresh changes local process
 * state and, in the HTTP layer, requires the CSRF-resistant action header. A read path can therefore never
 * trigger a database scan as a side effect of rendering a page.
 *
 * <p>{@link #requestScan()} starts AT MOST ONE scan and answers with a value rather than an exception:
 * {@link ScanStartResult#ALREADY_RUNNING} is the deterministic conflict a second concurrent refresh gets,
 * with zero side effects. That is deliberately not a boolean: "already running", "unavailable" and "closed"
 * are three different answers with three different HTTP meanings.
 *
 * <h2>Unavailable is not an error state</h2>
 *
 * <p>When the feature is off, the driver is missing, the credential is unreadable or the database is
 * unreachable, this interface stays constructible and answers honestly: {@link #availability()} explains
 * why, {@link #snapshot()} is empty until a scan ever completes, and nothing about the IBM CM metadata or
 * retention halves changes.
 *
 * <h2>No JDBC object crosses this boundary</h2>
 *
 * <p>Pool facts reach a consumer as plain numbers through {@link #diagnostics()}. That is why the
 * diagnostics view belongs on this interface rather than on a pool type the web layer would have to hold:
 * there is exactly one safe shape, and no route can obtain a {@code Connection}, {@code Statement} or
 * {@code ResultSet} to leak.
 */
public interface StatisticsRepository {

    /** Whether a scan may be requested, and why not when it may not. */
    StatisticsAvailability availability();

    /** Convenience over {@link #availability()}: true only when a scan may be requested. */
    default boolean available() {
        return availability().available();
    }

    /** The sanitized reason the analytics half cannot serve a scan, or an empty string when it can. */
    default String unavailableReason() {
        return availability().reason();
    }

    /**
     * The latest COMPLETED snapshot, or empty when none was ever published.
     *
     * <p>Never a partial object: while a scan runs this keeps returning the PREVIOUS completed snapshot.
     */
    Optional<StatisticsSnapshot> snapshot();

    /** The current scan status, or the last scan's terminal status. Never {@code null}. */
    ScanStatus progress();

    /** Alias of {@link #progress()} for callers that name the DTO by its type. */
    default ScanStatus scanStatus() {
        return progress();
    }

    /** True while a scan is in flight. */
    boolean isScanInFlight();

    /**
     * Starts one scan unless one is already running.
     *
     * <p>Returns immediately: the work happens on the coordinator's bounded workers.
     */
    ScanStartResult requestScan();

    /** Cancels the running scan, best-effort. Returns true when a scan was actually signalled. */
    boolean cancelScan();

    /**
     * Waits until no scan is in flight, up to {@code timeout}.
     *
     * @return true when no scan is running (the last one is terminal, published or not)
     */
    boolean awaitScanCompletion(Duration timeout) throws InterruptedException;

    /**
     * The safe, value-only view of the analytics JDBC pool and the scan.
     *
     * <p>Never {@code null}, and never contains a JDBC object, URL, user name, schema or raw driver
     * message: when analytics is disabled or has no pool, this reports that fact with zero counters.
     */
    StatisticsDiagnostics diagnostics();
}
