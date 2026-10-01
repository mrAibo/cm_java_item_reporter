package com.mraibo.cminsight.config;

import java.util.Objects;

/**
 * Describes <em>how</em> a credential is obtained without exposing the credential itself.
 *
 * <p>Deliberately a class rather than a record: a record's generated {@code toString()} would print
 * every component, which for an inline secret would leak the value into logs, diagnostics and JSON.
 * Here {@link #toString()} delegates to {@link #describe()}, which never contains the value, and the
 * value is only reachable through the package-private {@link #value()} accessor.
 */
public final class SecretRef {

    /** Where a credential comes from. */
    public enum Source {
        /** Value read from an environment variable. */
        ENVIRONMENT,
        /** Value read from a file below the configured secrets directory. */
        FILE,
        /** Value written directly into the configuration (discouraged). */
        INLINE,
        /** Built-in development default was applied. */
        DEFAULT,
        /** Configured, but no value could be resolved. */
        MISSING
    }

    private final Source source;
    private final String locator;
    private final String value;

    SecretRef(Source source, String locator, String value) {
        this.source = Objects.requireNonNull(source, "source");
        this.locator = locator == null ? "" : locator;
        this.value = value;
    }

    /** A reference with no value, used for the MISSING and descriptive cases. */
    SecretRef(Source source, String locator) {
        this(source, locator, null);
    }

    public Source source() {
        return source;
    }

    /** Environment variable name, secret file name, or a safe human description. Never a value. */
    public String locator() {
        return locator;
    }

    /** True when a usable value is available. */
    public boolean resolved() {
        return source != Source.MISSING && value != null && !value.isEmpty();
    }

    /** The secret value. Package-private so only the configuration layer can read it. */
    String value() {
        return value;
    }

    /** Safe for logs, diagnostics and API responses. Never contains the credential. */
    public String describe() {
        return switch (source) {
            case ENVIRONMENT -> "environment variable " + locator;
            case FILE -> "secret file " + locator;
            case INLINE -> "inline configuration value" + (locator.isEmpty() ? "" : " (" + locator + ")");
            case DEFAULT -> "built-in development default";
            case MISSING -> locator.isEmpty() ? "unresolved" : locator;
        };
    }

    @Override
    public String toString() {
        return describe();
    }
}
