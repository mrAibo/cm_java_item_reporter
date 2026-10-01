package com.mraibo.cminsight.statistics;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.core.JdbcPoolSettings;
import com.mraibo.cminsight.core.StatisticsSettings;
import com.mraibo.cminsight.db.JdbcDialect;
import com.mraibo.cminsight.db.JdbcDrivers;
import com.mraibo.cminsight.db.JdbcSession;
import com.mraibo.cminsight.db.JdbcSessionFactory;
import com.mraibo.cminsight.metadata.ItemTypeSummary;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Builds the analytics capability of one repository context - and, crucially, only builds it when it can
 * actually work.
 *
 * <h2>JDBC is optional to activation</h2>
 *
 * <p>A repository whose IBM CM adapter works must stay selectable and its metadata/retention pages usable
 * when the analytics feature is switched off, when the DB2/Oracle driver is missing, when the JDBC URL
 * belongs to another vendor family, or when the JDBC credential cannot be resolved. {@link #activate} is the
 * single place that decides, locally and without touching a database:
 *
 * <ol>
 *   <li>the feature switch is read first, so {@code feature.statistics=false} costs nothing and creates
 *       nothing;</li>
 *   <li>{@link JdbcDrivers#readiness} answers "is a driver installed and registered for this vendor, and does
 *       the configured URL belong to it?" - class loading and {@code DriverManager} inspection only, never a
 *       connection, never the network;</li>
 *   <li>the JDBC credential pair is resolved once, so an unreadable or missing secret is reported here rather
 *       than discovered as a failed scan.</li>
 * </ol>
 *
 * <p>Every one of those refusals returns an <em>unavailable</em> service and registers NO resource: no pool,
 * no coordinator and no connection exist, so the CM half cannot be affected by them. The refusal reason is
 * fixed and value-free - no URL, no user name, no schema, no secret - so a route or a doctor line may print
 * it verbatim.
 *
 * <h2>The pool is created, never initialized, and the registration order matters</h2>
 *
 * <p>When everything is available, this method builds the {@link BoundedPool} through
 * {@link JdbcSessionFactory#lazyPool} and deliberately <strong>never</strong> calls
 * {@code BoundedPool.initialize()}: the pool object exists immediately so the context can own and later
 * close a real pool, while every physical connection is created lazily by the first borrow. That is the
 * mechanism which makes activation independent of the database's reachability.
 *
 * <p>The resources are handed to {@code resourceRegistrar} in a fixed order - the pool FIRST, the
 * coordinator SECOND, the targeted-refresh capability THIRD - because {@code RepositoryContext} closes its
 * resources in reverse order. The targeted capability is therefore closed first (its in-flight operation
 * cancelled and drained, its detail cache discarded), then the coordinator (scan cancelled and threads
 * joined), and only then the pool they both borrow from, so no analytics operation can be running against a
 * pool that is already closed and no detail measured against the previous repository can survive into the
 * next one.
 *
 * <p>ONE {@link AnalyticsOperationGate} belongs to one activation and is handed to BOTH the coordinator and
 * the targeted capability, so a full scan and a targeted refresh can never overlap and there is no second
 * latch that could disagree with the first.
 *
 * <p>Because the pool is registered as an owned resource of the context, the context's close state derives
 * from it for free: a connection still out on a lease reports {@code CLOSING} (the switch is refused as
 * pending), and a quarantined connection reports {@code CLOSED_UNCERTAIN} (the switch is refused
 * permanently) - the same Goal 01C semantics the CM pool already has, with nothing weakened and nothing
 * hidden behind an opaque wrapper.
 */
public final class StatisticsCapability {

    private StatisticsCapability() {
    }

    /**
     * Builds the analytics capability with the documented default freshness threshold.
     *
     * @see #activate(RepositoryProfile, SecretResolver, JdbcDialect, JdbcPoolSettings, StatisticsSettings,
     *      Supplier, Consumer, FreshnessThreshold)
     */
    public static StatisticsService activate(RepositoryProfile profile,
                                             SecretResolver secrets,
                                             JdbcDialect dialect,
                                             JdbcPoolSettings poolSettings,
                                             StatisticsSettings settings,
                                             Supplier<List<ItemTypeSummary>> itemTypeSource,
                                             Consumer<AutoCloseable> resourceRegistrar) {
        return activate(profile, secrets, dialect, poolSettings, settings, itemTypeSource, resourceRegistrar,
                FreshnessThreshold.defaults());
    }

    /**
     * Builds the analytics capability, or explains why it cannot be built.
     *
     * @param profile           the repository being activated; its vendor, URL and schema are read, never a
     *                          credential value
     * @param secrets           the resolver the JDBC credential pair is checked with and re-read per
     *                          connection
     * @param dialect           the vendor's SQL, selected by the caller from {@code profile.databaseVendor()}
     * @param poolSettings      the validated JDBC pool bounds
     * @param settings          the validated analytics bounds (worker count, query timeout, scan deadline)
     * @param itemTypeSource    called once per scan to freeze the ItemType list, normally
     *                          {@code metadataRepository::listItemTypes}
     * @param resourceRegistrar receives the owned resources in the order the context must close them
     *                          (register last = closed first); normally {@code resources::add}
     * @param freshnessThreshold the configured {@code cache.statistics.ttl.seconds}, carried by the service
     *                          so a freshness judgement is never made against a number the operator did not
     *                          write - including while the analytics half is disabled or unavailable
     * @return a service that is either usable or explains why it is not; never {@code null}
     */
    public static StatisticsService activate(RepositoryProfile profile,
                                             SecretResolver secrets,
                                             JdbcDialect dialect,
                                             JdbcPoolSettings poolSettings,
                                             StatisticsSettings settings,
                                             Supplier<List<ItemTypeSummary>> itemTypeSource,
                                             Consumer<AutoCloseable> resourceRegistrar,
                                             FreshnessThreshold freshnessThreshold) {
        return activate(profile, secrets, dialect, poolSettings, settings, itemTypeSource, resourceRegistrar,
                freshnessThreshold, snapshot -> { });
    }

    /**
     * The full Goal 04 form: additionally reports each published snapshot to a listener.
     *
     * <p>The listener is how persistent aggregate history sees a completed scan. It is wired HERE rather
     * than by a poller or by the request that started the scan, because the coordinator invokes it from its
     * single publication point - which only a scan that reached normal terminal completion ever reaches. A
     * timed-out, cancelled or catastrophic scan therefore creates no history row as a property of where the
     * hook sits, and a targeted refresh cannot create one at all because it never publishes a full snapshot.
     *
     * @param publicationListener called after each full snapshot is published; a failure inside it is
     *                            contained by the coordinator, so optional history can never fail a scan
     */
    public static StatisticsService activate(RepositoryProfile profile,
                                             SecretResolver secrets,
                                             JdbcDialect dialect,
                                             JdbcPoolSettings poolSettings,
                                             StatisticsSettings settings,
                                             Supplier<List<ItemTypeSummary>> itemTypeSource,
                                             Consumer<AutoCloseable> resourceRegistrar,
                                             FreshnessThreshold freshnessThreshold,
                                             Consumer<StatisticsSnapshot> publicationListener) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(secrets, "secrets");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(poolSettings, "poolSettings");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(itemTypeSource, "itemTypeSource");
        Objects.requireNonNull(resourceRegistrar, "resourceRegistrar");
        Objects.requireNonNull(freshnessThreshold, "freshnessThreshold");
        Objects.requireNonNull(publicationListener, "publicationListener");

        if (!settings.enabled()) {
            return StatisticsService.disabled(profile.id(),
                    StatisticsSettings.ENABLED_KEY + "=false, so no analytics pool was created",
                    freshnessThreshold);
        }

        JdbcDrivers.Readiness readiness = JdbcDrivers.readiness(profile.databaseVendor(), profile.jdbcUrl());
        if (!readiness.ready()) {
            return StatisticsService.unavailable(profile.id(),
                    "the analytics JDBC pool was not created: " + readiness.reason(), freshnessThreshold);
        }

        try {
            // Resolved once here purely as a provable precondition. The value is NOT cached and NOT passed
            // on: the session factory re-resolves the pair for every new or replacement connection, so a
            // credential rotated or revoked after activation takes effect at the next connection.
            profile.resolveJdbcCredentials(secrets);
        } catch (RuntimeException unreadable) {
            // The message is deliberately not reproduced: a configuration error can name a file path or a
            // field, and this reason is published verbatim.
            return StatisticsService.unavailable(profile.id(),
                    "the analytics JDBC credentials could not be resolved, so no analytics pool was"
                            + " created; see the configuration diagnostics for"
                            + " repository.jdbc.user/repository.jdbc.password", freshnessThreshold);
        }

        JdbcSessionFactory factory = new JdbcSessionFactory(profile, secrets, settings.queryTimeoutSeconds());
        BoundedPool<JdbcSession> pool = factory.lazyPool(
                "jdbc:" + profile.id(),
                poolSettings.size(),
                poolSettings.borrowTimeout(),
                poolSettings.maxAge(),
                poolSettings.maxOperations());
        JdbcStatisticsEngine engine = new JdbcStatisticsEngine(dialect, profile.jdbcSchema(), pool, factory);
        // ONE arbiter for this activation, handed to BOTH analytics operations. It is created here, where
        // the pool and the engine are, because this is the only place that owns both of them together.
        AnalyticsOperationGate operationGate = new AnalyticsOperationGate();
        ScanCoordinator coordinator = new ScanCoordinator(settings, profile.id(), itemTypeSource, engine,
                operationGate, "full-scan:" + profile.id(), publicationListener);
        TargetedRefreshService targetedRefresh = new TargetedRefreshService(operationGate, settings,
                profile.id(), itemTypeSource, engine, freshnessThreshold);

        // Order is the contract: the pool is registered first, so the targeted capability (registered
        // third) is closed FIRST by RepositoryContext's reverse-order close, then the coordinator, then the
        // pool. A repository switch therefore cancels and drains a running targeted refresh and discards
        // its detail cache before the coordinator's scan is drained and long before the pool is closed.
        resourceRegistrar.accept(pool);
        resourceRegistrar.accept(coordinator);
        resourceRegistrar.accept(targetedRefresh);

        return StatisticsService.of(profile.id(), coordinator, engine, freshnessThreshold, targetedRefresh);
    }
}
