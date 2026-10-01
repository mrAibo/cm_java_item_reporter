package com.mraibo.cminsight.report;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An opaque, generated identity for one written report artifact.
 *
 * <h2>It is a URL value, so it is not a path and cannot become one</h2>
 *
 * <p>A report id appears in {@code GET /api/reports/{id}/download}, which makes it request-surface input.
 * Goal 04's output rule is absolute: <strong>no HTTP parameter ever becomes a filesystem path</strong> - not
 * a name, not a suffix and not a subdirectory. This type is how that rule is kept satisfiable: an id is a
 * lowercase alphanumeric token with no separator, no dot, no percent sign and no uppercase, so the
 * filename the output directory builds from it is already constrained before any containment check runs.
 * Validation happens here, once, and every route and every file lookup gets the same answer.
 *
 * <h2>Generated form</h2>
 *
 * <p>{@code r} + ten lowercase base-36 characters of the epoch millisecond + sixteen lowercase hex
 * characters drawn from {@link SecureRandom} (27 characters). The fixed-width timestamp prefix makes ids
 * sort in creation order, which is what lets the oldest-first retention of generated files be a name
 * comparison; the random suffix makes a collision impossible in practice without a filesystem check.
 *
 * <p>{@link #parse(String)} accepts the same shape at a bounded length rather than one exact length, so a
 * future generator can change the suffix width without orphaning artifacts an operator still has.
 */
public record ReportId(String value) {

    /** Shortest accepted token. Comfortably longer than anything a caller could guess usefully. */
    public static final int MIN_LENGTH = 16;
    /** Longest accepted token, and the bound that keeps a name from being abusive. */
    public static final int MAX_LENGTH = 32;

    private static final int TIMESTAMP_CHARS = 10;
    private static final int RANDOM_CHARS = 16;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * The only shape an identity may have: a lowercase alphanumeric token beginning with a letter.
     *
     * <p>Deliberately excludes every separator, both case ranges and any leading digit, so a value that
     * could be read as a path fragment, a JDBC URL part or a SQL identifier is not an identity at all.
     */
    private static final Pattern SHAPE = Pattern.compile("[a-z][a-z0-9]{"
            + (MIN_LENGTH - 1) + "," + (MAX_LENGTH - 1) + "}");

    private static final SecureRandom RANDOM = new SecureRandom();

    public ReportId {
        value = value == null ? "" : value.trim();
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("a report id must be an opaque lowercase alphanumeric token"
                    + " of " + MIN_LENGTH + " to " + MAX_LENGTH + " characters");
        }
    }

    /** A fresh identity, timestamped with {@code at} for ordering. */
    public static ReportId generate(Instant at) {
        Objects.requireNonNull(at, "at");
        String stamp = Long.toString(Math.max(at.toEpochMilli(), 0L), 36).toLowerCase(Locale.ROOT);
        StringBuilder generated = new StringBuilder(1 + TIMESTAMP_CHARS + RANDOM_CHARS);
        generated.append('r');
        // Left-padding to a fixed width is what makes lexicographic order chronological order.
        for (int index = stamp.length(); index < TIMESTAMP_CHARS; index++) {
            generated.append('0');
        }
        generated.append(stamp);
        for (int index = 0; index < RANDOM_CHARS; index++) {
            generated.append(HEX[RANDOM.nextInt(HEX.length)]);
        }
        return new ReportId(generated.toString());
    }

    /** Parses a caller-supplied value, or empty when it is not a valid identity. */
    public static Optional<ReportId> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ReportId(raw));
        } catch (IllegalArgumentException notAnIdentity) {
            return Optional.empty();
        }
    }

    /** The token, which is also how an id round-trips through a URL. */
    @Override
    public String toString() {
        return value;
    }
}
