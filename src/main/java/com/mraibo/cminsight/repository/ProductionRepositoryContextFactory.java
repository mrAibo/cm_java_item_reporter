package com.mraibo.cminsight.repository;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CmSession;
import com.mraibo.cminsight.connection.PoolMetrics;
import com.mraibo.cminsight.connection.ResourceFactory;
import com.mraibo.cminsight.core.CmPoolDiagnostics;
import com.mraibo.cminsight.core.CmPoolSettings;
import com.mraibo.cminsight.core.CmSessionFactory;
import com.mraibo.cminsight.core.CloseState;
import com.mraibo.cminsight.core.HistorySettings;
import com.mraibo.cminsight.core.JdbcPoolSettings;
import com.mraibo.cminsight.core.MetadataCache;
import com.mraibo.cminsight.core.RepositoryServices;
import com.mraibo.cminsight.core.StatisticsSettings;
import com.mraibo.cminsight.db.Db2Dialect;
import com.mraibo.cminsight.db.JdbcDialect;
import com.mraibo.cminsight.db.OracleDialect;
import com.mraibo.cminsight.history.HistoryCapability;
import com.mraibo.cminsight.history.HistoryRecorder;
import com.mraibo.cminsight.ibm.CmAdapterProvider;
import com.mraibo.cminsight.ibm.CmAdapterSettings;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.metadata.MetadataRepository;
import com.mraibo.cminsight.retention.RetentionRepository;
import com.mraibo.cminsight.statistics.FreshnessThreshold;
import com.mraibo.cminsight.statistics.StatisticsCapability;
import com.mraibo.cminsight.statistics.StatisticsService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The production activation path: the one factory that turns a repository profile into a fully initialized
 * {@link RepositoryContext} through the discovered adapter.
 *
 * <h2>The activation sequence, and why it is in this order</h2>
 *
 * <ol>
 *   <li><strong>Refuse an adapter whose vendor runtime is not ready.</strong> Readiness is asked before
 *       anything is allocated, so an adapter that cannot activate a repository never reaches a session
 *       factory, a pool or a credential read. Through {@code Main} this is already impossible - the
 *       registry refuses such an adapter and exposes no provider - and this step is the structural form of
 *       the same rule for every other route to this factory.</li>
 *   <li><strong>Resolve the credentials the adapter actually needs.</strong> Only the two CM credentials,
 *       never the JDBC pair: this build reads ItemTypes and retention policies and never opens the database,
 *       so requiring a JDBC password would make a read path depend on something it does not use. Resolving
 *       first means a repository with no usable credential fails before anything is allocated.</li>
 *   <li><strong>Ask the adapter for its session factory</strong>, passing the profile and the validated
 *       settings. No connection is opened here; the factory is only the recipe.</li>
 *   <li><strong>Create the hard-bounded CM session pool</strong> over that factory, and put it in the
 *       resource list BEFORE it is initialized. That single ordering decision is what makes the next step
 *       safe: from the moment the pool exists, every later failure already owns a context that names
 *       it.</li>
 *   <li><strong>Initialize the pool</strong>, which creates the configured number of sessions eagerly so a
 *       broken repository fails at activation rather than at the first request.</li>
 *   <li><strong>Ask the adapter for its read services</strong>, handing over the initialized pool: the
 *       ItemType and retention services borrow their sessions from it, so a read can never open a
 *       connection of its own.</li>
 *   <li><strong>Wrap them in the metadata cache</strong> and add it to the resource list, so its lifetime
 *       is tied to the context instead of outliving it.</li>
 *   <li><strong>Publish.</strong> The context is returned only when every step above succeeded. Nothing is
 *       ever published half-initialized, and no empty placeholder context is ever returned to stand in for
 *       a missing adapter.</li>
 * </ol>
 *
 * <h2>A failed activation never loses an allocated resource</h2>
 *
 * <p>Every failure after step 3 throws {@link ActivationFailedException} carrying a cleanup
 * {@link RepositoryContext} that owns everything allocated so far - the pool it may already have created
 * sessions in, and the cache. The manager retains that context in the same latch a switch uses and closes
 * it through the same close-outcome path, so the three outcomes are the ones section J requires: a proven
 * clean close lets a later activation proceed, a still-draining one refuses retries as pending, and a
 * quarantined one - a session whose creation or close could not be proven gone - refuses them permanently.
 *
 * <p>That is also why an {@link Error} from the pool is wrapped rather than propagated: an Error thrown
 * past the manager would drop the only reference to a pool that may hold a live session. The Error stays
 * reachable as the cause of the reported failure.
 *
 * <h2>What this class never does</h2>
 *
 * <p>No connection outside the pool, no second pool, no retry that hides a failure, and no fallback to a
 * weaker provider when the discovered one is missing - an absent adapter is the {@code ABSENT} status, and
 * the caller refuses to activate.
 */
public final class ProductionRepositoryContextFactory implements RepositoryContextFactory {

    private final CmAdapterProvider provider;
    private final CmAdapterSettings settings;
    private final SecretResolver secrets;
    private final JdbcPoolSettings jdbcPoolSettings;
    private final StatisticsSettings statisticsSettings;
    private final FreshnessThreshold freshnessThreshold;

    /** The process-wide history capability; an unavailable store is a real object, never null. */
    private final HistoryCapability history;

    /**
     * @param provider the discovered adapter; the caller refuses to activate when there is none
     * @param settings the validated pool bounds, cache TTL and credential resolver
     * @param secrets  the resolver used to prove the CM credentials are usable before anything is allocated
     */
    public ProductionRepositoryContextFactory(CmAdapterProvider provider,
                                             CmAdapterSettings settings,
                                             SecretResolver secrets) {
        this(provider, settings, secrets, JdbcPoolSettings.defaults(), StatisticsSettings.disabled(),
                FreshnessThreshold.defaults());
    }

    /**
     * @param provider           the discovered adapter; the caller refuses to activate when there is none
     * @param settings           the validated CM pool bounds, cache TTL and credential resolver
     * @param secrets            the resolver used to prove credentials are usable before anything is allocated
     * @param jdbcPoolSettings   the validated analytics pool bounds; reached only when analytics is enabled
     * @param statisticsSettings the validated analytics scan settings, including the enabled switch
     */
    public ProductionRepositoryContextFactory(CmAdapterProvider provider,
                                             CmAdapterSettings settings,
                                             SecretResolver secrets,
                                             JdbcPoolSettings jdbcPoolSettings,
                                             StatisticsSettings statisticsSettings) {
        this(provider, settings, secrets, jdbcPoolSettings, statisticsSettings, FreshnessThreshold.defaults());
    }

    /** Full Goal 04 form with the configured statistics freshness threshold. */
    public ProductionRepositoryContextFactory(CmAdapterProvider provider,
                                             CmAdapterSettings settings,
                                             SecretResolver secrets,
                                             JdbcPoolSettings jdbcPoolSettings,
                                             StatisticsSettings statisticsSettings,
                                             FreshnessThreshold freshnessThreshold) {
        this(provider, settings, secrets, jdbcPoolSettings, statisticsSettings, freshnessThreshold,
                HistoryCapability.unwired(HistorySettings.defaults(java.nio.file.Path.of("data"))));
    }

    /**
     * The complete production form: additionally receives the process-wide history capability.
     *
     * <p>The store is passed IN rather than created here, because persistent history outlives a
     * {@code RepositoryContext}: this factory runs once per activation while the store must survive every
     * repository switch. What is built here is the per-repository {@link HistoryRecorder}, which captures
     * this repository's identity and its retention-policy names - both of which belong to the activation,
     * not to the store.
     */
    public ProductionRepositoryContextFactory(CmAdapterProvider provider,
                                             CmAdapterSettings settings,
                                             SecretResolver secrets,
                                             JdbcPoolSettings jdbcPoolSettings,
                                             StatisticsSettings statisticsSettings,
                                             FreshnessThreshold freshnessThreshold,
                                             HistoryCapability history) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.jdbcPoolSettings = Objects.requireNonNull(jdbcPoolSettings, "jdbcPoolSettings");
        this.statisticsSettings = Objects.requireNonNull(statisticsSettings, "statisticsSettings");
        this.freshnessThreshold = Objects.requireNonNull(freshnessThreshold, "freshnessThreshold");
        this.history = Objects.requireNonNull(history, "history");
    }

    @Override
    public RepositoryContext create(RepositoryProfile profile) throws Exception {
        Objects.requireNonNull(profile, "profile");

        CmPoolSettings poolSettings = settings.pool();

        // 0. Fail closed on an adapter whose vendor runtime cannot activate anything, BEFORE any resource
        //    exists. Through Main this is unreachable - the registry refuses such an adapter and exposes no
        //    provider at all - and it is kept because it is the structural form of the same rule: a
        //    readiness verdict of "not ready" must never be able to produce a pool, a session or a factory,
        //    whatever route reached this factory. Nothing is allocated above this line, so the refusal
        //    carries no cleanup context.
        requireRuntimeReady(profile);

        // 1. Fail closed on a credential the adapter cannot use, before any resource exists.
        RepositoryProfile.CmCredentials credentials = resolveCredentials(profile);

        // 2. The recipe for a session, not a session.
        CmSessionFactory sessions = sessionFactory(profile);

        // 3. The pool, registered as an owned resource before it holds anything.
        List<AutoCloseable> resources = new ArrayList<>(2);
        SessionErrorLog errors = new SessionErrorLog();
        BoundedPool<CmSession> pool = new BoundedPool<>(
                "cm:" + profile.id(),
                poolSettings.size(),
                poolSettings.borrowTimeout(),
                new PooledSessionFactory(sessions, profile, errors),
                poolSettings.maxAge(),
                poolSettings.maxOperations());
        resources.add(pool);

        // 4. Eager initialization: the configured sessions are created now, and a failure is an activation
        //    failure rather than a surprise on the first request. A creation that could not prove its
        //    cleanup quarantines its slot inside the pool, and that quarantined slot travels with the
        //    cleanup context below, which is what makes a retry refuse permanently afterwards.
        try {
            pool.initialize();
        } catch (Throwable failure) {
            throw failed(profile, pool, errors, resources, "could not create and initialize its "
                    + poolSettings.size() + "-session CM pool", failure);
        }

        // 5. The adapter's read services, built over the pool the core owns.
        CmAdapterProvider.AdapterServices adapterServices;
        try {
            CmAdapterProvider.AdapterServices services =
                    provider.services(new CmAdapterProvider.AdapterContext(profile, settings, pool));
            adapterServices = services == null ? CmAdapterProvider.AdapterServices.NONE : services;
        } catch (Throwable failure) {
            throw failed(profile, pool, errors, resources, "could not build its read services", failure);
        }

        // 6. One cache per context, tying the services and their lifetime together.
        MetadataRepository adapterMetadata = adapterServices.metadataService().orElse(null);
        RetentionRepository adapterRetention = adapterServices.retentionService().orElse(null);
        MetadataCache cache = null;
        if (adapterMetadata != null || adapterRetention != null) {
            cache = new MetadataCache(settings.metadataCacheTtl(), adapterMetadata, adapterRetention);
            resources.add(cache);
        }

        // 7. The analytics half, if this repository has one. Section 3 of the goal is the rule that shapes
        //    this call: JDBC is OPTIONAL to activation, so nothing here may connect, and an unusable
        //    analytics side must leave a perfectly working CM repository behind. activate() therefore
        //    returns an unavailable service - registering ZERO resources and opening ZERO connections - for
        //    a disabled feature, an absent driver, a driver not registered for this vendor, a vendor/URL
        //    mismatch and an unresolvable JDBC credential. The pool it does build is lazy: initialize() is
        //    never called, so the first connection happens when a scan asks for one.
        //
        //    The metadata supplier is the CACHE's view, not the adapter's, so a scan reuses the same
        //    per-context ItemType snapshot the read API serves rather than issuing its own CM sessions.
        MetadataRepository statisticsMetadata = cache == null ? adapterMetadata : cache.metadataView();
        Supplier<List<ItemTypeSummary>> itemTypeSource =
                statisticsMetadata == null ? List::of : statisticsMetadata::listItemTypes;

        // The per-repository history recorder. It captures THIS repository's identity and, when it can, the
        // retention-policy name as it was when the scan ran - a stored snapshot must be renderable without a
        // live read, so a name that was not captured stays empty rather than being re-derived from metadata
        // that may since have changed.
        //
        // Built even when the store is unavailable, because the recorder is what knows how to turn a
        // published snapshot into a stored shape; an unavailable store simply makes record() answer empty.
        //
        // Retention names are read from the metadata CACHE's already-loaded view, never by asking the adapter:
        // this is activation, and the goal is explicit that nothing here may open a CM session. When the cache
        // has no snapshot yet the names are absent, which is the honest answer - "not known at capture time".
        HistoryRecorder recorder = HistoryRecorder.forRepository(history.store(), profile.id(),
                profile.displayName(), profile.databaseVendor().name());
        try {
            MetadataRepository metadataForNames = cache == null ? adapterMetadata : cache.metadataView();
            if (metadataForNames != null) {
                recorder = recorder.withRetentionPolicyNames(retentionNamesOf(metadataForNames.listItemTypes()));
            }
        } catch (RuntimeException metadataUnavailable) {
            // A history nicety must never be the reason an activation fails, and it must never be the reason
            // a CM session is opened. Without the names the report renders them as unknown, which is correct.
            recorder = recorder.withRetentionPolicyNames(Map.of());
        }

        StatisticsService statistics = StatisticsCapability.activate(
                profile,
                secrets,
                dialectFor(profile),
                jdbcPoolSettings,
                statisticsSettings,
                itemTypeSource,
                resources::add,
                freshnessThreshold,
                recorder::record);

        RepositoryServices published = new RepositoryServices(
                cache == null ? null : cache.metadataView(),
                cache == null ? null : cache.retentionView(),
                adapterServices.diagnosticsService().orElseGet(() -> new PoolCmDiagnostics(pool, errors)),
                statistics);

        // 8. Publish, only now. A repository whose adapter offers no read service is still a valid
        //    activated repository - its services are reported as unavailable rather than faked.
        return new RepositoryContext(profile, List.copyOf(resources), published);
    }

    /**
     * The retention-policy name per ItemType id, from an already-loaded metadata list.
     *
     * <p>Pure and tolerant: a missing name, a blank name or a null row is simply omitted, because the stored
     * shape's contract is "the name when known" and an invented placeholder would be indistinguishable from
     * a real policy once it reached a report.
     */
    private static Map<Integer, String> retentionNamesOf(List<ItemTypeSummary> itemTypes) {
        if (itemTypes == null || itemTypes.isEmpty()) {
            return Map.of();
        }
        Map<Integer, String> names = new LinkedHashMap<>();
        for (ItemTypeSummary summary : itemTypes) {
            if (summary == null) {
                continue;
            }
            String policy = summary.retentionPolicyName();
            if (policy != null && !policy.isBlank()) {
                names.put(summary.itemTypeId(), policy);
            }
        }
        return names.isEmpty() ? Map.of() : names;
    }

    /**
     * The dialect for a repository's configured vendor.
     *
     * <p>Selection is by the profile's own {@code databaseVendor}, never by inspecting the JDBC URL: the
     * vendor is validated configuration, whereas the URL is free text, and a dialect chosen from free text
     * would be a second, weaker answer to a question the configuration already answered. A URL that
     * disagrees with the vendor is refused later by the driver-readiness check, which reports it as
     * statistics-unavailable rather than silently switching dialect.
     */
    private static JdbcDialect dialectFor(RepositoryProfile profile) {
        return switch (profile.databaseVendor()) {
            case DB2 -> new Db2Dialect();
            case ORACLE -> new OracleDialect();
        };
    }

    /**
     * Refuses activation unless the adapter reports its vendor runtime ready.
     *
     * <p>Asked before any resource is allocated, and the answer is taken as final: an adapter that says it
     * cannot activate a repository is never asked for a session factory, so no pool is built and no
     * credential is even resolved. A readiness answer that throws is treated as NOT ready - the conservative
     * direction - because a provider that cannot answer has not proven that it can activate anything.
     *
     * @throws ActivationFailedException with no cleanup context: nothing exists to clean up
     */
    private void requireRuntimeReady(RepositoryProfile profile) throws ActivationFailedException {
        final CmAdapterProvider.Readiness readiness;
        try {
            readiness = provider.readiness();
        } catch (RuntimeException | Error failure) {
            throw new ActivationFailedException("Repository '" + profile.id() + "' cannot be activated by"
                    + " adapter '" + provider.providerId() + "': it did not answer whether its vendor runtime"
                    + " is ready, and an unproven runtime is not a usable one.");
        }
        if (readiness == null || !readiness.ready()) {
            String reason = readiness == null ? "" : sanitise(readiness.reason());
            throw new ActivationFailedException("Repository '" + profile.id() + "' cannot be activated by"
                    + " adapter '" + provider.providerId() + "': the adapter's runtime is not ready"
                    + (reason.isEmpty() ? "" : " - " + reason)
                    + ". No CM session pool was created.");
        }
    }

    /**
     * Bounds a provider-supplied readiness sentence so it can appear in one log line.
     *
     * <p>The provider is required to sanitize its own reason, but this text crosses a third-party boundary
     * and is printed, so control characters are dropped and the length is capped here too. The registry
     * applies the same treatment when it publishes its verdict; a second, cheaper copy at this boundary is
     * deliberate - the two surfaces are reached by different callers.
     */
    private static String sanitise(String reason) {
        if (reason == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(Math.min(reason.length(), MAX_REASON_LENGTH));
        boolean lastWasSpace = true;
        for (int i = 0; i < reason.length() && cleaned.length() < MAX_REASON_LENGTH; i++) {
            char c = reason.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (lastWasSpace) {
                    continue;
                }
                lastWasSpace = true;
                cleaned.append(' ');
                continue;
            }
            lastWasSpace = false;
            cleaned.append(c);
        }
        return cleaned.toString().trim();
    }

    /** {@link #sanitise(String)}'s cap: one readiness sentence, not a paragraph. */
    private static final int MAX_REASON_LENGTH = 200;

    /** The profile's CM-only credentials, resolved and checked. Nothing has been allocated at this point. */
    private RepositoryProfile.CmCredentials resolveCredentials(RepositoryProfile profile) throws ActivationFailedException {
        final RepositoryProfile.CmCredentials credentials;
        try {
            credentials = profile.resolveCmCredentials(secrets);
        } catch (RuntimeException failure) {
            // Thrown before the pool exists, so there is nothing to hand back: the exception carries an
            // empty cleanup context, and the manager's behaviour is a clean failed activation.
            throw new ActivationFailedException("Repository '" + profile.id()
                    + "' has no usable CM credentials: " + describe(failure), failure);
        }
        if (credentials == null || !credentials.usable()) {
            // Unreachable through the profile's own constructor; kept as the final guard because
            // "connect with something" must never be the recovery from "the credential could not be read".
            throw new ActivationFailedException("Repository '" + profile.id()
                    + "' has no usable CM credentials; refusing to activate.");
        }
        return credentials;
    }

    private CmSessionFactory sessionFactory(RepositoryProfile profile) throws ActivationFailedException {
        final CmSessionFactory sessions;
        try {
            sessions = provider.sessionFactory(profile, settings);
        } catch (RuntimeException failure) {
            throw new ActivationFailedException("Repository '" + profile.id()
                    + "' could not build a CM session factory: " + describe(failure), failure);
        }
        if (sessions == null) {
            throw new ActivationFailedException("Repository '" + profile.id()
                    + "': adapter '" + provider.providerId() + "' returned no session factory.");
        }
        return sessions;
    }

    /**
     * Reports a failure that happened after resources were allocated, handing the manager a context that
     * owns them.
     *
     * <p>The cleanup context carries the pool's own diagnostics, even though it exposes no read service.
     * That is deliberate: a failed activation is the case where a slot is most likely to be quarantined -
     * the factory could not prove its cleanup - and the operator-facing smoke check has to be able to
     * report that physical state. Without the diagnostics the only handle on the failed pool would be the
     * resource list, which is exactly the kind of type-sniffing the diagnostics interface exists to
     * remove. The context still owns the resources, so {@code close()} releases them as usual.
     */
    private static ActivationFailedException failed(RepositoryProfile profile,
                                                   BoundedPool<CmSession> pool,
                                                   SessionErrorLog errors,
                                                   List<AutoCloseable> resources,
                                                   String action,
                                                   Throwable cause) {
        RepositoryServices services = pool == null
                ? RepositoryServices.NONE
                : new RepositoryServices(null, null, new PoolCmDiagnostics(pool, errors));
        RepositoryContext cleanup = new RepositoryContext(profile, List.copyOf(resources), services);
        return new ActivationFailedException("Repository '" + profile.id() + "' " + action + ": "
                + describe(cause), cleanup, cause);
    }

    private static String describe(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String text = failure.getClass().getSimpleName() + ": " + message;
        return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }

    private static final int MAX_MESSAGE_LENGTH = 400;

    /**
     * The pool's view of one session factory.
     *
     * <p>The pool's resource source is deliberately this small wrapper rather than the adapter's own object:
     * it depends only on the vendor-neutral {@link CmSessionFactory}, so the core needs no type from any
     * adapter to build a pool, and a {@link com.mraibo.cminsight.connection.CreationFailure} reported by the
     * adapter passes through unchanged - which is what makes an unproven cleanup quarantine the slot
     * instead of releasing it.
     *
     * <p>Every failure is also recorded, sanitised, so the diagnostics service can answer "what went wrong
     * last" without waiting for the pool to expose its own counters. The exception is rethrown unchanged;
     * recording never replaces reporting.
     */
    private static final class PooledSessionFactory implements ResourceFactory<CmSession> {

        private final CmSessionFactory sessions;
        private final RepositoryProfile profile;
        private final SessionErrorLog errors;

        private PooledSessionFactory(CmSessionFactory sessions, RepositoryProfile profile, SessionErrorLog errors) {
            this.sessions = sessions;
            this.profile = profile;
            this.errors = errors;
        }

        @Override
        public CmSession create() throws Exception {
            try {
                CmSession session = sessions.open(profile);
                if (session == null) {
                    throw new IllegalStateException("the adapter returned no session for repository '"
                            + profile.id() + "'");
                }
                return session;
            } catch (Exception failure) {
                errors.record(failure);
                throw failure;
            } catch (Error failure) {
                errors.record(failure);
                throw failure;
            }
        }

        /**
         * A cheap local read, never I/O: {@code BoundedPool} calls this while it holds its lock, on the
         * borrow path, the return path and during a rotation sweep. The adapter's own session is required
         * to answer from a local flag, and a probe that throws is treated as "not healthy", which retires
         * the session rather than handing it out.
         */
        @Override
        public boolean isHealthy(CmSession session) {
            if (session == null) {
                return false;
            }
            try {
                return session.isHealthy();
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public String describe() {
            return "cm-sessions:" + profile.id();
        }
    }

    /**
     * The last sanitised session failure, kept as a bounded single line.
     *
     * <p>Text, not a throwable: a diagnostics reader needs one sentence, and holding the exception would
     * keep an SDK object graph alive for the lifetime of the context. The adapter guarantees that the
     * message of a failure it reports carries no credential and no raw vendor text.
     */
    private static final class SessionErrorLog {

        private static final int MAX_ERROR_LENGTH = 300;

        private volatile String lastError = "";

        private void record(Throwable failure) {
            if (failure == null) {
                return;
            }
            String message = failure.getMessage();
            String text = message == null || message.isBlank()
                    ? failure.getClass().getSimpleName()
                    : failure.getClass().getSimpleName() + ": " + message;
            StringBuilder single = new StringBuilder(Math.min(text.length(), MAX_ERROR_LENGTH));
            for (int i = 0; i < text.length() && single.length() < MAX_ERROR_LENGTH; i++) {
                char c = text.charAt(i);
                single.append(c < 0x20 || c == 0x7f ? ' ' : c);
            }
            lastError = single.toString();
        }

        private Optional<String> last() {
            String error = lastError;
            return error.isEmpty() ? Optional.empty() : Optional.of(error);
        }
    }

    /**
     * The pool-derived diagnostics, used when the adapter reports none of its own.
     *
     * <p>Every read is a cheap, lock-guarded snapshot: no I/O and no session is touched, so a diagnostics
     * page can never delay a borrow or a lifecycle decision. Two questions the pool genuinely cannot
     * answer are reported as absent rather than guessed: per-session ages (the pool does not expose them)
     * and anything about the vendor API beyond the last recorded failure.
     */
    private static final class PoolCmDiagnostics implements CmPoolDiagnostics {

        private final BoundedPool<CmSession> pool;
        private final SessionErrorLog errors;

        private PoolCmDiagnostics(BoundedPool<CmSession> pool, SessionErrorLog errors) {
            this.pool = pool;
            this.errors = errors;
        }

        private PoolMetrics metrics() {
            return pool.metrics();
        }

        @Override
        public String poolName() {
            return pool.name();
        }

        @Override
        public int configuredSize() {
            return pool.configuredSize();
        }

        @Override
        public int capacityInUse() {
            return metrics().capacityInUse();
        }

        @Override
        public int available() {
            return metrics().available();
        }

        @Override
        public int leased() {
            return metrics().leased();
        }

        @Override
        public int creating() {
            return metrics().creating();
        }

        @Override
        public int retiring() {
            return metrics().retiring();
        }

        @Override
        public int quarantined() {
            return metrics().quarantined();
        }

        @Override
        public long createAttempts() {
            return metrics().createAttempts();
        }

        @Override
        public long created() {
            return metrics().created();
        }

        @Override
        public long createFailures() {
            return metrics().createFailures();
        }

        @Override
        public long createQuarantineFailures() {
            return metrics().createQuarantineFailures();
        }

        @Override
        public long closeAttempts() {
            return metrics().closeAttempts();
        }

        @Override
        public long closeSuccesses() {
            return metrics().closeSuccesses();
        }

        @Override
        public long closeFailures() {
            return metrics().closeFailures();
        }

        @Override
        public long borrowCount() {
            return metrics().borrowCount();
        }

        @Override
        public long borrowTimeoutCount() {
            return metrics().borrowTimeoutCount();
        }

        @Override
        public double averageBorrowWaitMillis() {
            return metrics().averageBorrowWaitMs();
        }

        @Override
        public double maxBorrowWaitMillis() {
            return metrics().maxBorrowWaitMs();
        }

        @Override
        public CloseState closeState() {
            return pool.closeState();
        }

        @Override
        public Optional<Duration> oldestSessionAge() {
            // Not knowable from the pool: it tracks counts, not per-session creation instants. Reporting an
            // empty optional is the honest answer; a fabricated age would be believed.
            return Optional.empty();
        }

        @Override
        public boolean degraded() {
            return metrics().degraded();
        }

        @Override
        public Optional<String> lastAdapterError() {
            return errors.last();
        }

        @Override
        public String toString() {
            PoolMetrics snapshot = metrics();
            return "CmPoolDiagnostics[" + poolName()
                    + ", size=" + configuredSize()
                    + ", inUse=" + snapshot.capacityInUse()
                    + ", quarantined=" + snapshot.quarantined()
                    + ", state=" + pool.closeState() + "]";
        }
    }
}
