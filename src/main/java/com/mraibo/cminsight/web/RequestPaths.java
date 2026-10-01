package com.mraibo.cminsight.web;

import java.util.regex.Pattern;

/**
 * The one implementation of the request-path and request-parameter validation every route family shares.
 *
 * <h2>Why this exists (and why there is only one copy)</h2>
 *
 * <p>{@link Router} matches exact paths and one prefix level and has no path-parameter syntax, so a route
 * like {@code /api/history/{id}} is registered once as a PREFIX and the handler parses its own trailing
 * segment. Every family that does this needs the same three rules: take the trailing segment without
 * truncating a deeper path into its first component, validate an identifier-shaped value, and bound a
 * caller-supplied limit. Written once here, those rules cannot drift between the CM routes, the history
 * routes and the report routes - which is exactly how a validation gap appears when each family keeps a
 * private copy.
 *
 * <p>Nothing here reads state, touches a service or writes a response: these are pure value functions, so a
 * malformed request is refused by the caller with a clean {@code 400} before any service is reached.
 */
final class RequestPaths {

    /** Longest accepted ItemType or retention policy name. */
    static final int MAX_NAME_LENGTH = 128;

    /**
     * The repository-id and history/report-cursor allow-list: an alphanumeric first character, then letters,
     * digits, dot, underscore and dash.
     *
     * <p>This is the shape {@link com.mraibo.cminsight.config.RepositoryProfile} validates its own id with, and
     * the shape a generated report or history identity uses. It refuses traversal, an encoded traversal, a
     * slash or backslash (which would make a value a path), a percent sign (so a double-decoded value cannot
     * be smuggled through) and every SQL metacharacter, without needing a rule per attack.
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /**
     * The ItemType/retention name allow-list: an alphanumeric first character, then letters, digits, space,
     * dot, underscore and dash.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,127}");

    /** The ItemType-id shape: a positive integer, nothing else. */
    private static final Pattern POSITIVE_INT = Pattern.compile("[0-9]{1,10}");

    private RequestPaths() {
    }

    /**
     * The trailing path segment after {@code base}, or {@code null} when the request targets {@code base}
     * itself.
     *
     * <p>The router matched this handler through {@code base} as a prefix, so a non-null result always has at
     * least one character; a deeper path (one containing another slash) is rejected by the caller's own
     * validator rather than being silently truncated to its first segment.
     */
    static String trailingSegment(String path, String base) {
        if (path == null || base == null || !path.startsWith(base + "/")) {
            return null;
        }
        return path.substring(base.length() + 1);
    }

    /**
     * A validated ItemType or retention policy name, or {@code null} when the segment is not one.
     *
     * <p>An allow-list rather than a block-list of attacks: letters, digits, space, dot, underscore and dash,
     * at most {@value #MAX_NAME_LENGTH} characters. A name outside the list is simply not addressable.
     */
    static String safeName(String raw) {
        if (raw == null) {
            return null;
        }
        String candidate = raw.trim();
        if (candidate.isEmpty() || candidate.length() > MAX_NAME_LENGTH || candidate.contains("..")
                || candidate.indexOf('/') >= 0 || candidate.indexOf('\\') >= 0) {
            return null;
        }
        return SAFE_NAME.matcher(candidate).matches() ? candidate : null;
    }

    /**
     * A validated opaque id - a repository id, a report id or a page cursor - or {@code null} when the value
     * is absent, blank or not identifier-shaped.
     */
    static String safeId(String raw) {
        if (raw == null) {
            return null;
        }
        String candidate = raw.trim();
        if (candidate.isEmpty() || candidate.contains("..")) {
            return null;
        }
        return SAFE_ID.matcher(candidate).matches() ? candidate : null;
    }

    /**
     * A validated positive integer value, or {@code -1} when it is not one.
     *
     * <p>Used for an ItemType id in a path: a value that is not a plain decimal integer is refused rather
     * than parsed loosely, so no name, path fragment or SQL text can ever reach the service behind it.
     */
    static int positiveInt(String raw) {
        if (raw == null) {
            return -1;
        }
        String candidate = raw.trim();
        if (!POSITIVE_INT.matcher(candidate).matches()) {
            return -1;
        }
        try {
            long value = Long.parseLong(candidate);
            return value >= 1L && value <= Integer.MAX_VALUE ? (int) value : -1;
        } catch (NumberFormatException notAnInteger) {
            return -1;
        }
    }

    /**
     * A caller-supplied page size, clamped into {@code [1, max]}.
     *
     * <p>The maximum is enforced, not suggested: a list API that returned whatever it was asked for would be
     * an unbounded read dressed as a bounded one. A value that is not a decimal integer is refused by the
     * caller with a {@code 400}; this method is only reached for a syntactically valid one.
     */
    static int boundedLimit(int requested, int defaultValue, int max) {
        if (requested <= 0) {
            return defaultValue;
        }
        return Math.min(requested, max);
    }
}
