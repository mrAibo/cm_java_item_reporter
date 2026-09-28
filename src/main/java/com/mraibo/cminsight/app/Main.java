package com.mraibo.cminsight.app;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.ClassificationRules;
import com.mraibo.cminsight.config.ConfigException;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.RepositoryProfileLoader;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.config.WebAuthSettings;
import com.mraibo.cminsight.core.FeatureIds;
import com.mraibo.cminsight.core.FeatureModule;
import com.mraibo.cminsight.core.FeatureRegistry;
import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.security.SecurityPolicy;
import com.mraibo.cminsight.web.Router;
import com.mraibo.cminsight.web.WebServer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process entry point: configuration, secret resolution, lifecycle wiring and shutdown.
 *
 * <p>The HTTP surface itself belongs to {@link WebServer}, which installs and protects the mandatory
 * routes. This class never builds a route, never touches a connection and never prints a credential.
 */
public final class Main {

    /**
     * Reported by {@code --version} and, for authenticated callers, by {@code GET /api/info}.
     *
     * <p>Kept in step by hand with {@code APP_VERSION} in {@code build.sh} and {@code DEFAULT_VERSION}
     * in {@code WebServer}. A single source of truth would need a pre-compile step, which the
     * no-build-tool constraint does not allow; if you bump the version, bump all three.
     */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    /**
     * Scalar configuration keys this build understands.
     *
     * <p>This is an exact set rather than a list of prefixes on purpose: with a prefix rule, a typo
     * such as {@code web.prt=8080} is silently swallowed because it still starts with {@code web.},
     * which is exactly the misconfiguration an operator most needs to be told about.
     */
    private static final Set<String> KNOWN_CONFIG_KEYS = Set.of(
            "app.name", "app.version", "app.mode",
            "web.bind", "web.port", "web.threads", "web.backlog",
            "web.auth.user", "web.auth.user.env", "web.auth.user.file",
            "web.auth.password", "web.auth.password.env", "web.auth.password.file",
            "web.auth.maxFailures", "web.auth.lockout", "web.auth.maxTrackedKeys",
            "cm.pool.size", "cm.pool.borrow.timeout.ms",
            "cm.pool.max.age.minutes", "cm.pool.max.operations",
            "jdbc.pool.size", "jdbc.pool.borrow.timeout.ms",
            "statistics.threads", "statistics.itemtype.timeout.seconds",
            "statistics.scan.timeout.seconds",
            "cache.metadata.ttl.seconds", "cache.statistics.ttl.seconds",
            "profiles.dir", "classifications.file", "secrets.dir",
            "data.dir", "reports.dir", "logs.dir",
            "repository.auto.activate");

    /** Genuinely open families whose keys are discovered from their own naming scheme. */
    private static final List<String> OPEN_CONFIG_PREFIXES = List.of(
            ClassificationRules.CONFIG_PREFIX);

    private Main() {
    }

    public static void main(String[] args) {
        int exit;
        try {
            exit = execute(args);
        } catch (ConfigException e) {
            System.err.println("ERROR configuration: " + e.getMessage());
            exit = 3;
        } catch (IOException e) {
            System.err.println("ERROR io: " + e.getMessage());
            exit = 4;
        } catch (Exception e) {
            System.err.println("ERROR startup: " + e);
            exit = 1;
        }
        if (exit != 0) {
            System.exit(exit);
        }
    }

    private static int execute(String[] args) throws Exception {
        Path configPath = Path.of("conf", "application.properties");
        boolean printConfig = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> {
                    if (i + 1 >= args.length) {
                        throw new ConfigException("--config requires a file path");
                    }
                    configPath = Path.of(args[++i]);
                }
                case "--version", "-V" -> {
                    System.out.println("CM Insight " + VERSION);
                    return 0;
                }
                case "--help", "-h" -> {
                    usage(System.out);
                    return 0;
                }
                case "--self-test" -> {
                    return SelfCheck.run();
                }
                case "--print-config" -> printConfig = true;
                default -> throw new ConfigException("Unknown argument '" + args[i] + "'; use --help");
            }
        }

        AppConfig config = AppConfig.load(configPath);
        List<String> warnings = new ArrayList<>();

        Path secretsDir = Path.of(config.get("secrets.dir", "conf/secrets"));
        SecretResolver secrets = SecretResolver.system(secretsDir);
        WebAuthSettings auth = WebAuthSettings.resolve(config, secrets);
        warnings.addAll(auth.warnings());

        String bind = config.webBind();
        int port = config.webPort();
        // Fail closed before anything is opened. This is the operator-facing check: a refusal here
        // becomes a configuration error and process exit code 3. WebServer.start() applies the same
        // policy again immediately before it binds, as defence in depth; reached through Main that
        // second copy is unreachable, and if it ever did fire it would surface as a startup failure
        // with exit code 1.
        try {
            SecurityPolicy.validateWebExposure(auth, bind, port);
        } catch (IllegalStateException e) {
            throw new ConfigException(e.getMessage(), e);
        }

        RepositoryProfileLoader profileLoader =
                new RepositoryProfileLoader(Path.of(config.get("profiles.dir", "conf/profiles")));
        List<RepositoryProfile> profiles = profileLoader.loadAll();
        warnings.addAll(profileLoader.diagnostics());

        FeatureRegistry features = FeatureRegistry.defaults(config);
        warnings.addAll(features.warnings());

        ClassificationRules classifications = ClassificationRules.load(config);
        warnings.addAll(classifications.diagnostics());

        warnings.addAll(unknownKeyWarnings(config));

        if (printConfig) {
            printEffectiveConfiguration(config, auth, features, profiles, classifications, secretsDir, bind, port);
            printWarnings(warnings);
            return 0;
        }

        RepositoryManager repositories = new RepositoryManager(RepositoryContext::new);
        activateConfiguredRepository(config, profiles, repositories);

        return serve(config, auth, repositories, warnings);
    }

    private static int serve(AppConfig config,
                             WebAuthSettings auth,
                             RepositoryManager repositories,
                             List<String> warnings) throws Exception {
        Router router = new Router();
        WebServer server = new WebServer(config, auth, router);

        CountDownLatch shutdown = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();

        Thread hook = new Thread(() -> {
            if (closed.compareAndSet(false, true)) {
                closeQuietly(server, repositories);
            }
            shutdown.countDown();
        }, "cm-insight-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        try {
            server.start();
            warnings.addAll(server.warnings());

            printStartupBanner(config, server, auth, repositories, warnings);

            shutdown.await();
            return 0;
        } finally {
            if (closed.compareAndSet(false, true)) {
                closeQuietly(server, repositories);
            }
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // The JVM is already shutting down; the hook owns cleanup.
            }
        }
    }

    /**
     * Optionally activates the repository named by {@code repository.auto.activate}.
     *
     * <p>Goal 01 has no IBM CM adapter, so nothing is activated by default: an operator picks a
     * repository once the adapter exists. When activation is requested explicitly, a failure aborts
     * startup rather than leaving a silently unusable console.
     */
    private static void activateConfiguredRepository(AppConfig config,
                                                     List<RepositoryProfile> profiles,
                                                     RepositoryManager repositories)
            throws Exception {
        String requested = config.get("repository.auto.activate", null);
        if (requested == null || requested.isBlank()) {
            return;
        }
        String id = requested.trim();
        RepositoryProfile profile = profiles.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ConfigException("repository.auto.activate names unknown repository '"
                        + id + "'. Configured repositories: "
                        + (profiles.isEmpty() ? "(none)" : String.join(", ", profiles.stream()
                                .map(RepositoryProfile::id).toList()))));
        repositories.switchTo(profile);
    }

    private static void printStartupBanner(AppConfig config,
                                            WebServer server,
                                            WebAuthSettings auth,
                                            RepositoryManager repositories,
                                            List<String> warnings) {
        System.out.println("CM Insight " + VERSION);
        System.out.println("  config file      : " + describePath(config.sourcePath()));
        System.out.println("  listening        : http://" + server.bindAddress() + ":" + server.port());
        System.out.println("  web user         : " + auth.user() + " [" + auth.userSource().describe() + "]");
        System.out.println("  web password     : <redacted> [" + auth.passwordSource().describe() + "]");
        System.out.println("  repository state : " + repositories.state().name()
                + " (" + repositories.state().description() + ")"
                + (repositories.activeRepositoryId() == null ? "" : ", " + repositories.activeRepositoryId()));
        System.out.println("  read-only        : V1/V2 performs no IBM CM writes");
        printWarnings(warnings);
        for (String note : repositories.diagnostics()) {
            System.out.println("  lifecycle        : " + note);
        }
    }

    private static void printEffectiveConfiguration(AppConfig config,
                                                     WebAuthSettings auth,
                                                     FeatureRegistry features,
                                                     List<RepositoryProfile> profiles,
                                                     ClassificationRules classifications,
                                                     Path secretsDir,
                                                     String bind,
                                                     int port) {
        System.out.println("CM Insight " + VERSION + " effective configuration");
        System.out.println("  config file       : " + describePath(config.sourcePath()));
        System.out.println("  web.bind          : " + bind);
        System.out.println("  web.port          : " + port);
        System.out.println("  web user          : " + auth.user() + " [" + auth.userSource().describe() + "]");
        System.out.println("  web password      : <redacted> [" + auth.passwordSource().describe() + "]");
        System.out.println("  default creds     : " + auth.defaultCredentials());
        System.out.println("  secrets dir       : " + secretsDir.toAbsolutePath());
        System.out.println("  profiles dir      : " + config.get("profiles.dir", "conf/profiles"));
        System.out.println("  classification    : " + classifications.ruleCount() + " rule(s), fallback '"
                + classifications.fallbackLabel() + "'");
        System.out.println("  features          :");
        features.states().forEach((id, enabled) ->
                System.out.println("      " + id + " = " + enabled));
        System.out.println("  repositories      : " + profiles.size());
        for (RepositoryProfile profile : profiles) {
            System.out.println("      " + profile.id() + " (" + profile.displayName() + ", "
                    + profile.databaseVendor() + ", SSID " + profile.ssid() + ")"
                    + (profile.credentialEnvNames().isEmpty()
                            ? "" : " credentials: " + String.join(", ", profile.credentialEnvNames())));
        }
    }

    /**
     * Reports configuration keys that no layer understands.
     *
     * <p>{@code feature.*} keys are excluded because {@link FeatureRegistry} already reports the
     * unknown ones against its own module list, and reporting them twice would train an operator to
     * ignore warnings.
     */
    private static List<String> unknownKeyWarnings(AppConfig config) {
        Set<String> known = new LinkedHashSet<>(KNOWN_CONFIG_KEYS);
        for (FeatureModule module : FeatureRegistry.defaultModules()) {
            known.add(FeatureRegistry.configKey(module.id()));
        }

        List<String> warnings = new ArrayList<>();
        for (String key : config.keys()) {
            if (key.startsWith(FeatureIds.CONFIG_PREFIX)) {
                continue;
            }
            if (known.contains(key) || OPEN_CONFIG_PREFIXES.stream().anyMatch(key::startsWith)) {
                continue;
            }
            warnings.add("Unknown configuration key '" + key + "' is ignored.");
        }
        return warnings;
    }

    private static void printWarnings(List<String> warnings) {
        // The same observation can arrive from more than one layer (auth, secret resolution, the web
        // server). Repeating it once per layer trains operators to ignore warnings, so print each
        // distinct message once, in the order it first appeared.
        for (String warning : new LinkedHashSet<>(warnings)) {
            System.out.println("  WARN             : " + warning);
        }
    }

    private static void closeQuietly(WebServer server, RepositoryManager repositories) {
        try {
            server.close();
        } catch (Exception e) {
            System.err.println("ERROR closing the web server: " + e.getMessage());
        }
        try {
            repositories.close();
        } catch (Exception e) {
            System.err.println("ERROR closing the repository manager: " + e.getMessage());
        }
    }

    private static String describePath(Path path) {
        return path == null ? "(built from properties)" : path.toString();
    }

    private static void usage(PrintStream out) {
        out.println("CM Insight " + VERSION);
        out.println();
        out.println("Usage: cm-insight [options]");
        out.println();
        out.println("  --config <path>    configuration file (default conf/application.properties)");
        out.println("  --print-config     print the effective configuration with secrets redacted, then exit");
        out.println("  --self-test        run the built-in artifact self-check, then exit");
        out.println("  --version, -V      print the version, then exit");
        out.println("  --help, -h         print this help, then exit");
        out.println();
        out.println("This application reads its configuration only from --config.");
        out.println();
        out.println("Environment, read by the launcher scripts rather than by this class:");
        out.println("  JAVA_HOME             JDK 17+ used to compile and run");
        out.println("  CM_INSIGHT_CONFIG     configuration file used by bin/cm-insight, bin/start.sh,");
        out.println("                        bin/status.sh and bin/doctor.sh");
        out.println("  CM_INSIGHT_JAVA_OPTS  extra JVM options used by bin/cm-insight");
        out.println();
        out.println("Credentials are never read from tracked configuration: use '<key>.env' or");
        out.println("'<key>.file' indirection, for example web.auth.password.env=CM_INSIGHT_WEB_PASSWORD.");
    }
}
