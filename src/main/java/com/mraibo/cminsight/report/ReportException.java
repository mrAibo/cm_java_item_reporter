package com.mraibo.cminsight.report;

import java.io.Serial;
import java.util.Objects;

/**
 * A report failure reduced to a fixed, value-free reason.
 *
 * <h2>Why there is no free-form message and no cause</h2>
 *
 * <p>A report error travels the furthest of any string in this application: it reaches an HTTP error body,
 * an operator's browser and possibly a log line. The values it would naturally carry are exactly the ones
 * Goal 04 forbids publishing - a JDBC URL, a database user name, a credential, a raw SQL fragment, a raw
 * {@code SQLException} message or a local filesystem path - and a report writer is precisely the place
 * where a driver message would otherwise be convenient. So the type makes that impossible rather than
 * discouraged: the only constructor takes a {@link Reason}, the message is the constant the reason defines,
 * and there is deliberately no constructor that accepts a message, a cause or a throwable. Nothing can be
 * attached to it that a caller could then print.
 *
 * <p>The cost is a lost stack-trace root for a genuinely surprising I/O failure. That is paid where the
 * failure is <em>detected</em>, which is inside this package: the write path discards the
 * {@code IOException} it caught and reports {@link Reason#WRITE_FAILED}, so the diagnosis lives in the
 * package's own control flow instead of in a string that escapes to a client.
 */
public final class ReportException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The closed set of report failures, each with the fixed sentence an operator may see. */
    public enum Reason {
        /** The value is not a report identity at all: no file lookup is attempted for it. */
        INVALID_REPORT_ID("the report identifier is not a valid report identity"),
        /** The value is not one of the supported format tokens. */
        INVALID_FORMAT("the requested report format is not supported"),
        /** The format exists in the vocabulary but this build cannot produce it. */
        FORMAT_UNAVAILABLE("the requested report format is not available in this build"),
        /** {@code reports.dir} cannot be used as a directory: it could not be created or is not a directory. */
        OUTPUT_DIRECTORY_UNUSABLE("the configured reports directory is not usable"),
        /** The resolved output path would not be a direct child of {@code reports.dir}. */
        PATH_OUTSIDE_OUTPUT_DIRECTORY("the resolved report path is outside the reports directory"),
        /** The rendered bytes exceed the fixed bound a single report artifact may have. */
        CONTENT_TOO_LARGE("the rendered report exceeds the maximum report size"),
        /** The artifact could not be written and no partial file was left behind. */
        WRITE_FAILED("the report could not be written"),
        /** A stored artifact exists but its attributes or contents could not be read. */
        OUTPUT_UNREADABLE("the stored report could not be read"),
        /** A list request asked for a limit outside the bounded range. */
        INVALID_LIMIT("the requested report list limit is outside the accepted range"),
        /** The immutable report input is not usable as a report source. */
        MODEL_INVALID("the report model is not usable");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        /**
         * The fixed sentence this reason publishes.
         *
         * <p>Every constant above is a compile-time literal, so the value-free property is a property of the
         * type: no constructor argument, no path and no exception text can reach an operator through it.
         */
        public String message() {
            return message;
        }
    }

    private final transient Reason reason;

    public ReportException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").message());
        this.reason = reason;
    }

    /** The fixed category of this failure. */
    public Reason reason() {
        return reason;
    }

    /**
     * The same fixed sentence as {@link #getMessage()}.
     *
     * <p>Both are provided because a caller that sanitises messages (the web tier) reads better when it
     * asks for a safe string by name. They are identical by construction: the message could not be anything
     * else.
     */
    public String safeMessage() {
        return reason.message();
    }
}
