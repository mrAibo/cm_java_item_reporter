package com.mraibo.cminsight.test;

import com.mraibo.cminsight.security.Authenticator;
import com.mraibo.cminsight.security.LoginThrottle;
import com.mraibo.cminsight.security.Principal;
import com.mraibo.cminsight.web.RequestHandler;
import com.mraibo.cminsight.web.Router;
import com.mraibo.cminsight.web.http.HttpMethod;
import com.mraibo.cminsight.web.http.RequestContext;

import java.time.Duration;

/** Routing, the authentication boundary and the error envelope, driven through a fake transport. */
public class RouterTest {

    private static final String USER = "ops";
    private static final String PASSWORD = "Router-Pass-77";

    private static final RequestHandler OK = context -> context.sendJson(200, "{\"ok\":true}");

    private static Authenticator authenticator() {
        return new Authenticator(TestSupport.credentials(USER, PASSWORD),
                new LoginThrottle(5, Duration.ofMinutes(1), 64));
    }

    private static Router boundRouter() {
        Router router = new Router();
        router.bindAuthenticator(authenticator());
        return router;
    }

    private static FakeTransport authorized(FakeTransport transport) {
        return transport.withHeader("Authorization", TestSupport.basic(USER, PASSWORD));
    }

    private static void handle(Router router, FakeTransport transport, String requestId) throws Exception {
        router.handle(new RequestContext(transport, requestId));
    }

    public void onlyTheHealthPathMayBeRegisteredWithoutAuthentication() {
        Router router = new Router();
        router.publicGet(Router.PUBLIC_PATH, OK);
        Assert.assertEquals(1, router.routes().size(), "the public health route is registered");
        Assert.assertEquals(Router.PUBLIC_PATH, router.routes().get(0).path(), "the path is stored as given");
        Assert.assertFalse(router.routes().get(0).authRequired(), "a public route does not require authentication");

        Assert.assertThrows(IllegalArgumentException.class,
                () -> router.publicGet("/api/info", OK), "another public path is refused");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> router.publicGet("/api/healthz", OK), "a lookalike path is refused");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> router.publicGet("/", OK), "the root path cannot be public");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> router.publicPrefix("/web", OK), "a public prefix is refused");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> router.add(HttpMethod.GET, "/api/x", false, false, OK),
                "authRequired=false is refused for every other path");
        // t5 F4: only the EXACT path is public - the health sub-tree is no longer registerable, and a
        // public prefix is never allowed. Fails against the pre-fix code, which accepted the sub-tree.
        Assert.assertThrows(IllegalArgumentException.class,
                () -> router.publicGet(Router.PUBLIC_PATH + "/live", OK),
                "a health sub-path is refused");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> router.publicGet("/api/healthx", OK), "a health lookalike is refused");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> router.publicGet("//api/health", OK), "a doubled slash is refused");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> router.publicPrefix(Router.PUBLIC_PATH, OK), "the health path cannot be a public prefix");
        Assert.assertEquals(1, router.routes().size(), "every refused registration added nothing");

        // A trailing slash is normalized by Route BEFORE the rule is applied, so
        // publicGet("/api/health/") is accepted as the exact public path and adds no extra surface.
        Router normalizing = new Router();
        normalizing.publicGet(Router.PUBLIC_PATH + "/", OK);
        Assert.assertEquals(1, normalizing.routes().size(), "the trailing-slash spelling registers one route");
        Assert.assertEquals(Router.PUBLIC_PATH, normalizing.routes().get(0).path(),
                "the trailing slash is normalized to the exact public path");
        Assert.assertFalse(normalizing.routes().get(0).prefixMatch(), "it is not a prefix route");

        router.get("/api/dupe", OK);
        Assert.assertThrows(IllegalStateException.class, () -> router.get("/api/dupe", OK),
                "a duplicate method/path pair is refused");
        Assert.assertThrows(IllegalStateException.class, () -> router.publicGet(Router.PUBLIC_PATH, OK),
                "registering the public path twice is refused");
        Assert.assertEquals(2, router.routes().size(), "the refused duplicates add nothing");
    }

    public void anonymousRequestsAreUnauthorizedAndLearnNothing() throws Exception {
        Router router = boundRouter();
        router.get("/api/secret", context -> context.sendText(200, null, "SECRET-PAYLOAD"));

        FakeTransport secret = FakeTransport.get("/api/secret");
        handle(router, secret, "req-1");
        Assert.assertEquals(401, secret.status(), "a protected route without credentials is 401");
        Assert.assertTrue(secret.bodyText().contains("\"code\":\"unauthorized\""),
                "the standard error envelope is used: " + secret.bodyText());
        Assert.assertFalse(secret.bodyText().contains("SECRET-PAYLOAD"), "the handler never ran");
        Assert.assertNotNull(secret.responseHeader("WWW-Authenticate"), "a challenge is sent");
        Assert.assertTrue(secret.responseHeader("WWW-Authenticate").contains("Basic realm=\"CM Insight\""),
                "the realm is advertised: " + secret.responseHeader("WWW-Authenticate"));
        Assert.assertTrue(secret.contentType().startsWith("application/json"),
                "the error body is JSON: " + secret.contentType());

        FakeTransport unknown = FakeTransport.get("/api/does-not-exist");
        handle(router, unknown, "req-2");
        Assert.assertEquals(401, unknown.status(), "an unknown path is authenticated first: 401, never 404");
        Assert.assertEquals(secret.bodyText(), unknown.bodyText(),
                "the response is identical, so it cannot reveal whether the path exists");
        Assert.assertFalse(unknown.bodyText().contains("not_found"), "no not_found code leaks");

        FakeTransport anonymousPost = FakeTransport.post("/api/secret");
        handle(router, anonymousPost, "req-3");
        Assert.assertEquals(401, anonymousPost.status(), "an anonymous wrong-method request is 401, never 405");

        FakeTransport badCredentials = FakeTransport.get("/api/secret")
                .withHeader("Authorization", TestSupport.basic(USER, "wrong"));
        handle(router, badCredentials, "req-4");
        Assert.assertEquals(401, badCredentials.status(), "wrong credentials are 401");
        Assert.assertFalse(badCredentials.bodyText().contains("wrong"),
                "the response never echoes the submitted credential: " + badCredentials.bodyText());
    }

    public void authenticatedRequestsReachTheHandlerWithAPrincipal() throws Exception {
        Router router = boundRouter();
        Principal[] seen = new Principal[1];
        router.get("/api/secret", context -> {
            seen[0] = context.principal();
            context.sendJson(200, "{\"ok\":true}");
        });

        FakeTransport transport = authorized(FakeTransport.get("/api/secret"));
        handle(router, transport, "req");
        Assert.assertEquals(200, transport.status(), "an authenticated request is handled");
        Assert.assertEquals("{\"ok\":true}", transport.bodyText(), "the handler wrote the body");
        Assert.assertNotNull(seen[0], "the router published the principal");
        Assert.assertEquals(USER, seen[0].user(), "the principal is the authenticated user");
        Assert.assertTrue(transport.contentType().startsWith("application/json"),
                "the content type is the one the handler chose: " + transport.contentType());
    }

    public void unknownPathsAndWrongMethodsProduceCleanJson() throws Exception {
        Router router = boundRouter();
        router.get("/api/info", OK);

        FakeTransport notFound = authorized(FakeTransport.get("/api/nothing"));
        handle(router, notFound, "req");
        Assert.assertEquals(404, notFound.status(), "an unknown path is 404 once authenticated");
        Assert.assertEquals("{\"error\":{\"code\":\"not_found\",\"message\":\"No such endpoint\"}}",
                notFound.bodyText(), "the 404 body is the clean JSON envelope");
        Assert.assertFalse(notFound.bodyText().contains("Exception"), "no exception text leaks");

        FakeTransport wrongMethod = authorized(FakeTransport.post("/api/info"));
        handle(router, wrongMethod, "req");
        Assert.assertEquals(405, wrongMethod.status(), "a known path with the wrong method is 405");
        Assert.assertEquals(
                "{\"error\":{\"code\":\"method_not_allowed\",\"message\":\"Method not allowed for this path\"}}",
                wrongMethod.bodyText(), "the 405 body is clean JSON");
        Assert.assertEquals("GET, HEAD", wrongMethod.responseHeader("Allow"),
                "the allowed methods are advertised: " + wrongMethod.responseHeader("Allow"));

        router.publicGet(Router.PUBLIC_PATH, context -> context.sendJson(200, "{\"status\":\"UP\"}"));
        FakeTransport publicWrongMethod = FakeTransport.post(Router.PUBLIC_PATH);
        handle(router, publicWrongMethod, "req");
        Assert.assertEquals(405, publicWrongMethod.status(), "the public path answers 405 without credentials");
        Assert.assertFalse(publicWrongMethod.bodyText().contains("unauthorized"),
                "no authentication challenge is produced for the public path");
    }

    public void aHandlerFailureBecomesACleanJson500() throws Exception {
        Router router = boundRouter();
        router.get("/api/boom", context -> {
            throw new IllegalStateException("TELLTALE-9911-INTERNAL");
        });

        FakeTransport transport = authorized(FakeTransport.get("/api/boom"));
        handle(router, transport, "req");
        Assert.assertEquals(500, transport.status(), "a handler failure is a 500");
        Assert.assertEquals("{\"error\":{\"code\":\"internal_error\",\"message\":\"Request could not be processed\"}}",
                transport.bodyText(), "the 500 body is fixed text");
        Assert.assertFalse(transport.bodyText().contains("TELLTALE-9911-INTERNAL"),
                "the exception message never leaks: " + transport.bodyText());
        Assert.assertFalse(transport.bodyText().contains("IllegalStateException"), "the exception type never leaks");
        Assert.assertFalse(transport.bodyText().contains("\tat "), "no stack trace is sent");
        Assert.assertFalse(transport.bodyText().contains("com.mraibo"), "no package name is sent");

        router.get("/api/silent", context -> {
            // Deliberately sends nothing.
        });
        FakeTransport silent = authorized(FakeTransport.get("/api/silent"));
        handle(router, silent, "req-2");
        Assert.assertEquals(500, silent.status(), "a handler that writes nothing is an internal error");
        Assert.assertEquals("{\"error\":{\"code\":\"internal_error\",\"message\":\"Endpoint produced no response\"}}",
                silent.bodyText(), "the wiring bug is reported instead of hanging the client");
    }

    public void headIsAnsweredByTheGetRoute() throws Exception {
        Router router = boundRouter();
        router.get("/api/info", context -> context.sendJson(200, "{\"info\":true}"));
        router.publicGet(Router.PUBLIC_PATH, context -> context.sendJson(200, "{\"status\":\"UP\"}"));

        FakeTransport head = authorized(FakeTransport.of("HEAD", "/api/info"));
        handle(router, head, "req");
        Assert.assertEquals(200, head.status(), "HEAD is answered by the GET route");
        Assert.assertEquals("{\"info\":true}", head.bodyText(), "the GET handler runs for HEAD");

        FakeTransport headHealth = FakeTransport.of("HEAD", Router.PUBLIC_PATH);
        handle(router, headHealth, "req-2");
        Assert.assertEquals(200, headHealth.status(), "the public health route answers HEAD without credentials");
        Assert.assertEquals("{\"status\":\"UP\"}", headHealth.bodyText(), "the same body is produced");

        FakeTransport headUnknown = authorized(FakeTransport.of("HEAD", "/api/nothing"));
        handle(router, headUnknown, "req-3");
        Assert.assertEquals(404, headUnknown.status(), "HEAD for an unknown path is 404");
    }

    public void aRouterWithoutAnAuthenticatorFailsClosed() throws Exception {
        Router router = new Router();
        router.get("/api/secret", OK);

        FakeTransport transport = authorized(FakeTransport.get("/api/secret"));
        handle(router, transport, "req");
        Assert.assertEquals(500, transport.status(), "an unbound router never serves a protected route");
        Assert.assertEquals(
                "{\"error\":{\"code\":\"internal_error\",\"message\":\"Authentication is not configured on this server\"}}",
                transport.bodyText(), "the wiring bug is reported loudly");
    }

    public void malformedPathsAreRefusedBeforeRouting() throws Exception {
        Router router = boundRouter();
        router.get("/api/secret", OK);

        FakeTransport traversal = FakeTransport.get("/api/../etc/passwd");
        handle(router, traversal, "req");
        Assert.assertEquals(400, traversal.status(), "a path containing '..' is refused");
        Assert.assertTrue(traversal.bodyText().contains("\"code\":\"bad_request\""),
                "the bad_request envelope is used: " + traversal.bodyText());

        FakeTransport relative = FakeTransport.get("api/secret");
        handle(router, relative, "req-2");
        Assert.assertEquals(400, relative.status(), "a path that does not start with '/' is refused");
    }
}
