package com.mraibo.cminsight.db;

import com.mraibo.cminsight.config.DatabaseVendor;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Local, offline readiness for the DB2 and Oracle JDBC drivers.
 *
 * <p>Answers one runtime question: <em>is a driver for this vendor installed on the class path?</em> It is
 * deliberately the only place in the codebase that names a vendor driver class, so the readiness verdict
 * cannot be computed two different ways by two callers.
 *
 * <h2>Discovery is local and never opens a connection</h2>
 *
 * <p>Readiness is class loading and {@link DriverManager} inspection only. It performs no network call, no
 * DNS lookup and no connection attempt, so it is safe to run from configuration validation, from
 * {@code bin/doctor.sh}, and from a diagnostics route - none of which may touch a database merely to
 * describe configuration. <strong>A loadable driver is not evidence of a reachable database</strong>, and
 * nothing here should ever be reported as live-database validation.
 *
 * <p>The Oracle legacy class is accepted as a compatibility fallback because older Oracle jars register
 * {@code oracle.jdbc.driver.OracleDriver} rather than {@code oracle.jdbc.OracleDriver}; the preferred class
 * is reported when both are present.
 */
public final class JdbcDrivers {

    /** DB2's universal JDBC driver. */
    public static final String DB2_DRIVER = "com.ibm.db2.jcc.DB2Driver";

    /** Oracle's current driver class name. */
    public static final String ORACLE_DRIVER = "oracle.jdbc.OracleDriver";

    /** Oracle's legacy driver class name, accepted as a compatibility fallback only. */
    public static final String ORACLE_LEGACY_DRIVER = "oracle.jdbc.driver.OracleDriver";

    private JdbcDrivers() {
    }

    /** The driver class name this project expects for {@code vendor}, preferring the current name. */
    public static String expectedDriverClass(DatabaseVendor vendor) {
        Objects.requireNonNull(vendor, "vendor");
        return switch (vendor) {
            case DB2 -> DB2_DRIVER;
            case ORACLE -> ORACLE_DRIVER;
        };
    }

    /** Every accepted driver class name for {@code vendor}, in preference order. */
    public static List<String> acceptedDriverClasses(DatabaseVendor vendor) {
        Objects.requireNonNull(vendor, "vendor");
        return switch (vendor) {
            case DB2 -> List.of(DB2_DRIVER);
            case ORACLE -> List.of(ORACLE_DRIVER, ORACLE_LEGACY_DRIVER);
        };
    }

    /** The only JDBC URL prefix accepted for {@code vendor}. */
    public static String urlPrefix(DatabaseVendor vendor) {
        Objects.requireNonNull(vendor, "vendor");
        return switch (vendor) {
            case DB2 -> "jdbc:db2:";
            case ORACLE -> "jdbc:oracle:";
        };
    }

    /**
     * True when {@code jdbcUrl} belongs to {@code vendor}'s family.
     *
     * <p>A mismatch is a configuration error the caller must refuse, never something to "try anyway": the
     * wrong driver cannot serve the URL, and attempting it produces a driver exception that says less than
     * this check does.
     */
    public static boolean urlMatchesVendor(DatabaseVendor vendor, String jdbcUrl) {
        Objects.requireNonNull(vendor, "vendor");
        if (jdbcUrl == null) {
            return false;
        }
        return jdbcUrl.trim().toLowerCase(Locale.ROOT).startsWith(urlPrefix(vendor));
    }

    /** True when the driver class for {@code vendor} can be loaded from the current class path. */
    public static boolean driverInstalled(DatabaseVendor vendor) {
        return driverInstalled(vendor, JdbcDrivers.class.getClassLoader());
    }

    /**
     * True when the driver class for {@code vendor} can be loaded from {@code loader}.
     *
     * <p>The loader is a parameter so the "driver absent" answer is testable. Without it, a test tree that
     * commits real vendor-named driver classes - which a POSITIVE readiness test must, because
     * {@link #driverReady} needs a registered driver whose class name matches - makes absence permanently
     * unreachable in-process, and the branch would be untestable precisely because the positive case works.
     *
     * @param loader the class loader to probe; a null value means the platform class loader
     */
    public static boolean driverInstalled(DatabaseVendor vendor, ClassLoader loader) {
        for (String className : acceptedDriverClasses(vendor)) {
            try {
                Class.forName(className, false, loader);
                return true;
            } catch (ClassNotFoundException | LinkageError notInstalled) {
                // Try the next accepted name; absence is a normal, reportable state and not an error.
            }
        }
        return false;
    }

    /** True when a driver for {@code vendor} is both loadable <em>and</em> registered with {@link DriverManager}. */
    public static boolean driverReady(DatabaseVendor vendor) {
        return driverReady(vendor, JdbcDrivers.class.getClassLoader());
    }

    /**
     * True when a driver for {@code vendor} is both loadable through {@code loader} <em>and</em> registered
     * with {@link DriverManager}, so a {@code DriverManager.getConnection} call would find it.
     *
     * <p>A driver class that exists but never registered cannot serve a connection, so reporting it as
     * ready would move the failure to scan time and present it as a database problem.
     *
     * <p>Note the asymmetry this method deliberately keeps visible: the <em>registration</em> check reads the
     * process-wide {@link DriverManager}, which no class loader can scope. A loader that hides the driver
     * class therefore yields not-ready even if an identically named driver is registered - the honest answer,
     * because that loader could not load the class the registration belongs to.
     */
    public static boolean driverReady(DatabaseVendor vendor, ClassLoader loader) {
        if (!driverInstalled(vendor, loader)) {
            return false;
        }
        List<String> accepted = acceptedDriverClasses(vendor);
        Enumeration<Driver> registered = DriverManager.getDrivers();
        while (registered.hasMoreElements()) {
            Driver driver = registered.nextElement();
            if (driver != null && accepted.contains(driver.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The safe, printable driver identity for diagnostics: the class name actually installed, or an empty
     * list when none is. A class name is metadata about the class path, not a credential or a URL.
     */
    public static List<String> installedDriverClasses(DatabaseVendor vendor) {
        return installedDriverClasses(vendor, JdbcDrivers.class.getClassLoader());
    }

    /** As {@link #installedDriverClasses(DatabaseVendor)} but probing {@code loader}. */
    public static List<String> installedDriverClasses(DatabaseVendor vendor, ClassLoader loader) {
        List<String> installed = new ArrayList<>(2);
        for (String className : acceptedDriverClasses(vendor)) {
            try {
                Class.forName(className, false, loader);
                installed.add(className);
            } catch (ClassNotFoundException | LinkageError notInstalled) {
                // Absence is reported by omission rather than as an error string.
            }
        }
        return List.copyOf(installed);
    }

    /**
     * Validates the vendor/URL pairing and answers the driver-readiness verdict in one place.
     *
     * <p>Returns a ready result carrying the driver class name, or a not-ready result carrying a fixed,
     * value-free reason suitable for a diagnostics payload or a doctor line.
     */
    public static Readiness readiness(DatabaseVendor vendor, String jdbcUrl) {
        return readiness(vendor, jdbcUrl, JdbcDrivers.class.getClassLoader());
    }

    /** As {@link #readiness(DatabaseVendor, String)} but probing {@code loader} for the driver class. */
    public static Readiness readiness(DatabaseVendor vendor, String jdbcUrl, ClassLoader loader) {
        Objects.requireNonNull(vendor, "vendor");
        if (!urlMatchesVendor(vendor, jdbcUrl)) {
            return new Readiness(false, "", "the configured JDBC URL is not a " + vendor
                    + " URL (expected a prefix of '" + urlPrefix(vendor) + "')");
        }
        List<String> installed = installedDriverClasses(vendor, loader);
        if (installed.isEmpty()) {
            return new Readiness(false, "", "the " + vendor + " JDBC driver is not on the class path; place"
                    + " its jar in " + vendorLibDirectory(vendor) + " or set CM_INSIGHT_JDBC_LIBS");
        }
        String installedClass = installed.get(0);
        if (!driverReady(vendor, loader)) {
            return new Readiness(false, installedClass, "the " + vendor + " JDBC driver class is present but"
                    + " no registered Driver serves this vendor, so a connection could not be obtained");
        }
        return new Readiness(true, installedClass, "");
    }

    /** The documented local directory an operator places this vendor's driver jars in. */
    public static String vendorLibDirectory(DatabaseVendor vendor) {
        Objects.requireNonNull(vendor, "vendor");
        return switch (vendor) {
            case DB2 -> "lib/db2";
            case ORACLE -> "lib/oracle";
        };
    }

    /**
     * A local readiness verdict.
     *
     * @param ready       true only when a registered driver can serve this vendor's URLs
     * @param driverClass the installed driver class name, or empty when none is installed; never a URL or a
     *                    credential
     * @param reason      a fixed, value-free explanation when not ready, else empty
     */
    public record Readiness(boolean ready, String driverClass, String reason) {

        public Readiness {
            Objects.requireNonNull(driverClass, "driverClass");
            Objects.requireNonNull(reason, "reason");
        }

        /** The driver identity to publish, or a stable placeholder when no driver is installed. */
        public String driverIdentity() {
            return driverClass.isEmpty() ? "none" : driverClass;
        }
    }

    /**
     * Confirms {@code jdbcUrl} can be served by the installed driver, throwing a <strong>sanitised</strong>
     * failure when it cannot.
     *
     * <p>The thrown exception deliberately carries only the fixed reason and never the URL, so a caller that
     * records or returns it cannot leak a connection string. Its class is the caller's own configuration
     * exception type, supplied so this class does not need to depend on the configuration package.
     */
    public static void requireReady(DatabaseVendor vendor, String jdbcUrl,
            java.util.function.Function<String, RuntimeException> refusalFactory) {
        Readiness readiness = readiness(vendor, jdbcUrl);
        if (!readiness.ready()) {
            throw refusalFactory.apply(readiness.reason());
        }
    }

    /**
     * A {@code DriverManager}-independent URL sanity check used by tests and by configuration validation,
     * so neither has to attempt a connection to find out that a URL is obviously wrong.
     */
    public static boolean looksLikeJdbcUrl(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.trim().toLowerCase(Locale.ROOT).startsWith("jdbc:");
    }

    /** Exposed so a caller can report driver registration problems without catching {@link SQLException}. */
    public static List<String> registeredDriverClassNames() {
        List<String> names = new ArrayList<>();
        Enumeration<Driver> registered = DriverManager.getDrivers();
        while (registered.hasMoreElements()) {
            Driver driver = registered.nextElement();
            if (driver != null) {
                names.add(driver.getClass().getName());
            }
        }
        return List.copyOf(names);
    }
}
