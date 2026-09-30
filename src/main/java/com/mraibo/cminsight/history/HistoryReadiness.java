package com.mraibo.cminsight.history;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * What the history layer can say about itself <strong>without opening anything</strong>.
 *
 * <h2>Local readiness only, and why that distinction is printed</h2>
 *
 * <p>Goal 04 section 11 requires the doctor to distinguish four facts: the feature switch, the presence of
 * the local database driver, whether the store is ready or unavailable, and whether the data directory is
 * writable. It also forbids exactly what would make those facts easy to produce - connecting to anything
 * merely to print configuration. So this value is built from a class-loading probe and filesystem checks
 * only: no database is opened, no directory is created, no socket is touched.
 *
 * <p>{@link State#EXPECTED_READY} therefore means "the three local preconditions hold", not "the database
 * answered". The reason string says so, because "the driver is installed" and "the store opened" are
 * different claims and a diagnostics line that let them blur would be worse than no line - the same rule
 * {@code JdbcDrivers.Readiness} states for the analytics half.
 *
 * @param enabled         whether {@code feature.history} switched history on
 * @param driverPresent   whether the local database driver class is loadable on this class path
 * @param driverClassName the driver class name that was probed; never a URL or a credential
 * @param storageUsable   whether the resolved data directory is writable, or can be created inside a
 *                        writable ancestor
 * @param databasePath    where the store file lives or would live; a local application path, not a URL
 * @param state           the local verdict
 * @param reason          a fixed, value-free explanation for the verdict
 */
public record HistoryReadiness(boolean enabled,
                               boolean driverPresent,
                               String driverClassName,
                               boolean storageUsable,
                               Path databasePath,
                               State state,
                               String reason) {

    /** The four verdicts this local check can reach. */
    public enum State {
        /** {@code feature.history=false}: no local database is opened and no history is offered. */
        DISABLED_BY_FEATURE,
        /** The feature is on but the local database driver is not on the class path. */
        DRIVER_MISSING,
        /** The driver is installed but the data directory cannot be written. */
        STORAGE_UNUSABLE,
        /** Feature on, driver installed, data directory usable - and nothing has been opened yet. */
        EXPECTED_READY
    }

    public HistoryReadiness {
        Objects.requireNonNull(driverClassName, "driverClassName");
        Objects.requireNonNull(databasePath, "databasePath");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reason, "reason");
    }

    /** True when the local preconditions hold; the store itself still has to open successfully. */
    public boolean expectedReady() {
        return state == State.EXPECTED_READY;
    }

    /** True when this verdict means "history is not offered, and nothing else is affected". */
    public boolean unavailable() {
        return state != State.EXPECTED_READY;
    }

    /** One line for a doctor/health payload; carries no credential and no JDBC URL. */
    public String describe() {
        return "history "
                + (enabled ? "enabled" : "disabled")
                + ", local database driver "
                + (driverPresent ? "present (" + driverClassName + ")" : "absent (" + driverClassName + ")")
                + ", storage " + (storageUsable ? "usable" : "NOT usable")
                + ", " + state + ": " + reason;
    }

    @Override
    public String toString() {
        return description();
    }

    private String description() {
        return "HistoryReadiness[" + describe() + "]";
    }

    /**
     * True when {@code directory} is writable, or does not exist yet and can be created below a writable
     * ancestor.
     *
     * <p>Creates nothing: the check is a property of the directory that is already there (or of the nearest
     * ancestor that is), so asking the question twice has no side effect and cannot make an operator's
     * filesystem look different from what the report said.
     */
    static boolean storageUsable(Path directory) {
        if (directory == null) {
            return false;
        }
        Path candidate = directory.toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.exists(candidate)) {
                // An existing path that is not a directory can never contain the store file, however
                // writable it may be, so it is NOT reported as usable.
                return Files.isDirectory(candidate) && Files.isWritable(candidate);
            }
            candidate = candidate.getParent();
        }
        return false;
    }
}
