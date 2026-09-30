package com.mraibo.cminsight.config;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The single authority for CM Insight operational paths.
 *
 * <h2>The one rule</h2>
 *
 * <p><strong>A relative operational path is resolved against the application home, never against the
 * working directory of the process that happened to start the JVM.</strong> {@code profiles.dir=conf/profiles}
 * therefore names the same directory whether the application was started by {@code bin/cm-insight}
 * from the repository root, by a systemd unit whose {@code WorkingDirectory} is {@code /}, or by an
 * interactive shell sitting in {@code $HOME} - and it names the same directory the shell scripts
 * ({@code bin/doctor.sh}, {@code bin/start.sh}, ...) already used, because they resolve relative
 * values against the application root too.
 *
 * <h2>Where the home comes from</h2>
 *
 * <ol>
 *   <li>{@code -Dcminsight.home=<dir>} - what {@code bin/cm-insight} passes. Primary source.</li>
 *   <li>{@code CM_INSIGHT_HOME=<dir>} - used when a JVM is started without the flag (a test harness,
 *       an IDE, a packaging wrapper).</li>
 *   <li>the working directory - the <em>documented fallback</em> when no launcher supplied a home, so
 *       a development run from the repository root keeps behaving exactly as it did before this class
 *       existed.</li>
 * </ol>
 *
 * <p>A <em>relative</em> home value is itself resolved against the working directory: there is no
 * other base it could sensibly be relative to.
 *
 * <h2>Absolute values</h2>
 *
 * <p>An absolute configured path is used unchanged (normalised), so an operator who writes an
 * absolute directory in {@code application.properties} gets exactly the path that was written.
 *
 * <p>Instances are immutable and safe to share. {@link #resolve()} re-reads the system property and the
 * environment on every call, which is what lets a test or a diagnostics entry point
 * ({@code com.mraibo.cminsight.app.ConfigCheck}) fix the home for everything that follows.
 */
public final class AppPaths {

    /** System property a launcher sets to the application home. Takes precedence over the environment. */
    public static final String HOME_PROPERTY = "cminsight.home";
    /** Environment variable used when {@link #HOME_PROPERTY} is absent. */
    public static final String HOME_ENV = "CM_INSIGHT_HOME";

    /** Configuration key of the repository profile directory. */
    public static final String PROFILES_DIR_KEY = "profiles.dir";
    /** Configuration key of the optional classification rules file. */
    public static final String CLASSIFICATIONS_FILE_KEY = "classifications.file";
    /** Configuration key of the secrets directory. */
    public static final String SECRETS_DIR_KEY = "secrets.dir";
    /** Configuration key reserved for the future data directory. */
    public static final String DATA_DIR_KEY = "data.dir";
    /** Configuration key reserved for the future reports directory. */
    public static final String REPORTS_DIR_KEY = "reports.dir";
    /** Configuration key reserved for the future logs directory. */
    public static final String LOGS_DIR_KEY = "logs.dir";

    /** Home-relative default of the configuration file. */
    public static final String DEFAULT_CONFIG_FILE = "conf/application.properties";
    /** Home-relative default of {@link #PROFILES_DIR_KEY}. */
    public static final String DEFAULT_PROFILES_DIR = "conf/profiles";
    /** Home-relative default of {@link #SECRETS_DIR_KEY}. */
    public static final String DEFAULT_SECRETS_DIR = "conf/secrets";
    /** Home-relative default of {@link #DATA_DIR_KEY}. */
    public static final String DEFAULT_DATA_DIR = "data";
    /** Home-relative default of {@link #REPORTS_DIR_KEY}. */
    public static final String DEFAULT_REPORTS_DIR = "reports";
    /** Home-relative default of {@link #LOGS_DIR_KEY}. */
    public static final String DEFAULT_LOGS_DIR = "logs";

    /** Where the application home came from. */
    public enum HomeSource {
        /** {@code -Dcminsight.home} - the launcher's primary mechanism. */
        SYSTEM_PROPERTY("-D" + HOME_PROPERTY),
        /** {@code CM_INSIGHT_HOME} - the launcher's secondary mechanism. */
        ENVIRONMENT("environment variable " + HOME_ENV),
        /** The documented fallback: no launcher supplied a home. */
        WORKING_DIRECTORY("the working directory (documented fallback: no launcher supplied a home)");

        private final String description;

        HomeSource(String description) {
            this.description = description;
        }

        /** A short, credential-free description suitable for a startup banner or a doctor line. */
        public String description() {
            return description;
        }
    }

    /** Whether a configured key produced the resolved path or a built-in default did. */
    public enum Origin {
        /** The key is present in the configuration. */
        CONFIGURED,
        /** The key is absent or blank; the documented default applies. */
        DEFAULT;

        /** {@code configured} or {@code default}, for diagnostics. */
        public String describe() {
            return this == CONFIGURED ? "configured" : "default";
        }
    }

    /**
     * One resolved operational path.
     *
     * @param key                    the configuration key it comes from
     * @param path                   the absolute, normalised path the application will use
     * @param origin                 whether the key was configured or a default applied
     * @param reservedForLaterGoal   true for the paths carried for a later goal (data/reports/logs),
     *                               which no Java code reads yet
     */
    public record OperationalPath(String key, Path path, Origin origin, boolean reservedForLaterGoal) {

        public OperationalPath {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(origin, "origin");
        }

        /** {@code profiles.dir=/app/conf/profiles (configured)}, for diagnostics. */
        public String describe() {
            return key + "=" + path + " (" + origin.describe()
                    + (reservedForLaterGoal ? ", reserved for a later goal" : "") + ")";
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    private final Path home;
    private final HomeSource homeSource;

    private AppPaths(Path home, HomeSource homeSource) {
        this.home = home;
        this.homeSource = Objects.requireNonNull(homeSource, "homeSource");
    }

    /**
     * Resolves the application home from the system property, then the environment, then the working
     * directory (the documented fallback).
     *
     * <p>A home supplied explicitly by the property or the environment must be an existing directory:
     * a typo there would otherwise misresolve every relative path silently, and the operator would see
     * only the downstream symptom ({@code Configuration file not found: /typo/conf/application.properties}).
     *
     * @throws ConfigException when the explicitly supplied home is not a usable, existing directory
     */
    public static AppPaths resolve() {
        String property = trimToNull(System.getProperty(HOME_PROPERTY));
        if (property != null) {
            return supplied(parse(HOME_PROPERTY, property), HomeSource.SYSTEM_PROPERTY);
        }
        String environment = trimToNull(System.getenv(HOME_ENV));
        if (environment != null) {
            return supplied(parse(HOME_ENV, environment), HomeSource.ENVIRONMENT);
        }
        String working = trimToNull(System.getProperty("user.dir"));
        Path base = working == null ? Path.of(".") : Path.of(working);
        return of(base, HomeSource.WORKING_DIRECTORY);
    }

    /** An explicit home, for a caller that already knows it (tests, a diagnostics entry point). */
    public static AppPaths of(Path home, HomeSource source) {
        Objects.requireNonNull(home, "home");
        return new AppPaths(home.toAbsolutePath().normalize(), source);
    }

    /**
     * A home a launcher supplied through the property or the environment.
     *
     * <p>Unlike {@link #of(Path, HomeSource)}, which trusts its caller, this validates the directory:
     * the launcher contract is "point this at the application root", and getting that wrong deserves a
     * diagnostic naming the home rather than a downstream "file not found".
     */
    private static AppPaths supplied(Path home, HomeSource source) {
        Path absolute = home.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new ConfigException("'" + absolute + "' (from " + source.description()
                    + ") is not an existing directory, so relative operational paths (profiles.dir,"
                    + " classifications.file, secrets.dir, data.dir, reports.dir, logs.dir) cannot be"
                    + " resolved against it. Point it at the application root - the directory that"
                    + " contains bin/ and conf/ - or unset it to fall back to the working directory.");
        }
        return new AppPaths(absolute, source);
    }

    /** The absolute application home every relative operational path is resolved against. */
    public Path home() {
        return home;
    }

    /** Where the home came from. */
    public HomeSource homeSource() {
        return homeSource;
    }

    /** True when no launcher supplied a home and the working directory is in use. */
    public boolean usedWorkingDirectoryFallback() {
        return homeSource == HomeSource.WORKING_DIRECTORY;
    }

    /** {@code /app (from -Dcminsight.home)}, for a startup banner or a doctor line. */
    public String describeHome() {
        return home + " (from " + homeSource.description() + ")";
    }

    /**
     * The absolute path a configured value names, or {@code null} when the value is absent or blank.
     *
     * <p>An absolute value is used as-is; a relative value is resolved against {@link #home()}.
     */
    public Path resolve(String configuredValue) {
        String value = trimToNull(configuredValue);
        if (value == null) {
            return null;
        }
        Path candidate = parse("path", value);
        return candidate.isAbsolute() ? candidate.normalize() : home.resolve(candidate).normalize();
    }

    /** As {@link #resolve(String)}, but falling back to a home-relative default when unset. */
    public Path resolve(String configuredValue, String defaultRelative) {
        Objects.requireNonNull(defaultRelative, "defaultRelative");
        Path resolved = resolve(configuredValue);
        return resolved != null ? resolved : home.resolve(parse("default path", defaultRelative)).normalize();
    }

    /** The configuration file named by {@code --config}, or {@code <home>/conf/application.properties}. */
    public Path configurationFile(String configuredValue) {
        return resolve(configuredValue, DEFAULT_CONFIG_FILE);
    }

    /** {@code profiles.dir} - the directory repository profiles are loaded from. */
    public Path profilesDir(AppConfig config) {
        return resolve(config.get(PROFILES_DIR_KEY, null), DEFAULT_PROFILES_DIR);
    }

    /**
     * {@code classifications.file}, or {@code null} when it is not configured.
     *
     * <p>There is deliberately no default: an absent key means "inline classification rules only", and
     * inventing a file path here would change which rules apply.
     */
    public Path classificationsFile(AppConfig config) {
        return resolve(config.get(CLASSIFICATIONS_FILE_KEY, null));
    }

    /** {@code secrets.dir} - the directory {@code .file} credential references resolve against. */
    public Path secretsDir(AppConfig config) {
        return resolve(config.get(SECRETS_DIR_KEY, null), DEFAULT_SECRETS_DIR);
    }

    /** {@code data.dir} - application-local persistent data, including Goal 04 history. */
    public Path dataDir(AppConfig config) {
        return resolve(config.get(DATA_DIR_KEY, null), DEFAULT_DATA_DIR);
    }

    /** {@code reports.dir} - confined output directory for Goal 04 generated reports. */
    public Path reportsDir(AppConfig config) {
        return resolve(config.get(REPORTS_DIR_KEY, null), DEFAULT_REPORTS_DIR);
    }

    /** {@code logs.dir} - resolved now, read by a later goal. */
    public Path logsDir(AppConfig config) {
        return resolve(config.get(LOGS_DIR_KEY, null), DEFAULT_LOGS_DIR);
    }

    /**
     * Every operational path this class defines, resolved, in a stable order, for diagnostics.
     *
     * <p>Never contains a credential: these are directory and file names only.
     */
    public List<OperationalPath> describe(AppConfig config) {
        Objects.requireNonNull(config, "config");
        List<OperationalPath> entries = new ArrayList<>(6);
        entries.add(entry(config, PROFILES_DIR_KEY, DEFAULT_PROFILES_DIR, false));
        if (config.find(CLASSIFICATIONS_FILE_KEY).isPresent()) {
            entries.add(entry(config, CLASSIFICATIONS_FILE_KEY, null, false));
        }
        entries.add(entry(config, SECRETS_DIR_KEY, DEFAULT_SECRETS_DIR, false));
        // Goal 04 activates the application-local data and report directories. logs.dir stays reserved
        // until a Java logging owner consumes it; keeping that distinction explicit prevents diagnostics
        // from claiming an operational path is live before any code actually uses it.
        entries.add(entry(config, DATA_DIR_KEY, DEFAULT_DATA_DIR, false));
        entries.add(entry(config, REPORTS_DIR_KEY, DEFAULT_REPORTS_DIR, false));
        entries.add(entry(config, LOGS_DIR_KEY, DEFAULT_LOGS_DIR, true));
        return List.copyOf(entries);
    }

    private OperationalPath entry(AppConfig config, String key, String defaultRelative, boolean reserved) {
        boolean configured = config.find(key).isPresent();
        // A key with no default is only listed when it is configured, so one of the two branches
        // always yields a path and OperationalPath never carries null.
        Path path = defaultRelative == null
                ? resolve(config.get(key, null))
                : resolve(config.get(key, null), defaultRelative);
        return new OperationalPath(key, path, configured ? Origin.CONFIGURED : Origin.DEFAULT, reserved);
    }

    private static Path parse(String what, String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new ConfigException("'" + value + "' is not a usable " + what + ": " + e.getMessage());
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public String toString() {
        return "AppPaths[home=" + home + ", source=" + homeSource + "]";
    }
}
