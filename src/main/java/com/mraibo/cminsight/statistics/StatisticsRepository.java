package com.mraibo.cminsight.statistics;

import com.mraibo.cminsight.metadata.ItemTypeSummary;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    // ---------------------------------------------------------------- freshness

    /**
     * The freshness judgement of the latest completed snapshot, taken now.
     *
     * <p>A read, not a refresh: it is a pure function of the snapshot's own {@code capturedAt} and the
     * configured threshold, so calling it can never start database work and never discards what it judges.
     * A stale snapshot stays visible - {@link Freshness#stale()} is reported beside the value, and
     * {@link #snapshot()} keeps returning it.
     */
    default Freshness freshness() {
        return freshness(Instant.now());
    }

    /**
     * The same judgement at a caller-supplied instant, so a payload and its diagnostics can describe one
     * instant.
     *
     * <p>The default judges against the documented default threshold and {@link Freshness#none} when no
     * snapshot exists. An implementation that knows the CONFIGURED threshold overrides this - the
     * statistics service does - so a repository never reports a threshold the operator did not set.
     */
    default Freshness freshness(Instant now) {
        return FreshnessThreshold.defaults().judge(snapshot(), now);
    }

    // ---------------------------------------------------------------- targeted single-ItemType refresh

    /**
     * Refreshes ONE ItemType under the context's shared analytics-operation gate.
     *
     * <p>Synchronous: it returns when the measurement was published or refused, so there is no second
     * "is it done" endpoint. The result is SEPARATE immutable detail data keyed by the ItemType id, with
     * its own capture instant and its own database anchor; it never becomes part of a
     * {@link StatisticsSnapshot}, never changes a total and is never persisted as history. A caller that
     * finds the outcome empty of a detail can render the previously published one, which this call does not
     * touch when it fails or is refused.
     *
     * <p>The default refuses for every implementation without the capability - an unavailable or disabled
     * service, or a bare coordinator - rather than pretending to start work.
     */
    default TargetedRefreshResult refreshItemType(int itemTypeId) {
        return TargetedRefreshResult.unavailable(itemTypeId,
                "this analytics service has no targeted single-ItemType refresh capability");
    }

    /** The published targeted detail for one ItemType, or empty when this context has none. */
    default Optional<TargetedItemTypeDetail> targetedDetail(int itemTypeId) {
        return Optional.empty();
    }

    /** Every published targeted detail in this context, in ItemType id order; a bounded, copied list. */
    default List<TargetedItemTypeDetail> targetedDetails() {
        return List.of();
    }

    /**
     * True while a targeted single-ItemType refresh is running in this context.
     *
     * <p>A diagnostics fact, not a gate: it never refuses work and never decides admission. It is asked by
     * the System/Diagnostics view, which must be able to say that an analytics operation is in flight even
     * though a targeted refresh is not a scan.
     */
    default boolean isTargetedRefreshInFlight() {
        return false;
    }

    /** How many ItemTypes have a published targeted detail in this context; zero when there is none. */
    default int targetedDetailCount() {
        return 0;
    }
}
