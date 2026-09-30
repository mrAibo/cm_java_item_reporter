package com.mraibo.cminsight.history;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A store-owned identity for one persisted history snapshot.
 *
 * <h2>Why it is not the scan id</h2>
 *
 * <p>The statistics snapshot carries a {@code scanId} that is monotonically increasing
 * <strong>within one repository context</strong>. After a restart the counter starts again, and two
 * repositories use it independently, so it cannot identify a row in a store that outlives both. Using it as
 * a key would make yesterday's scan collide with today's, which is the defect this type exists to prevent.
 *
 * <h2>It is also a URL value, so it is opaque and validated</h2>
 *
 * <p>A history id appears in {@code GET /api/history/{id}} and in a report request, so it is part of the
 * request surface. It is therefore deliberately constrained to an opaque token with no structure a caller
 * could steer: no path separators, no dots, no percent signs, nothing that could be read as a filesystem
 * path or as SQL. Validation happens here, once, so every route that accepts one gets the same answer.
 *
 * @param value the opaque token; never blank, never containing a path or SQL character
 */
public record HistoryId(String value) {

    /** The longest accepted token. Comfortably above any generated form, far below anything abusive. */
    public static final int MAX_LENGTH = 64;

    /**
     * The only shape an identity may have: a lowercase hex/alphanumeric token beginning with a letter.
     *
     * <p>Deliberately excludes {@code -} and {@code _} as leading characters and excludes every separator,
     * so a value that could be mistaken for a path, a schema, a JDBC URL fragment or an SQL literal is not
     * an identity at all.
     */
    private static final Pattern SHAPE = Pattern.compile("[a-z][a-z0-9]{0," + (MAX_LENGTH - 1) + "}");

    public HistoryId {
        value = value == null ? "" : value.trim();
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("a history id must be an opaque lowercase alphanumeric token"
                    + " of at most " + MAX_LENGTH + " characters, but was "
                    + (value.isEmpty() ? "(blank)" : "'" + redact(value) + "'"));
        }
    }

    /** Parses a caller-supplied value, or empty when it is not a valid identity. */
    public static java.util.Optional<HistoryId> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new HistoryId(raw));
        } catch (IllegalArgumentException notAnIdentity) {
            return java.util.Optional.empty();
        }
    }

    /**
     * A bounded echo of a rejected value for a message.
     *
     * <p>A malformed identity is caller-supplied text, so it is quoted back only after control characters
     * are removed and the length is capped - a refusal must not become a way to echo arbitrary bytes into a
     * log or a response.
     */
    private static String redact(String raw) {
        StringBuilder cleaned = new StringBuilder(Math.min(raw.length(), 24));
        for (int index = 0; index < raw.length() && cleaned.length() < 24; index++) {
            char c = raw.charAt(index);
            cleaned.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        return cleaned.length() < raw.length() ? cleaned + "..." : cleaned.toString();
    }

    @Override
    public String toString() {
        return value;
    }

    /** True when {@code other} is a non-null identity with the same token. */
    public boolean sameAs(HistoryId other) {
        return other != null && Objects.equals(value, other.value);
    }
}
