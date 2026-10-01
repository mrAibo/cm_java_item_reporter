package com.mraibo.cminsight.db;

/**
 * A JDBC analytics operation cannot be performed safely, so the affected metric (or the whole ItemType) is
 * unavailable rather than guessed.
 *
 * <p>This is a checked exception on purpose. Every caller has to decide what an unavailable measurement
 * means for its own output - a statistics service turns it into {@code MetricValue.UNAVAILABLE}, a
 * diagnostic prints the reason - and an unchecked exception would let that decision be skipped by accident.
 *
 * <h2>The message is safe to surface</h2>
 *
 * <p>A message is an actionable, sanitized reason: what was being attempted, and what the operator can do
 * about it. It deliberately never contains SQL text, a schema or table name taken from a failure, a JDBC
 * URL, a user name, a credential, or a raw {@code SQLException.getMessage()}. Goal 03 section 13 requires
 * exactly that of every analytics response, and a reason that is safe at the origin cannot leak on the way
 * out. The original {@link java.sql.SQLException} stays available as the {@link #getCause() cause} for a
 * local log; it is never the text of this exception.
 */
public class SqlUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    public SqlUnavailableException(String reason) {
        super(reason);
    }

    public SqlUnavailableException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
