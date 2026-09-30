package com.mraibo.cminsight.history;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * The local, file-backed implementation of {@link HistoryStore}: one H2 database file below {@code data.dir}.
 *
 * <h2>Nothing here is required for the application to work</h2>
 *
 * <p>The local database is an <em>optional</em> runtime input, discovered by class name through
 * {@code java.sql}. The core compiles and every test passes with zero local-database JARs present, because
 * no type from that JAR is ever named at compile time. When the driver is missing, or the file cannot be
 * opened, or the file was written by another schema version, this class reports a REFUSED state
 * ({@link #available()} false plus a fixed reason) instead of throwing: repository activation, the metadata
 * and retention halves and the live statistics must never fail because an operator did not install a JAR.
 *
 * <h2>At most ONE physical connection, owned here</h2>
 *
 * <p>The connection is opened once, eagerly, by the factory - never on the publish thread and never per
 * request - and is reused for the store's whole life. Every public method runs under one lock and the
 * connection is private to this object: there is no pool, no per-call connection, and no way for a caller to
 * obtain a handle. The repository's JDBC credentials and the analytics pool are never touched; the local
 * database is a file this application owns and its URL is built from the resolved data directory only.
 *
 * <h2>One transaction per write, so a reader sees the old state or the whole new one</h2>
 *
 * <p>Autocommit is off for the life of the connection. A recorded snapshot - its row, every ItemType row,
 * every metric row and the retention prune - is written inside ONE transaction with a single
 * {@link LocalDatabase#commit()} at the end. Because every reader shares that same lock, a reader can never
 * run while a write transaction is in flight, and it can only ever observe committed state: a failure
 * anywhere in the write rolls the whole transaction back and leaves the previously committed rows exactly as
 * they were. There is no partial snapshot to see, for a reader or for the next process that opens the file.
 *
 * <h2>Schema versioning is explicit</h2>
 *
 * <p>The schema is created on first open and its version is recorded in the database itself
 * ({@link #SCHEMA_VERSION}, currently 1). A file whose recorded version is not the version this build writes
 * is REFUSED - {@link #available()} becomes false, {@link #schemaVersionMismatch()} becomes true, and the
 * reason names both versions. Nothing is migrated, reinterpreted or "best-effort" read: a stored read model
 * whose meaning changed silently is worse than a read model that is not served.
 *
 * <h2>Value-free failures</h2>
 *
 * <p>A failure reason never carries a raw vendor message or a connection string: a SQL failure is reported as
 * its SQLState and exception class, and every other failure as a bounded, control-character-free excerpt.
 * Nothing that could carry a credential is ever stored, returned or logged.
 */
public final class H2HistoryStore implements HistoryStore {

    /**
     * The schema version this build writes.
     *
     * <p>Bumping it is a deliberate act that must come with a migration: the reader refuses a file whose
     * recorded version differs rather than trying to interpret rows it was not written for. Versions and
     * Parts are deliberately absent from the stored shape altogether - when that research lands, the schema
     * gains columns and this number changes, which is exactly the explicit, versioned change a persisted
     * read model requires.
     */
    public static final int SCHEMA_VERSION = 1;

    /** Largest page a single read may materialize; a larger requested limit is capped here. */
    public static final int MAX_PAGE_SIZE = 10_000;

    /** The local database's own fixed default account. Never a repository credential. */
    private static final String LOCAL_DATABASE_USER = "sa";
    /** The local database's fixed empty password: the file is application-local and holds no secret. */
    private static final String LOCAL_DATABASE_PASSWORD = "";

    /**
     * URL options, stated rather than defaulted.
     *
     * <p>{@code DB_CLOSE_ON_EXIT=FALSE} keeps the database's lifetime owned by {@link #close()} instead of by
     * a JVM shutdown hook, so a repository shutdown that closes the store sees the real outcome.
     * {@code LOCK_TIMEOUT=5000} bounds every wait: a write is performed on the publication path of a
     * completed scan, so it must fail rather than block indefinitely. {@code AUTO_SERVER=FALSE} keeps this a
     * single-process local file, which is the whole storage model.
     */
    private static final String URL_OPTIONS =
            ";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE;LOCK_TIMEOUT=5000";

    /** Longest failure reason kept for diagnostics. */
    private static final int MAX_FAILURE_REASON_LENGTH = 300;

    /** Longest value a bounded column accepts; an over-long value fails the write instead of truncating. */
    static final int MAX_TEXT_LENGTH = 1_000;

    // ------------------------------------------------------------------ schema

    private static final String SQL_CREATE_SCHEMA_TABLE =
            "CREATE TABLE IF NOT EXISTS cm_history_schema ("
                    + " schema_version INTEGER NOT NULL,"
                    + " installed_at TIMESTAMP WITH TIME ZONE NOT NULL)";

    private static final String SQL_CREATE_SNAPSHOT_TABLE =
            "CREATE TABLE IF NOT EXISTS cm_history_snapshot ("
                    + " snapshot_seq BIGINT GENERATED BY DEFAULT AS IDENTITY,"
                    + " public_id VARCHAR(64) NOT NULL,"
                    + " repository_id VARCHAR(200) NOT NULL,"
                    + " repository_display_name VARCHAR(300) NOT NULL,"
                    + " database_vendor VARCHAR(64) NOT NULL,"
                    + " captured_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                    + " scan_started_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                    + " scan_duration_ms BIGINT NOT NULL,"
                    + " anchor_date DATE,"
                    + " scan_id BIGINT NOT NULL,"
                    + " item_type_count INTEGER NOT NULL,"
                    + " partial_failure_count INTEGER NOT NULL,"
                    + " is_complete BOOLEAN NOT NULL,"
                    + " logical_items_total BIGINT NOT NULL,"
                    + " warning_text VARCHAR(" + MAX_TEXT_LENGTH + ") NOT NULL,"
                    + " PRIMARY KEY (snapshot_seq),"
                    + " CONSTRAINT cm_history_snapshot_public_id UNIQUE (public_id))";

    private static final String SQL_CREATE_ITEM_TYPE_TABLE =
            "CREATE TABLE IF NOT EXISTS cm_history_item_type ("
                    + " snapshot_seq BIGINT NOT NULL,"
                    + " row_ordinal INTEGER NOT NULL,"
                    + " item_type_id INTEGER NOT NULL,"
                    + " item_type_name VARCHAR(300) NOT NULL,"
                    + " business_classification VARCHAR(300) NOT NULL,"
                    + " retention_policy_name VARCHAR(300) NOT NULL,"
                    + " status VARCHAR(16) NOT NULL,"
                    + " duration_ms BIGINT NOT NULL,"
                    + " reason_text VARCHAR(" + MAX_TEXT_LENGTH + ") NOT NULL,"
                    + " PRIMARY KEY (snapshot_seq, row_ordinal),"
                    + " CONSTRAINT cm_history_item_type_snapshot FOREIGN KEY (snapshot_seq)"
                    + " REFERENCES cm_history_snapshot (snapshot_seq) ON DELETE CASCADE)";

    private static final String SQL_CREATE_METRIC_TABLE =
            "CREATE TABLE IF NOT EXISTS cm_history_metric ("
                    + " snapshot_seq BIGINT NOT NULL,"
                    + " row_ordinal INTEGER NOT NULL,"
                    + " metric_name VARCHAR(32) NOT NULL,"
                    + " metric_state VARCHAR(16) NOT NULL,"
                    + " measured_value BIGINT,"
                    + " reason_text VARCHAR(" + MAX_TEXT_LENGTH + ") NOT NULL,"
                    + " PRIMARY KEY (snapshot_seq, row_ordinal, metric_name),"
                    + " CONSTRAINT cm_history_metric_snapshot FOREIGN KEY (snapshot_seq)"
                    + " REFERENCES cm_history_snapshot (snapshot_seq) ON DELETE CASCADE,"
                    // Structural, not conventional: a metric that was not measured cannot be stored with a
                    // number, so a stored zero is always a real measurement of zero.
                    + " CONSTRAINT cm_history_metric_value CHECK ("
                    + "(metric_state = 'AVAILABLE' AND measured_value IS NOT NULL)"
                    + " OR (metric_state <> 'AVAILABLE' AND measured_value IS NULL)))";

    private static final String SQL_CREATE_SNAPSHOT_ORDER_INDEX =
            "CREATE INDEX IF NOT EXISTS cm_history_snapshot_repository_order"
                    + " ON cm_history_snapshot (repository_id, captured_at, snapshot_seq)";

    private static final List<String> SCHEMA_DDL = List.of(
            SQL_CREATE_SCHEMA_TABLE,
            SQL_CREATE_SNAPSHOT_TABLE,
            SQL_CREATE_ITEM_TYPE_TABLE,
            SQL_CREATE_METRIC_TABLE,
            SQL_CREATE_SNAPSHOT_ORDER_INDEX);

    // ------------------------------------------------------------------ statements

    private static final String SUMMARY_COLUMNS =
            "public_id, repository_id, repository_display_name, database_vendor, captured_at,"
                    + " scan_started_at, scan_duration_ms, anchor_date, scan_id, item_type_count,"
                    + " partial_failure_count, is_complete, logical_items_total";

    private static final String ORDER_NEWEST_FIRST = " ORDER BY captured_at DESC, snapshot_seq DESC";

    private static final String SQL_SELECT_SCHEMA_VERSION =
            "SELECT schema_version FROM cm_history_schema";

    private static final String SQL_INSERT_SCHEMA_VERSION =
            "INSERT INTO cm_history_schema (schema_version, installed_at) VALUES (?, ?)";

    private static final String SQL_TABLE_PRESENT =
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?";

    private static final String SQL_COUNT_SNAPSHOTS =
            "SELECT COUNT(*) FROM cm_history_snapshot WHERE repository_id = ?";

    private static final String SQL_INSERT_SNAPSHOT =
            "INSERT INTO cm_history_snapshot (public_id, repository_id, repository_display_name,"
                    + " database_vendor, captured_at, scan_started_at, scan_duration_ms, anchor_date, scan_id,"
                    + " item_type_count, partial_failure_count, is_complete, logical_items_total, warning_text)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_INSERT_ITEM_TYPE =
            "INSERT INTO cm_history_item_type (snapshot_seq, row_ordinal, item_type_id, item_type_name,"
                    + " business_classification, retention_policy_name, status, duration_ms, reason_text)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_INSERT_METRIC =
            "INSERT INTO cm_history_metric (snapshot_seq, row_ordinal, metric_name, metric_state,"
                    + " measured_value, reason_text) VALUES (?, ?, ?, ?, ?, ?)";

    /**
     * Retention, in the SAME transaction as the insert it follows.
     *
     * <p>The newest {@code history.max.snapshots.per.repository} rows of ONE repository are kept, ranked by
     * the same (captured instant, sequence) order every read uses; everything below that ranking is deleted
     * for that repository only. The child rows go with their snapshot through the schema's
     * {@code ON DELETE CASCADE}, so pruning can never leave an orphaned ItemType or metric row behind.
     */
    private static final String SQL_PRUNE =
            "DELETE FROM cm_history_snapshot"
                    + " WHERE repository_id = ?"
                    + " AND snapshot_seq NOT IN ("
                    + "     SELECT snapshot_seq FROM cm_history_snapshot"
                    + "     WHERE repository_id = ?"
                    + "     ORDER BY captured_at DESC, snapshot_seq DESC LIMIT ?)";

    private static final String SQL_LIST =
            "SELECT " + SUMMARY_COLUMNS + " FROM cm_history_snapshot"
                    + " WHERE repository_id = ?" + ORDER_NEWEST_FIRST + " LIMIT ?";

    private static final String SQL_CURSOR =
            "SELECT snapshot_seq, captured_at FROM cm_history_snapshot"
                    + " WHERE public_id = ? AND repository_id = ?";

    private static final String SQL_LIST_AFTER_KEY =
            "SELECT " + SUMMARY_COLUMNS + " FROM cm_history_snapshot"
                    + " WHERE repository_id = ? AND (captured_at < ? OR (captured_at = ? AND snapshot_seq < ?))"
                    + ORDER_NEWEST_FIRST + " LIMIT ?";

    private static final String SQL_LIST_AFTER_TIME =
            "SELECT " + SUMMARY_COLUMNS + " FROM cm_history_snapshot"
                    + " WHERE repository_id = ? AND captured_at < ?" + ORDER_NEWEST_FIRST + " LIMIT ?";

    private static final String SQL_FIND =
            "SELECT " + SUMMARY_COLUMNS + ", snapshot_seq, warning_text FROM cm_history_snapshot"
                    + " WHERE public_id = ?";

    private static final String SQL_FIND_ITEM_TYPES =
            "SELECT row_ordinal, item_type_id, item_type_name, business_classification,"
                    + " retention_policy_name, status, duration_ms, reason_text"
                    + " FROM cm_history_item_type WHERE snapshot_seq = ? ORDER BY row_ordinal";

    private static final String SQL_FIND_METRICS =
            "SELECT row_ordinal, metric_name, metric_state, measured_value, reason_text"
                    + " FROM cm_history_metric WHERE snapshot_seq = ? ORDER BY row_ordinal, metric_name";

    private static final String METRIC_LOGICAL_ITEMS = "logical_items";
    private static final String METRIC_CREATED_TODAY = "created_today";
    private static final String METRIC_CREATED_LAST_7_DAYS = "created_last_7_days";
    private static final String METRIC_CREATED_LAST_30_DAYS = "created_last_30_days";
    private static final String METRIC_CREATED_CURRENT_YEAR = "created_current_year";

    /** The fixed reason a version-mismatched file is refused with; the recorded version is appended. */
    private static final String MISMATCH_PREFIX =
            "the local history database records schema version ";

    // ------------------------------------------------------------------ state

    private final Driver driver;
    private final Path databasePath;
    private final String jdbcUrl;
    private final int maxSnapshotsPerRepository;
    private final Instant openedAt;
    private final Object lock = new Object();

    private LocalDatabase database;
    private int connectionsOpened;
    private Integer storedSchemaVersion;
    private boolean schemaVersionMismatch;
    private String lastFailure = "";
    private volatile String refusalReason = "";

    private H2HistoryStore(Driver driver, Path databasePath, int maxSnapshotsPerRepository) {
        this.driver = driver;
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath")
                .toAbsolutePath().normalize();
        this.maxSnapshotsPerRepository = maxSnapshotsPerRepository;
        if (maxSnapshotsPerRepository < 1) {
            // An impossible retention bound is a programming error, not a runtime state: it cannot come from
            // configuration (HistorySettings range-checks it) and silently clamping it would hide a defect.
            throw new IllegalArgumentException("maxSnapshotsPerRepository must be at least 1 but was "
                    + maxSnapshotsPerRepository);
        }
        this.jdbcUrl = "jdbc:h2:file:" + this.databasePath.toString().replace('\\', '/') + URL_OPTIONS;
        this.openedAt = Instant.now();
    }

    /**
     * Opens the local database below {@code databasePath}, discovering the driver by class name.
     *
     * <p>Never throws: a missing driver, an uncreatable directory, an unusable file or a version mismatch all
     * produce a refused store object, so a caller can always report the state instead of handling an
     * exception on an activation path.
     */
    public static H2HistoryStore open(Path databasePath, int maxSnapshotsPerRepository) {
        Objects.requireNonNull(databasePath, "databasePath");
        Optional<Class<? extends Driver>> found = HistoryStores.loadDriverClass(HistoryStores.H2_DRIVER_CLASS);
        if (found.isEmpty()) {
            H2HistoryStore refused = new H2HistoryStore(null, databasePath, maxSnapshotsPerRepository);
            refused.refuse("the local history database driver " + HistoryStores.H2_DRIVER_CLASS
                    + " is not on this runtime's class path");
            return refused;
        }
        return open(found.get(), databasePath, maxSnapshotsPerRepository);
    }

    /**
     * As {@link #open(Path, int)} but from a driver CLASS.
     *
     * <p>The class is instantiated here rather than registered process-wide, so opening a store has no global
     * side effect and a test can hand in a driver class of its own.
     */
    public static H2HistoryStore open(Class<? extends Driver> driverClass, Path databasePath,
                                     int maxSnapshotsPerRepository) {
        Objects.requireNonNull(driverClass, "driverClass");
        final Driver driver;
        try {
            driver = driverClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            H2HistoryStore refused = new H2HistoryStore(null, databasePath, maxSnapshotsPerRepository);
            refused.refuse("the local history database driver class " + driverClass.getName()
                    + " could not be instantiated: " + reasonOf(failure));
            return refused;
        }
        return open(driver, databasePath, maxSnapshotsPerRepository);
    }

    /**
     * As {@link #open(Path, int)} but from an already instantiated driver.
     *
     * <p>This is the seam a dependency-free test suite uses: a {@code java.sql.Driver} is a JDK type, so the
     * refusal paths - a version mismatch, a statement failure, a connection that never opens - can be
     * exercised with no local database JAR on the class path at all.
     */
    public static H2HistoryStore open(Driver driver, Path databasePath, int maxSnapshotsPerRepository) {
        Objects.requireNonNull(driver, "driver");
        H2HistoryStore store = new H2HistoryStore(driver, databasePath, maxSnapshotsPerRepository);
        store.initialise();
        return store;
    }

    /** Creates the data directory, opens the ONE connection and reconciles the schema. */
    private void initialise() {
        synchronized (lock) {
            if (!refusalReason.isEmpty() || driver == null) {
                return;
            }
            try {
                Path directory = databasePath.getParent();
                if (directory != null) {
                    // Explicit, and created here rather than assumed: nothing else in the application has
                    // made this directory, and a relative path resolved against the application home may be
                    // several levels deep.
                    Files.createDirectories(directory);
                }
                database = openConnection();
                reconcileSchema();
            } catch (SQLException | IOException | RuntimeException failure) {
                refuse("the local history database " + databasePath.getFileName()
                        + " could not be opened: " + reasonOf(failure));
                releaseConnection();
            }
        }
    }

    /** Opens the one physical connection this store will ever hold. */
    private LocalDatabase openConnection() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", LOCAL_DATABASE_USER);
        properties.setProperty("password", LOCAL_DATABASE_PASSWORD);
        LocalDatabase opened = LocalDatabase.connect(driver, jdbcUrl, properties);
        connectionsOpened++;
        return opened;
    }

    /**
     * Creates the schema on a fresh file, or validates the version of an existing one.
     *
     * <p>Existence is probed through {@code INFORMATION_SCHEMA} first, because a query against a table that
     * does not exist yet is a driver error rather than an empty result - and the very first open of a fresh
     * file is exactly that case. Every statement is {@code IF NOT EXISTS}, so an interrupted creation is
     * completed by the next open rather than leaving a file that can never be used. The version row is
     * written LAST, so "a version table with no row" means "creation did not finish" and never "some other
     * version" - and it is only repaired when the file holds no snapshot rows at all. Anything else is an
     * explicit refusal, because adopting rows written by an unknown schema is exactly the silent
     * reinterpretation this class refuses to perform.
     */
    private void reconcileSchema() throws SQLException {
        if (!tablePresent("CM_HISTORY_SCHEMA")) {
            if (snapshotsPresent()) {
                refuse("the local history database holds stored snapshots but no recorded schema version:"
                        + " refusing to adopt an unknown schema. Restore the file, or move it aside to start"
                        + " a new local history.");
                releaseConnection();
                return;
            }
            createSchema();
            recordSchemaVersion();
            return;
        }

        List<Object[]> recorded = database.query(SQL_SELECT_SCHEMA_VERSION, List.of(), 2);
        if (recorded.isEmpty()) {
            if (snapshotsPresent()) {
                refuse("the local history database holds stored snapshots but no recorded schema version:"
                        + " refusing to adopt an unknown schema. Restore the file, or move it aside to start"
                        + " a new local history.");
                releaseConnection();
                return;
            }
            // A creation that did not finish: complete it, rather than leaving an unusable file behind.
            createSchema();
            recordSchemaVersion();
            return;
        }

        int version = intOf(recorded.get(0)[0]);
        storedSchemaVersion = version;
        if (version != SCHEMA_VERSION) {
            refuse(MISMATCH_PREFIX + version + " but this build writes version " + SCHEMA_VERSION
                    + ": the stored rows are not reinterpreted. Move the file aside, or migrate it with the"
                    + " version that wrote it; local history stays unavailable until then, and nothing else"
                    + " is affected.");
            releaseConnection();
            return;
        }
        // The version matches: re-running the idempotent DDL repairs an interrupted creation and creates a
        // table a later version of this schema added, without touching a single stored row.
        createSchema();
    }

    private void recordSchemaVersion() throws SQLException {
        database.update(SQL_INSERT_SCHEMA_VERSION,
                parameters(SCHEMA_VERSION, OffsetDateTime.now(ZoneOffset.UTC)));
        database.commit();
        storedSchemaVersion = SCHEMA_VERSION;
    }

    private boolean snapshotsPresent() throws SQLException {
        return tablePresent("CM_HISTORY_SNAPSHOT") && countAllSnapshots() > 0L;
    }

    private void createSchema() throws SQLException {
        for (String statement : SCHEMA_DDL) {
            database.update(statement, List.of());
        }
    }

    private boolean tablePresent(String tableName) throws SQLException {
        List<Object[]> rows = database.query(SQL_TABLE_PRESENT, parameters(tableName), 1);
        return !rows.isEmpty() && longOf(rows.get(0)[0]) > 0L;
    }

    private long countAllSnapshots() throws SQLException {
        List<Object[]> rows = database.query("SELECT COUNT(*) FROM cm_history_snapshot", List.of(), 1);
        return rows.isEmpty() ? 0L : longOf(rows.get(0)[0]);
    }

    // ------------------------------------------------------------------ HistoryStore

    /** True when the factory opened the store and its owner has not closed or refused it. Performs no I/O. */
    @Override
    public boolean available() {
        return refusalReason.isEmpty();
    }

    @Override
    public Optional<String> unavailableReason() {
        String reason = refusalReason;
        return reason.isEmpty() ? Optional.empty() : Optional.of(reason);
    }

    /**
     * Records one completed snapshot, assigning its identity here.
     *
     * <p>The whole write - the snapshot row, every ItemType row, every metric row and the retention prune -
     * runs inside one transaction with a single commit. Any failure rolls all of it back, so a reader that
     * arrives afterwards observes the previously committed state exactly as it was: there is no half-written
     * snapshot to see, at any moment, from any caller. A failure is reported by returning empty rather than by
     * throwing, because this call arrives from the publication listener of a scan that has already published
     * its result; a best-effort durable copy must never be able to fail the scan it copies.
     *
     * <p>The identity carried by {@code detail}'s summary is DELIBERATELY ignored: identity is store-owned,
     * and a value derived from the context-local scan id would collide across a restart.
     */
    @Override
    public Optional<HistoryId> record(HistoryDetail detail) {
        Objects.requireNonNull(detail, "detail");
        synchronized (lock) {
            if (!usable()) {
                return Optional.empty();
            }
            HistoryId assigned = HistoryStores.newId();
            try {
                long sequence = database.insertReturningKey(SQL_INSERT_SNAPSHOT,
                        snapshotParameters(assigned, detail));
                if (sequence < 0L) {
                    throw new SQLException("the local history database returned no identity for the snapshot");
                }
                insertItemTypes(sequence, detail);
                prune(detail.summary().repositoryId());
                database.commit();
                lastFailure = "";
                return Optional.of(assigned);
            } catch (SQLException | RuntimeException failure) {
                database.rollbackQuietly();
                lastFailure = reasonOf(failure);
                return Optional.empty();
            }
        }
    }

    @Override
    public List<HistorySummary> list(String repositoryId, int limit) {
        int bounded = effectiveLimit(limit);
        synchronized (lock) {
            if (!usable()) {
                return List.of();
            }
            try {
                return summaries(database.query(SQL_LIST, parameters(key(repositoryId), bounded), bounded));
            } catch (SQLException | RuntimeException failure) {
                lastFailure = reasonOf(failure);
                return List.of();
            }
        }
    }

    /**
     * The page strictly older than {@code before}, newest first.
     *
     * <p>The cursor is resolved to the STORED (captured instant, sequence) pair of the row it names, so paging
     * cannot skip or repeat a row even when several snapshots share one instant - the sequence breaks the tie
     * and the comparison is strict on both keys. When the cursor row itself has been pruned, its capture
     * instant is still authoritative: every row that ranked after it was pruned with it, so comparing strictly
     * on the instant cannot hide a row the reader has not already seen before that cursor.
     */
    @Override
    public List<HistorySummary> listAfter(String repositoryId, HistorySummary before, int limit) {
        Objects.requireNonNull(before, "before");
        int bounded = effectiveLimit(limit);
        synchronized (lock) {
            if (!usable()) {
                return List.of();
            }
            String repository = key(repositoryId);
            try {
                List<Object[]> cursor = database.query(SQL_CURSOR,
                        parameters(before.id().value(), repository), 1);
                if (!cursor.isEmpty()) {
                    Object[] row = cursor.get(0);
                    long sequence = longOf(row[0]);
                    OffsetDateTime captured = timestampOf(row[1]);
                    return summaries(database.query(SQL_LIST_AFTER_KEY,
                            parameters(repository, captured, captured, sequence, bounded), bounded));
                }
                return summaries(database.query(SQL_LIST_AFTER_TIME,
                        parameters(repository, timestampOf(before.capturedAt()), bounded), bounded));
            } catch (SQLException | RuntimeException failure) {
                lastFailure = reasonOf(failure);
                return List.of();
            }
        }
    }

    /**
     * One stored snapshot in full.
     *
     * <p>A stored snapshot whose rows disagree with its own summary is never rendered as a partial one: the
     * detail refuses a row count that contradicts the summary, and this method reports empty and records the
     * reason instead of presenting coverage that the rows do not support. A single missing metric row is
     * honest absence - the metric is reported UNAVAILABLE - because a number is never invented.
     */
    @Override
    public Optional<HistoryDetail> find(HistoryId id) {
        Objects.requireNonNull(id, "id");
        synchronized (lock) {
            if (!usable()) {
                return Optional.empty();
            }
            try {
                List<Object[]> rows = database.query(SQL_FIND, parameters(id.value()), 1);
                if (rows.isEmpty()) {
                    return Optional.empty();
                }
                Object[] row = rows.get(0);
                HistorySummary summary = summary(row);
                long sequence = longOf(row[13]);
                String warning = stringOf(row[14]);
                List<HistoryItemType> itemTypes = itemTypes(sequence, summary.itemTypeCount());
                return Optional.of(new HistoryDetail(summary, itemTypes, warning));
            } catch (SQLException | RuntimeException failure) {
                lastFailure = reasonOf(failure);
                return Optional.empty();
            }
        }
    }

    @Override
    public long count(String repositoryId) {
        synchronized (lock) {
            if (!usable()) {
                return 0L;
            }
            try {
                List<Object[]> rows = database.query(SQL_COUNT_SNAPSHOTS, parameters(key(repositoryId)), 1);
                return rows.isEmpty() ? 0L : longOf(rows.get(0)[0]);
            } catch (SQLException | RuntimeException failure) {
                lastFailure = reasonOf(failure);
                return 0L;
            }
        }
    }

    @Override
    public Optional<HistorySummary> latest(String repositoryId) {
        synchronized (lock) {
            if (!usable()) {
                return Optional.empty();
            }
            try {
                List<HistorySummary> rows = summaries(
                        database.query(SQL_LIST, parameters(key(repositoryId), 1), 1));
                return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
            } catch (SQLException | RuntimeException failure) {
                lastFailure = reasonOf(failure);
                return Optional.empty();
            }
        }
    }

    /**
     * Releases the one connection and refuses every later call.
     *
     * <p>Never throws: a history store that cannot close must not be able to fail the repository shutdown that
     * is releasing its real resources. A clean close is reported as a refusal on purpose - the store genuinely
     * cannot read or write any more - and a failed close keeps the failure as the reason. Calling this any
     * number of times, on a store that never opened, is a no-op.
     */
    @Override
    public void close() {
        synchronized (lock) {
            LocalDatabase open = database;
            database = null;
            if (open == null) {
                return;
            }
            try {
                open.close();
                if (refusalReason.isEmpty()) {
                    refusalReason = "the local history store was closed by its owner: open a new store to read"
                            + " or write local history again";
                }
            } catch (SQLException failure) {
                refuse("the local history database could not be closed cleanly: " + reasonOf(failure));
            }
        }
    }

    /** The schema version this build writes: {@value #SCHEMA_VERSION}. */
    @Override
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }

    @Override
    public Instant openedAt() {
        return openedAt;
    }

    // ------------------------------------------------------------------ diagnostics and evidence

    /** The schema version recorded in the file, when one was read. */
    public Optional<Integer> storedSchemaVersion() {
        return Optional.ofNullable(storedSchemaVersion);
    }

    /** True when the file was refused because its recorded schema version is not this build's. */
    public boolean schemaVersionMismatch() {
        return schemaVersionMismatch;
    }

    /** How many physical connections this store has opened; the one-connection rule's measurable witness. */
    public int connectionsOpened() {
        synchronized (lock) {
            return connectionsOpened;
        }
    }

    /** True when the one connection is currently held and usable. */
    public boolean connectionOpen() {
        synchronized (lock) {
            return database != null && !database.closed();
        }
    }

    /** The local database file's base path; a local application path, never a URL with credentials. */
    public Path databasePath() {
        return databasePath;
    }

    /** The most recent failure reason, value-free, or empty when the last operation succeeded. */
    public Optional<String> lastFailureReason() {
        synchronized (lock) {
            return lastFailure.isEmpty() ? Optional.empty() : Optional.of(lastFailure);
        }
    }

    /** One line for diagnostics: no URL, no credential, no stored value. */
    public String describe() {
        StringBuilder description = new StringBuilder("history[local database ")
                .append(databasePath.getFileName())
                .append(", schema version ").append(SCHEMA_VERSION);
        storedSchemaVersion().ifPresent(version -> description.append(" (stored: ").append(version).append(')'));
        description.append(", connections ").append(connectionsOpened());
        String reason = refusalReason;
        description.append(reason.isEmpty() ? ", available" : ", refused: " + reason);
        description.append(']');
        return description.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    // ------------------------------------------------------------------ writing

    private List<Object> snapshotParameters(HistoryId id, HistoryDetail detail) {
        HistorySummary summary = detail.summary();
        return parameters(
                id.value(),
                summary.repositoryId(),
                summary.repositoryDisplayName(),
                summary.databaseVendor(),
                timestampOf(summary.capturedAt()),
                timestampOf(summary.scanStartedAt()),
                summary.scanDurationMs(),
                summary.anchorDate(),
                summary.scanId(),
                summary.itemTypeCount(),
                summary.partialFailureCount(),
                summary.complete(),
                summary.logicalItemsTotal(),
                detail.warning());
    }

    private void insertItemTypes(long sequence, HistoryDetail detail) throws SQLException {
        int ordinal = 0;
        for (HistoryItemType itemType : detail.itemTypes()) {
            database.update(SQL_INSERT_ITEM_TYPE, parameters(
                    sequence,
                    ordinal,
                    itemType.itemTypeId(),
                    itemType.itemTypeName(),
                    itemType.businessClassification(),
                    itemType.retentionPolicyName(),
                    itemType.status().name(),
                    itemType.durationMs(),
                    itemType.reason()));
            insertMetric(sequence, ordinal, METRIC_LOGICAL_ITEMS, itemType.logicalItems());
            insertMetric(sequence, ordinal, METRIC_CREATED_TODAY, itemType.createdToday());
            insertMetric(sequence, ordinal, METRIC_CREATED_LAST_7_DAYS, itemType.createdLast7Days());
            insertMetric(sequence, ordinal, METRIC_CREATED_LAST_30_DAYS, itemType.createdLast30Days());
            insertMetric(sequence, ordinal, METRIC_CREATED_CURRENT_YEAR, itemType.createdCurrentYear());
            ordinal++;
        }
    }

    private void insertMetric(long sequence, int ordinal, String name, HistoryMetric metric)
            throws SQLException {
        // measured() is empty for every non-AVAILABLE state, and the schema refuses a non-AVAILABLE row that
        // carries a number, so an absent measurement cannot become a stored zero by any path.
        database.update(SQL_INSERT_METRIC, parameters(
                sequence, ordinal, name, metric.state().name(), metric.measured().orElse(null),
                metric.reason()));
    }

    /** Keeps the newest {@code maxSnapshotsPerRepository} rows of ONE repository; touches no other. */
    private void prune(String repositoryId) throws SQLException {
        String repository = key(repositoryId);
        database.update(SQL_PRUNE, parameters(repository, repository, maxSnapshotsPerRepository));
    }

    // ------------------------------------------------------------------ reading

    private List<HistorySummary> summaries(List<Object[]> rows) {
        List<HistorySummary> summaries = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            summaries.add(summary(row));
        }
        return List.copyOf(summaries);
    }

    private HistorySummary summary(Object[] row) {
        return new HistorySummary(
                new HistoryId(stringOf(row[0])),
                stringOf(row[1]),
                stringOf(row[2]),
                stringOf(row[3]),
                instantOf(row[4]),
                instantOf(row[5]),
                longOf(row[6]),
                localDateOf(row[7]),
                longOf(row[8]),
                intOf(row[9]),
                intOf(row[10]),
                booleanOf(row[11]),
                longOf(row[12]));
    }

    private List<HistoryItemType> itemTypes(long sequence, int expectedCount) throws SQLException {
        List<Object[]> rows = database.query(SQL_FIND_ITEM_TYPES, parameters(sequence),
                Math.max(expectedCount, 1));
        Map<Integer, Map<String, HistoryMetric>> metrics = metrics(sequence);
        List<HistoryItemType> itemTypes = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            int ordinal = intOf(row[0]);
            Map<String, HistoryMetric> rowMetrics = metrics.getOrDefault(ordinal, Map.of());
            itemTypes.add(new HistoryItemType(
                    intOf(row[1]),
                    stringOf(row[2]),
                    stringOf(row[3]),
                    stringOf(row[4]),
                    statusOf(stringOf(row[5])),
                    metric(rowMetrics, METRIC_LOGICAL_ITEMS),
                    metric(rowMetrics, METRIC_CREATED_TODAY),
                    metric(rowMetrics, METRIC_CREATED_LAST_7_DAYS),
                    metric(rowMetrics, METRIC_CREATED_LAST_30_DAYS),
                    metric(rowMetrics, METRIC_CREATED_CURRENT_YEAR),
                    longOf(row[6]),
                    stringOf(row[7])));
        }
        return List.copyOf(itemTypes);
    }

    private Map<Integer, Map<String, HistoryMetric>> metrics(long sequence) throws SQLException {
        Map<Integer, Map<String, HistoryMetric>> byOrdinal = new LinkedHashMap<>();
        for (Object[] row : database.query(SQL_FIND_METRICS, parameters(sequence), 0)) {
            byOrdinal.computeIfAbsent(intOf(row[0]), stored -> new LinkedHashMap<>())
                    .put(stringOf(row[1]), metric(row));
        }
        return byOrdinal;
    }

    private static HistoryMetric metric(Map<String, HistoryMetric> rowMetrics, String name) {
        HistoryMetric stored = rowMetrics.get(name);
        if (stored != null) {
            return stored;
        }
        // Absence is reported as absence, never as zero: a snapshot whose metric row is missing is damaged,
        // and a damaged number must not enter a report looking like a measurement.
        return HistoryMetric.unavailable("the stored snapshot carries no value for this metric");
    }

    private static HistoryMetric metric(Object[] row) {
        String state = stringOf(row[2]);
        Long value = row[3] == null ? null : longOf(row[3]);
        String reason = stringOf(row[4]);
        if ("AVAILABLE".equals(state)) {
            return value == null
                    ? HistoryMetric.unavailable("the stored snapshot carries no value for this metric")
                    : HistoryMetric.available(value);
        }
        if ("ERROR".equals(state)) {
            return HistoryMetric.error(reason);
        }
        if ("UNAVAILABLE".equals(state)) {
            return HistoryMetric.unavailable(reason);
        }
        return HistoryMetric.unavailable(reason.isEmpty()
                ? "the stored snapshot carries an unrecognised metric state"
                : reason);
    }

    private static HistoryItemType.Status statusOf(String stored) {
        for (HistoryItemType.Status status : HistoryItemType.Status.values()) {
            if (status.name().equals(stored)) {
                return status;
            }
        }
        // An unrecognised stored status is not silently promoted: the row is reported as an incomplete
        // measurement, which is the conservative reading and can never overstate coverage.
        return HistoryItemType.Status.PARTIAL;
    }

    // ------------------------------------------------------------------ small helpers

    private boolean usable() {
        return refusalReason.isEmpty() && database != null;
    }

    /** Capped page size: a single read can never materialize an unbounded list. */
    private static int effectiveLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive but was " + limit);
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    private static String key(String repositoryId) {
        return repositoryId == null ? "" : repositoryId.trim();
    }

    private static List<Object> parameters(Object... values) {
        // Arrays.asList, not List.of: a bound NULL is a legitimate parameter (an absent anchor date, an
        // absent measurement) and List.of refuses null elements.
        return Arrays.asList(values);
    }

    private static OffsetDateTime timestampOf(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static OffsetDateTime timestampOf(Object stored) {
        Instant instant = instantOf(stored);
        return timestampOf(instant);
    }

    private static Instant instantOf(Object stored) {
        if (stored instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (stored instanceof Instant instant) {
            return instant;
        }
        if (stored instanceof ZonedDateTime zoned) {
            return zoned.toInstant();
        }
        if (stored instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("the local history database returned an unusable timestamp value ("
                + (stored == null ? "null" : stored.getClass().getName()) + ")");
    }

    private static LocalDate localDateOf(Object stored) {
        if (stored == null) {
            return null;
        }
        if (stored instanceof LocalDate date) {
            return date;
        }
        if (stored instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        throw new IllegalStateException("the local history database returned an unusable date value ("
                + stored.getClass().getName() + ")");
    }

    private static String stringOf(Object stored) {
        return stored == null ? "" : String.valueOf(stored);
    }

    private static long longOf(Object stored) {
        if (stored instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("the local history database returned an unusable numeric value ("
                + (stored == null ? "null" : stored.getClass().getName()) + ")");
    }

    private static int intOf(Object stored) {
        return (int) longOf(stored);
    }

    private static boolean booleanOf(Object stored) {
        if (stored instanceof Boolean value) {
            return value;
        }
        if (stored instanceof Number number) {
            return number.longValue() != 0L;
        }
        throw new IllegalStateException("the local history database returned an unusable boolean value ("
                + (stored == null ? "null" : stored.getClass().getName()) + ")");
    }

    /**
     * Refuses this store with a fixed reason, once.
     *
     * <p>Refusing never overwrites an existing reason: the FIRST refusal is the cause, and a later close
     * failure must not hide a schema mismatch behind itself.
     */
    private void refuse(String reason) {
        synchronized (lock) {
            if (refusalReason.isEmpty()) {
                refusalReason = bounded(reason);
                schemaVersionMismatch = refusalReason.startsWith(MISMATCH_PREFIX);
            }
        }
    }

    /** Closes the held connection without touching the refusal reason. */
    private void releaseConnection() {
        LocalDatabase open = database;
        database = null;
        if (open != null) {
            try {
                open.close();
            } catch (SQLException ignored) {
                // The store is already refused; the refusal reason is the one the caller must see.
            }
        }
    }

    /**
     * A bounded, single-line, value-free failure description.
     *
     * <p>A SQL failure is reported as its SQLState and class only: a vendor message routinely contains the
     * JDBC URL, a schema name or a value, and none of that belongs in a diagnostics payload. Every other
     * failure contributes a cleaned, length-bounded excerpt.
     */
    private static String reasonOf(Throwable failure) {
        if (failure instanceof SQLException sql) {
            String state = sql.getSQLState();
            return "JDBC error " + (state == null || state.isBlank() ? "(no SQLState)" : "SQLState " + state)
                    + " from " + sql.getClass().getSimpleName();
        }
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank()
                ? ""
                : ": " + message);
    }

    private static String bounded(String reason) {
        if (reason == null || reason.isBlank()) {
            return "local history is unavailable and no further reason was recorded";
        }
        StringBuilder cleaned = new StringBuilder(Math.min(reason.length(), MAX_FAILURE_REASON_LENGTH));
        boolean lastWasSpace = false;
        for (int index = 0; index < reason.length() && cleaned.length() < MAX_FAILURE_REASON_LENGTH; index++) {
            char c = reason.charAt(index);
            if (c < 0x20 || c == 0x7f || Character.isWhitespace(c)) {
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
        String fixed = cleaned.toString().trim();
        return fixed.isEmpty() ? "local history is unavailable and no further reason was recorded" : fixed;
    }
}
