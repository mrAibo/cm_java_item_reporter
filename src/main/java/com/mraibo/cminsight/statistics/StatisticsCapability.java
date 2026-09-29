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
 * <p>The two resources are handed to {@code resourceRegistrar} in a fixed order - the pool FIRST, the
 * coordinator SECOND - because {@code RepositoryContext} closes its resources in reverse order. The
 * coordinator is therefore always closed (scan cancelled and threads joined) before the pool it borrows
 * from, so a scan can never be running against a pool that is already closed.
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
     * @return a service that is either usable or explains why it is not; never {@code null}
     */
    public static StatisticsService activate(RepositoryProfile profile,
                                             SecretResolver secrets,
                                             JdbcDialect dialect,
                                             JdbcPoolSettings poolSettings,
                                             StatisticsSettings settings,
                                             Supplier<List<ItemTypeSummary>> itemTypeSource,
                                             Consumer<AutoCloseable> resourceRegistrar) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(secrets, "secrets");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(poolSettings, "poolSettings");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(itemTypeSource, "itemTypeSource");
        Objects.requireNonNull(resourceRegistrar, "resourceRegistrar");

        if (!settings.enabled()) {
            return StatisticsService.disabled(profile.id(),
                    StatisticsSettings.ENABLED_KEY + "=false, so no analytics pool was created");
        }

        JdbcDrivers.Readiness readiness = JdbcDrivers.readiness(profile.databaseVendor(), profile.jdbcUrl());
        if (!readiness.ready()) {
            return StatisticsService.unavailable(profile.id(),
                    "the analytics JDBC pool was not created: " + readiness.reason());
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
                            + " repository.jdbc.user/repository.jdbc.password");
        }

        JdbcSessionFactory factory = new JdbcSessionFactory(profile, secrets, settings.queryTimeoutSeconds());
        BoundedPool<JdbcSession> pool = factory.lazyPool(
                "jdbc:" + profile.id(),
                poolSettings.size(),
                poolSettings.borrowTimeout(),
                poolSettings.maxAge(),
                poolSettings.maxOperations());
        JdbcStatisticsEngine engine = new JdbcStatisticsEngine(dialect, profile.jdbcSchema(), pool, factory);
        ScanCoordinator coordinator = new ScanCoordinator(settings, profile.id(), itemTypeSource, engine);

        // Order is the contract: the pool is registered first, so the coordinator (registered second) is
        // closed FIRST by RepositoryContext's reverse-order close - the scan is drained before the pool.
        resourceRegistrar.accept(pool);
        resourceRegistrar.accept(coordinator);

        return StatisticsService.of(profile.id(), coordinator, engine);
    }
}
