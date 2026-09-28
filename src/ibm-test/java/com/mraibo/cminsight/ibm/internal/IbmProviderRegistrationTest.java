package com.mraibo.cminsight.ibm.internal;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.core.CmSessionFactory;
import com.mraibo.cminsight.ibm.CmAdapterProvider;
import com.mraibo.cminsight.ibm.CmAdapterSettings;
import com.mraibo.cminsight.ibm.IbmCmAdapterRegistry;

import java.util.Optional;

import static com.mraibo.cminsight.ibm.internal.Assert.assertEquals;
import static com.mraibo.cminsight.ibm.internal.Assert.assertFalse;
import static com.mraibo.cminsight.ibm.internal.Assert.assertNotNull;
import static com.mraibo.cminsight.ibm.internal.Assert.assertTrue;

/**
 * The packaged adapter is REGISTERED: discovery on the production class path finds exactly this provider.
 *
 * <h2>Why this belongs in the IBM test tree</h2>
 *
 * <p>The assertion is about this build's own packaging - the adapter's classes plus its service descriptor,
 * both in the same jar - so it can only be made where those are on the class path. The core suite is
 * deliberately adapter-free, and a test there would either have to skip or assert the opposite of the
 * truth; {@code com.mraibo.cminsight.test.ProviderDiscoveryTest} covers the registry's decision logic
 * instead, with descriptors it supplies itself.
 *
 * <h2>What would break if the registration went missing</h2>
 *
 * <p>Nothing in the adapter would fail to compile, no guard would trip, and every unit of the read path would
 * still pass its own tests - but {@code ServiceLoader} would find nothing, discovery would report ABSENT, and
 * every repository would become unactivatable with a message about a missing adapter that is in fact present.
 * That is precisely the class of defect a test has to assert rather than a review has to notice.
 */
public final class IbmProviderRegistrationTest {

    /** Discovery on the real class path reports exactly one usable provider, and it is this adapter. */
    public void thePackagedAdapterIsDiscoveredAsTheOnlyProvider() {
        IbmCmAdapterRegistry registry = IbmCmAdapterRegistry.discover();

        assertEquals(IbmCmAdapterRegistry.Availability.AVAILABLE, registry.status().availability(),
                "this build packages the adapter's classes and its service descriptor, so discovery must"
                        + " report AVAILABLE; anything else means the registration is missing from the jar and"
                        + " every repository is unactivatable. Status: " + registry.status().describe());
        assertTrue(registry.status().available(), "and the status agrees with the availability");
        assertFalse(registry.status().refused(), "a discovered provider is not refused");
        assertEquals(IbmCmAdapterProvider.PROVIDER_ID, registry.status().providerId(),
                "the discovered provider must be THIS adapter, not some other registration that happened to"
                        + " be on the class path");
        assertEquals(IbmCmAdapterProvider.ADAPTER_VERSION, registry.status().adapterVersion(),
                "and its advertised build version is the one the provider declares");
        assertFalse(registry.status().describe().isBlank(),
                "and the operator-facing description exists");
    }

    /** The discovered provider is the adapter's own class, so activation goes through this source set. */
    public void theDiscoveredProviderIsTheAdapterItself() {
        Optional<CmAdapterProvider> provider = IbmCmAdapterRegistry.discover().provider();

        assertTrue(provider.isPresent(), "an AVAILABLE verdict must carry the provider, since every"
                + " activation is built from it");
        assertTrue(provider.get() instanceof IbmCmAdapterProvider,
                "the discovered provider must be the adapter's own implementation, but was "
                        + provider.get().getClass().getName());
        assertEquals(IbmCmAdapterProvider.PROVIDER_ID, provider.get().providerId(),
                "and it identifies itself with the documented provider id");
    }

    /**
     * The provider can hand back a session-factory recipe without opening anything.
     *
     * <p>{@code sessionFactory} is the one member that reaches toward the vendor boundary, so it is asserted
     * to be constructible and to return a non-null recipe: a provider that was discoverable but could not
     * produce a factory would fail at activation instead of here. Nothing is connected - the recipe is only a
     * factory object - and the profile's credentials are references, never values.
     */
    public void theProviderBuildsASessionFactoryRecipeWithoutConnecting() {
        CmAdapterProvider provider = IbmCmAdapterRegistry.discover().provider().orElseThrow();
        RepositoryProfile profile = IbmFakes.profile("registration");
        CmAdapterSettings settings = CmAdapterSettings.defaults(IbmFakes.resolver());

        CmSessionFactory factory = provider.sessionFactory(profile, settings);

        assertNotNull(factory, "the provider must produce a session factory recipe; a discoverable adapter"
                + " that cannot produce one would fail at activation instead of here");
        assertTrue(factory instanceof IbmCmSessionFactory,
                "and it must be the adapter's own factory, so sessions come from this source set: " + factory);
    }

    /**
     * The advertised SDK release is never null and never a credential.
     *
     * <p>It is empty when the vendor manifest cannot be read - which is the case on a stub class path - and
     * that is an answer, not a failure: diagnostics render it as {@code unknown}. Asserted because the value
     * is free text that travels to an operator page.
     */
    public void theSdkReleaseIsReportedValueFreeWhenUnknown() {
        IbmCmAdapterRegistry.Status status = IbmCmAdapterRegistry.discover().status();

        assertNotNull(status.sdkRelease(), "the SDK release must be an empty string rather than null");
        assertFalse(status.sdkRelease().contains("password") || status.sdkRelease().contains("user="),
                "the release is vendor version text, never a credential: " + status.sdkRelease());
        assertFalse(status.describe().toLowerCase(java.util.Locale.ROOT).contains("password"),
                "and neither is the description: " + status.describe());
    }
}
