package com.mraibo.cminsight.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The one gate through which an identifier becomes SQL.
 *
 * <h2>What may become an identifier, and what may not</h2>
 *
 * <p>Exactly two kinds of name are allowed to reach a statement:
 *
 * <ul>
 *   <li>the <strong>schema</strong>, validated once here against the unquoted-identifier rule, from
 *       {@code repository.jdbc.schema} or from the live session's reported schema;</li>
 *   <li><strong>generated component-root tables</strong> ({@code ICMUTnnnnnsss}), validated against IBM's
 *       documented shape.</li>
 * </ul>
 *
 * <p>Everything else - a request parameter, an ItemType name, a classification label, a table name from a
 * message or a configuration value with a dot or a space in it - is refused. This version supports safe
 * unquoted identifiers only; a name that would need quoted-identifier semantics is reported as unavailable
 * (via {@link SqlUnavailableException}) with an actionable reason, never interpolated.
 *
 * <p>The IBM CM <em>metadata</em> tables are addressed by {@link #metadataTable(String)} from a closed list of
 * constants, so a caller cannot smuggle an arbitrary name in through the same door.
 *
 * <p>Every value that is data - an {@code ItemTypeID}, a date-key boundary - travels as a bind parameter.
 * The {@link AggregateQuery} this class returns carries its SQL and its values together, so a caller cannot
 * bind them in a different order than the markers expect.
 */
public final class SqlQueryBuilder {

    /** The ItemID column of a component-root table; the date key is read from it, never from CreateTS. */
    public static final String ITEM_ID_COLUMN = "ITEMID";

    /**
     * The number of boundary values one root segment needs: today lo/hi, 7-day lo/hi, 30-day lo/hi and
     * year lo/hi - the order {@link JdbcDialect#aggregateSql} documents and
     * {@code ScanWindows.parameters()} produces.
     */
    public static final int WINDOW_BOUNDARY_COUNT_PER_SEGMENT = 8;

    /** Column 1 of the aggregate: the distinct logical-item count. */
    public static final String TOTAL_ITEMS_COLUMN = "TOTAL_ITEMS";

    /**
     * Columns 2..5 of the aggregate, in order: created today, in the last 7 days, in the last 30 days and in
     * the current calendar year.
     */
    public static final List<String> WINDOW_COLUMNS = List.of(
            "CREATED_TODAY", "CREATED_LAST_7_DAYS", "CREATED_LAST_30_DAYS", "CREATED_CURRENT_YEAR");

    /**
     * The IBM CM metadata tables this analytics layer is allowed to name.
     *
     * <p>A closed list, not a pattern: these two tables carry the ItemType definition and its root component
     * types, and nothing else in the analytics path needs a metadata table.
     */
    private static final List<String> CM_METADATA_TABLES = List.of("ICMSTITEMTYPEDEFS", "ICMSTCOMPDEFS");

    private final JdbcDialect dialect;
    private final String schema;

    private SqlQueryBuilder(JdbcDialect dialect, String schema) {
        this.dialect = dialect;
        this.schema = schema;
    }

    /**
     * A builder for one validated schema.
     *
     * @throws SqlUnavailableException when the schema is blank or is not a safe unquoted identifier
     */
    public static SqlQueryBuilder forSchema(JdbcDialect dialect, String schema) throws SqlUnavailableException {
        Objects.requireNonNull(dialect, "dialect");
        return new SqlQueryBuilder(dialect, SqlIdentifiers.requireSafeSchema(schema, "repository.jdbc.schema"));
    }

    public JdbcDialect dialect() {
        return dialect;
    }

    /** The validated schema every statement from this builder is qualified with. */
    public String schema() {
        return schema;
    }

    /**
     * The qualified name of one IBM CM metadata table.
     *
     * @throws IllegalArgumentException when the name is not one of the fixed metadata tables, which is a
     *                                  programming error rather than a configuration problem
     */
    public String metadataTable(String cmMetadataTable) {
        if (cmMetadataTable == null || !CM_METADATA_TABLES.contains(cmMetadataTable)) {
            throw new IllegalArgumentException("only the fixed IBM CM metadata tables " + CM_METADATA_TABLES
                    + " may be qualified by this builder");
        }
        return schema + "." + cmMetadataTable;
    }

    /**
     * The proven ItemType-to-root-component mapping query.
     *
     * <p>Adapted from the working CM_retention read query and deliberately unchanged in shape: it joins the
     * component-type definitions to the ItemType definitions and keeps only rows whose
     * {@code PARENTCOMPTYPEID} is {@code 0}, i.e. the root component type. {@code C.ITEMTYPEID} is a
     * {@code ?} marker - bound, never interpolated.
     */
    public String rootComponentMappingSql() {
        return "SELECT C.COMPONENTTYPEID, I.SEGMENTID"
                + " FROM " + metadataTable("ICMSTCOMPDEFS") + " C"
                + " JOIN " + metadataTable("ICMSTITEMTYPEDEFS") + " I ON I.ITEMTYPEID = C.ITEMTYPEID"
                + " WHERE C.ITEMTYPEID = ? AND C.PARENTCOMPTYPEID = 0";
    }

    /** The mapping query with its one bound {@code ItemTypeID}, ready for a leased session. */
    public AggregateQuery rootComponentMapping(int itemTypeId) {
        if (itemTypeId <= 0) {
            throw new IllegalArgumentException("an ItemTypeID must be a positive integer, not " + itemTypeId);
        }
        return new AggregateQuery(rootComponentMappingSql(), List.of(itemTypeId),
                "resolve root component mapping");
    }

    /**
     * The qualified name of a generated root table, after validating IBM's generated-name shape.
     *
     * @throws SqlUnavailableException when {@code generatedRootTable} is not {@code ICMUTnnnnnsss}
     */
    public String qualifiedRootTable(String generatedRootTable) throws SqlUnavailableException {
        return schema + "." + SqlIdentifiers.requireGeneratedRootTableName(generatedRootTable);
    }

    /**
     * The complete zero-row probe statement for one expected root segment.
     *
     * <p>A successful execution proves the table exists and is readable; a failure means it is absent or not
     * accessible, which fails the whole ItemType mapping rather than skipping that segment.
     */
    public AggregateQuery rootTableProbe(String generatedRootTable) throws SqlUnavailableException {
        String table = SqlIdentifiers.requireGeneratedRootTableName(generatedRootTable);
        return new AggregateQuery(dialect.zeroRowProbeSql(schema, table), List.of(), "probe root table");
    }

    /**
     * The per-ItemType logical-item aggregate, with its boundary values bound in the documented order.
     *
     * <p>{@code perSegmentBoundaries} is the eight-value window tuple for <em>one</em> segment
     * ({@code ScanWindows.parameters()}); this method repeats it once per physical root segment, because the
     * generated statement evaluates the window flags inside every segment branch. That repetition is why the
     * value list and the SQL cannot drift: both are produced here and counted by {@link AggregateQuery}.
     *
     * @param physicalSchema      the resolved mapping - every expected segment, never only the current one
     * @param perSegmentBoundaries the window boundaries for one segment, in
     *                            {@link JdbcDialect#aggregateSql}'s documented order
     */
    public AggregateQuery aggregate(PhysicalSchema physicalSchema, List<Object> perSegmentBoundaries) {
        Objects.requireNonNull(physicalSchema, "physicalSchema");
        List<Object> oneSegment = List.copyOf(Objects.requireNonNull(perSegmentBoundaries,
                "perSegmentBoundaries"));
        if (oneSegment.size() != WINDOW_BOUNDARY_COUNT_PER_SEGMENT) {
            throw new IllegalArgumentException("a segment's window boundaries are "
                    + WINDOW_BOUNDARY_COUNT_PER_SEGMENT + " values (today lo/hi, 7-day lo/hi, 30-day lo/hi,"
                    + " year lo/hi) but " + oneSegment.size() + " were supplied");
        }
        List<String> rootTables = validatedRootTables(physicalSchema);
        String sql = dialect.aggregateSql(schema, rootTables, ITEM_ID_COLUMN);
        List<Object> parameters = new ArrayList<>(oneSegment.size() * rootTables.size());
        for (int segment = 0; segment < rootTables.size(); segment++) {
            parameters.addAll(oneSegment);
        }
        return new AggregateQuery(sql, parameters, "logical item aggregate");
    }

    /**
     * The total-only aggregate, for the one case where the window boundaries cannot be expressed.
     *
     * <p>Goal 03 section 10: when the database's current date (or a boundary it implies) falls outside the
     * documented ItemID date encoding, the four time metrics are unavailable but the total is still
     * provable. This query has no {@code ?} marker at all, so it runs where {@link #aggregate} cannot.
     *
     * <p>Result: one row, one column ({@link #TOTAL_ITEMS_COLUMN}).
     */
    public AggregateQuery totalItems(PhysicalSchema physicalSchema) {
        Objects.requireNonNull(physicalSchema, "physicalSchema");
        return new AggregateQuery(dialect.totalItemsSql(schema, validatedRootTables(physicalSchema),
                ITEM_ID_COLUMN), List.of(), "logical item total");
    }

    /**
     * The physical root tables of a resolved mapping, re-validated at the last point before they become SQL
     * text: the gate must not depend on the caller having been careful.
     */
    private static List<String> validatedRootTables(PhysicalSchema physicalSchema) {
        List<String> rootTables = new ArrayList<>(physicalSchema.rootTables().size());
        for (String table : physicalSchema.rootTables()) {
            if (!SqlIdentifiers.isGeneratedRootTableName(table)) {
                throw new IllegalArgumentException("a physical root table of ItemType "
                        + physicalSchema.itemTypeId() + " is not a generated ICMUTnnnnnsss name, so it was"
                        + " refused rather than interpolated");
            }
            rootTables.add(table);
        }
        return List.copyOf(rootTables);
    }

    /** True when {@code cmMetadataTable} is one of the fixed metadata tables this builder may qualify. */
    public static boolean isMetadataTable(String cmMetadataTable) {
        return cmMetadataTable != null && CM_METADATA_TABLES.contains(cmMetadataTable);
    }
}
