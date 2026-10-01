package com.mraibo.cminsight.app;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.ConfigException;
import com.mraibo.cminsight.config.SecretRef;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.config.WebAuthSettings;
import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.PoolMetrics;
import com.mraibo.cminsight.connection.ResourceFactory;
import com.mraibo.cminsight.core.FeatureIds;
import com.mraibo.cminsight.core.FeatureRegistry;
import com.mraibo.cminsight.db.Db2Dialect;
import com.mraibo.cminsight.db.OracleDialect;
import com.mraibo.cminsight.security.SecurityPolicy;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dependency-free sanity check of the shipped artifact.
 *
 * <p>This is not the test suite. It verifies a small set of invariants that must hold in the packaged
 * JAR even when no test classes are on the classpath, which is what makes {@code --self-test} useful
 * on a target host. The full suite is {@code com.mraibo.cminsight.test.SelfTest} and is run by
 * {@code build.sh} and {@code tests/selftest.sh}.
 */
public final class SelfCheck {

    private static final AtomicInteger CHECKS = new AtomicInteger();

    private SelfCheck() {
    }

    /** Runs every check. Returns a process exit code: 0 when all checks pass. */
    public static int run() {
        try {
            dialectsAreStable();
            durationParsingIsStrict();
            nonLoopbackDefaultsAreRejected();
            loopbackDefaultsAreAllowed();
            retentionAdminCannotBeEnabled();
            poolNeverOvershoots();
            poolBorrowTimeoutIsBackpressure();
        } catch (AssertionError e) {
            System.err.println("CM Insight self-check: FAIL - " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("CM Insight self-check: ERROR - " + e);
            return 1;
        }
        System.out.println("CM Insight self-check: PASS (" + CHECKS.get() + " checks)");
        return 0;
    }

    private static void check(String name, boolean condition) {
        CHECKS.incrementAndGet();
        if (!condition) {
            throw new AssertionError(name);
        }
    }

    /**
     * The dialects still identify themselves by the configuration vendor name, and every SQL operation they
     * expose is now a <strong>complete statement</strong>.
     *
     * <p>The replaced dialect exposed {@code oneRowSuffix()}, and the two implementations disagreed about
     * what that fragment was: DB2 returned a trailing clause, Oracle a {@code WHERE}-clause fragment that was
     * wrong unless the caller happened to have a {@code WHERE} clause. Pinning the complete statements here
     * means the packaged artifact cannot silently reintroduce a context-sensitive fragment.
     */
    private static void dialectsAreStable() {
        Db2Dialect db2 = new Db2Dialect();
        OracleDialect oracle = new OracleDialect();
        check("DB2 dialect id", "DB2".equals(db2.id()));
        check("Oracle dialect id", "ORACLE".equals(oracle.id()));
        check("DB2 accepts only db2 URLs",
                db2.supports("jdbc:db2://host:50000/db") && !db2.supports("jdbc:oracle:thin:@host:1521/db"));
        check("Oracle accepts only oracle URLs",
                oracle.supports("jdbc:oracle:thin:@host:1521/db") && !oracle.supports("jdbc:db2://host:50000/db"));
        check("DB2 current-date query is a complete SELECT using DB2's dummy table",
                "SELECT CURRENT DATE FROM SYSIBM.SYSDUMMY1".equals(db2.currentDateSql().trim()));
        check("Oracle current-date query is a complete statement using DUAL",
                oracle.currentDateSql().toUpperCase(java.util.Locale.ROOT).contains("FROM DUAL"));
        check("DB2 zero-row probe carries DB2's own row-limit clause",
                "SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0 FETCH FIRST 1 ROW ONLY"
                        .equals(db2.zeroRowProbeSql("ICMADMIN", "ICMUT00001001")));
        check("Oracle zero-row probe carries its own row-limit predicate",
                "SELECT 1 AS PRESENT FROM ICMADMIN.ICMUT00001001 WHERE 1 = 0 AND ROWNUM <= 1"
                        .equals(oracle.zeroRowProbeSql("ICMADMIN", "ICMUT00001001")));
    }

    private static void durationParsingIsStrict() {
        Properties properties = new Properties();
        properties.setProperty("a", "500ms");
        properties.setProperty("b", "30s");
        properties.setProperty("c", "5m");
        AppConfig config = AppConfig.fromProperties(properties);
        check("500ms", Duration.ofMillis(500).equals(
                config.getDuration("a", Duration.ZERO, Duration.ZERO, Duration.ofDays(1))));
        check("30s", Duration.ofSeconds(30).equals(
                config.getDuration("b", Duration.ZERO, Duration.ZERO, Duration.ofDays(1))));
        check("5m", Duration.ofMinutes(5).equals(
                config.getDuration("c", Duration.ZERO, Duration.ZERO, Duration.ofDays(1))));

        Properties bad = new Properties();
        bad.setProperty("x", "not-a-duration");
        boolean rejected = false;
        try {
            AppConfig.fromProperties(bad).getDuration("x", Duration.ZERO, Duration.ZERO, Duration.ofDays(1));
        } catch (ConfigException expected) {
            rejected = true;
        }
        check("invalid duration is rejected", rejected);
    }

    private static void nonLoopbackDefaultsAreRejected() {
        WebAuthSettings defaults = WebAuthSettings.resolve(
                AppConfig.fromProperties(new Properties()),
                new SecretResolver(java.util.Map.of(), null));
        check("effective credentials are the development default", defaults.defaultCredentials());
        boolean rejected = false;
        try {
            SecurityPolicy.validateWebExposure(defaults, "0.0.0.0", 8080);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        check("non-loopback bind with default credentials is refused", rejected);
    }

    private static void loopbackDefaultsAreAllowed() {
        WebAuthSettings defaults = WebAuthSettings.resolve(
                AppConfig.fromProperties(new Properties()),
                new SecretResolver(java.util.Map.of(), null));
        SecurityPolicy.validateWebExposure(defaults, "127.0.0.1", 8080);
        check("loopback bind with default credentials is allowed", true);

        Properties configured = new Properties();
        configured.setProperty("web.auth.user", "operator");
        configured.setProperty("web.auth.password.env", "CM_INSIGHT_WEB_PASSWORD");
        WebAuthSettings resolved = WebAuthSettings.resolve(
                AppConfig.fromProperties(configured),
                new SecretResolver(java.util.Map.of("CM_INSIGHT_WEB_PASSWORD", "s3cret"), null));
        check("environment indirection resolves", "s3cret".equals(resolved.password()));
        check("environment indirection is recorded",
                resolved.passwordSource().source() == SecretRef.Source.ENVIRONMENT);
        check("resolved credentials are not the default", !resolved.defaultCredentials());
    }

    private static void retentionAdminCannotBeEnabled() {
        Properties properties = new Properties();
        properties.setProperty(FeatureRegistry.configKey(FeatureIds.RETENTION_ADMIN), "true");
        boolean rejected = false;
        try {
            FeatureRegistry.defaults(AppConfig.fromProperties(properties));
        } catch (ConfigException expected) {
            rejected = true;
        }
        check("retention administration cannot be enabled", rejected);

        FeatureRegistry registry = FeatureRegistry.defaults(AppConfig.fromProperties(new Properties()));
        check("item lookup is off by default", !registry.isEnabled(FeatureIds.ITEM_LOOKUP));
        check("retention viewer is on by default", registry.isEnabled(FeatureIds.RETENTION_VIEWER));
    }

    private static void poolNeverOvershoots() throws Exception {
        AtomicInteger live = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ResourceFactory<AutoCloseable> factory = new ResourceFactory<>() {
            @Override
            public AutoCloseable create() {
                int now = live.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                return live::decrementAndGet;
            }

            /**
             * This fixture's resource is a bare live-counter lambda with no state beyond existence, so
             * "healthy" has no finer answer than true and no vendor call would be reachable here.
             *
             * <p>Stated explicitly rather than inherited: a constant is only honest when the resource
             * genuinely has no stronger local state, and an inherited "non-null means healthy" default
             * was removed for exactly this reason. The rotation and retirement paths that a real health
             * probe drives are exercised by the suites that own a resource with a real health flag.
             */
            @Override
            public boolean isHealthy(AutoCloseable resource) {
                return resource != null;
            }
        };
        try (BoundedPool<AutoCloseable> pool = new BoundedPool<>("self-check", 2, Duration.ofMillis(250), factory)) {
            pool.initialize();
            try (var first = pool.borrow(); var second = pool.borrow()) {
                check("both resources are leased", first.value() != null && second.value() != null);
                check("live count equals pool size", live.get() == 2);
            }
            PoolMetrics metrics = pool.metrics();
            check("available returns to pool size", metrics.available() == 2);
            check("capacity never exceeds configured size", metrics.capacityInUse() <= metrics.configuredSize());
            check("peak live count stayed within the bound", peak.get() <= 2);
        }
    }

    private static void poolBorrowTimeoutIsBackpressure() throws Exception {
        AtomicInteger live = new AtomicInteger();
        try (BoundedPool<AutoCloseable> pool = new BoundedPool<>("self-check-timeout", 1,
                Duration.ofMillis(80), countingFactory(live))) {
            try (var held = pool.borrow()) {
                check("the single leased resource is usable", held.value() != null);
                boolean timedOut = false;
                try {
                    pool.borrow();
                } catch (java.util.concurrent.TimeoutException expected) {
                    timedOut = true;
                }
                check("exhausted pool fails with backpressure instead of creating another resource", timedOut);
                check("no extra resource was created", live.get() == 1);
            }
        }
    }

    /** A factory that tracks how many resources are currently alive. */
    private static ResourceFactory<AutoCloseable> countingFactory(AtomicInteger live) {
        return new ResourceFactory<>() {
            @Override
            public AutoCloseable create() {
                live.incrementAndGet();
                return live::decrementAndGet;
            }

            /** Same reasoning as {@link #poolNeverOvershoots()}: existence is this resource's only state. */
            @Override
            public boolean isHealthy(AutoCloseable resource) {
                return resource != null;
            }
        };
    }
}
