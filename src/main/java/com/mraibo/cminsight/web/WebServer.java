package com.mraibo.cminsight.web;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.WebAuthSettings;
import com.mraibo.cminsight.ibm.IbmCmAdapterRegistry;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.security.Authenticator;
import com.mraibo.cminsight.security.LoginThrottle;
import com.mraibo.cminsight.security.SecurityPolicy;
import com.mraibo.cminsight.web.http.BodyLimitExceededException;
import com.mraibo.cminsight.web.http.HttpMethod;
import com.mraibo.cminsight.web.http.HttpStatus;
import com.mraibo.cminsight.web.http.JsonWriter;
import com.mraibo.cminsight.web.http.RequestContext;
import com.mraibo.cminsight.web.http.Transport;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The runtime's only contact point with {@code com.sun.net.httpserver}.
 *
 * <p>Wiring rules this class implements so no caller has to remember them:
 *
 * <ul>
 *   <li>the mandatory routes are installed here: {@code GET /api/health} (public, fixed minimal
 *       body), {@code GET /api/info} (authenticated, name/version/mode only), {@code GET /} and the
 *       authenticated prefix {@code /web} for the bundled static assets. Registering any of them a
 *       second time is a startup error, not a silent override;</li>
 *   <li>the router is bound to an {@link Authenticator} built from {@link WebAuthSettings} plus the
 *       brute-force guard settings from configuration;</li>
 *   <li>the authenticated CM read API is installed by one explicit
 *       {@link #installCmApiRoutes(RepositoryManager, List, IbmCmAdapterRegistry)} call, which also binds
 *       the repository manager the handlers read through. It is optional: a core-only runtime that never
 *       calls it serves the mandatory routes alone, and every CM API route is registered as an
 *       authenticated one;</li>
 *   <li>the exposure policy runs before the socket is opened, so a refused configuration never
 *       listens at all; a non-loopback plain-HTTP bind additionally requires the explicit
 *       {@code web.allowInsecureHttp=true} opt-in (default false) and is announced as a security
 *       warning when it is used;</li>
 *   <li>worker threads are daemons and the work queue is bounded, so a request flood applies
 *       backpressure instead of growing memory without limit.</li>
 * </ul>
 */
public final class WebServer implements AutoCloseable {

    /** Path of the public, minimal liveness endpoint. */
    public static final String HEALTH_PATH = "/api/health";

    /** Path of the authenticated build/version endpoint. */
    public static final String INFO_PATH = "/api/info";

    /** Prefix of the bundled static assets, served from {@code /web/} on the classpath. */
    public static final String STATIC_PREFIX = "/web";

    private static final String HEALTH_BODY = "{\"status\":\"UP\",\"service\":\"cm-insight\"}";
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
                    + "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'";
    private static final String DEFAULT_VERSION = "0.1.0-SNAPSHOT";
    private static final String DEFAULT_MODE = "bootstrap-read-only";
    private static final int MAX_STATIC_NAME = 64;

    private static final String KEY_BIND = "web.bind";
    private static final String KEY_PORT = "web.port";
    private static final String KEY_THREADS = "web.threads";
    private static final String KEY_BACKLOG = "web.backlog";
    private static final String KEY_MAX_FAILURES = "web.auth.maxFailures";
    private static final String KEY_LOCKOUT = "web.auth.lockout";
    private static final String KEY_MAX_TRACKED_KEYS = "web.auth.maxTrackedKeys";
    private static final String KEY_VERSION = "app.version";
    private static final String KEY_MODE = "app.mode";

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern SAFE_ASSET_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final WebAuthSettings auth;
    private final Router router;
    private final Authenticator authenticator;
    private final String bindAddress;
    private final int configuredPort;
    private final int threads;
    private final int backlog;
    private final String version;
    private final String mode;
    private final boolean allowInsecureHttp;
    private final List<String> warnings;

    private HttpServer server;
    private ExecutorService executor;
    /** The optional authenticated CM read API, or null when the runtime installed none. */
    private CmApiRoutes cmApiRoutes;

    public WebServer(AppConfig config, WebAuthSettings auth, Router router) {
        Objects.requireNonNull(config, "config");
        this.auth = Objects.requireNonNull(auth, "auth");
        this.router = Objects.requireNonNull(router, "router");

        this.bindAddress = config.webBind();
        this.configuredPort = config.webPort();
        this.threads = config.webThreads();
        this.backlog = config.getInt(KEY_BACKLOG, 0, 0, 4096);
        this.version = config.get(KEY_VERSION, DEFAULT_VERSION);
        this.mode = config.get(KEY_MODE, DEFAULT_MODE);
        // Fail closed: an absent key is false, and anything that is not exactly true/false is a
        // configuration error rather than a silent yes or no. Parsed by SecurityPolicy so the doctor
        // entry point applies the identical rule.
        this.allowInsecureHttp = SecurityPolicy.allowInsecureHttp(config);

        LoginThrottle throttle = new LoginThrottle(
                config.getInt(KEY_MAX_FAILURES, 5, 1, 1000),
                config.getDuration(KEY_LOCKOUT, Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofHours(24)),
                config.getInt(KEY_MAX_TRACKED_KEYS, 4096, 16, 1_048_576));
        this.authenticator = new Authenticator(auth, throttle);
        this.warnings = SecurityPolicy.exposureWarnings(auth, bindAddress, configuredPort, allowInsecureHttp);
    }

    /** Exposure warnings collected at construction: safe to print, never contains a credential. */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * The effective value of {@code web.allowInsecureHttp} (default {@code false}). True only when the
     * operator explicitly accepted a non-loopback plain-HTTP bind.
     */
    public boolean allowInsecureHttp() {
        return allowInsecureHttp;
    }

    /**
     * Validates the exposure policy, installs the mandatory routes and binds the socket.
     *
     * @throws IllegalStateException when the exposure policy refuses the configuration
     * @throws IOException           when the address cannot be bound
     */
    public void start() throws IOException {
        synchronized (this) {
            if (server != null) {
                throw new IllegalStateException("The web server is already started");
            }
            // Fails closed before a socket exists.
            SecurityPolicy.validateWebExposure(auth, bindAddress, configuredPort, allowInsecureHttp);
            printStartupWarnings();

            router.bindAuthenticator(authenticator);
            installMandatoryRoutes();

            HttpServer created = HttpServer.create(new InetSocketAddress(bindAddress, configuredPort), backlog);
            ExecutorService pool = null;
            try {
                pool = createExecutor();
                created.setExecutor(pool);
                created.createContext("/", this::handleExchange);
                created.start();
            } catch (RuntimeException | Error e) {
                if (pool != null) {
                    pool.shutdownNow();
                }
                created.stop(0);
                throw e;
            }
            this.executor = pool;
            this.server = created;
        }
    }

    /**
     * Installs the authenticated CM read API and binds the repository manager it reads through.
     *
     * <p>Deliberately one additive call: the mandatory routes above are installed exactly as they were,
     * and this registers the Goal 02 routes from {@link CmApiRoutes} on the same router while retaining
     * the manager so the binding is explicit rather than implied by one handler. Calling it twice is a
     * wiring bug and is refused instead of silently re-registering a route.
     *
     * @throws IllegalStateException when the CM API routes have already been installed
     */
    public void installCmApiRoutes(RepositoryManager repositories,
                                   List<RepositoryProfile> profiles,
                                   IbmCmAdapterRegistry adapters) {
        Objects.requireNonNull(repositories, "repositories");
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(adapters, "adapters");
        synchronized (this) {
            if (cmApiRoutes != null) {
                throw new IllegalStateException("The CM API routes are already installed");
            }
            CmApiRoutes installed = new CmApiRoutes(repositories, profiles, adapters);
            installed.install(router);
            this.cmApiRoutes = installed;
        }
    }

    /** The bound port, or the configured port before {@link #start()}. */
    public int port() {
        HttpServer current = server;
        return current == null ? configuredPort : current.getAddress().getPort();
    }

    /** The configured bind address. */
    public String bindAddress() {
        return bindAddress;
    }

    /** Stops the server and then the executor. Idempotent. */
    @Override
    public void close() {
        HttpServer current;
        ExecutorService pool;
        synchronized (this) {
            current = server;
            pool = executor;
            server = null;
            executor = null;
        }
        if (current != null) {
            current.stop(2);
        }
        if (pool != null) {
            pool.shutdownNow();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("cm-insight web: worker threads did not stop within 5s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void printStartupWarnings() {
        for (String warning : auth.warnings()) {
            System.err.println("cm-insight web: warning: " + warning);
        }
        for (String warning : warnings) {
            // An accepted insecure plain-HTTP exposure is an operator decision, not a routine note:
            // it gets its own prefix so it cannot be mistaken for noise in a startup log.
            String prefix = warning.startsWith(SecurityPolicy.INSECURE_HTTP_MARKER)
                    ? "cm-insight web: SECURITY WARNING: "
                    : "cm-insight web: warning: ";
            System.err.println(prefix + warning);
        }
    }

    private void installMandatoryRoutes() {
        router.publicGet(HEALTH_PATH, WebServer::health);
        router.get(INFO_PATH, this::info);
        router.get("/", WebServer::index);
        router.add(HttpMethod.GET, STATIC_PREFIX, true, true, WebServer::staticAsset);
    }

    private static void health(RequestContext ctx) {
        // Fixed body: no version, no configuration, no diagnostics. Liveness only.
        ctx.sendJson(HttpStatus.OK, HEALTH_BODY);
    }

    private void info(RequestContext ctx) {
        ctx.sendJson(HttpStatus.OK, JsonWriter.object(
                "name", "CM Insight",
                "version", version,
                "mode", mode));
    }

    private static void index(RequestContext ctx) {
        byte[] body = readResource("/web/index.html");
        if (body == null) {
            ctx.sendError(HttpStatus.NOT_FOUND, "not_found", "The web console asset is not available");
            return;
        }
        ctx.sendBytes(HttpStatus.OK, "text/html; charset=utf-8", body);
    }

    private static void staticAsset(RequestContext ctx) {
        String path = ctx.path();
        String name = path.length() > STATIC_PREFIX.length() ? path.substring(STATIC_PREFIX.length() + 1) : "";
        String contentType = contentTypeFor(name);
        if (contentType == null) {
            ctx.sendError(HttpStatus.NOT_FOUND, "not_found", "No such asset");
            return;
        }
        byte[] body = readResource("/web/" + name);
        if (body == null) {
            ctx.sendError(HttpStatus.NOT_FOUND, "not_found", "No such asset");
            return;
        }
        ctx.sendBytes(HttpStatus.OK, contentType, body);
    }

    /**
     * Maps an asset name to a content type. A name that is not a plain file name with a known
     * extension is rejected, which is what keeps {@code ..}, sub-directories and unknown types out of
     * the classpath lookup.
     */
    private static String contentTypeFor(String name) {
        if (name.isEmpty() || name.length() > MAX_STATIC_NAME || !SAFE_ASSET_NAME.matcher(name).matches()
                || name.contains("..")) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 1) {
            return null;
        }
        return switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "html", "htm" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js", "mjs" -> "application/javascript; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "txt" -> "text/plain; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "ico" -> "image/x-icon";
            case "woff2" -> "font/woff2";
            default -> null;
        };
    }

    private static byte[] readResource(String resource) {
        try (InputStream in = WebServer.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private void handleExchange(HttpExchange exchange) throws IOException {
        String requestId = requestId(exchange);
        Transport transport = new ExchangeTransport(exchange, requestId);
        RequestContext ctx = new RequestContext(transport, requestId);
        try {
            router.handle(ctx);
        } catch (Exception e) {
            // The router handles its own failures; this is the last-resort guard.
            if (!ctx.responseSent()) {
                try {
                    ctx.sendError(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                            "Request could not be processed");
                } catch (RuntimeException ignored) {
                    // The connection is already unusable; nothing useful can be reported.
                }
            }
        } finally {
            try {
                exchange.close();
            } catch (RuntimeException ignored) {
                // Closing an already-closed exchange is fine.
            }
        }
    }

    private static String requestId(HttpExchange exchange) {
        String supplied = exchange.getRequestHeaders().getFirst("X-Request-Id");
        if (supplied != null) {
            String candidate = supplied.trim();
            if (SAFE_REQUEST_ID.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return UUID.randomUUID().toString();
    }

    private ExecutorService createExecutor() {
        AtomicInteger counter = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "cm-insight-web-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        int queueCapacity = Math.max(threads * 64, 64);
        // Fixed size, bounded queue, caller-runs saturation: a flood blocks the accepting thread
        // instead of queueing unbounded work in memory.
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** The single adapter between {@link Transport} and the JDK HTTP server. */
    private static final class ExchangeTransport implements Transport {

        private final HttpExchange exchange;
        private final String requestId;
        private final Map<String, String> query;
        private volatile boolean responseSent;

        ExchangeTransport(HttpExchange exchange, String requestId) {
            this.exchange = exchange;
            this.requestId = requestId;
            this.query = parseQuery(exchange.getRequestURI().getRawQuery());
        }

        @Override
        public String method() {
            return exchange.getRequestMethod();
        }

        @Override
        public String path() {
            String path = exchange.getRequestURI().getPath();
            return path == null || path.isEmpty() ? "/" : path;
        }

        @Override
        public Map<String, String> query() {
            return Collections.unmodifiableMap(query);
        }

        @Override
        public String header(String name) {
            return name == null ? null : exchange.getRequestHeaders().getFirst(name);
        }

        @Override
        public String remoteAddress() {
            InetSocketAddress remote = exchange.getRemoteAddress();
            if (remote == null) {
                return null;
            }
            InetAddress address = remote.getAddress();
            return address == null ? remote.getHostString() : address.getHostAddress();
        }

        @Override
        public byte[] readBody(int maxBytes) throws IOException {
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be positive");
            }
            InputStream in = exchange.getRequestBody();
            if (in == null) {
                return new byte[0];
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] chunk = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new BodyLimitExceededException(maxBytes);
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }

        @Override
        public void respond(int status, String contentType, byte[] body, Map<String, String> headers)
                throws IOException {
            if (responseSent) {
                throw new IllegalStateException("A response has already been sent for this exchange");
            }
            byte[] payload = body == null ? new byte[0] : body;
            Headers responseHeaders = exchange.getResponseHeaders();
            if (contentType != null && !contentType.isBlank()) {
                responseHeaders.set("Content-Type", contentType);
            }
            responseHeaders.set("Cache-Control", "no-store");
            responseHeaders.set("X-Content-Type-Options", "nosniff");
            responseHeaders.set("Referrer-Policy", "no-referrer");
            responseHeaders.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
            responseHeaders.set("X-Request-Id", requestId);
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() == null || header.getValue() == null) {
                        continue;
                    }
                    responseHeaders.set(header.getKey(), header.getValue());
                }
            }
            responseSent = true;
            boolean head = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
            long declaredLength = head || payload.length == 0 ? -1L : payload.length;
            exchange.sendResponseHeaders(status, declaredLength);
            if (!head && payload.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        }

        @Override
        public boolean isResponseSent() {
            return responseSent;
        }

        private static Map<String, String> parseQuery(String rawQuery) {
            if (rawQuery == null || rawQuery.isEmpty()) {
                return Map.of();
            }
            Map<String, String> parameters = new LinkedHashMap<>();
            for (String pair : rawQuery.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int equals = pair.indexOf('=');
                String name = equals < 0 ? pair : pair.substring(0, equals);
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                String decodedName = decode(name);
                // First occurrence wins: duplicate parameters cannot silently override a value.
                parameters.putIfAbsent(decodedName, decode(value));
            }
            return parameters;
        }

        private static String decode(String value) {
            try {
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return value;
            }
        }
    }
}
