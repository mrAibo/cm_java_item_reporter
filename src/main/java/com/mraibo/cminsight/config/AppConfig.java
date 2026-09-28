package com.mraibo.cminsight.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Typed, validating access to the plain {@code conf/application.properties} file.
 *
 * <p>No external configuration framework is used: this is {@link java.util.Properties} behind a small
 * typed facade that fails fast with an actionable message whenever a value is missing, blank or out
 * of range.
 *
 * <p>Every value is trimmed on load. A property that is present but blank is treated as unset, so a
 * trailing {@code key=} can never silently become an empty credential or an empty bind address.
 */
public final class AppConfig {

    /** Upper bound accepted for any configured duration, to keep arithmetic sane. */
    private static final long MAX_DURATION_MILLIS = Duration.ofDays(365).toMillis();

    private final Properties properties;
    private final Path sourcePath;

    private AppConfig(Properties source, Path sourcePath) {
        Objects.requireNonNull(source, "properties");
        this.properties = new Properties();
        for (String rawName : source.stringPropertyNames()) {
            if (rawName == null) {
                continue;
            }
            String name = rawName.trim();
            if (name.isEmpty()) {
                continue;
            }
            String value = source.getProperty(rawName);
            this.properties.setProperty(name, value == null ? "" : value.trim());
        }
        this.sourcePath = sourcePath;
    }

    /**
     * Loads configuration from a file.
     *
     * @throws IOException when the file is missing or unreadable
     */
    public static AppConfig load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path)) {
            throw new IOException("Configuration file not found: " + path.toAbsolutePath());
        }
        Properties loaded = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            loaded.load(in);
        }
        return new AppConfig(loaded, path.toAbsolutePath());
    }

    /** Builds configuration from already-parsed properties. Used by tests and by the self-check. */
    public static AppConfig fromProperties(Properties properties) {
        return new AppConfig(properties, null);
    }

    /** An empty configuration: every accessor falls back to its default. */
    public static AppConfig empty() {
        return new AppConfig(new Properties(), null);
    }

    /** Absolute path of the loaded file, or {@code null} when built from properties. */
    public Path sourcePath() {
        return sourcePath;
    }

    /** Returns the trimmed value, or empty when the key is absent or blank. */
    public Optional<String> find(String key) {
        Objects.requireNonNull(key, "key");
        String value = properties.getProperty(key);
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    /** Returns the trimmed value, or the (trimmed) default when the key is absent or blank. */
    public String get(String key, String defaultValue) {
        Optional<String> found = find(key);
        if (found.isPresent()) {
            return found.get();
        }
        return defaultValue == null ? null : defaultValue.trim();
    }

    /** Returns the trimmed value or fails closed with an actionable diagnostic. */
    public String require(String key) {
        return find(key).orElseThrow(() -> new ConfigException(
                "Missing required configuration '" + key + "'" + inFile()));
    }

    public int getInt(String key, int defaultValue, int min, int max) {
        Optional<String> raw = find(key);
        if (raw.isEmpty()) {
            return defaultValue;
        }
        final int value;
        try {
            value = Integer.parseInt(raw.get());
        } catch (NumberFormatException e) {
            throw new ConfigException("Invalid integer for '" + key + "': '" + raw.get() + "'" + inFile());
        }
        if (value < min || value > max) {
            throw new ConfigException("'" + key + "' must be between " + min + " and " + max
                    + " but was " + value + inFile());
        }
        return value;
    }

    public long getLong(String key, long defaultValue, long min, long max) {
        Optional<String> raw = find(key);
        if (raw.isEmpty()) {
            return defaultValue;
        }
        final long value;
        try {
            value = Long.parseLong(raw.get());
        } catch (NumberFormatException e) {
            throw new ConfigException("Invalid integer for '" + key + "': '" + raw.get() + "'" + inFile());
        }
        if (value < min || value > max) {
            throw new ConfigException("'" + key + "' must be between " + min + " and " + max
                    + " but was " + value + inFile());
        }
        return value;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Optional<String> raw = find(key);
        if (raw.isEmpty()) {
            return defaultValue;
        }
        String value = raw.get();
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new ConfigException("Invalid boolean for '" + key + "': '" + value
                + "' (expected true or false)" + inFile());
    }

    /**
     * Reads a duration. Accepts a bare number (milliseconds) or an explicit {@code ms}, {@code s},
     * {@code m}, {@code h} or {@code d} suffix.
     */
    public Duration getDuration(String key, Duration defaultValue, Duration min, Duration max) {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        Optional<String> raw = find(key);
        if (raw.isEmpty()) {
            return defaultValue;
        }
        Duration value = parseDuration(key, raw.get());
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new ConfigException("'" + key + "' must be between " + min + " and " + max
                    + " but was " + value + inFile());
        }
        return value;
    }

    private Duration parseDuration(String key, String raw) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        try {
            Duration value;
            if (text.endsWith("ms")) {
                value = Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2).trim()));
            } else if (text.endsWith("s")) {
                value = Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1).trim()));
            } else if (text.endsWith("m")) {
                value = Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1).trim()));
            } else if (text.endsWith("h")) {
                value = Duration.ofHours(Long.parseLong(text.substring(0, text.length() - 1).trim()));
            } else if (text.endsWith("d")) {
                value = Duration.ofDays(Long.parseLong(text.substring(0, text.length() - 1).trim()));
            } else {
                value = Duration.ofMillis(Long.parseLong(text));
            }
            if (value.isNegative() || value.toMillis() > MAX_DURATION_MILLIS) {
                throw new ConfigException("'" + key + "' is out of the supported duration range: '"
                        + raw + "'" + inFile());
            }
            return value;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new ConfigException("Invalid duration for '" + key + "': '" + raw
                    + "' (expected e.g. 500ms, 30s, 5m, 2h, 1d, or a bare millisecond count)" + inFile());
        }
    }

    /** All configured keys, sorted for stable diagnostics. */
    public Set<String> keys() {
        return Set.copyOf(new TreeSet<>(properties.stringPropertyNames()));
    }

    /**
     * A defensive copy of the underlying properties.
     *
     * <p>NOT redacted: if the configuration contains an inline secret, its value is in this copy in
     * plain text. Never log, serialise or otherwise expose the result, and prefer the typed accessors
     * plus {@code WebAuthSettings}/{@code SecretResolver} for anything credential-related.
     */
    public Properties raw() {
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    // Convenience accessors shared by the web layer.

    public String webBind() {
        return get("web.bind", "127.0.0.1");
    }

    public int webPort() {
        // 0 is accepted and means "let the operating system choose an ephemeral port". The launcher
        // scripts and the end-to-end socket tests rely on it; WebServer.port() reports the real port.
        return getInt("web.port", 8080, 0, 65535);
    }

    public int webThreads() {
        return getInt("web.threads", 8, 1, 256);
    }

    private String inFile() {
        return sourcePath == null ? "" : " (in " + sourcePath + ")";
    }

    @Override
    public String toString() {
        return "AppConfig[keys=" + keys().size() + (sourcePath == null ? "" : ", source=" + sourcePath) + "]";
    }
}
