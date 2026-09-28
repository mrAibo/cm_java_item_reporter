package com.mraibo.cminsight.web;

import com.mraibo.cminsight.web.http.HttpMethod;

import java.util.Objects;

/**
 * A single registered endpoint.
 *
 * <p>{@code authRequired} is carried on the route rather than decided by the handler, so the default
 * posture (authenticated) is the one a caller gets by not saying anything.
 *
 * @param method      HTTP method this route answers
 * @param path        normalized path; always starts with {@code '/'} and never ends with one (except
 *                    for the root), so matching is a plain string compare
 * @param authRequired true when the route needs a valid principal
 * @param prefixMatch true when the route also matches everything below {@code path}
 * @param handler     the endpoint
 */
public record Route(HttpMethod method, String path, boolean authRequired, boolean prefixMatch,
                    RequestHandler handler) {

    public Route {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(handler, "handler");
        path = normalizePath(path);
        if (prefixMatch && "/".equals(path)) {
            throw new IllegalArgumentException("The root path '/' cannot be registered as a prefix route");
        }
    }

    /** True when this route answers the given request path. */
    public boolean matchesPath(String requestPath) {
        if (requestPath == null) {
            return false;
        }
        if (prefixMatch) {
            return requestPath.equals(path) || requestPath.startsWith(path + "/");
        }
        return requestPath.equals(path);
    }

    private static String normalizePath(String raw) {
        Objects.requireNonNull(raw, "path");
        String path = raw.trim();
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Route path must start with '/': " + path);
        }
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Route path must not contain a query or fragment: " + path);
        }
        if (path.contains("..")) {
            throw new IllegalArgumentException("Route path must not contain '..': " + path);
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
