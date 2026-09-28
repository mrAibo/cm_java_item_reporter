package com.mraibo.cminsight.config;

/**
 * Raised when configuration is missing, malformed or contradicts a fixed policy rule.
 *
 * <p>Unchecked on purpose: the app is a single JVM with a fail-fast startup, so an invalid
 * configuration must abort startup with an actionable diagnostic instead of threading a checked
 * exception through every accessor.
 *
 * <p>Messages must never contain secret values.
 */
public class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
