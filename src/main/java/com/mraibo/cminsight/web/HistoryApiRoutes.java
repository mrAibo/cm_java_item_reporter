package com.mraibo.cminsight.web;

import com.mraibo.cminsight.history.HistoryDetail;
import com.mraibo.cminsight.history.HistoryId;
import com.mraibo.cminsight.history.HistoryItemType;
import com.mraibo.cminsight.history.HistoryMetric;
import com.mraibo.cminsight.history.HistorySummary;
import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.web.http.HttpMethod;
import com.mraibo.cminsight.web.http.HttpStatus;
import com.mraibo.cminsight.web.http.JsonWriter;
import com.mraibo.cminsight.web.http.RequestContext;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The authenticated persistent-history API.
 *
 * <h2>The route table</h2>
 *
 * <pre>
 *   GET  /api/history          the stored snapshots of one repository, newest first, bounded
 *   GET  /api/history/{id}     one stored snapshot in full
 * </pre>
 *
 * <p>Both are registered through the authenticated {@link Router} methods only, so an unauthenticated caller
 * learns nothing - not even whether history is switched on.
 *
 * <h2>{@code {id}} without a path-parameter syntax</h2>
 *
 * <p>{@link Router} has no path parameters, so {@code /api/history} is registered ONCE as a prefix route and
 * the handler parses its own trailing segment ({@link RequestPaths#trailingSegment}). The segment is then
 * parsed through {@link HistoryId#parse} - the store's own opaque, validated identity - rather than through a
 * pattern written here. Nothing path-shaped, SQL-shaped, schema-shaped or connection-shaped can survive that
 * parser, so no route accepts a raw path or a raw repository JDBC value. A malformed token is
 * {@code 400 bad_request}; a well-formed but unknown one is {@code 404}.
 *
 * <h2>Installed, always</h2>
 *
 * <p>The routes are installed by {@link CmApiRoutes#install(Router)} alongside the CM and analytics routes,
 * which {@code Main.serve()} reaches before the socket is opened. History being switched off, or having no
 * local store, is a NORMAL state of this application: it must answer with the documented state rather than
 * with a {@code 404} that reads like a typo in the client's URL. That is why this class is constructed with
 * {@link HistoryApi#of(HistoryApi.State, String)} on every wiring path that has no store.
 *
 * <h2>Bounded, deterministic lists</h2>
 *
 * <p>{@code limit} is clamped into {@code [1, }{@value #MAX_LIMIT}{@code ]} - never passed through - and the
 * order is the store's own newest-first order, which its contract fixes for the same query. Paging uses the
 * last displayed row as an opaque cursor, so two calls cannot skip or repeat a row. A non-numeric limit is a
 * {@code 400} rather than a silent default, because a client that asked for something invalid should not be
 * told it got it.
 *
 * <h2>Reads never start work</h2>
 *
 * <p>Every response is built from already-stored local data: no method of this class can query IBM CM, open
 * a repository connection or start a scan, so a page that lists history cannot cause database work.
 */
public final class HistoryApiRoutes {

    /** Path of the history collection, and the prefix of the single-snapshot form. */
    public static final String HISTORY_PATH = "/api/history";

    /** Query parameter selecting the repository whose history is listed. Optional. */
    public static final String REPOSITORY_PARAMETER = "repository";

    /** Query parameter bounding the page size. Optional. */
    public static final String LIMIT_PARAMETER = "limit";

    /** Query parameter carrying the opaque newest-row cursor for the next page. Optional. */
    public static final String BEFORE_PARAMETER = "before";

    /** The page size used when the client does not ask for one. */
    public static final int DEFAULT_LIMIT = 25;

    /**
     * The hard maximum page size. A client asking for more receives this many rows, never more: an
     * unbounded history collection is exactly what this bound exists to prevent.
     */
    public static final int MAX_LIMIT = 100;

    /**
     * 503 for "the capability you asked for is not here", kept local rather than added to {@link HttpStatus}:
     * it is this route family's documented refusal for a history request when history cannot serve one.
     */
    private static final int HISTORY_UNAVAILABLE = 503;

    /** 409, the same conflict family every route uses for a state that forbids the request. */
    private static final int CONFLICT = 409;

    /**
     * Fixed text for a read that threw. The failure's own message is never used: it comes from a storage
     * layer and may name a file or a path, and a response body is not the place to find out whether that text
     * is safe. The operator gets a state and a next step instead.
     */
    private static final String READ_FAILED =
            "The local history store could not be read; see the server log and GET /api/history";

    private final RepositoryManager repositories;
    private final HistoryApi history;

    /**
     * @param repositories the manager that owns the single active repository, consulted only to default the
     *                     repository id of a listing to the active one
     * @param history      the history capability; {@code null} degrades to the documented unavailable state,
     *                     so a wiring mistake cannot produce a missing route or a {@code NullPointerException}
     *                     in a request thread
     */
    public HistoryApiRoutes(RepositoryManager repositories, HistoryApi history) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.history = history == null
                ? HistoryApi.unavailable("No history capability is wired into this runtime")
                : history;
    }

    /** Registers the two routes. Both are authenticated. */
    public void install(Router router) {
        Objects.requireNonNull(router, "router");
        router.add(HttpMethod.GET, HISTORY_PATH, true, true, this::historyRoute);
    }

    // ---------------------------------------------------------------- dispatch

    private void historyRoute(RequestContext ctx) {
        String trailing = RequestPaths.trailingSegment(ctx.path(), HISTORY_PATH);
        if (trailing == null) {
            list(ctx);
            return;
        }
        Optional<HistoryId> id = HistoryId.parse(trailing);
        if (id.isEmpty()) {
            // The segment is caller-supplied text, so the refusal names the rule and not the value.
            ctx.sendError(HttpStatus.BAD_REQUEST, "bad_request",
                    "A history id must be an opaque lowercase alphanumeric token");
            return;
        }
        detail(ctx, id.get());
    }

    // ---------------------------------------------------------------- GET /api/history

    private void list(RequestContext ctx) {
        String requestedRepository = requestedRepository(ctx);
        if (requestedRepository == null) {
            return;
        }
        String activeId = activeRepositoryId();

        Integer limit = limit(ctx);
        if (limit == null) {
            return;
        }
        Cursor cursor = cursor(ctx);
        if (!cursor.valid()) {
            return;
        }

        View view = view();
        Object[] entries = new Object[0];
        long stored = 0L;
        boolean hasMore = false;
        String nextBefore = "";
        if (view.state() == HistoryApi.State.AVAILABLE) {
            Page page = page(requestedRepository, cursor.id(), limit);
            if (page == null) {
                ctx.sendJson(HttpStatus.OK, listJson(
                        new View(HistoryApi.State.UNAVAILABLE, READ_FAILED), requestedRepository,
                        activeId, limit, 0L, new Object[0], false, ""));
                return;
            }
            if (page.unresolvedCursor()) {
                // A well-formed cursor that names nothing is a client error, and answering the head of the
                // list instead would silently repeat rows the client already displayed.
                ctx.sendError(HttpStatus.BAD_REQUEST, "bad_request",
                        "The '" + BEFORE_PARAMETER + "' cursor does not name a stored snapshot");
                return;
            }
            entries = page.entries();
            stored = page.stored();
            hasMore = page.hasMore();
            nextBefore = page.nextBefore();
        }

        ctx.sendJson(HttpStatus.OK, listJson(view, requestedRepository, activeId, limit, stored, entries,
                hasMore, nextBefore));
    }

    /**
     * One bounded page.
     *
     * <p>One extra row is requested and then dropped, which is how {@code hasMore} is known without a second
     * counting query: the alternative - reading {@code count()} and comparing - is a second read whose answer
     * can disagree with the page that was just returned.
     */
    private Page page(String repositoryId, HistoryId before, int limit) {
        try {
            List<HistorySummary> rows;
            if (before == null) {
                rows = history.list(repositoryId, limit + 1);
            } else {
                Optional<HistoryDetail> cursor = history.find(before);
                if (cursor.isEmpty()) {
                    // The cursor is well-formed but names nothing: either the client made it up, or the page
                    // it was on has since been pruned. Both are answered the same way - never by silently
                    // restarting at the head of the list.
                    return Page.unresolvedCursor(safeCount(repositoryId));
                }
                rows = history.listAfter(repositoryId, cursor.get().summary(), limit + 1);
            }
            if (rows == null) {
                return null;
            }
            boolean hasMore = rows.size() > limit;
            int returned = hasMore ? limit : rows.size();
            Object[] entries = new Object[returned];
            for (int i = 0; i < returned; i++) {
                entries[i] = JsonWriter.raw(summaryJson(rows.get(i)));
            }
            String nextBefore = hasMore && returned > 0 ? rows.get(returned - 1).id().value() : "";
            return new Page(entries, safeCount(repositoryId), hasMore, nextBefore, false);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private long safeCount(String repositoryId) {
        try {
            return Math.max(0L, history.count(repositoryId));
        } catch (RuntimeException failure) {
            return 0L;
        }
    }

    // ---------------------------------------------------------------- GET /api/history/{id}

    private void detail(RequestContext ctx, HistoryId id) {
        View view = view();
        if (view.state() != HistoryApi.State.AVAILABLE) {
            ctx.sendJson(HISTORY_UNAVAILABLE, JsonWriter.object(
                    "state", view.state().name(),
                    "available", false,
                    "enabled", view.state() != HistoryApi.State.DISABLED,
                    "reason", view.reason(),
                    "entry", JsonWriter.raw("null")));
            return;
        }
        Optional<HistoryDetail> found;
        try {
            found = history.find(id);
        } catch (RuntimeException failure) {
            ctx.sendJson(HttpStatus.OK, JsonWriter.object(
                    "state", HistoryApi.State.UNAVAILABLE.name(),
                    "available", false,
                    "enabled", true,
                    "reason", READ_FAILED,
                    "entry", JsonWriter.raw("null")));
            return;
        }
        if (found == null) {
            ctx.sendJson(HttpStatus.OK, JsonWriter.object(
                    "state", HistoryApi.State.UNAVAILABLE.name(),
                    "available", false,
                    "enabled", true,
                    "reason", READ_FAILED,
                    "entry", JsonWriter.raw("null")));
            return;
        }
        if (found.isEmpty()) {
            ctx.sendError(HttpStatus.NOT_FOUND, "not_found", "No stored history snapshot has that id");
            return;
        }
        ctx.sendJson(HttpStatus.OK, JsonWriter.object(
                "state", HistoryApi.State.AVAILABLE.name(),
                "available", true,
                "enabled", true,
                "reason", "",
                "entry", JsonWriter.raw(detailJson(found.get()))));
    }

    // ---------------------------------------------------------------- payload shapes

    private static String listJson(View view, String repositoryId, String activeId, int limit, long stored,
                                   Object[] entries, boolean hasMore, String nextBefore) {
        boolean active = repositoryId != null && repositoryId.equals(activeId);
        return JsonWriter.object(
                "state", view.state().name(),
                "available", view.state() == HistoryApi.State.AVAILABLE,
                "enabled", view.state() != HistoryApi.State.DISABLED,
                "reason", DiagnosticText.scrub(view.reason()),
                "repositoryId", repositoryId,
                "repositoryActive", active,
                "limit", limit,
                "maxLimit", MAX_LIMIT,
                "defaultLimit", DEFAULT_LIMIT,
                "storedCount", stored,
                "returned", entries.length,
                "hasMore", hasMore,
                "nextBefore", nextBefore,
                "entries", JsonWriter.raw(JsonWriter.array(entries)));
    }

    private static String summaryJson(HistorySummary summary) {
        return JsonWriter.object(
                "id", summary.id().value(),
                "repositoryId", DiagnosticText.scrub(summary.repositoryId()),
                "repositoryDisplayName", DiagnosticText.scrub(summary.repositoryDisplayName()),
                "displayName", DiagnosticText.scrub(summary.displayName()),
                "databaseVendor", DiagnosticText.scrub(summary.databaseVendor()),
                "capturedAt", instant(summary.capturedAt()),
                "scanStartedAt", instant(summary.scanStartedAt()),
                "scanDurationMs", summary.scanDurationMs(),
                "anchorDate", summary.anchorDate() == null ? null : summary.anchorDate().toString(),
                "scanId", summary.scanId(),
                "itemTypeCount", summary.itemTypeCount(),
                "partialFailureCount", summary.partialFailureCount(),
                "complete", summary.complete(),
                "covered", summary.covered(),
                "logicalItemsTotal", summary.logicalItemsTotal(),
                // The age is reported beside the timestamp, never instead of it: a client that wants to render
                // "3 minutes ago" must not have to trust the server's clock, and one that wants the instant
                // must not have to re-derive it.
                "ageMillis", summary.ageMillisAt(Instant.now()));
    }

    private static String detailJson(HistoryDetail detail) {
        Object[] rows = new Object[detail.itemTypes().size()];
        for (int i = 0; i < detail.itemTypes().size(); i++) {
            rows[i] = JsonWriter.raw(itemTypeJson(detail.itemTypes().get(i)));
        }
        return JsonWriter.object(
                "summary", JsonWriter.raw(summaryJson(detail.summary())),
                "warning", DiagnosticText.scrub(detail.warning()),
                "itemTypes", JsonWriter.raw(JsonWriter.array(rows)));
    }

    private static String itemTypeJson(HistoryItemType row) {
        return JsonWriter.object(
                "itemTypeId", row.itemTypeId(),
                "name", DiagnosticText.scrub(row.itemTypeName()),
                "classification", DiagnosticText.scrub(row.classificationLabel()),
                "retentionPolicy", DiagnosticText.scrub(row.retentionPolicyOrEmpty()),
                "status", DiagnosticText.label(row.status().name()),
                "logicalItems", JsonWriter.raw(metricJson(row.logicalItems())),
                "today", JsonWriter.raw(metricJson(row.createdToday())),
                "last7Days", JsonWriter.raw(metricJson(row.createdLast7Days())),
                "last30Days", JsonWriter.raw(metricJson(row.createdLast30Days())),
                "currentYear", JsonWriter.raw(metricJson(row.createdCurrentYear())),
                // Storage keeps no placeholder for these two, because this project cannot produce them: the
                // API states UNAVAILABLE rather than inventing a field a report could read as a measurement.
                "versions", JsonWriter.raw(unavailableMetricJson()),
                "parts", JsonWriter.raw(unavailableMetricJson()),
                "durationMs", row.durationMs(),
                "reason", DiagnosticText.scrub(row.reason()));
    }

    private static String metricJson(HistoryMetric metric) {
        if (metric == null) {
            return unavailableMetricJson();
        }
        if (metric.state() == HistoryMetric.State.AVAILABLE) {
            return JsonWriter.object("available", true, "state", metric.state().name(), "value",
                    metric.value());
        }
        return JsonWriter.object(
                "available", false,
                "state", metric.state().name(),
                "value", null,
                "reason", DiagnosticText.scrub(metric.reason()));
    }

    private static String unavailableMetricJson() {
        return JsonWriter.object("available", false, "state", HistoryMetric.State.UNAVAILABLE.name(), "value",
                null);
    }

    // ---------------------------------------------------------------- request validation

    /**
     * The repository whose history is addressed: the explicit query parameter when present, otherwise the
     * active repository.
     *
     * <p>A query parameter is a convenience for an operator looking at a repository that is not active -
     * history is addressable by repository id without activating it - so it is validated with the same
     * identifier allow-list the profiles use, and it never reaches the store as a path or a SQL value. When
     * neither is available the request is refused with the documented {@code 409}: answering with another
     * repository's history would be worse than refusing.
     */
    private String requestedRepository(RequestContext ctx) {
        String raw = ctx.query(REPOSITORY_PARAMETER);
        if (raw != null) {
            String id = RequestPaths.safeId(raw);
            if (id == null) {
                ctx.sendError(HttpStatus.BAD_REQUEST, "bad_request",
                        "The '" + REPOSITORY_PARAMETER + "' parameter must be a configured repository id");
                return null;
            }
            return id;
        }
        String active = activeRepositoryId();
        if (active == null) {
            ctx.sendError(CONFLICT, "no_active_repository",
                    "No repository is active; pass '" + REPOSITORY_PARAMETER + "' to address stored history");
            return null;
        }
        return active;
    }

    /** The bounded page size, or {@code null} after answering a malformed value with {@code 400}. */
    private static Integer limit(RequestContext ctx) {
        String raw = ctx.query(LIMIT_PARAMETER);
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        if (!raw.trim().matches("[0-9]{1,9}")) {
            ctx.sendError(HttpStatus.BAD_REQUEST, "bad_request",
                    "The '" + LIMIT_PARAMETER + "' parameter must be a whole number");
            return null;
        }
        return RequestPaths.boundedLimit(Integer.parseInt(raw.trim()), DEFAULT_LIMIT, MAX_LIMIT);
    }

    /**
     * The paging cursor, or an invalid marker after the refusal has been written.
     *
     * <p>A malformed cursor is refused with {@code 400} rather than answered with the head of the list:
     * silently restarting paging would repeat rows the client has already displayed, and a handler that
     * simply returned here would produce the router's "no response" failure instead of a clean refusal.
     */
    private static Cursor cursor(RequestContext ctx) {
        String raw = ctx.query(BEFORE_PARAMETER);
        if (raw == null) {
            return new Cursor(true, null);
        }
        Optional<HistoryId> parsed = HistoryId.parse(raw);
        if (parsed.isEmpty()) {
            ctx.sendError(HttpStatus.BAD_REQUEST, "bad_request",
                    "The '" + BEFORE_PARAMETER + "' cursor must be a valid history id");
            return new Cursor(false, null);
        }
        return new Cursor(true, parsed.get());
    }

    private String activeRepositoryId() {
        RepositoryContext context = repositories.status().context();
        return context == null ? null : context.profileUnchecked().id();
    }

    /** One consistent read of the capability, with a read failure mapped to explicit unavailable text. */
    private View view() {
        try {
            HistoryApi.State state = history.state();
            return new View(state == null ? HistoryApi.State.UNAVAILABLE : state, history.reason());
        } catch (RuntimeException failure) {
            return new View(HistoryApi.State.UNAVAILABLE, READ_FAILED);
        }
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    /** One consistent capability read. */
    private record View(HistoryApi.State state, String reason) {

        private View {
            state = state == null ? HistoryApi.State.UNAVAILABLE : state;
            reason = reason == null ? "" : reason;
        }
    }

    /** One bounded page, plus the facts a paging client needs. */
    private record Page(Object[] entries, long stored, boolean hasMore, String nextBefore,
                        boolean unresolvedCursor) {

        /** The page that a well-formed cursor which names nothing produces: no rows, and a refusal. */
        static Page unresolvedCursor(long stored) {
            return new Page(new Object[0], stored, false, "", true);
        }
    }

    /** A validated paging cursor, or the marker that a malformed one was already refused. */
    private record Cursor(boolean valid, HistoryId id) {
    }

    @Override
    public String toString() {
        return "HistoryApiRoutes[repositories=" + repositories.state().name() + ", history=" + history + "]";
    }
}
