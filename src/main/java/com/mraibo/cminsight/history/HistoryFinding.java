package com.mraibo.cminsight.history;

import java.util.Objects;

/**
 * One classified history-readiness line, produced by the history layer for the configuration validator.
 *
 * <h2>Why this type exists instead of the validator reading history internals</h2>
 *
 * <p>The rules that decide whether local history is usable - is the feature switched on, is the local
 * database driver installed, is the data directory writable - belong to the history layer, and the
 * {@code --validate-config} report must print exactly those verdicts. Re-implementing them in the validator
 * would create a second copy that can drift, which is the defect this project has already fixed once for
 * the credential rules. So the history layer answers, and the validator prints.
 *
 * <p>{@link #describe()} reproduces the validator's own line prefixes so the text it prints is the text
 * this layer produced.
 *
 * @param level   the verdict this line carries
 * @param message the line without its prefix; never contains a credential, a JDBC URL or a repository value
 */
public record HistoryFinding(Level level, String message) {

    /** What the validator does with this line. */
    public enum Level {
        /** Informational: history resolved this way and is usable. */
        OK,
        /** History is unavailable, but the runtime starts and every other feature keeps working. */
        WARN,
        /** The runtime refuses to start, with exactly this reason. */
        ERROR
    }

    public HistoryFinding {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");
    }

    /** The complete line as printed, with the prefix the configuration report maps onto its counters. */
    public String describe() {
        return switch (level) {
            case OK -> "OK:    " + message;
            case WARN -> "WARN:  " + message;
            case ERROR -> "ERROR: " + message;
        };
    }

    @Override
    public String toString() {
        return describe();
    }
}
