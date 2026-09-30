package com.mraibo.cminsight.history;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * History as an explicit, first-class state rather than as a {@code null} check.
 *
 * <h2>Why a real object</h2>
 *
 * <p>History is optional: the local database driver may be absent, the feature may be switched off, or the
 * store file may not open. In every one of those cases the application must behave as though there is simply
 * no history - a repository whose metadata, retention and live statistics all work must never fail to
 * activate because a local file could not be opened. Returning {@code null} would move that decision to
 * every call site and one of them would forget; this type makes "there is no history" an answer the whole
 * codebase already handles, because it is the same interface every caller was written against.
 *
 * <h2>Everything here is safe to call</h2>
 *
 * <p>Every read returns empty, every write is refused without throwing, and {@link #close()} is a no-op that
 * may be called any number of times. A caller that only checks {@link #available()} once and then holds the
 * store for the process lifetime therefore cannot be surprised by a later call - which is exactly why the
 * API surface is callable rather than throwing {@code UnsupportedOperationException}.
 *
 * <h2>The reason is fixed at construction and carries no value</h2>
 *
 * <p>It is sanitized once (control characters removed, whitespace collapsed, length bounded) so the same
 * sentence is returned on every call and cannot forge a log line or echo arbitrary bytes from a path or a
 * driver message into a diagnostics payload.
 */
public final class UnavailableHistoryStore implements HistoryStore {

    /** What {@link #schemaVersion()} reports when no schema exists, because no store is open. */
    public static final int NO_SCHEMA_VERSION = 0;

    /** Longest accepted reason; a fixed sentence, far below this bound. */
    static final int MAX_REASON_LENGTH = 300;

    /** The fixed reason used when a caller supplies a blank one. */
    static final String UNSPECIFIED_REASON =
            "local history is unavailable and no further reason was recorded";

    private final String reason;
    private final Instant openedAt;

    /**
     * An unavailable store with one fixed reason.
     *
     * @param reason why history is unavailable; blank becomes a fixed placeholder, control characters are
     *               removed and the length is bounded
     */
    public UnavailableHistoryStore(String reason) {
        this.reason = fix(reason);
        this.openedAt = Instant.now();
    }

    /** The feature was switched off. */
    public static UnavailableHistoryStore disabledByFeature() {
        return new UnavailableHistoryStore("history is switched off by feature.history=false: no local"
                + " database is opened and no history is stored or offered");
    }

    /** The local database driver is not on this runtime's class path. */
    public static UnavailableHistoryStore driverMissing(String driverClassName) {
        String name = driverClassName == null || driverClassName.isBlank() ? "unknown" : driverClassName.trim();
        return new UnavailableHistoryStore("the local history database driver (" + name + ") is not on this"
                + " runtime's class path: place the local database jar in lib/app to enable persistent"
                + " history. Everything else keeps working and repository activation is unaffected.");
    }

    /** A fixed, sanitized reason. */
    public static UnavailableHistoryStore of(String reason) {
        return new UnavailableHistoryStore(reason);
    }

    /** Always false: this store exists precisely because it can do nothing. */
    @Override
    public boolean available() {
        return false;
    }

    /** The fixed reason, sanitized at construction. */
    @Override
    public Optional<String> unavailableReason() {
        return Optional.of(reason);
    }

    /** Refused: no identity is assigned and nothing is written. A null entry is refused the same way. */
    @Override
    public Optional<HistoryId> record(HistoryDetail detail) {
        return Optional.empty();
    }

    /** Empty, whatever the arguments. */
    @Override
    public List<HistorySummary> list(String repositoryId, int limit) {
        return List.of();
    }

    /** Empty, whatever the arguments: there is nothing to page through. */
    @Override
    public List<HistorySummary> listAfter(String repositoryId, HistorySummary before, int limit) {
        return List.of();
    }

    /** Empty: no identity can exist in a store that never wrote a row. */
    @Override
    public Optional<HistoryDetail> find(HistoryId id) {
        return Optional.empty();
    }

    /** Zero: no rows exist. */
    @Override
    public long count(String repositoryId) {
        return 0L;
    }

    /** Empty: no rows exist. */
    @Override
    public Optional<HistorySummary> latest(String repositoryId) {
        return Optional.empty();
    }

    /** A no-op: there is nothing to release, and this never throws. */
    @Override
    public void close() {
        // Deliberately empty rather than an error: an owner may close a store it never opened.
    }

    /** {@value #NO_SCHEMA_VERSION}: no schema exists because no store is open. */
    @Override
    public int schemaVersion() {
        return NO_SCHEMA_VERSION;
    }

    /** When this capability was determined; there is no open instant to report. */
    @Override
    public Instant openedAt() {
        return openedAt;
    }

    /** A short value-free description for diagnostics. */
    public String describe() {
        return "history[unavailable: " + reason + "]";
    }

    @Override
    public String toString() {
        return describe();
    }

    /** One bounded, single-line reason: control characters become spaces and the length is capped. */
    private static String fix(String reason) {
        if (reason == null || reason.isBlank()) {
            return UNSPECIFIED_REASON;
        }
        StringBuilder cleaned = new StringBuilder(Math.min(reason.length(), MAX_REASON_LENGTH));
        boolean lastWasSpace = false;
        for (int index = 0; index < reason.length() && cleaned.length() < MAX_REASON_LENGTH; index++) {
            char c = reason.charAt(index);
            if (c < 0x20 || c == 0x7f || Character.isWhitespace(c)) {
                if (lastWasSpace) {
                    continue;
                }
                lastWasSpace = true;
                cleaned.append(' ');
                continue;
            }
            lastWasSpace = false;
            cleaned.append(c);
        }
        String fixed = cleaned.toString().trim();
        return fixed.isEmpty() ? UNSPECIFIED_REASON : fixed;
    }
}
