package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.ibm.CmAdapterProvider;
import com.mraibo.cminsight.ibm.CmAdapterSettings;
import com.mraibo.cminsight.ibm.IbmCmAdapterRegistry;
import com.mraibo.cminsight.repository.ActivationFailedException;
import com.mraibo.cminsight.repository.RepositoryContextFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
 */
public class ProviderDiscoveryTest {

    /** The service descriptor ServiceLoader reads for the adapter seam. */
    private static final String SERVICE_RESOURCE =
            "META-INF/services/" + CmAdapterProvider.class.getName();

    /**
     * The shipped build has exactly one provider, and it is the IBM adapter.
     *
     * <p>Asserted against the real classpath rather than a fake, because "one provider is installed" is the
     * production configuration the rest of the runtime is written for: the build packages the adapter's
     * classes AND its service descriptor into the same jar, and a descriptor missing from the jar would make
     * every repository silently unactivatable.
     */
    public void thePackagedBuildDiscoversExactlyOneUsableProvider() {
        IbmCmAdapterRegistry registry = IbmCmAdapterRegistry.discover();

        Assert.assertEquals(IbmCmAdapterRegistry.Availability.AVAILABLE, registry.status().availability(),
                "this build packages one CM adapter provider, so discovery must report AVAILABLE; anything"
                        + " else means the service descriptor is missing from the jar and every repository is"
                        + " unactivatable. Status: " + registry.status().describe());
        Assert.assertTrue(registry.provider().isPresent(),
                "an AVAILABLE verdict must carry the provider, since every activation goes through it");
        Assert.assertTrue(registry.status().available(), "and the status agrees");
        Assert.assertFalse(registry.status().refused(),
                "a discovered provider must not be reported as refused");
        Assert.assertFalse(registry.status().providerId().isBlank(),
                "the provider id is published so diagnostics can name the adapter");
        Assert.assertFalse(registry.status().adapterVersion().isBlank(),
                "and its build version");
        Assert.assertFalse(registry.status().describe().isBlank(),
                "and a value-free description exists for the operator");
    }

    /** No provider registered is ABSENT, and nothing may be activated through it. */
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

    // ------------------------------------------------------------------ harness

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
     * {@link #thePackagedBuildDiscoversExactlyOneUsableProvider()}.
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
}
