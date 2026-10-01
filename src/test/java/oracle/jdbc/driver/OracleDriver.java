package oracle.jdbc.driver;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

import com.mraibo.cminsight.test.FakeJdbc;

/**
 * A test-tree stand-in for the LEGACY Oracle JDBC driver, named exactly as the real one.
 *
 * <p>Goal 03 section 5 requires the legacy name {@code oracle.jdbc.driver.OracleDriver} to keep working as
 * a compatibility fallback. That fallback is a real code path - preferred class first, legacy second - so it
 * needs a real class to fall back TO; otherwise the only reachable outcome in CI is "no driver", and the
 * fallback would ship untested. Lives in {@code src/test/java} and is never packaged; every method delegates
 * to {@link FakeJdbc}.
 */
public final class OracleDriver implements Driver {

    private static final OracleDriver INSTANCE = new OracleDriver();

    static {
        try {
            DriverManager.registerDriver(INSTANCE);
        } catch (SQLException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** Public no-argument constructor: readiness may instantiate the driver class reflectively. */
    public OracleDriver() {
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return FakeJdbc.connectViaVendorDriver(url, info);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:oracle:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("oracle.jdbc.driver.OracleDriver");
    }

    @Override
    public String toString() {
        return "FakeOracleLegacyDriver";
    }
}
