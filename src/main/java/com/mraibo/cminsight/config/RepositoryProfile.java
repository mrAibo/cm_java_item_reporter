package com.mraibo.cminsight.config;

public record RepositoryProfile(
        String id,
        String displayName,
        String ssid,
        DatabaseVendor databaseVendor,
        String jdbcUrl,
        String jdbcSchema,
        String cmUserEnv,
        String cmPasswordEnv,
        String jdbcUserEnv,
        String jdbcPasswordEnv,
        String icnBaseUrl) {

    public RepositoryProfile {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Repository id is required");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("Display name is required");
        if (ssid == null || ssid.isBlank()) throw new IllegalArgumentException("SSID is required");
        if (databaseVendor == null) throw new IllegalArgumentException("Database vendor is required");
    }
}
