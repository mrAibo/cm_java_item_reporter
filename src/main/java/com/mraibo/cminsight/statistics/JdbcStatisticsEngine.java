package com.mraibo.cminsight.statistics;

import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.connection.PoolMetrics;
import com.mraibo.cminsight.db.AggregateQuery;
import com.mraibo.cminsight.db.JdbcAccessException;
import com.mraibo.cminsight.db.JdbcDialect;
import com.mraibo.cminsight.db.JdbcSession;
import com.mraibo.cminsight.db.JdbcSessionFactory;
import com.mraibo.cminsight.db.PhysicalSchema;
import com.mraibo.cminsight.db.PhysicalSchemaResolver;
import com.mraibo.cminsight.db.SqlQueryBuilder;
import com.mraibo.cminsight.db.SqlUnavailableException;
import com.mraibo.cminsight.metadata.ItemTypeSummary;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The JDBC implementation of {@link StatisticsEngine}: the only bridge between the scan coordinator and the
 * database analytics layer.
 *
 * <h2>Shape of one ItemType measurement</h2>
 *
 * <ol>
 *   <li>borrow ONE {@link JdbcSession} from the hard-bounded pool, and return it before this method returns -
 *       the pool creates the physical connection lazily on the first borrow, which is what keeps repository
 *       activation independent of the database;</li>
 *   <li>register {@link JdbcSession#cancelInFlight} with the scan's cancellation signal, so a cancelled scan
 *       reaches {@code Statement.cancel()} while a query runs, and unregister it when the item is done (the
 *       registration covers the mapping query, every segment probe and the aggregate alike, because they all
 *       run on this one session);</li>
 *   <li>resolve the analytics schema once per engine - the configured one, or the live connection's own
 *       current schema when none was configured - and build the validated {@link SqlQueryBuilder} for it;</li>
 *   <li>resolve the ItemType's physical root segments through {@link PhysicalSchemaResolver}: EVERY expected
 *       segment 1..N, and a missing middle segment fails the ItemType rather than counting a subset;</li>
 *   <li>run the aggregate over all segments (or, when a window boundary cannot be encoded, the total-only
 *       statement) and read its columns.</li>
 * </ol>
 *
 * <p>The coordinator calls this from up to {@code statistics.workers} threads at once. Nothing mutable is
 * shared except the schema context and the resolver's own per-instance mapping cache, both published once and
 * safe to share; the JDBC work itself is a pool borrow, which is atomic. One lease per ItemType covers the
 * mapping and the aggregate, which is what bounds concurrent connections by the worker count.
 *
 * <h2>Failure translation, and why nothing raw escapes</h2>
 *
 * <p>A driver failure arrives as {@link JdbcAccessException}, whose message is built from fixed parts
 * (operation label plus SQLState/vendor code) and whose raw {@code SQLException} is deliberately not
 * attached. A mapping, identifier or missing-segment refusal arrives as {@link SqlUnavailableException},
 * whose message is an actionable, sanitized sentence by contract. Both are re-thrown as
 * {@link StatisticsQueryException}, the only failure type the coordinator records, so a stored reason can
 * never be a driver message, SQL text, a JDBC URL, a user name or a schema taken from an exception.
 *
 * <h2>An unrepresentable window keeps the total</h2>
 *
 * <p>The ItemID date encoding covers 2000-2199. When a window boundary falls outside it, the four window
 * counts cannot be expressed, but the distinct-ItemID total does not use the date key at all. This engine
 * therefore runs the builder's dedicated total-only statement - a different, complete {@code SELECT} with no
 * boundary markers - and reports the total AVAILABLE with all four windows UNAVAILABLE and
 * {@link ScanWindows#unavailableReason()} as their reason, exactly as goal section 10 requires. No boundary
 * value is invented for the window query, because that query is not run at all.
 */
public final class JdbcStatisticsEngine implements StatisticsEngine, StatisticsDiagnosticsSource {

    /** Column 1 of the aggregate: the distinct-ItemID total. */
    private static final int TOTAL_COLUMN = 1;

    /** Column 2 of the aggregate: items created today. */
    private static final int TODAY_COLUMN = 2;

    /** Column 3 of the aggregate: items created in the last 7 days. */
    private static final int LAST_7_COLUMN = 3;

    /** Column 4 of the aggregate: items created in the last 30 days. */
    private static final int LAST_30_COLUMN = 4;

    /** Column 5 of the aggregate: items created in the current calendar year. */
    private static final int CURRENT_YEAR_COLUMN = 5;

    private final JdbcDialect dialect;
    private final BoundedPool<JdbcSession> pool;
    private final JdbcSessionFactory factory;
    private final String configuredSchema;

    private final Object contextLock = new Object();
    private final AtomicReference<SchemaContext> context = new AtomicReference<>();

    /**
     * @param dialect          the database family's SQL; selected by the caller from the repository vendor
     * @param configuredSchema {@code repository.jdbc.schema}, or {@code null} to derive it from the live
     *                         connection's own current schema
     * @param pool             the context's one JDBC pool; created lazily, never initialized eagerly
     * @param factory          the session factory, read for the physical connection facts in diagnostics
     */
    public JdbcStatisticsEngine(JdbcDialect dialect,
                                String configuredSchema,
                                BoundedPool<JdbcSession> pool,
                                JdbcSessionFactory factory) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.configuredSchema = configuredSchema == null || configuredSchema.isBlank()
                ? null : configuredSchema.trim();
        this.pool = Objects.requireNonNull(pool, "pool");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /** The dialect this engine binds its statements with. */
    public JdbcDialect dialect() {
        return dialect;
    }

    /** The validated analytics schema once it is known, or empty before the first scan resolved it. */
    public Optional<String> resolvedSchema() {
        SchemaContext current = context.get();
        return current == null ? Optional.empty() : Optional.of(current.builder.schema());
    }

    // ------------------------------------------------------------------ StatisticsEngine

    @Override
    public LocalDate databaseCurrentDate() throws Exception {
        try (Lease<JdbcSession> lease = pool.borrow()) {
            try {
                // The dialect's own complete statement, executed through the session so it inherits the
                // query timeout and the SELECT-only refusal. This is the ONE database date of the scan.
                return lease.value().queryCurrentDate(dialect);
            } catch (JdbcAccessException failure) {
                throw translate(failure);
            }
        } catch (TimeoutException exhausted) {
            throw borrowFailed(exhausted);
        }
    }

    @Override
    public ItemTypeAggregate aggregate(ItemTypeSummary itemType,
                                       ScanWindows windows,
                                       ScanCancellation cancellation) throws Exception {
        Objects.requireNonNull(itemType, "itemType");
        Objects.requireNonNull(windows, "windows");
        Objects.requireNonNull(cancellation, "cancellation");
        try (Lease<JdbcSession> lease = pool.borrow()) {
            JdbcSession session = lease.value();
            // The abort action lives exactly as long as this query. Registering it with the scan's signal is
            // what makes cancellation reach Statement.cancel() from the coordinator's thread; closing the
            // registration removes it again, so the map never grows past the in-flight queries. Written as an
            // explicit try/finally rather than try-with-resources because the handle is intentionally not
            // read in the body, and the lint pass is right to call that out.
            ScanCancellation.Registration registration = cancellation.register(session::cancelInFlight);
            try {
                SchemaContext schemaContext = schemaContext(session);
                PhysicalSchema schema;
                try {
                    schema = schemaContext.resolver.resolve(session, itemType.itemTypeId(), itemType.name());
                } catch (SqlUnavailableException failure) {
                    throw StatisticsQueryException.withSanitizedReason(
                            "resolve physical root segments", failure.getMessage(), failure);
                }
                return measure(session, schemaContext.builder, schema, windows);
            } finally {
                registration.close();
            }
        } catch (TimeoutException exhausted) {
            throw borrowFailed(exhausted);
        }
    }

    // ------------------------------------------------------------------ StatisticsDiagnosticsSource

    /**
     * The safe pool view: counters, close state and, at most, the factory's own sanitized error sentence.
     *
     * <p>A plain metrics read under the pool's lock - no connection is touched, no statement is prepared and
     * no database is contacted, so a diagnostics route can never delay a borrow or a lifecycle decision.
     */
    @Override
    public StatisticsDiagnostics diagnostics(StatisticsAvailability availability) {
        PoolMetrics metrics = pool.metrics();
        String lastError = factory.lastSafeError()
                .map(JdbcSessionFactory.JdbcSafeError::text)
                .orElse("");
        return StatisticsDiagnostics.of(availability, metrics, pool.closeState(),
                factory.openedConnections(), factory.liveConnections(), factory.peakLiveConnections(),
                lastError);
    }

    // ------------------------------------------------------------------ internals

    /** Runs the right statement for the window state and turns its columns into metrics. */
    private ItemTypeAggregate measure(JdbcSession session,
                                      SqlQueryBuilder builder,
                                      PhysicalSchema schema,
                                      ScanWindows windows) throws StatisticsQueryException {
        if (!windows.fullyRepresentable()) {
            // No boundary can be invented, so the window statement is not run at all: the dedicated
            // total-only statement has no markers and one column, and the four time metrics are reported
            // unavailable with the fixed reason the window type already produced.
            long total = readTotalOnly(session, builder.totalItems(schema));
            MetricValue unavailable = ItemTypeStatistics.unavailableMetric(windows.unavailableReason()
                    .orElse("the window boundaries cannot be encoded from the documented ItemID date rules"));
            return new ItemTypeAggregate(total, unavailable, unavailable, unavailable, unavailable);
        }
        AggregateColumns columns = readAggregate(session, builder.aggregate(schema, windows.parameters()));
        return new ItemTypeAggregate(columns.value(TOTAL_COLUMN),
                windowMetric(columns, TODAY_COLUMN),
                windowMetric(columns, LAST_7_COLUMN),
                windowMetric(columns, LAST_30_COLUMN),
                windowMetric(columns, CURRENT_YEAR_COLUMN));
    }

    /**
     * Reads the five aggregate columns.
     *
     * <p>The window columns are {@code COALESCE(..., 0)} in both vendors' SQL, so a window with no matching
     * item is a REAL zero and becomes {@link MetricValue#available}. Only a genuinely absent column (a NULL
     * the SQL promised not to produce) is reported as not measurable, which is why the presence of each
     * column is tracked separately from its value.
     */
    private AggregateColumns readAggregate(JdbcSession session, AggregateQuery query)
            throws StatisticsQueryException {
        long[] values = new long[CURRENT_YEAR_COLUMN];
        boolean[] present = new boolean[CURRENT_YEAR_COLUMN];
        boolean[] rowSeen = new boolean[1];
        try {
            session.query(query, row -> {
                if (rowSeen[0]) {
                    // The aggregate is a single row; a second one would mean the statement changed shape.
                    return;
                }
                rowSeen[0] = true;
                if (row.columnCount() < CURRENT_YEAR_COLUMN) {
                    throw new JdbcAccessException(query.description(),
                            "the logical-item aggregate returned " + row.columnCount() + " column(s) where "
                                    + CURRENT_YEAR_COLUMN + " were expected");
                }
                for (int column = 1; column <= CURRENT_YEAR_COLUMN; column++) {
                    if (row.isNull(column)) {
                        continue;
                    }
                    values[column - 1] = row.getLong(column);
                    present[column - 1] = true;
                }
            });
        } catch (JdbcAccessException failure) {
            throw translate(failure);
        }
        if (!rowSeen[0] || !present[TOTAL_COLUMN - 1]) {
            throw StatisticsQueryException.withSanitizedReason("logical item aggregate",
                    "the logical-item aggregate returned no distinct-ItemID total, so this ItemType's"
                            + " count cannot be proven", null);
        }
        return new AggregateColumns(values, present);
    }

    /** Reads the single total column of the total-only statement. */
    private long readTotalOnly(JdbcSession session, AggregateQuery query) throws StatisticsQueryException {
        long[] total = new long[1];
        boolean[] present = new boolean[1];
        boolean[] rowSeen = new boolean[1];
        try {
            session.query(query, row -> {
                if (rowSeen[0]) {
                    return;
                }
                rowSeen[0] = true;
                if (row.columnCount() < 1) {
                    throw new JdbcAccessException(query.description(),
                            "the logical-item total query returned no column");
                }
                if (row.isNull(1)) {
                    return;
                }
                total[0] = row.getLong(1);
                present[0] = true;
            });
        } catch (JdbcAccessException failure) {
            throw translate(failure);
        }
        if (!rowSeen[0] || !present[0]) {
            throw StatisticsQueryException.withSanitizedReason("logical item total",
                    "the logical-item total query returned no distinct-ItemID total, so this ItemType's"
                            + " count cannot be proven", null);
        }
        return total[0];
    }

    /** Marker for a window column the database did not return; see {@link #readAggregate}. */
    private static MetricValue windowMetric(AggregateColumns columns, int column) {
        if (!columns.present()[column - 1]) {
            return ItemTypeStatistics.unavailableMetric(
                    "the database returned no value for this window, so it is not measurable");
        }
        return MetricValue.available(columns.value(column));
    }

    /** The five aggregate columns plus whether the database actually returned each one. */
    private record AggregateColumns(long[] values, boolean[] present) {

        private long value(int column) {
            return values[column - 1];
        }
    }

    /**
     * The schema-dependent half of the engine, created once on first use.
     *
     * <p>Created lazily on purpose: when no schema is configured, the connection's own current schema can
     * only be read from a live session, and reading it during repository activation would mean opening a
     * connection at activation - exactly what the goal forbids. The first scan resolves it, validates it with
     * the same identifier rule, and reuses the result for every later ItemType and every later scan of this
     * context. The resolver's mapping cache is per instance, i.e. per repository context, so a repository
     * switch can never be served a mapping resolved against another schema.
     */
    private SchemaContext schemaContext(JdbcSession session) throws StatisticsQueryException {
        SchemaContext current = context.get();
        if (current != null) {
            return current;
        }
        synchronized (contextLock) {
            current = context.get();
            if (current != null) {
                return current;
            }
            String schema = configuredSchema;
            if (schema == null) {
                try {
                    schema = session.currentSchema();
                } catch (JdbcAccessException failure) {
                    throw translate(failure);
                }
                if (schema == null || schema.isBlank()) {
                    throw StatisticsQueryException.withSanitizedReason("resolve the analytics schema",
                            "the database connection reported no current schema and repository.jdbc.schema"
                                    + " is not set, so the analytics tables cannot be addressed", null);
                }
            }
            try {
                SqlQueryBuilder builder = SqlQueryBuilder.forSchema(dialect, schema);
                SchemaContext created = new SchemaContext(
                        new PhysicalSchemaResolver(dialect, builder.schema()), builder);
                context.set(created);
                return created;
            } catch (SqlUnavailableException failure) {
                // A schema that needs quoted-identifier semantics is reported as unavailable with an
                // actionable reason, never interpolated into SQL.
                throw StatisticsQueryException.withSanitizedReason("resolve the analytics schema",
                        failure.getMessage(), failure);
            }
        }
    }

    /** Translates a sanitized JDBC failure into the one failure type the coordinator records. */
    private static StatisticsQueryException translate(JdbcAccessException failure) {
        return new StatisticsQueryException(failure.operation(), failure.sqlState(),
                failure.vendorCode() == 0 ? null : failure.vendorCode(), failure);
    }

    private static StatisticsQueryException borrowFailed(TimeoutException exhausted) {
        return StatisticsQueryException.withSanitizedReason("borrow a JDBC session",
                "no JDBC connection could be borrowed within the pool's borrow timeout, so the analytics"
                        + " pool is exhausted or its connections could not be created", exhausted);
    }

    @Override
    public String toString() {
        return "JdbcStatisticsEngine[dialect=" + dialect.id()
                + ", schema=" + (configuredSchema == null ? "(derived at first scan)" : configuredSchema)
                + ", pool=" + pool.name() + ", state=" + pool.closeState() + "]";
    }

    /** The validated schema and the resolver for it, created once per engine. */
    private record SchemaContext(PhysicalSchemaResolver resolver, SqlQueryBuilder builder) {
    }
}
