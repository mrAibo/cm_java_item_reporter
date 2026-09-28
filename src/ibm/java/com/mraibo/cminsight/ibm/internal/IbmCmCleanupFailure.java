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
 * <p>An instance of this type is thrown ONLY when a {@code disconnect()} or {@code destroy()} step
 * actually threw. The presence of this type is therefore the evidence, and its construction site in
 * {@link IbmCmSession} is the only one.
 *
 * <p>The message is already sanitised by {@link IbmErrorSanitizer}: a step name and a category, never a
 * vendor message verbatim and never any part of a credential.
 */
public final class IbmCmCleanupFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Which teardown step could not be proven, for example {@code disconnect} or {@code destroy}. */
    private final String step;

    public IbmCmCleanupFailure(String step, String message, Throwable cause) {
        super(message, cause);
        this.step = step == null ? "cleanup" : step;
    }

    public String step() {
        return step;
    }

    @Override
    public String toString() {
        return "IbmCmCleanupFailure[step=" + step + ", message=" + getMessage() + "]";
    }
}
