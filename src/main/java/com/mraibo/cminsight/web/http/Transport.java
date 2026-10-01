package com.mraibo.cminsight.web.http;

import java.io.IOException;
import java.util.Map;

/**
 * The one and only seam to {@code com.sun.net.httpserver}.
 *
 * <p>Feature controllers and route handlers depend on this interface (through {@link RequestContext}),
 * never on {@code HttpExchange}. That keeps every handler testable without a socket, and keeps the
 * JDK-specific import confined to the single adapter that implements this interface.
 *
 * <p>Implementations are used by exactly one request at a time and are therefore not required to be
 * thread-safe, except where noted.
 */
public interface Transport {

    /** The wire method, verbatim, e.g. {@code "GET"}. Never null for a real request. */
    String method();

    /** The decoded request path, always starting with {@code '/'}. */
    String path();

    /** Decoded query parameters; first occurrence of a repeated name wins. Never null. */
    Map<String, String> query();

    /** Case-insensitive lookup of a request header. Null when absent. */
    String header(String name);

    /** The peer address, or null when the transport cannot supply one. */
    String remoteAddress();

    /**
     * Reads at most {@code maxBytes} bytes of the request body.
     *
     * @throws BodyLimitExceededException when the body is larger than {@code maxBytes}
     * @throws IllegalArgumentException   when {@code maxBytes} is not positive
     * @throws IOException                when the body cannot be read
     */
    byte[] readBody(int maxBytes) throws IOException;

    /**
     * Writes the response. Implementations must add the mandatory security headers
     * ({@code Cache-Control: no-store}, {@code X-Content-Type-Options: nosniff},
     * {@code Referrer-Policy: no-referrer}) themselves, so no caller can forget them.
     *
     * @param status      HTTP status code
     * @param contentType value for {@code Content-Type}; null means "do not set one"
     * @param body        response body; null is treated as empty
     * @param headers     extra response headers, or null
     */
    void respond(int status, String contentType, byte[] body, Map<String, String> headers)
            throws IOException;

    /** True once a response has been started, so callers can avoid sending a second one. */
    boolean isResponseSent();
}
