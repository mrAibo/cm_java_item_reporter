package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.ConfigException;
import com.mraibo.cminsight.config.SecretRef;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.config.WebAuthSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Secret indirection: environment, file, inline, defaults, fail-closed resolution and redaction. */
public class SecretResolverTest {

    public void environmentVariableIsResolved() {
        SecretResolver resolver = new SecretResolver(Map.of("CM_TEST_PASSWORD", "env-secret-1177"), null);
        SecretRef ref = resolver.classify("CM_TEST_PASSWORD", null, "inline-should-not-win", "web.auth.password");

        Assert.assertEquals(SecretRef.Source.ENVIRONMENT, ref.source(), "the environment is the first source");
        Assert.assertTrue(ref.resolved(), "the ref is resolved");
        Assert.assertEquals("CM_TEST_PASSWORD", ref.locator(), "the locator is the variable name, not the value");
        Assert.assertEquals("env-secret-1177", resolver.resolve(ref), "the value is the environment value");
        Assert.assertTrue(ref.describe().contains("environment variable CM_TEST_PASSWORD"),
                "describe names the variable: " + ref.describe());
    }

    public void secretFileIsResolvedBelowTheSecretsDirectory() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-secrets-");
        try {
            Path secrets = dir.resolve("secrets");
            TestSupport.writeFile(secrets.resolve("cm-password.txt"), "\n  file-secret-4242  \nsecond-line\n");
            SecretResolver resolver = new SecretResolver(Map.of(), secrets);

            SecretRef ref = resolver.classify(null, "cm-password.txt", "inline-should-not-win", "web.auth.password");
            Assert.assertEquals(SecretRef.Source.FILE, ref.source(), "a configured file is used after the environment");
            Assert.assertEquals("file-secret-4242", resolver.resolve(ref), "the first non-blank line is trimmed");
            Assert.assertEquals("cm-password.txt", ref.locator(), "the locator is the file name");
            Assert.assertTrue(ref.describe().contains("secret file cm-password.txt"),
                    "describe names the file: " + ref.describe());

            Path blank = secrets.resolve("blank.txt");
            TestSupport.writeFile(blank, "\n   \n");
            SecretRef empty = resolver.classify(null, "blank.txt", null, "web.auth.password");
            Assert.assertEquals(SecretRef.Source.MISSING, empty.source(), "a file with no value is missing");
            Assert.assertTrue(resolver.warnings().stream().anyMatch(w -> w.contains("contains no usable value")),
                    "the empty file is reported: " + resolver.warnings());
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    public void inlineValueIsTheLastConfiguredSourceAndIsWarnedAbout() {
        SecretResolver resolver = new SecretResolver(Map.of(), null);
        SecretRef ref = resolver.classify(null, null, "inline-secret-5150", "web.auth.password");

        Assert.assertEquals(SecretRef.Source.INLINE, ref.source(), "inline is used when nothing else is configured");
        Assert.assertEquals("inline-secret-5150", resolver.resolve(ref), "the inline value is available");
        Assert.assertTrue(resolver.warnings().stream().anyMatch(w -> w.contains("stored inline")),
                "an inline secret produces a warning: " + resolver.warnings());

        SecretRef unconfigured = resolver.classify(null, null, null, "web.auth.password");
        Assert.assertEquals(SecretRef.Source.MISSING, unconfigured.source(), "nothing configured is MISSING");
        Assert.assertFalse(unconfigured.resolved(), "a MISSING ref is not resolved");
        Assert.assertTrue(unconfigured.describe().contains("not configured"), "describe explains it: "
                + unconfigured.describe());
    }

    public void configuredButMissingSourceFailsClosedInsteadOfDowngrading() {
        SecretResolver resolver = new SecretResolver(Map.of(), null);

        SecretRef missingEnv = resolver.classify("CM_ABSENT_PASSWORD", null, "INLINE-FALLBACK-8891", "web.auth.password");
        Assert.assertEquals(SecretRef.Source.MISSING, missingEnv.source(),
                "a configured but unset environment variable does not fall back to another source");
        Assert.assertTrue(resolver.resolve(missingEnv) == null, "no value is produced");
        Assert.assertTrue(missingEnv.locator().contains("CM_ABSENT_PASSWORD"),
                "the locator names the missing variable: " + missingEnv.locator());
        Assert.assertFalse(missingEnv.locator().contains("INLINE-FALLBACK-8891"),
                "the locator never carries a value");

        SecretRef missingFile = resolver.classify(null, "absent-secret.txt", "INLINE-FALLBACK-8891",
                "web.auth.password");
        Assert.assertEquals(SecretRef.Source.MISSING, missingFile.source(),
                "a configured but unreadable file does not fall back either");
        Assert.assertTrue(resolver.resolve(missingFile) == null, "no value is produced for a missing file");

        Properties properties = new Properties();
        properties.setProperty("web.auth.user", "ops");
        properties.setProperty("web.auth.password.env", "CM_ABSENT_PASSWORD");
        properties.setProperty("web.auth.password", "INLINE-FALLBACK-8891");
        ConfigException passwordFailure = Assert.assertThrows(ConfigException.class,
                () -> WebAuthSettings.resolve(AppConfig.fromProperties(properties), resolver),
                "an explicitly configured but unresolvable password source must fail closed");
        Assert.assertTrue(passwordFailure.getMessage().contains("web.auth.password"),
                "the message names the field: " + passwordFailure.getMessage());
        Assert.assertTrue(passwordFailure.getMessage().contains("CM_ABSENT_PASSWORD"),
                "the message names the environment variable that is not set: " + passwordFailure.getMessage());
        Assert.assertTrue(passwordFailure.getMessage().contains("is not set"),
                "the message names the reason: " + passwordFailure.getMessage());
        Assert.assertFalse(passwordFailure.getMessage().contains("INLINE-FALLBACK-8891"),
                "the message never echoes a configured value: " + passwordFailure.getMessage());

        Properties userProperties = new Properties();
        userProperties.setProperty("web.auth.user.env", "CM_ABSENT_USER");
        ConfigException userFailure = Assert.assertThrows(ConfigException.class,
                () -> WebAuthSettings.resolve(AppConfig.fromProperties(userProperties), resolver),
                "an explicitly configured but unresolvable user source must fail closed too");
        Assert.assertTrue(userFailure.getMessage().contains("web.auth.user")
                        && userFailure.getMessage().contains("CM_ABSENT_USER"),
                "the user failure names the field and the variable: " + userFailure.getMessage());
    }

    /**
     * Regression (t5 F2): the development default applies ONLY when nothing at all was configured.
     *
     * <p>Fails against the pre-fix code, which silently substituted admin/admin whenever a configured
     * source could not be resolved (the template ships {@code web.auth.password.env=...}, so copying
     * the template without exporting the variable produced a public admin/admin console).
     */
    public void developmentDefaultsApplyOnlyWhenNothingIsConfigured() {
        SecretResolver resolver = new SecretResolver(Map.of(), null);

        WebAuthSettings defaults = WebAuthSettings.resolve(AppConfig.empty(), resolver);
        Assert.assertEquals(WebAuthSettings.DEFAULT_USER, defaults.user(), "the default user applies");
        Assert.assertEquals(WebAuthSettings.DEFAULT_PASSWORD, defaults.password(), "the default password applies");
        Assert.assertTrue(defaults.defaultCredentials(), "the pair is flagged as the development default");
        Assert.assertEquals(SecretRef.Source.DEFAULT, defaults.passwordSource().source(),
                "the password source is reported as the default, not as a resolved secret");
        Assert.assertTrue(defaults.usingDevelopmentDefaults(), "the settings admit they use a default");
        Assert.assertTrue(defaults.warnings().stream().anyMatch(w -> w.contains("admin/admin")),
                "using the default is called out: " + defaults.warnings());

        // A partially configured pair is fine as long as the other field was never configured at all.
        Properties partial = new Properties();
        partial.setProperty("web.auth.user", "ops");
        WebAuthSettings partialSettings = WebAuthSettings.resolve(AppConfig.fromProperties(partial), resolver);
        Assert.assertEquals("ops", partialSettings.user(), "the configured user is used");
        Assert.assertEquals(WebAuthSettings.DEFAULT_PASSWORD, partialSettings.password(),
                "the unconfigured password still falls back to the flagged default");
        Assert.assertEquals(SecretRef.Source.DEFAULT, partialSettings.passwordSource().source(),
                "an unconfigured field is reported as the default");
        Assert.assertFalse(partialSettings.defaultCredentials(),
                "a configured user means the development pair is not in use");
    }

    public void pathTraversalOutsideTheSecretsDirectoryIsRefused() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-secrets-escape-");
        try {
            Path secrets = dir.resolve("secrets");
            TestSupport.writeFile(secrets.resolve("inside.txt"), "inside-1199\n");
            TestSupport.writeFile(dir.resolve("outside.txt"), "outside-7788\n");
            SecretResolver resolver = new SecretResolver(Map.of(), secrets);

            SecretRef escaped = resolver.classify(null, "../outside.txt", null, "web.auth.password");
            Assert.assertEquals(SecretRef.Source.MISSING, escaped.source(), "an escaping name is refused");
            Assert.assertTrue(resolver.resolve(escaped) == null, "the outside file is never read");
            Assert.assertTrue(resolver.warnings().stream().anyMatch(w -> w.contains("Refusing secret file")),
                    "the refusal is reported: " + resolver.warnings());

            SecretRef inside = resolver.classify(null, "inside.txt", null, "web.auth.password");
            Assert.assertEquals("inside-1199", resolver.resolve(inside), "a file inside the directory still works");
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    public void missingSecretsDirectoryProducesNoValue() {
        SecretResolver resolver = new SecretResolver(Map.of(), null);
        SecretRef ref = resolver.classify(null, "cm-password.txt", null, "web.auth.password");
        Assert.assertEquals(SecretRef.Source.MISSING, ref.source(), "without a directory a file cannot be read");
        Assert.assertTrue(ref.locator().contains("no secrets directory configured"),
                "the locator explains why: " + ref.locator());
    }

    public void developmentDefaultsAreFlaggedAndWarnedAbout() {
        SecretResolver resolver = new SecretResolver(Map.of(), null);
        SecretRef ref = resolver.defaults("web.auth.password", WebAuthSettings.DEFAULT_PASSWORD);
        Assert.assertEquals(SecretRef.Source.DEFAULT, ref.source(), "defaults are their own source");
        Assert.assertTrue(ref.resolved(), "a default is usable");
        Assert.assertEquals("built-in development default", ref.describe(), "describe names the default");
        Assert.assertTrue(resolver.warnings().stream().anyMatch(w -> w.contains("built-in development default")),
                "applying a default warns: " + resolver.warnings());

        WebAuthSettings settings = WebAuthSettings.resolve(AppConfig.empty(), resolver);
        Assert.assertEquals(WebAuthSettings.DEFAULT_USER, settings.user(), "the default user is admin");
        Assert.assertEquals(WebAuthSettings.DEFAULT_PASSWORD, settings.password(), "the default password is admin");
        Assert.assertTrue(settings.defaultCredentials(), "the default pair is flagged");
        Assert.assertTrue(settings.warnings().stream().anyMatch(w -> w.contains("admin/admin")),
                "the default pair is called out: " + settings.warnings());
    }

    public void secretReferencesAndSettingsNeverPrintTheValue() {
        SecretResolver resolver = new SecretResolver(Map.of(), null);
        SecretRef ref = resolver.classify(null, null, "RAW-SECRET-9987", "web.auth.password");
        Assert.assertEquals("RAW-SECRET-9987", resolver.resolve(ref), "the value is still resolvable internally");
        Assert.assertFalse(ref.toString().contains("RAW-SECRET-9987"), "SecretRef.toString is redacted: " + ref);
        Assert.assertFalse(ref.describe().contains("RAW-SECRET-9987"), "SecretRef.describe is redacted: " + ref);
        Assert.assertTrue(ref.toString().contains("inline configuration value"),
                "the source is still described: " + ref);

        WebAuthSettings settings = TestSupport.credentials("ops", "PLAIN-TEXT-HUNTER-2");
        Assert.assertEquals("PLAIN-TEXT-HUNTER-2", settings.password(), "the password is available to the caller");
        Assert.assertFalse(settings.toString().contains("PLAIN-TEXT-HUNTER-2"),
                "WebAuthSettings.toString is redacted: " + settings);
        Assert.assertTrue(settings.toString().contains("<redacted"), "the redaction is visible: " + settings);
        Assert.assertFalse(String.join("|", settings.warnings()).contains("PLAIN-TEXT-HUNTER-2"),
                "warnings never carry the value: " + settings.warnings());
        Assert.assertFalse(settings.defaultCredentials(), "explicit credentials are not the default pair");
        Assert.assertFalse(settings.usingDevelopmentDefaults(), "explicit credentials are not defaults");
        Assert.assertEquals(SecretRef.Source.INLINE, settings.userSource().source(), "the user source is reported");
        Assert.assertTrue(settings.warnings().stream().anyMatch(w -> w.contains("stored inline")),
                "inline credentials are warned about: " + settings.warnings());
    }

    public void secretsFileListIsNotEmptyAfterEachResolutionKind() {
        SecretResolver resolver = new SecretResolver(Map.of("CM_TEST_USER", "ops"), null);
        List<String> before = resolver.warnings();
        Assert.assertTrue(before.isEmpty(), "a fresh resolver has no warnings");
        resolver.classify("CM_TEST_USER", null, null, "web.auth.user");
        Assert.assertEquals(0, resolver.warnings().size(), "a clean environment lookup warns about nothing");
    }
}
