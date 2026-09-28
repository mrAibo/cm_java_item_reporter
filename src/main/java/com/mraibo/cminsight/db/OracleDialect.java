package com.mraibo.cminsight.db;

public final class OracleDialect implements DatabaseDialect {
    @Override public String id() { return "ORACLE"; }
    @Override public String currentTimestampExpression() { return "CURRENT_TIMESTAMP"; }
    @Override public String oneRowSuffix() { return " AND ROWNUM = 1"; }
}
