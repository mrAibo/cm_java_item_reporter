package com.mraibo.cminsight.db;

import com.mraibo.cminsight.config.DatabaseVendor;

import java.util.List;

/**
 * Oracle's complete SQL operations.
 *
 * <p>The replaced dialect answered {@code oneRowSuffix()} with {@code " AND ROWNUM = 1"} - a clause that is
 * only valid inside a {@code WHERE} clause - while DB2 answered with a trailing clause. That is gone: the
 * row-limit shape is part of the complete statement each method returns here.
 *
 * <p>{@code FROM DUAL} is required by Oracle for a select list with no table, which is why the date query is
 * written out rather than shared with DB2.
 */
public final class OracleDialect implements JdbcDialect {

    @Override
    public String id() {
        return DatabaseVendor.ORACLE.name();
    }

    @Override
    public String driverClassName() {
        return JdbcDrivers.expectedDriverClass(DatabaseVendor.ORACLE);
    }

    @Override
    public String urlPrefix() {
        return JdbcDrivers.urlPrefix(DatabaseVendor.ORACLE);
    }

    @Override
    public boolean supports(String jdbcUrl) {
        return JdbcDrivers.urlMatchesVendor(DatabaseVendor.ORACLE, jdbcUrl);
    }

    /**
     * {@code SELECT TRUNC(SYSDATE) FROM DUAL}: the complete Oracle date query, with the {@code DUAL} table
     * Oracle requires.
     *
     * <p>{@code TRUNC} removes the time-of-day component so the anchor is the database's calendar date, in
     * exactly the shape DB2's {@code CURRENT DATE} answers with. Oracle's session time zone is still the
     * database's, never the application host's, which is what Goal 03 section 2.3 requires.
     */
    @Override
    public String currentDateSql() {
        return "SELECT TRUNC(SYSDATE) FROM DUAL";
    }

    /**
     * A complete query that returns no rows for an existing, readable table and fails when it is absent or
     * inaccessible.
     *
     * <p>{@code ROWNUM <= 1} is placed where it is valid - inside this statement's own {@code WHERE} clause,
     * together with the {@code 1 = 0} predicate - instead of being exposed as a fragment a caller could
     * append to a statement that has no {@code WHERE} clause at all.
     */
    @Override
    public String zeroRowProbeSql(String schema, String table) {
        return "SELECT 1 AS PRESENT FROM " + qualified(schema, table)
                + " WHERE 1 = 0 AND ROWNUM <= 1";
    }

    @Override
    public String aggregateSql(String schema, List<String> rootTables, String dateKeyColumn) {
        return ItemIdAggregateSql.sql(schema, rootTables, dateKeyColumn, dateKeyBoundaryOperator());
    }

    /** {@code schema.table}, refusing anything that is not a safe unquoted identifier. */
    static String qualified(String schema, String table) {
        return SqlIdentifiers.requireEmittedIdentifier(schema, "schema")
                + "." + SqlIdentifiers.requireEmittedIdentifier(table, "generated root table");
    }

    @Override
    public String toString() {
        return "OracleDialect";
    }
}
