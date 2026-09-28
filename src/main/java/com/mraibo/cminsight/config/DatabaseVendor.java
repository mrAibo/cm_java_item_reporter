package com.mraibo.cminsight.config;

import java.util.Locale;

/** Database products supported behind the dialect boundary. */
public enum DatabaseVendor {
    DB2,
    ORACLE;

    /**
     * Parses a configured vendor name.
     *
     * <p>Uses {@link Locale#ROOT} so the result does not depend on the host locale.
     *
     * @throws ConfigException when the value is missing or unsupported
     */
    public static DatabaseVendor parse(String value) {
        if (value == null || value.isBlank()) {
            throw new ConfigException("Repository database vendor is required (expected DB2 or ORACLE)");
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "DB2" -> DB2;
            case "ORACLE" -> ORACLE;
            default -> throw new ConfigException("Unsupported database vendor '" + value.trim()
                    + "': expected DB2 or ORACLE");
        };
    }
}
