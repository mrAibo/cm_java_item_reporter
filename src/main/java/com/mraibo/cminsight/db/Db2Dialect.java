package com.mraibo.cminsight.db;

import com.mraibo.cminsight.config.DatabaseVendor;

import java.util.List;

/**
 * DB2's complete SQL operations.
 *
 * <p>Every method returns a whole statement. There is deliberately no {@code oneRowSuffix()} and no other
 * fragment whose validity would depend on the caller having supplied a {@code WHERE} clause: the bootstrap
 * dialect's failure was exactly that two implementations of one method disagreed about what a suffix is.
 *
 * <p>Identifier safety is delegated to {@link SqlIdentifiers}: only an unquoted schema identifier and an
 * IBM-generated {@code ICMUTnnnnnsss} table name are ever emitted, and an input that does not match is
 * refused rather than quoted, escaped or repaired.
 */
public final class Db2Dialect implements JdbcDialect {

    @Override
    public String id() {
        return DatabaseVendor.DB2.name();
    }

    @Override
    public String driverClassName() {
        // Never a literal here: the IBM driver class name may not appear contiguously under src/main/java,
        // which tests/shell/ibm_guard.sh enforces as the SDK-isolation rule.
        return JdbcDrivers.expectedDriverClass(DatabaseVendor.DB2);
    }

    @Override
    public String urlPrefix() {
        return JdbcDrivers.urlPrefix(DatabaseVendor.DB2);
    }

    @Override
    public boolean supports(String jdbcUrl) {
        return JdbcDrivers.urlMatchesVendor(DatabaseVendor.DB2, jdbcUrl);
    }

    /**
     * {@code SELECT CURRENT DATE FROM SYSIBM.SYSDUMMY1}: DB2's complete one-row, one-column date query.
     *
     * <h2>Why the SELECT form rather than {@code VALUES CURRENT DATE}</h2>
     *
     * <p>{@code VALUES CURRENT DATE} is valid DB2 and shorter, and it was the first version of this method.
     * It is wrong here, and the reason is a real integration property rather than style: the analytics query
     * surface admits read-only statements whose first keyword is {@code SELECT} or {@code WITH} - that is the
     * structural guarantee Goal 03 section 7 rests on - so {@code JdbcSession} refuses a {@code VALUES}
     * statement before it reaches the driver. A DB2 scan would therefore never have been able to read its
     * calendar anchor and every DB2 scan would have failed. Reproduced end to end against a real
     * {@code JdbcSession} and the fake driver by the test unit before this was changed.
     *
     * <p>{@code SYSIBM.SYSDUMMY1} is DB2's documented one-row dummy table, the structural counterpart of
     * Oracle's {@code DUAL} used below, so both vendors read their anchor through the same SELECT-only rule.
     * {@code CURRENT DATE} (not {@code CURRENT TIMESTAMP}) has no time-of-day component, so the scan's single
     * anchor does not depend on when in the day it ran.
     */
    @Override
    public String currentDateSql() {
        return "SELECT CURRENT DATE FROM SYSIBM.SYSDUMMY1";
    }

    /**
     * A complete query that returns no rows for an existing, readable table and fails when the table is
     * absent or inaccessible.
     *
     * <p>{@code FETCH FIRST 1 ROW ONLY} is DB2's own row-limit clause and belongs to this statement, not to a
     * caller's {@code WHERE} clause - the distinction the replaced dialect got wrong.
     */
    @Override
    public String zeroRowProbeSql(String schema, String table) {
        return "SELECT 1 AS PRESENT FROM " + qualified(schema, table)
                + " WHERE 1 = 0 FETCH FIRST 1 ROW ONLY";
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
        return "Db2Dialect";
    }
}
