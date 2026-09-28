package com.mraibo.cminsight.core;

import java.time.Duration;
import java.util.Optional;

/**
 * Vendor-neutral diagnostics for the CM session pool of one active repository.
 *
 * <h2>Value-free by contract</h2>
 *
 * <p>Everything reachable from this interface is a count, a state, a duration or a version. There is
 * deliberately no accessor for a connection string, a user name, a password, a session handle or a raw
 * SDK message: an operator needs to know <em>what</em> is wrong, and the cheapest way to guarantee that
 * a credential cannot be exposed through diagnostics is for diagnostics to have nowhere to put one.
 * {@link #lastAdapterError()} is the single free-text accessor and its contract is that the text is
 * already sanitised by the adapter - a class name and a category, never a vendor message verbatim and
 * never any part of a credential.
 *
 * <h2>Cheap reads only</h2>
 *
 * <p>Every method must be cheap and non-blocking. This type is read by the diagnostics endpoint and by
 * the health of the console, but it is also a sibling of the machinery that decides whether a
 * repository switch may proceed - a diagnostics read that blocked on the network would delay a
 * lifecycle decision for no reason. An implementation therefore reports snapshots it already holds.
 */
public interface CmPoolDiagnostics {

    /** Label of the pool, for a log line or a diagnostics page. */
    String poolName();

    /** The configured hard maximum number of live CM sessions. Never exceeded. */
    int configuredSize();

    /**
     * Capacity slots currently consumed.
     *
     * <p>Invariant, for every implementation: this equals
     * {@code available + leased + creating + retiring + quarantined}, and is never greater than
     * {@link #configuredSize()}.
     */
    int capacityInUse();

    int available();

    int leased();

    /** Sessions being created right now; a slot is reserved for each. */
    int creating();

    /** Sessions being retired right now; a slot stays reserved until the close finishes. */
    int retiring();

    /**
     * Slots whose physical outcome is unproven: a close that did not return normally, or a creation
     * failure that could not prove its cleanup. Those slots stay consumed for the lifetime of the pool,
     * so a non-zero value means the pool is running below its configured capacity.
     */
    int quarantined();

    long createAttempts();

    long created();

    /** Creation attempts that did not produce a resource. */
    long createFailures();

    /** Creation attempts whose failure reported an UNPROVEN cleanup, so a slot was quarantined. */
    long createQuarantineFailures();

    long closeAttempts();

    long closeSuccesses();

    long closeFailures();

    long borrowCount();

    long borrowTimeoutCount();

    /** Mean time a borrow waited for a session, across every attempt. */
    double averageBorrowWaitMillis();

    /** Longest single borrow wait observed. */
    double maxBorrowWaitMillis();

    /** The shutdown state of the pool, derived from the same lock that authorises every capacity change. */
    CloseState closeState();

    /** Age of the oldest live session, or empty when the pool holds none. */
    Optional<Duration> oldestSessionAge();

    /** True when at least one slot is quarantined, i.e. the pool is below capacity for good. */
    boolean degraded();

    /**
     * The last adapter failure, already sanitised, or empty when none has occurred.
     *
     * <p>Value-free by contract: a category and a class name, never a vendor message verbatim and never
     * any part of a credential. Implementations must return {@link Optional#empty()} rather than a
     * message they have not sanitised.
     */
    Optional<String> lastAdapterError();
}
