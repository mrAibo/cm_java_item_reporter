package com.mraibo.cminsight.db;

/**
 * The analytics JDBC layer refused or failed an operation, in a form that is safe to store, print and
 * publish.
 *
 * <h2>Why this type exists instead of letting SQLException through</h2>
 *
 * <p>Two rules of this project meet here. The first is architectural: no {@code java.sql} type may
 * escape the database package, so a service, a DTO or a web handler can never hold a {@code Connection},
 * a {@code Statement}, a {@code ResultSet} or a driver exception. The second is a security rule: any
 * database error text that is stored or displayed must be sanitized, because a driver message routinely
 * contains the SQL text, the schema, a table name, an internal statement name - and, in the worst case,
 * a fragment of the connection string. {@link java.sql.SQLException#getMessage()} is therefore never
 * reproduced anywhere in this layer.
 *
 * <p>What is carried instead is the small, provably safe subset an operator can act on:
 *
 * <ul>
 *   <li>{@link #operation()} - a fixed label produced by this layer, for example
 *       {@code "execute select"} or {@code "close connection"};</li>
 *   <li>{@link #sqlState()} - the five-character SQLSTATE when the driver reported one, otherwise an
 *       empty string;</li>
 *   <li>{@link #vendorCode()} - the driver's own error code when it reported a non-zero one, otherwise
 *       {@code 0}.</li>
 * </ul>
 *
 * <p>The message is a sentence built entirely from those fixed parts, never from driver text. The cause
 * is deliberately <em>not</em> the original {@code SQLException}: attaching it would put the raw message
 * back within reach of every consumer through {@code getCause()}. A sanitized copy is preserved where
 * the resource-lifecycle contract requires a diagnostic cause - see the create path of
 * {@link JdbcSessionFactory}, which reports it inside a
 * {@link com.mraibo.cminsight.connection.CreationFailure} - and even there the copy carries a sanitized
 * message, only the SQLSTATE and the vendor code of the original.
 */
public class JdbcAccessException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String operation;
    private final String sqlState;
    private final int vendorCode;

    /**
     * @param operation       fixed label of the failed operation, never driver text
     * @param sanitizedMessage a sentence built from fixed parts only; it must never contain SQL text, a
     *                         JDBC URL, a credential, a schema taken from an exception or a raw driver
     *                         message
     */
    public JdbcAccessException(String operation, String sanitizedMessage) {
        this(operation, sanitizedMessage, "", 0);
    }

    /**
     * @param operation        fixed label of the failed operation, never driver text
     * @param sanitizedMessage a sentence built from fixed parts only
     * @param sqlState         the SQLSTATE reported by the driver, or {@code null} when unknown
     * @param vendorCode       the driver error code, or {@code 0} when unknown
     */
    public JdbcAccessException(String operation, String sanitizedMessage, String sqlState, int vendorCode) {
        super(sanitizedMessage);
        this.operation = operation == null || operation.isBlank() ? "jdbc operation" : operation.trim();
        this.sqlState = sqlState == null ? "" : sqlState.trim();
        this.vendorCode = vendorCode;
    }

    /** Fixed label of the failed operation. Never driver text. */
    public String operation() {
        return operation;
    }

    /** The SQLSTATE the driver reported, or an empty string when it reported none. */
    public String sqlState() {
        return sqlState;
    }

    /** The driver error code, or {@code 0} when it reported none. */
    public int vendorCode() {
        return vendorCode;
    }

    /** True when the driver reported a SQLSTATE. */
    public boolean hasSqlState() {
        return !sqlState.isEmpty();
    }

    @Override
    public String toString() {
        return "JdbcAccessException[operation=" + operation
                + (sqlState.isEmpty() ? "" : ", sqlState=" + sqlState)
                + (vendorCode == 0 ? "" : ", vendorCode=" + vendorCode)
                + ", message=" + getMessage() + "]";
    }
}
