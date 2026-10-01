package com.mraibo.cminsight.web.http;

import com.mraibo.cminsight.security.Principal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything a route handler may know about one request, expressed without any JDK server type.
 *
 * <p>Handlers never see {@code HttpExchange}. They read the request through this facade and write the
 * response through its {@code send*} methods, which is also what keeps the mandatory security headers
 * and the JSON error envelope in one place instead of in every controller.
 *
 * <p>Response bodies handed to {@code sendError} must be constant, non-secret text. A message built
 * from an exception or from a submitted credential can defeat the "no credential in a response" rule,
 * so the router only ever passes fixed strings.
 */
public final class RequestContext {

    /** Content type used for every JSON response. */
    public static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    /** Content type used by {@link #sendText} when the caller passes none. */
    public static final String TEXT_CONTENT_TYPE = "text/plain; charset=utf-8";

    private static final int MAX_ERROR_TEXT_CHARS = 256;

    private final Transport transport;
    private final String requestId;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final Map<String, String> responseHeaders = new ConcurrentHashMap<>();
    private volatile Principal principal;
    private volatile boolean sent;

    public RequestContext(Transport transport, String requestId) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.requestId = requestId == null ? "" : requestId;
    }

    /**
     * The parsed method, or null when the wire method is not one of {@link HttpMethod}'s values.
     *
     * <p>A null result is deliberately not defaulted to {@code GET}: an unknown method must fall out
     * of route matching (the router answers 405), never be dispatched as something it is not.
     */
    public HttpMethod method() {
        return HttpMethod.parse(transport.method()).orElse(null);
    }

    public String path() {
        return transport.path();
    }

    public String query(String name) {
        return name == null ? null : transport.query().get(name);
    }

    public Map<String, String> queryParameters() {
        return Collections.unmodifiableMap(transport.query());
    }

    public String header(String name) {
        return transport.header(name);
    }

    public String remoteAddress() {
        return transport.remoteAddress();
    }

    public String requestId() {
        return requestId;
    }

    /**
     * Reads the request body, enforcing the byte cap here as well as in the transport.
     *
     * @throws BodyLimitExceededException when the body is larger than {@code maxBytes}
     */
    public byte[] readBody(int maxBytes) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        byte[] body = transport.readBody(maxBytes);
        if (body == null) {
            return new byte[0];
        }
        if (body.length > maxBytes) {
            throw new BodyLimitExceededException(maxBytes);
        }
        return body;
    }

    public void setAttribute(String name, Object value) {
        Objects.requireNonNull(name, "name");
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }

    public Object attribute(String name) {
        return name == null ? null : attributes.get(name);
    }

    public void setPrincipal(Principal principal) {
        this.principal = principal;
    }

    public Principal principal() {
        return principal;
    }

    /**
     * Adds a response header to the next (and only) response.
     *
     * <p>Header names and values are validated: a CR, LF or colon can never travel through here, so a
     * value derived from user input cannot split the response or inject a header.
     */
    public void setResponseHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (name.isEmpty() || name.length() > 128) {
            throw new IllegalArgumentException("Invalid response header name");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == ':') {
                throw new IllegalArgumentException("Invalid response header name");
            }
        }
        if (value.length() > 512) {
            throw new IllegalArgumentException("Response header value is too long");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("Invalid response header value");
            }
        }
        responseHeaders.put(name, value);
    }

    /** Extra response headers registered by the router or the handler. */
    public Map<String, String> responseHeaders() {
        return Collections.unmodifiableMap(responseHeaders);
    }

    /** Sends a JSON body. Null is written as the JSON literal {@code null}. */
    public void sendJson(int status, String json) {
        sendBytes(status, JSON_CONTENT_TYPE, (json == null ? "null" : json).getBytes(StandardCharsets.UTF_8));
    }

    /** Sends a text body. A null content type falls back to plain text. */
    public void sendText(int status, String contentType, String body) {
        sendBytes(status, contentType == null ? TEXT_CONTENT_TYPE : contentType,
                (body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
    }

    /** Sends raw bytes. A null body is treated as empty. */
    public void sendBytes(int status, String contentType, byte[] body) {
        ensureNotSent();
        sent = true;
        try {
            transport.respond(status, contentType, body == null ? new byte[0] : body,
                    Map.copyOf(responseHeaders));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write the HTTP response", e);
        }
    }

    /** Sends an empty response with no body. */
    public void sendEmpty(int status) {
        ensureNotSent();
        sent = true;
        try {
            transport.respond(status, null, new byte[0], Map.copyOf(responseHeaders));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write the HTTP response", e);
        }
    }

    /**
     * Sends the standard error envelope: {@code {"error":{"code":"...","message":"..."}}}.
     *
     * <p>The message is control-character-stripped and truncated. Callers must pass a constant string
     * that cannot contain a credential, an {@code Authorization} header or a decoded secret.
     */
    public void sendError(int status, String code, String message) {
        sendJson(status, JsonWriter.error(clean(code, 64), clean(message, MAX_ERROR_TEXT_CHARS)));
    }

    /** True once a response has been started through this context or the transport. */
    public boolean responseSent() {
        return sent || transport.isResponseSent();
    }

    private void ensureNotSent() {
        if (responseSent()) {
            throw new IllegalStateException("A response has already been sent for this request");
        }
    }

    private static String clean(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), maxChars));
        for (int i = 0; i < text.length() && out.length() < maxChars; i++) {
            char c = text.charAt(i);
            out.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return out.toString().trim();
    }
}
