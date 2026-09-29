package com.mraibo.cminsight.ibmtest;

import com.mraibo.cminsight.ibm.internal.IbmCmPreAllocationCleanTest;
import com.mraibo.cminsight.ibm.internal.IbmCmSessionFactoryVerdictTest;
import com.mraibo.cminsight.ibm.internal.IbmClassificationProvenanceTest;
import com.mraibo.cminsight.ibm.internal.IbmCleanupVerdictTest;
import com.mraibo.cminsight.ibm.internal.IbmConnectCleanupRuleTest;
import com.mraibo.cminsight.ibm.internal.IbmMappingRulesTest;
import com.mraibo.cminsight.ibm.internal.IbmProviderRegistrationTest;
import com.mraibo.cminsight.ibm.internal.IbmSessionPoisoningTest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
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
 *   <li>a runnable suite compiled into {@code com.mraibo.cminsight.ibm.internal} that
 *       {@link #TEST_CLASSES} does not list FAILS the run instead of silently not running - see
 *       {@link IbmSuiteRegistration} and {@code unregisteredSuites} below;</li>
 *   <li>one {@code PASS}/{@code FAIL} line per test, and the final line is {@code Tests run: N, failures: M};</li>
 *   <li>the exit code is 0 only when at least one test ran and none failed, so a silently empty suite can
 *       never look like a pass;</li>
 *   <li>each test runs on a daemon worker with a 30-second wall clock timeout, so a hung test fails the
 *       build instead of hanging it.</li>
 * </ul>
 *
 * <h2>Why the list stays explicit, and how its omission became loud</h2>
 *
 * <p>The list is kept rather than replaced by discovery, because the run ORDER is part of this runner's
 * contract (successive runs print identical output) and because the list is the human-auditable inventory of
 * what the IBM evidence consists of. The defect Goal 02A found was never the existence of a list - it was
 * that forgetting one entry was indistinguishable from having nothing to forget: the suite simply did not
 * run and the build stayed green.
 *
 * <p>{@link IbmSuiteRegistration} closes that hole. It reads the COMPILED IBM test tree - not a second
 * hand-written source list, which could drift from this one - and fails the run for every runnable suite
 * ({@code public}, concrete, public no-argument constructor, at least one public no-argument {@code void}
 * method, which is exactly what {@link IbmSuiteRegistration#discoverTests(Class)} accepts) that
 * {@link #TEST_CLASSES} omits. It also fails when it cannot read a root at all, so "the guard could not
 * check" can never be mistaken for "checked and clean". Adding a suite is therefore still a deliberate act -
 * and forgetting it is a red build, not a missing assertion.
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
            IbmConnectCleanupRuleTest.class,
            IbmSessionPoisoningTest.class,
            IbmClassificationProvenanceTest.class,
            IbmMappingRulesTest.class,
            IbmCmPreAllocationCleanTest.class);

    private IbmAdapterTest() {
    }

    public static void main(String[] args) {
        long startedNanos = System.nanoTime();
        int run = 0;
        int failures = 0;

        // Before anything runs: refuse to report a pass while a runnable suite in the IBM suite package is
        // not in the list above. A test class that exists but is never registered does not run, does not
        // fail, and does not change any count - the suite simply reports success without it. That happened
        // for real in this project on the CORE runner: four committed suites, including the Java half of the
        // read-only guarantee, sat unregistered for a whole goal and had never executed an assertion while
        // the build reported green. Evidence that does not run is not evidence, so the omission is an error
        // here too rather than a silent absence.
        IbmSuiteRegistration.Check registration = IbmSuiteRegistration.check(TEST_CLASSES);
        for (String name : registration.unregisteredSuites()) {
            System.out.println("FAIL  " + name + ".<registration>: the class is compiled into "
                    + IbmSuiteRegistration.SUITE_PACKAGE + " but is not in IbmAdapterTest.TEST_CLASSES, so it"
                    + " would never run");
            failures++;
        }
        // A root the guard could not read is a FAILURE, not a warning: registration was not verified there,
        // and an unchecked tree may hold exactly the suite this guard exists to find.
        for (String unreadable : registration.unreadableRoots()) {
            System.out.println("FAIL  " + IbmSuiteRegistration.SUITE_PACKAGE + ".<registration>: the suite"
                    + " registration could not be verified against " + unreadable + "; refusing to report a"
                    + " pass while an unlisted IBM suite could be hiding there");
            failures++;
        }

        for (Class<?> testClass : TEST_CLASSES) {
            List<Method> tests = IbmSuiteRegistration.discoverTests(testClass);
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
