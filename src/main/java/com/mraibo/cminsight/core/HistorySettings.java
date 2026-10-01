package com.mraibo.cminsight.core;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.AppPaths;
import com.mraibo.cminsight.config.ConfigException;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The validated bounds of the application-local, persistent history store, read once from configuration.
 *
 * <p>Deliberately the same shape as {@link JdbcPoolSettings} and {@link StatisticsSettings} and
 * deliberately a value: the store receives bounds that have already been range-checked, so a misconfigured
 * bound cannot reach the filesystem layer and a diagnostics endpoint can print exactly the numbers the
 * store is running with. The keys, defaults and ranges are the single source of truth for this record and
 * are documented in {@code conf/application.properties.example}:
 *
 * <pre>
 *   feature.history                            default true
 *   history.max.snapshots.per.repository       default 1000, 1..100000
 * </pre>
 *
 * <h2>Where the database file lives</h2>
 *
 * <p>There is deliberately <strong>no</strong> key for the H2 path. The path is derived from
 * {@code data.dir} through {@link AppPaths} - the one authority for "a relative operational path is
 * resolved against the application home, never against the working directory of whatever process started
 * the JVM" - so a second, independent path rule cannot drift from the first. The file is
 * {@code <data.dir>/history/cm-insight-history}, and no caller may place it anywhere else: it is derived
 * here, never accepted from a request, a profile or a URL.
 *
 * <h2>Nothing here is a credential or a connection string</h2>
 *
 * <p>The local database needs no repository credential at all: it is a file this application owns, and the
 * store never reads {@code RepositoryProfile} JDBC values or the analytics pool. This record can therefore
 * be printed and rendered as-is, which is what {@link #describe()} is for.
 *
 * <h2>Disabled is a first-class state</h2>
 *
 * <p>{@code feature.history=false} yields {@link #enabled()} {@code == false}. It is not an error and it
 * does not touch a repository: the IBM CM metadata, the retention viewer, the live statistics and
 * repository activation all stay usable with history off. No local database is opened, and the history API
 * reports the disabled state instead.
 *
 * @param enabled                   whether persistent history is switched on at all
 * @param maxSnapshotsPerRepository how many snapshots one repository may retain, 1..100000
 * @param dataDir                   the resolved {@code data.dir} the store file is created below
 */
public record HistorySettings(boolean enabled, int maxSnapshotsPerRepository, Path dataDir) {

    /** Feature switch: {@code feature.history}. */
    public static final String ENABLED_KEY = "feature.history";
    /** Retention bound: {@code history.max.snapshots.per.repository}. */
    public static final String MAX_SNAPSHOTS_KEY = "history.max.snapshots.per.repository";

    /** Documented default of {@link #MAX_SNAPSHOTS_KEY}: the bounded history window one repository keeps. */
    public static final int DEFAULT_MAX_SNAPSHOTS = 1_000;
    /** Smallest accepted retention bound. */
    public static final int MIN_MAX_SNAPSHOTS = 1;
    /** Largest accepted retention bound. */
    public static final int MAX_MAX_SNAPSHOTS = 100_000;

    /** Directory created below {@code data.dir} for the local database file. */
    public static final String HISTORY_DIRECTORY_NAME = "history";
    /** Base name of the local database file; the embedded database appends its own file suffix. */
    public static final String HISTORY_DATABASE_NAME = "cm-insight-history";

    public HistorySettings {
        Objects.requireNonNull(dataDir, "dataDir");
        if (maxSnapshotsPerRepository < MIN_MAX_SNAPSHOTS || maxSnapshotsPerRepository > MAX_MAX_SNAPSHOTS) {
            throw new IllegalArgumentException(MAX_SNAPSHOTS_KEY + " must be between " + MIN_MAX_SNAPSHOTS
                    + " and " + MAX_MAX_SNAPSHOTS + " but was " + maxSnapshotsPerRepository);
        }
    }

    /**
     * Reads the history settings, failing closed on every out-of-range value.
     *
     * <p>Uses the same strict {@code getBoolean}/{@code getInt} readers as {@code cm.pool.*} and
     * {@code jdbc.pool.*}, so a typo such as {@code history.max.snapshots.per.repository=0}, a value above
     * the documented maximum, an unparseable number or {@code feature.history=yes} is a
     * {@link ConfigException} naming the key - never a silently clamped or silently ignored value. No file
     * is created, no directory is probed and no database is opened by reading configuration: this method is
     * safe on the activation path of a repository that has nothing to do with history.
     *
     * @param config the configuration to read
     * @param paths  the path authority that resolves {@code data.dir}
     * @throws ConfigException when a value is not a boolean/integer or is out of range
     */
    public static HistorySettings from(AppConfig config, AppPaths paths) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(paths, "paths");
        boolean enabled = config.getBoolean(ENABLED_KEY, true);
        int maxSnapshots = config.getInt(MAX_SNAPSHOTS_KEY, DEFAULT_MAX_SNAPSHOTS,
                MIN_MAX_SNAPSHOTS, MAX_MAX_SNAPSHOTS);
        return new HistorySettings(enabled, maxSnapshots, paths.dataDir(config));
    }

    /** The documented defaults over an already resolved data directory. */
    public static HistorySettings defaults(Path dataDir) {
        return new HistorySettings(true, DEFAULT_MAX_SNAPSHOTS, dataDir);
    }

    /** The directory the local database file is created in: {@code <data.dir>/history}. */
    public Path databaseDirectory() {
        return dataDir.resolve(HISTORY_DIRECTORY_NAME).normalize();
    }

    /**
     * The local database file, resolved below {@link #databaseDirectory()}.
     *
     * <p>The embedded database appends its own suffix (for the bundled engine, {@code .mv.db}), so this is
     * the base name of the file rather than the file itself. It is always confined to the resolved data
     * directory: the confinement is structural because the value is only ever built by resolving a fixed
     * name below {@link #dataDir}, never accepted from configuration or from a request.
     */
    public Path databasePath() {
        return databaseDirectory().resolve(HISTORY_DATABASE_NAME).normalize();
    }

    /** True when {@link #databasePath()} is confined to the configured data directory. */
    public boolean databaseConfined() {
        // Both sides are made absolute before the comparison so a programmatically supplied relative
        // data directory cannot make confinement look false (or true) for the wrong reason.
        return databasePath().toAbsolutePath().normalize().startsWith(dataDir.toAbsolutePath().normalize());
    }

    /** One line for the doctor/configuration output; carries no credential and no URL. */
    public String describe() {
        return ENABLED_KEY + "=" + enabled
                + ", " + MAX_SNAPSHOTS_KEY + "=" + maxSnapshotsPerRepository
                + ", " + AppPaths.DATA_DIR_KEY + "=" + dataDir
                + ", local history database below " + databaseDirectory();
    }

    @Override
    public String toString() {
        return "HistorySettings[enabled=" + enabled
                + ", maxSnapshotsPerRepository=" + maxSnapshotsPerRepository
                + ", dataDir=" + dataDir + "]";
    }
}
