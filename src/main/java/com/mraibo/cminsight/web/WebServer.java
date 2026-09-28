package com.mraibo.cminsight.web;

import com.mraibo.cminsight.app.Main;
import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.security.BasicAuth;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WebServer implements AutoCloseable {
    private final AppConfig config;
    private HttpServer server;
    private ExecutorService executor;

    public WebServer(AppConfig config) {
        this.config = config;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.webBind(), config.webPort()), 0);
        executor = Executors.newFixedThreadPool(config.webThreads());
        server.setExecutor(executor);

        server.createContext("/api/health", this::health);
        server.createContext("/api/info", new BasicAuth(this::info, config.webUser(), config.webPassword()));
        server.createContext("/", new BasicAuth(this::index, config.webUser(), config.webPassword()));
        server.start();
    }

    private void health(HttpExchange exchange) throws IOException {
        send(exchange, 200, "application/json; charset=utf-8",
                "{\"status\":\"UP\",\"service\":\"cm-insight\"}");
    }

    private void info(HttpExchange exchange) throws IOException {
        send(exchange, 200, "application/json; charset=utf-8",
                "{\"name\":\"CM Insight\",\"version\":\"" + Main.VERSION +
                        "\",\"mode\":\"bootstrap-read-only\"}");
    }

    private void index(HttpExchange exchange) throws IOException {
        try (InputStream in = WebServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                send(exchange, 500, "text/plain; charset=utf-8", "Missing web resource");
                return;
            }
            send(exchange, 200, "text/html; charset=utf-8",
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        if (server != null) server.stop(2);
        if (executor != null) executor.shutdownNow();
    }
}
