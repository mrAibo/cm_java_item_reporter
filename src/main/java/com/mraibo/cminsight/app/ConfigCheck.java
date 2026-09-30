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
import com.mraibo.cminsight.core.JdbcPoolSettings;
import com.mraibo.cminsight.core.StatisticsSettings;
import com.mraibo.cminsight.db.JdbcDrivers;
import com.mraibo.cminsight.history.HistoryFinding;
import com.mraibo.cminsight.history.HistoryStores;
import com.mraibo.cminsight.ibm.CmAdapterSettings;
import com.mraibo.cminsight.ibm.IbmCmAdapterRegistry;
import com.mraibo.cminsight.report.ReportFormat;
import com.mraibo.cminsight.security.SecurityPolicy;
import com.mraibo.cminsight.statistics.FreshnessThreshold;

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
 * ({@link #inspectProfiles}), the CM adapter verdicts ({@link #adapterFindings}) and the local analytics
 * readiness verdicts ({@link #statisticsFindings}) - exactly the decisions doctor and runtime must agree on
 * - plus the resolved operational path set from
 * {@link AppPaths}. It never opens a socket, never touches the network, never opens a database connection
 * and never prints a credential value.
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
        List<Finding> doctorFindings = new ArrayList<>(profileFindings.size() + 8);
        doctorFindings.addAll(profileFindings);
        doctorFindings.addAll(adapterFindings(config, secrets));
        // Goal 03 section 14: the analytics half's own local readiness. A third independent question -
        // "could this runtime run a statistics scan, and if not, why" - kept apart from the two above for
        // the same reason they are apart from each other.
        doctorFindings.addAll(statisticsFindings(paths, config, secrets));
        // Goal 04 section 11: the local-storage half - history, the data/reports directories, the report
        // formats and the statistics freshness threshold. A fourth independent question, and, like the three
        // above, answered WITHOUT opening anything: no CM session, no repository database and no H2
        // connection is created merely to print configuration.
        doctorFindings.addAll(localStorageFindings(paths, config));

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

    /**
     * The analytics section of the report: the validated bounds, and each profile's LOCAL JDBC readiness.
     *
     * <p>Goal 03 section 14 asks for six facts an operator has to be able to tell apart, and they are
     * reported as six facts rather than as one verdict:
     *
     * <ul>
     *   <li>the analytics feature is switched off ({@code feature.statistics=false}) - a WARN, because the
     *       runtime starts and the repository works; nothing is wrong with the configuration;</li>
     *   <li>the JDBC driver is <em>absent</em> - a WARN naming the measured directory to place the jar in;</li>
     *   <li>the driver is <em>present</em> - reported as ready, with the class name that was found;</li>
     *   <li>the JDBC URL belongs to another vendor's family - a WARN naming the expected prefix. A mismatch
     *       is never "try it anyway", and it is not an ERROR here either, because the IBM CM half of the
     *       repository is unaffected;</li>
     *   <li>the schema is configured, or has to be derived from a live session at scan time;</li>
     *   <li>the pool and worker bounds are accepted - and when they are not, an ERROR, because the runtime
     *       refuses to start on the same {@code ConfigException} this line prints.</li>
     * </ul>
     *
     * <p><strong>Local readiness only.</strong> Nothing here opens a database connection: the driver
     * verdict is {@code JdbcDrivers}' class-loading and {@code DriverManager} inspection, and a loadable
     * driver is <em>not</em> evidence of a reachable database. That sentence is printed as part of the
     * report on purpose - "the driver is installed" and "the database answers" are different claims, and a
     * doctor line that let them blur would be worse than no line.
     *
     * <p>Deliberately never the JDBC URL, the database user name, the schema name or a credential value:
     * the verdict text comes from {@code JdbcDrivers}, whose contract is that it contains none of them, and
     * the schema is reported as configured-versus-derived rather than by name.
     *
     * <p>Kept out of {@link Report#errors()}, which stays the runtime's own refusal text, exactly like
     * {@link #adapterFindings}: an absent driver must not make the doctor refuse a configuration the
     * runtime accepts.
     */
    public static List<Finding> statisticsFindings(AppPaths paths, AppConfig config, SecretResolver secrets) {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(secrets, "secrets");

        List<Finding> findings = new ArrayList<>(8);
        JdbcPoolSettings pool = null;
        try {
            // The runtime's own reader, range check included, so the doctor cannot accept a jdbc.pool.*
            // value that would make Main exit 3 - or refuse one it accepts.
            pool = JdbcPoolSettings.from(config);
            findings.add(new Finding(Level.OK, "JDBC pool bounds accepted: " + pool.describe()));
        } catch (ConfigException e) {
            // Main reads this before it serves anything and exits 3 on the same exception.
            findings.add(new Finding(Level.ERROR, e.getMessage()));
        }

        StatisticsSettings statistics = null;
        if (pool == null) {
            // The pool bounds are already refused above; the worker check needs a pool size, and reporting
            // a second failure derived from the first would double-count one misconfiguration.
            findings.add(new Finding(Level.WARN, "analytics worker bounds were not checked because the JDBC"
                    + " pool bounds are refused above"));
        } else {
            try {
                statistics = StatisticsSettings.from(config, pool);
                if (statistics.enabled()) {
                    findings.add(new Finding(Level.OK, "analytics settings accepted: " + statistics.describe()));
                } else {
                    findings.add(new Finding(Level.WARN, StatisticsSettings.ENABLED_KEY + "=false: the"
                            + " analytics half is disabled. No JDBC pool is created and no database connection"
                            + " is ever attempted; repository activation, the ItemType reads and the retention"
                            + " viewer are unaffected, and /api/statistics reports the disabled state."));
                }
            } catch (ConfigException e) {
                findings.add(new Finding(Level.ERROR, e.getMessage()));
            } catch (IllegalArgumentException e) {
                // StatisticsSettings refuses an impossible pool size programmatically rather than by
                // configuration; surfacing it here keeps the doctor from reporting a clean run over a
                // contradiction it could not evaluate.
                findings.add(new Finding(Level.ERROR, e.getMessage()));
            }
        }

        final List<RepositoryProfile> profiles;
        try {
            profiles = new RepositoryProfileLoader(paths.profilesDir(config)).loadAll();
        } catch (ConfigException e) {
            // Deliberately not reported as a second ERROR: inspectProfiles() already reports the loader's
            // refusal, and the doctor must not count one broken profile file twice. The missing readiness
            // lines are explained instead of silently absent.
            findings.add(new Finding(Level.WARN, "repository JDBC readiness was not evaluated because the"
                    + " repository profiles could not be loaded (see the profile findings)"));
            return List.copyOf(findings);
        }

        for (RepositoryProfile profile : profiles) {
            findings.add(readinessFinding(profile));
        }
        findings.add(new Finding(Level.OK, "analytics readiness is LOCAL only: driver discovery loads classes"
                + " and inspects DriverManager, no database connection was attempted, and a loadable driver is"
                + " not evidence of a reachable database"));
        return List.copyOf(findings);
    }

    /**
     * One repository's local JDBC readiness, as a single classified line.
     *
     * <p>A repository whose driver is missing or whose URL names another vendor stays a WARN: Goal 03
     * section 3 requires the metadata and retention halves to keep working in exactly that state, so making
     * the doctor refuse would contradict the runtime. The line says what the operator will see - the
     * statistics API reporting {@code UNAVAILABLE} - so "not ready" cannot be read as "the application is
     * broken".
     */
    private static Finding readinessFinding(RepositoryProfile profile) {
        JdbcDrivers.Readiness readiness = JdbcDrivers.readiness(profile.databaseVendor(), profile.jdbcUrl());
        String schema = profile.jdbcSchema() == null
                ? "schema not configured (derived from the live session at scan time, then validated by the"
                        + " same identifier rule; never a guessed ICMADMIN)"
                : "schema configured (repository.jdbc.schema)";
        String facts = "url family " + JdbcDrivers.urlPrefix(profile.databaseVendor()) + ", " + schema;
        if (readiness.ready()) {
            return new Finding(Level.OK, "repository " + profile.id() + " [" + profile.databaseVendor()
                    + "] JDBC driver ready (" + readiness.driverIdentity() + "); " + facts);
        }
        return new Finding(Level.WARN, "repository " + profile.id() + " [" + profile.databaseVendor()
                + "] JDBC driver NOT ready: " + readiness.reason() + "; " + facts
                + ". Analytics will report UNAVAILABLE and the repository stays usable: the ItemType and"
                + " retention routes are unaffected.");
    }

    /**
     * The Goal 04 local-storage section of the report: history, the operational directories the new features
     * write into, the report formats and the statistics freshness threshold.
     *
     * <h2>Local only, and that is the whole point</h2>
     *
     * <p>Goal 04 section 11 forbids the doctor from connecting to IBM CM, the repository database or H2 merely
     * to print configuration, and it also forbids the public health endpoint from doing it. So every verdict
     * here comes from a class-loading probe and a filesystem check:
     *
     * <ul>
     *   <li>the history lines come from {@link HistoryStores#findings(AppPaths, AppConfig)} - the history
     *       layer's own local verdict - which loads a driver CLASS NAME and inspects the data directory, and
     *       opens no database;</li>
     *   <li>the directory lines report whether {@code data.dir} and {@code reports.dir} are writable, or can
     *       be created inside a writable ancestor, and they create nothing;</li>
     *   <li>the report-format lines come from the format enum itself, so an unavailable XLSX is stated as
     *       unavailable rather than omitted;</li>
     *   <li>the freshness line comes from {@link FreshnessThreshold}, the one reader of
     *       {@code cache.statistics.ttl.seconds} the runtime uses, so the doctor cannot accept a value the
     *       runtime refuses or refuse one it accepts.</li>
     * </ul>
     *
     * <p>"Expected ready" is deliberately not reported as "opened": the store is opened by the
     * application-local history lifecycle, not by a configuration check, and a diagnostics line that let the
     * two blur would claim more than it proved.
     */
    public static List<Finding> localStorageFindings(AppPaths paths, AppConfig config) {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(config, "config");

        List<Finding> findings = new ArrayList<>(10);

        // History: the history layer's own local readiness, mapped into this report's level type rather than
        // re-derived here. An absent H2 driver is a WARN and not an ERROR, because the runtime serves the
        // documented "history unavailable" state and everything else keeps working.
        for (HistoryFinding finding : HistoryStores.findings(paths, config)) {
            findings.add(new Finding(switch (finding.level()) {
                case OK -> Level.OK;
                case WARN -> Level.WARN;
                case ERROR -> Level.ERROR;
            }, finding.message()));
        }

        // The two directories the new features write into. reports.dir holds generated reports; data.dir holds
        // the local history store. Both are resolved by AppPaths exactly as the runtime resolves them.
        findings.add(directoryFinding(AppPaths.DATA_DIR_KEY, paths.dataDir(config)));
        findings.add(directoryFinding(AppPaths.REPORTS_DIR_KEY, paths.reportsDir(config)));

        // Report formats: the COMPLETE list, with an unavailable one stated as unavailable and its reason.
        List<String> available = new ArrayList<>(ReportFormat.values().length);
        for (ReportFormat format : ReportFormat.availableFormats()) {
            available.add(format.token());
        }
        findings.add(new Finding(Level.OK, "report formats available in this build: "
                + String.join(", ", available)
                + " (HTML and CSV are produced by JDK-only code; XLSX is a real minimal OOXML workbook written"
                + " with the JDK zip support, never a renamed CSV)"));
        for (ReportFormat format : ReportFormat.unavailableFormats()) {
            findings.add(new Finding(Level.WARN, "report format " + format.token() + " is UNAVAILABLE: "
                    + format.detail() + ". /api/reports and the Reports view state it as unavailable and never"
                    + " substitute another format."));
        }

        // The freshness threshold, read by the runtime's own reader.
        try {
            FreshnessThreshold freshness = FreshnessThreshold.from(config);
            findings.add(new Finding(Level.OK, "statistics freshness: " + freshness.describe()
                    + " (a judgement only: the published snapshot stays visible when it is stale, age is always"
                    + " reported, and no GET request starts a scan)"));
        } catch (ConfigException e) {
            // Main reads this value before it serves anything and refuses the same way.
            findings.add(new Finding(Level.ERROR, e.getMessage()));
        } catch (IllegalArgumentException e) {
            findings.add(new Finding(Level.ERROR, e.getMessage()));
        }

        findings.add(new Finding(Level.OK, "history and report readiness is LOCAL only: no IBM CM session, no"
                + " repository-database connection and no local history database was opened to produce these"
                + " lines, and 'expected ready' does not claim that the store has been opened"));
        return List.copyOf(findings);
    }

    /**
     * One operational directory's writability as a single classified line.
     *
     * <p>Writability is a property of the directory that is already there, or of the nearest existing ancestor
     * that is, so asking the question creates NOTHING: the report cannot make an operator's filesystem look
     * different from what it said. A path that exists but is not a directory can never contain the output, so
     * it is reported as NOT writable however permissive its own attributes are.
     */
    private static Finding directoryFinding(String key, Path directory) {
        if (directory == null) {
            return new Finding(Level.WARN, key + " could not be resolved to a path");
        }
        Path candidate = directory.toAbsolutePath().normalize();
        boolean exists = Files.exists(candidate);
        Path existing = exists ? candidate : candidate.getParent();
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return new Finding(Level.WARN, key + " = " + candidate + " has no existing ancestor, so it cannot"
                    + " be created; reports or history would be unavailable and everything else is unaffected");
        }
        boolean usable = Files.isDirectory(existing) && writable(existing);
        if (usable) {
            return new Finding(Level.OK, key + " = " + candidate + (exists
                    ? " (writable"
                    : " (does not exist yet; it can be created inside " + existing + ", which is writable")
                    + ")");
        }
        return new Finding(Level.WARN, key + " = " + candidate + " is NOT writable (" + existing
                + " is not a writable directory); reports or history would report their documented unavailable"
                + " state and repository activation is unaffected");
    }

    /**
     * True when a directory can be written to, checked without creating anything.
     *
     * <p>Two independent access checks, because one of them lies on some hosts: the NIO
     * {@link Files#isWritable(Path)} check reports "not writable" for a directory this project can plainly
     * write to when the JVM runs under a restricted Windows token, while the legacy
     * {@link java.io.File#canWrite()} verdict is the attribute-based one most Windows tooling uses. A
     * directory is reported writable when EITHER check says so, which removes the false negative without
     * making the check optimistic on a platform where the two agree (on Linux they are the same call).
     */
    private static boolean writable(Path directory) {
        if (Files.isWritable(directory)) {
            return true;
        }
        return directory.toFile().canWrite();
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
        out.println("The analytics section reports the jdbc.pool.*/statistics.* bounds, whether statistics are");
        out.println("switched off, and each repository's LOCAL JDBC readiness: driver absent, driver present, a");
        out.println("JDBC URL that belongs to another vendor's family, and whether the schema is configured or");
        out.println("derived from a live session at scan time. It opens NO database connection - a loadable");
        out.println("driver is not evidence of a reachable database - and an out-of-range bound is an ERROR,");
        out.println("because the runtime refuses to start on the same message.");
        out.println();
        out.println("The local-storage section reports whether persistent history is enabled by feature, whether");
        out.println("the local database driver is present, whether the history store is expected ready or");
        out.println("unavailable and why, whether data.dir and reports.dir are writable, which report formats");
        out.println("this build can produce (an unavailable XLSX is stated as unavailable, never omitted), and");
        out.println("the cache.statistics.ttl.seconds freshness threshold. It is LOCAL only: no IBM CM session,");
        out.println("no repository-database connection and no history database is opened to print it, so");
        out.println("\"expected ready\" does not claim that the store has been opened.");
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
