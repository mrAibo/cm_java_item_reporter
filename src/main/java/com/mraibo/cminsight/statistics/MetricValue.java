package com.mraibo.cminsight.statistics;

import java.util.Objects;

/**
 * One measured number, or the honest statement that there is none.
 *
 * <h2>Three states, and only one of them carries a number</h2>
 *
 * <ul>
 *   <li>{@link Availability#AVAILABLE} - the number was measured, and it is exactly {@link #value()}.
 *       A legitimate zero is representable and stays distinguishable from "no data";</li>
 *   <li>{@link Availability#UNAVAILABLE} - the metric is not produced in this goal (Versions and Parts) or
 *       its boundary could not be represented. There is no number at all;</li>
 *   <li>{@link Availability#ERROR} - the measurement failed. There is no number either, and
 *       {@link #reason()} says what failed in sanitized form.</li>
 * </ul>
 *
 * <p>This record makes "error or unavailable was quietly rendered as 0" unrepresentable rather than merely
 * discouraged: the compact constructor FORCES {@link #value()} to {@code null} for every non-available
 * state, so even a caller that passes a number by mistake cannot produce a zero that would later be summed
 * as data. That is why the canonical component is a boxed {@link Long} and not a {@code long}: a primitive
 * could not express "absent" and would have to invent a sentinel.
 *
 * <h2>Why there are exactly two factories and no third</h2>
 *
 * <p>{@link #available(long)} and {@link #unavailable()} are the only public static methods, and that set is
 * pinned by {@code StatisticsContractTest} precisely so the vocabulary cannot grow a state that expresses a
 * guess. An ERROR metric - and an UNAVAILABLE one that has a reason to state - is built through the canonical
 * constructor:
 *
 * <pre>{@code
 * MetricValue.error  ==  new MetricValue(null, MetricValue.Availability.ERROR, sanitizedReason)
 * }</pre>
 *
 * <p>The constructor still refuses the one inconsistent shape: {@code AVAILABLE} with no value. So the
 * absence of a factory does not open a way to build a numberless "available" metric.
 *
 * @param value        the measured count, or {@code null} when the metric has no number
 * @param availability the state; never {@code null}
 * @param reason       sanitized explanation for UNAVAILABLE/ERROR, or an empty string
 */
public record MetricValue(Long value, Availability availability, String reason) {

    /** The closed set of states a metric can be in. Nothing else may be added to make a guess expressible. */
    public enum Availability {
        /** A number was measured. */
        AVAILABLE,
        /** No number exists for this metric in this goal. */
        UNAVAILABLE,
        /** The measurement failed; no number exists and none may be invented. */
        ERROR
    }

    /** Longest accepted reason, so one hostile or verbose failure cannot inflate a snapshot. */
    static final int MAX_REASON_LENGTH = 200;

    public MetricValue {
        Objects.requireNonNull(availability, "availability");
        reason = SanitizedText.clean(reason, MAX_REASON_LENGTH);
        if (availability == Availability.AVAILABLE) {
            if (value == null) {
                // Fail closed on the shape itself: an "available" metric with no number is the one state
                // that would force a downstream consumer to guess.
                throw new IllegalArgumentException("an AVAILABLE metric must carry the measured value");
            }
        } else {
            // Not an exception: a caller may pass the number it would have used and still get an honest
            // absence. Discarding it here is what makes a zero impossible to observe for ERROR/UNAVAILABLE.
            value = null;
        }
    }

    /** A measured count. */
    public static MetricValue available(long value) {
        return new MetricValue(value, Availability.AVAILABLE, "");
    }

    /** No number exists for this metric, with no further explanation. */
    public static MetricValue unavailable() {
        return new MetricValue(null, Availability.UNAVAILABLE, "");
    }

    /** True only for a genuinely measured number. */
    public boolean isAvailable() {
        return availability == Availability.AVAILABLE;
    }

    /** True when the measurement failed, as opposed to being unavailable by design. */
    public boolean isError() {
        return availability == Availability.ERROR;
    }

    /** True when this metric carries a number at all. */
    public boolean hasValue() {
        return value != null;
    }

    /** The measured number or {@code 0} for display only; validation must use {@link #isAvailable()}. */
    public long valueOrZero() {
        return value == null ? 0L : value;
    }

    @Override
    public String toString() {
        if (availability == Availability.AVAILABLE) {
            return Long.toString(value);
        }
        return availability.name() + (reason.isEmpty() ? "" : "(" + reason + ")");
    }
}
