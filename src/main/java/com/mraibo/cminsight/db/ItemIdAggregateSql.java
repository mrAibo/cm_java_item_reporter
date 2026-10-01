package com.mraibo.cminsight.db;

import java.util.List;

/**
 * The ItemID aggregate statement, assembled once for both vendors.
 *
 * <h2>Why the two dialects share this text</h2>
 *
 * <p>The aggregate is written in SQL that DB2 and Oracle both accept - a common-table expression, a
 * {@code CASE} expression, {@code COUNT(DISTINCT ...)}, {@code COALESCE} and {@code UNION}. Duplicating it
 * per vendor would create two statements that must stay semantically identical forever, which is exactly the
 * kind of drift Goal 03 section 8 exists to remove. The vendors differ where they genuinely differ (the
 * current-date query and the zero-row probe), and those stay in their own dialect.
 *
 * <h2>The shape, and why it is this shape</h2>
 *
 * <p>One branch per expected physical root segment, each {@code SELECT DISTINCT} and joined by {@code UNION}
 * (not {@code UNION ALL}), so the source set is <strong>one row per distinct ItemID</strong>:
 *
 * <ul>
 *   <li>a versioned item has several root rows sharing one ItemID, so the per-branch {@code DISTINCT}
 *       collapses them - a bare {@code COUNT(*)} over a root table is the defect this whole goal exists to
 *       prevent. The per-branch distinct is not decoration: {@code UNION} deduplicates only <em>across</em>
 *       branches, so for a single-segment ItemType (no {@code UNION} operator in the statement at all) it is
 *       the only thing collapsing an item's versions;</li>
 *   <li>the same ItemID may appear in more than one segment, and {@code UNION} collapses that too;</li>
 *   <li>each branch also carries the four window flags, so the outer {@code SUM}s see one row per logical
 *       item. The flags are a pure function of the ItemID, so every copy of the same ItemID in any segment
 *       computes the same flag values and the deduplication keeps working. That is what makes the
 *       documented <em>eight bind values per segment</em> parameter order well defined rather than
 *       accidental.</li>
 * </ul>
 *
 * <p>{@code COUNT(DISTINCT ItemID)} is used for the total as belt and braces: even if a driver or a future
 * rewrite produced a duplicate row, the logical-item count cannot inflate and is never
 * {@code COUNT(*)}.
 *
 * <p>Columns 2..5 are wrapped in {@code COALESCE(..., 0)}, so a table with rows but none inside a window
 * reports a real zero rather than {@code NULL}. A {@code NULL} would let a caller confuse "no rows matched"
 * with "not measurable", and Goal 03 section 11 forbids turning that distinction into a number.
 */
final class ItemIdAggregateSql {

    /** The ItemID column of a component-root table. */
    static final String ITEM_ID_COLUMN = "ITEMID";

    /** The four window flag columns defined inside the CTE. */
    private static final String TODAY_FLAG = "W_TODAY";
    private static final String LAST_7_DAYS_FLAG = "W_LAST_7_DAYS";
    private static final String LAST_30_DAYS_FLAG = "W_LAST_30_DAYS";
    private static final String CURRENT_YEAR_FLAG = "W_CURRENT_YEAR";

    private ItemIdAggregateSql() {
    }

    /**
     * Builds the aggregate statement.
     *
     * <p>Everything interpolated here has been validated first: the schema as an unquoted identifier and
     * every table as an IBM-generated {@code ICMUTnnnnnsss} name. The date-key column is validated the same
     * way. Nothing else reaches the text, and every boundary is a {@code ?} marker.
     *
     * @param dateKeyColumn the ItemID column whose six-character date key is compared; used as
     *                      {@code SUBSTR(<column>, 9, 6)}
     * @param boundaryOperator the dialect's lower-bound comparison, one of {@code >=} or {@code >}; the upper
     *                         bound is always the exclusive {@code <}, because the windows are half-open
     */
    static String sql(String schema, List<String> rootTables, String dateKeyColumn, String boundaryOperator) {
        String safeSchema = SqlIdentifiers.requireEmittedIdentifier(schema, "schema");
        String safeColumn = SqlIdentifiers.requireEmittedIdentifier(dateKeyColumn, "date-key column");
        String operator = requireComparisonOperator(boundaryOperator);
        if (rootTables == null || rootTables.isEmpty()) {
            throw new IllegalArgumentException("the logical-item aggregate needs at least one physical root"
                    + " table; an ItemType mapping without every expected segment must fail rather than"
                    + " silently count from no table");
        }
        String dateKey = "SUBSTR(" + safeColumn + ", 9, 6)";

        StringBuilder from = new StringBuilder();
        for (String table : rootTables) {
            String safeTable = SqlIdentifiers.requireEmittedIdentifier(table, "generated root table");
            if (from.length() > 0) {
                from.append(" UNION ");
            }
            // SELECT DISTINCT, on every branch including a single-segment ItemType. UNION alone deduplicates
            // only ACROSS branches, so with one branch a versioned item's several root rows would each carry
            // its window flag and every SUM below would count that item once per version. The flags are a pure
            // function of the ItemID, so DISTINCT (ItemID + flags) is exactly one row per logical item in
            // that segment, and the UNION then collapses the same ItemID across segments.
            from.append("SELECT DISTINCT ").append(safeColumn)
                    .append(", ").append(flag(dateKey, operator, TODAY_FLAG))
                    .append(", ").append(flag(dateKey, operator, LAST_7_DAYS_FLAG))
                    .append(", ").append(flag(dateKey, operator, LAST_30_DAYS_FLAG))
                    .append(", ").append(flag(dateKey, operator, CURRENT_YEAR_FLAG))
                    .append(" FROM ").append(safeSchema).append('.').append(safeTable);
        }

        return "WITH LOGICAL_ITEMS AS (" + from + ") SELECT COUNT(DISTINCT " + safeColumn
                + ") AS TOTAL_ITEMS"
                + ", COALESCE(SUM(" + TODAY_FLAG + "), 0) AS CREATED_TODAY"
                + ", COALESCE(SUM(" + LAST_7_DAYS_FLAG + "), 0) AS CREATED_LAST_7_DAYS"
                + ", COALESCE(SUM(" + LAST_30_DAYS_FLAG + "), 0) AS CREATED_LAST_30_DAYS"
                + ", COALESCE(SUM(" + CURRENT_YEAR_FLAG + "), 0) AS CREATED_CURRENT_YEAR"
                + " FROM LOGICAL_ITEMS";
    }

    /** One window flag: two {@code ?} markers in the order lo, hi. */
    private static String flag(String dateKey, String operator, String alias) {
        return "CASE WHEN " + dateKey + " " + operator + " ? AND " + dateKey
                + " < ? THEN 1 ELSE 0 END AS " + alias;
    }

    /**
     * Accepts only the two comparison operators a half-open window can use.
     *
     * <p>An operator is SQL syntax, not data, so it may be emitted - but only from this closed set, never as
     * a caller-supplied string.
     */
    private static String requireComparisonOperator(String operator) {
        if (">=".equals(operator) || ">".equals(operator)) {
            return operator;
        }
        throw new IllegalArgumentException("a date-key lower-bound operator must be '>=' or '>', not an"
                + " arbitrary string");
    }

    /**
     * The logical-item <strong>total only</strong>, with no date-key comparison at all.
     *
     * <p>Exists for the case Goal 03 section 10 names explicitly: the database's current date can fall
     * outside the documented 2000-2199 ItemID encoding (or an offset can walk a boundary out of it), so the
     * four window boundaries cannot be expressed. The total never uses the date key, so it is still provable,
     * and the affected time metrics are reported unavailable rather than the whole ItemType failing - or,
     * worse, being counted with invented boundaries.
     *
     * <p>Same deduplication as the full aggregate: one row per distinct ItemID across versions and segments.
     * It carries no parameters, which is exactly why it can run when no boundary can be encoded.
     */
    static String totalSql(String schema, List<String> rootTables, String dateKeyColumn) {
        String safeSchema = SqlIdentifiers.requireEmittedIdentifier(schema, "schema");
        String safeColumn = SqlIdentifiers.requireEmittedIdentifier(dateKeyColumn, "date-key column");
        if (rootTables == null || rootTables.isEmpty()) {
            throw new IllegalArgumentException("the logical-item total needs at least one physical root table;"
                    + " an ItemType mapping without every expected segment must fail rather than silently"
                    + " count from no table");
        }
        StringBuilder from = new StringBuilder();
        for (String table : rootTables) {
            String safeTable = SqlIdentifiers.requireEmittedIdentifier(table, "generated root table");
            if (from.length() > 0) {
                from.append(" UNION ");
            }
            // DISTINCT on every branch for the same reason as the full aggregate: with a single segment no
            // UNION operator is present, so a versioned item's several root rows would otherwise all be
            // counted. COUNT(DISTINCT ItemID) below is the second, independent guard.
            from.append("SELECT DISTINCT ").append(safeColumn)
                    .append(" FROM ").append(safeSchema).append('.').append(safeTable);
        }
        return "WITH LOGICAL_ITEMS AS (" + from + ") SELECT COUNT(DISTINCT " + safeColumn
                + ") AS TOTAL_ITEMS FROM LOGICAL_ITEMS";
    }
}
