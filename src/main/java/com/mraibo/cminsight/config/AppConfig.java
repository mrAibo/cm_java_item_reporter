package com.mraibo.cminsight.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

public final class AppConfig {
    private final Properties properties;

    private AppConfig(Properties properties) {
        this.properties = new Properties();
        this.properties.putAll(properties);
    }

    public static AppConfig load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path)) {
            throw new IOException("Configuration file not found: " + path.toAbsolutePath());
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        }
        return new AppConfig(p);
    }

    public static AppConfig fromProperties(Properties properties) {
        return new AppConfig(properties);
    }

    public String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue).trim();
    }

    public int getInt(String key, int defaultValue, int min, int max) {
        String raw = get(key, Integer.toString(defaultValue));
        final int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + key + ": " + raw, e);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + " outside allowed range " + min + ".." + max + ": " + value);
        }
        return value;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String raw = get(key, Boolean.toString(defaultValue));
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        throw new IllegalArgumentException("Invalid boolean for " + key + ": " + raw);
    }

    public String webBind() { return get("web.bind", "127.0.0.1"); }
    public int webPort() { return getInt("web.port", 8080, 1, 65535); }
    public int webThreads() { return getInt("web.threads", 8, 1, 256); }
    public String webUser() { return get("web.auth.user", "admin"); }
    public String webPassword() { return get("web.auth.password", "admin"); }
}
