package oracle.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

import com.mraibo.cminsight.test.FakeJdbc;

/**
 * A test-tree stand-in for the preferred Oracle JDBC driver, named exactly as the real one.
 *
 * <p>See {@code com.ibm.db2.jcc.DB2Driver} in this same test tree for the full reasoning: Goal 03 section 5
 * resolves drivers by their real class name with {@code Class.forName}, so a POSITIVE readiness result can
 * only be tested with a real class carrying that name. This class lives in {@code src/test/java} and is
 * never packaged; every method delegates to {@link FakeJdbc}.
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
        return Logger.getLogger("oracle.jdbc.OracleDriver");
    }

    @Override
    public String toString() {
        return "FakeOracleDriver";
    }
}
