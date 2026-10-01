package com.mraibo.cminsight.history;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.AppPaths;
import com.mraibo.cminsight.config.ConfigException;
import com.mraibo.cminsight.core.HistorySettings;

import java.nio.file.Path;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The one place that decides which {@link HistoryStore} a runtime gets, and the only place that names the
 * local database driver class.
 *
 * <h2>Absence is a state, never an activation failure</h2>
 *
 * <p>Three facts decide the answer, and every one of them is local: is history switched on, is the local
 * database driver installed, does the store file open. When any of them says no, the caller receives an
 * {@link UnavailableHistoryStore} carrying the reason - never an exception and never a half-open store.
 * A repository whose local history cannot work must still activate, so a missing optional JAR can never
 * reach the repository activation path as a failure.
 *
 * <p>Driver discovery is {@code Class.forName} on a class NAME through {@code java.sql}, exactly as the
 * analytics half discovers its vendors' drivers: the core compiles with zero local-database classes on its
 * class path, so a compile-time import would break a clean checkout while working on a developer machine
 * that happens to have the JAR. Nothing here uses {@code DriverManager.getConnection}: the discovered
 * driver is instantiated and asked for a connection itself, so no process-wide registration state decides
 * whether local history works.
 *
 * <h2>Readiness without opening anything</h2>
 *
 * <p>{@link #readiness(HistorySettings)} and {@link #findings(AppPaths, AppConfig)} answer what the doctor
 * and a diagnostics payload need, using a class-loading probe and filesystem checks only. They open no
 * database, create no directory and touch no socket - the local store is opened by the application-local
 * history lifecycle, and by that lifecycle only.
 */
public final class HistoryStores {

    /** The local database driver this application expects, discovered by name at runtime. */
    public static final String H2_DRIVER_CLASS = "org.h2.Driver";

    private HistoryStores() {
    }

    /**
     * Opens the local history store for {@code settings}, or returns an explicitly unavailable store.
     *
     * <p>The returned object is always usable: every state is an object, so a caller never branches on
     * {@code null}. A store whose file opens but whose recorded schema version is not the version this build
     * writes is returned as a REFUSED {@link H2HistoryStore} (rather than as a generic unavailable store),
     * because that refusal has to stay diagnosable: it names both versions and reports
     * {@link HistoryStore#schemaVersion()} for the build that refused.
     */
    public static HistoryStore open(HistorySettings settings) {
        return open(settings, H2_DRIVER_CLASS);
    }

    /**
     * As {@link #open(HistorySettings)} but probing {@code databaseDriverClassName}.
     *
     * <p>The class name is a parameter so "the driver is absent" is testable in-process: the committed test
     * tree cannot remove a driver from its own class path, and a branch that can only be observed by
     * breaking the environment is a branch nobody checks.
     */
    public static HistoryStore open(HistorySettings settings, String databaseDriverClassName) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(databaseDriverClassName, "databaseDriverClassName");

        if (!settings.enabled()) {
            return UnavailableHistoryStore.disabledByFeature();
        }
        Optional<Class<? extends Driver>> driverClass = loadDriverClass(databaseDriverClassName);
        if (driverClass.isEmpty()) {
            return UnavailableHistoryStore.driverMissing(databaseDriverClassName);
        }
        H2HistoryStore store = H2HistoryStore.open(driverClass.get(), settings.databasePath(),
                settings.maxSnapshotsPerRepository());
        if (store.available() || store.schemaVersionMismatch()) {
            // A schema-version refusal is returned as itself on purpose: it is not "history is missing", it
            // is "this file was written by another version", and the object that knows both versions is the
            // one that must report it.
            return store;
        }
        String reason = store.unavailableReason()
                .orElse("the local history database could not be opened");
        store.close();
        return new UnavailableHistoryStore(reason);
    }

    /** An explicitly unavailable store with a fixed reason, for a caller that has to report one. */
    public static HistoryStore unavailable(String reason) {
        return new UnavailableHistoryStore(reason);
    }

    /** True when the expected local database driver is loadable from the current class path. */
    public static boolean driverPresent() {
        return driverPresent(H2_DRIVER_CLASS);
    }

    /**
     * True when {@code driverClassName} is loadable and is a JDBC driver.
     *
     * <p>A class-loading probe only: the class is loaded without being initialized, no
     * {@code DriverManager} state is consulted and no connection is attempted. "The driver is installed" is
     * not evidence of a usable store, which is why {@link #readiness(HistorySettings)} reports the two
     * facts apart.
     */
    public static boolean driverPresent(String driverClassName) {
        return driverPresent(driverClassName, HistoryStores.class.getClassLoader());
    }

    /** As {@link #driverPresent(String)} but probing {@code loader}, so absence is testable. */
    public static boolean driverPresent(String driverClassName, ClassLoader loader) {
        return loadDriverClass(driverClassName, loader).isPresent();
    }

    /**
     * The local readiness verdict: feature switch, driver presence, storage usability - and nothing opened.
     *
     * <p>Contains no I/O beyond filesystem property checks, so it is safe in a doctor run, in a health
     * endpoint and on a repository activation path.
     */
    public static HistoryReadiness readiness(HistorySettings settings) {
        Objects.requireNonNull(settings, "settings");
        boolean enabled = settings.enabled();
        boolean driver = driverPresent(H2_DRIVER_CLASS);
        boolean storage = HistoryReadiness.storageUsable(settings.dataDir());
        Path databasePath = settings.databasePath();

        if (!enabled) {
            return new HistoryReadiness(false, driver, H2_DRIVER_CLASS, storage, databasePath,
                    HistoryReadiness.State.DISABLED_BY_FEATURE,
                    HistorySettings.ENABLED_KEY + "=false: no local database is opened, no history is"
                            + " recorded and the history routes report the disabled state. Repository"
                            + " activation, metadata, retention and live statistics are unaffected.");
        }
        if (!driver) {
            return new HistoryReadiness(true, false, H2_DRIVER_CLASS, storage, databasePath,
                    HistoryReadiness.State.DRIVER_MISSING,
                    "the local history database driver (" + H2_DRIVER_CLASS + ") is not on this runtime's"
                            + " class path: place the local database jar in lib/app to enable persistent"
                            + " history. Nothing else is affected and no repository fails to activate.");
        }
        if (!storage) {
            return new HistoryReadiness(true, true, H2_DRIVER_CLASS, false, databasePath,
                    HistoryReadiness.State.STORAGE_UNUSABLE,
                    "the configured data directory " + settings.dataDir() + " is not writable and no"
                            + " writable ancestor can create it, so the local history store cannot be"
                            + " opened. History reports unavailable; everything else keeps working.");
        }
        return new HistoryReadiness(true, true, H2_DRIVER_CLASS, true, databasePath,
                HistoryReadiness.State.EXPECTED_READY,
                "the feature is on, the local database driver is present and the data directory is usable."
                        + " This check opened no database: the store is opened by the application-local"
                        + " history lifecycle, and a loadable driver is not evidence of an open store.");
    }

    /**
     * The history section of the configuration report, composed from the runtime's own readers.
     *
     * <p>Nothing here opens a database, creates a directory or reads a repository. A malformed
     * {@code history.*} value is an {@link HistoryFinding.Level#ERROR} carrying the runtime's own refusal
     * message, because the runtime reads the same value through the same reader before it serves anything -
     * so the doctor cannot accept a bound the runtime would refuse, or refuse one it accepts.
     */
    public static List<HistoryFinding> findings(AppPaths paths, AppConfig config) {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(config, "config");

        List<HistoryFinding> findings = new ArrayList<>(4);
        final HistorySettings settings;
        try {
            settings = HistorySettings.from(config, paths);
        } catch (ConfigException e) {
            // The runtime reads this before it serves anything and exits on the same exception, so it is a
            // refusal here, not advice.
            findings.add(new HistoryFinding(HistoryFinding.Level.ERROR, e.getMessage()));
            findings.add(new HistoryFinding(HistoryFinding.Level.WARN, "history readiness was not further"
                    + " evaluated because the history settings above are refused"));
            return List.copyOf(findings);
        }

        if (settings.enabled()) {
            findings.add(new HistoryFinding(HistoryFinding.Level.OK,
                    "history settings accepted: " + settings.describe()));
        } else {
            findings.add(new HistoryFinding(HistoryFinding.Level.WARN, HistorySettings.ENABLED_KEY
                    + "=false: persistent history is switched off. No local database is opened and the"
                    + " history routes report the disabled state; repository activation, metadata, retention"
                    + " and live statistics are unaffected."));
        }

        HistoryReadiness readiness = readiness(settings);
        findings.add(new HistoryFinding(
                readiness.driverPresent() ? HistoryFinding.Level.OK : HistoryFinding.Level.WARN,
                "local history database driver " + H2_DRIVER_CLASS + " = "
                        + (readiness.driverPresent() ? "present" : "ABSENT")
                        + (readiness.driverPresent()
                                ? ""
                                : " (place the local database jar in lib/app; this is not an error, history"
                                        + " reports unavailable and nothing else changes)")));

        findings.add(new HistoryFinding(
                readiness.expectedReady() ? HistoryFinding.Level.OK : HistoryFinding.Level.WARN,
                "history store = " + (readiness.expectedReady() ? "expected ready" : "UNAVAILABLE")
                        + ": " + readiness.reason()));
        findings.add(new HistoryFinding(HistoryFinding.Level.OK,
                "history readiness is LOCAL only: this check loaded a class NAME and inspected the data"
                        + " directory, opened no database and created nothing"));
        return List.copyOf(findings);
    }

    /**
     * A fresh store-owned identity: a lowercase alphanumeric token of the one shape {@link HistoryId}
     * accepts.
     *
     * <p>This is the generator {@link H2HistoryStore#record(HistoryDetail)} uses, exposed so a test double
     * can produce the same shape. It is deliberately NOT a way for a caller to choose a key:
     * {@code record(...)} always assigns its own identity and ignores the one carried by the incoming
     * summary, because a value derived from the context-local scan id is not an identity after a restart.
     */
    public static HistoryId newId() {
        return new HistoryId("h" + UUID.randomUUID().toString().replace("-", ""));
    }

    /**
     * Refuses a non-positive page size, exactly as {@link HistoryStore#list(String, int)} requires.
     *
     * <p>One implementation for every store: a caller bug is refused identically whether or not local
     * history happens to be available, so a limit that is accepted in one environment cannot be silently
     * tolerated in another.
     */
    static int requirePositiveLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive but was " + limit);
        }
        return limit;
    }

    /**
     * The driver class, when it is loadable and really is a JDBC driver.
     *
     * <p>Loaded WITHOUT initialization and never through {@code DriverManager}: discovery must be a pure
     * class-path question, so it can be answered by a doctor that is forbidden to connect to anything.
     */
    static Optional<Class<? extends Driver>> loadDriverClass(String driverClassName) {
        return loadDriverClass(driverClassName, HistoryStores.class.getClassLoader());
    }

    /** As {@link #loadDriverClass(String)} but probing {@code loader}. */
    static Optional<Class<? extends Driver>> loadDriverClass(String driverClassName, ClassLoader loader) {
        if (driverClassName == null || driverClassName.isBlank()) {
            return Optional.empty();
        }
        try {
            Class<?> candidate = Class.forName(driverClassName.trim(), false, loader);
            if (!Driver.class.isAssignableFrom(candidate)) {
                return Optional.empty();
            }
            return Optional.of(candidate.asSubclass(Driver.class));
        } catch (ClassNotFoundException | LinkageError | RuntimeException notInstalledOrNotADriver) {
            // Absence is a normal, reportable state and never an error: the caller turns it into an
            // explicitly unavailable store. RuntimeException covers a security manager's refusal and a
            // driver class that fails while linkage resolves - all of them mean "not usable here".
            return Optional.empty();
        }
    }
}
