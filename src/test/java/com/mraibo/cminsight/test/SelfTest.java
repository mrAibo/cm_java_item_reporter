package com.mraibo.cminsight.test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The dependency-free test suite entry point used by {@code build.sh} and {@code tests/selftest.sh}.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>every test class in {@link #TEST_CLASSES} is instantiated through its public no-argument
 *       constructor and every public no-argument {@code void} method is run as a test;</li>
 *   <li>one {@code PASS}/{@code FAIL} line is printed per test, and the final line is
 *       {@code Tests run: N, failures: M};</li>
 *   <li>the exit code is 0 only when at least one test ran and none failed, and 1 otherwise, so a
 *       silently empty suite can never look like a pass;</li>
 *   <li>each test runs on a daemon worker with a {@value #PER_TEST_TIMEOUT_SECONDS}-second wall clock
 *       timeout, so a hung concurrency test fails the build instead of hanging it.</li>
 * </ul>
 *
 * <p>Test order is explicit (the class list) plus alphabetical within a class, so successive runs
 * print identical output.
 */
public final class SelfTest {

    /** Wall clock allowed for one test. */
    private static final int PER_TEST_TIMEOUT_SECONDS = 30;

    private static final Duration PER_TEST_TIMEOUT = Duration.ofSeconds(PER_TEST_TIMEOUT_SECONDS);

    /** Every suite of this package. Longest-running suites are spread out, order is irrelevant. */
    private static final List<Class<?>> TEST_CLASSES = List.of(
            AppConfigTest.class,
            SecretResolverTest.class,
            RepositoryProfileLoaderTest.class,
            RepositoryCredentialsTest.class,
            AppPathsTest.class,
            ConfigCheckTest.class,
            ClassificationRulesTest.class,
            FeatureRegistryTest.class,
            SecurityPolicyTest.class,
            AuthenticationTest.class,
            BoundedPoolTest.class,
            BoundedPoolLifecycleTest.class,
            BoundedPoolHardeningTest.class,
            UncertainCreationTest.class,
            ActivationCleanupTest.class,
            MetadataCacheTest.class,
            CmPoolDiagnosticsPassThroughTest.class,
            RepositoryManagerTest.class,
            RepositoryClosePropagationTest.class,
            RepositorySwitchQuiescenceTest.class,
            StatisticsContractTest.class,
            StatisticsPublicationTest.class,
            JdbcPoolFactoryTest.class,
            JdbcSqlDialectTest.class,
            ScanCoordinatorTest.class,
            ScanGateLatchTest.class,
            AnchorDeadlineCancellationTest.class,
            SqlAdmissionTest.class,
            JdbcQueryAdmissionRuntimeTest.class,
            JdbcSessionSchemaHealthTest.class,
            StatisticsApiSecurityTest.class,
            AnalyticsSourceReadOnlyGuardTest.class,
            RouterTest.class,
            RouterActionGuardTest.class,
            WebServerSocketTest.class,
            CmApiRoutesInstallTest.class,
            ProviderDiscoveryTest.class,
            CoreIbmIsolationTest.class,
            IbmSourceReadOnlyGuardTest.class,
            ScriptPermissionTest.class,
            // Goal 04: cache/history, freshness, targeted refresh, reports and the API/UI security controls.
            // Every one of these is registered here, because a suite in this package that is not listed makes
            // the run fail on purpose (see unregisteredSuites below).
            HistoryPublicationBoundaryTest.class,
            HistoryPersistenceTest.class,
            StatisticsFreshnessTest.class,
            TargetedRefreshConcurrencyTest.class,
            ReportContentSecurityTest.class,
            ReportOutputConfinementTest.class,
            Goal04ApiSecurityTest.class,
            OperatorUiOfflineAssetTest.class);

    private SelfTest() {
    }

    public static void main(String[] args) {
        long startedNanos = System.nanoTime();
        int run = 0;
        int failures = 0;

        // Before anything runs: refuse to report a pass while a suite in this package is not in the list.
        // A test class that exists but is never registered does not run, does not fail, and does not
        // change any count - the suite simply reports success without it. That happened for real in this
        // project: four committed suites, including an isolation guard and a POST action-guard suite,
        // sat unregistered and had never executed a single assertion, while the build reported green.
        // Evidence that does not run is not evidence, so the omission is now an error rather than a
        // silent absence.
        List<String> unregistered = unregisteredSuites();
        for (String name : unregistered) {
            System.out.println("FAIL  " + name + ".<registration>: the class exists in this package but is not"
                    + " in SelfTest.TEST_CLASSES, so it would never run");
            failures++;
        }

        for (Class<?> testClass : TEST_CLASSES) {
            List<Method> tests = discover(testClass);
            if (tests.isEmpty()) {
                System.out.println("FAIL  " + testClass.getSimpleName() + ".<suite>: no test methods were found");
                failures++;
                continue;
            }
            for (Method test : tests) {
                run++;
                String name = testClass.getSimpleName() + "." + test.getName();
                Throwable failure = runOne(testClass, test);
                if (failure == null) {
                    System.out.println("PASS  " + name);
                } else {
                    failures++;
                    System.out.println("FAIL  " + name + ": " + describe(failure));
                }
            }
        }

        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
        if (run == 0) {
            System.out.println("FAIL  the suite registered no tests at all; refusing to report a pass");
            failures++;
        }
        System.out.println("Tests run: " + run + ", failures: " + failures);
        System.out.flush();
        if (elapsedMillis > 60_000L) {
            System.err.println("cm-insight tests: the suite took " + elapsedMillis
                    + " ms, which is longer than the 60 s budget");
        }
        System.exit(run > 0 && failures == 0 ? 0 : 1);
    }

    /**
     * Test classes present in this package that {@link #TEST_CLASSES} does not list, and that look like
     * suites rather than helpers.
     *
     * <p>A class is reported only when it would have been RUN had it been registered: it must be
     * {@code public} with a public no-argument constructor and declare at least one public no-argument
     * {@code void} method, which is exactly the shape {@link #discover(Class)} looks for. That keeps every
     * legitimate helper out of the result - {@code Assert}, {@code TestSupport}, {@code FakeResource},
     * {@code FakeTransport} and the rest are package-private or fixture types with no test methods, so
     * they are not suites and never appear here. Requiring the full shape rather than "is public" matters:
     * a rule that flagged helpers would be satisfied by adding noise to an allow-list, and the list would
     * stop meaning anything.
     *
     * <p>The package is enumerated through the CLASS LOADER rather than a directory walk, so the check
     * still works when the suite is run from a jar or with a class path that has no source tree beside it.
     * If the package cannot be enumerated at all the check reports nothing - it is a guard against a
     * forgotten registration, not a replacement for running the tests, and a guard that cannot see the
     * package must not invent a failure.
     */
    private static List<String> unregisteredSuites() {
        String packageName = SelfTest.class.getPackageName();
        String packagePath = packageName.replace('.', '/');
        List<String> unregistered = new ArrayList<>();
        try {
            ClassLoader loader = SelfTest.class.getClassLoader();
            Enumeration<URL> roots = loader.getResources(packagePath);
            Set<String> examined = new TreeSet<>();
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if (!"file".equals(root.getProtocol())) {
                    // A jar has no listable entries through this URL; skip rather than guess.
                    continue;
                }
                Path directory = Path.of(root.toURI());
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.class")) {
                    for (Path entry : entries) {
                        String fileName = entry.getFileName().toString();
                        if (fileName.contains("$")) {
                            continue; // a nested or anonymous class belongs to its outer class
                        }
                        String simpleName = fileName.substring(0, fileName.length() - ".class".length());
                        if (!examined.add(simpleName)) {
                            continue;
                        }
                        if (isUnregisteredSuite(packageName + "." + simpleName)) {
                            unregistered.add(packageName + "." + simpleName);
                        }
                    }
                }
            }
        } catch (IOException | URISyntaxException | RuntimeException | LinkageError ignored) {
            return List.of();
        }
        return List.copyOf(unregistered);
    }

    /** True when this class name is a runnable suite that {@link #TEST_CLASSES} does not list. */
    private static boolean isUnregisteredSuite(String className) {
        for (Class<?> registered : TEST_CLASSES) {
            if (registered.getName().equals(className)) {
                return false;
            }
        }
        try {
            Class<?> candidate = Class.forName(className, false, SelfTest.class.getClassLoader());
            if (!Modifier.isPublic(candidate.getModifiers()) || candidate.isInterface()
                    || Modifier.isAbstract(candidate.getModifiers())) {
                return false;
            }
            if (candidate.getDeclaredConstructor().getModifiers() != Modifier.PUBLIC) {
                return false;
            }
            return !discover(candidate).isEmpty();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError notASuite) {
            // Not loadable as a suite: either a helper this check cannot instantiate or something that is
            // not a test at all. Reporting it would be a false alarm, so it is left alone.
            return false;
        }
    }

    /** Public no-argument {@code void} methods declared by the test class, in stable name order. */
    private static List<Method> discover(Class<?> testClass) {        List<Method> tests = new ArrayList<>();
        for (Method method : testClass.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getParameterCount() != 0 || method.getReturnType() != void.class) {
                continue;
            }
            tests.add(method);
        }
        tests.sort(Comparator.comparing(Method::getName));
        return tests;
    }

    /**
     * Runs one test on a bounded daemon worker.
     *
     * @return the failure, or {@code null} when the test passed
     */
    private static Throwable runOne(Class<?> testClass, Method test) {
        final Object instance;
        try {
            instance = testClass.getDeclaredConstructor().newInstance();
        } catch (Throwable constructionFailure) {
            return unwrap(constructionFailure);
        }

        Throwable[] failure = new Throwable[1];
        Thread worker = new Thread(() -> {
            try {
                test.invoke(instance);
            } catch (InvocationTargetException e) {
                failure[0] = e.getCause() == null ? e : e.getCause();
            } catch (Throwable thrown) {
                failure[0] = thrown;
            }
        }, "selftest-" + testClass.getSimpleName() + "." + test.getName());
        worker.setDaemon(true);
        worker.start();

        try {
            worker.join(PER_TEST_TIMEOUT.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new AssertionError("the test runner was interrupted while running " + test.getName());
        }
        if (worker.isAlive()) {
            worker.interrupt();
            return new AssertionError("timed out after " + PER_TEST_TIMEOUT_SECONDS + "s");
        }
        return failure[0];
    }

    private static Throwable unwrap(Throwable thrown) {
        if (thrown instanceof InvocationTargetException && thrown.getCause() != null) {
            return thrown.getCause();
        }
        return thrown;
    }

    private static String describe(Throwable failure) {
        Throwable root = unwrap(failure);
        String type = root.getClass().getSimpleName();
        String message = root.getMessage();
        String text = message == null || message.isBlank() ? type : type + ": " + message;
        return text.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
