package com.mraibo.cminsight.statistics;

/**
 * A database failure reduced to what may be stored, logged and displayed.
 *
 * <h2>Why this type exists instead of {@code SQLException}</h2>
 *
 * <p>A raw {@link java.sql.SQLException#getMessage()} routinely contains the JDBC URL, the user name, the
 * schema and fragments of the statement, and the goal forbids all four from reaching a snapshot or a
 * response. Holding the exception itself would also keep a driver object graph alive for as long as the
 * snapshot is visible. So the JDBC layer converts a failure into this value at the boundary and nothing
 * downstream ever sees the driver's text.
 *
 * <p>There are exactly two ways to build one, and neither can carry driver text:
 *
 * <ul>
 *   <li>{@link #StatisticsQueryException(String, String, Integer, Throwable)} - a fixed operation label plus
 *       a SQLState/vendor code where the driver supplied them. The sentence is assembled from those parts
 *       only;</li>
 *   <li>{@link #withSanitizedReason(String, String, Throwable)} - a sentence that a lower layer has already
 *       sanitized by contract. This is how the analytics database layer's own refusal reasons (an
 *       unaddressable schema, a missing root segment, an ambiguous mapping) reach a snapshot without being
 *       flattened into a meaningless label.</li>
 * </ul>
 *
 * <p>{@code cause} is retained for in-process diagnosis (a stack trace), but it is never rendered into
 * {@link #sanitizedReason()}, and no {@code toString()} of this type prints a message other than the
 * sanitized sentence.
 */
public class StatisticsQueryException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Bounded so one long label cannot inflate a snapshot. */
    static final int MAX_OPERATION_LENGTH = 80;

    /** Bounded so one failure cannot inflate a snapshot. */
    static final int MAX_REASON_LENGTH = 400;

    private final String operation;
    private final String reason;
    private final String sqlState;
    private final Integer vendorCode;

    /**
     * @param operation  a FIXED label such as {@code "database current date"} or {@code "logical item
     *                   aggregate"}; never user input and never a statement
     * @param sqlState   the driver's SQLState, or {@code null} when it reported none
     * @param vendorCode the driver's vendor code, or {@code null} when it reported none
     * @param cause      the original failure, kept for in-process diagnosis only
     */
    public StatisticsQueryException(String operation, String sqlState, Integer vendorCode, Throwable cause) {
        this(operation, null, sqlState, vendorCode, cause);
    }

    /**
     * A failure whose reason is already a sanitized sentence from a lower layer.
     *
     * <p>The contract is the same one {@code SqlUnavailableException} and {@code JdbcAccessException}
     * document: the sentence names what was attempted and what an operator can do about it, and never
     * contains SQL text, a schema or table name taken from a failure, a JDBC URL, a user name or a
     * credential. It is additionally cleaned and length-bounded here, so even a caller that breaks that
     * contract cannot publish a multi-line blob.
     */
    public static StatisticsQueryException withSanitizedReason(String operation,
                                                               String sanitizedReason,
                                                               Throwable cause) {
        return new StatisticsQueryException(operation, sanitizedReason, null, null, cause);
    }

    private StatisticsQueryException(String operation,
                                     String sanitizedReason,
                                     String sqlState,
                                     Integer vendorCode,
                                     Throwable cause) {
        super(SanitizedText.clean(sanitizedReason, MAX_REASON_LENGTH).isEmpty()
                ? defaultReason(operation) + describeState(sqlState, vendorCode)
                : SanitizedText.clean(sanitizedReason, MAX_REASON_LENGTH));
        this.operation = SanitizedText.clean(operation, MAX_OPERATION_LENGTH);
        this.reason = getMessage();
        this.sqlState = cleanCode(sqlState);
        this.vendorCode = vendorCode;
    }

    /** The fixed operation label, sanitized. */
    public String operation() {
        return operation;
    }

    /** The driver's SQLState, or {@code null}. */
    public String sqlState() {
        return sqlState;
    }

    /** The driver's vendor code, or {@code null}. */
    public Integer vendorCode() {
        return vendorCode;
    }

    /**
     * The only text a caller may store or display: a fixed sentence built from the operation label and,
     * where available, a SQLState/vendor code - or a sentence a lower layer already sanitized. Never the
     * driver's message, the SQL, the URL or the user.
     */
    public String sanitizedReason() {
        return reason;
    }

    /** {@link #sanitizedReason()} plus the codes when they were not already part of it. */
    public String sanitizedReasonWithState() {
        return reason + describeState(sqlState, vendorCode);
    }

    private static String defaultReason(String operation) {
        String label = SanitizedText.clean(operation, MAX_OPERATION_LENGTH);
        return label.isEmpty() ? "statistics query failed" : "statistics query '" + label + "' failed";
    }

    private static String describeState(String sqlState, Integer vendorCode) {
        String state = cleanCode(sqlState);
        if (state == null && vendorCode == null) {
            return "";
        }
        StringBuilder detail = new StringBuilder(" (");
        if (state != null) {
            detail.append("SQLState ").append(state);
        }
        if (vendorCode != null) {
            if (state != null) {
                detail.append(", ");
            }
            detail.append("vendor code ").append(vendorCode);
        }
        return detail.append(')').toString();
    }

    /** SQLState is five characters by contract; anything longer is not a state and is not kept. */
    private static String cleanCode(String code) {
        String cleaned = SanitizedText.clean(code, 16);
        return cleaned.isEmpty() ? null : cleaned;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + sanitizedReasonWithState() + "]";
    }
}
