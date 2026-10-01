package com.mraibo.cminsight.db;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/**
 * The single classifier of "this driver cannot do that" among the JDBC failures of this layer.
 *
 * <h2>Why the rule has a name instead of living inline in a catch block</h2>
 *
 * <p>A feature-not-supported answer is a statement about the driver's <em>capabilities</em>. It is not
 * evidence that the connection is damaged, and it is the only JDBC failure this layer may read that way.
 * Every other failure - on the query path and on the current-schema path alike - is conservative: the
 * session is retired, because nothing in that failure proves the driver state is reusable.
 *
 * <p>That distinction used to be applied by accident rather than by rule:
 * {@link JdbcSession#currentSchema()} treated <em>every</em> {@code SQLException} as a capability answer
 * and left a poisoned connection in the pool for the next borrower. The rule therefore lives here, once,
 * so it is nameable in review and testable on its own instead of being re-derived (or forgotten) at each
 * call site.
 *
 * <h2>The exact rule - one benign form, two spellings</h2>
 *
 * <p>{@link #isFeatureNotSupported(SQLException)} is true for exactly two things, and nothing else:
 *
 * <ul>
 *   <li>the dedicated {@link SQLFeatureNotSupportedException} type (or any subclass of it), which is what
 *       the JDBC contract tells drivers to throw;</li>
 *   <li>the documented SQLSTATE for the same condition, {@code 0A000}, compared case-insensitively -
 *       several enterprise drivers report the generic type with that state instead of the dedicated
 *       exception.</li>
 * </ul>
 *
 * <p>Everything else is false, deliberately including near misses: another SQLSTATE inside class
 * {@code 0A}, a {@code null} state, and the empty state. A wrong "benign" answer here would hand a
 * damaged physical connection back to the pool, which is the defect this class exists to prevent, so the
 * classifier is narrow by construction rather than generous by convenience.
 *
 * <p>Package-private like {@link JdbcSqlErrors}: this is an internal decision of the JDBC layer, not an
 * API, and no consumer outside the database package distinguishes these two failure kinds - the behaviour
 * they observe is the session's local health and the sanitized failure, both of which are public on
 * {@link JdbcSession}.
 */
final class JdbcFeatureSupport {

    /**
     * The SQLSTATE JDBC uses for "feature not supported" when a driver does not throw the dedicated
     * {@link SQLFeatureNotSupportedException} type. {@link JdbcSessionFactory} applies the same single
     * value to the read-only hint in its session setup.
     */
    static final String FEATURE_NOT_SUPPORTED_SQLSTATE = "0A000";

    private JdbcFeatureSupport() {
    }

    /**
     * True only when the driver answered "I cannot do that" rather than "something went wrong".
     *
     * @param failure the driver failure to classify; never {@code null}
     * @return {@code true} for {@link SQLFeatureNotSupportedException} (or a subclass) and for SQLSTATE
     *         {@code 0A000}; {@code false} for every other {@code SQLException}, including a missing or
     *         blank SQLSTATE
     */
    static boolean isFeatureNotSupported(SQLException failure) {
        if (failure instanceof SQLFeatureNotSupportedException) {
            return true;
        }
        String state = failure.getSQLState();
        return state != null && FEATURE_NOT_SUPPORTED_SQLSTATE.equalsIgnoreCase(state.trim());
    }
}
