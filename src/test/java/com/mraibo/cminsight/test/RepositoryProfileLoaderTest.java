package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.ConfigException;
import com.mraibo.cminsight.config.DatabaseVendor;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.RepositoryProfileLoader;
import com.mraibo.cminsight.config.SecretRef;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Repository profile loading: valid profiles, fail-closed validation and secret rejection. */
public class RepositoryProfileLoaderTest {

    /**
     * The four credential references used by the programmatic fixtures. Goal 01A section C: a profile
     * declares where a credential comes from (a {@link SecretRef}), never the value itself.
     */
    private static final SecretRef CM_USER = RepositoryProfile.credentialFromEnvironment(
            RepositoryProfile.CM_USER_KEY, "CM_USER");
    private static final SecretRef CM_PASSWORD = RepositoryProfile.credentialFromEnvironment(
            RepositoryProfile.CM_PASSWORD_KEY, "CM_PASSWORD");
    private static final SecretRef JDBC_USER = RepositoryProfile.credentialFromEnvironment(
            RepositoryProfile.JDBC_USER_KEY, "JDBC_USER");
    private static final SecretRef JDBC_PASSWORD = RepositoryProfile.credentialFromEnvironment(
            RepositoryProfile.JDBC_PASSWORD_KEY, "JDBC_PASSWORD");

    private static SecretRef envRef(String key, String envName) {
        return RepositoryProfile.credentialFromEnvironment(key, envName);
    }

    private static final String VALID_PROFILE = """
            repository.id=crm
            repository.name=CRM Production
            repository.ssid=ICMCRM
            repository.db.vendor=db2
            repository.jdbc.url=jdbc:db2://db.example:50000/CRM
            repository.jdbc.schema=ICMADMIN
            repository.cm.user.env=CM_CRM_USER
            repository.cm.password.env=CM_CRM_PASSWORD
            repository.jdbc.user.env=CM_CRM_JDBC_USER
            repository.jdbc.password.env=CM_CRM_JDBC_PASSWORD
            repository.icn.base.url=https://icn.example/icn
            """;

    public void validProfileLoadsWithEveryFieldAndDiagnosticsMentionTheCount() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-profiles-");
        try {
            TestSupport.writeFile(dir.resolve("crm.properties"), VALID_PROFILE);
            RepositoryProfileLoader loader = new RepositoryProfileLoader(dir);

            List<RepositoryProfile> profiles = loader.loadAll();
            Assert.assertEquals(1, profiles.size(), "one profile is loaded");
            RepositoryProfile profile = profiles.get(0);
            Assert.assertEquals("crm", profile.id(), "the id is read");
            Assert.assertEquals("CRM Production", profile.displayName(), "the display name is read");
            Assert.assertEquals("ICMCRM", profile.ssid(), "the ssid is read");
            Assert.assertEquals(DatabaseVendor.DB2, profile.databaseVendor(), "the vendor is parsed");
            Assert.assertEquals("jdbc:db2://db.example:50000/CRM", profile.jdbcUrl(), "the JDBC URL is read");
            Assert.assertEquals("ICMADMIN", profile.jdbcSchema(), "the schema is read");
            Assert.assertEquals("https://icn.example/icn", profile.icnBaseUrl(), "the ICN URL is read");
            Assert.assertEquals(dir.resolve("crm.properties").toAbsolutePath().normalize(), profile.sourceFile(),
                    "the source file is recorded");
            Assert.assertEquals(4, profile.credentialEnvNames().size(), "all four credential names are reported");
            Assert.assertTrue(profile.credentialEnvNames().contains("repository.cm.password=CM_CRM_PASSWORD"),
                    "credential names are labels to environment variable names: " + profile.credentialEnvNames());
            Assert.assertTrue(loader.diagnostics().stream().anyMatch(d -> d.contains("Loaded 1 repository profile(s)")),
                    "diagnostics report the count: " + loader.diagnostics());

            Assert.assertEquals("crm", loader.load("crm").id(), "load(id) returns the profile");
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    public void missingRequiredKeyFailsWithTheFileNameInTheMessage() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-profiles-broken-");
        try {
            TestSupport.writeFile(dir.resolve("broken.properties"), """
                    repository.id=broken
                    repository.name=Broken Repository
                    repository.db.vendor=db2
                    repository.jdbc.url=jdbc:db2://db.example:50000/BROKEN
                    repository.cm.user.env=CM_BROKEN_USER
                    repository.cm.password.env=CM_BROKEN_PASSWORD
                    repository.jdbc.user.env=JDBC_BROKEN_USER
                    repository.jdbc.password.env=JDBC_BROKEN_PASSWORD
                    """);
            RepositoryProfileLoader loader = new RepositoryProfileLoader(dir);
            ConfigException failure = Assert.assertThrows(ConfigException.class, loader::loadAll,
                    "a profile without an ssid must not be skipped silently");
            Assert.assertTrue(failure.getMessage().contains("broken.properties"),
                    "the message names the offending file: " + failure.getMessage());
            Assert.assertTrue(failure.getMessage().contains("repository.ssid"),
                    "the message names the missing key: " + failure.getMessage());
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    public void invalidVendorIsRejected() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-profiles-vendor-");
        try {
            TestSupport.writeFile(dir.resolve("mysql.properties"), """
                    repository.id=mysql
                    repository.name=MySQL
                    repository.ssid=SSIDMYSQL
                    repository.db.vendor=mysql
                    repository.jdbc.url=jdbc:mysql://db.example:3306/x
                    repository.cm.user.env=CM_USER
                    repository.cm.password.env=CM_PASSWORD
                    repository.jdbc.user.env=JDBC_USER
                    repository.jdbc.password.env=JDBC_PASSWORD
                    """);
            RepositoryProfileLoader loader = new RepositoryProfileLoader(dir);
            ConfigException failure = Assert.assertThrows(ConfigException.class, loader::loadAll,
                    "an unsupported vendor must fail the load");
            Assert.assertTrue(failure.getMessage().contains("mysql"),
                    "the message names the unsupported vendor: " + failure.getMessage());
            Assert.assertTrue(failure.getMessage().contains("mysql.properties"),
                    "the message names the offending profile file as well: " + failure.getMessage());
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    /**
     * Regression (loader vendor fix): a MISSING vendor must also be reported with the file name.
     *
     * <p>Fails against the pre-fix code, which caught only {@code IllegalArgumentException} while
     * {@code DatabaseVendor.parse} throws {@code ConfigException}, so the file name was lost.
     */
    public void missingVendorIsRejectedWithTheFileName() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-profiles-novendor-");
        try {
            TestSupport.writeFile(dir.resolve("novendor.properties"), """
                    repository.id=novendor
                    repository.name=No Vendor
                    repository.ssid=SSIDNOVENDOR
                    repository.jdbc.url=jdbc:db2://db.example:50000/NOVENDOR
                    repository.cm.user.env=CM_USER
                    repository.cm.password.env=CM_PASSWORD
                    repository.jdbc.user.env=JDBC_USER
                    repository.jdbc.password.env=JDBC_PASSWORD
                    """);
            ConfigException failure = Assert.assertThrows(ConfigException.class,
                    new RepositoryProfileLoader(dir)::loadAll, "a profile without a vendor must fail");
            Assert.assertTrue(failure.getMessage().contains("novendor.properties"),
                    "the message names the offending file: " + failure.getMessage());
            Assert.assertTrue(failure.getMessage().contains("vendor"),
                    "the message explains the missing vendor: " + failure.getMessage());
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    /**
     * Regression (t5 F6): a JDBC URL that embeds a credential in any of the three driver shapes is
     * rejected, and {@code jdbcUrlEmbedsCredential()} reports the same rule.
     *
     * <p>Fails against the pre-fix code, which accepted all three URLs and would have printed the
     * password through the generated record {@code toString()}.
     */
    public void jdbcUrlsWithAnEmbeddedCredentialAreRejected() throws IOException {
        RepositoryProfile userInfo = new RepositoryProfile("crm", "CRM", "SSID", DatabaseVendor.DB2,
                "jdbc:db2://cmuser:Credential-5521@db.example:50000/CRM", null,
                CM_USER, CM_PASSWORD, JDBC_USER, JDBC_PASSWORD, null, null);
        Assert.assertTrue(userInfo.jdbcUrlEmbedsCredential(), "//user:password@host is detected");
        RepositoryProfile oracleThin = new RepositoryProfile("crm", "CRM", "SSID", DatabaseVendor.ORACLE,
                "jdbc:oracle:thin:cmuser/Credential-5521@db.example:1521/CRMSVC", null,
                CM_USER, CM_PASSWORD, JDBC_USER, JDBC_PASSWORD, null, null);
        Assert.assertTrue(oracleThin.jdbcUrlEmbedsCredential(), "the Oracle thin :user/password@host is detected");
        RepositoryProfile property = new RepositoryProfile("crm", "CRM", "SSID", DatabaseVendor.DB2,
                "jdbc:db2://db.example:50000/CRM;password=Credential-5521", null,
                CM_USER, CM_PASSWORD, JDBC_USER, JDBC_PASSWORD, null, null);
        Assert.assertTrue(property.jdbcUrlEmbedsCredential(), ";password=value is detected");

        RepositoryProfile clean = new RepositoryProfile("crm", "CRM", "SSID", DatabaseVendor.ORACLE,
                "jdbc:oracle:thin:@//db.example:1521/CRMSVC", null,
                CM_USER, CM_PASSWORD, JDBC_USER, JDBC_PASSWORD, null, null);
        Assert.assertFalse(clean.jdbcUrlEmbedsCredential(), "a credential-free Oracle service URL passes");
        Assert.assertEquals("jdbc:oracle:thin:@//db.example:1521/CRMSVC", clean.safeJdbcUrl(),
                "a clean URL is returned unchanged");

        Assert.assertFalse(clean.toString().contains("Credential-5521"), "a clean profile prints no credential");
        Assert.assertFalse(userInfo.safeJdbcUrl().contains("Credential-5521"),
                "the userinfo password is masked: " + userInfo.safeJdbcUrl());
        Assert.assertFalse(oracleThin.safeJdbcUrl().contains("Credential-5521"),
                "the Oracle thin password is masked: " + oracleThin.safeJdbcUrl());
        Assert.assertFalse(property.safeJdbcUrl().contains("Credential-5521"),
                "the property password is masked: " + property.safeJdbcUrl());
        String printed = userInfo.toString() + oracleThin + property;
        Assert.assertFalse(printed.contains("Credential-5521"),
                "RepositoryProfile.toString() masks every shape: " + printed);

        Path dir = TestSupport.newTempDir("cminsight-profiles-jdbcurl-");
        try {
            TestSupport.writeFile(dir.resolve("leaky.properties"), """
                    repository.id=leaky
                    repository.name=Leaky
                    repository.ssid=SSIDLEAKY
                    repository.db.vendor=db2
                    repository.jdbc.url=jdbc:db2://cmuser:Credential-5521@db.example:50000/LEAKY
                    repository.cm.user.env=CM_USER
                    repository.cm.password.env=CM_PASSWORD
                    repository.jdbc.user.env=JDBC_USER
                    repository.jdbc.password.env=JDBC_PASSWORD
                    """);
            ConfigException failure = Assert.assertThrows(ConfigException.class,
                    new RepositoryProfileLoader(dir)::loadAll,
                    "a profile whose JDBC URL embeds a credential must not load");
            Assert.assertTrue(failure.getMessage().contains("leaky.properties"),
                    "the message names the offending file: " + failure.getMessage());
            Assert.assertTrue(failure.getMessage().contains("repository.jdbc.url"),
                    "the message names the offending key: " + failure.getMessage());
            Assert.assertFalse(failure.getMessage().contains("Credential-5521"),
                    "the message never echoes the credential: " + failure.getMessage());
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    public void duplicateRepositoryIdIsRejected() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-profiles-duplicate-");
        try {
            TestSupport.writeFile(dir.resolve("one.properties"), VALID_PROFILE);
            TestSupport.writeFile(dir.resolve("two.properties"), VALID_PROFILE);
            RepositoryProfileLoader loader = new RepositoryProfileLoader(dir);
            ConfigException failure = Assert.assertThrows(ConfigException.class, loader::loadAll,
                    "two files declaring the same id must fail");
            Assert.assertTrue(failure.getMessage().contains("Duplicate repository id 'crm'"),
                    "the message names the duplicate: " + failure.getMessage());
            Assert.assertTrue(failure.getMessage().contains("one.properties")
                            && failure.getMessage().contains("two.properties"),
                    "both files are named: " + failure.getMessage());
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    public void inlinePasswordLookingKeyIsRejected() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-profiles-inline-");
        try {
            Path inlineCase = dir.resolve("inline");
            TestSupport.writeFile(inlineCase.resolve("inline.properties"), VALID_PROFILE
                    + "repository.cm.password=plaintext-in-the-file\n");
            RepositoryProfileLoader loader = new RepositoryProfileLoader(inlineCase);
            ConfigException failure = Assert.assertThrows(ConfigException.class, loader::loadAll,
                    "an inline password key must be rejected");
            Assert.assertTrue(failure.getMessage().contains("looks like an inline secret"),
                    "the message explains the rule: " + failure.getMessage());
            Assert.assertTrue(failure.getMessage().contains("inline.properties"),
                    "the message names the file: " + failure.getMessage());
            Assert.assertFalse(failure.getMessage().contains("plaintext-in-the-file"),
                    "the message must not echo an inline secret value: " + failure.getMessage());

            Path tokenCase = dir.resolve("token");
            TestSupport.writeFile(tokenCase.resolve("token.properties"), VALID_PROFILE
                    + "repository.api.token=abc\n");
            ConfigException tokenFailure = Assert.assertThrows(ConfigException.class,
                    new RepositoryProfileLoader(tokenCase)::loadAll, "a token key is rejected as well");
            Assert.assertTrue(tokenFailure.getMessage().contains("repository.api.token"),
                    "the message names the offending key: " + tokenFailure.getMessage());

            Path secretCase = dir.resolve("secret");
            TestSupport.writeFile(secretCase.resolve("secret.properties"), VALID_PROFILE
                    + "repository.jdbc.secret=abc\n");
            ConfigException secretFailure = Assert.assertThrows(ConfigException.class,
                    new RepositoryProfileLoader(secretCase)::loadAll, "a secret key is rejected as well");
            Assert.assertTrue(secretFailure.getMessage().contains("repository.jdbc.secret"),
                    "the message names the offending key: " + secretFailure.getMessage());

            Path passwdCase = dir.resolve("passwd");
            TestSupport.writeFile(passwdCase.resolve("passwd.properties"), VALID_PROFILE
                    + "repository.passwd=abc\n");
            Assert.assertThrows(ConfigException.class, new RepositoryProfileLoader(passwdCase)::loadAll,
                    "a passwd key is rejected as well");
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    /**
     * Goal 01A (C): the {@code .file} indirection keys are accepted AND survive loading as file
     * references.
     *
     * <p>Fails against the pre-Goal-01A model, which accepted the key and then kept only the
     * environment-variable fields: the loaded profile reported no reference for the file at all.
     */
    public void environmentIndirectionKeysAreAccepted() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-profiles-indirection-");
        try {
            TestSupport.writeFile(dir.resolve("crm.properties"), """
                    repository.id=crm
                    repository.name=CRM Production
                    repository.ssid=ICMCRM
                    repository.db.vendor=db2
                    repository.jdbc.url=jdbc:db2://db.example:50000/CRM
                    repository.cm.user.env=CM_CRM_USER
                    repository.cm.password.file=cm-password.txt
                    repository.jdbc.user.env=CM_CRM_JDBC_USER
                    repository.jdbc.password.file=jdbc-password.txt
                    """);
            RepositoryProfileLoader loader = new RepositoryProfileLoader(dir);
            List<RepositoryProfile> profiles = loader.loadAll();
            Assert.assertEquals(1, profiles.size(), "keys ending in .env or .file are not secrets");

            RepositoryProfile profile = profiles.get(0);
            Assert.assertEquals(SecretRef.Source.FILE, profile.cmPasswordRef().source(),
                    "the .file key survives loading as a FILE reference");
            Assert.assertEquals("cm-password.txt", profile.cmPasswordRef().locator(),
                    "the file name survives loading for the future adapter");
            Assert.assertEquals(SecretRef.Source.FILE, profile.jdbcPasswordRef().source(),
                    "every credential key keeps its .file reference");
            Assert.assertEquals(SecretRef.Source.ENVIRONMENT, profile.cmUserRef().source(),
                    "the .env keys still win where they are the only source");
            Assert.assertEquals(2, profile.credentialEnvNames().size(),
                    "only the .env references are reported as environment variable names");
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    public void nonPropertiesFilesAreIgnored() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-profiles-skip-");
        try {
            TestSupport.writeFile(dir.resolve("crm.properties"), VALID_PROFILE);
            TestSupport.writeFile(dir.resolve("readme.txt"), "not a profile\n");
            TestSupport.writeFile(dir.resolve("crm.properties.example"), "repository.id=ignored\n");
            RepositoryProfileLoader loader = new RepositoryProfileLoader(dir);

            List<RepositoryProfile> profiles = loader.loadAll();
            Assert.assertEquals(1, profiles.size(), "only *.properties files are loaded");
            Assert.assertEquals("crm", profiles.get(0).id(), "the example template is skipped");
            Assert.assertTrue(loader.diagnostics().stream()
                            .noneMatch(d -> d.contains("readme") || d.contains("example")),
                    "the skipped files are mentioned nowhere: " + loader.diagnostics());
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    public void unknownRepositoryIdFailsWithTheKnownIds() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-profiles-unknown-");
        try {
            TestSupport.writeFile(dir.resolve("crm.properties"), VALID_PROFILE);
            RepositoryProfileLoader loader = new RepositoryProfileLoader(dir);
            ConfigException failure = Assert.assertThrows(ConfigException.class, () -> loader.load("nope"),
                    "an unknown id must fail");
            Assert.assertTrue(failure.getMessage().contains("Unknown repository id 'nope'"),
                    "the message names the requested id: " + failure.getMessage());
            Assert.assertTrue(failure.getMessage().contains("crm"), "the known ids are listed: "
                    + failure.getMessage());
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    public void missingProfilesDirectoryIsReportedNotFatal() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-profiles-missing-");
        try {
            RepositoryProfileLoader loader = new RepositoryProfileLoader(dir.resolve("absent"));
            Assert.assertTrue(loader.loadAll().isEmpty(), "no directory means no profiles");
            Assert.assertTrue(loader.diagnostics().stream().anyMatch(d -> d.contains("no repositories are configured")),
                    "the absence is reported: " + loader.diagnostics());
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    public void profileValidationRejectsIncompleteOrUnsafeValues() {
        Assert.assertThrows(ConfigException.class,
                () -> new RepositoryProfile(null, "name", "ssid", DatabaseVendor.DB2, "jdbc:db2://h/1", null,
                        CM_USER, CM_PASSWORD, JDBC_USER, JDBC_PASSWORD, null, null),
                "a missing repository id is rejected");
        Assert.assertThrows(ConfigException.class,
                () -> new RepositoryProfile("bad id", "name", "ssid", DatabaseVendor.DB2, "jdbc:db2://h/1", null,
                        CM_USER, CM_PASSWORD, JDBC_USER, JDBC_PASSWORD, null, null),
                "an invalid id is rejected");
        Assert.assertThrows(ConfigException.class,
                () -> new RepositoryProfile("crm", null, "ssid", DatabaseVendor.DB2, "jdbc:db2://h/1", null,
                        CM_USER, CM_PASSWORD, JDBC_USER, JDBC_PASSWORD, null, null),
                "a missing name is rejected");
        Assert.assertThrows(ConfigException.class,
                () -> new RepositoryProfile("crm", "name", null, DatabaseVendor.DB2, "jdbc:db2://h/1", null,
                        CM_USER, CM_PASSWORD, JDBC_USER, JDBC_PASSWORD, null, null),
                "a missing ssid is rejected");
        Assert.assertThrows(ConfigException.class,
                () -> new RepositoryProfile("crm", "name", "ssid", null, "jdbc:db2://h/1", null,
                        CM_USER, CM_PASSWORD, JDBC_USER, JDBC_PASSWORD, null, null),
                "a missing vendor is rejected");
        Assert.assertThrows(ConfigException.class,
                () -> new RepositoryProfile("crm", "name", "ssid", DatabaseVendor.DB2, null, null,
                        CM_USER, CM_PASSWORD, JDBC_USER, JDBC_PASSWORD, null, null),
                "a missing JDBC URL is rejected");
        Assert.assertThrows(ConfigException.class,
                () -> new RepositoryProfile("crm", "name", "ssid", DatabaseVendor.DB2, "jdbc:db2://h/1", null,
                        envRef(RepositoryProfile.CM_USER_KEY, "not a name"), CM_PASSWORD, JDBC_USER, JDBC_PASSWORD,
                        null, null),
                "an invalid environment variable name is rejected");
        Assert.assertThrows(ConfigException.class,
                () -> new RepositoryProfile("crm", "name", "ssid", DatabaseVendor.DB2, "jdbc:db2://h/1", null,
                        RepositoryProfile.credentialFromSecretFile(RepositoryProfile.CM_USER_KEY, "../escape.txt"),
                        CM_PASSWORD, JDBC_USER, JDBC_PASSWORD, null, null),
                "a traversing secret file reference is rejected while the profile is built");
        Assert.assertThrows(ConfigException.class,
                () -> new RepositoryProfile("crm", "name", "ssid", DatabaseVendor.DB2, "jdbc:db2://h/1", null,
                        RepositoryProfile.credentialFromSecretFile(RepositoryProfile.CM_USER_KEY, "/etc/passwd"),
                        CM_PASSWORD, JDBC_USER, JDBC_PASSWORD, null, null),
                "an absolute secret file reference is rejected while the profile is built");

        RepositoryProfile minimal = new RepositoryProfile("crm", "name", "ssid", DatabaseVendor.ORACLE,
                "jdbc:oracle:thin:@h:1521/X", null, null, null, null, null, null, null);
        Assert.assertTrue(minimal.credentialEnvNames().isEmpty(),
                "a profile without credential indirection reports no names");
    }
}
