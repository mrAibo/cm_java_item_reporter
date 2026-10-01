package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.ConfigException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

/** Typed configuration parsing: defaults, ranges, malformed values and fail-fast lookups. */
public class AppConfigTest {

    private static AppConfig config(String... keyValuePairs) {
        Properties properties = new Properties();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            properties.setProperty(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return AppConfig.fromProperties(properties);
    }

    public void typedGettersReturnParsedValuesAndDefaults() {
        AppConfig empty = AppConfig.empty();
        Assert.assertEquals(8080, empty.getInt("web.port", 8080, 1, 65535), "int falls back to its default");
        Assert.assertEquals(3600L, empty.getLong("pool.age", 3600L, 0L, 86400L), "long falls back to its default");
        Assert.assertTrue(empty.getBoolean("feature.reports", true), "boolean falls back to its default");
        Assert.assertEquals("fallback", empty.get("missing.key", "fallback"), "string falls back to its default");

        AppConfig configured = config(
                "web.port", "9090",
                "pool.age", "120",
                "feature.reports", "false",
                "app.mode", "read-only");
        Assert.assertEquals(9090, configured.getInt("web.port", 8080, 1, 65535), "configured int wins");
        Assert.assertEquals(120L, configured.getLong("pool.age", 3600L, 0L, 86400L), "configured long wins");
        Assert.assertFalse(configured.getBoolean("feature.reports", true), "configured boolean wins");
        Assert.assertEquals("read-only", configured.get("app.mode", "x"), "configured string wins");

        Assert.assertEquals("127.0.0.1", empty.webBind(), "web.bind defaults to loopback");
        Assert.assertEquals(8, empty.webThreads(), "web.threads defaults to 8");
        Assert.assertTrue(empty.find("missing.key").isEmpty(), "find is empty for an absent key");
        Assert.assertTrue(configured.find("app.mode").isPresent(), "find is present for a configured key");
    }

    public void invalidIntegerIsRejected() {
        AppConfig broken = config("web.port", "not-a-number");
        ConfigException failure = Assert.assertThrows(ConfigException.class,
                () -> broken.getInt("web.port", 8080, 1, 65535), "a non-numeric int must fail");
        Assert.assertTrue(failure.getMessage().contains("web.port"), "message names the key: "
                + failure.getMessage());
        Assert.assertTrue(failure.getMessage().contains("not-a-number"), "message shows the value");

        Assert.assertThrows(ConfigException.class,
                () -> config("pool.age", "12x").getLong("pool.age", 1L, 0L, 100L), "a non-numeric long must fail");
    }

    public void integerOutsideItsRangeIsRejected() {
        ConfigException tooLarge = Assert.assertThrows(ConfigException.class,
                () -> config("web.port", "70000").getInt("web.port", 8080, 1, 65535), "port above the range fails");
        Assert.assertTrue(tooLarge.getMessage().contains("must be between"), "message explains the range: "
                + tooLarge.getMessage());
        Assert.assertThrows(ConfigException.class,
                () -> config("web.port", "0").getInt("web.port", 8080, 1, 65535), "port below the range fails");
        Assert.assertThrows(ConfigException.class,
                () -> config("web.threads", "999").getInt("web.threads", 8, 1, 256), "threads above the range fail");
        Assert.assertEquals(11L, config("x", "11").getLong("x", 5L, 10L, 20L), "an in-range long is accepted");
    }

    public void booleanParsingIsCaseInsensitiveAndRejectsOtherText() {
        Assert.assertTrue(config("flag", "TRUE").getBoolean("flag", false), "TRUE is a boolean");
        Assert.assertTrue(config("flag", "True").getBoolean("flag", false), "mixed case is accepted");
        Assert.assertTrue(config("flag", " true ").getBoolean("flag", false), "the value is trimmed");
        Assert.assertFalse(config("flag", "False").getBoolean("flag", true), "False is a boolean");
        ConfigException failure = Assert.assertThrows(ConfigException.class,
                () -> config("flag", "yes").getBoolean("flag", true), "'yes' is not a boolean here");
        Assert.assertTrue(failure.getMessage().contains("expected true or false"), "message explains the syntax: "
                + failure.getMessage());
    }

    public void blankValuesAreTreatedAsUnsetAndTrimmed() {
        AppConfig blank = config("web.port", "   ", "app.mode", "");
        Assert.assertEquals(8080, blank.getInt("web.port", 8080, 1, 65535), "a blank int is unset, not zero");
        Assert.assertEquals("read-only", blank.get("app.mode", "read-only"), "a blank string is unset");
        Assert.assertTrue(blank.find("app.mode").isEmpty(), "find reports a blank value as absent");

        AppConfig padded = config("app.mode", "  bootstrap-read-only  ");
        Assert.assertEquals("bootstrap-read-only", padded.get("app.mode", "x"), "values are trimmed");
        Assert.assertEquals("fallback", padded.get("absent", "  fallback  "), "the default is trimmed too");
    }

    public void requireFailsClosedWhenTheKeyIsMissing() {
        AppConfig configured = config("repository.id", "crm");
        Assert.assertEquals("crm", configured.require("repository.id"), "require returns a configured value");
        ConfigException failure = Assert.assertThrows(ConfigException.class,
                () -> AppConfig.empty().require("repository.id"), "require fails for a missing key");
        Assert.assertTrue(failure.getMessage().contains("Missing required configuration 'repository.id'"),
                "message is actionable: " + failure.getMessage());
        Assert.assertThrows(ConfigException.class,
                () -> config("repository.id", "  ").require("repository.id"), "a blank value is missing");
    }

    public void durationsParseUnitsAndEnforceTheirRange() {
        Duration min = Duration.ofMillis(1);
        Duration max = Duration.ofMinutes(10);
        Assert.assertEquals(Duration.ofMillis(500),
                config("d", "500ms").getDuration("d", Duration.ofSeconds(1), min, max), "ms suffix");
        Assert.assertEquals(Duration.ofSeconds(30),
                config("d", "30s").getDuration("d", Duration.ofSeconds(1), min, max), "s suffix");
        Assert.assertEquals(Duration.ofMinutes(5),
                config("d", "5m").getDuration("d", Duration.ofSeconds(1), min, max), "m suffix");
        Assert.assertEquals(Duration.ofMillis(1500),
                config("d", "1500").getDuration("d", Duration.ofSeconds(1), min, max), "a bare number is ms");
        Assert.assertEquals(Duration.ofHours(2),
                config("d", "2h").getDuration("d", Duration.ofSeconds(1), Duration.ZERO, Duration.ofDays(1)),
                "h suffix");
        Assert.assertEquals(Duration.ofDays(1),
                config("d", "1d").getDuration("d", Duration.ofSeconds(1), Duration.ZERO, Duration.ofDays(7)),
                "d suffix");
        Assert.assertEquals(Duration.ofSeconds(3),
                AppConfig.empty().getDuration("d", Duration.ofSeconds(3), min, max), "a missing duration is the default");
        ConfigException tooLong = Assert.assertThrows(ConfigException.class,
                () -> config("d", "11m").getDuration("d", Duration.ofSeconds(1), min, max), "out of range fails");
        Assert.assertTrue(tooLong.getMessage().contains("must be between"), "message explains the range");
    }

    public void malformedDurationsAreRejected() {
        Duration min = Duration.ZERO;
        Duration max = Duration.ofHours(24);
        Assert.assertThrows(ConfigException.class,
                () -> config("d", "abc").getDuration("d", Duration.ofSeconds(1), min, max), "garbage is rejected");
        Assert.assertThrows(ConfigException.class,
                () -> config("d", "1e3ms").getDuration("d", Duration.ofSeconds(1), min, max), "exponent is rejected");
        ConfigException negative = Assert.assertThrows(ConfigException.class,
                () -> config("d", "-5s").getDuration("d", Duration.ofSeconds(1), min, max), "negative is rejected");
        Assert.assertTrue(negative.getMessage().contains("d"), "message names the key: " + negative.getMessage());
        Assert.assertThrows(ConfigException.class,
                () -> config("d", "12ms").getDuration("d", Duration.ofSeconds(1), Duration.ofSeconds(1), max),
                "below the minimum fails");
    }

    public void keysReportsEveryConfiguredKey() {
        AppConfig config = config(
                "web.port", "9090",
                "web.bind", "127.0.0.1",
                "feature.reports", "false",
                "webprt", "8080",
                "typo.thing", "1");
        // Unknown-key detection is not an AppConfig concern: Main owns that rule, against an exact key
        // list, so that a typo such as "web.prt" is caught instead of passing as part of "web.".
        Assert.assertEquals(5, config.keys().size(), "keys() reports every configured key");
        Assert.assertTrue(config.keys().contains("webprt"), "keys() contains the unknown key as well");
        Assert.assertTrue(config.keys().contains("typo.thing"), "keys() contains the unknown key as well");
    }

    /**
     * Regression: {@code webPort()} accepts 0 for an operating-system-chosen ephemeral port.
     *
     * <p>Fails against the pre-fix code, whose accepted range started at 1, so {@code web.port=0} was
     * refused and an ephemeral bind was not expressible in configuration at all.
     */
    public void webPortAcceptsZeroForAnEphemeralBind() {
        Assert.assertEquals(8080, AppConfig.empty().webPort(), "the default port is unchanged");
        Assert.assertEquals(0, config("web.port", "0").webPort(),
                "0 is accepted and means an ephemeral bind chosen by the operating system");
        Assert.assertEquals(1, config("web.port", "1").webPort(), "the lowest fixed port is accepted");
        Assert.assertEquals(65535, config("web.port", "65535").webPort(), "the upper boundary is accepted");

        ConfigException negative = Assert.assertThrows(ConfigException.class,
                () -> config("web.port", "-1").webPort(), "a negative port is refused");
        Assert.assertTrue(negative.getMessage().contains("between 0 and 65535"),
                "the message states the accepted range: " + negative.getMessage());
        Assert.assertThrows(ConfigException.class, () -> config("web.port", "65536").webPort(),
                "a port above the range is refused");
        Assert.assertThrows(ConfigException.class, () -> config("web.port", "not-a-port").webPort(),
                "a non-numeric port is refused");
    }

    /**
     * Regression (mutation-coverage gap found by the reliability review): the range guard in
     * {@code getLong}, mirroring the already-pinned int path.
     *
     * <p>Fails against a mutant that keeps only the parse check: the out-of-range values are accepted.
     */
    public void longOutsideItsRangeIsRejected() {
        Assert.assertEquals(11L, config("x", "11").getLong("x", 5L, 10L, 20L), "an in-range long is accepted");
        Assert.assertEquals(20L, config("x", "20").getLong("x", 5L, 10L, 20L), "the maximum is accepted");
        Assert.assertEquals(5L, AppConfig.empty().getLong("x", 5L, 10L, 20L), "a missing long is the default");

        ConfigException tooLarge = Assert.assertThrows(ConfigException.class,
                () -> config("x", "30").getLong("x", 5L, 10L, 20L), "a long above the maximum is refused");
        Assert.assertTrue(tooLarge.getMessage().contains("between 10 and 20"),
                "the message states the accepted range: " + tooLarge.getMessage());
        Assert.assertTrue(tooLarge.getMessage().contains("30"),
                "the message shows the value: " + tooLarge.getMessage());
        Assert.assertThrows(ConfigException.class,
                () -> config("x", "9").getLong("x", 5L, 10L, 20L), "a long below the minimum is refused");
    }

    public void rawIsADefensiveCopyThatDoesNotChangeTheConfig() {
        AppConfig config = config("app.mode", "original");
        Properties copy = config.raw();
        copy.setProperty("app.mode", "mutated");
        copy.setProperty("injected.key", "value");
        Assert.assertEquals("original", config.get("app.mode", "x"), "mutating the copy does not touch the config");
        Assert.assertTrue(config.find("injected.key").isEmpty(), "new keys are not injected");
    }

    public void loadReadsAFileAndNamesItInDiagnostics() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-appconfig-");
        try {
            Path file = TestSupport.writeFile(dir.resolve("application.properties"),
                    "web.port=not-a-number\napp.mode=bootstrap-read-only\n");
            AppConfig config = AppConfig.load(file);
            Assert.assertEquals(file.toAbsolutePath(), config.sourcePath(), "sourcePath is the absolute file");
            Assert.assertEquals("bootstrap-read-only", config.get("app.mode", "x"), "values are read from the file");
            ConfigException failure = Assert.assertThrows(ConfigException.class,
                    () -> config.getInt("web.port", 8080, 1, 65535), "the bad value still fails");
            Assert.assertTrue(failure.getMessage().contains("application.properties"),
                    "the message names the file: " + failure.getMessage());

            Assert.assertThrows(IOException.class,
                    () -> AppConfig.load(dir.resolve("does-not-exist.properties")),
                    "loading a missing file fails");
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    public void configToStringNeverPrintsValues() {
        AppConfig config = config("web.auth.password", "SUPER-SECRET-CONFIG-VALUE");
        Assert.assertFalse(config.toString().contains("SUPER-SECRET-CONFIG-VALUE"),
                "AppConfig.toString only reports the key count: " + config);
    }
}
