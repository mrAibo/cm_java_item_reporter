package com.mraibo.cminsight.db;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The single place where a value becomes a SQL identifier.
 *
 * <h2>Why this is a validator and not a quoter</h2>
 *
 * <p>Analytics SQL is assembled from a configured schema and from IBM CM's own generated table names. No
 * request parameter, ItemType name, business classification or any other free text may reach a statement as
 * an identifier, so the rule here is <em>refusal</em>: a value is either provably safe as an unquoted
 * identifier or it is rejected with an actionable reason. Nothing is escaped, rewritten or trimmed into
 * safety - a value that needs quoting to work is a value this version has not proven it can handle, and
 * Goal 03 section 8 says to report that as unavailable instead of interpolating it.
 *
 * <p>Values that PASS this rule still bind as parameters where a value (as opposed to a name) is meant: the
 * resolver always binds {@code ItemTypeID}, never interpolates it.
 *
 * <h2>The two accepted shapes</h2>
 *
 * <ul>
 *   <li><b>schema</b>: an unquoted SQL identifier, {@code [A-Za-z][A-Za-z0-9_]{0,127}}. Both DB2 and
 *       Oracle fold an unquoted identifier to upper case, so a configured lower-case name addresses the
 *       upper-case object - which is the only semantics this version claims. A schema that contains a
 *       space, a dash, a quote, a dot or a non-ASCII character needs quoted-identifier resolution that has
 *       not been proven here, and is refused.</li>
 *   <li><b>generated component-root table</b>: {@code ICMUT} + 5-digit zero-padded ComponentTypeID +
 *       3-digit segment id, uppercase, matching {@code ICMUT[0-9]{5}[0-9]{3}}. IBM documents the component
 *       root table name as {@code ICMUTnnnnnsss} (its own example is {@code ICMUTnnnnn001}), so a name that
 *       does not match that shape is not a table this code is allowed to name.</li>
 * </ul>
 *
 * <p>The IBM CM <em>metadata</em> tables ({@code ICMSTCOMPDEFS}, {@code ICMSTITEMTYPEDEFS}) are addressed
 * only through {@link SqlQueryBuilder#metadataTable(String)}, which accepts them from a closed list of
 * constants rather than from a caller-supplied string.
 */
public final class SqlIdentifiers {

    /** IBM's documented component-root table name prefix. */
    public static final String ROOT_TABLE_PREFIX = "ICMUT";

    /** First segment id of a segmented ItemType. */
    public static final int MIN_SEGMENT_ID = 1;

    /** Last segment id IBM documents for a segmented ItemType. */
    public static final int MAX_SEGMENT_ID = 36;

    /** Largest ComponentTypeID representable in the five digits of a generated root table name. */
    public static final int MAX_COMPONENT_TYPE_ID = 99_999;

    /** Longest unquoted identifier this version claims to handle (both vendors allow at least this). */
    public static final int MAX_IDENTIFIER_LENGTH = 128;

    private static final Pattern SCHEMA = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,127}");

    private static final Pattern GENERATED_ROOT_TABLE = Pattern.compile("ICMUT[0-9]{5}[0-9]{3}");

    private SqlIdentifiers() {
    }

    /** True when this value is provably safe to interpolate as an unquoted schema identifier. */
    public static boolean isSafeUnquotedIdentifier(String value) {
        return value != null && SCHEMA.matcher(value).matches();
    }

    /** True when this value is safe to interpolate as a plain unquoted identifier, for example a column. */
    public static boolean isSafeIdentifier(String value) {
        return value != null && SCHEMA.matcher(value).matches();
    }

    /**
     * The identifier gate every dialect passes its inputs through before emitting them.
     *
     * <p>Stricter than the {@code [A-Za-z0-9_]} minimum a dialect could get away with, because a name that
     * does not start with a letter is not a name either vendor would fold to the object this code means.
     * Nothing is quoted, escaped or repaired: an unusable name is refused, which is Goal 03 section 8's rule
     * for a value whose quoted-identifier semantics this version has not proven.
     *
     * @param role what the value is, so the refusal names the offending input without echoing it
     * @throws IllegalArgumentException when the value is not a safe unquoted identifier; the message never
     *                                  contains the value, so it is safe to publish
     */
    public static String requireEmittedIdentifier(String value, String role) {
        if (!isSafeIdentifier(value)) {
            throw new IllegalArgumentException("the " + role + " passed to a SQL statement is not a safe"
                    + " unquoted identifier ([A-Za-z][A-Za-z0-9_]{0,127}), so it was refused rather than"
                    + " interpolated");
        }
        return value;
    }

    /** True when this value matches IBM's generated {@code ICMUTnnnnnsss} root table shape. */
    public static boolean isGeneratedRootTableName(String value) {
        return value != null && GENERATED_ROOT_TABLE.matcher(value).matches();
    }

    /**
     * The generated root table name for one component type and segment.
     *
     * @throws IllegalArgumentException for a component type or segment outside the documented range, which
     *                                  is a programming error: the resolver validates the database's answer
     *                                  before it ever gets here
     */
    public static String generatedRootTableName(int componentTypeId, int segmentId) {
        if (componentTypeId < 1 || componentTypeId > MAX_COMPONENT_TYPE_ID) {
            throw new IllegalArgumentException("ComponentTypeID " + componentTypeId
                    + " is outside the documented 1.." + MAX_COMPONENT_TYPE_ID + " range");
        }
        if (segmentId < MIN_SEGMENT_ID || segmentId > MAX_SEGMENT_ID) {
            throw new IllegalArgumentException("SegmentID " + segmentId
                    + " is outside the documented " + MIN_SEGMENT_ID + ".." + MAX_SEGMENT_ID + " range");
        }
        return String.format(Locale.ROOT, "%s%05d%03d", ROOT_TABLE_PREFIX, componentTypeId, segmentId);
    }

    /**
     * Validates a schema name taken from configuration.
     *
     * @param origin the configuration key the value came from, named in the reason so the operator knows
     *               what to change
     * @throws SqlUnavailableException when the value is blank or is not a safe unquoted identifier
     */
    public static String requireSafeSchema(String candidate, String origin) throws SqlUnavailableException {
        if (candidate == null || candidate.isBlank()) {
            throw new SqlUnavailableException(origin + " is not set, so the IBM CM metadata tables cannot be"
                    + " addressed; set it to the CM schema (for example ICMADMIN)");
        }
        if (!isSafeUnquotedIdentifier(candidate)) {
            throw new SqlUnavailableException(origin + " is not a safe unquoted SQL identifier, so it is not"
                    + " interpolated. Statistics need a schema that matches [A-Za-z][A-Za-z0-9_]{0,127};"
                    + " quoted-identifier schemes are not supported by this version");
        }
        return candidate;
    }

    /**
     * The schema analytics must use, from configuration or, when that is absent, from the live session.
     *
     * <p>This is Goal 03 section 8's whole rule in one place:
     *
     * <ol>
     *   <li>a configured {@code repository.jdbc.schema} wins, after the same validation;</li>
     *   <li>otherwise the schema the driver reports for the live connection is used, after the SAME
     *       validation - a driver answer is not automatically an identifier;</li>
     *   <li>a blank or unusable answer from both is UNAVAILABLE. It is deliberately not defaulted to
     *       {@code ICMADMIN}: guessing the schema would silently address a different, real schema on a
     *       database where that name exists.</li>
     * </ol>
     *
     * @param configuredSchema     {@code repository.jdbc.schema}, or {@code null} when the key is absent
     * @param sessionReportedSchema the live session's schema (JDBC {@code Connection.getSchema()}), or
     *                             {@code null}/{@code ""} when it reported none
     */
    public static String resolveSchema(String configuredSchema, String sessionReportedSchema)
            throws SqlUnavailableException {
        if (configuredSchema != null && !configuredSchema.isBlank()) {
            return requireSafeSchema(configuredSchema, "repository.jdbc.schema");
        }
        if (sessionReportedSchema == null || sessionReportedSchema.isBlank()) {
            throw new SqlUnavailableException("repository.jdbc.schema is not set and the JDBC session"
                    + " reported no current schema, so the IBM CM metadata tables cannot be addressed. Set"
                    + " repository.jdbc.schema to the CM schema (the CM database user's schema is usually"
                    + " ICMADMIN)");
        }
        if (!isSafeUnquotedIdentifier(sessionReportedSchema)) {
            throw new SqlUnavailableException("the JDBC session reported a current schema that is not a safe"
                    + " unquoted SQL identifier, so it is not interpolated. Set repository.jdbc.schema"
                    + " explicitly instead of relying on the driver's answer");
        }
        return sessionReportedSchema;
    }

    /**
     * Validates a generated root table name before it is interpolated.
     *
     * @throws SqlUnavailableException when the name does not match {@code ICMUTnnnnnsss}
     */
    public static String requireGeneratedRootTableName(String candidate) throws SqlUnavailableException {
        if (!isGeneratedRootTableName(candidate)) {
            throw new SqlUnavailableException("a generated component-root table name did not match the"
                    + " documented ICMUT + 5-digit ComponentTypeID + 3-digit segment shape, so it was not"
                    + " used in a statement");
        }
        return candidate;
    }
}
