package com.mraibo.cminsight.web;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.history.HistoryDetail;
import com.mraibo.cminsight.history.HistoryId;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.report.GeneratedReport;
import com.mraibo.cminsight.report.ReportContext;
import com.mraibo.cminsight.report.ReportException;
import com.mraibo.cminsight.report.ReportFormat;
import com.mraibo.cminsight.report.ReportId;
import com.mraibo.cminsight.report.ReportModel;
import com.mraibo.cminsight.report.ReportService;
import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.statistics.StatisticsRepository;
import com.mraibo.cminsight.statistics.StatisticsSnapshot;
import com.mraibo.cminsight.web.http.HttpMethod;
import com.mraibo.cminsight.web.http.HttpStatus;
import com.mraibo.cminsight.web.http.JsonWriter;
import com.mraibo.cminsight.web.http.RequestContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The authenticated report API: generate from a frozen input, list what exists, and download it.
 *
 * <h2>The route table</h2>
 *
 * <pre>
 *   POST /api/reports                    generate one report; requires {@code report-generate}
 *   GET  /api/reports                    formats, capability and the generated artifacts, newest first
 *   GET  /api/reports/{id}/download      one artifact, as an attachment
 * </pre>
 *
 * <p>All three are registered through the authenticated {@link Router} methods only.
 *
 * <h2>{@code {id}/download} without a path-parameter syntax</h2>
 *
 * <p>{@link Router} has no path parameters, so {@code /api/reports} is registered ONCE as a GET prefix
 * (beside the exact POST registration, which is a different method and therefore not a duplicate) and the
 * handler parses its own trailing segment. Only the exact {@code {id}/download} shape is accepted; a deeper
 * path is refused rather than truncated. The id is then parsed through {@link ReportId#parse} - the report
 * package's own opaque identity - so no route accepts a raw path or a file name.
 *
 * <h2>The action guard</h2>
 *
 * <p>{@code POST /api/reports} writes a file, so it is a state change: it requires
 * {@code X-CM-Insight-Action: report-generate} through the same {@link ActionGuard} the repository selection
 * and the statistics scans use, and the check runs BEFORE the source, the format or any service is read. A
 * request with a missing or wrong header therefore has ZERO side effects - in particular it can never write a
 * file. The header is the control because a cross-site HTML form cannot set one; the three form-sendable
 * content types are refused as well.
 *
 * <h2>Frozen input, and no hidden I/O</h2>
 *
 * <p>A {@code CURRENT} report is built from the published {@link StatisticsSnapshot} and the display values
 * already held on the active {@link RepositoryProfile}: the retention policy name and the classification come
 * from the snapshot's own frozen per-ItemType rows, so generating a report performs no IBM CM read and no
 * repository-database read. A {@code HISTORY} report is built from one stored snapshot and is renderable
 * without activating that repository at all. A value that was not captured renders as unknown rather than
 * being fetched.
 *
 * <h2>Output security</h2>
 *
 * <p>Every artifact is written below {@code reports.dir} by {@link ReportService} using an opaque generated
 * id; no request parameter becomes a path. The download response sets its content type and its
 * {@code Content-Disposition} file name from the service's own validated values, never from the request, and
 * the response carries the mandatory {@code no-store} and {@code nosniff} headers every response in this
 * server already sets.
 */
public final class ReportApiRoutes {

    /** Path of the report collection, the generate action and the download prefix. */
    public static final String REPORTS_PATH = "/api/reports";

    /** The fixed final segment of the download form: {@code /api/reports/{id}/download}. */
    public static final String DOWNLOAD_SEGMENT = "download";

    /** The only value of {@link ActionGuard#ACTION_HEADER} that authorises a report generation. */
    public static final String GENERATE_REPORT_ACTION = ActionGuard.REPORT_GENERATE_ACTION;

    /** Query parameter selecting the report source: {@code current} or {@code history}. */
    public static final String SOURCE_PARAMETER = "source";

    /** Query parameter naming the stored snapshot a {@code history} report is built from. */
    public static final String HISTORY_PARAMETER = "historyId";

    /** Query parameter selecting the output format; required, with no default. */
    public static final String FORMAT_PARAMETER = "format";

    /** Query parameter bounding the size of the artifact list. Optional. */
    public static final String LIMIT_PARAMETER = "limit";

    /** Source token meaning "the current completed full snapshot". */
    public static final String SOURCE_CURRENT = "current";

    /** Source token meaning "one stored persistent history snapshot". */
    public static final String SOURCE_HISTORY = "history";

    /** The page size used when the client does not ask for one. */
    public static final int DEFAULT_LIMIT = 25;

    /**
     * The hard maximum page size. Deliberately below {@link ReportService#MAX_LIST_LIMIT} so this route's own
     * bound is the one that decides, and no client can ask for the whole directory.
     */
    public static final int MAX_LIMIT = 100;

    /**
     * 503 for "the capability you asked for is not here", kept local rather than added to {@link HttpStatus},
     * exactly as the statistics family does.
     */
    private static final int REPORT_UNAVAILABLE = 503;

    /** 409, the same conflict family every route uses for a state that forbids the request. */
    private static final int CONFLICT = 409;

    /** 404 for a well-formed identity that names nothing. */
    private static final int NOT_FOUND = 404;

    /**
     * Fixed text for a generation or read that failed for a reason nobody can act on from the response. The
     * exception's own message is never used: the report layer's text is already value-free, but a response body
     * is not the place to take that on trust.
     */
    private static final String GENERATE_FAILED =
            "The report could not be generated; see the server log and GET /api/reports";

    /** The longest file name this route will put into a response header. */
    private static final int MAX_FILE_NAME = 96;

    private final RepositoryManager repositories;
    private final HistoryApi history;
    private final ReportService reports;

    /**
     * @param repositories the manager that owns the single active repository, consulted only for a
     *                     {@code current} report
     * @param history      the history capability, consulted only for a {@code history} report
     * @param reports      the report service; {@code null} degrades to the documented unavailable state, so a
     *                     wiring mistake cannot produce a missing route or a {@code NullPointerException} in a
     *                     request thread
     */
    public ReportApiRoutes(RepositoryManager repositories, HistoryApi history, ReportService reports) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.history = history == null
                ? HistoryApi.unavailable("No history capability is wired into this runtime")
                : history;
        this.reports = reports;
    }

    /** Registers the three routes. All of them are authenticated. */
    public void install(Router router) {
        Objects.requireNonNull(router, "router");
        router.post(REPORTS_PATH, this::generate);
        router.add(HttpMethod.GET, REPORTS_PATH, true, true, this::reportsRoute);
    }

    // ---------------------------------------------------------------- dispatch

    private void reportsRoute(RequestContext ctx) {
        String trailing = RequestPaths.trailingSegment(ctx.path(), REPORTS_PATH);
        if (trailing == null) {
            list(ctx);
            return;
        }
        String suffix = "/" + DOWNLOAD_SEGMENT;
        if (!trailing.endsWith(suffix)) {
            ctx.sendError(HttpStatus.BAD_REQUEST, "bad_request",
                    "Expected " + REPORTS_PATH + "/{reportId}/" + DOWNLOAD_SEGMENT);
            return;
        }
        String idPart = trailing.substring(0, trailing.length() - suffix.length());
        if (idPart.indexOf('/') >= 0) {
            ctx.sendError(HttpStatus.BAD_REQUEST, "bad_request",
                    "Expected " + REPORTS_PATH + "/{reportId}/" + DOWNLOAD_SEGMENT);
            return;
        }
        Optional<ReportId> id = ReportId.parse(idPart);
        if (id.isEmpty()) {
            ctx.sendError(HttpStatus.BAD_REQUEST, "invalid_report_id", "The report id is not a valid identity");
            return;
        }
        download(ctx, id.get());
    }

    // ---------------------------------------------------------------- POST /api/reports

    private void generate(RequestContext ctx) {
        if (!ActionGuard.authorises(ctx, GENERATE_REPORT_ACTION)) {
            ctx.sendError(HttpStatus.FORBIDDEN, "action_forbidden",
                    ActionGuard.refusalMessage(GENERATE_REPORT_ACTION));
            return;
        }

        Source source = source(ctx);
        if (source == null) {
            return;
        }
        ReportFormat format = format(ctx);
        if (format == null) {
            return;
        }
        if (!format.available()) {
            // An explicitly unavailable format is refused with its own documented reason, and never replaced
            // by another format: a client that asked for XLSX must not receive CSV.
            ctx.sendJson(REPORT_UNAVAILABLE, JsonWriter.object(
                    "generated", false,
                    "available", false,
                    "format", format.token(),
                    "reason", DiagnosticText.scrub(format.detail())));
            return;
        }
        if (reports == null) {
            unavailable(ctx, "No report capability is wired into this runtime");
            return;
        }

        ReportModel model = model(ctx, source);
        if (model == null) {
            return;
        }

        GeneratedReport generated;
        try {
            generated = reports.generate(model, format);
        } catch (ReportException refused) {
            reportRefused(ctx, refused);
            return;
        } catch (RuntimeException failure) {
            // A read of an artifact the service just wrote can fail when the filesystem removed it; the
            // failure's own text is never published.
            ctx.sendError(REPORT_UNAVAILABLE, "report_failed", GENERATE_FAILED);
            return;
        }
        if (generated == null) {
            ctx.sendError(REPORT_UNAVAILABLE, "report_failed", GENERATE_FAILED);
            return;
        }
        ctx.sendJson(HttpStatus.CREATED, JsonWriter.object(
                "generated", true,
                "report", JsonWriter.raw(reportJson(generated))));
    }

    /**
     * The immutable input, or {@code null} after a refusal has been written.
     *
     * <p>A {@code CURRENT} report needs an active repository with a completed scan; a {@code HISTORY} report
     * needs neither, because it renders stored data only. Both paths capture their input before any file is
     * written, and neither holds a CM or JDBC lease while {@link ReportService} renders.
     */
    private ReportModel model(RequestContext ctx, Source source) {
        Instant now = Instant.now();
        if (source.history) {
            String raw = ctx.query(HISTORY_PARAMETER);
            Optional<HistoryId> historyId = HistoryId.parse(raw);
            if (historyId.isEmpty()) {
                ctx.sendError(HttpStatus.BAD_REQUEST, "invalid_history_id",
                        "A history report needs a valid '" + HISTORY_PARAMETER + "' identity");
                return null;
            }
            HistoryApi.State state;
            try {
                state = history.state();
            } catch (RuntimeException failure) {
                state = HistoryApi.State.UNAVAILABLE;
            }
            if (state != HistoryApi.State.AVAILABLE) {
                ctx.sendJson(REPORT_UNAVAILABLE, JsonWriter.object(
                        "generated", false,
                        "available", false,
                        "state", state.name(),
                        "reason", DiagnosticText.scrub(history.reason())));
                return null;
            }
            Optional<HistoryDetail> detail;
            try {
                detail = history.find(historyId.get());
            } catch (RuntimeException failure) {
                ctx.sendJson(REPORT_UNAVAILABLE, JsonWriter.object(
                        "generated", false,
                        "available", false,
                        "state", HistoryApi.State.UNAVAILABLE.name(),
                        "reason", "The local history store could not be read"));
                return null;
            }
            if (detail == null || detail.isEmpty()) {
                ctx.sendError(NOT_FOUND, "unknown_history",
                        "No stored history snapshot has that id");
                return null;
            }
            // Zero IBM CM and zero repository-database reads: the model is the stored snapshot.
            return ReportModel.fromHistory(detail.get(), now);
        }

        RepositoryManager.Status status = repositories.status();
        if (!status.usable()) {
            ctx.sendError(CONFLICT, "no_active_repository",
                    "No repository is active; a current report needs one, or select a stored snapshot");
            return null;
        }
        RepositoryContext context = status.context();
        StatisticsRepository statistics = context == null ? null : context.statistics().orElse(null);
        if (statistics == null || !statistics.available()) {
            ctx.sendJson(REPORT_UNAVAILABLE, JsonWriter.object(
                    "generated", false,
                    "available", false,
                    "state", "UNAVAILABLE",
                    "reason", "The active repository provides no usable analytics capability"));
            return null;
        }
        StatisticsSnapshot snapshot = statistics.snapshot().orElse(null);
        if (snapshot == null) {
            ctx.sendError(CONFLICT, "no_completed_scan",
                    "The active repository has no completed full scan; run a statistics refresh first");
            return null;
        }
        // The display name and the vendor come from the profile (configuration, already held), and the frozen
        // per-ItemType rows come from the snapshot itself - so this reporting path performs no CM read.
        RepositoryProfile profile = context.profileUnchecked();
        return ReportModel.fromSnapshot(snapshot,
                new ReportContext(profile.displayName(), profile.databaseVendor().name(), frozenMetadata(snapshot)),
                now);
    }

    /** The snapshot's own per-ItemType rows, mapped to the captured metadata shape. */
    private static List<ItemTypeSummary> frozenMetadata(StatisticsSnapshot snapshot) {
        List<ItemTypeSummary> metadata = new ArrayList<>(snapshot.perItemType().size());
        for (com.mraibo.cminsight.statistics.ItemTypeStatistics row : snapshot.perItemType()) {
            // The raw IBM classification is not carried by a statistics result; the business classification
            // already present in metadata is used for both, which is honest: nothing is re-derived here.
            metadata.add(new ItemTypeSummary(row.itemTypeName(), "", row.itemTypeId(),
                    row.businessClassification(), row.businessClassification(), row.retentionPolicyName()));
        }
        return metadata;
    }

    // ---------------------------------------------------------------- GET /api/reports

    private void list(RequestContext ctx) {
        Integer limit = limit(ctx);
        if (limit == null) {
            return;
        }
        if (reports == null) {
            ctx.sendJson(HttpStatus.OK, listJson(false, "No report capability is wired into this runtime",
                    limit, List.of()));
            return;
        }
        List<GeneratedReport> listed;
        try {
            listed = reports.list(limit);
        } catch (RuntimeException failure) {
            ctx.sendJson(HttpStatus.OK, listJson(false, "The report directory could not be listed", limit,
                    List.of()));
            return;
        }
        ctx.sendJson(HttpStatus.OK, listJson(true, "", limit, listed == null ? List.of() : listed));
    }

    private static String listJson(boolean available, String reason, int limit, List<GeneratedReport> listed) {
        Object[] entries = new Object[listed.size()];
        for (int i = 0; i < listed.size(); i++) {
            entries[i] = JsonWriter.raw(reportJson(listed.get(i)));
        }
        return JsonWriter.object(
                "available", available,
                "state", available ? "AVAILABLE" : "UNAVAILABLE",
                "reason", DiagnosticText.scrub(reason),
                "directoryPresent", available,
                "formats", JsonWriter.raw(formatsJson()),
                "limit", limit,
                "maxLimit", MAX_LIMIT,
                "defaultLimit", DEFAULT_LIMIT,
                "returned", entries.length,
                "entries", JsonWriter.raw(JsonWriter.array(entries)));
    }

    /**
     * Every format this build declares, with its availability and its fixed description.
     *
     * <p>Deliberately the complete list including an unavailable format: the goal requires XLSX to be VISIBLE
     * as unavailable rather than silently omitted, so the payload enumerates the enum rather than the usable
     * subset.
     */
    private static String formatsJson() {
        ReportFormat[] all = ReportFormat.values();
        Object[] formats = new Object[all.length];
        for (int i = 0; i < all.length; i++) {
            ReportFormat format = all[i];
            formats[i] = JsonWriter.raw(JsonWriter.object(
                    "format", format.token(),
                    "available", format.available(),
                    "contentType", format.contentType(),
                    "extension", format.extension(),
                    "detail", DiagnosticText.scrub(format.detail())));
        }
        return JsonWriter.array(formats);
    }

    private static String reportJson(GeneratedReport report) {
        return JsonWriter.object(
                "id", report.id().value(),
                "format", report.format().token(),
                "fileName", safeFileName(report.fileName()),
                "sizeBytes", report.sizeBytes(),
                "writtenAt", report.writtenAt() == null ? null : report.writtenAt().toString(),
                "ageMillis", report.writtenAt() == null ? null
                        : Math.max(0L, Duration.between(report.writtenAt(), Instant.now()).toMillis()),
                // The download path is built from the validated id and nothing else; a client never has to
                // construct a file name, and no request value can steer it.
                "downloadPath", REPORTS_PATH + "/" + report.id().value() + "/" + DOWNLOAD_SEGMENT
                        + "?" + FORMAT_PARAMETER + "=" + report.format().token());
    }

    // ---------------------------------------------------------------- GET /api/reports/{id}/download

    private void download(RequestContext ctx, ReportId id) {
        if (reports == null) {
            unavailable(ctx, "No report capability is wired into this runtime");
            return;
        }
        ReportFormat format = downloadFormat(ctx, id);
        if (format == null) {
            return;
        }
        Optional<GeneratedReport> found;
        try {
            found = reports.find(id, format);
        } catch (ReportException refused) {
            reportRefused(ctx, refused);
            return;
        } catch (RuntimeException failure) {
            ctx.sendError(REPORT_UNAVAILABLE, "report_failed", GENERATE_FAILED);
            return;
        }
        if (found == null || found.isEmpty()) {
            ctx.sendError(NOT_FOUND, "not_found", "No report has that identity");
            return;
        }
        GeneratedReport report = found.get();
        byte[] body;
        try {
            Path path = report.path();
            if (path == null || !Files.isRegularFile(path)
                    || Files.size(path) > ReportService.MAX_REPORT_BYTES) {
                ctx.sendError(REPORT_UNAVAILABLE, "report_unreadable",
                        "The report artifact is not readable as a completed file");
                return;
            }
            body = Files.readAllBytes(path);
        } catch (IOException | RuntimeException failure) {
            ctx.sendError(REPORT_UNAVAILABLE, "report_unreadable",
                    "The report artifact is not readable as a completed file");
            return;
        }
        // Attachment + the server's own nosniff header: a report may describe operational data, so a browser
        // must never render it in place or sniff a type for it.
        ctx.setResponseHeader("Content-Disposition",
                "attachment; filename=\"" + safeFileName(report.fileName()) + "\"");
        ctx.sendBytes(HttpStatus.OK, report.format().contentType(), body);
    }

    /**
     * The format a download is served as: the requested one when the client names it, otherwise the first
     * declared format that actually resolves for that identity.
     *
     * <p>A requested format that is unavailable or malformed is refused rather than substituted - a client
     * that asked for {@code .xlsx} must never receive HTML - and the fallback probes only the formats this
     * build declares, so it can never invent one.
     */
    private ReportFormat downloadFormat(RequestContext ctx, ReportId id) {
        String raw = ctx.query(FORMAT_PARAMETER);
        if (raw != null) {
            Optional<ReportFormat> requested = ReportFormat.parse(raw);
            if (requested.isEmpty()) {
                ctx.sendError(HttpStatus.BAD_REQUEST, "invalid_format",
                        "The '" + FORMAT_PARAMETER + "' parameter must be one of html, csv or xlsx");
                return null;
            }
            if (!requested.get().available()) {
                ctx.sendJson(REPORT_UNAVAILABLE, JsonWriter.object(
                        "available", false,
                        "format", requested.get().token(),
                        "reason", DiagnosticText.scrub(requested.get().detail())));
                return null;
            }
            return requested.get();
        }
        for (ReportFormat candidate : ReportFormat.availableFormats()) {
            try {
                Optional<GeneratedReport> found = reports.find(id, candidate);
                if (found != null && found.isPresent()) {
                    return candidate;
                }
            } catch (RuntimeException ignored) {
                // A format that cannot be probed simply is not the answer; the loop states the fallback rule.
            }
        }
        ctx.sendError(NOT_FOUND, "not_found", "No report has that identity");
        return null;
    }

    // ---------------------------------------------------------------- request validation

    /** The requested source: {@code current} by default, {@code history} when asked for. */
    private static Source source(RequestContext ctx) {
        String raw = ctx.query(SOURCE_PARAMETER);
        if (raw == null || raw.isBlank()) {
            return Source.CURRENT;
        }
        String token = raw.trim().toLowerCase(Locale.ROOT);
        if (SOURCE_CURRENT.equals(token)) {
            return Source.CURRENT;
        }
        if (SOURCE_HISTORY.equals(token)) {
            return Source.HISTORY;
        }
        ctx.sendError(HttpStatus.BAD_REQUEST, "invalid_source",
                "The '" + SOURCE_PARAMETER + "' parameter must be '" + SOURCE_CURRENT + "' or '"
                        + SOURCE_HISTORY + "'");
        return null;
    }

    /** The required output format, or {@code null} after a refusal. */
    private static ReportFormat format(RequestContext ctx) {
        String raw = ctx.query(FORMAT_PARAMETER);
        Optional<ReportFormat> parsed = ReportFormat.parse(raw);
        if (parsed.isEmpty()) {
            ctx.sendError(HttpStatus.BAD_REQUEST, "invalid_format",
                    "A '" + FORMAT_PARAMETER + "' parameter of html, csv or xlsx is required");
            return null;
        }
        return parsed.get();
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
     * A file name safe to place in a response header: the service's own validated value when it is one, and a
     * synthesised name otherwise.
     *
     * <p>Never derived from a request parameter. The shape excludes quotes, semicolons, control characters and
     * separators, so a hostile or careless producer cannot split the header or make a browser treat the
     * artifact as another kind of file.
     */
    private static String safeFileName(String raw) {
        if (raw != null) {
            String candidate = raw.trim();
            if (!candidate.isEmpty() && candidate.length() <= MAX_FILE_NAME
                    && !candidate.contains("..") && candidate.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                return candidate;
            }
        }
        return "cm-insight-report";
    }

    /** The documented unavailable state for a request that could not be served at all. */
    private static void unavailable(RequestContext ctx, String reason) {
        ctx.sendJson(REPORT_UNAVAILABLE, JsonWriter.object(
                "available", false,
                "state", "UNAVAILABLE",
                "reason", DiagnosticText.scrub(reason)));
    }

    /**
     * Maps the report layer's own refusal to a status, using its stable reason label as the code.
     *
     * <p>The layer's exception carries no cause and no free-form message, and this method still publishes only
     * the reason NAME plus a fixed sentence, so no artifact path or directory can reach a response body.
     */
    private static void reportRefused(RequestContext ctx, ReportException refused) {
        ReportException.Reason reason = refused.reason();
        String code = reason == null ? "report_failed" : reason.name().toLowerCase(Locale.ROOT);
        if (reason == ReportException.Reason.FORMAT_UNAVAILABLE) {
            ctx.sendError(REPORT_UNAVAILABLE, code, "The requested report format is not available");
            return;
        }
        if (reason == ReportException.Reason.OUTPUT_DIRECTORY_UNUSABLE
                || reason == ReportException.Reason.PATH_OUTSIDE_OUTPUT_DIRECTORY
                || reason == ReportException.Reason.WRITE_FAILED
                || reason == ReportException.Reason.CONTENT_TOO_LARGE) {
            ctx.sendError(REPORT_UNAVAILABLE, code,
                    "The report output directory is not usable; see the server log");
            return;
        }
        if (reason == ReportException.Reason.INVALID_FORMAT
                || reason == ReportException.Reason.INVALID_REPORT_ID
                || reason == ReportException.Reason.INVALID_LIMIT) {
            ctx.sendError(HttpStatus.BAD_REQUEST, code, "The report request is not valid");
            return;
        }
        ctx.sendError(REPORT_UNAVAILABLE, code, GENERATE_FAILED);
    }

    /** The two sources, as a value rather than a boolean pair. */
    private enum Source {

        CURRENT(false),
        HISTORY(true);

        private final boolean history;

        Source(boolean history) {
            this.history = history;
        }
    }

    @Override
    public String toString() {
        return "ReportApiRoutes[repositories=" + repositories.state().name()
                + ", reports=" + (reports == null ? "not wired" : "wired") + "]";
    }
}
