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
 * <p>Fails closed: a malformed profile, a duplicate repository id or an inline password aborts
 * loading with a message naming the offending file, because silently skipping a repository would
 * present an operator with an incomplete list of systems.
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
                    config.find("repository.cm.user.env").orElse(null),
                    config.find("repository.cm.password.env").orElse(null),
                    config.find("repository.jdbc.user.env").orElse(null),
                    config.find("repository.jdbc.password.env").orElse(null),
                    config.find("repository.icn.base.url").orElse(null),
                    file.toAbsolutePath().normalize());
        } catch (ConfigException e) {
            throw new ConfigException(file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * A profile must reference secrets, never contain them. This is the one place where a committed
     * password would be caught before it reaches Git.
     */
    private static void rejectInlineSecrets(AppConfig config, Path file) {
        for (String key : config.keys()) {
            String lower = key.toLowerCase(Locale.ROOT);
            boolean looksSecret = SECRET_MARKERS.stream().anyMatch(lower::contains);
            if (!looksSecret) {
                continue;
            }
            boolean indirection = SECRET_INDIRECTIONS.stream().anyMatch(lower::endsWith);
            if (!indirection) {
                throw new ConfigException(file.getFileName() + ": key '" + key
                        + "' looks like an inline secret. Repository profiles must reference credentials by name;"
                        + " use '" + key + ".env' or '" + key + ".file' instead.");
            }
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
