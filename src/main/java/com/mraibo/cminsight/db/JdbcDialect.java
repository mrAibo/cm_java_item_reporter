package com.mraibo.cminsight.db;

/**
 * One database family's SQL, expressed as <strong>complete operations</strong> rather than as fragments a
 * caller has to assemble correctly.
 *
 * <h2>Why this replaces the bootstrap dialect</h2>
 *
 * <p>The earlier three-method dialect exposed {@code oneRowSuffix()}, and the two implementations disagreed
 * about what a "suffix" even is: DB2 returned {@code " FETCH FIRST 1 ROW ONLY"} - a trailing clause - while
 * Oracle returned {@code " AND ROWNUM = 1"}, which is only valid <em>inside</em> a {@code WHERE} clause. A
 * caller that appended either one to a statement without the matching context produced invalid SQL, and
 * nothing in the type said so. Those two methods are deliberately gone: every operation here is a whole
 * statement, so a caller cannot assemble an invalid combination out of correct-looking pieces, and the
 * compiler stops anyone re-introducing a context-dependent fragment.
 *
 * <h2>Identifier safety</h2>
 *
 * <p>Implementations accept a schema and generated table names that have <strong>already</strong> been
 * validated by the caller against a strict identifier rule. An implementation must still refuse to emit a
 * name containing anything outside {@code [A-Za-z0-9_]} and must never quote, escape or "repair" a hostile
 * name into valid SQL - refusing is the only safe answer. No request parameter, ItemType name, or
 * user-supplied string may ever reach these methods as an identifier.
 *
 * <h2>Parameter binding</h2>
 *
 * <p>Every value that comes from data is a bind parameter. ItemType ids, date-key boundaries and segment
 * counts are never interpolated into SQL text, and the methods here return SQL containing {@code ?}
 * markers only. Their order is part of each method's contract.
 */
public interface JdbcDialect {

    /** Stable short id, matching the configuration vendor name: {@code "DB2"} or {@code "ORACLE"}. */
    String id();

    /** The vendor's JDBC driver class name, for local readiness discovery only. */
    String driverClassName();

    /** The only JDBC URL prefix this dialect accepts, e.g. {@code "jdbc:db2:"}. */
    String urlPrefix();

    /** True when {@code jdbcUrl} belongs to this dialect's vendor family. Never a connection attempt. */
    boolean supports(String jdbcUrl);

    /**
     * A complete query returning exactly one row and one column: the database's current date.
     *
     * <p>Completeness is the point: DB2 needs no {@code FROM} clause, while Oracle needs
     * {@code FROM DUAL}, and the previous design left that difference to the caller. The caller reads the
     * single date value and turns it into the scan's one calendar anchor.
     */
    String currentDateSql();

    /**
     * A complete query that returns no rows, used to prove a generated root table exists and is readable.
     *
     * <p>The caller passes an already-validated {@code schema} and generated table name. This replaces the
     * old suffix approach: the zero-row shape is part of the statement, not a fragment appended to one.
     */
    String zeroRowProbeSql(String schema, String table);

    /**
     * SQL for the per-ItemType logical-item aggregate.
     *
     * <p><strong>Semantics the caller relies on</strong>, and which every implementation must preserve
     * exactly whatever the vendor-specific text looks like:
     *
     * <ul>
     *   <li>the source set is one {@code ItemID} per row, deduplicated across versions <em>and</em> across
     *       all physical root segments - a versioned item has several root rows sharing one ItemID, so a
     *       bare {@code COUNT(*)} is not a logical-item count;</li>
     *   <li>column 1 is the total logical items;</li>
     *   <li>columns 2..5 are, in order, the counts created today, in the last 7 days, in the last 30 days
     *       and in the current calendar year;</li>
     *   <li>each of columns 2..5 is computed by comparing the ItemID's encoded date key against two
     *       boundaries using the comparison operator {@link #dateKeyBoundaryOperator()} documents;</li>
     *   <li>{@code ?} parameters appear in ascending column order, two per segment:</li>
     * </ul>
     *
     * <pre>
     *   ? ? ? ? ? ? ? ?   (today lo, today hi, 7-day lo, 7-day hi, 30-day lo, 30-day hi, year lo, year hi)
     *   ... repeated once per segment, in the order the segments were supplied
     * </pre>
     *
     * <p>Columns 2..5 must never be {@code NULL} for a table that has rows: implementations coalesce a
     * missing count to {@code 0} so a caller cannot mistake "no rows matched" for "not measurable".
     *
     * @param schema        an already-validated schema identifier
     * @param rootTables    one or more already-validated generated root table names, in segment order;
     *                      never empty
     * @param dateKeyColumn the column expression holding the ItemID, used as
     *                      {@code SUBSTR(<expr>, 9, 6)} by the implementations
     */
    String aggregateSql(String schema, java.util.List<String> rootTables, String dateKeyColumn);

    /**
     * How the encoded date key is compared against a boundary.
     *
     * <p>IBM encodes the ItemID date in six fixed-width characters, so the key sorts chronologically and a
     * plain relational comparison is correct on both vendors - there is no need to decode it in SQL. The
     * method exists so this fact is stated once, testably, instead of being assumed at four call sites.
     */
    default String dateKeyBoundaryOperator() {
        return ">=";
    }

    /**
     * SQL for the logical-item <strong>total only</strong>, with no {@code ?} marker at all.
     *
     * <p>Goal 03 section 10 requires that a provable total survives an unrepresentable window boundary: the
     * database's current date can fall outside the documented 2000-2199 ItemID encoding, or an offset can
     * walk a boundary out of it, and the four time metrics are then unavailable while the total is not. The
     * total never uses the date key, so it is exactly the query that can still run - and running it is
     * honest, whereas substituting a boundary or failing the ItemType would not be.
     *
     * <p>The result is one row with one column: the distinct ItemID count across versions and all segments.
     *
     * @param schema        an already-validated schema identifier
     * @param rootTables    one or more already-validated generated root table names, in segment order
     * @param dateKeyColumn the ItemID column expression
     */
    default String totalItemsSql(String schema, java.util.List<String> rootTables, String dateKeyColumn) {
        return ItemIdAggregateSql.totalSql(schema, rootTables, dateKeyColumn);
    }
}
