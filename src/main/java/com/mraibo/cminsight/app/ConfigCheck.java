package com.mraibo.cminsight.app;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.AppPaths;
import com.mraibo.cminsight.config.ClassificationRules;
import com.mraibo.cminsight.config.ConfigException;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.RepositoryProfileLoader;
import com.mraibo.cminsight.config.SecretRef;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.config.WebAuthSettings;
import com.mraibo.cminsight.ibm.CmAdapterSettings;
import com.mraibo.cminsight.ibm.IbmCmAdapterRegistry;
import com.mraibo.cminsight.security.SecurityPolicy;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only configuration validator: the same decisions the runtime makes, made without starting
 * anything.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Goal 01A section E: {@code bin/doctor.sh} used to keep its own, subtly different copy of the
 * credential rules, and that copy claimed a missing {@code web.auth.password.env} falls back to
 * {@code admin/admin} on loopback. The runtime does the opposite - once a source is configured it is
 * authoritative and a missing value fails closed. A second parser in Bash can only ever drift, so the
 * doctor now asks this class instead, and the messages it prints are produced by
 * {@link WebAuthSettings} and {@link SecurityPolicy} themselves.
 *
 * <h2>Scope</h2>
 *
 * <p>Deliberately narrow: credential resolution ({@link WebAuthSettings#resolve}), the exposure policy
 * ({@link SecurityPolicy#validateWebExposure}), the repository-profile credential verdicts
 * ({@link #inspectProfiles}) and the CM adapter verdicts ({@link #adapterFindings}) - exactly the
 * decisions doctor and runtime must agree on - plus the resolved operational path set from
 * {@link AppPaths}. It never opens a socket, never touches the network and never prints a credential
 * value.
 *
 * <p>Every verdict is produced by the runtime's own code: {@code WebAuthSettings}, {@code SecurityPolicy},
 * {@code RepositoryProfileLoader} and {@code SecretResolver}. Nothing here re-implements a rule, which is
 * what makes the parity structural rather than aspirational - notably for secret files, where a shell
 * {@code -r} test followed links and reported a traversing reference as readable while the resolver
 * refused it.
 *
 * <h2>Exit codes</h2>
 *
 * <ul>
 *   <li>{@value #EXIT_OK} - configuration accepted (warnings may have been printed);</li>
 *   <li>{@value #EXIT_REFUSED} - configuration refused, fail closed; every reason is an {@code ERROR:}
 *       line whose text is the message the runtime would print;</li>
 *   <li>{@value #EXIT_USAGE} - usage error;</li>
 *   <li>{@value #EXIT_UNREADABLE} - the configuration file itself could not be read, or the
 *       application home is not a usable path.</li>
 * </ul>
 *
 * <h2>Output contract</h2>
 *
 * <p>Every line is one of {@code OK:    ...}, {@code WARN:  ...}, {@code ERROR: ...},
 * {@code RESULT: ...} on the report stream (stdout), in that order per line, so a shell caller can map
 * the prefixes onto its own counters without parsing the text. Credentials appear as a source
 * description only; no value is ever printed.
 */
public final class ConfigCheck {

    /** Configuration accepted (possibly with warnings). */
    public static final int EXIT_OK = 0;
    /** Configuration refused; the runtime would fail closed with the same messages. */
    public static final int EXIT_REFUSED = 1;
    /** Usage error. */
    public static final int EXIT_USAGE = 2;
    /** The configuration file could not be read, or the application home is unusable. */
    public static final int EXIT_UNREADABLE = 3;

    /**
     * The configuration key that names the repository activated at startup.
     *
     * <p>Read by {@link #autoActivatedProfile(AppConfig, List)} - the one lookup that both
     * {@code Main.activateConfiguredRepository} and this validator use - so the launcher and the doctor
     * cannot disagree about which repository startup activates, or about whether an unknown id is a
     * refusal.
     */
    public static final String AUTO_ACTIVATE_KEY = "repository.auto.activate";

    private static final String OK_PREFIX = "OK:    ";
    private static final String WARN_PREFIX = "WARN:  ";
    private static final String ERROR_PREFIX = "ERROR: ";

    private ConfigCheck() {
    }

    /**
     * The outcome of a validation.
     *
     * @param auth     the resolved credentials, or {@code null} when resolution itself failed; the
     *                 object never carries a printable value (see {@link WebAuthSettings#toString()})
     * @param errors   reasons the runtime refuses to start; each is the runtime's own message
     * @param warnings the runtime's own non-fatal observations, in first-seen order
     */
    public record Report(WebAuthSettings auth, List<String> errors, List<String> warnings) {

        public Report {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** True when the configuration is acceptable; warnings do not make it unacceptable. */
        public boolean accepted() {
            return errors.isEmpty();
        }

        /** {@link #EXIT_OK} when accepted, {@link #EXIT_REFUSED} otherwise. */
        public int exitCode() {
            return accepted() ? EXIT_OK : EXIT_REFUSED;
        }
    }

    /**
     * Validates credentials and exposure exactly as {@code Main} does, and reports what the runtime
     * would report.
     *
     * <p>Never throws for a bad configuration: a refusal is data ({@link Report#errors()}), not an
     * exception, so a caller such as a shell script can always print the reasons.
     */
    public static Report validate(AppConfig config, SecretResolver secrets) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(secrets, "secrets");

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        WebAuthSettings auth = null;

        try {
            String bind = config.webBind();
            int port = config.webPort();
            // The single strict reader of web.allowInsecureHttp: a typo such as "yes" is a
            // configuration error here, exactly as it is in the runtime, never a silent "off".
            boolean allowInsecureHttp = SecurityPolicy.allowInsecureHttp(config);
            auth = WebAuthSettings.resolve(config, secrets);
            warnings.addAll(auth.warnings());
            warnings.addAll(SecurityPolicy.exposureWarnings(auth, bind, port, allowInsecureHttp));
            try {
                // The same call Main makes before it opens anything, including the same refusal text.
                SecurityPolicy.validateWebExposure(auth, bind, port, allowInsecureHttp);
            } catch (IllegalStateException e) {
                errors.add(e.getMessage());
            }
        } catch (ConfigException e) {
            // Main turns this into "ERROR configuration: <message>" and exit code 3. Here the message
            // is the report; the exit code is the doctor's accepted/refused contract.
            errors.add(e.getMessage());
        }
        // The resolver's own explanations are collected even when resolution THREW: without them a
        // refused file that exists and is readable - a link, junction or other reparse point escaping
        // the secrets directory - is indistinguishable from a missing one in the refusal message.
        // Never a value: SecretResolver reports names, paths and reasons only.
        warnings.addAll(secrets.warnings());

        return new Report(auth, errors, warnings);
    }

    public static void main(String[] args) {
        int exit = run(args, System.out, System.err);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    /** Runs the validator and writes the report to {@code out}; usage errors go to {@code err}. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");

        String configValue = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> {
                    if (i + 1 >= args.length) {
                        err.println("ERROR: --config requires a file path");
                        usage(err);
                        return EXIT_USAGE;
                    }
                    configValue = args[++i];
                }
                case "--help", "-h" -> {
                    usage(out);
                    return EXIT_OK;
                }
                case "--validate-config" -> {
                    // Accepted and ignored: Main dispatches here by handing over the whole argument
                    // vector, so the flag that triggered this run is still in it. Ignoring it keeps
                    // the flag usable in any position (`cm-insight --validate-config --config <f>`).
                }
                default -> {
                    err.println("ERROR: unknown argument '" + args[i] + "'");
                    usage(err);
                    return EXIT_USAGE;
                }
            }
        }

        final AppPaths paths;
        try {
            paths = AppPaths.resolve();
        } catch (ConfigException e) {
            out.println(ERROR_PREFIX + e.getMessage());
            out.println("RESULT: unusable application home (1 error(s), 0 warning(s))");
            return EXIT_UNREADABLE;
        }

        Path configPath = paths.configurationFile(configValue);
        final AppConfig config;
        try {
            config = AppConfig.load(configPath);
        } catch (IOException e) {
            out.println(ERROR_PREFIX + e.getMessage());
            out.println("RESULT: configuration unreadable (1 error(s), 0 warning(s))");
            return EXIT_UNREADABLE;
        }

        SecretResolver secrets = SecretResolver.system(paths.secretsDir(config));
        Report report = validate(config, secrets);
        List<Finding> profileFindings = inspectProfiles(paths, config, secrets);
        // The doctor's report is where the two independent questions are composed: what is true of the
        // profile files, and whether an adapter is installed to read them. inspectProfiles keeps answering
        // only the first, so a healthy profile stays healthy in core-only mode.
        List<Finding> doctorFindings = new ArrayList<>(profileFindings.size() + 3);
        doctorFindings.addAll(profileFindings);
        doctorFindings.addAll(adapterFindings(config, secrets));

        out.println(OK_PREFIX + "home = " + paths.describeHome());
        out.println(OK_PREFIX + "config file = " + configPath);
        for (AppPaths.OperationalPath entry : paths.describe(config)) {
            out.println(OK_PREFIX + "path " + entry.describe());
        }
        // Printed raw on purpose: when web.port or web.bind is malformed, an operator needs to see the
        // text that was written in the file, and the validator below already reports the rejection.
        out.println(OK_PREFIX + "web.bind = " + config.get("web.bind", "127.0.0.1"));
        out.println(OK_PREFIX + "web.port = " + config.get("web.port", "8080"));
        if (report.auth() == null) {
            out.println(OK_PREFIX + "web credentials = unresolved (see the ERROR line below)");
        } else {
            out.println(OK_PREFIX + "web user = " + report.auth().user()
                    + " [" + report.auth().userSource().describe() + "]");
            out.println(OK_PREFIX + "web password = <redacted> ["
                    + report.auth().passwordSource().describe() + "]");
            // Deliberately NOT a boolean. "false" printed directly beneath "[built-in development
            // default]" reads as a contradiction when only half of the pair is the default; the shared
            // helper names the halves that are (none / user only / password only / both).
            out.println(OK_PREFIX + "web development credentials in use = "
                    + SecurityPolicy.describeDevelopmentDefaults(report.auth()));
        }

        List<String> profileOk = new ArrayList<>();
        List<String> profileWarn = new ArrayList<>();
        List<String> profileError = new ArrayList<>();
        for (Finding finding : doctorFindings) {
            switch (finding.level()) {
                case OK -> profileOk.add(finding.message());
                case WARN -> profileWarn.add(finding.message());
                case ERROR -> profileError.add(finding.message());
            }
        }

        for (String line : profileOk) {
            out.println(OK_PREFIX + line);
        }
        for (String warning : uniqueWarnings(report)) {
            out.println(WARN_PREFIX + warning);
        }
        for (String warning : profileWarn) {
            out.println(WARN_PREFIX + warning);
        }
        for (String error : report.errors()) {
            out.println(ERROR_PREFIX + error);
        }
        for (String error : profileError) {
            out.println(ERROR_PREFIX + error);
        }

        int errors = report.errors().size() + profileError.size();
        int warnings = uniqueWarnings(report).size() + profileWarn.size();
        boolean refused = errors > 0;
        out.println("RESULT: " + (refused ? "refused" : "accepted")
                + " (" + errors + " error(s), " + warnings + " warning(s))");
        return refused ? EXIT_REFUSED : EXIT_OK;
    }

    /**
     * The warnings with duplicates removed, in first-seen order.
     *
     * <p>The same observation legitimately arrives from more than one layer (the secret resolution
     * warnings are collected once by {@code SecretResolver} and once through {@code WebAuthSettings}),
     * and a caller that counts printed lines must see the same number as the summary reports.
     */
    private static List<String> uniqueWarnings(Report report) {
        return List.copyOf(new LinkedHashSet<>(report.warnings()));
    }

    // Repository profiles -------------------------------------------------------------------------

    /** What the runtime would do with a {@link Finding}. */
    public enum Level {
        /** Informational: the configuration resolved this way. */
        OK,
        /** The runtime would still start, but with this caveat. */
        WARN,
        /** The runtime would refuse to start, with exactly this reason. */
        ERROR
    }

    /**
     * One classified line of the report, inspectable without parsing text.
     *
     * @param level   the verdict this line carries
     * @param message the line without its prefix; never contains a credential value
     */
    public record Finding(Level level, String message) {

        public Finding {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(message, "message");
        }

        /** The complete line as printed, with the prefix a shell caller maps onto its own counters. */
        public String describe() {
            return switch (level) {
                case OK -> OK_PREFIX + message;
                case WARN -> WARN_PREFIX + message;
                case ERROR -> ERROR_PREFIX + message;
            };
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /**
     * The repository-profile section of the report.
     *
     * <p>Every verdict comes from the runtime's own code: {@link RepositoryProfileLoader} decides which
     * profiles exist (a profile the loader refuses is reported as an ERROR, because {@code Main} calls
     * the same loader before it opens anything and exits 3 on that exception), and {@link SecretResolver}
     * decides whether each declared credential resolves.
     *
     * <p>That is the whole point of this method: {@code bin/doctor.sh} used to probe a secret file with a
     * shell {@code -r} test, which follows symbolic links and knows nothing about {@code ..}. A reference
     * that escaped the secrets directory - by traversal, or through a link, junction or other reparse
     * point - was reported as "readable" while {@link SecretResolver} refused it, so doctor was more
     * permissive than the runtime, the one direction section E must never allow.
     *
     * <p>An unresolvable credential is a WARN and not an ERROR: the runtime resolves repository
     * credentials only when a repository is actually activated, so a credentialless profile does not stop
     * the application from starting - it stops it from connecting.
     */
    public static List<Finding> inspectProfiles(AppPaths paths, AppConfig config, SecretResolver secrets) {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(secrets, "secrets");

        Path profilesDir = paths.profilesDir(config);
        RepositoryProfileLoader loader = new RepositoryProfileLoader(profilesDir);
        final List<RepositoryProfile> profiles;
        try {
            profiles = loader.loadAll();
        } catch (ConfigException e) {
            // Main calls loadAll() before it opens anything and exits 3 on this exception, so this is a
            // refusal, not advice.
            return List.of(new Finding(Level.ERROR, e.getMessage()));
        }

        List<Finding> findings = new ArrayList<>();
        for (String diagnostic : loader.diagnostics()) {
            if (isLoaderSummary(diagnostic)) {
                // "Loaded N repository profile(s) from ..." is informational: the profiles it counts are
                // listed individually below.
                continue;
            }
            findings.add(new Finding(Level.WARN, diagnostic));
        }
        if (Files.isDirectory(profilesDir) && profiles.isEmpty()) {
            findings.add(new Finding(Level.WARN, "No repository profiles in " + profilesDir
                    + " (expected *.properties); add one before connecting to a repository."));
        }
        try {
            RepositoryProfile autoActivated = autoActivatedProfile(config, profiles);
            if (autoActivated != null) {
                findings.add(new Finding(Level.OK, AUTO_ACTIVATE_KEY + " = " + autoActivated.id()
                        + " (activated at startup)"));
            }
        } catch (ConfigException e) {
            // Main resolves auto-activation before it serves anything and refuses to start on an unknown
            // id, so an unknown id is a refusal here and not a note.
            findings.add(new Finding(Level.ERROR, e.getMessage()));
        }
        // Deliberately NOT the adapter findings. This method answers one question - what is true of the
        // PROFILE FILES - and adapter availability is an independent one: a profile whose credentials all
        // resolve is healthy whether or not an adapter happens to be installed, and folding the two lists
        // together would make a healthy profile report a non-OK finding. The doctor composes both lists in
        // its own report (see run()).
        for (RepositoryProfile profile : profiles) {
            findings.add(new Finding(Level.OK, "repository profile " + describeProfileSource(profile)));
            for (Map.Entry<String, SecretRef> credential : profile.credentialRefs().entrySet()) {
                findings.add(credentialFinding(secrets, credential.getKey(), credential.getValue()));
            }
        }
        return List.copyOf(findings);
    }

    /**
     * The repository named by {@link #AUTO_ACTIVATE_KEY}, or {@code null} when the key is absent or
     * blank (nothing is activated, which is the default).
     *
     * <p><strong>This is the one lookup.</strong> {@code Main.activateConfiguredRepository} calls it at
     * startup and {@link #inspectProfiles} calls it for the doctor, so "which repository does startup
     * activate" has a single implementation: the doctor cannot silently accept an unknown id that makes
     * the runtime exit 3, and it cannot reject an id the runtime accepts.
     *
     * @param config   the configuration, read with the same accessor {@code Main} uses
     * @param profiles the profiles the runtime's loader produced
     * @return the profile to activate, or {@code null} when auto-activation is not configured
     * @throws ConfigException when the key names a repository that is not configured
     */
    public static RepositoryProfile autoActivatedProfile(AppConfig config, List<RepositoryProfile> profiles) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(profiles, "profiles");

        String requested = config.get(AUTO_ACTIVATE_KEY, null);
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String id = requested.trim();
        return profiles.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ConfigException(AUTO_ACTIVATE_KEY + " names unknown repository '"
                        + id + "'. Configured repositories: "
                        + (profiles.isEmpty() ? "(none)" : String.join(", ", profiles.stream()
                                .map(RepositoryProfile::id).toList()))));
    }

    /**
     * The adapter section of the report: whether a CM adapter can be activated at all, and whether the
     * adapter settings the runtime reads are acceptable.
     *
     * <p>Deliberately separate from {@link #inspectProfiles}: that method answers "what is true of the
     * repository profile files", this one answers "can an adapter read them". A profile whose four
     * credentials all resolve is healthy in core-only mode, so folding the two would report a healthy
     * profile as not-OK. The doctor's report composes both lists; nothing here is ever added to
     * {@link Report#errors()}, which stays the runtime's own refusal text.
     *
     * <p>Three verdicts, each the one the runtime would produce:
     *
     * <ul>
     *   <li>exactly one provider AND its vendor runtime ready - informational, and it reports the two facts
     *       separately, because "an adapter is installed" is not "activation is ready" (section C);</li>
     *   <li>no provider, more than one, an unloadable one, or one whose runtime is not ready - a WARNING
     *       while nothing is configured to activate, because the runtime still starts and lists its
     *       repositories, and an ERROR as soon as {@code repository.auto.activate} names one, because then
     *       startup genuinely fails. A missing optional adapter is never a configuration error by
     *       itself;</li>
     *   <li>an adapter setting outside its documented range - an ERROR, since the runtime reads those keys
     *       before it serves anything.</li>
     * </ul>
     *
     * <p>Discovery is the runtime's own, so the doctor cannot report an adapter the application would not
     * find, and the finding text is produced from the same {@code Status} the startup banner prints.
     */
    public static List<Finding> adapterFindings(AppConfig config, SecretResolver secrets) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(secrets, "secrets");

        List<Finding> findings = new ArrayList<>(4);
        IbmCmAdapterRegistry.Status status = IbmCmAdapterRegistry.discover().status();
        // Read exactly as Main does, so the doctor's verdict and the runtime's refusal cannot diverge
        // about whether auto-activation is configured at all.
        String requested = config.get(AUTO_ACTIVATE_KEY, "");
        boolean autoActivationConfigured = requested != null && !requested.isBlank();

        // Section C, first fact: is the adapter's CODE installed? Reported for every outcome, because the
        // two concepts have to be visible independently or the verdict cannot be told apart from a
        // core-only build that legitimately has no adapter.
        findings.add(new Finding(Level.OK, "CM adapter installed = " + status.providerInstalled()
                + (status.providerId().isEmpty() ? "" : " (provider " + status.providerId()
                        + ", adapter " + status.adapterVersion() + ")")));

        if (status.available()) {
            findings.add(new Finding(Level.OK, "CM adapter " + status.providerId() + " (adapter "
                    + status.adapterVersion() + ", CM API " + status.sdkReleaseOrUnknown()
                    + ") is available and its runtime is ready to activate a repository"));
        } else if (autoActivationConfigured) {
            findings.add(new Finding(Level.ERROR, status.describe() + "; " + AUTO_ACTIVATE_KEY + "='"
                    + requested.trim() + "' is configured, so the runtime refuses to start"));
        } else {
            findings.add(new Finding(Level.WARN, status.describe()
                    + "; repository profiles are listed but none can be activated"));
        }
        // Section D: the settings are built from the SAME configuration this check just read, including the
        // rules it loaded from that configuration, so the doctor reports exactly the rule set the adapter
        // will consume. The adapter has no configuration reader of its own to disagree with.
        try {
            ClassificationRules classifications = ClassificationRules.load(config);
            CmAdapterSettings settings = CmAdapterSettings.from(config, secrets, classifications);
            findings.add(new Finding(Level.OK, "adapter settings " + settings.pool() + ", "
                    + CmAdapterSettings.METADATA_TTL_KEY + "=" + settings.metadataCacheTtlSeconds() + "s"));
            findings.add(new Finding(Level.OK, "adapter classification rules "
                    + settings.classifications().ruleCount() + " rule(s), fallback '"
                    + settings.classifications().fallbackLabel() + "' (from this configuration)"));
        } catch (ConfigException e) {
            findings.add(new Finding(Level.ERROR, e.getMessage()));
        }
        return findings;
    }

    private static Finding credentialFinding(SecretResolver secrets, String key, SecretRef declared) {
        if (declared.source() == SecretRef.Source.MISSING) {
            return new Finding(Level.WARN, "credential " + key + " is not configured; declare '" + key
                    + ".env' or '" + key + ".file' in the profile before connecting to this repository");
        }
        if (declared.source() != SecretRef.Source.ENVIRONMENT && declared.source() != SecretRef.Source.FILE) {
            // RepositoryProfile refuses INLINE and DEFAULT while the profile is loaded, so this is only
            // reachable for a programmatically built profile.
            return new Finding(Level.ERROR, "credential " + key + " declares an unsupported source ("
                    + declared.describe() + "); only '" + key + ".env' and '" + key + ".file' are accepted");
        }

        boolean fromEnvironment = declared.source() == SecretRef.Source.ENVIRONMENT;
        // Exactly the primitive RepositoryProfile.resolve() calls, with the same argument order, applied
        // to ONE credential: the aggregate resolveCredentials() reports only the first failure, and the
        // doctor must list every credential so that one broken source cannot hide the others.
        List<String> before = secrets.warnings();
        SecretRef resolved = fromEnvironment
                ? secrets.classify(declared.locator(), null, null, key)
                : secrets.classify(null, declared.locator(), null, key);
        List<String> after = secrets.warnings();
        List<String> raised = after.subList(before.size(), after.size());

        String source = fromEnvironment
                ? "environment variable " + declared.locator()
                : "secret file " + declared.locator();

        if (resolved.resolved()) {
            return new Finding(Level.OK, "credential " + key + " -> " + source
                    + (fromEnvironment
                            ? " is set (value not shown)"
                            : " is readable inside " + secrets.secretDir() + " (contents not shown)"));
        }
        if (fromEnvironment) {
            return new Finding(Level.WARN, "credential " + key + " -> " + source + " is NOT set (value never"
                    + " shown); this repository cannot connect until it is exported");
        }
        if (!raised.isEmpty()) {
            // A real refusal: the resolver's own sentence, naming the real path it refused and why.
            return new Finding(Level.WARN, "credential " + key + " -> " + source + " is REFUSED: "
                    + String.join(" ", raised));
        }
        return new Finding(Level.WARN, "credential " + key + " -> " + source + " cannot be read: "
                + withoutKeyPrefix(key, resolved.describe()));
    }

    private static String describeProfileSource(RepositoryProfile profile) {
        Path source = profile.sourceFile();
        return source == null ? profile.id() + " (programmatic)" : source.toString();
    }

    /**
     * The loader's own summary line, which is informational here because the profiles it counts are
     * listed individually below it.
     */
    private static boolean isLoaderSummary(String diagnostic) {
        return diagnostic.startsWith("Loaded ") && diagnostic.contains("repository profile(s) from ");
    }

    /** {@code key: reason} becomes {@code reason}; the line already names the credential. */
    private static String withoutKeyPrefix(String key, String message) {
        String prefix = key + ": ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }

    private static void usage(PrintStream out) {
        out.println("CM Insight configuration check " + Main.VERSION);
        out.println();
        out.println("Usage: ConfigCheck [--config <file>] [--help]");
        out.println();
        out.println("Validates the configuration exactly as the runtime does - credential sources, the web");
        out.println("exposure policy and the CM adapter - and prints the resolved operational paths. Nothing");
        out.println("is started, no network is used, and no credential value is ever printed.");
        out.println();
        out.println("The adapter section reports whether exactly one CM adapter provider is installed and");
        out.println("whether the cm.pool.*/cache.metadata.* values the runtime reads are acceptable. A missing");
        out.println("adapter is a WARN while nothing activates a repository, and an ERROR when");
        out.println("repository.auto.activate names one, because the runtime then refuses to start.");
        out.println();
        out.println("  --config <file>   configuration file (default <home>/conf/application.properties)");
        out.println("  --validate-config accepted and ignored (the flag that dispatches here from Main)");
        out.println("  --help, -h        print this help");
        out.println();
        out.println("Application home, in order: -D" + AppPaths.HOME_PROPERTY + ", " + AppPaths.HOME_ENV
                + ", then the working");
        out.println("directory (the documented fallback). A relative --config or operational path");
        out.println("(profiles.dir, classifications.file, secrets.dir, ...) is resolved against that home,");
        out.println("never against the directory the launcher happened to be invoked from.");
        out.println();
        out.println("Exit codes: 0 accepted, 1 refused (fail closed), 2 usage error, 3 unreadable.");
    }
}
