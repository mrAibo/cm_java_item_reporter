package com.mraibo.cminsight.test;

import com.mraibo.cminsight.web.http.BodyLimitExceededException;
import com.mraibo.cminsight.web.http.Transport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * An in-memory {@link Transport}, so routing and authentication are exercised without a socket.
 *
 * <p>Request header names and response header names are both stored lower-cased, which mirrors the
 * case-insensitive semantics of HTTP and lets a test ask for {@code WWW-Authenticate} or
 * {@code www-authenticate} interchangeably.
 */
final class FakeTransport implements Transport {

    private final String method;
    private final String path;
    private final Map<String, String> query;
    private final Map<String, String> requestHeaders = new TreeMap<>();
    private final byte[] requestBody;

    private String remoteAddress = "127.0.0.1";
    private int status = -1;
    private String contentType;
    private byte[] responseBody = new byte[0];
    private final Map<String, String> responseHeaders = new TreeMap<>();
    private boolean responseSent;

    FakeTransport(String method, String path) {
        this(method, path, Map.of(), new byte[0]);
    }

    FakeTransport(String method, String path, Map<String, String> query, byte[] requestBody) {
        this.method = method;
        this.path = path;
        this.query = query == null ? Map.of() : Map.copyOf(query);
        this.requestBody = requestBody == null ? new byte[0] : requestBody.clone();
    }

    static FakeTransport of(String method, String path) {
        return new FakeTransport(method, path);
    }

    static FakeTransport get(String path) {
        return new FakeTransport("GET", path);
    }

    static FakeTransport post(String path) {
        return new FakeTransport("POST", path);
    }

    FakeTransport withHeader(String name, String value) {
        requestHeaders.put(name.toLowerCase(Locale.ROOT), value);
        return this;
    }

    FakeTransport withRemoteAddress(String address) {
        this.remoteAddress = address;
        return this;
    }

    // ------------------------------------------------------------- request side

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public Map<String, String> query() {
        return query;
    }

    @Override
    public String header(String name) {
        return name == null ? null : requestHeaders.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public String remoteAddress() {
        return remoteAddress;
    }

    @Override
    public byte[] readBody(int maxBytes) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (requestBody.length > maxBytes) {
            throw new BodyLimitExceededException(maxBytes);
        }
        return requestBody.clone();
    }

    @Override
    public void respond(int status, String contentType, byte[] body, Map<String, String> headers)
            throws IOException {
        if (responseSent) {
            throw new IllegalStateException("A response has already been sent");
        }
        this.status = status;
        this.contentType = contentType;
        this.responseBody = body == null ? new byte[0] : body.clone();
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (header.getKey() != null && header.getValue() != null) {
                    responseHeaders.put(header.getKey().toLowerCase(Locale.ROOT), header.getValue());
                }
            }
        }
        this.responseSent = true;
    }

    @Override
    public boolean isResponseSent() {
        return responseSent;
    }

    // ------------------------------------------------------------ response side

    int status() {
        return status;
    }

    String contentType() {
        return contentType;
    }

    String bodyText() {
        return new String(responseBody, StandardCharsets.UTF_8);
    }

    /** Case-insensitive lookup of a response header, or null. */
    String responseHeader(String name) {
        return name == null ? null : responseHeaders.get(name.toLowerCase(Locale.ROOT));
    }

    /** Response headers as the router produced them, keyed by lower-cased name. */
    Map<String, String> responseHeaders() {
        return Map.copyOf(responseHeaders);
    }
}
