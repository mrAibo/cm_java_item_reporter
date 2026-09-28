package com.mraibo.cminsight.config;

public enum DatabaseVendor {
    DB2,
    ORACLE;

    public static DatabaseVendor parse(String value) {
        if (value == null) throw new IllegalArgumentException("Database vendor is required");
        return switch (value.trim().toUpperCase()) {
            case "DB2" -> DB2;
            case "ORACLE" -> ORACLE;
            default -> throw new IllegalArgumentException("Unsupported database vendor: " + value);
        };
    }
}
