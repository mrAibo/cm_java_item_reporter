package com.mraibo.cminsight.test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
            RepositoryManagerTest.class,
            RepositoryClosePropagationTest.class,
            StatisticsContractTest.class,
            RouterTest.class,
            WebServerSocketTest.class);

    private SelfTest() {
    }

    public static void main(String[] args) {
        long startedNanos = System.nanoTime();
        int run = 0;
        int failures = 0;

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

    /** Public no-argument {@code void} methods declared by the test class, in stable name order. */
    private static List<Method> discover(Class<?> testClass) {
        List<Method> tests = new ArrayList<>();
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
