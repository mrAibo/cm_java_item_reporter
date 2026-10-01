package com.mraibo.cminsight.ibmtest;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The registration guard for {@link IbmAdapterTest}: it answers "is a runnable IBM suite compiled into
 * {@link #SUITE_PACKAGE} that the runner's explicit list does not run?".
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Goal 02A found the real failure this guards against: four committed core suites - including the Java
 * half of the read-only guarantee and a POST action guard - had existed for a whole goal, been reviewed as
 * coverage, and never executed a single assertion, because the runner's class list did not name them. A
 * suite that is not registered does not run, does not fail and does not change any count, so the build
 * reports green while the evidence is simply absent. {@code SelfTest.unregisteredSuites()} now makes that an
 * error for the core runner; this is the same protection for the IBM runner, which carried the same manual
 * list and the same silence.
 *
 * <h2>What counts as a suite, and why the whole shape is required</h2>
 *
 * <p>A class is reported only when it is exactly what the runner would have RUN had it been listed: public,
 * concrete, with a public no-argument constructor, and with at least one public no-argument {@code void}
 * method. That predicate is not re-implemented here - it is {@link #discoverTests(Class)}, the SAME method
 * the runner uses to enumerate the tests of a registered suite. A guard that carried its own copy of the
 * shape could drift from the runner it guards, which is the defect this file exists to remove; a guard that
 * accepted "is public" would flag the package's legitimate helpers and would then be satisfied by padding
 * the list with noise until the list meant nothing.
 *
 * <p>The package's helpers are therefore never reported, and deliberately so:
 *
 * <ul>
 *   <li>{@code Assert} is a package-private final class with a private constructor and only static
 *       assertion methods. It is not public, cannot be instantiated, and has no instance test method.</li>
 *   <li>{@code IbmFakes} is a package-private final class with a private constructor; the fakes it exposes
 *       are nested classes, and nested class files are skipped by name ({@code Outer$Inner}) because a
 *       nested type belongs to its outer class and is never a suite the runner could instantiate on its
 *       own.</li>
 * </ul>
 *
 * <p>A future public fixture that happens to carry a public no-argument {@code void} method WOULD be
 * reported. That is deliberate and matches the core guard: the remedy is to register it or to give it the
 * shape of a helper (package-private, or a constructor that takes its collaborators), and either way the
 * author is told instead of the suite silently not running.
 *
 * <h2>Where the guard looks, and why it does not mistake the adapter for a suite</h2>
 *
 * <p>{@code com.mraibo.cminsight.ibm.internal} is a SPLIT package: the adapter's own internals
 * ({@code IbmCmSession}, {@code IbmCmSessionFactory}, ...) are compiled into {@code build/ibm-classes},
 * while the suites of the same package are compiled into {@code build/ibm-test-classes}. Enumerating "the
 * package" through the class loader would therefore return BOTH roots, and an adapter class that happened to
 * match the suite shape would be reported as an unregistered suite.
 *
 * <p>So the scan is anchored to the IBM TEST output root by construction: the roots of the RUNNER's own
 * package ({@code com.mraibo.cminsight.ibmtest}) are enumerated, and {@link #SUITE_PACKAGE} is read from
 * those roots. That root is precisely the one {@code build.sh} requires the entry point to exist in
 * ({@code build/ibm-test-classes/com/mraibo/cminsight/ibmtest/IbmAdapterTest.class}), and it is the root the
 * suite sources are compiled into. The production adapter root is never scanned, so no second
 * allow-list of "these are production classes, not suites" is needed - a duplicate list would be able to
 * drift from the very list it protects.
 *
 * <h2>Directory roots, jar roots, and the SDK path</h2>
 *
 * <p>The guard is class-path-shape agnostic, and it is explicit about every root it cannot read:
 *
 * <ul>
 *   <li><b>Directory root</b> ({@code file:}, the {@code build.sh} case): the suite directory is listed with
 *       {@code *.class}. This is the case on BOTH the committed-stub class path and a real IBM CM 8.7 SDK
 *       class path - the SDK set only changes what the suites LINK against, never where the suite classes
 *       themselves are compiled, so the stub and real-SDK runs enumerate identically.</li>
 *   <li><b>Jar root</b> ({@code jar:}, e.g. an operator running the IBM suites out of a packaged test jar):
 *       the jar is opened and its entries under {@link #SUITE_PACKAGE} are enumerated. This branch exists
 *       because a jar has no listable directory entries - the naive version of this check would find
 *       nothing, report no unregistered suite, and pass. That is exactly the silent success this guard
 *       exists to remove, so it is handled rather than skipped.</li>
 *   <li><b>Any other protocol, an unreadable root, or a root with no suite package beside the runner</b>:
 *       reported as unreadable, and the runner FAILS. A guard that cannot see the package has not verified
 *       anything, and "could not check" must not be indistinguishable from "checked and clean".</li>
 * </ul>
 *
 * <p>A class file that exists but cannot be loaded is not reported: it cannot be instantiated, so it is not
 * a suite that would have run, and reporting it would be a false alarm. This is the same choice the core
 * guard makes.
 */
final class IbmSuiteRegistration {

    /** The package the IBM adapter suites are compiled into. */
    static final String SUITE_PACKAGE = "com.mraibo.cminsight.ibm.internal";

    /** The runner's own package; its compiled roots are the IBM test output roots. */
    static final String RUNNER_PACKAGE = "com.mraibo.cminsight.ibmtest";

    private static final String SUITE_PACKAGE_PATH = SUITE_PACKAGE.replace('.', '/');

    private static final String RUNNER_PACKAGE_PATH = RUNNER_PACKAGE.replace('.', '/');

    private static final int RUNNER_PACKAGE_SEGMENTS = RUNNER_PACKAGE.split("\\.").length;

    private static final String CLASS_SUFFIX = ".class";

    private IbmSuiteRegistration() {
    }

    /**
     * The outcome of one registration check.
     *
     * @param unregisteredSuites runnable suites in {@link #SUITE_PACKAGE} that the runner's list omits,
     *                           sorted; each one is a suite that would never have run
     * @param unreadableRoots    class path roots the check could not inspect, so registration could not be
     *                           verified against them; each one is a reason the check is not evidence
     */
    record Check(List<String> unregisteredSuites, List<String> unreadableRoots) {
    }

    /**
     * Checks the compiled IBM test tree against {@code registered}.
     *
     * @param registered the runner's explicit suite list ({@code IbmAdapterTest.TEST_CLASSES}); passed in
     *                   rather than duplicated, so this class can never hold a second list that drifts
     */
    static Check check(List<Class<?>> registered) {
        Set<String> unregistered = new TreeSet<>();
        List<String> unreadable = new ArrayList<>();
        Set<String> seenRoots = new HashSet<>();
        boolean sawRoot = false;
        try {
            ClassLoader loader = IbmSuiteRegistration.class.getClassLoader();
            Enumeration<URL> roots = loader.getResources(RUNNER_PACKAGE_PATH);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if (!seenRoots.add(root.toExternalForm())) {
                    continue;
                }
                sawRoot = true;
                collect(root, registered, unregistered, unreadable);
            }
        } catch (IOException | RuntimeException | LinkageError failure) {
            unreadable.add(RUNNER_PACKAGE + " (the class loader could not enumerate it: " + failure + ")");
            return new Check(List.copyOf(unregistered), List.copyOf(unreadable));
        }
        if (!sawRoot) {
            unreadable.add(RUNNER_PACKAGE + " (the class loader reported no root for it at all)");
        }
        return new Check(List.copyOf(unregistered), List.copyOf(unreadable));
    }

    /** Reads one compiled root of the runner package and records what it contributes. */
    private static void collect(URL runnerRoot, List<Class<?>> registered, Set<String> unregistered,
                                List<String> unreadable) {
        String protocol = runnerRoot.getProtocol();
        if ("file".equals(protocol)) {
            collectFromDirectory(runnerRoot, registered, unregistered, unreadable);
        } else if ("jar".equals(protocol)) {
            collectFromJar(runnerRoot, registered, unregistered, unreadable);
        } else {
            unreadable.add(runnerRoot.toExternalForm() + " (protocol " + protocol
                    + " cannot be enumerated for a registration check)");
        }
    }

    /** The {@code build.sh} case: the suite classes sit beside the runner in a directory. */
    private static void collectFromDirectory(URL runnerRoot, List<Class<?>> registered,
                                             Set<String> unregistered, List<String> unreadable) {
        final Path outputRoot;
        try {
            Path packageDirectory = Path.of(runnerRoot.toURI());
            Path walk = packageDirectory;
            for (int i = 0; i < RUNNER_PACKAGE_SEGMENTS && walk != null; i++) {
                walk = walk.getParent();
            }
            if (walk == null) {
                unreadable.add(runnerRoot.toExternalForm()
                        + " (the class path root above the runner package could not be determined)");
                return;
            }
            outputRoot = walk;
        } catch (URISyntaxException | RuntimeException failure) {
            unreadable.add(runnerRoot.toExternalForm() + " (not readable as a directory: " + failure + ")");
            return;
        }

        Path suiteDirectory = outputRoot.resolve(SUITE_PACKAGE_PATH);
        if (!Files.isDirectory(suiteDirectory)) {
            unreadable.add(outputRoot.toString() + " (" + SUITE_PACKAGE + " was not compiled next to "
                    + RUNNER_PACKAGE + ", so its registration cannot be verified)");
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(suiteDirectory, "*" + CLASS_SUFFIX)) {
            for (Path entry : entries) {
                String simpleName = suiteSimpleName(entry.getFileName().toString());
                if (simpleName != null) {
                    consider(SUITE_PACKAGE + "." + simpleName, registered, unregistered);
                }
            }
        } catch (IOException | RuntimeException failure) {
            unreadable.add(suiteDirectory.toString() + " (" + failure + ")");
        }
    }

    /** The packaged case: enumerate the jar's entries, because a jar has no listable directories. */
    private static void collectFromJar(URL runnerRoot, List<Class<?>> registered, Set<String> unregistered,
                                       List<String> unreadable) {
        try {
            URLConnection connection = runnerRoot.openConnection();
            if (!(connection instanceof JarURLConnection jarConnection)) {
                unreadable.add(runnerRoot.toExternalForm() + " (a jar: URL that is not a JarURLConnection)");
                return;
            }
            // No cache: the jar is opened to be read here and closed again below.
            jarConnection.setUseCaches(false);
            try (JarFile jar = jarConnection.getJarFile()) {
                String prefix = SUITE_PACKAGE_PATH + "/";
                boolean sawSuitePackage = false;
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String entryName = entries.nextElement().getName();
                    if (!entryName.startsWith(prefix)) {
                        continue;
                    }
                    sawSuitePackage = true;
                    String simpleName = suiteSimpleName(entryName.substring(prefix.length()));
                    if (simpleName != null) {
                        consider(SUITE_PACKAGE + "." + simpleName, registered, unregistered);
                    }
                }
                if (!sawSuitePackage) {
                    unreadable.add(jar.getName() + " (no " + SUITE_PACKAGE + " entries were packaged next to "
                            + RUNNER_PACKAGE + ", so its registration cannot be verified)");
                }
            }
        } catch (IOException | RuntimeException failure) {
            unreadable.add(runnerRoot.toExternalForm() + " (not readable as a jar: " + failure + ")");
        }
    }

    /**
     * The simple class name of a class file entry, or {@code null} when the entry is not a top level class.
     *
     * <p>A nested or anonymous class ({@code Outer$Inner.class}) belongs to its outer class and is never a
     * suite the runner could instantiate on its own, so it is skipped by name exactly as the core guard
     * skips it.
     */
    private static String suiteSimpleName(String fileName) {
        if (!fileName.endsWith(CLASS_SUFFIX) || fileName.contains("$")) {
            return null;
        }
        String simpleName = fileName.substring(0, fileName.length() - CLASS_SUFFIX.length());
        return simpleName.isEmpty() ? null : simpleName;
    }

    /** Records {@code className} when it is a runnable suite that {@code registered} does not list. */
    private static void consider(String className, List<Class<?>> registered, Set<String> unregistered) {
        if (isUnregisteredSuite(className, registered)) {
            unregistered.add(className);
        }
    }

    /** True when this class name is a runnable suite that {@code registered} does not list. */
    private static boolean isUnregisteredSuite(String className, List<Class<?>> registered) {
        for (Class<?> registeredClass : registered) {
            if (registeredClass.getName().equals(className)) {
                return false;
            }
        }
        try {
            Class<?> candidate = Class.forName(className, false, IbmSuiteRegistration.class.getClassLoader());
            if (!Modifier.isPublic(candidate.getModifiers()) || candidate.isInterface()
                    || Modifier.isAbstract(candidate.getModifiers())) {
                return false;
            }
            if (!Modifier.isPublic(candidate.getDeclaredConstructor().getModifiers())) {
                return false;
            }
            return !discoverTests(candidate).isEmpty();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError notASuite) {
            // Not loadable as a suite: a helper this check cannot instantiate, or something that is not a
            // test at all. Reporting it would be a false alarm, so it is left alone.
            return false;
        }
    }

    /**
     * Public no-argument {@code void} methods declared by the test class, in stable name order.
     *
     * <p>This is the runner's own definition of "a test", and the registration guard deliberately shares it
     * instead of restating it: the guard reports exactly the classes the runner would have run.
     */
    static List<Method> discoverTests(Class<?> testClass) {
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
}
