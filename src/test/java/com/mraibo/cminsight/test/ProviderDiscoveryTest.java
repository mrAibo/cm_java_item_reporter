package com.mraibo.cminsight.test;

import com.mraibo.cminsight.app.Main;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.ibm.CmAdapterProvider;
import com.mraibo.cminsight.ibm.CmAdapterSettings;
import com.mraibo.cminsight.ibm.IbmCmAdapterRegistry;
import com.mraibo.cminsight.repository.ActivationFailedException;
import com.mraibo.cminsight.repository.RepositoryContextFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Goal 02 section B: the provider boundary, and the startup decision that depends on it.
 *
 * <h2>What is actually at risk here</h2>
 *
 * <p>Discovery is a {@link java.util.ServiceLoader} lookup, which means the verdict is a property of the
 * CLASSPATH and not of any code path a handler test can reach. Two failure modes matter and neither shows up
 * as an exception:
 *
 * <ul>
 *   <li><strong>A silent pick.</strong> Two providers installed is an ambiguous classpath; choosing one would
 *       activate a repository through an adapter nobody selected. It must be reported AMBIGUOUS and refuse.</li>
 *   <li><strong>A placeholder context.</strong> Auto-activation configured with no usable adapter must FAIL
 *       startup. Publishing an empty context instead would serve a console reporting a healthy repository
 *       that nobody can read - worse than refusing to start, because it looks like it works.</li>
 * </ul>
 *
 * <p>The statuses are driven through {@link IbmCmAdapterRegistry#discover(ClassLoader)} with a loader that
 * presents a chosen service descriptor over the application classpath, which is exactly how the real lookup
 * sees the world: the verdict is decided by what
 * {@code META-INF/services/com.mraibo.cminsight.ibm.CmAdapterProvider} contains.
 *
 * <h2>Why this suite needs no adapter on its class path</h2>
 *
 * <p>It asserts the registry's DECISION, which is a pure function of the descriptors it finds, so every case
 * supplies its own descriptor. The complementary property - that this build's own registration is present and
 * resolves to the IBM provider - needs the adapter's classes and therefore lives in {@code src/ibm-test},
 * where the class path carries them: {@code IbmProviderRegistrationTest}. Keeping the two apart is what lets
 * this suite run inside the core suite, which is deliberately adapter-free.
 */
public class ProviderDiscoveryTest {

    /** The service descriptor ServiceLoader reads for the adapter seam. */
    private static final String SERVICE_RESOURCE =
            "META-INF/services/" + CmAdapterProvider.class.getName();

    /**
     * No provider registered is ABSENT, and nothing may be activated through it.
     */
    public void noProviderRegisteredIsAbsentAndActivatesNothing() throws IOException {
        IbmCmAdapterRegistry registry = IbmCmAdapterRegistry.discover(isolatedLoaderWith(""));

        Assert.assertEquals(IbmCmAdapterRegistry.Availability.ABSENT, registry.status().availability(),
                "an empty service descriptor means no adapter is installed, reported ABSENT. Status: "
                        + registry.status().describe());
        Assert.assertFalse(registry.status().available(), "ABSENT is not available");
        Assert.assertTrue(registry.provider().isEmpty(), "and there is no provider to activate through");

        assertActivationRefusedWithoutAPlaceholder(registry, "with no adapter installed");
    }

    /** Two providers registered is AMBIGUOUS: an explicit refusal, never a silent pick. */
    public void twoProvidersRegisteredIsAmbiguousAndNeverSilentlyPicksOne() throws IOException {
        // Two providers that both LOAD: ambiguity is about how many are installed, not about whether one is
        // broken. A descriptor naming one good and one unloadable provider is UNAVAILABLE instead, and that
        // distinction is asserted separately below.
        IbmCmAdapterRegistry registry = IbmCmAdapterRegistry.discover(
                loaderWith(FakeProvider.class.getName() + "\n" + OtherFakeProvider.class.getName() + "\n"));

        Assert.assertEquals(IbmCmAdapterRegistry.Availability.AMBIGUOUS, registry.status().availability(),
                "two installed providers must be reported AMBIGUOUS; picking one would activate a repository"
                        + " through an adapter nobody selected. Status: " + registry.status().describe());
        Assert.assertTrue(registry.provider().isEmpty(),
                "an ambiguous classpath must expose NO provider, so no activation can proceed by accident");
        Assert.assertTrue(registry.status().refused(), "and the status is refused");
        Assert.assertFalse(registry.status().describe().isBlank(),
                "the reason is published so an operator knows to remove one adapter");

        assertActivationRefusedWithoutAPlaceholder(registry, "with an ambiguous adapter set");
    }

    /** A provider class that cannot be loaded is UNAVAILABLE, reported without a stack trace. */
    public void anUnloadableProviderIsUnavailableRatherThanAFailure() throws IOException {
        IbmCmAdapterRegistry registry = IbmCmAdapterRegistry.discover(
                loaderWith("com.mraibo.cminsight.ibm.internal.NoSuchAdapterProvider\n"));

        Assert.assertEquals(IbmCmAdapterRegistry.Availability.UNAVAILABLE, registry.status().availability(),
                "a descriptor naming a class that cannot be loaded must be reported UNAVAILABLE, so a"
                        + " mismatched build is diagnosable instead of crashing startup. Status: "
                        + registry.status().describe());
        Assert.assertTrue(registry.provider().isEmpty(), "and no provider is exposed");
        Assert.assertTrue(registry.status().refused(), "the verdict is refused");

        String description = registry.status().describe();
        Assert.assertFalse(description.contains("NoSuchAdapterProvider"),
                "the description must not publish the provider class name: it is build internals, not"
                        + " something an operator page should leak. Was: " + description);
        Assert.assertFalse(description.contains("\n") || description.contains("\r"),
                "and it must never be a stack trace, so no line break may survive into it. Was: "
                        + description);
        Assert.assertFalse(description.contains("NoClassDefFoundError")
                        || description.contains("ServiceConfigurationError"),
                "nor may it name the JDK failure type: an operator needs the actionable sentence, not the"
                        + " exception class. Was: " + description);

        assertActivationRefusedWithoutAPlaceholder(registry, "with an unloadable adapter");
    }

    /**
     * A refused verdict still produces a factory, and that factory refuses - it never yields a context.
     *
     * <p>This is the "never an empty placeholder context" rule at the level where it is enforceable: the
     * manager asks the factory for a context, and a context that exists would be reported as an active
     * repository. The assertion is therefore on the exception AND on the absence of a returned context, so a
     * factory that returned an empty-but-valid context would fail here.
     */
    public void aRefusedVerdictYieldsNoPlaceholderContext() {
        for (IbmCmAdapterRegistry.Availability availability : IbmCmAdapterRegistry.Availability.values()) {
            if (availability == IbmCmAdapterRegistry.Availability.AVAILABLE) {
                continue;
            }
            RepositoryContextFactory factory = refusingFactory(availability);
            RepositoryProfile profile = TestSupport.profile("alpha");

            ActivationFailedException failure = Assert.assertThrows(ActivationFailedException.class,
                    () -> factory.create(profile),
                    "a factory with no usable adapter (" + availability + ") must refuse to build a context"
                            + " rather than return an empty one: an empty context would be published as an"
                            + " active repository that nobody can read");
            Assert.assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("adapter"),
                    "the refusal must name the missing adapter so the operator knows what to install: "
                            + failure.getMessage());
            Assert.assertFalse(failure.hasCleanupContext(),
                    "a factory that allocated nothing must not pretend it has something to clean up");
        }
    }

    /**
     * An INSTALLED provider whose vendor runtime is not ready is reported installed and UNAVAILABLE, and
     * nothing may be built from it.
     *
     * <p>This is Goal 02A section C's core rule, and it is the distinction the review demanded be kept
     * separate: "the adapter's code is on the class path" and "a repository can be activated" are different
     * facts. The provider here loads and describes itself - so {@code providerInstalled()} must be true - and
     * answers that its runtime is not ready, so the activation verdict must be {@code UNAVAILABLE} and the
     * provider must be exposed NOWHERE. If it were exposed, a caller could build a session factory from an
     * adapter that has just said it cannot activate a repository, and the failure would move from a clean
     * refusal to a linkage error inside a pool.
     *
     * <p>{@code sessionFactory} is counted rather than asserted absent: the whole claim is that no session
     * factory and therefore no session attempt can happen on a select/check path while the runtime is
     * unavailable, and a count is what proves it.
     */
    public void anInstalledProviderWithoutAReadyRuntimeIsInstalledButUnavailable() throws IOException {
        UnreadyFakeProvider.sessionFactoryCalls.set(0);
        IbmCmAdapterRegistry registry = IbmCmAdapterRegistry.discover(
                loaderWith(UnreadyFakeProvider.class.getName() + "\n"));

        Assert.assertEquals(IbmCmAdapterRegistry.Availability.UNAVAILABLE, registry.status().availability(),
                "C: an installed provider that reports its vendor runtime not ready must be UNAVAILABLE, not"
                        + " AVAILABLE - reporting the found provider as activatable is the defect section C"
                        + " removes. Status: " + registry.status().describe());
        Assert.assertTrue(registry.status().providerInstalled(),
                "C: the adapter's CODE is installed, and that fact must stay visible so an operator can tell"
                        + " 'no adapter' from 'adapter present, runtime missing'. Status: "
                        + registry.status().describe());
        Assert.assertEquals("test-unready-adapter", registry.status().providerId(),
                "C: the installed adapter still identifies itself");
        Assert.assertTrue(registry.status().refused(), "C: the activation verdict is a refusal");
        Assert.assertTrue(registry.provider().isEmpty(),
                "C: and NO provider is exposed, so no session factory can be built from it");
        Assert.assertEquals(0, UnreadyFakeProvider.sessionFactoryCalls.get(),
                "C: discovering and reporting is not activating - no session factory was requested, so no"
                        + " session attempt can have happened");
        Assert.assertTrue(registry.status().describe().contains("installed but its runtime is not ready"),
                "C: the operator-facing sentence says installed-but-not-ready rather than 'no adapter': "
                        + registry.status().describe());
        Assert.assertEquals("installed, unavailable", registry.status().summary(),
                "C: the compact banner form distinguishes the two cases too: " + registry.status().summary());
        Assert.assertTrue(registry.status().describe().contains("test-unready"),
                "C: the reason published is the provider's own fixed sentence: " + registry.status().describe());

        assertActivationRefusedWithoutAPlaceholder(registry, "with an installed but unready adapter");
    }

    /**
     * The PACKAGED runtime with no vendor SDK and no test stubs reports the adapter installed and the
     * runtime unavailable.
     *
     * <h2>What this really loads</h2>
     *
     * <p>The packaged artifact merges {@code build/classes} with the optional source set's
     * {@code target/ibm-classes}, and it is exactly what an operator runs when the IBM jars are absent. That
     * class path is reproduced here: a loader whose parent is the APPLICATION loader - so the core and the
     * adapter resolve to this JVM's own copies of {@code CmAdapterProvider} and cannot hit a class-loader
     * identity wall - plus the compiled adapter directory, which carries the ServiceLoader descriptor.
     *
     * <p>Two things are asserted in one place, and they are the same fact from both ends: the SDK is NOT
     * loadable in that runtime, and the adapter still reports itself as installed with its runtime not
     * ready. The first half is also the assertion the goal asks for about the test-only stubs: a stub is a
     * compile input under {@code tests/ibm-stubs} and is never on a packaged runtime class path, so a build
     * that accidentally smuggled one in would make it loadable here and fail this test rather than pass as
     * a "production SDK".
     */
    public void thePackagedRuntimeWithoutTheVendorSdkIsInstalledButUnavailable() throws Exception {
        Path ibmClasses = packagedAdapterClasses();
        ClassLoader packaged = new URLClassLoader(new URL[] {ibmClasses.toUri().toURL()},
                ProviderDiscoveryTest.class.getClassLoader());

        Assert.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.ibm.mm.sdk.server.DKDatastoreICM", false, packaged),
                "C: the packaged runtime must NOT have the vendor SDK on its class path - if this loads,"
                        + " either a real SDK or a test-only stub has been packaged, and the SDK-free case"
                        + " this test is about no longer exists");
        Assert.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.ibm.mm.sdk.common.DKException", false, packaged),
                "C: and no other SDK class is visible either, so a half-visible vendor class path cannot"
                        + " make the probe answer ready");
        Assert.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.ibm.mm.sdk.server.DKDatastoreICM", false,
                        ProviderDiscoveryTest.class.getClassLoader()),
                "C: the CORE suite's own class path carries neither the SDK nor the stubs, which is the"
                        + " isolation the build enforces with a separate stub directory");

        IbmCmAdapterRegistry registry = IbmCmAdapterRegistry.discover(packaged);

        Assert.assertEquals(IbmCmAdapterRegistry.Availability.UNAVAILABLE, registry.status().availability(),
                "C: a packaged runtime with no IBM JARs must report UNAVAILABLE, not AVAILABLE - this is the"
                        + " exact build the review found advertising available=true. Status: "
                        + registry.status().describe());
        Assert.assertTrue(registry.status().providerInstalled(),
                "C: while still reporting the adapter as INSTALLED, because its classes and descriptor are"
                        + " in the artifact. Status: " + registry.status().describe());
        Assert.assertEquals("ibm-cm-8.7", registry.status().providerId(),
                "C: and it is the real IBM provider that was discovered, not a leftover registration");
        Assert.assertTrue(registry.provider().isEmpty(),
                "C: no provider may be exposed on an SDK-free runtime, so --check-repository cannot walk into"
                        + " activation and auto-activation cannot take the activation-failure path");
        Assert.assertEquals("the IBM Content Manager SDK is not on the class path; place the CM 8.7 SDK jars"
                        + " where the launcher looks for them",
                registry.status().reason(),
                "C: the published reason is the adapter's own fixed, sanitized sentence - a literal pin, so a"
                        + " rewrite of that text is a deliberate change rather than silent drift");
        Assert.assertFalse(registry.status().describe().contains("NoClassDefFoundError")
                        || registry.status().describe().contains("ClassNotFoundException"),
                "C: and it names no JDK failure type: an operator needs the action, not the exception class: "
                        + registry.status().describe());
    }

    /**
     * {@code --check-repository} returns the documented adapter-unavailable exit path when no adapter can
     * activate.
     *
     * <p>{@code Main.execute} is the production entry point minus {@code System.exit}, so the exit code
     * asserted here is the code the launcher really returns. It is reached reflectively because the method is
     * private and {@code main} terminates the JVM - calling it would end the test JVM rather than assert
     * anything. In this adapter-free suite the verdict is ABSENT; the INSTALLED-but-not-ready variant of the
     * same refusal is pinned by
     * {@link #thePackagedRuntimeWithoutTheVendorSdkIsInstalledButUnavailable}.
     */
    public void checkRepositoryReturnsTheAdapterUnavailableExitPath() throws Exception {
        Path dir = TestSupport.newTempDir("check-repository-no-adapter");
        try {
            Path config = TestSupport.writeFile(dir.resolve("application.properties"),
                    "web.bind=127.0.0.1\nweb.port=8080\n");

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            int exit;
            try {
                System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
                exit = execute(new String[] {"--config", config.toString(), "--check-repository", "crm"});
            } finally {
                System.setErr(originalErr);
            }

            Assert.assertEquals(4, exit,
                    "C: --check-repository must return the documented adapter-unavailable exit code (4) when"
                            + " no adapter can activate a repository, instead of entering activation and"
                            + " failing later. stderr: " + err);
            Assert.assertEquals(4, Main.EXIT_ADAPTER_UNAVAILABLE,
                    "C: and 4 is the constant the launcher documents for that outcome");
            String text = err.toString(StandardCharsets.UTF_8);
            Assert.assertTrue(text.contains("ERROR adapter:"),
                    "C: the refusal names the adapter as the cause: " + text);
            Assert.assertFalse(text.contains("could not create and initialize"),
                    "C: and it must NOT have reached pool initialization - that would mean the catalog was"
                            + " entered instead of refused: " + text);
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    /**
     * Auto-activation configured with no usable adapter fails explicitly as adapter/runtime unavailable.
     *
     * <p>This is the startup half of section C, and the failure mode it replaces is the worst one available:
     * publishing an empty placeholder context and serving a console that reports a healthy repository nobody
     * can read. The profile is a real one on disk, so the run reaches the auto-activation decision rather
     * than stopping at a missing profile.
     */
    public void autoActivationWithoutAUsableAdapterFailsAsAdapterUnavailable() throws Exception {
        Path dir = TestSupport.newTempDir("auto-activate-no-adapter");
        try {
            Path secrets = Files.createDirectories(dir.resolve("secrets"));
            TestSupport.writeFile(secrets.resolve("web-user.txt"), "ops\n");
            TestSupport.writeFile(secrets.resolve("web-password.txt"), "Auto-Activate-Secret-8814\n");
            Path profiles = Files.createDirectories(dir.resolve("profiles"));
            TestSupport.writeFile(profiles.resolve("crm.properties"), """
                    repository.id=crm
                    repository.name=CRM
                    repository.ssid=ICMCRM
                    repository.db.vendor=db2
                    repository.jdbc.url=jdbc:db2://db.example:50000/CRM
                    repository.cm.user.file=web-user.txt
                    repository.cm.password.file=web-password.txt
                    repository.jdbc.user.file=web-user.txt
                    repository.jdbc.password.file=web-password.txt
                    """);
            Path config = TestSupport.writeFile(dir.resolve("application.properties"), """
                    web.bind=127.0.0.1
                    web.port=8080
                    secrets.dir=%s
                    profiles.dir=%s
                    repository.auto.activate=crm
                    """.formatted(propertiesPath(secrets), propertiesPath(profiles)));

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            int exit;
            try {
                System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
                exit = execute(new String[] {"--config", config.toString()});
            } finally {
                System.setErr(originalErr);
            }

            Assert.assertEquals(4, exit,
                    "C: auto-activation with no usable adapter must fail startup with the adapter-unavailable"
                            + " exit code rather than publish a placeholder context. stderr: " + err);
            String text = err.toString(StandardCharsets.UTF_8);
            Assert.assertTrue(text.contains("repository.auto.activate"),
                    "C: the failure names the configuration key that asked for the activation: " + text);
            Assert.assertTrue(text.contains("refusing to start"),
                    "C: and says it is refusing to start instead of degrading quietly: " + text);
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    // ------------------------------------------------------------------ harness

    /** The compiled optional source set, derived from this suite's own build location. */
    private static Path packagedAdapterClasses() throws Exception {
        Path classesDir = Paths.get(ProviderDiscoveryTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        // Walk up rather than assume one fixed depth: the build places this suite at
        // <root>/build/test-classes and the adapter at <root>/target/ibm-classes, which are siblings two
        // levels apart, but a developer (or a verification tree) may run the same classes from a different
        // nesting. Looking for the descriptor, not for a name, keeps the assertion about the artifact
        // instead of about the directory layout.
        Path candidate = classesDir;
        for (int level = 0; level < 5 && candidate != null; level++) {
            Path ibmClasses = candidate.resolve("target").resolve("ibm-classes");
            if (Files.exists(ibmClasses.resolve(SERVICE_RESOURCE))) {
                return ibmClasses;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("the packaged adapter classes could not be found from " + classesDir
                + ": no ancestor carries target/ibm-classes/" + SERVICE_RESOURCE + ". build.sh compiles the"
                + " optional source set before it runs this suite, so this means the build order changed and"
                + " the SDK-free runtime assertion would otherwise test nothing");
    }

    /** Runs {@code Main.execute}, which is the launcher without its {@code System.exit}. */
    private static int execute(String[] args) throws Exception {
        java.lang.reflect.Method execute = Main.class.getDeclaredMethod("execute", String[].class);
        execute.setAccessible(true);
        return (Integer) execute.invoke(null, (Object) args);
    }

    /** A properties value that names a path; backslashes would be escape characters there. */
    private static String propertiesPath(Path path) {
        return path.toString().replace("\\", "/");
    }

    /**
     * The factory {@code Main} builds when no provider is present.
     *
     * <p>Reproduced rather than invented: it throws {@link ActivationFailedException} with a reason naming
     * the adapter and the verdict. Kept in one place so the behavioural assertions above read as one rule.
     */
    private static RepositoryContextFactory refusingFactory(IbmCmAdapterRegistry.Availability availability) {
        String reason = "no CM adapter provider is available (" + availability.label()
                + "); a repository can be listed but not activated";
        return profile -> {
            throw new ActivationFailedException(reason);
        };
    }

    /** Asserts a refused registry exposes no provider and refuses to activate. */
    private static void assertActivationRefusedWithoutAPlaceholder(IbmCmAdapterRegistry registry, String when) {
        Assert.assertTrue(registry.provider().isEmpty(),
                "a refused registry must expose no provider " + when);
        Assert.assertTrue(registry.status().refused(),
                "and report itself refused " + when);
    }

    /**
     * A class loader that APPENDS a chosen service descriptor to the discovered ones.
     *
     * <p>Discovery is decided by the union of the descriptors
     * {@code META-INF/services/com.mraibo.cminsight.ibm.CmAdapterProvider} on the class path, so this writes
     * a descriptor into a temporary directory and puts that directory first. Appending rather than replacing
     * is the point: the production registration stays visible, which is what makes "exactly one provider" and
     * "two providers" the two real configurations rather than an artefact of the test.
     *
     * <p>The directory is created under the suite's own scratch root (see {@link TestSupport#newTempDir}),
     * not under {@code java.io.tmpdir}, because the build tree is what is writable here.
     */
    private static ClassLoader loaderWith(String descriptorContent) throws IOException {
        Path root = TestSupport.newTempDir("provider-descriptor");
        Path descriptor = root.resolve(SERVICE_RESOURCE);
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, descriptorContent, StandardCharsets.UTF_8);
        ClassLoader application = ProviderDiscoveryTest.class.getClassLoader();
        return new URLClassLoader(new URL[] {root.toUri().toURL() }, application);
    }

    /**
     * A loader whose application half is the test class path only.
     *
     * <p>Used where the assertion is "no provider at all", which must not be polluted by whatever else the
     * build happens to put on the class path.
     */
    private static ClassLoader isolatedLoaderWith(String descriptorContent) throws IOException {
        Path root = TestSupport.newTempDir("provider-isolated");
        Path descriptor = root.resolve(SERVICE_RESOURCE);
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, descriptorContent, StandardCharsets.UTF_8);
        return new URLClassLoader(new URL[] {root.toUri().toURL() },
                ClassLoader.getPlatformClassLoader());
    }

    /** A provider that exists only to make the descriptor name something loadable. */
    public static final class FakeProvider implements CmAdapterProvider {

        @Override
        public String providerId() {
            return "test-fake-adapter";
        }

        @Override
        public String adapterVersion() {
            return "0.0.0-test";
        }

        @Override
        public String sdkRelease() {
            return "";
        }

        @Override
        public com.mraibo.cminsight.core.CmSessionFactory sessionFactory(RepositoryProfile profile,
                                                                        CmAdapterSettings settings) {
            throw new UnsupportedOperationException("the discovery tests never open a session");
        }
    }

    /**
     * A SECOND loadable provider, for the ambiguous case.
     *
     * <p>Deliberately a different class rather than the real adapter: ambiguity is about how many providers
     * are installed, so counting the production registration would make the test depend on the build's own
     * packaging - which is asserted on its own, against the real class path, in
     * {@link #thePackagedRuntimeWithoutTheVendorSdkIsInstalledButUnavailable()}.
     */
    public static final class OtherFakeProvider implements CmAdapterProvider {

        @Override
        public String providerId() {
            return "test-other-fake-adapter";
        }

        @Override
        public String adapterVersion() {
            return "0.0.0-test";
        }

        @Override
        public String sdkRelease() {
            return "";
        }

        @Override
        public com.mraibo.cminsight.core.CmSessionFactory sessionFactory(RepositoryProfile profile,
                                                                        CmAdapterSettings settings) {
            throw new UnsupportedOperationException("the discovery tests never open a session");
        }
    }

    /**
     * A loadable provider whose vendor runtime is NOT ready, with a counted session factory.
     *
     * <p>This is the shape section C is about, expressed without any vendor code: the provider class is
     * installed and describes itself, and it answers "my runtime is not ready" with a fixed sentence. The
     * session factory is counted rather than left unimplemented so the test can prove the negative half of
     * the rule - that discovery and reporting never turn into a session attempt.
     */
    public static final class UnreadyFakeProvider implements CmAdapterProvider {

        /** How many times discovery (or anything else) asked for a session factory. */
        static final AtomicInteger sessionFactoryCalls = new AtomicInteger();

        @Override
        public String providerId() {
            return "test-unready-adapter";
        }

        @Override
        public String adapterVersion() {
            return "0.0.0-test";
        }

        @Override
        public String sdkRelease() {
            return "";
        }

        @Override
        public Readiness readiness() {
            return Readiness.unavailable("test-unready-adapter: the vendor runtime is not on the class path");
        }

        @Override
        public com.mraibo.cminsight.core.CmSessionFactory sessionFactory(RepositoryProfile profile,
                                                                        CmAdapterSettings settings) {
            sessionFactoryCalls.incrementAndGet();
            throw new UnsupportedOperationException("no session may be requested while the runtime is unready");
        }
    }
}
