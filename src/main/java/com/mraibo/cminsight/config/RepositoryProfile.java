package com.mraibo.cminsight.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One configured IBM CM repository.
 *
 * <p>A profile never contains a credential. It stores the <em>names</em> of the environment variables
 * (or secret files) that hold them, which is what keeps tracked configuration free of secrets.
 *
 * @param sourceFile the file the profile was read from, or {@code null} when built programmatically
 */
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
        String icnBaseUrl,
        Path sourceFile) {

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern ENV_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * {@code //user:password@host} - DB2 and the Oracle service form.
     *
     * <p>The password part is matched with {@code [^@]*} rather than a slash-excluding character class:
     * a password may legitimately contain '/', and a narrower match leaves a leading fragment of it
     * behind in the "masked" output.
     */
    private static final Pattern JDBC_URL_USERINFO = Pattern.compile("(//[^/@]*:)[^@]*(@)");
    /** {@code :user/password@host} - the Oracle thin form. */
    private static final Pattern JDBC_ORACLE_THIN = Pattern.compile("(:[^/@:]+/)([^/@]+)@");
    /** {@code ;password=value} - the DB2 property form. */
    private static final Pattern JDBC_PASSWORD_PROPERTY = Pattern.compile("(?i)(password=)[^;&]*");

    public RepositoryProfile {
        id = trimToNull(id);
        if (id == null) {
            throw new ConfigException("Repository profile is missing repository.id");
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new ConfigException("Repository id '" + id
                    + "' is invalid: use letters, digits, dot, underscore or dash (max 64 characters)");
        }
        displayName = trimToNull(displayName);
        if (displayName == null) {
            throw new ConfigException("Repository '" + id + "' is missing repository.name");
        }
        ssid = trimToNull(ssid);
        if (ssid == null) {
            throw new ConfigException("Repository '" + id + "' is missing repository.ssid");
        }
        if (databaseVendor == null) {
            throw new ConfigException("Repository '" + id + "' is missing repository.db.vendor");
        }
        jdbcUrl = trimToNull(jdbcUrl);
        if (jdbcUrl == null) {
            throw new ConfigException("Repository '" + id + "' is missing repository.jdbc.url");
        }
        jdbcSchema = trimToNull(jdbcSchema);
        cmUserEnv = requireEnvName(cmUserEnv, "repository.cm.user.env", id);
        cmPasswordEnv = requireEnvName(cmPasswordEnv, "repository.cm.password.env", id);
        jdbcUserEnv = requireEnvName(jdbcUserEnv, "repository.jdbc.user.env", id);
        jdbcPasswordEnv = requireEnvName(jdbcPasswordEnv, "repository.jdbc.password.env", id);
        icnBaseUrl = trimToNull(icnBaseUrl);
    }

    /**
     * Credential references as {@code label=ENV_VAR_NAME} entries.
     *
     * <p>Names only, never values: this is the shape used for startup diagnostics and the
     * {@code bin/doctor.sh} report.
     */
    public List<String> credentialEnvNames() {
        List<String> names = new ArrayList<>(4);
        addIfPresent(names, "repository.cm.user", cmUserEnv);
        addIfPresent(names, "repository.cm.password", cmPasswordEnv);
        addIfPresent(names, "repository.jdbc.user", jdbcUserEnv);
        addIfPresent(names, "repository.jdbc.password", jdbcPasswordEnv);
        return List.copyOf(names);
    }

    private static void addIfPresent(List<String> target, String label, String value) {
        if (value != null) {
            target.add(label + "=" + value);
        }
    }

    /**
     * True when the JDBC URL embeds a credential instead of referencing one by name.
     *
     * <p>{@link RepositoryProfileLoader} rejects such a profile outright; this method exists so the
     * rule can be asserted directly and so a programmatically built profile can be screened too.
     */
    public boolean jdbcUrlEmbedsCredential() {
        return jdbcUrl != null
                && (JDBC_URL_USERINFO.matcher(jdbcUrl).find()
                || JDBC_ORACLE_THIN.matcher(jdbcUrl).find()
                || JDBC_PASSWORD_PROPERTY.matcher(jdbcUrl).find());
    }

    /**
     * The JDBC URL in a form that is safe to print.
     *
     * <p>When no credential is detected the URL is returned unchanged, so diagnostics stay useful. Once
     * a credential IS present the URL is ambiguous - a password may itself contain '/' or '@', so a
     * surgical mask can leave a fragment of the secret behind or mangle the host - and the whole URL is
     * therefore replaced. A redaction that cannot be proven complete is not worth the convenience.
     */
    public String safeJdbcUrl() {
        if (jdbcUrl == null) {
            return null;
        }
        return jdbcUrlEmbedsCredential() ? "<jdbc url with an embedded credential redacted>" : jdbcUrl;
    }

    /**
     * Deliberately overridden: the compiler-generated record {@code toString()} would print
     * {@code jdbcUrl} verbatim, and a JDBC URL can carry a password in several driver-specific shapes.
     */
    @Override
    public String toString() {
        return "RepositoryProfile[id=" + id
                + ", ssid=" + ssid
                + ", vendor=" + databaseVendor
                + ", jdbcUrl=" + safeJdbcUrl()
                + ", source=" + (sourceFile == null ? "(programmatic)" : sourceFile.getFileName()) + "]";
    }

    private static String requireEnvName(String value, String key, String id) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (!ENV_PATTERN.matcher(trimmed).matches()) {
            throw new ConfigException("Repository '" + id + "' has an invalid environment variable name for "
                    + key + ": '" + trimmed + "'");
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
