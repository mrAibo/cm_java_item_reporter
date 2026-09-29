package com.mraibo.cminsight.app;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.AppPaths;
import com.mraibo.cminsight.config.ClassificationRules;
import com.mraibo.cminsight.config.ConfigException;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.RepositoryProfileLoader;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.config.WebAuthSettings;
import com.mraibo.cminsight.core.CloseState;
import com.mraibo.cminsight.core.CmPoolDiagnostics;
import com.mraibo.cminsight.core.FeatureIds;
import com.mraibo.cminsight.core.FeatureModule;
import com.mraibo.cminsight.core.FeatureRegistry;
import com.mraibo.cminsight.core.JdbcPoolSettings;
import com.mraibo.cminsight.core.StatisticsSettings;
import com.mraibo.cminsight.db.JdbcDrivers;
import com.mraibo.cminsight.ibm.CmAdapterProvider;
import com.mraibo.cminsight.ibm.CmAdapterSettings;
import com.mraibo.cminsight.ibm.IbmCmAdapterRegistry;
import com.mraibo.cminsight.repository.ActivationFailedException;
import com.mraibo.cminsight.repository.ProductionRepositoryContextFactory;
import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryContextFactory;
import com.mraibo.cminsight.repository.RepositoryException;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.security.SecurityPolicy;
import com.mraibo.cminsight.web.AnalyticsApi;
import com.mraibo.cminsight.web.Router;
import com.mraibo.cminsight.web.WebServer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** Exit code of {@code --check-repository} when the whole production path succeeded. */
    public static final int EXIT_CLEAN = 0;
    /** Activation failed, a read failed, or the final shutdown was not terminal-clean. */
    public static final int EXIT_FAILURE = 1;
    /** Usage error. */
    public static final int EXIT_USAGE = 2;
    /** Configuration error: an unknown repository id, or a value the runtime refuses. */
    public static final int EXIT_CONFIGURATION = 3;
    /** No usable adapter: absent, ambiguous or unloadable, or it cannot answer the questions asked. */
    public static final int EXIT_ADAPTER_UNAVAILABLE = 4;

    /**
     * Scalar configuration keys this build understands.
     *
     * <p>This is an exact set rather than a list of prefixes on purpose: with a prefix rule, a typo
     * such as {@code web.prt=8080} is silently swallowed because it still starts with {@code web.},
     * which is exactly the misconfiguration an operator most needs to be told about.
     *
     * <p>Goal 03 replaced the bootstrap analytics keys with the goal's documented set
     * ({@code statistics.workers}, {@code statistics.query.timeout.seconds},
     * {@code jdbc.pool.max.age.minutes}, {@code jdbc.pool.max.operations}). The retired names are not
     * listed here: they are named in {@link #RETIRED_CONFIG_KEYS} so a stale file gets a warning that
     * names its replacement instead of a generic "unknown key" - see that constant for why.
     */
    private static final Set<String> KNOWN_CONFIG_KEYS = Set.of(
            "app.name", "app.version", "app.mode",
            "web.bind", "web.port", "web.threads", "web.backlog",
            "web.auth.user", "web.auth.user.env", "web.auth.user.file",
            "web.auth.password", "web.auth.password.env", "web.auth.password.file",
            "web.auth.maxFailures", "web.auth.lockout", "web.auth.maxTrackedKeys",
            SecurityPolicy.KEY_ALLOW_INSECURE_HTTP,
            "cm.pool.size", "cm.pool.borrow.timeout.ms",
            "cm.pool.max.age.minutes", "cm.pool.max.operations",
            "jdbc.pool.size", "jdbc.pool.borrow.timeout.ms",
            "jdbc.pool.max.age.minutes", "jdbc.pool.max.operations",
            StatisticsSettings.ENABLED_KEY, StatisticsSettings.WORKERS_KEY,
            StatisticsSettings.QUERY_TIMEOUT_KEY, StatisticsSettings.SCAN_TIMEOUT_KEY,
            "cache.metadata.ttl.seconds", "cache.statistics.ttl.seconds",
            "profiles.dir", "classifications.file", "secrets.dir",
            "data.dir", "reports.dir", "logs.dir",
            "repository.auto.activate");

    /**
     * Analytics keys this build no longer reads, and the key that replaced each one.
     *
     * <p>The old bootstrap surface declared {@code statistics.threads} and
     * {@code statistics.itemtype.timeout.seconds}; Goal 03 section 4 names
     * {@code statistics.workers} and {@code statistics.query.timeout.seconds} instead, and
     * {@code jdbc.pool.size}/{@code jdbc.pool.borrow.timeout.ms} are the only survivors of that block.
     *
     * <p>Why a dedicated map rather than letting them fall through as unknown keys: an operator who
     * still has the old name in a file gets one warning that says which key to write. Leaving it to the
     * generic "Unknown configuration key" message would be technically correct and practically
     * useless - the old value is IGNORED, so the runtime silently uses the default parallelism, which is
     * the one failure mode a settings rename must not produce.
     */
    private static final Map<String, String> RETIRED_CONFIG_KEYS = Map.of(
            "statistics.threads", StatisticsSettings.WORKERS_KEY,
            "statistics.itemtype.timeout.seconds", StatisticsSettings.QUERY_TIMEOUT_KEY);

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
        // Goal 01A (D): every operational path is resolved against ONE application home, so the
        // launcher works from any working directory. See AppPaths for the precedence.
        AppPaths paths = AppPaths.resolve();
        Path configPath = paths.configurationFile(null);
        boolean printConfig = false;
        String checkRepositoryId = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> {
                    if (i + 1 >= args.length) {
                        throw new ConfigException("--config requires a file path");
                    }
                    configPath = paths.configurationFile(args[++i]);
                }
                case "--check-repository" -> {
                    // Section L: a usage error, not a configuration error, so the exit code is 2.
                    if (i + 1 >= args.length || args[i + 1].isBlank()) {
                        System.err.println("ERROR: --check-repository requires a repository id");
                        usage(System.err);
                        return EXIT_USAGE;
                    }
                    checkRepositoryId = args[++i].trim();
                }
                case "--validate-config" -> {
                    return ConfigCheck.run(args, System.out, System.err);
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

        Path secretsDir = paths.secretsDir(config);
        SecretResolver secrets = SecretResolver.system(secretsDir);
        WebAuthSettings auth = WebAuthSettings.resolve(config, secrets);
        warnings.addAll(auth.warnings());

        String bind = config.webBind();
        int port = config.webPort();
        // Non-loopback plain HTTP sends reusable Basic credentials with no transport encryption, so it
        // is refused unless the operator explicitly opts in. The flag defaults to false and never
        // unlocks the admin/admin refusal, which is checked before it.
        // One strict parse shared with SecurityPolicy/ConfigCheck, so Main, WebServer and doctor can
        // never disagree about what the flag means. A non-boolean value is a hard failure.
        boolean allowInsecureHttp = SecurityPolicy.allowInsecureHttp(config);

        // Fail closed before anything is opened. This is the operator-facing check: a refusal here
        // becomes a configuration error and process exit code 3. WebServer.start() applies the same
        // policy again immediately before it binds, as defence in depth; reached through Main that
        // second copy is unreachable, and if it ever did fire it would surface as a startup failure
        // with exit code 1.
        try {
            SecurityPolicy.validateWebExposure(auth, bind, port, allowInsecureHttp);
        } catch (IllegalStateException e) {
            throw new ConfigException(e.getMessage(), e);
        }
        warnings.addAll(SecurityPolicy.exposureWarnings(auth, bind, port, allowInsecureHttp));

        RepositoryProfileLoader profileLoader = new RepositoryProfileLoader(paths.profilesDir(config));
        List<RepositoryProfile> profiles = profileLoader.loadAll();
        warnings.addAll(profileLoader.diagnostics());

        FeatureRegistry features = FeatureRegistry.defaults(config);
        warnings.addAll(features.warnings());

        ClassificationRules classifications = ClassificationRules.load(config);
        warnings.addAll(classifications.diagnostics());

        warnings.addAll(unknownKeyWarnings(config));

        // Goal 02 (sections B and D): the adapter verdict and the adapter settings are resolved before
        // anything is opened, so both --print-config and --check-repository report the same facts the
        // serving path acts on. A cm.pool.* value outside its documented range is a configuration error
        // (exit 3) here exactly as it is on the serving path - there is one reader of those keys.
        //
        // Goal 02A (sections C and D): the verdict is the ACTIVATION verdict - an installed adapter whose
        // IBM runtime is absent is refused here, before any repository manager exists - and the settings
        // carry the classification rules loaded above from THIS configuration, so the adapter cannot read a
        // different config file and cannot label ItemTypes with rules Main never printed.
        IbmCmAdapterRegistry adapters = IbmCmAdapterRegistry.discover();
        CmAdapterSettings adapterSettings = CmAdapterSettings.from(config, secrets, classifications);

        // Goal 03 (section 4): the analytics bounds are read from the SAME configuration, before anything
        // is opened, so --print-config, --validate-config and the serving path cannot disagree about them.
        // An out-of-range value - and a statistics.workers above jdbc.pool.size, which cannot be delivered
        // - is a configuration error here (exit 3), exactly as it is for cm.pool.*: one reader per key.
        //
        // This is a MALFORMED-CONFIGURATION refusal, not the runtime unavailability section 3 is about: a
        // missing driver, a missing JDBC credential, an unusable schema or an unreachable database still
        // leaves the repository activated and its metadata/retention routes working (they are reported as
        // the analytics availability state instead). Deliberately the difference between "you typed a
        // number this build refuses" and "the database side is not usable right now".
        JdbcPoolSettings jdbcPoolSettings = JdbcPoolSettings.from(config);
        StatisticsSettings statisticsSettings = StatisticsSettings.from(config, jdbcPoolSettings);

        if (printConfig) {
            printEffectiveConfiguration(config, auth, features, profiles, classifications, secretsDir, bind, port,
                    allowInsecureHttp, paths, adapters, jdbcPoolSettings, statisticsSettings);
            printWarnings(warnings);
            return 0;
        }

        if (checkRepositoryId != null) {
            return runRepositoryCheck(profiles, secrets, adapters, adapterSettings, jdbcPoolSettings,
                    statisticsSettings, checkRepositoryId, System.out, System.err);
        }

        // Repository profiles are listed without an adapter; only ACTIVATION needs one. The manager is
        // therefore built over the production factory, and auto-activation is decided with the same
        // lookup the doctor uses, so the two cannot disagree about which repository startup activates.
        RepositoryProfile autoActivated = ConfigCheck.autoActivatedProfile(config, profiles);
        if (autoActivated != null && adapters.status().refused()) {
            // Section B: auto-activation with no usable adapter is a startup FAILURE. Publishing an empty
            // placeholder context instead would report a working console over a repository nobody can read.
            //
            // Section C: this is also the path an INSTALLED adapter whose IBM runtime is absent takes, and
            // the message says which of the two it is - "not installed" and "installed but its runtime is
            // not ready" need different operator actions. No session, factory or pool has been created.
            System.err.println("ERROR adapter: " + ConfigCheck.AUTO_ACTIVATE_KEY + "='" + autoActivated.id()
                    + "' is configured but " + adapters.status().describe()
                    + "; refusing to start rather than activating a repository that cannot be read.");
            return EXIT_ADAPTER_UNAVAILABLE;
        }

        RepositoryManager repositories =
                new RepositoryManager(productionFactory(adapters, adapterSettings, secrets, jdbcPoolSettings,
                        statisticsSettings));
        if (autoActivated != null) {
            // An activation failure propagates out of execute(), so startup fails (exit 1) instead of
            // serving a console whose repository could not be opened.
            repositories.switchTo(autoActivated);
        }

        return serve(config, auth, repositories, profiles, adapters, statisticsSettings, warnings);
    }

    /**
     * The production activation path, or a factory that fails clearly when no adapter is installed.
     *
     * <p>Deliberately a factory and not a null check at the call site: this is the only place a repository
     * context is created, so "no session outside the pool" and "no placeholder context" hold for every
     * activation, including one requested later through the API rather than at startup.
     */
    private static RepositoryContextFactory productionFactory(IbmCmAdapterRegistry adapters,
                                                              CmAdapterSettings adapterSettings,
                                                              SecretResolver secrets,
                                                              JdbcPoolSettings jdbcPoolSettings,
                                                              StatisticsSettings statisticsSettings) {
        Optional<CmAdapterProvider> provider = adapters.provider();
        if (provider.isPresent()) {
            return new ProductionRepositoryContextFactory(provider.get(), adapterSettings, secrets,
                    jdbcPoolSettings, statisticsSettings);
        }
        // The two cases need different operator actions, so the refusal names which one it is: an adapter
        // that is not installed at all, or one that IS installed but reports its IBM runtime not ready
        // (section C). Both refuse without creating anything - no session, no factory, no pool.
        IbmCmAdapterRegistry.Status status = adapters.status();
        String reason = (status.providerInstalled()
                ? status.describe()
                : "no CM adapter provider is available (" + status.describe() + ")")
                + "; a repository can be listed but not activated";
        return profile -> {
            throw new ActivationFailedException(reason);
        };
    }

    /**
     * Goal 02 section L: the smoke check that walks the production path and reports what it found.
     *
     * <p>Never a one-off SDK connection: it discovers the provider, builds the same
     * {@link RepositoryManager} with the same factory the server uses, activates through it, reads the
     * ItemType and retention counts through the context's own services, and then closes through the
     * manager. What it therefore proves is that the real wiring works - not that a connection can be
     * opened by some other means.
     *
     * <p>Exit codes, as frozen in section L and section 9: {@code 0} clean, {@code 1} activation or
     * shutdown failure, {@code 2} usage, {@code 3} configuration error, {@code 4} no usable adapter or a
     * read that could not be answered. The final shutdown is part of the verdict: a check that leaves a
     * session behind is not a pass.
     */
    private static int runRepositoryCheck(List<RepositoryProfile> profiles,
                                          SecretResolver secrets,
                                          IbmCmAdapterRegistry adapters,
                                          CmAdapterSettings adapterSettings,
                                          JdbcPoolSettings jdbcPoolSettings,
                                          StatisticsSettings statisticsSettings,
                                          String repositoryId,
                                          PrintStream out,
                                          PrintStream err) {
        // Section C: this is the ADAPTER-UNAVAILABLE exit path, and it is reached for both shapes of
        // "cannot activate": no provider at all, and a provider that is installed while its IBM runtime is
        // not ready. Nothing below this line runs in either case - no profile is looked up, no repository
        // manager is created, no CM session factory is asked for anything and no pool is built - so the
        // check reports the adapter verdict instead of walking into an activation that cannot succeed.
        if (!adapters.status().available()) {
            err.println("ERROR adapter: " + adapters.status().describe());
            err.println("       no repository was activated and no CM session was attempted.");
            return EXIT_ADAPTER_UNAVAILABLE;
        }

        Optional<RepositoryProfile> found = profiles.stream()
                .filter(candidate -> candidate.id().equals(repositoryId))
                .findFirst();
        if (found.isEmpty()) {
            err.println("ERROR configuration: --check-repository names unknown repository '" + repositoryId
                    + "'. Configured repositories: " + (profiles.isEmpty() ? "(none)"
                            : String.join(", ", profiles.stream().map(RepositoryProfile::id).toList())));
            return EXIT_CONFIGURATION;
        }
        RepositoryProfile profile = found.get();

        CmAdapterProvider provider = adapters.provider().orElseThrow();
        out.println("CM Insight " + VERSION + " repository check");
        out.println("  repository    : " + profile.id() + " (" + profile.displayName() + ", SSID "
                + profile.ssid() + ", " + profile.databaseVendor() + ")");
        out.println("  adapter       : " + adapters.status().providerId()
                + " (adapter " + adapters.status().adapterVersion() + ")");
        out.println("  CM API release: " + adapters.status().sdkReleaseOrUnknown());
        out.println("  CM pool       : " + adapterSettings.pool());
        // Section D: printed from the settings the adapter is actually given, so a non-default --config
        // cannot show one rule set here and label ItemTypes with another. Nothing reads a config file a
        // second time to answer this line.
        out.println("  classification: " + adapterSettings.classifications().ruleCount() + " rule(s), fallback '"
                + adapterSettings.classifications().fallbackLabel() + "' (the rules the adapter receives)");

        RepositoryManager repositories =
                new RepositoryManager(new ProductionRepositoryContextFactory(provider, adapterSettings, secrets,
                        jdbcPoolSettings, statisticsSettings));
        try {
            repositories.switchTo(profile);
        } catch (RepositoryException e) {
            err.println("ERROR activation: " + e.getMessage());
            repositories.close();
            // The shutdown verdict is printed on the FAILURE path too, and to the same stream as the
            // success path. An activation failure is exactly the case that leaves a cleanup unproven, so
            // this is where the operator most needs the CloseState and the quarantined-slot count - the
            // one run that publishes a physical uncertainty must not be the one that says nothing.
            printShutdownSummary(repositories, null, out);
            out.println("RESULT: not clean (activation failed)");
            return EXIT_FAILURE;
        }

        Optional<RepositoryContext> active = repositories.activeContext();
        if (active.isEmpty()) {
            err.println("ERROR activation: the repository manager published no active context");
            repositories.close();
            printShutdownSummary(repositories, null, out);
            out.println("RESULT: not clean (no active context)");
            return EXIT_FAILURE;
        }
        RepositoryContext context = active.get();

        CountResult itemTypes = countItemTypes(context);
        CountResult policies = countRetentionPolicies(context);
        out.println("  item types    : " + itemTypes.describe());
        out.println("  retention     : " + policies.describe());

        int readExit = itemTypes.exitCode() == EXIT_CLEAN ? policies.exitCode() : itemTypes.exitCode();
        if (readExit != EXIT_CLEAN) {
            err.println("ERROR reads: " + (itemTypes.failed() ? itemTypes.detail() : policies.detail()));
        }

        // Section L: the check closes through the manager, and the shutdown is part of the verdict.
        repositories.close();
        printShutdownSummary(repositories, context, out);

        if (readExit != EXIT_CLEAN) {
            out.println("RESULT: not clean (a read could not be answered)");
            return readExit;
        }
        if (!terminalClean(context, repositories)) {
            out.println("RESULT: not clean (the shutdown is not terminal-clean; a physical session may still"
                    + " exist)");
            return EXIT_FAILURE;
        }
        out.println("RESULT: clean (" + itemTypes.count() + " ItemType(s), " + policies.count()
                + " retention policy(ies); every resource released)");
        return EXIT_CLEAN;
    }

    /**
     * The one shutdown summary, printed by every path that reaches a shutdown.
     *
     * <p>Written once and called from the success path and both failure paths on purpose: a second copy
     * is how the two would drift, and the failure path is the one where a quarantine is most likely - an
     * activation failure is exactly what leaves the CM factory's cleanup unproven. Prints to the report
     * stream (not the error stream) so a script that scrapes the report sees the final physical state in
     * one place, and never prints a credential: counts, states and the adapter's own value-free text only.
     *
     * @param published the context this check activated, or {@code null} on a failure path - in which case
     *                  the retained cleanup context is described instead
     */
    private static void printShutdownSummary(RepositoryManager repositories,
                                             RepositoryContext published,
                                             PrintStream out) {
        RepositoryContext context = published != null
                ? published
                : repositories.closingContext().orElse(null);
        CloseState state = context == null ? null : context.closeState();
        Optional<CloseState> retained = repositories.closingState();
        out.println("  shutdown      : repository " + (state == null ? "(none)" : state)
                + (retained.isPresent() ? ", retained context " + retained.get() : ", nothing retained"));
        if (context == null) {
            return;
        }
        List<String> failures = context.closeFailures();
        if (!failures.isEmpty()) {
            out.println("  close failures: " + failures.size() + " - " + String.join("; ", failures));
        }
        List<String> unproven = context.uncertainCloseReports();
        if (!unproven.isEmpty()) {
            out.println("  unproven close: " + unproven.size() + " - " + String.join("; ", unproven));
        }
        context.cmPool().ifPresent(pool -> out.println("  CM pool state : " + describePool(pool)));
    }

    /**
     * True when the repository is provably finished: the context is {@link CloseState#CLOSED_CLEAN} and
     * nothing the manager still retains is anything other than clean.
     *
     * <p>Deliberately not "no exception was thrown": a pool whose {@code close()} returned normally while
     * a slot stayed quarantined reports uncertainty through the close-state, and that is the case this
     * verdict exists for.
     */
    private static boolean terminalClean(RepositoryContext context, RepositoryManager repositories) {
        if (context == null || context.closeState() != CloseState.CLOSED_CLEAN) {
            return false;
        }
        Optional<CloseState> retained = repositories.closingState();
        return retained.isEmpty() || retained.get() == CloseState.CLOSED_CLEAN;
    }

    /** One count read, with the exit code the check must report when it could not answer. */
    private record CountResult(long count, String detail, int exitCode) {

        static CountResult of(long count, String noun) {
            return new CountResult(count, count + " " + noun, EXIT_CLEAN);
        }

        static CountResult unavailable(String reason) {
            return new CountResult(-1L, reason, EXIT_ADAPTER_UNAVAILABLE);
        }

        static CountResult failed(String reason) {
            return new CountResult(-1L, reason, EXIT_ADAPTER_UNAVAILABLE);
        }

        boolean failed() {
            return exitCode != EXIT_CLEAN;
        }

        String describe() {
            return detail;
        }
    }

    private static CountResult countItemTypes(RepositoryContext context) {
        Optional<com.mraibo.cminsight.metadata.MetadataRepository> metadata = context.metadata();
        if (metadata.isEmpty()) {
            return CountResult.unavailable("unavailable (the adapter provides no ItemType read service)");
        }
        try {
            return CountResult.of(metadata.get().listItemTypes().size(), "ItemType(s)");
        } catch (RuntimeException e) {
            return CountResult.failed("could not be read (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static CountResult countRetentionPolicies(RepositoryContext context) {
        Optional<com.mraibo.cminsight.retention.RetentionRepository> retention = context.retention();
        if (retention.isEmpty()) {
            return CountResult.unavailable("unavailable (the adapter provides no retention read service)");
        }
        try {
            return CountResult.of(retention.get().listPolicies().size(), "retention policy(ies)");
        } catch (RuntimeException e) {
            return CountResult.failed("could not be read (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static String describePool(CmPoolDiagnostics pool) {
        StringBuilder text = new StringBuilder(pool.poolName())
                .append(" size=").append(pool.configuredSize())
                .append(" inUse=").append(pool.capacityInUse())
                .append(" leased=").append(pool.leased())
                .append(" quarantined=").append(pool.quarantined());
        if (pool.degraded()) {
            // Why capacity was permanently lost: a creation whose cleanup could not be proven, or a close
            // that did not return normally. Counts and states only, never a credential.
            text.append(" createQuarantineFailures=").append(pool.createQuarantineFailures())
                    .append(" closeFailures=").append(pool.closeFailures());
        }
        // The adapter's own text, which its contract requires to be value-free, so the operator sees what
        // went wrong as well as how much capacity it cost.
        pool.lastAdapterError().ifPresent(error -> text.append(" lastError=").append(error));
        return text.append(" state=").append(pool.closeState()).toString();
    }

    private static int serve(AppConfig config,
                             WebAuthSettings auth,
                             RepositoryManager repositories,
                             List<RepositoryProfile> profiles,
                             IbmCmAdapterRegistry adapters,
                             StatisticsSettings statisticsSettings,
                             List<String> warnings) throws Exception {
        Router router = new Router();
        WebServer server = new WebServer(config, auth, router);

        // Goal 02 section K: the authenticated CM read API. Installed BEFORE the socket is opened, so a
        // route can never be missing from a served request - and unconditionally, not only when an
        // adapter is available, because "no adapter" must still answer the repositories and diagnostics
        // questions with the documented adapter_unavailable status instead of a 404 that reads like a
        // path typo. Registering here also keeps every route authenticated: the registration goes through
        // Router, which refuses any unauthenticated path except /api/health.
        //
        // Goal 03 section 13: the analytics routes are installed by the SAME call, and equally
        // unconditionally. "No JDBC driver is installed" is a normal state of this application, so
        // /api/statistics and /api/diagnostics/jdbc must answer with the documented unavailable state;
        // a 404 there would be indistinguishable from a typo in the client's URL. The adapter below is
        // lazy: constructing it opens no connection and loads no driver, and the routes read the active
        // repository's own statistics service when a request arrives.
        AnalyticsApi analytics = new RepositoryAnalyticsApi(repositories, statisticsSettings.enabled());
        server.installCmApiRoutes(repositories, profiles, adapters, analytics);

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

            printStartupBanner(config, server, auth, repositories, adapters, warnings);

            shutdown.await();
            return EXIT_CLEAN;
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

    private static void printStartupBanner(AppConfig config,
                                            WebServer server,
                                            WebAuthSettings auth,
                                            RepositoryManager repositories,
                                            IbmCmAdapterRegistry adapters,
                                            List<String> warnings) {
        System.out.println("CM Insight " + VERSION);
        System.out.println("  config file      : " + describePath(config.sourcePath()));
        System.out.println("  listening        : http://" + server.bindAddress() + ":" + server.port());
        System.out.println("  web user         : " + auth.user() + " [" + auth.userSource().describe() + "]");
        System.out.println("  web password     : <redacted> [" + auth.passwordSource().describe() + "]");
        // Value-free by construction: an availability label, a provider id, a version and a release, which
        // is the whole of what discovery is allowed to know.
        System.out.println("  adapter          : " + adapters.status().summary());
        if (adapters.status().availability() == IbmCmAdapterRegistry.Availability.UNAVAILABLE) {
            // "installed, unavailable" is the one label that needs its sentence: it means the adapter IS
            // present and its IBM runtime is not, which is a different operator action from installing an
            // adapter. Reported from the registry's own sanitized text, never re-derived here.
            System.out.println("  adapter detail   : " + adapters.status().describe());
        }
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
                                                     int port,
                                                     boolean allowInsecureHttp,
                                                     AppPaths paths,
                                                     IbmCmAdapterRegistry adapters,
                                                     JdbcPoolSettings jdbcPoolSettings,
                                                     StatisticsSettings statisticsSettings) {
        System.out.println("CM Insight " + VERSION + " effective configuration");
        System.out.println("  config file       : " + describePath(config.sourcePath()));
        System.out.println("  web.bind          : " + bind);
        System.out.println("  web.port          : " + port);
        System.out.println("  web user          : " + auth.user() + " [" + auth.userSource().describe() + "]");
        System.out.println("  web password      : <redacted> [" + auth.passwordSource().describe() + "]");
        // Names which parts are the built-in development default ("none", "user only",
        // "password only", "both") instead of a single boolean: a partially defaulted credential is
        // not "false", and printing false directly under "[built-in development default]" is exactly
        // the misreading finding F-1 was about.
        System.out.println("  default creds     : " + SecurityPolicy.describeDevelopmentDefaults(auth));
        System.out.println("  secrets dir       : " + secretsDir.toAbsolutePath());
        System.out.println("  application home  : " + paths.describeHome());
        // The override only has an effect off loopback, so only claim the insecure exposure when it
        // actually applies - a loopback bind stays safe whatever the flag says.
        boolean insecureActuallyApplies = allowInsecureHttp && !SecurityPolicy.isLoopbackLiteral(bind);
        System.out.println("  insecure http     : " + allowInsecureHttp
                + (insecureActuallyApplies
                        ? "  (SECURITY: non-loopback plain HTTP is permitted by opt-in)" : ""));
        System.out.println("  profiles dir      : " + paths.profilesDir(config));
        System.out.println("  classification    : " + classifications.ruleCount() + " rule(s), fallback '"
                + classifications.fallbackLabel() + "'  (loaded once; the adapter receives this same rule set)");
        System.out.println("  features          :");
        features.states().forEach((id, enabled) ->
                System.out.println("      " + id + " = " + enabled));
        System.out.println("  repositories      : " + profiles.size());
        // Reported even with no profile configured: "why can I not activate" is answered by the adapter
        // verdict, and an operator reading --print-config should not have to guess. The two facts are
        // printed apart on purpose (section C): "an adapter is installed" and "activation is ready" are
        // independent, and only the second one permits a repository to be activated.
        System.out.println("  CM adapter        : " + adapters.status().describe());
        System.out.println("  CM adapter state  : installed=" + adapters.status().providerInstalled()
                + ", activationReady=" + adapters.status().available());
        // Goal 03 section 14: a LOCAL readiness report. Reading these values loads no driver, opens no
        // connection and reads no credential, which is exactly why it is safe on the configuration path of
        // a repository whose database is unreachable.
        System.out.println("  JDBC pool bounds  : " + jdbcPoolSettings.describe());
        System.out.println("  analytics         : " + statisticsSettings.describe()
                + (statisticsSettings.enabled() ? "" : "  (feature.statistics=false: the analytics half is off"
                        + " and no JDBC pool is created; the repository is unaffected)"));
        for (RepositoryProfile profile : profiles) {
            printJdbcReadiness(profile);
        }
        if (!profiles.isEmpty()) {
            // Stated once, because it is the one thing an operator must not read into the lines above.
            System.out.println("  JDBC readiness    : local only - driver discovery loads classes and inspects"
                    + " DriverManager; no connection was attempted, and a loadable driver is NOT evidence of"
                    + " a reachable database");
        }
        for (RepositoryProfile profile : profiles) {
            // credentialSourceSummary covers BOTH indirections; credentialEnvNames is env-only and would
            // print nothing at all for a profile that uses a secret file.
            System.out.println("      " + profile.id() + " (" + profile.displayName() + ", "
                    + profile.databaseVendor() + ", SSID " + profile.ssid() + ")"
                    + (profile.credentialSourceSummary().isEmpty()
                            ? "" : " credentials: " + String.join(", ", profile.credentialSourceSummary())));
        }
    }

    /**
     * One repository's local JDBC readiness verdict, for {@code --print-config}.
     *
     * <p>Four facts an operator has to be able to tell apart, printed as four facts: the vendor, whether a
     * driver for it is installed AND registered, whether the configured URL belongs to that vendor's
     * family, and whether the schema is configured or has to be derived from a live session at scan time.
     *
     * <p>Deliberately never the JDBC URL. The URL family prefix is what the mismatch is about, and it is
     * the only part of the value this line reports - the same rule the authenticated
     * {@code GET /api/diagnostics/jdbc} route applies, so a configuration print and a diagnostics response
     * cannot disagree about how much of a connection string is publishable. The verdict text comes from
     * {@code JdbcDrivers}, whose contract is that it never contains the URL, a user name, a credential or a
     * driver message.
     */
    private static void printJdbcReadiness(RepositoryProfile profile) {
        JdbcDrivers.Readiness readiness = JdbcDrivers.readiness(profile.databaseVendor(), profile.jdbcUrl());
        System.out.println("  JDBC readiness    : " + profile.id()
                + " [" + profile.databaseVendor() + "] "
                + (readiness.ready()
                        ? "ready (driver " + readiness.driverIdentity() + ")"
                        : "NOT ready: " + readiness.reason())
                + ", url family " + JdbcDrivers.urlPrefix(profile.databaseVendor())
                + ", schema " + (profile.jdbcSchema() == null
                        ? "to be derived from a live session at scan time" : "configured"));
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
            String replacement = RETIRED_CONFIG_KEYS.get(key);
            if (replacement != null) {
                // Named before the generic case, and with the replacement, because this key is not a typo:
                // it worked in a previous build and its value is now IGNORED. An operator who keeps it
                // silently gets the default parallelism instead of the one they configured.
                warnings.add("Configuration key '" + key + "' was retired by Goal 03 and is IGNORED;"
                        + " write '" + replacement + "' instead (its value is not carried over).");
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
        out.println("  --config <path>    configuration file (default <application home>/conf/application.properties)");
        out.println("  --check-repository <id>");
        out.println("                     activate one repository through the production path (adapter ->");
        out.println("                     repository manager -> context -> CM session pool), report the CM API");
        out.println("                     release, the ItemType count and the retention policy count, close");
        out.println("                     through the manager, then exit. Never opens a one-off connection.");
        out.println("  --validate-config  validate configuration, credentials and exposure policy, then exit");
        out.println("  --print-config     print the effective configuration with secrets redacted, then exit");
        out.println("  --self-test        run the built-in artifact self-check, then exit");
        out.println("  --version, -V      print the version, then exit");
        out.println("  --help, -h         print this help, then exit");
        out.println();
        out.println("--check-repository exit codes:");
        out.println("  0  clean: activated, read and released, with a terminal-clean shutdown");
        out.println("  1  activation or shutdown failure (including a shutdown that is not terminal-clean)");
        out.println("  2  usage error");
        out.println("  3  configuration error (for example an unknown repository id)");
        out.println("  4  no usable adapter (absent, ambiguous or unloadable), or a read it cannot answer");
        out.println();
        out.println("Application home (the base for every relative operational path, so the launcher works");
        out.println("from any working directory) is resolved in this order:");
        out.println("  1. -Dcminsight.home=<dir>  (set by bin/cm-insight)");
        out.println("  2. CM_INSIGHT_HOME=<dir>   (environment)");
        out.println("  3. the current working directory (documented fallback)");
        out.println("Absolute paths in the configuration bypass the application home entirely.");
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
