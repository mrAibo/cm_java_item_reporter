package com.mraibo.cminsight.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The effective web credentials after secret indirection has been applied.
 *
 * <p>{@link #toString()} is overridden on purpose: the compiler-generated record {@code toString()}
 * would print the password, and this object is exactly the kind of thing that ends up in a log line
 * or a diagnostics JSON document.
 *
 * @param user                effective user name
 * @param password            effective password; never log or serialise this value
 * @param userSource          how the user name was obtained
 * @param passwordSource      how the password was obtained
 * @param defaultCredentials  true when the effective credentials are the built-in admin/admin pair
 * @param warnings            non-fatal configuration observations, safe to print
 */
public record WebAuthSettings(
        String user,
        String password,
        SecretRef userSource,
        SecretRef passwordSource,
        boolean defaultCredentials,
        List<String> warnings) {

    /** The development default, only acceptable on a loopback bind. */
    public static final String DEFAULT_USER = "admin";
    public static final String DEFAULT_PASSWORD = "admin";

    public WebAuthSettings {
        user = user == null ? "" : user.trim();
        password = password == null ? "" : password;
        Objects.requireNonNull(userSource, "userSource");
        Objects.requireNonNull(passwordSource, "passwordSource");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * Resolves the effective credentials from configuration.
     *
     * <p>Order per field: {@code web.auth.<field>.env}, then {@code web.auth.<field>.file}, then a
     * direct {@code web.auth.<field>} value, then the built-in development default.
     */
    public static WebAuthSettings resolve(AppConfig config, SecretResolver secrets) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(secrets, "secrets");

        List<String> warnings = new ArrayList<>(secrets.warnings());

        boolean userConfigured = configured(config, "web.auth.user");
        boolean passwordConfigured = configured(config, "web.auth.password");

        SecretRef configuredUser = secrets.classify(
                config.find("web.auth.user.env").orElse(null),
                config.find("web.auth.user.file").orElse(null),
                config.find("web.auth.user").orElse(null),
                "web.auth.user");
        final SecretRef userRef;
        final String user;
        if (configuredUser.resolved()) {
            userRef = configuredUser;
            user = configuredUser.value();
        } else if (!userConfigured) {
            userRef = secrets.defaults("web.auth.user", DEFAULT_USER);
            user = DEFAULT_USER;
        } else {
            throw unresolvedFailure("web.auth.user", configuredUser);
        }

        SecretRef configuredPassword = secrets.classify(
                config.find("web.auth.password.env").orElse(null),
                config.find("web.auth.password.file").orElse(null),
                config.find("web.auth.password").orElse(null),
                "web.auth.password");
        final SecretRef passwordRef;
        final String password;
        if (configuredPassword.resolved()) {
            passwordRef = configuredPassword;
            password = configuredPassword.value();
        } else if (!passwordConfigured) {
            passwordRef = secrets.defaults("web.auth.password", DEFAULT_PASSWORD);
            password = DEFAULT_PASSWORD;
        } else {
            throw unresolvedFailure("web.auth.password", configuredPassword);
        }

        boolean defaults = DEFAULT_USER.equals(user) && DEFAULT_PASSWORD.equals(password);
        if (defaults) {
            warnings.add("Web authentication is using the built-in admin/admin development credentials.");
        }
        warnings.addAll(secrets.warnings());

        return new WebAuthSettings(user, password, userRef, passwordRef, defaults, warnings);
    }

    /** True when any of {@code <key>}, {@code <key>.env} or {@code <key>.file} is present. */
    private static boolean configured(AppConfig config, String key) {
        return config.find(key).isPresent()
                || config.find(key + ".env").isPresent()
                || config.find(key + ".file").isPresent();
    }

    /**
     * A source was explicitly configured but produced no value.
     *
     * <p>Fails closed on purpose. Falling back to the built-in {@code admin}/{@code admin} here would
     * mean that forgetting to export the environment variable named in the tracked template silently
     * leaves a public, documented credential in place - the operator asked for a real secret and must
     * not silently get a weaker one. The development default is only applied when nothing at all was
     * configured, and even then only a loopback bind is accepted.
     */
    private static ConfigException unresolvedFailure(String key, SecretRef configuredRef) {
        return new ConfigException("'" + key + "' is configured but no value could be resolved ("
                + configuredRef.describe() + "). Refusing to fall back to the built-in development"
                + " default: provide the value, or remove the " + key + "* keys so that the loopback"
                + " development default applies deliberately.");
    }

    /** True when the credentials came from the built-in development default rather than config. */
    public boolean usingDevelopmentDefaults() {
        return userSource.source() == SecretRef.Source.DEFAULT
                || passwordSource.source() == SecretRef.Source.DEFAULT;
    }

    @Override
    public String toString() {
        return "WebAuthSettings[user=" + user
                + ", password=<redacted from " + passwordSource.describe() + ">"
                + ", defaultCredentials=" + defaultCredentials + "]";
    }
}
