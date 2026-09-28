package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.AppPaths;
import com.mraibo.cminsight.config.ConfigException;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.SecretRef;
import com.mraibo.cminsight.config.SecretResolver;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Goal 01A section D: one rule for every operational path.
 *
 * <blockquote>A relative operational path is resolved against the application home, never against the
 * directory from which the launcher happened to be invoked.</blockquote>
 *
 * <p>These tests pin {@link AppPaths} as the single Java authority: the launcher supplies the home
 * ({@code -Dcminsight.home}, then {@code CM_INSIGHT_HOME}, then the documented working-directory
 * fallback), relative values are resolved against it, absolute values are untouched, and the
 * repository secret files are found from the home even when the process working directory is somewhere
 * else entirely.
 */
public class AppPathsTest {

    /** Names the child-JVM log files uniquely inside one test run. */
    private static final AtomicInteger JVM_SEQUENCE = new AtomicInteger();

    /** A home directory that exists and is not the process working directory. */
    private static Path newHome(String label) throws IOException {
        Path home = TestSupport.newTempDir("cminsight-home-" + label + "-");
        Files.createDirectories(home.resolve("conf"));
        return home;
    }

    private interface Body {
        void run() throws Exception;
    }

    /** Runs {@code body} with {@code -Dcminsight.home} set, restoring the previous value afterwards. */
    private static void withLauncherHome(Path home, Body body) throws Exception {
        String previous = System.getProperty(AppPaths.HOME_PROPERTY);
        System.setProperty(AppPaths.HOME_PROPERTY, home.toString());
        try {
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty(AppPaths.HOME_PROPERTY);
            } else {
                System.setProperty(AppPaths.HOME_PROPERTY, previous);
            }
        }
    }

    private static Path workingDirectory() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /**
     * Goal 01A (D): the home the launcher supplies wins, and every relative path is resolved against
     * it - not against the process working directory.
     *
     * <p>Fails against the pre-Goal-01A code, which resolved relative values against the caller's
     * working directory: the resolved path would have been
     * {@code <user.dir>/conf/application.properties} instead of {@code <home>/conf/application.properties}.
     */
    public void aLauncherSuppliedHomeWinsOverTheWorkingDirectory() throws Exception {
        Path home = newHome("launcher");
        try {
            Assert.assertFalse(home.equals(workingDirectory()),
                    "this test needs a home that is not the process working directory");
            withLauncherHome(home, () -> {
                AppPaths paths = AppPaths.resolve();
                Assert.assertEquals(AppPaths.HomeSource.SYSTEM_PROPERTY, paths.homeSource(),
                        "-D" + AppPaths.HOME_PROPERTY + " is the primary launcher mechanism");
                Assert.assertEquals(home.toAbsolutePath().normalize(), paths.home(),
                        "the home is the directory the launcher supplied");
                Assert.assertFalse(paths.usedWorkingDirectoryFallback(), "no fallback was used");

                Path expected = home.resolve("conf/application.properties").normalize();
                Assert.assertEquals(expected, paths.configurationFile(null),
                        "the default configuration file is home-relative");
                Assert.assertFalse(expected.equals(workingDirectory().resolve("conf/application.properties")),
                        "the path is NOT resolved against the process working directory (" + workingDirectory() + ")");
                Assert.assertEquals(home.resolve("conf/profiles"), paths.profilesDir(AppConfig.empty()),
                        "profiles.dir resolves against the home");
                Assert.assertEquals(home.resolve("conf/secrets"), paths.secretsDir(AppConfig.empty()),
                        "secrets.dir resolves against the home");
                Assert.assertTrue(paths.describeHome().contains(home.toString()),
                        "the diagnostic names the home: " + paths.describeHome());
                Assert.assertTrue(paths.describeHome().contains(AppPaths.HOME_PROPERTY),
                        "the diagnostic names where the home came from: " + paths.describeHome());
            });
        } finally {
            TestSupport.deleteRecursively(home);
        }
    }

    /**
     * Goal 01A (D): an explicitly supplied home must exist, otherwise every relative path misresolves
     * silently. The refusal names the home and the mechanism that supplied it.
     *
     * <p>The permissive {@link AppPaths#of(Path, AppPaths.HomeSource)} stays permissive on purpose, so a
     * test or a diagnostics caller can construct a hypothetical home that does not exist yet - only the
     * launcher-supplied value is validated.
     */
    public void anExplicitlySuppliedHomeMustBeAnExistingDirectory() throws Exception {
        Path absent = workingDirectory().resolve("build/test-tmp/absent-home-" + System.nanoTime());
        Path notADirectory = TestSupport.writeFile(
                workingDirectory().resolve("build/test-tmp/home-is-a-file-" + System.nanoTime() + ".txt"),
                "not a directory\n");
        try {
            withLauncherHome(absent, () -> {
                ConfigException failure = Assert.assertThrows(ConfigException.class, AppPaths::resolve,
                        "a typo in the launcher home must fail loudly");
                Assert.assertTrue(failure.getMessage().contains(absent.toString()),
                        "the message names the unusable home: " + failure.getMessage());
                Assert.assertTrue(failure.getMessage().contains(AppPaths.HOME_PROPERTY),
                        "the message names the mechanism: " + failure.getMessage());
                Assert.assertTrue(failure.getMessage().contains("profiles.dir"),
                        "the message explains what could not be resolved: " + failure.getMessage());
            });

            withLauncherHome(notADirectory, () -> {
                ConfigException failure = Assert.assertThrows(ConfigException.class, AppPaths::resolve,
                        "a launcher home that is a file must fail loudly");
                Assert.assertTrue(failure.getMessage().contains(notADirectory.toString()),
                        "the message names the file: " + failure.getMessage());
                Assert.assertTrue(failure.getMessage().contains("is not an existing directory"),
                        "the message states the requirement: " + failure.getMessage());
            });

            // The permissive factory is NOT validated: callers that know a hypothetical home may build it.
            AppPaths hypothetical = AppPaths.of(absent, AppPaths.HomeSource.SYSTEM_PROPERTY);
            Assert.assertEquals(absent.toAbsolutePath().normalize(), hypothetical.home(),
                    "AppPaths.of() trusts its caller");
            Assert.assertEquals(absent.toAbsolutePath().normalize().resolve("conf/profiles"),
                    hypothetical.profilesDir(AppConfig.empty()),
                    "and resolves against that hypothetical home");
        } finally {
            TestSupport.deleteRecursively(absent);
            Files.deleteIfExists(notADirectory);
        }
    }

    /**
     * Goal 01A (D): the home precedence is {@code -Dcminsight.home}, then {@code CM_INSIGHT_HOME}, then
     * the working-directory fallback, and {@code describeHome()} always says which one was used.
     *
     * <p>The environment cannot be modified from inside the JVM, so the second half only asserts the
     * environment branch when this process actually has {@code CM_INSIGHT_HOME} set; the property branch
     * and the fallback branch are asserted unconditionally.
     */
    public void homePrecedenceIsPropertyThenEnvironmentThenWorkingDirectory() throws Exception {
        Path propertyHome = newHome("precedence");
        try {
            String envHome = System.getenv(AppPaths.HOME_ENV);
            withLauncherHome(propertyHome, () -> {
                AppPaths paths = AppPaths.resolve();
                Assert.assertEquals(AppPaths.HomeSource.SYSTEM_PROPERTY, paths.homeSource(),
                        "the system property wins over the environment and the working directory");
                Assert.assertEquals(propertyHome.toAbsolutePath().normalize(), paths.home(),
                        "even when " + AppPaths.HOME_ENV + " is set (" + envHome + ")");
                Assert.assertTrue(paths.describeHome().contains("-D" + AppPaths.HOME_PROPERTY),
                        "describeHome() names the winning source: " + paths.describeHome());
            });

            String previous = System.getProperty(AppPaths.HOME_PROPERTY);
            System.clearProperty(AppPaths.HOME_PROPERTY);
            try {
                AppPaths paths = AppPaths.resolve();
                if (envHome == null || envHome.isBlank()) {
                    Assert.assertEquals(AppPaths.HomeSource.WORKING_DIRECTORY, paths.homeSource(),
                            "with neither mechanism the working directory is the fallback");
                    Assert.assertTrue(paths.describeHome().contains("working directory"),
                            "describeHome() names the fallback: " + paths.describeHome());
                } else {
                    Assert.assertEquals(AppPaths.HomeSource.ENVIRONMENT, paths.homeSource(),
                            AppPaths.HOME_ENV + " wins over the working directory");
                    Assert.assertTrue(paths.describeHome().contains(AppPaths.HOME_ENV),
                            "describeHome() names the environment source: " + paths.describeHome());
                }
            } finally {
                if (previous != null) {
                    System.setProperty(AppPaths.HOME_PROPERTY, previous);
                }
            }
        } finally {
            TestSupport.deleteRecursively(propertyHome);
        }
    }

    /**
     * Goal 01A (D): without any launcher-supplied home the working directory is the documented
     * fallback, and when {@code CM_INSIGHT_HOME} is set it is honoured as the secondary mechanism.
     *
     * <p>The environment cannot be manipulated from inside the JVM, so this test accepts either branch
     * and asserts the corresponding, documented behaviour - never a silent third behaviour.
     */
    public void theDocumentedFallbackIsTheWorkingDirectory() throws Exception {
        String previous = System.getProperty(AppPaths.HOME_PROPERTY);
        System.clearProperty(AppPaths.HOME_PROPERTY);
        try {
            String envHome = System.getenv(AppPaths.HOME_ENV);
            AppPaths paths = AppPaths.resolve();
            if (envHome == null || envHome.isBlank()) {
                Assert.assertEquals(AppPaths.HomeSource.WORKING_DIRECTORY, paths.homeSource(),
                        "with no launcher home, the working directory is the documented fallback");
                Assert.assertTrue(paths.usedWorkingDirectoryFallback(), "the fallback is reported as such");
                Assert.assertEquals(workingDirectory(), paths.home(), "the fallback home is the working directory");
                Assert.assertEquals(paths.home().resolve("conf/profiles"), paths.profilesDir(AppConfig.empty()),
                        "defaults stay home-relative in the fallback case too");
            } else {
                Assert.assertEquals(AppPaths.HomeSource.ENVIRONMENT, paths.homeSource(),
                        AppPaths.HOME_ENV + " is the secondary launcher mechanism");
                Assert.assertEquals(Path.of(envHome).toAbsolutePath().normalize(), paths.home(),
                        "the environment home is used");
                Assert.assertFalse(paths.usedWorkingDirectoryFallback(),
                        "the working directory is not used when " + AppPaths.HOME_ENV + " is set");
            }
        } finally {
            if (previous != null) {
                System.setProperty(AppPaths.HOME_PROPERTY, previous);
            }
        }
    }

    /**
     * Goal 01A (D): relative values follow the home, absolute values are used unchanged - for every
     * operational key, including the three reserved for a later goal.
     */
    public void relativeAndAbsoluteOperationalPathsFollowTheOneRule() throws Exception {
        Path home = newHome("paths");
        Path absoluteTarget = newHome("absolute");
        try {
            Properties properties = new Properties();
            properties.setProperty("profiles.dir", "conf/profiles");
            properties.setProperty("classifications.file", "conf/classifications.properties");
            properties.setProperty("secrets.dir", "conf/secrets");
            properties.setProperty("data.dir", absoluteTarget.toString());
            properties.setProperty("reports.dir", "out/reports");
            AppConfig config = AppConfig.fromProperties(properties);

            withLauncherHome(home, () -> {
                AppPaths paths = AppPaths.resolve();
                Assert.assertEquals(home.resolve("conf/profiles"), paths.profilesDir(config),
                        "a relative profiles.dir resolves against the home");
                Assert.assertEquals(home.resolve("conf/classifications.properties"),
                        paths.classificationsFile(config), "a relative classifications.file follows the home");
                Assert.assertEquals(home.resolve("conf/secrets"), paths.secretsDir(config),
                        "a relative secrets.dir follows the home");
                Assert.assertEquals(home.resolve("out/reports"), paths.reportsDir(config),
                        "a relative reports.dir follows the home");
                Assert.assertEquals(absoluteTarget.toAbsolutePath().normalize(), paths.dataDir(config),
                        "an absolute data.dir is used unchanged");
                Assert.assertEquals(absoluteTarget.toAbsolutePath().normalize(),
                        paths.resolve(absoluteTarget.toString()), "an absolute value is never re-based");

                Assert.assertEquals(home.resolve("conf/profiles"), paths.profilesDir(AppConfig.empty()),
                        "the documented default applies when profiles.dir is absent");
                Assert.assertEquals(home.resolve("conf/secrets"), paths.secretsDir(AppConfig.empty()),
                        "the documented default applies when secrets.dir is absent");
                Assert.assertNull(paths.classificationsFile(AppConfig.empty()),
                        "an absent classifications.file has no invented default");
                Assert.assertNull(paths.resolve("   "), "a blank value means unset, not the home itself");
            });
        } finally {
            TestSupport.deleteRecursively(home);
            TestSupport.deleteRecursively(absoluteTarget);
        }
    }

    /** Goal 01A (D): diagnostics list every operational path, with its origin and purpose. */
    public void everyOperationalPathIsReportedWithItsOrigin() throws Exception {
        Path home = newHome("describe");
        try {
            Properties properties = new Properties();
            properties.setProperty("classifications.file", "conf/rules.properties");
            AppConfig config = AppConfig.fromProperties(properties);

            withLauncherHome(home, () -> {
                List<AppPaths.OperationalPath> described = AppPaths.resolve().describe(config);
                Assert.assertEquals(6, described.size(),
                        "profiles, classifications, secrets and the three later-goal paths are listed");
                Assert.assertEquals("profiles.dir", described.get(0).key(), "the order is stable");
                Assert.assertEquals(AppPaths.Origin.DEFAULT, described.get(0).origin(),
                        "an absent key is reported as a default");
                Assert.assertEquals(home.resolve("conf/profiles"), described.get(0).path(),
                        "the default path is home-relative");
                Assert.assertEquals("classifications.file", described.get(1).key(), "a configured file is listed");
                Assert.assertEquals(AppPaths.Origin.CONFIGURED, described.get(1).origin(),
                        "a present key is reported as configured");
                Assert.assertTrue(described.get(3).reservedForLaterGoal(), "data.dir is reserved for a later goal");
                Assert.assertTrue(described.get(4).reservedForLaterGoal(), "reports.dir is reserved for a later goal");
                Assert.assertTrue(described.get(5).reservedForLaterGoal(), "logs.dir is reserved for a later goal");
                Assert.assertFalse(described.get(0).reservedForLaterGoal(), "profiles.dir is read now");
                for (AppPaths.OperationalPath entry : described) {
                    Assert.assertTrue(entry.path().isAbsolute(),
                            "every listed path is absolute: " + entry.describe());
                    Assert.assertTrue(entry.path().startsWith(home),
                            "every listed path is below the application home: " + entry.describe());
                    Assert.assertTrue(entry.describe().startsWith(entry.key() + "="),
                            "describe() names the key: " + entry.describe());
                }
            });
        } finally {
            TestSupport.deleteRecursively(home);
        }
    }

    /**
     * Goal 01A (D): {@code CM_INSIGHT_HOME} is honoured END TO END by the real entry point, and the
     * {@code -Dcminsight.home} property the launcher passes still wins over it.
     *
     * <p>This is the committed form of the D acceptance criterion. The rest of this class cannot test the
     * environment branch at all: a JVM cannot change its own environment, so that branch was only ever
     * asserted when the ambient variable happened to be set - and {@code ./build.sh} does not set it, so a
     * regression that ignored {@code CM_INSIGHT_HOME} would turn nothing red. Here the real
     * {@code ConfigCheck} runs in a child JVM whose environment carries the variable, from a working
     * directory that is NOT the home, and the home it prints is asserted.
     *
     * <p>Fails against an {@code AppPaths.resolve()} that skips the environment branch: the child would
     * report the working directory instead of the {@code CM_INSIGHT_HOME} directory. The child's output is
     * redirected to a file rather than read through a pipe, so no sandbox pipe restriction can turn this
     * into a false failure, and a child that cannot be started fails the test instead of passing silently.
     */
    public void theEnvironmentHomeIsHonouredEndToEndAndThePropertyStillWins() throws Exception {
        Path environmentHome = newHome("env-home");
        Path propertyHome = newHome("property-home");
        Path callerDir = TestSupport.newTempDir("cminsight-home-caller-");
        try {
            Path config = TestSupport.writeFile(environmentHome.resolve("conf/application.properties"),
                    "web.bind=127.0.0.1\nweb.port=8080\n");

            String fromEnvironment = runConfigCheck(List.of(),
                    Map.of(AppPaths.HOME_ENV, environmentHome.toString()), config, callerDir);
            Assert.assertTrue(fromEnvironment.contains("home = " + environmentHome),
                    "the real entry point takes its home from " + AppPaths.HOME_ENV + ": " + fromEnvironment);
            Assert.assertTrue(fromEnvironment.contains("environment variable " + AppPaths.HOME_ENV),
                    "the diagnostic names the environment as the source: " + fromEnvironment);
            Assert.assertFalse(fromEnvironment.contains("home = " + callerDir),
                    "the working directory is NOT the home when the launcher supplied one: " + fromEnvironment);
            Assert.assertTrue(fromEnvironment.contains(environmentHome + File.separator + "conf"),
                    "relative paths resolve against the environment home: " + fromEnvironment);

            String fromProperty = runConfigCheck(
                    List.of("-D" + AppPaths.HOME_PROPERTY + "=" + propertyHome),
                    Map.of(AppPaths.HOME_ENV, environmentHome.toString()), config, callerDir);
            Assert.assertTrue(fromProperty.contains("home = " + propertyHome),
                    "-D" + AppPaths.HOME_PROPERTY + " wins over " + AppPaths.HOME_ENV + ": " + fromProperty);
            Assert.assertTrue(fromProperty.contains("-D" + AppPaths.HOME_PROPERTY),
                    "the diagnostic names the property as the source: " + fromProperty);
            Assert.assertFalse(fromProperty.contains("home = " + environmentHome),
                    "the environment home is not used once the property is set: " + fromProperty);
        } finally {
            TestSupport.deleteRecursively(environmentHome);
            TestSupport.deleteRecursively(propertyHome);
            TestSupport.deleteRecursively(callerDir);
        }
    }

    /**
     * Runs the real {@code ConfigCheck} entry point in a child JVM with the given JVM arguments and
     * environment additions, from {@code callerDir}, and returns everything it printed.
     *
     * <p>The output is redirected to a FILE, not read through a pipe: a sandboxed process may be denied
     * named pipes, and this must not be able to turn a green test red for an unrelated reason. The child
     * inherits this JVM's class path, so it loads exactly the classes the suite is running.
     */
    private static String runConfigCheck(List<String> jvmArguments,
                                         Map<String, String> environment,
                                         Path configFile,
                                         Path callerDir) throws IOException, InterruptedException {
        Path log = TestSupport.writeFile(callerDir.resolve("config-check-" + JVM_SEQUENCE.incrementAndGet()
                + ".log"), "");
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(jvmArguments);
        command.add("-cp");
        command.add(absoluteClassPath());
        command.add("com.mraibo.cminsight.app.ConfigCheck");
        command.add("--config");
        command.add(configFile.toString());

        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .directory(callerDir.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("the configuration-check child JVM did not finish within 60 s");
        }
        return Files.readString(log, StandardCharsets.UTF_8);
    }

    private static String javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path executable = bin.resolve("java.exe");
        if (!Files.isExecutable(executable)) {
            executable = bin.resolve("java");
        }
        return executable.toString();
    }

    /**
     * This JVM's class path with every entry made absolute.
     *
     * <p>Required because the child runs from a different working directory: the build scripts pass
     * relative entries ({@code build/test-classes:build/classes}), which would resolve against the child's
     * directory and leave {@code ConfigCheck} unloadable.
     */
    private static String absoluteClassPath() {
        String separator = File.pathSeparator;
        StringBuilder out = new StringBuilder();
        for (String entry : System.getProperty("java.class.path", "").split(Pattern.quote(separator))) {
            if (entry.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(separator);
            }
            out.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return out.toString();
    }

    /**
     * Goal 01A (D + C): {@code secrets.dir} is resolved from the application home, so a file-backed
     * credential is found even though the process is not running in the home directory.
     *
     * <p>Fails against the pre-Goal-01A code, where a relative {@code secrets.dir} was resolved against
     * the working directory: the file would not have been found at all.
     */
    public void aFileCredentialIsFoundFromTheApplicationHomeNotTheWorkingDirectory() throws Exception {
        Path home = newHome("secrets");
        try {
            Path secrets = Files.createDirectories(home.resolve("conf/secrets"));
            TestSupport.writeFile(secrets.resolve("cm-password.txt"), "File-Value-4417\n");
            Properties properties = new Properties();
            properties.setProperty("secrets.dir", "conf/secrets");
            AppConfig config = AppConfig.fromProperties(properties);

            withLauncherHome(home, () -> {
                Path resolvedDir = AppPaths.resolve().secretsDir(config);
                Assert.assertEquals(secrets, resolvedDir, "secrets.dir is resolved against the application home");
                Assert.assertFalse(resolvedDir.equals(workingDirectory().resolve("conf/secrets")),
                        "the resolved directory is not the working-directory spelling");

                SecretResolver resolver = new SecretResolver(Map.of(), resolvedDir);
                SecretRef reference = RepositoryProfile.credentialFromSecretFile(
                        RepositoryProfile.CM_PASSWORD_KEY, "cm-password.txt");
                SecretRef resolved = resolver.classify(null, reference.locator(), null,
                        RepositoryProfile.CM_PASSWORD_KEY);
                Assert.assertTrue(resolved.resolved(),
                        "a file below the resolved secrets directory is found: " + resolved.describe());
                Assert.assertEquals("File-Value-4417", resolver.resolve(resolved),
                        "the adapter receives the file-backed value");
            });
        } finally {
            TestSupport.deleteRecursively(home);
        }
    }
}
