package com.mraibo.cminsight.statistics;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of one targeted single-ItemType refresh attempt.
 *
 * <h2>A value, not an exception</h2>
 *
 * <p>Every outcome below is an ordinary, expected answer: another analytics operation holding the shared
 * gate is a deterministic conflict, an ItemType id that is not in the active metadata is a bad request, and
 * a repository whose analytics half is disabled is a configuration state. Returning a value rather than
 * throwing keeps the HTTP layer's mapping total and keeps a caller from probing state first and then
 * acting - the check-then-act that would race with a second caller.
 *
 * <h2>The gate is the only reason for {@link Outcome#REFUSED}</h2>
 *
 * <p>{@code REFUSED} means exactly one thing: {@link AnalyticsOperationGate#tryAcquire} refused because a
 * full scan or another targeted refresh already holds the context's one arbiter. The attempt was refused,
 * never queued, and nothing was read or changed - {@link #refusedBy()} names which operation held the gate,
 * so an operator is told "a full scan is in flight" rather than a bare conflict.
 *
 * <h2>Nothing is published unless the outcome is {@link Outcome#REFRESHED}</h2>
 *
 * <p>The three stopping outcomes - {@code CANCELLED}, {@code CLOSED}, {@code FAILED} - publish no detail
 * data at all, following the same publication rule a scan follows: a cancelled or failed attempt must not
 * leave a half-answer behind for a later reader to find. {@link #detail()} is therefore non-null exactly
 * when {@link #published()} is true, and the compact constructor enforces that pairing instead of leaving
 * it to a caller's discipline.
 *
 * @param outcome   what happened
 * @param itemTypeId the ItemType identity the attempt was for
 * @param reason    sanitized, value-free explanation; empty for {@link Outcome#REFRESHED}
 * @param detail    the published detail result, or {@code null} when nothing was published
 * @param refusedBy the operation that held the gate, or {@code null} when the gate was free
 */
public record TargetedRefreshResult(Outcome outcome,
                                    int itemTypeId,
                                    String reason,
                                    TargetedItemTypeDetail detail,
                                    AnalyticsOperationGate.Operation refusedBy) {

    /** How a targeted refresh attempt ended. */
    public enum Outcome {

        /**
         * The ItemType was measured and its detail result was published.
         *
         * <p>Exactly one database anchor was read and exactly one aggregate ran, under the shared gate, and
         * the result carries its own {@code capturedAt} and anchor. Nothing about the published full
         * snapshot or its totals changed.
         */
        REFRESHED,

        /**
         * Another analytics operation - a full scan or another targeted refresh - holds the shared gate.
         *
         * <p>Deterministic, immediate and side-effect free. The attempt is refused, never queued, and no
         * state was read or changed.
         */
        REFUSED,

        /** The id is not an ItemType of the active repository's metadata; nothing ran. */
        NOT_FOUND,

        /** The attempt was cancelled; nothing was published. */
        CANCELLED,

        /** The owning repository context was closed (or closed while the attempt ran); nothing was published. */
        CLOSED,

        /** The analytics half cannot run a refresh at all; the reason says why. */
        UNAVAILABLE,

        /** The measurement failed; the reason is the same sanitized reason a full scan would record. */
        FAILED
    }

    /** One sanitized line is a reason, not a paragraph. */
    static final int MAX_REASON_LENGTH = 200;

    public TargetedRefreshResult {
        Objects.requireNonNull(outcome, "outcome");
        reason = SanitizedText.clean(reason, MAX_REASON_LENGTH);
        if (outcome == Outcome.REFRESHED) {
            Objects.requireNonNull(detail, "detail");
            if (detail.itemTypeId() != itemTypeId) {
                throw new IllegalArgumentException("the published detail is for ItemType id "
                        + detail.itemTypeId() + " but the outcome names " + itemTypeId);
            }
        } else if (detail != null) {
            throw new IllegalArgumentException("outcome " + outcome
                    + " must not carry published targeted detail data");
        }
        if (outcome == Outcome.REFUSED) {
            Objects.requireNonNull(refusedBy, "refusedBy");
        } else if (refusedBy != null) {
            throw new IllegalArgumentException("only a REFUSED outcome names the operation holding the gate");
        }
    }

    /** The ItemType was measured and its detail result is published. */
    public static TargetedRefreshResult refreshed(TargetedItemTypeDetail detail) {
        Objects.requireNonNull(detail, "detail");
        return new TargetedRefreshResult(Outcome.REFRESHED, detail.itemTypeId(), "", detail, null);
    }

    /** The shared gate is held by {@code holder}; nothing was read, changed or queued. */
    public static TargetedRefreshResult refused(int itemTypeId, AnalyticsOperationGate.Operation holder) {
        Objects.requireNonNull(holder, "holder");
        return new TargetedRefreshResult(Outcome.REFUSED, itemTypeId, "", null, holder);
    }

    /** No ItemType of the active repository metadata has this id. */
    public static TargetedRefreshResult notFound(int itemTypeId) {
        return new TargetedRefreshResult(Outcome.NOT_FOUND, itemTypeId,
                "no ItemType of the active repository metadata has id " + itemTypeId, null, null);
    }

    /** The attempt was cancelled before it could publish. */
    public static TargetedRefreshResult cancelled(int itemTypeId, String reason) {
        return new TargetedRefreshResult(Outcome.CANCELLED, itemTypeId, reason, null, null);
    }

    /** The repository context is closed, or closed while the attempt ran. */
    public static TargetedRefreshResult closed(int itemTypeId, String reason) {
        return new TargetedRefreshResult(Outcome.CLOSED, itemTypeId, reason, null, null);
    }

    /** No targeted refresh can run at all; the reason is fixed and value-free. */
    public static TargetedRefreshResult unavailable(int itemTypeId, String reason) {
        return new TargetedRefreshResult(Outcome.UNAVAILABLE, itemTypeId, reason, null, null);
    }

    /** The measurement failed; the reason is sanitized exactly as a scan's failure reason is. */
    public static TargetedRefreshResult failed(int itemTypeId, String reason) {
        return new TargetedRefreshResult(Outcome.FAILED, itemTypeId, reason, null, null);
    }

    /** True exactly when a detail result was published. */
    public boolean published() {
        return outcome == Outcome.REFRESHED;
    }

    /** The published detail, or empty when this attempt published nothing. */
    public Optional<TargetedItemTypeDetail> detailOption() {
        return Optional.ofNullable(detail);
    }

    /** The operation that held the shared gate, or empty when the gate was free. */
    public Optional<AnalyticsOperationGate.Operation> refusedByOperation() {
        return Optional.ofNullable(refusedBy);
    }

    @Override
    public String toString() {
        return "TargetedRefreshResult[" + outcome + ", itemTypeId=" + itemTypeId
                + (refusedBy == null ? "" : ", held by " + refusedBy.label())
                + (reason.isEmpty() ? "" : ", reason=" + reason) + "]";
    }
}
