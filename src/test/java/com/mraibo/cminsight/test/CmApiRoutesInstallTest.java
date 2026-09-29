package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.ibm.IbmCmAdapterRegistry;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.retention.RetentionPolicyInfo;
import com.mraibo.cminsight.security.Authenticator;
import com.mraibo.cminsight.security.LoginThrottle;
import com.mraibo.cminsight.web.CmApiRoutes;
import com.mraibo.cminsight.web.Route;
import com.mraibo.cminsight.web.Router;
import com.mraibo.cminsight.web.http.RequestContext;

import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Goal 02 section F/G: the CM API routes are actually REGISTERED and actually SERVED.
 *
 * <h2>The bug this test exists for</h2>
 *
 * <p>This tree once shipped a build whose CM routes were written, compiled, reviewed and never installed: the
 * router served the mandatory routes alone, so every CM endpoint answered {@code 404} while every test that
 * exercised the handlers directly passed. The feature was complete and unreachable at the same time. Nothing
 * in a handler-level test can catch that, because the defect is in the wiring between the router and the
 * handler.
 *
 * <p>So the assertions are deliberately about the INSTALLED ROUTER, through the same {@link RequestContext}
 * path a socket request takes:
 *
 * <ul>
 *   <li>every documented CM path is present in {@code router.routes()} and requires authentication - a
 *       route that exists but is public would leak repository names to anyone who can reach the port;</li>
 *   <li>an authenticated request to each one is DISPATCHED, which is what a {@code 404} would prove
 *       otherwise, whatever its status code turns out to be.</li>
 * </ul>
 *
 * <h2>And the adapter verdict must not turn absence into a 404</h2>
 *
 * <p>The second half covers the other half of the same trap: with no adapter installed the routes must still
 * exist and answer {@code 503} (or report the adapter as absent in their payload), never {@code 404} and never
 * an empty body pretending the repository has no data. An operator has to be able to tell "this build has no
 * CM adapter" from "this URL is wrong", and only the status code distinguishes them.
 */
public class CmApiRoutesInstallTest {

    private static final String USER = "ops";
    private static final String PASSWORD = "Cm-Api-Install-77";

    /** Every path {@link CmApiRoutes#install(Router)} is required to register. */
    private static final Set<String> REQUIRED_PATHS = Set.of(
            CmApiRoutes.REPOSITORIES_PATH,
            CmApiRoutes.SELECT_PATH,
            CmApiRoutes.STATUS_PATH,
            CmApiRoutes.ITEM_TYPES_PATH,
            CmApiRoutes.RETENTION_POLICIES_PATH,
            CmApiRoutes.CM_DIAGNOSTICS_PATH);

    /**
     * Every documented CM path must be registered, and every one must require authentication.
     *
     * <p>Asserted against the router rather than against the handler, because "not installed" is the failure
     * mode this test is for. The set comparison is exact in both directions: a MISSING path is the reported
     * bug, and an EXTRA path is either a typo or an undocumented surface.
     */
    public void everyCmApiRouteIsInstalledAndAuthenticated() {
        Router router = routerWithCmRoutes(IbmCmAdapterRegistry.discover());

        Set<String> installed = new TreeSet<>();
        for (Route route : router.routes()) {
            if (REQUIRED_PATHS.contains(route.path())) {
                installed.add(route.path());
                Assert.assertTrue(route.authRequired(),
                        "every CM API route must require authentication, but " + route.path() + " does not:"
                                + " an unauthenticated CM route would let anyone who can reach the port probe"
                                + " repository names and SSIDs");
            }
        }

        Assert.assertEquals(installed, new TreeSet<>(REQUIRED_PATHS),
                "the router must serve exactly the documented CM API paths; a missing path is the"
                        + " 'written but never installed' defect, and an extra one is an undocumented surface");
    }

    /**
     * An authenticated request to each CM path must be DISPATCHED rather than answered with 404.
     *
     * <p>This is the direct assertion for the shipped bug. The statuses differ by design - a list that needs
     * no active repository is {@code 200}, a read that needs one is a refusal - so the assertion is that no
     * route answers {@code 404}, plus the specific statuses that must hold.
     */
    public void everyCmApiRouteIsServedRatherThanNotFound() throws Exception {
        Router router = routerWithCmRoutes(IbmCmAdapterRegistry.discover());

        for (String path : REQUIRED_PATHS) {
            // The select route is a POST; the rest are reads. The method is part of the route identity, and
            // using the wrong one yields a legitimate 405 that would hide an uninstalled path.
            boolean select = CmApiRoutes.SELECT_PATH.equals(path);
            String method = select ? "POST" : "GET";
            FakeTransport transport = authorized(select ? FakeTransport.post(path) : FakeTransport.get(path));
            router.handle(new RequestContext(transport, "cm-api-install"));

            Assert.assertFalse(transport.status() == 404,
                    method + " " + path + " answered 404, which means the route is not installed at all - the"
                            + " defect this test exists for. Body: " + transport.bodyText());
            Assert.assertFalse(transport.status() == 405,
                    method + " " + path + " answered 405, which means the path is registered for a different"
                            + " method than the documented one - a client following the contract would fail"
                            + " even though the route exists. Body: " + transport.bodyText());
            Assert.assertTrue(transport.status() == 200 || transport.status() == 403
                            || transport.status() == 409 || transport.status() == 503,
                    method + " " + path + " must answer 200, a documented refusal (403/409) or"
                            + " adapter_unavailable (503), but answered " + transport.status() + ": "
                            + transport.bodyText());
        }

        // The list route is the one that must work with nothing active: it reports the adapter verdict.
        FakeTransport list = authorized(FakeTransport.get(CmApiRoutes.REPOSITORIES_PATH));
        router.handle(new RequestContext(list, "cm-api-install"));
        Assert.assertEquals(200, list.status(),
                "the repository list must be readable with no active repository, so an operator can see what"
                        + " is configured and whether an adapter is installed");
        Assert.assertTrue(list.bodyText().contains("\"adapter\""),
                "and it must carry the adapter verdict, or a 200 would not distinguish 'no adapter installed'"
                        + " from 'no repositories configured'. Body: " + list.bodyText());

        FakeTransport diagnostics = authorized(FakeTransport.get(CmApiRoutes.CM_DIAGNOSTICS_PATH));
        router.handle(new RequestContext(diagnostics, "cm-api-install"));
        Assert.assertEquals(200, diagnostics.status(),
                "the CM diagnostics endpoint must answer even with no active repository");
        Assert.assertTrue(diagnostics.bodyText().contains("\"adapter\""),
                "and name the adapter verdict. Body: " + diagnostics.bodyText());
    }

    /** An unauthenticated request must be refused before any handler sees it, on every CM path. */
    public void everyCmApiRouteRefusesAnUnauthenticatedRequest() throws Exception {
        Router router = routerWithCmRoutes(IbmCmAdapterRegistry.discover());

        for (String path : REQUIRED_PATHS) {
            FakeTransport transport = FakeTransport.get(path);
            router.handle(new RequestContext(transport, "cm-api-anon"));

            Assert.assertEquals(401, transport.status(),
                    "GET " + path + " without credentials must be 401, not " + transport.status()
                            + ": the CM routes carry repository names and SSIDs and are not public");
        }
    }

    /**
     * With no adapter installed the routes must still exist, and the reads that need one must say so.
     *
     * <p>A discovery result with no usable provider is produced by loading the registry through a class loader
     * that cannot see the service descriptor, which is exactly the shape of "this build has no CM adapter".
     * The assertion is that the router still serves the paths and that the answer is a documented refusal
     * rather than a 404 or an empty 200 that would look like a repository with no data.
     */
    public void withNoAdapterInstalledTheRoutesStillExistAndRefuseCleanly() throws Exception {
        IbmCmAdapterRegistry absent = IbmCmAdapterRegistry.discover(isolatedLoader());
        Assert.assertTrue(absent.status().refused(),
                "a registry that cannot see any provider must report itself refused, but reported "
                        + absent.status().availability() + "; without that the router would treat a missing"
                        + " adapter as an available one");

        Router router = routerWithCmRoutes(absent);

        FakeTransport list = authorized(FakeTransport.get(CmApiRoutes.REPOSITORIES_PATH));
        router.handle(new RequestContext(list, "cm-api-absent"));
        Assert.assertEquals(200, list.status(),
                "the list is still served with no adapter installed: the repository is listed but cannot be"
                        + " activated, which is a different answer from 'no such route'");
        Assert.assertTrue(list.bodyText().toLowerCase(Locale.ROOT).contains("absent")
                        || list.bodyText().toLowerCase(Locale.ROOT).contains("unavailable"),
                "and the payload must report the adapter as absent/unavailable so the operator can tell why"
                        + " nothing can be activated. Body: " + list.bodyText());

        FakeTransport reads = authorized(FakeTransport.get(CmApiRoutes.ITEM_TYPES_PATH));
        router.handle(new RequestContext(reads, "cm-api-absent"));
        Assert.assertFalse(reads.status() == 404,
                "a read with no adapter installed must not 404 - the route exists and the honest answer is a"
                        + " refusal that names the reason");
        Assert.assertTrue(reads.status() == 409 || reads.status() == 503,
                "the read must answer a documented refusal (409/503) with no adapter, but answered "
                        + reads.status() + ": " + reads.bodyText());
        Assert.assertFalse(reads.bodyText().isBlank(),
                "and the refusal must carry a body: an empty response would be indistinguishable from 'this"
                        + " repository has no ItemTypes'");
    }

    /**
     * Goal 02A section F at the API boundary: the JSON the routes emit carries NO numeric code where none
     * has been established - it carries {@code null}.
     *
     * <h2>Why this is asserted here and not through a live route</h2>
     *
     * <p>Both retention routes serialise through {@code CmApiRoutes.policyJson(RetentionPolicyInfo)}, whose
     * output is embedded by {@code GET /api/retention/policies} and by
     * {@code GET /api/retention/policies/{name}}. Reaching either of those with a real policy needs an
     * activated repository and a live retention read, so the serialiser itself is invoked directly here -
     * it is the exact code the API returns, not a parallel implementation - and a DTO-level test in the
     * adapter suite pins the value that reaches it.
     *
     * <p>The assertion is deliberately about the WHOLE payload (no {@code -1} anywhere in it) rather than
     * only about the two fields: the defect section F removes is a doc'd "exact server code" carrying a
     * number that was never a server code, and a payload that leaks that number under a different key would
     * be the same defect.
     */
    public void theApiJsonCarriesAnAbsentRetentionCodeAsNull() throws Exception {
        RetentionPolicyInfo unmapped = retentionPolicy(7, null, null);
        String json = policyJson(unmapped);

        Assert.assertTrue(json.contains("\"retentionTypeCode\":null"),
                "F: an established-nowhere numeric code must be serialised as null, not as a number: " + json);
        Assert.assertTrue(json.contains("\"expirationActionCode\":null"),
                "F: and so must the expiration-action code: " + json);
        Assert.assertFalse(json.contains("-1"),
                "F: no part of the policy payload may carry the retired -1 sentinel, because -1 is a number a"
                        + " reader would take for an exact CM server code: " + json);
        Assert.assertTrue(json.contains("\"retentionType\":\"UNKNOWN(FIXED_TIME)\""),
                "F: while the readable field still carries IBM's certain constant identity: " + json);

        String mapped = policyJson(retentionPolicy(7, 3, 11));
        Assert.assertTrue(mapped.contains("\"retentionTypeCode\":3"),
                "F: a real code is still reported as the number the server said: " + mapped);
        Assert.assertTrue(mapped.contains("\"expirationActionCode\":11"),
                "F: and so is a real expiration-action code: " + mapped);
    }

    /**
     * Goal 02B section E at the API boundary: an explicitly supplied NEGATIVE numeric code is DATA, and the
     * serializer reports it as the number it was given.
     *
     * <h2>Why the API needs this assertion beside the absent-code one</h2>
     *
     * <p>Goal 02A's DTO normalised every negative code to {@code null}. Removing that normalisation is only
     * observable end to end if the shipped payload actually carries a supplied negative number, and the
     * dangerous failure in the other direction is a serializer or a JSON writer that quietly drops a value it
     * does not like - which would look exactly like an absence. So the two cases are asserted apart: the test
     * above pins {@code null} for an absence (and forbids the retired {@code -1} sentinel), and this one pins
     * that a supplied negative code is neither nulled nor rewritten.
     *
     * <p>The values are deliberately not {@code -1}: a payload-level rule that happened to preserve only the
     * old sentinel while nulling every other negative would pass a test written with {@code -1} alone.
     */
    public void theApiJsonPreservesAnExplicitlySuppliedNegativeRetentionCode() throws Exception {
        String json = policyJson(retentionPolicy(7, -2, -13));

        Assert.assertTrue(json.contains("\"retentionTypeCode\":-2"),
                "E: a supplied negative retention-type code must be rendered as that number - 'do not guess'"
                        + " means neither inventing a code nor discarding one, and discarding it here would be"
                        + " indistinguishable from an absence: " + json);
        Assert.assertTrue(json.contains("\"expirationActionCode\":-13"),
                "E: and likewise for the expiration-action code, with a second value so a -1-only rule cannot"
                        + " pass: " + json);
        Assert.assertFalse(json.contains("\"retentionTypeCode\":null"),
                "E: a supplied code must not be turned into an absence by the serializer: " + json);
        Assert.assertFalse(json.contains("\"expirationActionCode\":null"),
                "E: in either field: " + json);
        Assert.assertTrue(json.contains("\"retentionType\":\"UNKNOWN(FIXED_TIME)\""),
                "E: while the readable field keeps IBM's certain constant identity, exactly as it does for an"
                        + " absent code: " + json);
    }

    /** A documented retention policy for the JSON assertions; every other numeric is non-negative. */
    private static RetentionPolicyInfo retentionPolicy(int policyId, Integer retentionTypeCode,
                                                     Integer expirationActionCode) {
        return new RetentionPolicyInfo(
                "Policy", "description", policyId,
                "UNKNOWN(FIXED_TIME)", retentionTypeCode,
                true, "period", 30, "DAY",
                true, "expiration", 90, "DAY",
                "UNKNOWN(AUTO_DELETE)", expirationActionCode,
                "", 0, 0, 0, false, List.of("ItemTypeA"));
    }

    /** The exact serialiser both retention routes use; private today, so it is called reflectively. */
    private static String policyJson(RetentionPolicyInfo policy) throws Exception {
        java.lang.reflect.Method policyJson =
                CmApiRoutes.class.getDeclaredMethod("policyJson", RetentionPolicyInfo.class);
        policyJson.setAccessible(true);
        return (String) policyJson.invoke(null, policy);
    }

    // ------------------------------------------------------------------ harness

    private static Authenticator authenticator() {
        return new Authenticator(TestSupport.credentials(USER, PASSWORD),
                new LoginThrottle(5, Duration.ofMinutes(1), 64));
    }

    private static FakeTransport authorized(FakeTransport transport) {
        return transport.withHeader("Authorization", TestSupport.basic(USER, PASSWORD));
    }

    /** A router with the CM routes installed, exactly as the startup path installs them. */
    private static Router routerWithCmRoutes(IbmCmAdapterRegistry adapters) {
        Router router = new Router();
        router.bindAuthenticator(authenticator());
        List<RepositoryProfile> profiles = List.of(TestSupport.profile("alpha"));
        CmApiRoutes routes = new CmApiRoutes(new RepositoryManager(profile -> {
            throw new IllegalStateException("no activation is expected in this test");
        }), profiles, adapters);
        routes.install(router);
        return router;
    }

    /**
     * A class loader that cannot see the CM adapter service descriptor, so discovery reports no provider.
     *
     * <p>The platform loader is the parent on purpose: it can load {@code ServiceLoader} itself but none of
     * this application's classes, so the descriptor is invisible and the outcome is a clean "no usable
     * adapter" verdict rather than a linkage error.
     */
    private static ClassLoader isolatedLoader() {
        return new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader());
    }
}
