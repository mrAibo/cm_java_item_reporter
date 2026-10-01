package com.mraibo.cminsight.statistics;

/**
 * The outcome of asking for a scan.
 *
 * <p>A return value rather than an exception, because "a scan is already running" is an ordinary,
 * expected answer and not an error: the HTTP layer maps it to a deterministic conflict instead of a
 * failure, and a caller that only wants "make it fresh" can ignore the distinction. Making it a value also
 * removes any temptation to probe {@code isScanInFlight()} first and then request - a check-then-act that
 * would race with a second caller and start two scans.
 */
public enum ScanStartResult {

    /** A new scan was started. Exactly one scan runs as a result of this call. */
    STARTED,

    /** A scan is already in flight; nothing was started and nothing was changed. */
    ALREADY_RUNNING,

    /** The analytics half cannot serve a scan; see {@code availability().reason()}. */
    UNAVAILABLE,

    /** The owning repository context is closed, or is closing. */
    CLOSED
}
