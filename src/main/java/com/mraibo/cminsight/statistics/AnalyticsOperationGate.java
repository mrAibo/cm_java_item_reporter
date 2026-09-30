package com.mraibo.cminsight.statistics;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one exclusive analytics-operation arbiter for a repository context.
 *
 * <h2>Why this exists as a separate type</h2>
 *
 * <p>A full scan and a targeted single-ItemType refresh both run analytics SQL against the repository
 * database, so the goal requires that they never overlap in one {@code RepositoryContext}. The full scan
 * already had an in-flight latch of its own, but a second, independent latch in the targeted path would be
 * a gate that <em>looks</em> like one gate and is really two - and two gates that can disagree are how an
 * overlap becomes reachable again. So the latch is one object, shared by both operations.
 *
 * <h2>What it does and does not guarantee</h2>
 *
 * <p>It guarantees <strong>mutual exclusion</strong>: at most one analytics operation of any kind is
 * admitted at a time, and the second caller is refused immediately rather than queued. It deliberately
 * does NOT prove that the previous operation's physical work has stopped - that is the far stronger
 * property the scan coordinator's generation ownership provides, and it stays there. This gate decides
 * <em>admission</em>; the coordinator decides <em>physical termination</em>.
 *
 * <p>That division matters for the targeted path: a targeted refresh released this gate as soon as it
 * returns, but a full scan whose worker is still draining keeps it held until its own gate release, so a
 * targeted refresh cannot slip in behind a scan that has not physically finished.
 *
 * <h2>Ownership and identity</h2>
 *
 * <p>One instance belongs to one {@code RepositoryContext} and is discarded with it, so a stale operation
 * from a previous repository can never hold or release the new repository's gate. {@link #release(String)}
 * takes the owner token that {@link #tryAcquire(String)} returned, so a late release from an unrelated
 * caller is refused rather than silently opening the gate for work that is still running.
 */
public final class AnalyticsOperationGate {

    /** What kind of analytics work holds the gate, for diagnostics and for a refusal message. */
    public enum Operation {
        /** A full multi-ItemType scan. */
        FULL_SCAN("full scan"),
        /** A targeted single-ItemType refresh. */
        TARGETED_REFRESH("targeted refresh");

        private final String label;

        Operation(String label) {
            this.label = label;
        }

        /** A short human-readable label, safe to publish. */
        public String label() {
            return label;
        }
    }

    private final AtomicReference<Holder> holder = new AtomicReference<>();

    /**
     * Tries to become the exclusive analytics operation.
     *
     * @param operation what is being started, for the refusal message and for diagnostics
     * @param ownerToken an identity unique to this attempt, returned by the acquire and required by the
     *                   release so only the holder can open the gate
     * @return empty when the caller now holds the gate; otherwise the operation currently holding it
     */
    public Optional<Operation> tryAcquire(Operation operation, String ownerToken) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(ownerToken, "ownerToken");
        Holder candidate = new Holder(operation, ownerToken);
        if (holder.compareAndSet(null, candidate)) {
            return Optional.empty();
        }
        Holder current = holder.get();
        return Optional.of(current == null ? operation : current.operation);
    }

    /**
     * Releases the gate, but only for the holder that acquired it.
     *
     * <p>A release from any other token is ignored rather than honoured. The alternative - releasing on any
     * call - would let a late completion from a replaced repository context open the gate while the current
     * operation is still running, which is precisely the overlap this type exists to prevent.
     *
     * @return true when this call released the gate, false when it was not the holder
     */
    public boolean release(String ownerToken) {
        if (ownerToken == null) {
            return false;
        }
        Holder current = holder.get();
        if (current == null || !current.ownerToken.equals(ownerToken)) {
            return false;
        }
        return holder.compareAndSet(current, null);
    }

    /** True when some analytics operation currently holds the gate. */
    public boolean isHeld() {
        return holder.get() != null;
    }

    /** The operation currently holding the gate, or empty when it is free. */
    public Optional<Operation> currentOperation() {
        Holder current = holder.get();
        return current == null ? Optional.empty() : Optional.of(current.operation);
    }

    /** A short value-free description for diagnostics: what is running, or that nothing is. */
    public String describe() {
        Holder current = holder.get();
        return current == null ? "idle" : current.operation.label() + " in flight";
    }

    @Override
    public String toString() {
        return "AnalyticsOperationGate[" + describe() + "]";
    }

    /** The current holder: what it is doing and which attempt owns it. */
    private record Holder(Operation operation, String ownerToken) {
    }
}
