package com.mraibo.cminsight.statistics;

/**
 * Whether the analytics half can be used at all, and why not when it cannot.
 *
 * <h2>Unavailable is a normal state, not an activation failure</h2>
 *
 * <p>The IBM CM metadata and retention halves must keep working when statistics are switched off, when the
 * DB2/Oracle driver is missing, when the JDBC credential is missing or unreadable, when the analytics
 * schema is unusable, and when the database is simply unreachable. Every one of those is reported here as
 * {@code enabled} and/or {@code available} being false with a fixed reason, and none of them deactivates
 * the repository or fails activation.
 *
 * <p>{@link #enabled()} and {@link #available()} are deliberately two questions:
 *
 * <ul>
 *   <li>{@code enabled=false} - the operator switched the feature off ({@code feature.statistics=false}).
 *       Nothing was even constructed, and the reason says so;</li>
 *   <li>{@code enabled=true, available=false} - the feature is wanted but cannot run, and
 *       {@link #reason()} names the local, sanitized cause.</li>
 * </ul>
 *
 * <p>{@link #reason()} is fixed, sanitized text - a configuration key, a readiness verdict or a fixed
 * operation label with a SQLState/vendor code. It never contains a driver message, a URL, a user name or a
 * schema, so a route can publish it verbatim.
 *
 * @param enabled   whether the analytics feature is switched on in configuration
 * @param available whether the analytics half can currently serve a scan
 * @param reason    sanitized explanation, empty when {@link #available()}
 */
public record StatisticsAvailability(boolean enabled, boolean available, String reason) {

    static final int MAX_REASON_LENGTH = 200;

    public StatisticsAvailability {
        reason = SanitizedText.clean(reason, MAX_REASON_LENGTH);
    }

    /**
     * The analytics half is usable.
     *
     * <p>Named {@code ready} rather than {@code available} because a record cannot declare a static method
     * with the name of one of its components - the component accessor {@link #available()} owns that name.
     */
    public static StatisticsAvailability ready() {
        return new StatisticsAvailability(true, true, "");
    }

    /** The feature is switched off; nothing was constructed. */
    public static StatisticsAvailability featureDisabled(String reason) {
        return new StatisticsAvailability(false, false, reason);
    }

    /** The feature is wanted but the analytics half cannot serve a scan. */
    public static StatisticsAvailability unavailable(String reason) {
        return new StatisticsAvailability(true, false, reason);
    }

    /** True only when a scan may be requested. */
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String toString() {
        return "StatisticsAvailability[enabled=" + enabled + ", available=" + available
                + (reason.isEmpty() ? "" : ", reason=" + reason) + "]";
    }
}
