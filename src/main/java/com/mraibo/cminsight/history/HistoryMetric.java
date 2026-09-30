package com.mraibo.cminsight.history;

import java.util.Objects;
import java.util.Optional;

/**
 * One metric as it was captured at scan time: a state, an optional number, and an optional sanitized reason.
 *
 * <h2>Why history restates the metric shape instead of reusing the live one</h2>
 *
 * <p>The live statistics types are the analytics engine's own vocabulary and they evolve with it. A
 * persisted, versioned read model must not change meaning because an analytics record gained a field, so the
 * stored shape is its own small value with an explicit {@link State}. The cost is one mapping function; the
 * benefit is that a schema migration is a deliberate act rather than a side effect of an unrelated edit.
 *
 * <h2>Absence is never zero</h2>
 *
 * <p>{@link State#UNAVAILABLE} and {@link State#ERROR} can never carry a number - the canonical constructor
 * discards one rather than keeping it - so a count nobody measured cannot come back from storage looking
 * like a measurement of zero. That is the single most important property of this type, because a stored
 * zero is indistinguishable from a real zero once it is in a report.
 *
 * @param state  what kind of answer this is
 * @param value  the measured value, present only for {@link State#AVAILABLE}
 * @param reason a sanitized, value-free explanation, or empty
 */
public record HistoryMetric(State state, Long value, String reason) {

    /** The three states a stored metric may have. */
    public enum State {
        /** A number was measured. */
        AVAILABLE,
        /** No number exists because it could not be represented or was not measured. */
        UNAVAILABLE,
        /** The measurement failed. */
        ERROR
    }

    public HistoryMetric {
        Objects.requireNonNull(state, "state");
        reason = reason == null ? "" : reason.trim();
        if (state != State.AVAILABLE) {
            // Structural, not conventional: an unavailable or failed metric cannot carry a number, whoever
            // built it. This is the same guarantee the live MetricValue makes, restated for storage.
            value = null;
        }
        if (state == State.AVAILABLE && value == null) {
            throw new IllegalArgumentException("an AVAILABLE metric must carry a value");
        }
    }

    /** A measured value. */
    public static HistoryMetric available(long value) {
        return new HistoryMetric(State.AVAILABLE, value, "");
    }

    /** No number, with an optional sanitized explanation. */
    public static HistoryMetric unavailable(String reason) {
        return new HistoryMetric(State.UNAVAILABLE, null, reason);
    }

    /** A failed measurement, with an optional sanitized explanation. */
    public static HistoryMetric error(String reason) {
        return new HistoryMetric(State.ERROR, null, reason);
    }

    /** True when this metric carries a measured number. */
    public boolean isAvailable() {
        return state == State.AVAILABLE;
    }

    /** The number, or empty when nothing was measured. Never substitutes zero. */
    public Optional<Long> measured() {
        return Optional.ofNullable(value);
    }

    /**
     * The number for arithmetic, defaulting to zero.
     *
     * <p>Named to be conspicuous at a call site: summing absent metrics contributes nothing, which is the
     * correct behaviour for a total that must travel with its coverage, and it must never be confused with
     * rendering {@code 0} to an operator.
     */
    public long valueOrZeroForSum() {
        return value == null ? 0L : value;
    }

    @Override
    public String toString() {
        return state == State.AVAILABLE ? "AVAILABLE(" + value + ")" : state.toString();
    }
}
