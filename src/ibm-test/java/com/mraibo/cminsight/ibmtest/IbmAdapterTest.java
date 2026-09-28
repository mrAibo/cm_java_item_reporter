package com.mraibo.cminsight.ibmtest;

import com.mraibo.cminsight.ibm.internal.IbmCmSessionFactoryVerdictTest;
import com.mraibo.cminsight.ibm.internal.IbmCleanupVerdictTest;
import com.mraibo.cminsight.ibm.internal.IbmMappingRulesTest;
import com.mraibo.cminsight.ibm.internal.IbmProviderRegistrationTest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The IBM adapter test suite entry point, discovered by {@code build.sh} step 8/6b at exactly
 * {@code build/ibm-test-classes/com/mraibo/cminsight/ibmtest/IbmAdapterTest.class}.
 *
 * <h2>Why this tree exists separately from {@code src/test/java}</h2>
 *
 * <p>These suites exercise the adapter, so they must run on a class path that carries the SDK set (a real
 * IBM CM JAR when one is present, otherwise the signature-only compile stubs). {@code SelfTest} stays
 * core-only on purpose, so a broken IBM source set can never make the Goal 01 suites un-runnable. The
 * consequence is that the core suite does not register these classes and this runner does not need it.
 *
 * <h2>Contract, mirrored from {@code SelfTest} on purpose</h2>
 *
 * <ul>
 *   <li>every class in {@link #TEST_CLASSES} is instantiated through its public no-argument constructor and
 *       every public no-argument {@code void} method is run as a test;</li>
 *   <li>one {@code PASS}/{@code FAIL} line per test, and the final line is {@code Tests run: N, failures: M};</li>
 *   <li>the exit code is 0 only when at least one test ran and none failed, so a silently empty suite can
 *       never look like a pass;</li>
 *   <li>each test runs on a daemon worker with a 30-second wall clock timeout, so a hung test fails the
 *       build instead of hanging it.</li>
 * </ul>
 *
 * <p>The test classes themselves live in {@code com.mraibo.cminsight.ibm.internal} because that is where the
 * package-private seams they must drive live: {@code IbmCmConnectionFactory} and {@code IcmDatastore} are
 * deliberately not public, since they are the adapter's vendor boundary and nothing outside the adapter may
 * supply a physical connection. A runner in a different package is therefore not a style choice - it is what
 * keeps those seams closed while still letting the build find the entry point at a fixed path.
 *
 * <h2>No live server</h2>
 *
 * <p>No test here talks to an IBM CM server, and none may claim live validation: every physical interaction
 * is a fake behind {@code IcmDatastore}. The suite proves the adapter's DECISIONS - the cleanup verdict, the
 * teardown rules and the health probe's cost - which are exactly the parts a live server cannot be asked to
 * produce on demand.
 */
public final class IbmAdapterTest {

    /** Wall clock allowed for one test. */
    private static final int PER_TEST_TIMEOUT_SECONDS = 30;

    private static final Duration PER_TEST_TIMEOUT = Duration.ofSeconds(PER_TEST_TIMEOUT_SECONDS);

    /** Every IBM adapter suite. Order is explicit so successive runs print identical output. */
    private static final List<Class<?>> TEST_CLASSES = List.of(
            IbmProviderRegistrationTest.class,
            IbmCmSessionFactoryVerdictTest.class,
            IbmCleanupVerdictTest.class,
            IbmMappingRulesTest.class);

    private IbmAdapterTest() {
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

        if (run == 0) {
            System.out.println("FAIL  the IBM adapter suite registered no tests at all; refusing to report a pass");
            failures++;
        }
        System.out.println("Tests run: " + run + ", failures: " + failures);
        System.out.flush();
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

    /** Runs one test on a bounded daemon worker; returns the failure, or {@code null} when it passed. */
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
        }, "ibmtest-" + testClass.getSimpleName() + "." + test.getName());
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
