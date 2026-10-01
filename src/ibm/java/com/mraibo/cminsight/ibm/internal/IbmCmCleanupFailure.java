package com.mraibo.cminsight.ibm.internal;

/**
 * The teardown of one physical CM session did not provably complete, which is the difference between a
 * released capacity slot and a quarantined one.
 *
 * <h2>Why this needed its own type</h2>
 *
 * <p>{@link com.mraibo.cminsight.connection.BoundedPool} decides whether a retired session frees its
 * configured slot by watching whether {@code close()} <em>returned</em>. Any exception means "the
 * physical session may still exist", so the slot is quarantined - the safe direction, but a blunt one.
 * During activation the adapter needs a finer distinction than "something was thrown", because it has to
 * decide between {@link com.mraibo.cminsight.connection.CreationFailure.Cleanup#PROVEN_CLEAN} and
 * {@link com.mraibo.cminsight.connection.CreationFailure.Cleanup#UNPROVEN} for a session it is throwing
 * away. Guessing "clean" there is the one mistake that would let the pool exceed its hard bound.
 *
 * <p>An instance of this type is thrown ONLY when {@code destroy()} did not return normally. That is
 * deliberately narrower than "a teardown step threw": {@link #releaseQuietly(IcmDatastore)} explains why a
 * failed {@code disconnect()} is evidence about the server-side session but not about the physical
 * resource the capacity slot accounts for, and the review of Goal 02 required the verdict to follow IBM's
 * documented lifecycle rather than the pessimistic conjunction of both steps.
 *
 * <h2>One release routine, two callers</h2>
 *
 * <p>{@link #releaseQuietly(IcmDatastore)} is the single implementation of the documented release order
 * ({@code isConnected} -> {@code disconnect} when connected -> {@code destroy} always) and of the verdict
 * that follows from it. The two places that release a datastore - a failed
 * {@code IbmCmConnectionFactory} connect and a normal {@link IbmCmSession#close()} - call it, so the two
 * lifecycle paths cannot drift apart and a reviewer has one method to check instead of two.
 *
 * <p>It takes the {@link IcmDatastore} seam rather than the vendor's concrete {@code DKDatastoreICM}, which
 * is also what makes the rule testable without a server: the IBM test suite drives it with a fake handle
 * that counts {@code disconnect()}/{@code destroy()} calls and can make any step fail.
 *
 * <p>The message is already sanitised by {@link IbmErrorSanitizer}: a step name and a category, never a
 * vendor message verbatim and never any part of a credential.
 */
public final class IbmCmCleanupFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Which teardown step could not be proven, for example {@code destroy}. */
    private final String step;

    public IbmCmCleanupFailure(String step, String message, Throwable cause) {
        super(message, cause);
        this.step = step == null ? "cleanup" : step;
    }

    public String step() {
        return step;
    }

    /**
     * What one release attempt observed: whether it proved cleanup, and the sanitised problems the steps
     * reported.
     *
     * <p>A record rather than a boolean so a caller CANNOT keep the verdict while dropping the diagnostics:
     * the disconnect problem after a failed connect is the thing a reviewer insisted must survive, and a
     * second return value is the only way to make losing it impossible.
     *
     * @param destroyProven true when {@code destroy()} returned normally. This is the ONLY cleanup proof
     *                      this adapter accepts - see {@link #releaseQuietly(IcmDatastore)}
     * @param diagnostics   sanitised one-line descriptions of every step that reported a problem, joined
     *                      with {@code "; "}; empty when every step returned normally
     */
    record Teardown(boolean destroyProven, String diagnostics) {

        /** The sanitised problems, or an empty string. Never {@code null}. */
        @Override
        public String diagnostics() {
            return diagnostics == null ? "" : diagnostics;
        }

        /** {@code "; "} plus the problems, or an empty string, for appending to a sanitised message. */
        String diagnosticSuffix() {
            String problems = diagnostics();
            return problems.isEmpty() ? "" : "; " + problems;
        }
    }

    /**
     * Releases one datastore in IBM's documented order and reports whether that release is proven.
     *
     * <h2>The documented order, and why it is conditional</h2>
     *
     * <p>IBM CM 8.7 documents ({@code com.ibm.mm.sdk.server.DKDatastoreICM}, "Establishing a connection"):
     *
     * <ul>
     *   <li>{@code isConnected()} is true only when {@code connect()} was called and COMPLETED
     *       SUCCESSFULLY. It is not a communication liveness check, which is exactly why it is the right
     *       question here: it says whether a logical session was ever established;</li>
     *   <li>{@code destroy()} destroys the datastore object and "performs the datastore cleanup if
     *       needed";</li>
     *   <li>the normal successful lifecycle is {@code connect} -> {@code disconnect} -> {@code destroy}.</li>
     * </ul>
     *
     * <p>So the steps are: ask {@code isConnected()} - OUTSIDE any pool lock, which holds because both
     * callers run on the thread that owns the datastore and never under
     * {@link com.mraibo.cminsight.connection.BoundedPool}'s lock; when the answer is false, do NOT call
     * {@code disconnect()} merely to manufacture a second error; when it is true, attempt
     * {@code disconnect()}; when the answer cannot be read at all, take the conservative path and attempt
     * {@code disconnect()} anyway; and ALWAYS attempt {@code destroy()}, whatever happened before it.
     *
     * <h2>The verdict, and the decision behind it</h2>
     *
     * <p><strong>A successful {@code destroy()} IS cleanup proof, even when an earlier {@code disconnect()}
     * reported an error.</strong> That is an explicit, documented decision and not an accident of coding:
     *
     * <ul>
     *   <li>the two steps prove different claims. {@code disconnect()} proves the SERVER-SIDE session
     *       ended; {@code destroy()} releases the LOCAL SDK object and, per the documentation above,
     *       performs the datastore cleanup if needed.</li>
     *   <li>the capacity slot this class's exception guards accounts for the PHYSICAL datastore. Once
     *       {@code destroy()} has returned normally, the cleanup the slot is waiting for has happened, and
     *       treating a failed {@code disconnect()} as an open question would permanently quarantine an
     *       ordinary failed login that never established a session - degrading the pool without protecting
     *       the bound.</li>
     *   <li>the failure direction is preserved: a {@code destroy()} that does NOT return normally leaves
     *       cleanup unproven and the slot is quarantined, REGARDLESS of the disconnect result, because
     *       nothing else can stand in for the documented cleanup step.</li>
     *   <li>the disconnect problem is not dropped: it travels in {@link Teardown#diagnostics()} and reaches
     *       {@code lastAdapterError} through the caller's sanitised message.</li>
     * </ul>
     *
     * @param handle the datastore to release; never {@code null}
     * @return whether {@code destroy()} proved cleanup, plus the sanitised problems observed
     */
    static Teardown releaseQuietly(IcmDatastore handle) {
        StringBuilder problems = new StringBuilder();

        boolean connected;
        try {
            connected = handle.isConnected();
        } catch (RuntimeException | Error unreadable) {
            // 4. the answer cannot be read: take the conservative path and attempt the disconnect. Skipping
            // it would leave a possibly-live server-side session unreferenced, whereas attempting it costs
            // nothing that this verdict does not already tolerate.
            connected = true;
        }

        if (connected) {
            try {
                handle.disconnect();
            } catch (Exception | Error disconnectFailure) {
                // Expected after a failed connect, where there was never a completed connection to close.
                // Recorded as diagnostics - a server-side symptom an operator should see - but NOT allowed
                // to decide the verdict; destroy() does that below.
                append(problems, IbmErrorSanitizer.describe("disconnect", disconnectFailure));
            }
        }

        boolean destroyProven;
        try {
            handle.destroy();
            destroyProven = true;
        } catch (Exception | Error destroyFailure) {
            destroyProven = false;
            append(problems, IbmErrorSanitizer.describe("destroy", destroyFailure));
        }
        return new Teardown(destroyProven, problems.toString());
    }

    /** Joins one sanitised problem onto the diagnostic text, skipping blanks. */
    private static void append(StringBuilder problems, String sanitisedProblem) {
        if (sanitisedProblem == null || sanitisedProblem.isBlank()) {
            return;
        }
        if (!problems.isEmpty()) {
            problems.append("; ");
        }
        problems.append(sanitisedProblem);
    }

    @Override
    public String toString() {
        return "IbmCmCleanupFailure[step=" + step + ", message=" + getMessage() + "]";
    }
}
