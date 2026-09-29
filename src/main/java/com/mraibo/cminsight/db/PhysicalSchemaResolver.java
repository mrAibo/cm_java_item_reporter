package com.mraibo.cminsight.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps one IBM CM ItemType to <strong>every</strong> physical root segment it can have.
 *
 * <h2>The mapping, and why it is a list</h2>
 *
 * <p>IBM stores component roots in generated tables named {@code ICMUT<ComponentTypeID><SegmentID>}. For one
 * ItemType the mapping comes from the proven CM_retention read query:
 *
 * <pre>
 *   SELECT C.COMPONENTTYPEID, I.SEGMENTID
 *   FROM &lt;schema&gt;.ICMSTCOMPDEFS C
 *   JOIN &lt;schema&gt;.ICMSTITEMTYPEDEFS I ON I.ITEMTYPEID = C.ITEMTYPEID
 *   WHERE C.ITEMTYPEID = ? AND C.PARENTCOMPTYPEID = 0
 * </pre>
 *
 * <p>{@code ItemTypeID} is bound, never interpolated. The query must return <strong>exactly one</strong> root
 * component row: none means the ItemType cannot be counted, and more than one means the answer is ambiguous,
 * so both fail the mapping instead of picking a row.
 *
 * <p>For a current {@code SegmentID} of {@code N} the ItemType's roots may live in any segment
 * {@code 001..N}, and this resolver produces all of them. The reference backfill tool this project drew on
 * intentionally rejected {@code SegmentID != 1} because its <em>mutation</em> logic was single-segment;
 * analytics must not inherit that limitation, because counting only the current table silently undercounts a
 * segmented ItemType.
 *
 * <h2>Every expected table must be proven present</h2>
 *
 * <p>Each generated name is checked against IBM's {@code ICMUTnnnnnsss} shape and then probed with a complete
 * zero-row statement. A segment that is absent or not readable <strong>fails the whole mapping</strong> - it
 * is never skipped, because "we could not see one of the tables" and "the ItemType has no items there" are
 * different facts and only one of them may be reported as a count.
 *
 * <h2>Caching is per resolver instance, which is per repository context</h2>
 *
 * <p>The cache is a plain instance field: there is deliberately no static map anywhere in this class. A
 * repository switch discards the instance together with its context, so a mapping resolved against one
 * repository's schema and segment layout can never be served to another. Two instances never share an entry.
 */
public final class PhysicalSchemaResolver {

    /** The most root rows worth reading: the second one already proves the answer is ambiguous. */
    private static final int ROOT_ROWS_TO_DISTINGUISH_ONE_FROM_MANY = 2;

    private final JdbcDialect dialect;
    private final SqlQueryBuilder builder;
    private final ConcurrentHashMap<Integer, PhysicalSchema> mappings = new ConcurrentHashMap<>();

    /**
     * @param dialect the database family whose probe shape is used
     * @param schema  the validated analytics schema
     * @throws SqlUnavailableException when the schema is blank or is not a safe unquoted identifier
     */
    public PhysicalSchemaResolver(JdbcDialect dialect, String schema) throws SqlUnavailableException {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.builder = SqlQueryBuilder.forSchema(dialect, schema);
    }

    /** The validated schema this resolver maps against. */
    public String schema() {
        return builder.schema();
    }

    /** The dialect whose generated root tables and probe statements this resolver uses. */
    public JdbcDialect dialect() {
        return dialect;
    }

    /** How many ItemType mappings this instance has resolved and cached. Diagnostics only. */
    public int cachedMappingCount() {
        return mappings.size();
    }

    /**
     * Resolves the physical root tables of one ItemType, or fails.
     *
     * @param session      a leased session; the session supplies the query timeout and the retirement verdict
     * @param itemTypeId   the IBM ItemTypeID, bound into the mapping query
     * @param itemTypeName the ItemType name, carried for diagnostics and DTO construction; never a SQL
     *                     identifier
     * @throws SqlUnavailableException when the mapping cannot be proven: no row, more than one row, a value
     *                                outside IBM's documented range, or any expected segment absent or
     *                                unreadable. The message is actionable and never contains SQL text or
     *                                driver text.
     */
    public PhysicalSchema resolve(JdbcSession session, int itemTypeId, String itemTypeName)
            throws SqlUnavailableException {
        Objects.requireNonNull(session, "session");
        String name = itemTypeName == null ? "" : itemTypeName;
        if (itemTypeId <= 0) {
            throw new IllegalArgumentException("an ItemTypeID must be a positive integer, not " + itemTypeId);
        }
        PhysicalSchema cached = mappings.get(itemTypeId);
        if (cached != null) {
            return named(cached, name);
        }
        PhysicalSchema resolved = resolveUncached(session, itemTypeId, name);
        PhysicalSchema existing = mappings.putIfAbsent(itemTypeId, resolved);
        return existing == null ? resolved : named(existing, name);
    }

    /** The same mapping under a different diagnostic name, so a cache hit cannot report a stale ItemType name. */
    private static PhysicalSchema named(PhysicalSchema physicalSchema, String itemTypeName) {
        if (physicalSchema.itemTypeName().equals(itemTypeName)) {
            return physicalSchema;
        }
        return new PhysicalSchema(physicalSchema.itemTypeId(), itemTypeName,
                physicalSchema.componentTypeId(), physicalSchema.currentSegmentId(),
                physicalSchema.rootTables());
    }

    private PhysicalSchema resolveUncached(JdbcSession session, int itemTypeId, String itemTypeName)
            throws SqlUnavailableException {
        List<long[]> rootRows = readRootRows(session, itemTypeId);
        if (rootRows.isEmpty()) {
            throw new SqlUnavailableException("physical root mapping for ItemType " + itemTypeId
                    + ": no root component row exists (PARENTCOMPTYPEID = 0), so its generated root tables"
                    + " cannot be determined and the ItemType is not counted");
        }
        if (rootRows.size() > 1) {
            throw new SqlUnavailableException("physical root mapping for ItemType " + itemTypeId
                    + ": more than one root component row was returned where exactly one is required, so the"
                    + " mapping is ambiguous and the ItemType is not counted");
        }
        long componentTypeId = rootRows.get(0)[0];
        long currentSegmentId = rootRows.get(0)[1];
        if (componentTypeId < 1 || componentTypeId > SqlIdentifiers.MAX_COMPONENT_TYPE_ID) {
            throw new SqlUnavailableException("physical root mapping for ItemType " + itemTypeId
                    + ": the root COMPONENTTYPEID " + componentTypeId + " is outside the documented 1.."
                    + SqlIdentifiers.MAX_COMPONENT_TYPE_ID + " range, so no generated table name can be"
                    + " derived from it");
        }
        if (currentSegmentId < SqlIdentifiers.MIN_SEGMENT_ID
                || currentSegmentId > SqlIdentifiers.MAX_SEGMENT_ID) {
            throw new SqlUnavailableException("physical root mapping for ItemType " + itemTypeId
                    + ": the root SEGMENTID " + currentSegmentId + " is outside IBM's documented "
                    + SqlIdentifiers.MIN_SEGMENT_ID + ".." + SqlIdentifiers.MAX_SEGMENT_ID
                    + " range, so its physical tables cannot be enumerated");
        }

        int segments = (int) currentSegmentId;
        List<String> rootTables = new ArrayList<>(segments);
        for (int segment = SqlIdentifiers.MIN_SEGMENT_ID; segment <= segments; segment++) {
            rootTables.add(SqlIdentifiers.generatedRootTableName((int) componentTypeId, segment));
        }

        for (int index = 0; index < rootTables.size(); index++) {
            String table = rootTables.get(index);
            try {
                session.probe(builder.rootTableProbe(table));
            } catch (JdbcAccessException failure) {
                throw new SqlUnavailableException("physical root mapping for ItemType " + itemTypeId
                        + ": expected root segment " + (index + 1) + " of " + segments
                        + " could not be verified as present and accessible, so the whole mapping fails"
                        + " rather than counting a subset" + describe(failure), failure);
            }
        }
        return new PhysicalSchema(itemTypeId, itemTypeName, (int) componentTypeId, segments, rootTables);
    }

    /** Reads at most two root rows, so "no row", "one row" and "more than one" stay distinguishable. */
    private List<long[]> readRootRows(JdbcSession session, int itemTypeId) throws SqlUnavailableException {
        List<long[]> rows = new ArrayList<>(ROOT_ROWS_TO_DISTINGUISH_ONE_FROM_MANY);
        try {
            session.query(builder.rootComponentMapping(itemTypeId), row -> {
                if (rows.size() >= ROOT_ROWS_TO_DISTINGUISH_ONE_FROM_MANY) {
                    return; // the answer is already known to be ambiguous; draining the rest proves nothing
                }
                long componentTypeId = row.isNull(1) ? -1 : row.getLong(1);
                long segmentId = row.isNull(2) ? -1 : row.getLong(2);
                rows.add(new long[] {componentTypeId, segmentId});
            });
        } catch (JdbcAccessException failure) {
            throw new SqlUnavailableException("physical root mapping for ItemType " + itemTypeId
                    + " could not be read" + describe(failure), failure);
        }
        return rows;
    }

    /**
     * The sanitized part of a failure that may be published: the fixed operation label and, when the driver
     * reported one, its SQLSTATE. Never the driver's message, the SQL text or a name taken from an exception.
     */
    private static String describe(JdbcAccessException failure) {
        StringBuilder text = new StringBuilder(" (operation '").append(failure.operation()).append('\'');
        if (failure.hasSqlState()) {
            text.append(", SQLState=").append(failure.sqlState());
        }
        return text.append(')').toString();
    }
}
