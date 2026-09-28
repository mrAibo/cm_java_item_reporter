package com.mraibo.cminsight.security;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class BasicAuth implements HttpHandler {
    private final HttpHandler delegate;
    private final String expectedUser;
    private final String expectedPassword;

    public BasicAuth(HttpHandler delegate, String expectedUser, String expectedPassword) {
        this.delegate = delegate;
        this.expectedUser = expectedUser;
        this.expectedPassword = expectedPassword;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Basic ")) {
            unauthorized(exchange);
            return;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
            String[] pair = decoded.split(":", 2);
            if (pair.length != 2 || !constantTime(pair[0], expectedUser) || !constantTime(pair[1], expectedPassword)) {
                unauthorized(exchange);
                return;
            }
            delegate.handle(exchange);
        } catch (IllegalArgumentException e) {
            unauthorized(exchange);
        }
    }

    private static boolean constantTime(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static void unauthorized(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"CM Insight\"");
        byte[] body = "Authentication required".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(401, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
