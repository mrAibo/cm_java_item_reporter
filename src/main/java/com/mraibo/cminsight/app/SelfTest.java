package com.mraibo.cminsight.app;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.db.Db2Dialect;
import com.mraibo.cminsight.db.OracleDialect;
import com.mraibo.cminsight.security.SecurityPolicy;

import java.util.Properties;

public final class SelfTest {
    private SelfTest() {}

    public static void main(String[] args) {
        Properties local = new Properties();
        local.setProperty("web.bind", "127.0.0.1");
        local.setProperty("web.auth.user", "admin");
        local.setProperty("web.auth.password", "admin");
        SecurityPolicy.validateWebExposure(AppConfig.fromProperties(local));

        Properties exposed = new Properties();
        exposed.setProperty("web.bind", "0.0.0.0");
        exposed.setProperty("web.auth.user", "admin");
        exposed.setProperty("web.auth.password", "admin");
        boolean rejected = false;
        try {
            SecurityPolicy.validateWebExposure(AppConfig.fromProperties(exposed));
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        if (!rejected) throw new AssertionError("Default credentials must be rejected on non-loopback bind");
        if (!"DB2".equals(new Db2Dialect().id())) throw new AssertionError("DB2 dialect mismatch");
        if (!"ORACLE".equals(new OracleDialect().id())) throw new AssertionError("Oracle dialect mismatch");

        System.out.println("CM Insight bootstrap self-test: PASS");
    }
}
