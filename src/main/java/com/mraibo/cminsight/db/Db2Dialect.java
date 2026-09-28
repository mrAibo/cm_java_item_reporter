package com.mraibo.cminsight.db;

public final class Db2Dialect implements DatabaseDialect {
    @Override public String id() { return "DB2"; }
    @Override public String currentTimestampExpression() { return "CURRENT TIMESTAMP"; }
    @Override public String oneRowSuffix() { return " FETCH FIRST 1 ROW ONLY"; }
}
