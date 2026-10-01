package com.mraibo.cminsight.web.http;

import java.util.Locale;
import java.util.Optional;

/**
 * The HTTP methods this runtime knows about.
 *
 * <p>Deliberately a closed set: the router cannot dispatch a method it does not understand, which is
 * why {@link #parse(String)} returns an empty {@link Optional} instead of a permissive default. A
 * request whose wire method is unknown must never be treated as {@code GET}.
 */
public enum HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    HEAD,
    OPTIONS;

    /** Parses a wire method, case-insensitively. Empty when the value is null, blank or unknown. */
    public static Optional<HttpMethod> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String candidate = raw.trim().toUpperCase(Locale.ROOT);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        for (HttpMethod method : values()) {
            if (method.name().equals(candidate)) {
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }
}
