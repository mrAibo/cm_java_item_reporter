package com.mraibo.cminsight.web;

import com.mraibo.cminsight.security.AuthResult;
import com.mraibo.cminsight.security.Authenticator;
import com.mraibo.cminsight.security.LoginThrottle;
import com.mraibo.cminsight.web.http.BodyLimitExceededException;
import com.mraibo.cminsight.web.http.HttpMethod;
import com.mraibo.cminsight.web.http.HttpStatus;
import com.mraibo.cminsight.web.http.RequestContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Method and path routing with authentication enforced by the layer, not by convention.
 *
 * <ul>
 *   <li>every registration is authenticated unless it went through an explicit {@code public*}
 *       method, and those methods only accept the single path in {@link #PUBLIC_PATH};</li>
 *   <li>longest path wins, and an exact route beats a prefix route of the same length;</li>
 *   <li>{@code HEAD} is answered by the {@code GET} route when no {@code HEAD} route exists;</li>
 *   <li>a request is authenticated <em>before</em> a 404 or 405 is produced, so an unauthenticated
 *       caller learns nothing about which paths exist;</li>
 *   <li>unhandled exceptions become {@code 500} with a fixed JSON body: no stack trace, no exception
 *       message and therefore no credential that a lower layer might have put in one.</li>
 * </ul>
 */
public final class Router {

    /** The only path that may be registered without authentication. */
    public static final String PUBLIC_PATH = "/api/health";

    /** Realm advertised in the {@code WWW-Authenticate} challenge. */
    private static final String CHALLENGE = "Basic realm=\"" + Authenticator.REALM + "\", charset=\"UTF-8\"";

    private final List<Route> routes = new ArrayList<>();
    private volatile Authenticator authenticator;

    /** Binds the authenticator used for every protected route. Called once by the server at startup. */
    public void bindAuthenticator(Authenticator authenticator) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    /**
     * Registers a route.
     *
     * @throws IllegalArgumentException when {@code authRequired} is false for a path other than
     *                                  {@link #PUBLIC_PATH}
     * @throws IllegalStateException    when the method/path pair is already registered
     */
    public void add(HttpMethod method, String path, boolean authRequired, boolean prefixMatch,
                    RequestHandler handler) {
        Route candidate = new Route(method, path, authRequired, prefixMatch, handler);
        if (!authRequired) {
            requirePublicPathAllowed(candidate);
        }
        synchronized (routes) {
            for (Route existing : routes) {
                if (existing.method() == candidate.method() && existing.path().equals(candidate.path())) {
                    throw new IllegalStateException(
                            "Route already registered: " + existing.method() + " " + existing.path());
                }
            }
            routes.add(candidate);
        }
    }

    /** Registers an authenticated GET route. */
    public void get(String path, RequestHandler handler) {
        add(HttpMethod.GET, path, true, false, handler);
    }

    /**
     * Registers an unauthenticated GET route.
     *
     * @throws IllegalArgumentException for every path except {@link #PUBLIC_PATH}
     */
    public void publicGet(String path, RequestHandler handler) {
        add(HttpMethod.GET, path, false, false, handler);
    }

    /**
     * Registers an unauthenticated GET prefix.
     *
     * <p>Under the current policy no public prefix can exist: only the exact path
     * {@link #PUBLIC_PATH} may be unauthenticated, so this method always refuses. It is retained so
     * that the refusal is explicit and testable rather than an accidental omission.
     */
    public void publicPrefix(String prefix, RequestHandler handler) {
        add(HttpMethod.GET, prefix, false, true, handler);
    }

    /** Registers an authenticated POST route. */
    public void post(String path, RequestHandler handler) {
        add(HttpMethod.POST, path, true, false, handler);
    }

    /** A defensive snapshot of the registered routes, for diagnostics. */
    public List<Route> routes() {
        synchronized (routes) {
            return List.copyOf(routes);
        }
    }

    /**
     * Matches, authenticates and dispatches one request. Never throws for a request-level failure:
     * the response is written here, so a transport-level failure propagates as
     * {@link java.io.UncheckedIOException} and nothing else does.
     */
    public void handle(RequestContext ctx) throws Exception {
        Objects.requireNonNull(ctx, "ctx");
        try {
            dispatch(ctx);
        } catch (BodyLimitExceededException e) {
            if (!ctx.responseSent()) {
                ctx.sendError(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large",
                        "Request body is larger than this endpoint accepts");
            }
        } catch (Exception e) {
            if (!ctx.responseSent()) {
                ctx.sendError(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Request could not be processed");
            }
            logFailure(ctx, e);
        }
    }

    private void dispatch(RequestContext ctx) throws Exception {
        String path = ctx.path();
        if (!isSafePath(path)) {
            ctx.sendError(HttpStatus.BAD_REQUEST, "bad_request", "Malformed request path");
            return;
        }

        List<Route> pathMatches = matches(path);
        Route selected = select(pathMatches, ctx.method());
        if (selected != null) {
            if (!selected.authRequired()) {
                invoke(ctx, selected);
                return;
            }
            if (!authenticate(ctx)) {
                return;
            }
            invoke(ctx, selected);
            return;
        }

        // The path matched a different method, or nothing at all. Either way, authenticate unless the
        // only thing that could be revealed is the public path itself.
        boolean pathExists = !pathMatches.isEmpty();
        boolean onlyPublic = pathExists && pathMatches.stream().noneMatch(Route::authRequired);
        if (!onlyPublic && !authenticate(ctx)) {
            return;
        }
        if (pathExists) {
            methodNotAllowed(ctx, pathMatches);
        } else {
            ctx.sendError(HttpStatus.NOT_FOUND, "not_found", "No such endpoint");
        }
    }

    private boolean authenticate(RequestContext ctx) {
        Authenticator auth = this.authenticator;
        if (auth == null) {
            // Fail closed and be loud about it: an unbound router is a wiring bug, not a client error.
            ctx.sendError(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                    "Authentication is not configured on this server");
            return false;
        }
        String remoteAddress = ctx.remoteAddress();
        AuthResult result = auth.authenticate(ctx.header("Authorization"), remoteAddress);
        if (result.lockedOut()) {
            long retryAfter = LoginThrottle.secondsCeil(auth.retryAfter(remoteAddress));
            if (retryAfter > 0L) {
                ctx.setResponseHeader("Retry-After", Long.toString(retryAfter));
            }
            ctx.sendError(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests",
                    "Too many failed authentication attempts; try again later");
            return false;
        }
        if (!result.authenticated() || result.principal() == null) {
            ctx.setResponseHeader("WWW-Authenticate", CHALLENGE);
            ctx.sendError(HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication required");
            return false;
        }
        ctx.setPrincipal(result.principal());
        return true;
    }

    private static void invoke(RequestContext ctx, Route route) throws Exception {
        route.handler().handle(ctx);
        if (!ctx.responseSent()) {
            ctx.sendError(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Endpoint produced no response");
        }
    }

    private List<Route> matches(String path) {
        List<Route> found = new ArrayList<>(4);
        synchronized (routes) {
            for (Route route : routes) {
                if (route.matchesPath(path)) {
                    found.add(route);
                }
            }
        }
        return found;
    }

    private static Route select(List<Route> pathMatches, HttpMethod method) {
        if (method == null) {
            return null;
        }
        Route best = bestMatch(pathMatches, method);
        if (best == null && method == HttpMethod.HEAD) {
            best = bestMatch(pathMatches, HttpMethod.GET);
        }
        return best;
    }

    private static Route bestMatch(List<Route> pathMatches, HttpMethod method) {
        Route best = null;
        for (Route candidate : pathMatches) {
            if (candidate.method() != method) {
                continue;
            }
            if (best == null || isMoreSpecific(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean isMoreSpecific(Route candidate, Route current) {
        if (candidate.path().length() != current.path().length()) {
            return candidate.path().length() > current.path().length();
        }
        return current.prefixMatch() && !candidate.prefixMatch();
    }

    private static void methodNotAllowed(RequestContext ctx, List<Route> pathMatches) {
        Set<String> allowed = new LinkedHashSet<>();
        for (Route route : pathMatches) {
            allowed.add(route.method().name());
            if (route.method() == HttpMethod.GET) {
                allowed.add(HttpMethod.HEAD.name());
            }
        }
        ctx.setResponseHeader("Allow", String.join(", ", allowed));
        ctx.sendError(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Method not allowed for this path");
    }

    private static boolean isSafePath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') {
            return false;
        }
        if (path.contains("..")) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static void logFailure(RequestContext ctx, Exception failure) {
        // Only the exception type is logged. A message from a lower layer can carry a connection URL
        // or a credential, and it is never worth that risk.
        System.err.println("cm-insight web: request failed id=" + ctx.requestId()
                + " " + ctx.method() + " " + ctx.path()
                + " error=" + failure.getClass().getName());
    }

    /**
     * Only the exact {@link #PUBLIC_PATH} may be unauthenticated.
     *
     * <p>Neither a subtree such as {@code /api/health/x} nor a public prefix is permitted: "only
     * /api/health is unauthenticated" must hold literally, or a future route could slip an
     * unauthenticated surface under a health-looking name.
     */
    private static void requirePublicPathAllowed(Route candidate) {
        if (!PUBLIC_PATH.equals(candidate.path()) || candidate.prefixMatch()) {
            throw new IllegalArgumentException("Only the exact path " + PUBLIC_PATH + " may be registered "
                    + "without authentication (attempted: " + candidate.path()
                    + (candidate.prefixMatch() ? " as a public prefix" : "")
                    + "). Use an authenticated registration instead.");
        }
    }
}
