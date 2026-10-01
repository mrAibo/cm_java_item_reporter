package com.mraibo.cminsight.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads {@code conf/profiles/*.properties} into {@link RepositoryProfile} instances.
 *
 * <p>Fails closed: a malformed profile, a duplicate repository id or an inline credential aborts
 * loading with a message naming the offending file, because silently skipping a repository would
 * present an operator with an incomplete list of systems.
 *
 * <p>Every credential is loaded as a {@link SecretRef} that declares its source: {@code
 * repository.<cm|jdbc>.<user|password>.env} names an environment variable, {@code
 * repository.<cm|jdbc>.<user|password>.file} names a file below {@code secrets.dir}. Both shapes are
 * preserved and both are resolvable later by {@link SecretResolver}; nothing is dropped at load time.
 *
 * <p><strong>Precedence when a credential has both shapes:</strong> {@code .env} wins and is
 * authoritative - it is never allowed to fall back to the {@code .file} source, matching the rule the
 * web credentials already follow. A load-time diagnostic names the shadowed {@code .file} key, and if
 * the environment variable is unset, resolution fails closed instead of quietly using the file. An
 * inline value is not part of the model at all: {@code repository.cm.user=<value>} and every inline
 * password form are rejected below, with a message naming the profile file and the {@code .env}/{@code
 * .file} key to use instead.
 */
public final class RepositoryProfileLoader {

    /** Only real profiles are loaded; {@code *.properties.example} templates are ignored. */
    public static final String SUFFIX = ".properties";

    private static final List<String> SECRET_MARKERS = List.of("password", "passwd", "secret", "token");
    private static final List<String> SECRET_INDIRECTIONS = List.of(".env", ".file");

    private final Path profilesDir;
    private final List<String> diagnostics = new ArrayList<>();

    public RepositoryProfileLoader(Path profilesDir) {
        this.profilesDir = profilesDir == null ? null : profilesDir.toAbsolutePath().normalize();
    }

    public Path profilesDir() {
        return profilesDir;
    }

    /** Non-fatal observations gathered during the last load. */
    public List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public List<RepositoryProfile> loadAll() {
        diagnostics.clear();
        if (profilesDir == null || !Files.isDirectory(profilesDir)) {
            diagnostics.add("No repository profiles directory at "
                    + (profilesDir == null ? "(not configured)" : profilesDir.toString())
                    + "; no repositories are configured.");
            return List.of();
        }

        List<Path> files = listProfileFiles();
        List<RepositoryProfile> profiles = new ArrayList<>(files.size());
        Map<String, Path> seenIds = new TreeMap<>();

        for (Path file : files) {
            RepositoryProfile profile = loadFile(file);
            Path previous = seenIds.putIfAbsent(profile.id(), file);
            if (previous != null) {
                throw new ConfigException("Duplicate repository id '" + profile.id() + "' declared by both "
                        + previous.getFileName() + " and " + file.getFileName() + " in " + profilesDir);
            }
            profiles.add(profile);
        }
        profiles.sort(Comparator.comparing(RepositoryProfile::id));
        diagnostics.add("Loaded " + profiles.size() + " repository profile(s) from " + profilesDir + ".");
        return List.copyOf(profiles);
    }

    /** Loads exactly one repository by id, or fails with the list of known ids. */
    public RepositoryProfile load(String id) {
        List<RepositoryProfile> profiles = loadAll();
        for (RepositoryProfile profile : profiles) {
            if (profile.id().equals(id)) {
                return profile;
            }
        }
        List<String> known = profiles.stream().map(RepositoryProfile::id).toList();
        throw new ConfigException("Unknown repository id '" + id + "'. Configured repositories: "
                + (known.isEmpty() ? "(none)" : String.join(", ", known)));
    }

    private List<Path> listProfileFiles() {
        try (Stream<Path> stream = Files.list(profilesDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new ConfigException("Could not read the repository profiles directory " + profilesDir
                    + ": " + e.getMessage(), e);
        }
    }

    private RepositoryProfile loadFile(Path file) {
        final AppConfig config;
        try {
            config = AppConfig.load(file);
        } catch (IOException e) {
            throw new ConfigException("Could not read repository profile " + file + ": " + e.getMessage(), e);
        }
        rejectInlineSecrets(config, file);

        String id = config.find("repository.id").orElseGet(() -> fileNameStem(file));
        if (!id.equals(fileNameStem(file))) {
            diagnostics.add("Repository '" + id + "' is declared in " + file.getFileName()
                    + ", whose name does not match the repository id.");
        }

        DatabaseVendor vendor;
        try {
            vendor = DatabaseVendor.parse(config.find("repository.db.vendor").orElse(null));
        } catch (ConfigException | IllegalArgumentException e) {
            // Both are possible: DatabaseVendor.parse reports a ConfigException, but it is a public
            // API and an IllegalArgumentException must not escape without the file name either.
            throw new ConfigException(file.getFileName() + ": " + e.getMessage(), e);
        }

        try {
            return new RepositoryProfile(
                    id,
                    config.find("repository.name").orElse(null),
                    config.find("repository.ssid").orElse(null),
                    vendor,
                    config.find("repository.jdbc.url").orElse(null),
                    config.find("repository.jdbc.schema").orElse(null),
                    credential(config, file, RepositoryProfile.CM_USER_KEY),
                    credential(config, file, RepositoryProfile.CM_PASSWORD_KEY),
                    credential(config, file, RepositoryProfile.JDBC_USER_KEY),
                    credential(config, file, RepositoryProfile.JDBC_PASSWORD_KEY),
                    config.find("repository.icn.base.url").orElse(null),
                    file.toAbsolutePath().normalize());
        } catch (ConfigException e) {
            throw new ConfigException(file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Declares where one repository credential comes from, applying the documented precedence.
     *
     * <p>{@code .env} wins over {@code .file}, and the winning source is authoritative: a configured
     * environment variable that happens to be unset does NOT fall back to the secret file, it fails
     * closed at resolution time. Both sources are therefore never merged and never silently dropped -
     * when both are present the shadowed one is reported in {@link #diagnostics()}.
     */
    private SecretRef credential(AppConfig config, Path file, String key) {
        String envName = config.find(key + ".env").orElse(null);
        String fileName = config.find(key + ".file").orElse(null);
        if (envName != null && fileName != null) {
            diagnostics.add(file.getFileName() + ": '" + key + "' configures both '" + key + ".env' ("
                    + envName + ") and '" + key + ".file' (" + fileName + "); the environment variable is"
                    + " authoritative and the secret file is ignored.");
        }
        if (envName != null) {
            return RepositoryProfile.credentialFromEnvironment(key, envName);
        }
        if (fileName != null) {
            return RepositoryProfile.credentialFromSecretFile(key, fileName);
        }
        return RepositoryProfile.credentialNotConfigured(key);
    }

    /**
     * A profile must reference secrets, never contain them. This is the one place where a committed
     * credential would be caught before it reaches Git.
     *
     * <p>Two rules, because the old key-name heuristic covered only half of the model:
     *
     * <ul>
     *   <li>a key ending in {@code .user} under {@code repository.} is an inline credential value - the
     *       loader never read it, so before Goal 01A it was silently ignored and the operator's real
     *       user name never reached the connection;</li>
     *   <li>anything else whose name looks like a secret (password/passwd/secret/token) must use the
     *       {@code .env} or {@code .file} indirection.</li>
     * </ul>
     *
     * <p>Neither message echoes the value: a rejected credential must not be printed by the very
     * diagnostic that rejects it.
     */
    private static void rejectInlineSecrets(AppConfig config, Path file) {
        for (String key : config.keys()) {
            String lower = key.toLowerCase(Locale.ROOT);
            boolean indirection = SECRET_INDIRECTIONS.stream().anyMatch(lower::endsWith);
            if (indirection) {
                continue;
            }
            if (isInlineCredentialValueKey(lower)) {
                throw new ConfigException(file.getFileName() + ": key '" + key
                        + "' holds a repository credential value directly. Repository profiles must reference"
                        + " credentials by name; use '" + key + ".env' or '" + key + ".file' instead.");
            }
            boolean looksSecret = SECRET_MARKERS.stream().anyMatch(lower::contains);
            if (!looksSecret) {
                continue;
            }
            throw new ConfigException(file.getFileName() + ": key '" + key
                    + "' looks like an inline secret. Repository profiles must reference credentials by name;"
                    + " use '" + key + ".env' or '" + key + ".file' instead.");
        }

        // A key-name check is not enough: JDBC URLs routinely carry a password in the URL itself, and
        // a generated record toString() would print it.
        String jdbcUrl = config.find("repository.jdbc.url").orElse(null);
        if (jdbcUrl != null && (JDBC_URL_USERINFO.matcher(jdbcUrl).find()
                || JDBC_ORACLE_THIN.matcher(jdbcUrl).find()
                || JDBC_PASSWORD_PROPERTY.matcher(jdbcUrl).find())) {
            throw new ConfigException(file.getFileName() + ": repository.jdbc.url embeds a credential."
                    + " Put the user and password in repository.jdbc.user.env / repository.jdbc.password.env"
                    + " (or the matching '.file' keys) instead of inside the URL.");
        }
    }

    /**
     * True for {@code repository.*.user} - the one credential shape whose name carries no secret marker,
     * so the marker heuristic below would let it through as an ignored key.
     */
    private static boolean isInlineCredentialValueKey(String lowerKey) {
        return lowerKey.startsWith("repository.") && lowerKey.endsWith(".user");
    }

    /**
     * {@code //user:password@host} - DB2 and the Oracle service form. The password part is matched with
     * {@code [^@]*} so that a password containing '/' is still detected and masked in full.
     */
    private static final Pattern JDBC_URL_USERINFO = Pattern.compile("(//[^/@]*:)[^@]*(@)");
    /** {@code :user/password@host} - the Oracle thin form. */
    private static final Pattern JDBC_ORACLE_THIN = Pattern.compile("(:[^/@:]+/)([^/@]+)@");
    /** {@code ;password=value} - the DB2 property form. */
    private static final Pattern JDBC_PASSWORD_PROPERTY = Pattern.compile("(?i)(password=)[^;&]*");

    private static String fileNameStem(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(SUFFIX) ? name.substring(0, name.length() - SUFFIX.length()) : name;
    }
}
