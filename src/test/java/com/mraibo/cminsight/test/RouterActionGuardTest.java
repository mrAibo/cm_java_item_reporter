package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.config.WebAuthSettings;
import com.mraibo.cminsight.ibm.IbmCmAdapterRegistry;
import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryContextFactory;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.security.Authenticator;
import com.mraibo.cminsight.security.LoginThrottle;
import com.mraibo.cminsight.web.CmApiRoutes;
import com.mraibo.cminsight.web.Route;
import com.mraibo.cminsight.web.Router;
import com.mraibo.cminsight.web.WebServer;
import com.mraibo.cminsight.web.http.RequestContext;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Goal 02 section K: the repository action guard, and the authentication boundary around the CM API.
 *
 * <h2>The threat this exists for</h2>
 *
 * <p>{@code POST /api/repositories/select} changes which repository the process reads and opens physical CM
 * sessions. A browser will issue that request from any page the operator visits - a cross-site form
 * submission needs no CORS permission and carries the operator's ambient credentials - so an endpoint guarded
 * only by HTTP authentication is reachable by any site the operator has open. The defence is a custom header
 * a form cannot set, and the assertion is that a request without it is refused BEFORE anything is read or
 * changed: no repository may be activated, and no session opened, by a request that failed the guard.
 *
 * <h2>What is asserted, and why the activation factory is the witness</h2>
 *
 * <p>"Refused before any state change" is not observable from the status code: a handler could activate first
 * and answer 403 afterwards. Every negative case therefore asserts the manager's state AND that the activation
 * factory was never called, which is the only thing that can open a session.
 */
public class RouterActionGuardTest {

    private static final String USER = "ops";
    private static final String PASSWORD = "Action-Guard-Pass-77";

    /** A cross-site form cannot set the guard header, so its absence must be fatal. */
    public void aPostWithoutTheGuardHeaderIsRefusedBeforeAnyStateChange() throws Exception {
        Harness harness = new Harness();

        FakeTransport transport = harness.authorized(selection());
        harness.router.handle(new RequestContext(transport, "guard-missing"));

        Assert.assertEquals(403, transport.status(),
                "a POST without the action header must be refused with 403, not dispatched: this is the"
                        + " cross-site form submission shape. Body: " + transport.bodyText());
        Assert.assertTrue(transport.bodyText().contains("action_forbidden"),
                "and the body must carry the frozen error code. Body: " + transport.bodyText());
        harness.assertNothingActivated("a request with no action header");
    }

    /** A wrong value is as refused as a missing one: the guard is an exact match, not a presence check. */
    public void aPostWithTheWrongGuardValueIsRefused() throws Exception {
        Harness harness = new Harness();

        String[] wrongValues = {
                "", "select", "repository-select-extra", "REPOSITORY-SELECT", "repository_select",
        };
        for (String wrong : wrongValues) {
            FakeTransport transport = harness.authorized(
                    selection().withHeader(CmApiRoutes.ACTION_HEADER, wrong));
            harness.router.handle(new RequestContext(transport, "guard-wrong"));

            Assert.assertEquals(403, transport.status(),
                    "the guard value '" + wrong + "' must be refused: a presence check that accepted any value"
                            + " would be defeatable by a client that learned the header name. Body: "
                            + transport.bodyText());
        }
        harness.assertNothingActivated("requests with a wrong action header");
    }

    /**
     * The correct header value IS accepted, so the refusals above are the guard and not a broken route.
     *
     * <p>Without this, the negative cases would pass on a route that refused everything - including the
     * console's own request - and the guard would look stricter while the feature was unusable.
     */
    public void theCorrectGuardValueReachesTheHandler() throws Exception {
        Harness harness = new Harness();

        FakeTransport transport = harness.authorized(
                selection().withHeader(CmApiRoutes.ACTION_HEADER, CmApiRoutes.SELECT_ACTION));
        harness.router.handle(new RequestContext(transport, "guard-ok"));

        Assert.assertFalse(transport.status() == 403,
                "a correctly guarded selection must not be refused by the action guard. Body: "
                        + transport.bodyText());
        Assert.assertFalse(transport.status() == 404,
                "and the route must exist. Body: " + transport.bodyText());
    }

    /**
     * A form-sendable content type is refused even WITH the header.
     *
     * <p>The header is the real guard; this is the defence-in-depth half. An HTML form can be told to send
     * {@code application/x-www-form-urlencoded}, {@code multipart/form-data} or {@code text/plain}, so a
     * request declaring one of those is the shape a smuggled token would arrive in, and it must be refused
     * before the body is considered.
     */
    public void aFormSendableContentTypeIsRefusedEvenWithTheHeader() throws Exception {
        Harness harness = new Harness();

        String[] formTypes = {
                "application/x-www-form-urlencoded",
                "multipart/form-data; boundary=x",
                "text/plain",
        };
        for (String type : formTypes) {
            FakeTransport transport = harness.authorized(selection()
                    .withHeader(CmApiRoutes.ACTION_HEADER, CmApiRoutes.SELECT_ACTION)
                    .withHeader("Content-Type", type));
            harness.router.handle(new RequestContext(transport, "guard-form"));

            Assert.assertEquals(403, transport.status(),
                    "a request declaring the form-sendable type '" + type + "' must be refused even with the"
                            + " guard header: those are the three types an HTML form can produce. Body: "
                            + transport.bodyText());
        }
        harness.assertNothingActivated("requests with a form-sendable content type");
    }

    /**
     * The selection action is POST-only: a GET must not be an activation vector.
     *
     * <p>A GET is trivially issuable by any page (an image tag, a link), which is exactly why a state change
     * must not be reachable through one.
     */
    public void aGetOnTheSelectionPathIsNotAnActivationVector() throws Exception {
        Harness harness = new Harness();

        FakeTransport transport = harness.authorized(get(CmApiRoutes.SELECT_PATH,
                Map.of(CmApiRoutes.REPOSITORY_PARAMETER, "alpha")));
        harness.router.handle(new RequestContext(transport, "guard-get"));

        Assert.assertEquals(405, transport.status(),
                "GET must not be routed to the selection handler; the router answers 405 for a path that"
                        + " exists for another method. Body: " + transport.bodyText());
        harness.assertNothingActivated("a GET on the selection path");
    }

    /**
     * Every CM route requires authentication, and in the composed server the public surface is exactly
     * {@code /api/health}.
     *
     * <p>Asserted over the real composition rather than the CM table alone: {@code /api/health} is installed
     * by the mandatory route set, so a router holding only the CM routes has no public route at all - which is
     * itself the assertion for the CM half - and the full set must still expose exactly one public path. A
     * route added later without authentication fails here rather than at review time.
     */
    public void everyCmRouteIsAuthenticatedAndOnlyHealthIsPublic() {
        Harness harness = new Harness();

        for (Route route : harness.router.routes()) {
            Assert.assertTrue(route.authRequired(),
                    "route " + route.method() + " " + route.path() + " must require authentication; an"
                            + " unauthenticated route would let anyone reaching the port probe repository names,"
                            + " SSIDs and ItemType metadata");
        }
        Assert.assertEquals(0, harness.router.routes().stream().filter(route -> !route.authRequired()).count(),
                "the CM routes must contribute NO public route: the health endpoint comes from the mandatory"
                        + " set, and nothing else may be reachable without credentials");

        // The composed server: mandatory routes plus the CM ones, as Main installs them.
        Router composed = new Router();
        new WebServer(serverConfig(), authSettings(), composed);
        new CmApiRoutes(new RepositoryManager(harness.factory),
                List.of(TestSupport.profile("alpha")), IbmCmAdapterRegistry.discover()).install(composed);

        List<String> publicPaths = composed.routes().stream()
                .filter(route -> !route.authRequired())
                .map(Route::path)
                .toList();
        Assert.assertEquals(List.of(Router.PUBLIC_PATH), publicPaths,
                "the only public route in the composed server must be the health endpoint, but the public"
                        + " set is " + publicPaths);
    }

    /** An unauthenticated guarded POST is refused by authentication, before the guard is even consulted. */
    public void anUnauthenticatedSelectionIsRefusedByAuthentication() throws Exception {
        Harness harness = new Harness();

        FakeTransport transport = selection().withHeader(CmApiRoutes.ACTION_HEADER, CmApiRoutes.SELECT_ACTION);
        harness.router.handle(new RequestContext(transport, "guard-anon"));

        Assert.assertEquals(401, transport.status(),
                "an unauthenticated selection must be 401 even with a correct action header: authentication"
                        + " comes first, and a correct header proves nothing about who is asking. Body: "
                        + transport.bodyText());
        harness.assertNothingActivated("an unauthenticated request");
    }

    /**
     * Refusal bodies are clean JSON: no stack trace, and no credential the caller supplied.
     *
     * <p>The credential check is the containment half: the password travels in the {@code Authorization}
     * header, and a refusal that echoed its own request would put it into a response body and into any log that
     * records one.
     */
    public void refusalBodiesAreCleanJsonWithoutStackTracesOrCredentials() throws Exception {
        Harness harness = new Harness();

        List<FakeTransport> refusals = List.of(
                harness.authorized(selection()),                                            // no header
                harness.authorized(selection().withHeader(CmApiRoutes.ACTION_HEADER, "nope")), // wrong header
                selection().withHeader(CmApiRoutes.ACTION_HEADER, CmApiRoutes.SELECT_ACTION)); // no auth

        for (FakeTransport transport : refusals) {
            harness.router.handle(new RequestContext(transport, "guard-body"));
            String body = transport.bodyText();

            Assert.assertTrue(transport.status() >= 400,
                    "each case must be a refusal, but one answered " + transport.status());
            Assert.assertTrue(body.startsWith("{"),
                    "a refusal body must be the JSON error envelope, but was: " + body);
            Assert.assertFalse(body.contains("Exception") || body.contains("\tat ") || body.contains("\n"),
                    "a refusal must never carry a stack trace: it exposes file paths and internals. Body: "
                            + body);
            Assert.assertFalse(body.contains(PASSWORD),
                    "a refusal must never echo the request's credential. Body: " + body);
            Assert.assertFalse(body.toLowerCase(Locale.ROOT).contains("password"),
                    "and must not name credentials at all. Body: " + body);
            Assert.assertTrue(transport.contentType().startsWith("application/json"),
                    "the envelope must be declared JSON, was: " + transport.contentType());
        }
    }

    // ------------------------------------------------------------------ harness

    /**
     * The minimum configuration a {@link WebServer} needs to install its mandatory routes.
     *
     * <p>No socket is opened: the constructor installs the route table, and that table is what this test
     * reads. The bind is loopback and the port is 0 so nothing can collide with a real listener.
     */
    private static com.mraibo.cminsight.config.AppConfig serverConfig() {
        Properties properties = new Properties();
        properties.setProperty("web.bind", "127.0.0.1");
        properties.setProperty("web.port", "0");
        properties.setProperty("web.auth.user", USER);
        properties.setProperty("web.auth.password", PASSWORD);
        properties.setProperty("web.auth.maxFailures", "3");
        properties.setProperty("web.auth.lockout", "60s");
        properties.setProperty("web.threads", "4");
        return com.mraibo.cminsight.config.AppConfig.fromProperties(properties);
    }

    private static com.mraibo.cminsight.config.WebAuthSettings authSettings() {
        return com.mraibo.cminsight.config.WebAuthSettings.resolve(serverConfig(),
                new com.mraibo.cminsight.config.SecretResolver(Map.of(), null));
    }

    /** A POST with the given query parameters: {@code FakeTransport} takes them at construction. */
    private static FakeTransport post(String path, Map<String, String> query) {
        return new FakeTransport("POST", path, query, new byte[0]);
    }

    /** A GET with the given query parameters. */
    private static FakeTransport get(String path, Map<String, String> query) {
        return new FakeTransport("GET", path, query, new byte[0]);
    }

    /** The selection request a console would send, minus whichever guard piece a case is testing. */
    private static FakeTransport selection() {
        return post(CmApiRoutes.SELECT_PATH, Map.of(CmApiRoutes.REPOSITORY_PARAMETER, "alpha"));
    }

    /**
     * A router with the CM routes installed over a manager whose activation factory records every call.
     *
     * <p>The factory is the witness for "nothing happened": it is the only path to a session, so a count of
     * zero is proof that a refused request opened no physical resource.
     */
    private static final class Harness {

        final Router router = new Router();
        final CountingFactory factory = new CountingFactory();

        Harness() {
            router.bindAuthenticator(new Authenticator(TestSupport.credentials(USER, PASSWORD),
                    new LoginThrottle(5, Duration.ofMinutes(1), 64)));
            List<RepositoryProfile> profiles = List.of(TestSupport.profile("alpha"));
            new CmApiRoutes(new RepositoryManager(factory),
                    profiles, IbmCmAdapterRegistry.discover()).install(router);
        }

        FakeTransport authorized(FakeTransport transport) {
            return transport.withHeader("Authorization", TestSupport.basic(USER, PASSWORD));
        }

        void assertNothingActivated(String when) {
            Assert.assertEquals(0, factory.calls,
                    "the activation factory must never be called for " + when + ": the guard's whole purpose"
                            + " is that no state is read or changed for a refused request");
        }
    }

    /** An activation factory that records how often it was asked to build a context. */
    private static final class CountingFactory implements RepositoryContextFactory {

        private int calls;

        @Override
        public RepositoryContext create(RepositoryProfile profile) {
            calls++;
            throw new IllegalStateException("no activation is expected in this test");
        }
    }
}
