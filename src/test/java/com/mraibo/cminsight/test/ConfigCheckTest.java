package com.mraibo.cminsight.test;

import com.mraibo.cminsight.app.ConfigCheck;
import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.AppPaths;
import com.mraibo.cminsight.config.ConfigException;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.config.WebAuthSettings;
import com.mraibo.cminsight.security.SecurityPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Goal 01A section E: the doctor surface and the runtime must make the SAME credential decision.
 *
 * <p>Before this correction {@code bin/doctor.sh} had its own copy of the rules and claimed that a
 * configured-but-missing {@code web.auth.password.env} falls back to {@code admin/admin} on loopback.
 * The runtime does the opposite: once a source is configured it is authoritative, and a missing value
 * fails closed. {@link ConfigCheck} now delegates to the very classes the runtime uses, so this test
 * asserts the parity directly: for every scenario the doctor's verdict must equal the runtime's, and a
 * refusal must be the runtime's own message, verbatim.
 */
public class ConfigCheckTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String NON_LOOPBACK = "0.0.0.0";

    private static AppConfig config(String... keyValues) {
        Properties properties = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            properties.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return AppConfig.fromProperties(properties);
    }

    /**
     * Exactly the sequence {@code Main} performs before it opens a socket, reduced to its decision.
     *
     * @return {@code null} when the runtime would start, otherwise the message it would print
     */
    private static String runtimeRefusal(AppConfig config, SecretResolver secrets) {
        try {
            WebAuthSettings auth = WebAuthSettings.resolve(config, secrets);
            boolean allowInsecureHttp = SecurityPolicy.allowInsecureHttp(config);
            SecurityPolicy.validateWebExposure(auth, config.webBind(), config.webPort(), allowInsecureHttp);
            return null;
        } catch (ConfigException | IllegalStateException e) {
            return e.getMessage();
        }
    }

    private record Scenario(String name, AppConfig config, SecretResolver secrets, boolean accepted) {
    }

    /**
     * Goal 01A (E): doctor and runtime agree on every credential/exposure scenario, and the doctor's
     * refusal text IS the runtime's refusal text.
     *
     * <p>Fails against the pre-Goal-01A doctor, which reported "accepted" with an {@code admin/admin}
     * fallback for {@code missingEnvPasswordOnLoopback} while the runtime refused to start.
     */
    public void theDoctorAndTheRuntimeAgreeOnEveryScenario() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-doctor-");
        try {
            Path secrets = Files.createDirectories(dir.resolve("secrets"));
            TestSupport.writeFile(secrets.resolve("web-user.txt"), "ops\n");
            TestSupport.writeFile(secrets.resolve("web-password.txt"), "File-Web-Secret-2205\n");
            Path outside = Files.createDirectories(dir.resolve("outside"));
            TestSupport.writeFile(outside.resolve("escaped.txt"), "Escaped-Secret-7712\n");

            // t11: containment is decided by the runtime's own two checks (lexical + real location). A
            // symbolic link is used where the platform allows it, otherwise a Windows junction, which
            // needs no privileges - and which is the shape a Bash `[ -r ... ]` probe gets wrong.
            String linkedName = null;
            if (TestSupport.createSymbolicLink(secrets.resolve("linked.txt"), outside.resolve("escaped.txt"))) {
                linkedName = "linked.txt";
            } else if (TestSupport.createWindowsJunction(secrets.resolve("link-escape"), outside)) {
                linkedName = "link-escape/escaped.txt";
            }

            Map<String, String> fullEnvironment = Map.of(
                    "CM_WEB_TEST_USER", "ops",
                    "CM_WEB_TEST_PASSWORD", "Correct-Horse-9942");
            Map<String, String> emptyEnvironment = Map.of();

            List<Scenario> scenarios = new ArrayList<>(List.of(
                    new Scenario("envConfiguredAndPresentOnLoopback",
                            config("web.bind", LOOPBACK, "web.auth.user.env", "CM_WEB_TEST_USER",
                                    "web.auth.password.env", "CM_WEB_TEST_PASSWORD"),
                            new SecretResolver(fullEnvironment, secrets), true),
                    new Scenario("missingEnvPasswordOnLoopback",
                            config("web.bind", LOOPBACK, "web.auth.user.env", "CM_WEB_TEST_USER",
                                    "web.auth.password.env", "CM_WEB_TEST_PASSWORD"),
                            new SecretResolver(emptyEnvironment, secrets), false),
                    new Scenario("missingEnvPasswordIsNotRescuedByAFile",
                            config("web.bind", LOOPBACK,
                                    "web.auth.password.env", "CM_WEB_TEST_PASSWORD",
                                    "web.auth.password.file", "web-password.txt"),
                            new SecretResolver(emptyEnvironment, secrets), false),
                    new Scenario("fileSourcePresentOnLoopback",
                            config("web.bind", LOOPBACK, "web.auth.user.file", "web-user.txt",
                                    "web.auth.password.file", "web-password.txt"),
                            new SecretResolver(emptyEnvironment, secrets), true),
                    new Scenario("fileSourceMissingOnLoopback",
                            config("web.bind", LOOPBACK, "web.auth.user.file", "web-user.txt",
                                    "web.auth.password.file", "absent-password.txt"),
                            new SecretResolver(emptyEnvironment, secrets), false),
                    new Scenario("noSourceOnLoopbackUsesTheDevelopmentDefault",
                            config("web.bind", LOOPBACK), new SecretResolver(emptyEnvironment, secrets), true),
                    new Scenario("noSourceOnANonLoopbackBindIsRefused",
                            config("web.bind", NON_LOOPBACK), new SecretResolver(emptyEnvironment, secrets), false),
                    new Scenario("realCredentialsOnANonLoopbackBindWithoutTheOverride",
                            config("web.bind", NON_LOOPBACK, "web.auth.user", "ops",
                                    "web.auth.password", "Correct-Horse-9942"),
                            new SecretResolver(emptyEnvironment, secrets), false),
                    new Scenario("realCredentialsOnANonLoopbackBindWithTheOverride",
                            config("web.bind", NON_LOOPBACK, "web.auth.user", "ops",
                                    "web.auth.password", "Correct-Horse-9942",
                                    SecurityPolicy.KEY_ALLOW_INSECURE_HTTP, "true"),
                            new SecretResolver(emptyEnvironment, secrets), true),
                    new Scenario("theOverrideDoesNotPermitTheDevelopmentDefault",
                            config("web.bind", NON_LOOPBACK, SecurityPolicy.KEY_ALLOW_INSECURE_HTTP, "true"),
                            new SecretResolver(emptyEnvironment, secrets), false),
                    // t9 finding F-1 on the doctor side: a blank configured password is treated as
                    // unset and silently becomes the published development password. web-security-
                    // engineer's SecurityPolicyTest is authoritative for the rule; this pins that the
                    // DOCTOR reaches the same verdict as the runtime for it. Deliberately verdict-level
                    // (accepted/refused + exit code + the runtime's own message), never a display line.
                    new Scenario("blankPasswordFallsBackToTheDevelopmentDefaultAndIsRefusedEvenWithTheOverride",
                            config("web.bind", NON_LOOPBACK, "web.auth.user", "operator",
                                    "web.auth.password", "", SecurityPolicy.KEY_ALLOW_INSECURE_HTTP, "true"),
                            new SecretResolver(emptyEnvironment, secrets), false),
                    new Scenario("aTypoInTheOverrideIsNotASilentOff",
                            config("web.bind", LOOPBACK, SecurityPolicy.KEY_ALLOW_INSECURE_HTTP, "yes"),
                            new SecretResolver(emptyEnvironment, secrets), false),
                    // t11 containment, WEB credential: a .file reference that leaves the secrets directory
                    // - by ".." or through a link with an inside-looking name - is refused by the runtime,
                    // so the doctor must refuse it too. A plain file inside stays accepted (no false
                    // negative). Verdict + exit code only; the wording may still be tuned.
                    new Scenario("webSecretFileEscapesWithDotDotIsRefusedByBoth",
                            config("web.bind", LOOPBACK, "web.auth.user", "ops",
                                    "web.auth.password.file", "../outside/escaped.txt"),
                            new SecretResolver(emptyEnvironment, secrets), false),
                    new Scenario("webSecretFileInsideSecretsDirectoryIsAcceptedByBoth",
                            config("web.bind", LOOPBACK, "web.auth.user", "ops",
                                    "web.auth.password.file", "web-password.txt"),
                            new SecretResolver(emptyEnvironment, secrets), true)));

            if (linkedName != null) {
                scenarios.add(new Scenario("webSecretFileReachedThroughALinkIsRefusedByBoth",
                        config("web.bind", LOOPBACK, "web.auth.user", "ops",
                                "web.auth.password.file", linkedName),
                        new SecretResolver(emptyEnvironment, secrets), false));
            }

            for (Scenario scenario : scenarios) {
                ConfigCheck.Report report = ConfigCheck.validate(scenario.config(), scenario.secrets());
                String runtimeRefusal = runtimeRefusal(scenario.config(), scenario.secrets());

                Assert.assertEquals(scenario.accepted(), report.accepted(),
                        scenario.name() + ": the doctor verdict must be " + scenario.accepted()
                                + " but the report said " + report.errors());
                Assert.assertEquals(scenario.accepted(), runtimeRefusal == null,
                        scenario.name() + ": doctor and runtime disagree (doctor accepted="
                                + report.accepted() + ", runtime refusal=" + runtimeRefusal + ")");
                Assert.assertEquals(report.exitCode(), report.accepted() ? ConfigCheck.EXIT_OK
                                : ConfigCheck.EXIT_REFUSED,
                        scenario.name() + ": the exit code matches the verdict");
                if (!scenario.accepted()) {
                    Assert.assertEquals(1, report.errors().size(),
                            scenario.name() + ": exactly one problem, so parity can be exact: " + report.errors());
                    Assert.assertEquals(runtimeRefusal, report.errors().get(0),
                            scenario.name() + ": the doctor prints the runtime's own refusal text");
                }
            }

            // t11 containment at the doctor ENTRY POINT (ConfigCheck.run): the escaping shapes are
            // refused (exit 1, RESULT refused) and the plain one is accepted (exit 0). The runtime
            // reaches the identical verdict through WebAuthSettings.resolve/SecretResolver, which the
            // loop above already proved for the same scenario names.
            List<String> refusedPasswordFiles = new ArrayList<>();
            refusedPasswordFiles.add("../outside/escaped.txt");
            if (linkedName != null) {
                refusedPasswordFiles.add(linkedName);
            }
            for (String passwordFile : refusedPasswordFiles) {
                Path webConfig = TestSupport.writeFile(
                        dir.resolve("web-refused-" + Integer.toHexString(passwordFile.hashCode()) + ".properties"),
                        "web.bind=" + LOOPBACK + "\nweb.auth.user=ops\nweb.auth.password.file=" + passwordFile
                                + "\nsecrets.dir=" + propertiesPath(secrets) + "\n");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int exit = ConfigCheck.run(new String[]{"--config", webConfig.toString()},
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
                String text = out.toString(StandardCharsets.UTF_8);
                Assert.assertEquals(ConfigCheck.EXIT_REFUSED, exit,
                        "the doctor refuses the escaping .file reference '" + passwordFile + "': " + text);
                Assert.assertTrue(text.contains("RESULT: refused"),
                        "the result line reports the refusal for '" + passwordFile + "': " + text);
                Assert.assertFalse(text.contains("Escaped-Secret-7712"),
                        "the refused file's value is never printed: " + text);
            }

            Path plainConfig = TestSupport.writeFile(dir.resolve("web-plain.properties"),
                    "web.bind=" + LOOPBACK + "\nweb.auth.user=ops\nweb.auth.password.file=web-password.txt"
                            + "\nsecrets.dir=" + propertiesPath(secrets) + "\n");
            ByteArrayOutputStream plainOut = new ByteArrayOutputStream();
            int plainExit = ConfigCheck.run(new String[]{"--config", plainConfig.toString()},
                    new PrintStream(plainOut, true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            Assert.assertEquals(ConfigCheck.EXIT_OK, plainExit,
                    "a file inside the secrets directory is accepted, no false negative: " + plainOut);
            Assert.assertTrue(plainOut.toString(StandardCharsets.UTF_8).contains("RESULT: accepted"),
                    "the result line reports the acceptance: " + plainOut);

            // t11 containment for REPOSITORY profiles, through the same Java entry point the doctor now
            // uses. The verdict levels differ from the web case on purpose: Main resolves repository
            // credentials only when a repository is ACTIVATED, so an unresolvable one is a WARN - the
            // doctor must not refuse, but it must also never call such a credential OK.
            AppPaths profilePaths = AppPaths.of(dir, AppPaths.HomeSource.SYSTEM_PROPERTY);
            SecretResolver profileSecrets = new SecretResolver(emptyEnvironment, secrets);

            Path escapeProfiles = Files.createDirectories(dir.resolve("profiles-escape"));
            TestSupport.writeFile(escapeProfiles.resolve("crm.properties"),
                    profileWithPasswordFile("../outside/escaped.txt"));
            List<ConfigCheck.Finding> escaping = ConfigCheck.inspectProfiles(profilePaths,
                    config("profiles.dir", escapeProfiles.toString()), profileSecrets);
            Assert.assertEquals(1, escaping.size(),
                    "a profile leaving the secrets directory is refused while loading, as Main does: "
                            + describeFindings(escaping));
            Assert.assertEquals(ConfigCheck.Level.ERROR, escaping.get(0).level(),
                    "the loader's refusal is an ERROR: " + describeFindings(escaping));
            Assert.assertFalse(escaping.get(0).message().contains("Escaped-Secret-7712"),
                    "the refusal never carries a value: " + describeFindings(escaping));

            Path plainProfiles = Files.createDirectories(dir.resolve("profiles-plain"));
            TestSupport.writeFile(plainProfiles.resolve("crm.properties"),
                    profileWithPasswordFile("web-password.txt"));
            List<ConfigCheck.Finding> plainProfile = ConfigCheck.inspectProfiles(profilePaths,
                    config("profiles.dir", plainProfiles.toString()), profileSecrets);
            Assert.assertTrue(plainProfile.stream().noneMatch(finding -> finding.level() != ConfigCheck.Level.OK),
                    "a profile whose credentials all sit inside the secrets directory is fully OK: "
                            + describeFindings(plainProfile));
            Assert.assertTrue(plainProfile.stream()
                            .anyMatch(finding -> finding.message().contains("repository.cm.password")),
                    "every credential of the profile is listed: " + describeFindings(plainProfile));

            if (linkedName != null) {
                Path linkedProfiles = Files.createDirectories(dir.resolve("profiles-linked"));
                TestSupport.writeFile(linkedProfiles.resolve("crm.properties"),
                        profileWithPasswordFile(linkedName));
                List<ConfigCheck.Finding> linkedProfile = ConfigCheck.inspectProfiles(profilePaths,
                        config("profiles.dir", linkedProfiles.toString()), profileSecrets);
                Assert.assertTrue(linkedProfile.stream().noneMatch(finding -> finding.level() == ConfigCheck.Level.ERROR),
                        "the runtime still starts, so the doctor must not escalate to ERROR: "
                                + describeFindings(linkedProfile));
                Assert.assertTrue(linkedProfile.stream().anyMatch(finding ->
                                finding.level() == ConfigCheck.Level.WARN && finding.message().contains("REFUSED")),
                        "the refused link-shaped credential is reported as refused: "
                                + describeFindings(linkedProfile));
                Assert.assertTrue(linkedProfile.stream().noneMatch(finding ->
                                finding.level() == ConfigCheck.Level.OK
                                        && finding.message().contains("repository.cm.password")),
                        "doctor must never call a refused reference OK: " + describeFindings(linkedProfile));
                Assert.assertTrue(linkedProfile.stream()
                                .noneMatch(finding -> finding.message().contains("Escaped-Secret-7712")),
                        "no finding ever carries the value: " + describeFindings(linkedProfile));
            }
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    /** A valid profile whose CM password comes from {@code fileName} below the secrets directory. */
    private static String profileWithPasswordFile(String fileName) {
        return """
                repository.id=crm
                repository.name=CRM
                repository.ssid=ICMCRM
                repository.db.vendor=db2
                repository.jdbc.url=jdbc:db2://db.example:50000/CRM
                repository.cm.user.file=web-user.txt
                repository.cm.password.file=%s
                repository.jdbc.user.file=web-user.txt
                repository.jdbc.password.file=web-password.txt
                """.formatted(fileName);
    }

    /** A path in a {@code .properties} value: backslashes would be escape characters there. */
    private static String propertiesPath(Path path) {
        return path.toString().replace("\\", "/");
    }

    /** Finding verdicts as one safe, value-free line, for assertion messages. */
    private static String describeFindings(List<ConfigCheck.Finding> findings) {
        return findings.stream().map(finding -> finding.level() + ": " + finding.message()).toList().toString();
    }

    /**
     * Goal 01A (E): the specific doctor/runtime divergence - a configured but missing environment
     * variable - is refused even on loopback, and the message says why the fallback is not applied.
     *
     * <p>Fails against the pre-Goal-01A doctor, which printed that the loopback {@code admin/admin}
     * development default applies.
     */
    public void aMissingConfiguredEnvironmentVariableIsRefusedEvenOnLoopback() {
        AppConfig config = config("web.bind", "127.0.0.1", "web.auth.password.env", "CM_WEB_TEST_PASSWORD");
        ConfigCheck.Report report = ConfigCheck.validate(config, new SecretResolver(Map.of(), null));

        Assert.assertFalse(report.accepted(), "a configured but missing source is refused on loopback too");
        String error = report.errors().get(0);
        Assert.assertTrue(error.contains("web.auth.password"), "the refusal names the key: " + error);
        Assert.assertTrue(error.contains("CM_WEB_TEST_PASSWORD"), "the refusal names the variable: " + error);
        Assert.assertTrue(error.contains("Refusing to fall back"),
                "the refusal explains that the development default is NOT applied: " + error);
        Assert.assertNull(report.auth(), "no credentials are published for a refused configuration");
        Assert.assertEquals(error, runtimeRefusal(config, new SecretResolver(Map.of(), null)),
                "the runtime refuses with exactly the same text");
    }

    /**
     * Goal 01A (E): no source at all on loopback keeps the documented development fallback, with the
     * admin/admin warning - the divergence is narrow and deliberate.
     */
    public void noSourceOnLoopbackKeepsTheDocumentedDevelopmentFallback() {
        for (String bind : List.of("127.0.0.1", "127.5.5.5", "::1", "localhost")) {
            AppConfig config = config("web.bind", bind);
            ConfigCheck.Report report = ConfigCheck.validate(config, new SecretResolver(Map.of(), null));
            Assert.assertTrue(report.accepted(), bind + ": the loopback development default is accepted");
            Assert.assertNotNull(report.auth(), bind + ": credentials are published");
            Assert.assertTrue(report.auth().defaultCredentials(), bind + ": the default pair is in use");
            Assert.assertEquals("admin", report.auth().user(), bind + ": the development user is admin");
            Assert.assertTrue(report.warnings().stream()
                            .anyMatch(warning -> warning.contains("admin/admin")),
                    bind + ": the development credentials are warned about: " + report.warnings());
            Assert.assertNull(runtimeRefusal(config, new SecretResolver(Map.of(), null)),
                    bind + ": the runtime starts the same configuration");
        }
    }

    /** Goal 01A (E + F): an accepted insecure exposure is reported with the shared marker. */
    public void anAcceptedInsecureExposureIsWarnedAbout() {
        AppConfig config = config("web.bind", "0.0.0.0", "web.auth.user", "ops",
                "web.auth.password", "Correct-Horse-9942",
                SecurityPolicy.KEY_ALLOW_INSECURE_HTTP, "true");
        ConfigCheck.Report report = ConfigCheck.validate(config, new SecretResolver(Map.of(), null));

        Assert.assertTrue(report.accepted(), "the explicit override accepts the plain-HTTP bind");
        Assert.assertTrue(report.warnings().stream()
                        .anyMatch(warning -> warning.startsWith(SecurityPolicy.INSECURE_HTTP_MARKER)),
                "the accepted insecure exposure is reported: " + report.warnings());
        Assert.assertTrue(report.warnings().stream().noneMatch(warning -> warning.contains("Correct-Horse-9942")),
                "warnings never contain a credential: " + report.warnings());
    }

    /**
     * Goal 01A (E): {@code ConfigCheck.run} prints the documented prefix contract and a resolvable
     * result line, and never prints a credential value - not even a file-backed one.
     */
    public void runPrintsTheResultContractAndNeverACredential() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-configcheck-run-");
        try {
            Path secrets = Files.createDirectories(dir.resolve("secrets"));
            TestSupport.writeFile(secrets.resolve("web-password.txt"), "File-Web-Secret-2205\n");
            Path acceptedFile = TestSupport.writeFile(dir.resolve("accepted.properties"), """
                    web.bind=127.0.0.1
                    web.port=8080
                    secrets.dir=%s
                    web.auth.user=ops
                    web.auth.password.file=web-password.txt
                    """.formatted(secrets.toString().replace("\\", "/")));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = ConfigCheck.run(new String[]{"--config", acceptedFile.toString(), "--validate-config"},
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8));
            String text = out.toString(StandardCharsets.UTF_8);

            Assert.assertEquals(ConfigCheck.EXIT_OK, exit, "an accepted configuration exits 0: " + err);
            Assert.assertTrue(text.contains("RESULT: accepted"),
                    "the result line reports the verdict: " + text);
            Assert.assertTrue(text.contains("OK:    home = "), "the application home is reported: " + text);
            Assert.assertTrue(text.contains("OK:    path profiles.dir="), "the resolved paths are listed: " + text);
            Assert.assertTrue(text.contains("web password = <redacted>"),
                    "the password is reported as a redacted source: " + text);
            Assert.assertTrue(text.contains("secret file web-password.txt"),
                    "the doctor names the source of the password: " + text);
            Assert.assertFalse(text.contains("File-Web-Secret-2205"),
                    "no credential value is ever printed: " + text);

            Path refusedFile = TestSupport.writeFile(dir.resolve("refused.properties"), """
                    web.bind=0.0.0.0
                    web.port=8080
                    """);
            ByteArrayOutputStream refusedOut = new ByteArrayOutputStream();
            int refusedExit = ConfigCheck.run(new String[]{"--config", refusedFile.toString()},
                    new PrintStream(refusedOut, true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            String refusedText = refusedOut.toString(StandardCharsets.UTF_8);
            Assert.assertEquals(ConfigCheck.EXIT_REFUSED, refusedExit, "a refused configuration exits 1");
            Assert.assertTrue(refusedText.contains("RESULT: refused"), "the result line reports the refusal");
            Assert.assertTrue(refusedText.contains("ERROR: "), "the reasons are ERROR lines: " + refusedText);
            Assert.assertTrue(refusedText.contains("admin/admin"),
                    "the refusal is the runtime's admin/admin refusal: " + refusedText);

            // An INLINE web credential is legal (it is the repository credentials that must never be
            // inline); the report may name the source but must never print the value.
            Path inlineFile = TestSupport.writeFile(dir.resolve("inline.properties"), """
                    web.bind=127.0.0.1
                    web.auth.user=ops
                    web.auth.password=Inline-Web-Secret-6613
                    """);
            ByteArrayOutputStream inlineOut = new ByteArrayOutputStream();
            int inlineExit = ConfigCheck.run(new String[]{"--config", inlineFile.toString()},
                    new PrintStream(inlineOut, true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            String inlineText = inlineOut.toString(StandardCharsets.UTF_8);
            Assert.assertEquals(ConfigCheck.EXIT_OK, inlineExit,
                    "an inline web credential is accepted on loopback: " + inlineText);
            Assert.assertTrue(inlineText.contains("web password = <redacted>"),
                    "the password is reported as redacted: " + inlineText);
            Assert.assertTrue(inlineText.contains("inline configuration value"),
                    "the doctor names the inline source: " + inlineText);
            Assert.assertFalse(inlineText.contains("Inline-Web-Secret-6613"),
                    "a configured password VALUE never appears in the report: " + inlineText);
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    /** Goal 01A (E): usage and unreadable-input paths keep their own, distinguishable exit codes. */
    public void runDistinguishesUsageAndUnreadableInput() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-configcheck-usage-");
        try {
            ByteArrayOutputStream usageErr = new ByteArrayOutputStream();
            int usageExit = ConfigCheck.run(new String[]{"--nonsense"},
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    new PrintStream(usageErr, true, StandardCharsets.UTF_8));
            Assert.assertEquals(ConfigCheck.EXIT_USAGE, usageExit, "an unknown argument is a usage error");
            Assert.assertTrue(usageErr.toString(StandardCharsets.UTF_8).contains("unknown argument"),
                    "the usage error explains itself: " + usageErr);

            ByteArrayOutputStream missingOut = new ByteArrayOutputStream();
            int missingExit = ConfigCheck.run(
                    new String[]{"--config", dir.resolve("absent.properties").toString()},
                    new PrintStream(missingOut, true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            Assert.assertEquals(ConfigCheck.EXIT_UNREADABLE, missingExit,
                    "a missing configuration file is an unreadable-input error");
            Assert.assertTrue(missingOut.toString(StandardCharsets.UTF_8).contains("RESULT: configuration unreadable"),
                    "the result line says the configuration could not be read: " + missingOut);
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }
}
