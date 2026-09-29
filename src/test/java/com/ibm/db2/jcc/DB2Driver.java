package com.ibm.db2.jcc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

import com.mraibo.cminsight.test.FakeJdbc;

/**
 * A test-tree stand-in for the DB2 JDBC driver, named exactly as the real one.
 *
 * <h2>Why a class with a vendor name lives in the TEST tree</h2>
 *
 * <p>Goal 03 section 5 requires local driver readiness to be class loading and registration only - never a
 * connection - and to support {@code com.ibm.db2.jcc.DB2Driver} by that name. A dynamically registered
 * proxy cannot answer {@code Class.forName("com.ibm.db2.jcc.DB2Driver")}, so the only dependency-free way
 * to test a POSITIVE readiness result is a real class with the real name on the test class path.
 *
 * <p>It is deliberately in {@code src/test/java}, never {@code src/main/java}: the core class path must
 * contain no vendor driver, and {@code tests/shell/ibm_guard.sh} refuses a contiguous {@code com.ibm.}
 * reference under {@code src/main/java}. This class is also not packaged by {@code build.sh}, which
 * compiles the main sources before any test source exists.
 *
 * <p>Every method delegates to {@link FakeJdbc}, which owns the counters, the configurable failures and the
 * row model. With no active {@code FakeJdbc} for the URL this driver returns {@code null}, which is the
 * JDBC contract for "not my URL" and lets {@code DriverManager} report its own no-suitable-driver failure.
 */
public final class DB2Driver implements Driver {

    private static final DB2Driver INSTANCE = new DB2Driver();

    static {
        try {
            DriverManager.registerDriver(INSTANCE);
        } catch (SQLException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** Public no-argument constructor: readiness may instantiate the driver class reflectively. */
    public DB2Driver() {
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return FakeJdbc.connectViaVendorDriver(url, info);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:db2:");
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
        return Logger.getLogger("com.ibm.db2.jcc.DB2Driver");
    }

    @Override
    public String toString() {
        return "FakeDB2Driver";
    }
}
