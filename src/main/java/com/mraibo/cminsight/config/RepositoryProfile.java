package com.mraibo.cminsight.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One configured IBM CM repository.
 *
 * <p>A profile never contains a credential. Each of the four credentials - CM user, CM password, JDBC
 * user, JDBC password - is represented by a {@link SecretRef} that declares <em>where</em> the value
 * comes from and never the value itself: {@code <key>.env} names an environment variable, {@code
 * <key>.file} names a file below {@code secrets.dir}. That is the same reference type the web
 * credentials use, so the codebase has exactly one notion of "where does this secret come from", and
 * {@link SecretResolver} is the one implementation that reads it - including the rule that a secret
 * file can never escape {@code secrets.dir}.
 *
 * <p><strong>Precedence when both {@code <key>.env} and {@code <key>.file} are configured:</strong> the
 * environment variable wins, and it is authoritative in the fail-closed sense - if it is configured but
 * not set, resolution FAILS and does not fall back to the secret file. This is the same order the web
 * credentials use ({@code .env}, then {@code .file}, then inline), and the same rule
 * {@code bin/doctor.sh} must apply, so a repository credential cannot behave differently from a web
 * one. {@link RepositoryProfileLoader} records a diagnostic naming the ignored {@code .file} key, so the
 * shadowed source is never silently lost.
 *
 * <p>An inline value is not representable at all: there is no {@code repository.*.user=<value>} form, and
 * {@link RepositoryProfileLoader} rejects both it and every inline password with a message naming the
 * profile file. {@link SecretRef.Source#INLINE} and {@link SecretRef.Source#DEFAULT} are refused by the
 * canonical constructor as well, so a programmatically built profile cannot smuggle one in.
 *
 * @param cmUserRef         where the CM user name comes from
 * @param cmPasswordRef     where the CM password comes from
 * @param jdbcUserRef       where the JDBC user name comes from
 * @param jdbcPasswordRef   where the JDBC password comes from
 * @param sourceFile        the file the profile was read from, or {@code null} when built programmatically
 */
public record RepositoryProfile(
        String id,
        String displayName,
        String ssid,
        DatabaseVendor databaseVendor,
        String jdbcUrl,
        String jdbcSchema,
        SecretRef cmUserRef,
        SecretRef cmPasswordRef,
        SecretRef jdbcUserRef,
        SecretRef jdbcPasswordRef,
        String icnBaseUrl,
        Path sourceFile) {

    /** Config key of the CM user credential, without the {@code .env}/{@code .file} suffix. */
    public static final String CM_USER_KEY = "repository.cm.user";
    /** Config key of the CM password credential. */
    public static final String CM_PASSWORD_KEY = "repository.cm.password";
    /** Config key of the JDBC user credential. */
    public static final String JDBC_USER_KEY = "repository.jdbc.user";
    /** Config key of the JDBC password credential. */
    public static final String JDBC_PASSWORD_KEY = "repository.jdbc.password";

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern ENV_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** {@code C:...} - a Windows drive-relative path, which can leave the secrets directory. */
    private static final Pattern DRIVE_PREFIX = Pattern.compile("[A-Za-z]:.*");

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
        cmUserRef = requireCredentialRef(cmUserRef, CM_USER_KEY, id);
        cmPasswordRef = requireCredentialRef(cmPasswordRef, CM_PASSWORD_KEY, id);
        jdbcUserRef = requireCredentialRef(jdbcUserRef, JDBC_USER_KEY, id);
        jdbcPasswordRef = requireCredentialRef(jdbcPasswordRef, JDBC_PASSWORD_KEY, id);
        icnBaseUrl = trimToNull(icnBaseUrl);
    }

    /**
     * Declares a credential that is read from an environment variable.
     *
     * @param key     the credential key, for example {@code repository.cm.password}
     * @param envName the environment variable name, never a value
     */
    public static SecretRef credentialFromEnvironment(String key, String envName) {
        return new SecretRef(SecretRef.Source.ENVIRONMENT, requireEnvName(envName, requireCredentialKey(key), null));
    }

    /**
     * Declares a credential that is read from a file below the secrets directory.
     *
     * <p>The name is validated here, so a traversing reference is refused while the profile is being
     * loaded rather than only when a connection is first attempted. {@link SecretResolver} enforces the
     * same boundary a second time when it actually reads the file.
     *
     * @param key      the credential key, for example {@code repository.cm.password}
     * @param fileName the file name relative to the secrets directory, never a value
     */
    public static SecretRef credentialFromSecretFile(String key, String fileName) {
        return new SecretRef(SecretRef.Source.FILE, requireSecretFileName(fileName, requireCredentialKey(key)));
    }

    /** Declares that no source is configured for this credential. */
    public static SecretRef credentialNotConfigured(String key) {
        return new SecretRef(SecretRef.Source.MISSING, requireCredentialKey(key) + ": not configured");
    }

    /**
     * The four credential references by config key, in report order.
     *
     * <p>Names and sources only, never values - this is the shape the startup report, the
     * {@code bin/doctor.sh} parity checks and the future CM/JDBC adapter read.
     */
    public Map<String, SecretRef> credentialRefs() {
        Map<String, SecretRef> refs = new LinkedHashMap<>(4);
        refs.put(CM_USER_KEY, cmUserRef);
        refs.put(CM_PASSWORD_KEY, cmPasswordRef);
        refs.put(JDBC_USER_KEY, jdbcUserRef);
        refs.put(JDBC_PASSWORD_KEY, jdbcPasswordRef);
        return Collections.unmodifiableMap(refs);
    }

    /**
     * Credential references that name an environment variable, as {@code label=ENV_VAR_NAME} entries.
     *
     * <p>Names only, never values: this is the shape used for startup diagnostics. A credential that
     * comes from a secret file is reported by {@link #credentialSourceSummary()} instead, because a file
     * name is not an environment variable name and printing one as if it were would be misleading.
     */
    public List<String> credentialEnvNames() {
        List<String> names = new ArrayList<>(4);
        credentialRefs().forEach((key, ref) -> {
            if (ref.source() == SecretRef.Source.ENVIRONMENT) {
                names.add(key + "=" + ref.locator());
            }
        });
        return List.copyOf(names);
    }

    /**
     * Every configured credential source as {@code label=where it comes from} entries, for example
     * {@code repository.cm.password=secret file cm-password.txt}.
     *
     * <p>Safe to print: {@link SecretRef#describe()} never contains a value, and a credential with no
     * configured source is simply absent (the loader and the doctor report that separately).
     */
    public List<String> credentialSourceSummary() {
        List<String> summary = new ArrayList<>(4);
        credentialRefs().forEach((key, ref) -> {
            if (ref.source() != SecretRef.Source.MISSING) {
                summary.add(key + "=" + ref.describe());
            }
        });
        return List.copyOf(summary);
    }

    /**
     * Resolves all four credentials for this repository.
     *
     * <p>Fails closed: a credential whose declared source yields no value - an unset environment
     * variable, an unreadable or traversing secret file, an entirely unconfigured credential - raises
     * {@link ConfigException} naming the field and the source that failed, and never returns a partial
     * or empty value. A repository that cannot authenticate has no degraded mode worth running.
     *
     * @throws ConfigException when any credential is unconfigured or cannot be resolved
     */
    public RepositoryCredentials resolveCredentials(SecretResolver secrets) {
        Objects.requireNonNull(secrets, "secrets");
        return new RepositoryCredentials(
                resolve(secrets, cmUserRef, CM_USER_KEY),
                resolve(secrets, cmPasswordRef, CM_PASSWORD_KEY),
                resolve(secrets, jdbcUserRef, JDBC_USER_KEY),
                resolve(secrets, jdbcPasswordRef, JDBC_PASSWORD_KEY));
    }

    /**
     * Resolves <strong>only</strong> the two CM credentials of this repository.
     *
     * <p>The IBM CM adapter reads ItemTypes and retention policies and never opens the JDBC side, so
     * requiring the two JDBC credentials before a repository can be activated would make an analysis
     * feature depend on a database it does not use - and would turn a missing JDBC password into a
     * failure of a read path that never touches it. {@link #resolveCredentials(SecretResolver)} keeps
     * resolving all four for everything that genuinely needs both halves, and its behaviour is unchanged.
     *
     * <p>Fails closed in exactly the same way as the four-credential form: an unconfigured source, an
     * unset environment variable or an unreadable secret file raises {@link ConfigException} naming the
     * field and the source, and no partial or empty value is ever returned.
     *
     * @throws ConfigException when either CM credential is unconfigured or cannot be resolved
     */
    public CmCredentials resolveCmCredentials(SecretResolver secrets) {
        Objects.requireNonNull(secrets, "secrets");
        return new CmCredentials(
                resolve(secrets, cmUserRef, CM_USER_KEY),
                resolve(secrets, cmPasswordRef, CM_PASSWORD_KEY));
    }

    /**
     * Resolves <strong>only</strong> the two JDBC credentials of this repository.
     *
     * <p>The exact mirror of {@link #resolveCmCredentials(SecretResolver)} for the other half of the
     * architecture, and for the same reason: the IBM CM adapter must never be made to depend on a
     * database credential it does not use, so the analytics layer resolves the pair it needs, when it
     * needs it, and nothing else. Goal 03 requires a re-resolution for <em>every</em> new or replacement
     * JDBC connection, so the caller must invoke this method per connection attempt rather than caching
     * the result - a credential rotated, revoked or removed between two scans then takes effect at the
     * next connection instead of pinning the value that existed at activation time.
     *
     * <p>Fails closed in exactly the same way as the CM form: an unconfigured source, an unset
     * environment variable or an unreadable secret file raises {@link ConfigException} naming the field
     * and the source, and no partial or empty value is ever returned. Because that failure happens
     * <em>before</em> any JDBC connection is requested, the analytics layer reports it as a proven-clean
     * pre-allocation failure, so the reserved pool slot is released and a later attempt can succeed once
     * the credential is back.
     *
     * @throws ConfigException when either JDBC credential is unconfigured or cannot be resolved
     */
    public JdbcCredentials resolveJdbcCredentials(SecretResolver secrets) {
        Objects.requireNonNull(secrets, "secrets");
        return new JdbcCredentials(
                resolve(secrets, jdbcUserRef, JDBC_USER_KEY),
                resolve(secrets, jdbcPasswordRef, JDBC_PASSWORD_KEY));
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
     * The credential references are printed through {@link SecretRef#describe()}, which names the source
     * and never a value.
     */
    @Override
    public String toString() {
        return "RepositoryProfile[id=" + id
                + ", ssid=" + ssid
                + ", vendor=" + databaseVendor
                + ", jdbcUrl=" + safeJdbcUrl()
                + ", credentials=" + credentialSourceSummary()
                + ", source=" + (sourceFile == null ? "(programmatic)" : sourceFile.getFileName()) + "]";
    }

    // Resolution ---------------------------------------------------------------------------------

    private static SecretRef resolve(SecretResolver secrets, SecretRef declared, String key) {
        if (declared.source() == SecretRef.Source.MISSING) {
            throw new ConfigException("Repository credential '" + key + "' is not configured ("
                    + declared.describe() + "). Declare '" + key + ".env' or '" + key + ".file' in the"
                    + " profile; CM Insight never connects with a credential it cannot resolve.");
        }
        SecretRef resolved = switch (declared.source()) {
            case ENVIRONMENT -> secrets.classify(declared.locator(), null, null, key);
            case FILE -> secrets.classify(null, declared.locator(), null, key);
            default -> throw new ConfigException("Repository credential '" + key + "' declares an unsupported"
                    + " source (" + declared.describe() + "); only '" + key + ".env' and '" + key + ".file'"
                    + " are accepted.");
        };
        if (!resolved.resolved()) {
            throw new ConfigException("Repository credential '" + key + "' is configured but no value could be"
                    + " resolved (" + declared.describe() + "; " + resolved.describe() + "). Refusing to"
                    + " continue without it.");
        }
        return resolved;
    }

    /**
     * The four resolved repository credentials of one activation.
     *
     * <p>Exists so resolution happens exactly once, in one place, against one {@link SecretResolver},
     * and the values are then handed to the CM/JDBC adapter. They are held as resolved {@link SecretRef}
     * instances: read a value with {@link SecretResolver#resolve(SecretRef)}, and never log, serialise or
     * print this object - {@link #toString()} deliberately describes the sources instead.
     */
    public record RepositoryCredentials(
            SecretRef cmUser,
            SecretRef cmPassword,
            SecretRef jdbcUser,
            SecretRef jdbcPassword) {

        public RepositoryCredentials {
            cmUser = requireResolved(cmUser, CM_USER_KEY);
            cmPassword = requireResolved(cmPassword, CM_PASSWORD_KEY);
            jdbcUser = requireResolved(jdbcUser, JDBC_USER_KEY);
            jdbcPassword = requireResolved(jdbcPassword, JDBC_PASSWORD_KEY);
        }

        /** True when all four credentials carry a value. Always true for a constructed instance. */
        public boolean usable() {
            return cmUser.resolved() && cmPassword.resolved() && jdbcUser.resolved() && jdbcPassword.resolved();
        }

        @Override
        public String toString() {
            return "RepositoryCredentials[cmUser=" + cmUser.describe()
                    + ", cmPassword=<redacted from " + cmPassword.describe() + ">"
                    + ", jdbcUser=" + jdbcUser.describe()
                    + ", jdbcPassword=<redacted from " + jdbcPassword.describe() + ">]";
        }
    }

    /**
     * The two resolved CM credentials of one activation, and only those.
     *
     * <p>Deliberately the same shape and the same guarantees as {@link RepositoryCredentials} - resolved
     * {@link SecretRef} instances, never printable values, {@link #toString()} showing the sources - but
     * without the JDBC half. Read a value with {@link SecretResolver#resolve(SecretRef)}; never log,
     * serialise or print this object.
     */
    public record CmCredentials(SecretRef cmUser, SecretRef cmPassword) {

        public CmCredentials {
            cmUser = requireResolved(cmUser, CM_USER_KEY);
            cmPassword = requireResolved(cmPassword, CM_PASSWORD_KEY);
        }

        /** True when both CM credentials carry a value. Always true for a constructed instance. */
        public boolean usable() {
            return cmUser.resolved() && cmPassword.resolved();
        }

        @Override
        public String toString() {
            return "CmCredentials[cmUser=" + cmUser.describe()
                    + ", cmPassword=<redacted from " + cmPassword.describe() + ">]";
        }
    }

    /**
     * The two resolved JDBC credentials of one scan, and only those.
     *
     * <p>The mirror of {@link CmCredentials} for the analytics half, with the same guarantees: resolved
     * {@link SecretRef} instances, never printable values, {@link #toString()} showing the sources.
     * Read a value with {@link SecretResolver#resolve(SecretRef)}; never log, serialise or print this
     * object. Goal 03 re-resolves this pair for every new or replacement connection, so it is
     * deliberately a short-lived value rather than something the analytics layer caches.
     */
    public record JdbcCredentials(SecretRef jdbcUser, SecretRef jdbcPassword) {

        public JdbcCredentials {
            jdbcUser = requireResolved(jdbcUser, JDBC_USER_KEY);
            jdbcPassword = requireResolved(jdbcPassword, JDBC_PASSWORD_KEY);
        }

        /** True when both JDBC credentials carry a value. Always true for a constructed instance. */
        public boolean usable() {
            return jdbcUser.resolved() && jdbcPassword.resolved();
        }

        @Override
        public String toString() {
            return "JdbcCredentials[jdbcUser=" + jdbcUser.describe()
                    + ", jdbcPassword=<redacted from " + jdbcPassword.describe() + ">]";
        }
    }

    private static SecretRef requireResolved(SecretRef ref, String key) {
        if (ref == null || !ref.resolved()) {
            throw new ConfigException("Repository credential '" + key + "' is not resolved");
        }
        return ref;
    }

    // Validation ---------------------------------------------------------------------------------

    /**
     * Normalises a credential reference and refuses anything a repository profile may not declare.
     *
     * <p>A {@code null} component means "not configured" rather than an error: a profile may legitimately
     * declare no credentials at all (the doctor reports that), and resolution - not construction - is
     * where a missing credential fails closed.
     */
    private static SecretRef requireCredentialRef(SecretRef ref, String key, String id) {
        if (ref == null) {
            return credentialNotConfigured(key);
        }
        return switch (ref.source()) {
            case ENVIRONMENT -> credentialFromEnvironment(key, ref.locator());
            case FILE -> credentialFromSecretFile(key, ref.locator());
            case MISSING -> isBlank(ref.locator()) ? credentialNotConfigured(key) : ref;
            case INLINE, DEFAULT -> throw new ConfigException("Repository '" + id + "' declares an inline value"
                    + " for " + key + ". Repository credentials must reference the environment variable or the"
                    + " secret file that holds them; use '" + key + ".env' or '" + key + ".file' instead.");
        };
    }

    private static String requireCredentialKey(String key) {
        String trimmed = trimToNull(key);
        if (trimmed == null) {
            throw new ConfigException("A repository credential reference needs the configuration key it belongs to");
        }
        return trimmed;
    }

    private static String requireEnvName(String value, String key, String id) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new ConfigException("Repository credential " + key
                    + ".env is blank: name the environment variable that holds the value.");
        }
        if (!ENV_PATTERN.matcher(trimmed).matches()) {
            throw new ConfigException("Repository '" + (id == null ? "(profile)" : id)
                    + "' has an invalid environment variable name for " + key + ".env: '" + trimmed + "'");
        }
        return trimmed;
    }

    /**
     * A secret file reference must name a file below {@code secrets.dir}: relative, and free of any
     * traversal that would leave that directory. Absolute paths, drive-relative paths and {@code ..}
     * segments are refused here, and {@link SecretResolver} refuses them again at read time.
     */
    private static String requireSecretFileName(String value, String key) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new ConfigException("Repository credential " + key
                    + ".file is blank: name a file below the secrets directory.");
        }
        if (trimmed.startsWith("~")) {
            throw new ConfigException("Repository credential " + key + ".file must name a file below the secrets"
                    + " directory; '" + trimmed + "' is home-relative.");
        }
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") || DRIVE_PREFIX.matcher(trimmed).matches()) {
            throw new ConfigException("Repository credential " + key + ".file must name a file below the secrets"
                    + " directory; '" + trimmed + "' is an absolute path.");
        }
        final Path normalized;
        try {
            normalized = Path.of(trimmed).normalize();
        } catch (InvalidPathException e) {
            throw new ConfigException("Repository credential " + key + ".file is not a usable file name: '"
                    + trimmed + "'");
        }
        if (normalized.toString().isEmpty() || normalized.startsWith("..")) {
            throw new ConfigException("Repository credential " + key + ".file must name a file below the secrets"
                    + " directory; '" + trimmed + "' leaves it.");
        }
        return trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
