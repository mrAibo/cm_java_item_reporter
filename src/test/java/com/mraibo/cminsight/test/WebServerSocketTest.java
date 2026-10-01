package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.config.WebAuthSettings;
import com.mraibo.cminsight.web.Router;
import com.mraibo.cminsight.web.WebServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

/**
 * End-to-end over a real loopback socket: the JDK HTTP server, the router and the brute-force guard.
 *
 * <p>Each test starts its own server on an operating-system-chosen ephemeral port ({@code web.port=0}),
 * so the throttle state is never shared between them, no port has to be probed (and no probe can lose
 * a race), and every server is closed in a {@code finally} block.
 */
public class WebServerSocketTest {

    private static final String USER = "ops";
    private static final String PASSWORD = "S3cret-Pass-4242";

    /** One check performed against a freshly started server. */
    @FunctionalInterface
    private interface ServerCheck {
        void run(WebServer server, HttpClient client, String base) throws Exception;
    }

    public void healthEndpointIsPublicOnARealSocket() throws Exception {
        withServer((server, client, base) -> {
            Assert.assertTrue(server.port() > 0, "the server bound a real port: " + server.port());
            Assert.assertEquals("127.0.0.1", server.bindAddress(), "the configured bind address is used");
            Assert.assertFalse(server.warnings().stream().anyMatch(warning -> warning.contains(PASSWORD)),
                    "startup warnings never contain the password: " + server.warnings());

            HttpResponse<String> health = get(client, base + WebServer.HEALTH_PATH, null);
            Assert.assertEquals(200, health.statusCode(), "the health endpoint answers without credentials");
            Assert.assertEquals("{\"status\":\"UP\",\"service\":\"cm-insight\"}", health.body(),
                    "the health body is exactly the documented minimal body");
            Assert.assertTrue(health.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                    "the health body is served as JSON");
        });
    }

    public void infoEndpointRequiresCredentialsOnARealSocket() throws Exception {
        withServer((server, client, base) -> {
            HttpResponse<String> anonymous = get(client, base + WebServer.INFO_PATH, null);
            Assert.assertEquals(401, anonymous.statusCode(), "the info endpoint is protected");
            Assert.assertTrue(anonymous.headers().firstValue("WWW-Authenticate").isPresent(),
                    "the 401 carries a challenge");
            Assert.assertFalse(anonymous.body().contains(PASSWORD), "the 401 never contains the password");
        });
    }

    public void correctCredentialsReachTheInfoEndpointOnARealSocket() throws Exception {
        withServer((server, client, base) -> {
            HttpResponse<String> authenticated = get(client, base + WebServer.INFO_PATH,
                    TestSupport.basic(USER, PASSWORD));
            Assert.assertEquals(200, authenticated.statusCode(), "correct credentials are accepted");
            Assert.assertTrue(authenticated.body().contains("CM Insight"),
                    "the info body names the service: " + authenticated.body());
            Assert.assertFalse(authenticated.body().contains(PASSWORD), "the info body never contains the password");
            Assert.assertTrue(authenticated.headers().firstValue("X-Content-Type-Options").isPresent(),
                    "the mandatory security headers are present");
            Assert.assertTrue(authenticated.headers().firstValue("Cache-Control").orElse("").contains("no-store"),
                    "responses are not cacheable");
        });
    }

    public void repeatedBadCredentialsAreThrottledOnARealSocket() throws Exception {
        withServer((server, client, base) -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpResponse<String> rejected = get(client, base + WebServer.INFO_PATH,
                        TestSupport.basic(USER, "wrong-" + attempt));
                Assert.assertEquals(401, rejected.statusCode(), "a bad credential is 401 while attempts remain");
            }
            HttpResponse<String> locked = get(client, base + WebServer.INFO_PATH,
                    TestSupport.basic(USER, "wrong-again"));
            Assert.assertEquals(429, locked.statusCode(), "repeated bad credentials are throttled");
            Assert.assertTrue(locked.headers().firstValue("Retry-After").isPresent(), "a Retry-After hint is sent");
            Assert.assertEquals(429,
                    get(client, base + WebServer.INFO_PATH, TestSupport.basic(USER, PASSWORD)).statusCode(),
                    "the lockout applies before the credentials are compared");
        });
    }

    private static void withServer(ServerCheck check) throws Exception {
        AppConfig config = serverConfig();
        Assert.assertEquals(0, config.webPort(), "the suite asks the operating system for a free port");
        WebAuthSettings auth = WebAuthSettings.resolve(config, new SecretResolver(Map.of(), null));
        WebServer server = new WebServer(config, auth, new Router());
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            check.run(server, client, "http://127.0.0.1:" + server.port());
        } finally {
            server.close();
        }
    }

    private static AppConfig serverConfig() {
        Properties properties = new Properties();
        properties.setProperty("web.bind", "127.0.0.1");
        // 0 means "let the operating system choose". The real bound port is read back through
        // WebServer.port(), so nothing has to be probed and no probe can race another process.
        properties.setProperty("web.port", "0");
        properties.setProperty("web.auth.user", USER);
        properties.setProperty("web.auth.password", PASSWORD);
        properties.setProperty("web.auth.maxFailures", "3");
        properties.setProperty("web.auth.lockout", "60s");
        properties.setProperty("web.threads", "4");
        return AppConfig.fromProperties(properties);
    }

    private static HttpResponse<String> get(HttpClient client, String url, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
