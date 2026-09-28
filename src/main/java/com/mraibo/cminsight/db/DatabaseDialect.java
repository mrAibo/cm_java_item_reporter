package com.mraibo.cminsight.db;

public interface DatabaseDialect {
    String id();
    String currentTimestampExpression();
    String oneRowSuffix();
}
