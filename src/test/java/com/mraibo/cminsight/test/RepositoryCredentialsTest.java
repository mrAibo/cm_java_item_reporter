package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.ConfigException;
import com.mraibo.cminsight.config.DatabaseVendor;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.RepositoryProfileLoader;
import com.mraibo.cminsight.config.SecretRef;
import com.mraibo.cminsight.config.SecretResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Goal 01A section C: a repository profile declares WHERE each of its four credentials comes from -
 * {@code .env} or {@code .file} - and never the value itself.
 *
 * <p>Before this correction the loader accepted a {@code .file} key and then dropped it, because
 * {@code RepositoryProfile} only kept environment-variable names: a file-backed credential was
 * accepted at load time and silently lost. These tests pin the corrected model:
 *
 * <ul>
 *   <li>a {@code .file} reference survives loading and stays resolvable;</li>
 *   <li>the documented precedence is {@code .env} first, and the winner is authoritative - a missing
 *       environment variable does NOT fall back to the file;</li>
 *   <li>a configured-but-missing source fails closed at resolution time;</li>
 *   <li>inline values are rejected with a message naming the profile file, and the value is never
 *       echoed;</li>
 *   <li>a secret-file reference cannot leave {@code secrets.dir}.</li>
 * </ul>
 */
public class RepositoryCredentialsTest {

    private static final String BASE = """
            repository.id=crm
            repository.name=CRM Production
            repository.ssid=ICMCRM
            repository.db.vendor=db2
            repository.jdbc.url=jdbc:db2://db.example:50000/CRM
            repository.jdbc.schema=ICMADMIN
            """;

    private static final String ALL_ENV = """
            repository.cm.user.env=CM_CRM_USER
            repository.cm.password.env=CM_CRM_PASSWORD
            repository.jdbc.user.env=CM_CRM_JDBC_USER
            repository.jdbc.password.env=CM_CRM_JDBC_PASSWORD
            """;

    private static RepositoryProfile load(Path dir, String fileName, String body) throws IOException {
        TestSupport.writeFile(dir.resolve(fileName), body);
        return new RepositoryProfileLoader(dir).loadAll().get(0);
    }

    /**
     * Goal 01A (C): the file reference must survive loading instead of being accepted and dropped.
     *
     * <p>Fails against the pre-Goal-01A model, where {@code RepositoryProfile} had only the four
     * environment-variable name fields: the loaded profile reported no reference for the file-backed
     * credentials at all.
     */
    public void aSecretFileReferenceSurvivesLoading() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-creds-file-");
        try {
            RepositoryProfile profile = load(dir, "crm.properties", BASE
                    + "repository.cm.user.env=CM_CRM_USER\n"
                    + "repository.cm.password.file=cm-password.txt\n"
                    + "repository.jdbc.user.env=CM_CRM_JDBC_USER\n"
                    + "repository.jdbc.password.file=jdbc-password.txt\n");

            Assert.assertEquals(SecretRef.Source.ENVIRONMENT, profile.cmUserRef().source(),
                    "an .env reference is remembered as an environment reference");
            Assert.assertEquals("CM_CRM_USER", profile.cmUserRef().locator(), "the variable name survives");
            Assert.assertEquals(SecretRef.Source.FILE, profile.cmPasswordRef().source(),
                    "C: a .file reference must survive loading, not be dropped");
            Assert.assertEquals("cm-password.txt", profile.cmPasswordRef().locator(),
                    "the secret file name survives and is available to the future adapter");
            Assert.assertEquals(SecretRef.Source.FILE, profile.jdbcPasswordRef().source(),
                    "every credential key supports the file form");
            Assert.assertEquals("jdbc-password.txt", profile.jdbcPasswordRef().locator(),
                    "the JDBC password file name survives too");

            Assert.assertEquals(4, profile.credentialRefs().size(), "all four credentials have a reference");
            Assert.assertEquals(2, profile.credentialEnvNames().size(),
                    "only .env references are reported as environment variable names");
            Assert.assertTrue(profile.credentialSourceSummary()
                            .contains("repository.cm.password=secret file cm-password.txt"),
                    "the source summary names the file form: " + profile.credentialSourceSummary());
            Assert.assertTrue(profile.credentialSourceSummary()
                            .contains("repository.cm.user=environment variable CM_CRM_USER"),
                    "the source summary names the env form: " + profile.credentialSourceSummary());
            Assert.assertFalse(profile.credentialEnvNames().stream()
                            .anyMatch(name -> name.contains("cm-password.txt")),
                    "a file name is never reported as an environment variable name: "
                            + profile.credentialEnvNames());
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    /** Goal 01A (C): env-only and file-only profiles both load with exactly the declared sources. */
    public void envOnlyAndFileOnlyProfilesLoadWithTheDeclaredSources() throws IOException {
        Path envDir = TestSupport.newTempDir("cminsight-creds-envonly-");
        Path fileDir = TestSupport.newTempDir("cminsight-creds-fileonly-");
        try {
            RepositoryProfile envOnly = load(envDir, "crm.properties", BASE + ALL_ENV);
            for (Map.Entry<String, SecretRef> entry : envOnly.credentialRefs().entrySet()) {
                Assert.assertEquals(SecretRef.Source.ENVIRONMENT, entry.getValue().source(),
                        entry.getKey() + " comes from an environment variable");
            }
            Assert.assertEquals(4, envOnly.credentialEnvNames().size(), "all four env names are reported");

            RepositoryProfile fileOnly = load(fileDir, "crm.properties", BASE
                    + "repository.cm.user.file=cm-user.txt\n"
                    + "repository.cm.password.file=cm-password.txt\n"
                    + "repository.jdbc.user.file=jdbc-user.txt\n"
                    + "repository.jdbc.password.file=jdbc-password.txt\n");
            for (Map.Entry<String, SecretRef> entry : fileOnly.credentialRefs().entrySet()) {
                Assert.assertEquals(SecretRef.Source.FILE, entry.getValue().source(),
                        entry.getKey() + " comes from a secret file");
            }
            Assert.assertTrue(fileOnly.credentialEnvNames().isEmpty(),
                    "a file-only profile reports no environment variable names: " + fileOnly.credentialEnvNames());
            Assert.assertEquals(4, fileOnly.credentialSourceSummary().size(),
                    "all four file sources are reported: " + fileOnly.credentialSourceSummary());
        } finally {
            TestSupport.deleteRecursively(envDir);
            TestSupport.deleteRecursively(fileDir);
        }
    }

    /**
     * Goal 01A (C): with both forms configured the environment variable wins, deterministically, and
     * the shadowed file source is named in a diagnostic instead of being silently dropped.
     *
     * <p>Fails against the pre-Goal-01A loader, which kept only the environment name and reported
     * nothing about the ignored {@code .file} key.
     */
    public void conflictingSourcesPreferTheEnvironmentAndSaySo() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-creds-conflict-");
        try {
            Path fileName = dir.resolve("crm.properties");
            TestSupport.writeFile(fileName, BASE
                    + "repository.cm.user.env=CM_CRM_USER\n"
                    + "repository.cm.password.env=CM_CRM_PASSWORD\n"
                    + "repository.cm.password.file=cm-password.txt\n"
                    + "repository.jdbc.user.env=CM_CRM_JDBC_USER\n"
                    + "repository.jdbc.password.env=CM_CRM_JDBC_PASSWORD\n");
            RepositoryProfileLoader loader = new RepositoryProfileLoader(dir);
            RepositoryProfile profile = loader.loadAll().get(0);

            Assert.assertEquals(SecretRef.Source.ENVIRONMENT, profile.cmPasswordRef().source(),
                    "the environment variable wins over the secret file");
            Assert.assertEquals("CM_CRM_PASSWORD", profile.cmPasswordRef().locator(),
                    "the winning source is the environment variable name");
            Assert.assertTrue(loader.diagnostics().stream().anyMatch(diagnostic ->
                            diagnostic.contains("repository.cm.password")
                                    && diagnostic.contains("cm-password.txt")
                                    && diagnostic.contains("authoritative")),
                    "the shadowed file source is reported: " + loader.diagnostics());
            Assert.assertFalse(profile.credentialSourceSummary().contains("cm-password.txt"),
                    "the ignored source is not presented as active: " + profile.credentialSourceSummary());
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    /**
     * Goal 01A (C): a configured-but-missing source fails closed when resolution is requested.
     *
     * <p>Fails against the pre-Goal-01A model, which had no resolution step at all: an unset
     * environment variable produced no error and the adapter would have received nothing.
     */
    public void aConfiguredButMissingSourceFailsClosed() throws IOException {
        Path envDir = TestSupport.newTempDir("cminsight-creds-missing-env-");
        Path fileDir = TestSupport.newTempDir("cminsight-creds-missing-file-");
        try {
            RepositoryProfile envProfile = load(envDir, "crm.properties", BASE + ALL_ENV);
            ConfigException envFailure = Assert.assertThrows(ConfigException.class,
                    () -> envProfile.resolveCredentials(new SecretResolver(Map.of(), null)),
                    "a configured but unset environment variable must fail closed");
            Assert.assertTrue(envFailure.getMessage().contains("repository.cm.user"),
                    "the failure names the credential key: " + envFailure.getMessage());
            Assert.assertTrue(envFailure.getMessage().contains("CM_CRM_USER"),
                    "the failure names the environment variable: " + envFailure.getMessage());

            RepositoryProfile fileProfile = load(fileDir, "crm.properties", BASE
                    + "repository.cm.user.file=cm-user.txt\n"
                    + "repository.cm.password.file=cm-password.txt\n"
                    + "repository.jdbc.user.file=jdbc-user.txt\n"
                    + "repository.jdbc.password.file=jdbc-password.txt\n");
            ConfigException fileFailure = Assert.assertThrows(ConfigException.class,
                    () -> fileProfile.resolveCredentials(new SecretResolver(Map.of(), fileDir.resolve("secrets"))),
                    "a configured but unreadable secret file must fail closed");
            Assert.assertTrue(fileFailure.getMessage().contains("repository.cm.user"),
                    "the failure names the credential key: " + fileFailure.getMessage());
            Assert.assertTrue(fileFailure.getMessage().contains("could not be read"),
                    "the failure says the file could not be read: " + fileFailure.getMessage());
        } finally {
            TestSupport.deleteRecursively(envDir);
            TestSupport.deleteRecursively(fileDir);
        }
    }

    /**
     * Goal 01A (C): once {@code .env} is configured it is authoritative - a present secret file does
     * NOT rescue a missing environment variable.
     *
     * <p>Fails against any implementation that treats the two sources as a fallback chain (or merely
     * tries the file when the environment is empty): the resolution below would then succeed.
     */
    public void aSecretFileDoesNotRescueAMissingEnvironmentVariable() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-creds-authoritative-env-");
        try {
            Path secrets = Files.createDirectories(dir.resolve("secrets"));
            TestSupport.writeFile(secrets.resolve("cm-user.txt"), "cm-ops\n");
            TestSupport.writeFile(secrets.resolve("cm-password.txt"), "File-Only-Value-931\n");
            TestSupport.writeFile(secrets.resolve("jdbc-user.txt"), "jdbc-ops\n");
            TestSupport.writeFile(secrets.resolve("jdbc-password.txt"), "jdbc-value\n");
            // Every other credential is file-backed and present, so the only reason resolution can fail
            // is the credential whose authoritative .env source is configured but unset.
            RepositoryProfile profile = load(dir, "crm.properties", BASE
                    + "repository.cm.user.file=cm-user.txt\n"
                    + "repository.cm.password.env=CM_CRM_PASSWORD\n"
                    + "repository.cm.password.file=cm-password.txt\n"
                    + "repository.jdbc.user.file=jdbc-user.txt\n"
                    + "repository.jdbc.password.file=jdbc-password.txt\n");

            Assert.assertEquals(SecretRef.Source.ENVIRONMENT, profile.cmPasswordRef().source(),
                    "the environment reference is the declared source");
            Assert.assertEquals("CM_CRM_PASSWORD", profile.cmPasswordRef().locator(),
                    "the declared source is the environment variable, not the file");
            SecretResolver resolver = new SecretResolver(Map.of(), secrets);
            ConfigException failure = Assert.assertThrows(ConfigException.class,
                    () -> profile.resolveCredentials(resolver),
                    "the file must not rescue the missing authoritative environment variable");
            Assert.assertTrue(failure.getMessage().contains("CM_CRM_PASSWORD"),
                    "the failure names the missing environment variable: " + failure.getMessage());
            Assert.assertFalse(failure.getMessage().contains("File-Only-Value-931"),
                    "no credential value is ever printed: " + failure.getMessage());
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    /** Goal 01A (C): a file-backed credential resolves, is usable, and is never printed. */
    public void aResolvedFileCredentialIsAvailableWithoutALeak() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-creds-resolve-");
        try {
            Path secrets = Files.createDirectories(dir.resolve("secrets"));
            TestSupport.writeFile(secrets.resolve("cm-user.txt"), "cm-ops\n");
            TestSupport.writeFile(secrets.resolve("cm-password.txt"), "File-Only-Value-931\n");
            TestSupport.writeFile(secrets.resolve("jdbc-user.txt"), "jdbc-ops\n");
            TestSupport.writeFile(secrets.resolve("jdbc-password.txt"), "Jdbc-File-Value-4422\n");
            RepositoryProfile profile = load(dir, "crm.properties", BASE
                    + "repository.cm.user.file=cm-user.txt\n"
                    + "repository.cm.password.file=cm-password.txt\n"
                    + "repository.jdbc.user.file=jdbc-user.txt\n"
                    + "repository.jdbc.password.file=jdbc-password.txt\n");

            SecretResolver resolver = new SecretResolver(Map.of(), secrets);
            RepositoryProfile.RepositoryCredentials credentials = profile.resolveCredentials(resolver);
            Assert.assertTrue(credentials.usable(), "every credential resolved from a file");
            Assert.assertEquals(SecretRef.Source.FILE, credentials.cmPassword().source(),
                    "the resolved credential keeps its file origin");
            Assert.assertEquals("File-Only-Value-931", resolver.resolve(credentials.cmPassword()),
                    "the adapter can read the file-backed value");
            Assert.assertFalse(credentials.toString().contains("File-Only-Value-931"),
                    "credentials never print a value: " + credentials);
            Assert.assertFalse(credentials.toString().contains("Jdbc-File-Value-4422"),
                    "credentials never print a value: " + credentials);
            Assert.assertFalse(profile.toString().contains("File-Only-Value-931"),
                    "the profile never prints a value: " + profile);
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    /**
     * Goal 01A (C): every inline credential form is rejected, including
     * {@code repository.*.user=<value>}, which the old loader ignored silently.
     *
     * <p>Fails against the pre-Goal-01A loader for the {@code .user} keys: they carry no secret marker,
     * so the key-name heuristic let them through, and the operator's configured user name never reached
     * the connection while no error was raised.
     */
    public void inlineCredentialsAreRejectedWithTheFileName() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-creds-inline-");
        try {
            List<String> inlineKeys = List.of("repository.cm.user", "repository.cm.password",
                    "repository.jdbc.user", "repository.jdbc.password");
            for (String key : inlineKeys) {
                Path caseDir = dir.resolve(key.replace('.', '-'));
                TestSupport.writeFile(caseDir.resolve("inline.properties"),
                        BASE + key + "=Inline-Value-7781\n");
                ConfigException failure = Assert.assertThrows(ConfigException.class,
                        () -> new RepositoryProfileLoader(caseDir).loadAll(),
                        key + " must be rejected as an inline credential");
                Assert.assertTrue(failure.getMessage().contains("inline.properties"),
                        "the message names the profile file: " + failure.getMessage());
                Assert.assertTrue(failure.getMessage().contains(key),
                        "the message names the offending key: " + failure.getMessage());
                Assert.assertFalse(failure.getMessage().contains("Inline-Value-7781"),
                        "the rejection never echoes the value: " + failure.getMessage());
                Assert.assertTrue(failure.getMessage().contains(".env") && failure.getMessage().contains(".file"),
                        "the message says which forms are allowed: " + failure.getMessage());
            }
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    /**
     * Goal 01A (C): a secret-file reference cannot leave {@code secrets.dir}.
     *
     * <p>Fails against the pre-Goal-01A model, which never validated the name: a traversing reference
     * was accepted at load time and would only have failed (or succeeded, outside the directory)
     * later.
     */
    public void secretFileTraversalIsRefused() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-creds-traversal-");
        try {
            List<String> refused = List.of("../escape.txt", "sub/../../escape.txt", "/etc/passwd",
                    "~/escape.txt", "C:/escape.txt");
            int index = 0;
            for (String badName : refused) {
                Path caseDir = dir.resolve("case-" + index++);
                TestSupport.writeFile(caseDir.resolve("crm.properties"), BASE
                        + "repository.cm.password.file=" + badName + "\n");
                ConfigException failure = Assert.assertThrows(ConfigException.class,
                        () -> new RepositoryProfileLoader(caseDir).loadAll(),
                        "the secret file reference '" + badName + "' must be refused");
                Assert.assertTrue(failure.getMessage().contains("repository.cm.password.file"),
                        "the message names the offending key: " + failure.getMessage());
                Assert.assertTrue(failure.getMessage().contains("below the secrets"),
                        "the message states the boundary: " + failure.getMessage());
            }

            // Positive control: a nested name INSIDE the secrets directory stays legal, so the rule is a
            // traversal rule and not a blanket "no slashes".
            Path allowedDir = dir.resolve("allowed");
            TestSupport.writeFile(allowedDir.resolve("crm.properties"), BASE
                    + "repository.cm.password.file=sub/cm-password.txt\n");
            RepositoryProfile allowed = new RepositoryProfileLoader(allowedDir).loadAll().get(0);
            Assert.assertEquals("sub/cm-password.txt", allowed.cmPasswordRef().locator(),
                    "a nested file name inside the secrets directory is accepted");
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    /**
     * Goal 01A (C): a credential with no configured source is MISSING (never silently null) and fails
     * closed at resolution.
     */
    public void anUnconfiguredCredentialIsReportedAndFailsClosedAtResolution() {
        RepositoryProfile profile = new RepositoryProfile("crm", "CRM", "SSID", DatabaseVendor.DB2,
                "jdbc:db2://h:50000/CRM", null, null, null, null, null, null, null);

        Assert.assertEquals(SecretRef.Source.MISSING, profile.cmUserRef().source(),
                "an absent source is MISSING rather than an absent reference");
        Assert.assertTrue(profile.credentialEnvNames().isEmpty(), "no environment names are reported");
        Assert.assertTrue(profile.credentialSourceSummary().isEmpty(),
                "a missing source is not presented as configured: " + profile.credentialSourceSummary());
        Assert.assertEquals(4, profile.credentialRefs().size(), "the credential map is still complete");

        ConfigException failure = Assert.assertThrows(ConfigException.class,
                () -> profile.resolveCredentials(new SecretResolver(Map.of(), null)),
                "an unconfigured credential must fail closed when resolution is requested");
        Assert.assertTrue(failure.getMessage().contains("not configured"),
                "the failure explains the missing source: " + failure.getMessage());
    }
}
