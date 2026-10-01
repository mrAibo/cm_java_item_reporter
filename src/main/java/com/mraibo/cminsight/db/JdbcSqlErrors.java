package com.mraibo.cminsight.db;

import java.sql.SQLException;

/**
 * The single sanitizer of this layer: the one place where a driver failure is translated into text, so
 * the translation cannot be forgotten in one path and remembered in another.
 *
 * <h2>The rule</h2>
 *
 * <p>A {@link SQLException} message is not safe to store, log or publish. It can contain the SQL text,
 * a schema or table name, a driver-internal statement identifier, a host name and - for some drivers and
 * some failures - a fragment of the connection properties. Nothing in this layer reproduces it. What is
 * produced instead is a sentence assembled from fixed parts and the two fields the goal explicitly allows:
 *
 * <pre>
 *   JDBC operation 'open connection' failed (SQLState=08001, vendorCode=-30082)
 *   JDBC operation 'execute select' failed (no SQLState, vendorCode=1406)
 * </pre>
 *
 * <p>The only other thing kept from the original failure is its <em>stack trace</em>, on the sanitized
 * copy: a stack trace names classes and lines, not values, and it is what makes a production report
 * diagnosable at all.
 *
 * <p>This class is package-private on purpose. No consumer outside the database package can obtain a
 * sanitized {@code SQLException} from here; callers see {@link JdbcAccessException} (state and code as
 * plain fields) or the message text, both of which are already safe.
 */
final class JdbcSqlErrors {

    private JdbcSqlErrors() {
    }

    /**
     * The sanitized one-line description of a driver failure.
     *
     * @param operation fixed label of the failed operation, never driver text
     * @param failure   the driver failure whose message is deliberately NOT reproduced
     */
    static String message(String operation, SQLException failure) {
        StringBuilder text = new StringBuilder(64);
        text.append("JDBC operation '").append(operation).append("' failed");
        String state = failure.getSQLState();
        if (state == null || state.isBlank()) {
            text.append(" (no SQLState");
        } else {
            text.append(" (SQLState=").append(state.trim());
        }
        int code = failure.getErrorCode();
        if (code != 0) {
            text.append(", vendorCode=").append(code);
        }
        text.append(')');
        return text.toString();
    }

    /**
     * A sanitized copy of a driver failure, for the few places where the resource contract requires a
     * diagnostic <em>cause</em> rather than only text.
     *
     * <p>The copy keeps the SQLSTATE, the vendor code and the stack trace; it carries a sanitized message
     * and <strong>no</strong> cause chain, so the original raw message is not reachable through it either.
     */
    static SQLException sanitized(String operation, SQLException failure) {
        SQLException copy = new SQLException(message(operation, failure),
                failure.getSQLState(), failure.getErrorCode());
        copy.setStackTrace(failure.getStackTrace());
        return copy;
    }
}
