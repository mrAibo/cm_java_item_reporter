package com.mraibo.cminsight.test;

import com.mraibo.cminsight.core.RepositoryServices;
import com.mraibo.cminsight.core.StatisticsSettings;
import com.mraibo.cminsight.history.HistoryDetail;
import com.mraibo.cminsight.history.HistoryId;
import com.mraibo.cminsight.history.HistoryItemType;
import com.mraibo.cminsight.history.HistoryMetric;
import com.mraibo.cminsight.history.HistorySummary;
import com.mraibo.cminsight.ibm.IbmCmAdapterRegistry;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.security.Authenticator;
import com.mraibo.cminsight.security.LoginThrottle;
import com.mraibo.cminsight.statistics.AnalyticsOperationGate;
import com.mraibo.cminsight.statistics.Freshness;
import com.mraibo.cminsight.statistics.ItemTypeStatistics;
import com.mraibo.cminsight.statistics.ScanStartResult;
import com.mraibo.cminsight.statistics.ScanStatus;
import com.mraibo.cminsight.statistics.StatisticsAvailability;
import com.mraibo.cminsight.statistics.StatisticsDiagnostics;
import com.mraibo.cminsight.statistics.StatisticsRepository;
import com.mraibo.cminsight.statistics.StatisticsSnapshot;
import com.mraibo.cminsight.statistics.TargetedItemTypeDetail;
import com.mraibo.cminsight.statistics.FreshnessThreshold;
import com.mraibo.cminsight.statistics.ItemTypeAggregate;
import com.mraibo.cminsight.statistics.MetricValue;
import com.mraibo.cminsight.statistics.ScanCancellation;
import com.mraibo.cminsight.statistics.ScanWindows;
import com.mraibo.cminsight.statistics.StatisticsEngine;
import com.mraibo.cminsight.statistics.TargetedRefreshResult;
import com.mraibo.cminsight.statistics.TargetedRefreshService;
import com.mraibo.cminsight.report.ReportService;
import com.mraibo.cminsight.web.ActionGuard;
import com.mraibo.cminsight.web.AnalyticsApi;
import com.mraibo.cminsight.web.CmApiRoutes;
import com.mraibo.cminsight.web.HistoryApi;
import com.mraibo.cminsight.web.HistoryApiRoutes;
import com.mraibo.cminsight.web.ReportApiRoutes;
import com.mraibo.cminsight.web.Route;
import com.mraibo.cminsight.web.Router;
import com.mraibo.cminsight.web.StatisticsApiRoutes;
import com.mraibo.cminsight.web.http.RequestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Goal 04 sections 7, 9, 10 and 12 "API/UI/security": the six new routes are authenticated, every
 * state-changing one demands the exact action header with ZERO side effects on refusal, list limits are
 * bounded, identifiers are refused rather than sanitised, and a repository switch discards the old live
 * detail data.
 *
 * <h2>Why every negative case carries a witness</h2>
 *
 * <p>A status code alone cannot show that a refusal happened <em>before</em> the state was read: a handler
 * could do the work and answer 403 afterwards. So each refusal is asserted against a counter that only the
 * real work can move - a counting {@link HistoryApi} for the read/generate paths, a counting
 * {@link AnalyticsApi} for the targeted refresh, and, for a report generation, the reports directory
 * itself, which must still be empty.
 */
public class Goal04ApiSecurityTest {

    private static final String USER = "goal04-ops";

    private static final String PASSWORD = "Goal04-Api-Guard-19";

    private static final Instant CAPTURED_AT = Instant.parse("2024-06-16T08:00:00Z");

    /** Every new path this goal adds, in the shape the router registers them. */
    private static final Set<String> NEW_PATHS = Set.of(
            HistoryApiRoutes.HISTORY_PATH,
            ReportApiRoutes.REPORTS_PATH,
            StatisticsApiRoutes.ITEM_REFRESH_PATH);

    // ------------------------------------------------------------------ authentication

    /** Every new route is installed and requires authentication; nothing new is public. */
    public void everyNewRouteIsInstalledAndAuthenticated() throws Exception {
        try (Harness harness = new Harness()) {
            Set<String> installed = new TreeSet<>();
            for (Route route : harness.router.routes()) {
                if (NEW_PATHS.contains(route.path())) {
                    installed.add(route.path());
                    Assert.assertTrue(route.authRequired(),
                            "route " + route.method() + " " + route.path() + " must require authentication:"
                                    + " the history and report payloads carry repository identity and"
                                    + " aggregate data");
                }
            }
            Assert.assertEquals(new TreeSet<>(NEW_PATHS), installed,
                    "the composed router must serve exactly the documented new paths; a missing one is the"
                            + "'written but never installed' defect and an extra one is an undocumented"
                            + " surface");

            Assert.assertEquals(0, harness.router.routes().stream().filter(route -> !route.authRequired())
                            .count(),
                    "no new route may be public: only /api/health is, and it comes from the mandatory set");

            for (FakeTransport request : harness.everyNewRouteUnauthenticated()) {
                harness.router.handle(new RequestContext(request, "goal04-anon"));
                Assert.assertEquals(401, request.status(),
                        request.method() + " " + request.path() + " without credentials must be 401, not "
                                + request.status() + ": " + request.bodyText());
            }
            harness.assertNoSideEffects("unauthenticated requests");
        }
    }

    // ------------------------------------------------------------------ the action guard

    /**
     * The report route demands the exact action header, and a refusal has ZERO side effects.
     *
     * <p>The witness is twofold: the counting history port (a report from a stored snapshot must not be
     * looked up) and the reports directory, which must stay empty. A refusal that wrote a file would be
     * visible even if every counter were somehow bypassed.
     */
    public void aRefusedReportGenerationHasZeroSideEffects() throws Exception {
        try (Harness harness = new Harness()) {
            List<FakeTransport> refusals = new ArrayList<>();
            refusals.add(harness.authorized(post(ReportApiRoutes.REPORTS_PATH,
                    historyRequestParameters())));
            refusals.add(harness.authorized(post(ReportApiRoutes.REPORTS_PATH,
                    historyRequestParameters()).withHeader(ActionGuard.ACTION_HEADER, "repository-select")));
            refusals.add(harness.authorized(post(ReportApiRoutes.REPORTS_PATH,
                    historyRequestParameters())
                    .withHeader("X-CM-Insight-Action-Type", ActionGuard.REPORT_GENERATE_ACTION)));
            refusals.add(harness.authorized(post(ReportApiRoutes.REPORTS_PATH,
                    historyRequestParameters())
                    .withHeader(ActionGuard.ACTION_HEADER, "Report-Generate")));
            refusals.add(harness.authorized(post(ReportApiRoutes.REPORTS_PATH,
                    historyRequestParameters())
                    .withHeader(ActionGuard.ACTION_HEADER, ActionGuard.REPORT_GENERATE_ACTION)
                    .withHeader("Content-Type", "application/x-www-form-urlencoded")));

            for (FakeTransport request : refusals) {
                harness.router.handle(new RequestContext(request, "goal04-report-guard"));
                Assert.assertEquals(403, request.status(),
                        "a report request without the exact action header must be refused with 403, not "
                                + request.status() + ". Body: " + request.bodyText());
                Assert.assertTrue(request.bodyText().contains("action_forbidden"),
                        "and carry the documented code: " + request.bodyText());
            }
            harness.assertNoSideEffects("refused report generations");
            Assert.assertTrue(harness.reportFiles().isEmpty(),
                    "a refused export must leave NO artifact, but the reports directory holds "
                            + harness.reportFiles());

            // The exact header is accepted, so the refusals above are the guard and not a dead route.
            FakeTransport accepted = harness.authorized(post(ReportApiRoutes.REPORTS_PATH,
                    historyRequestParameters())
                    .withHeader(ActionGuard.ACTION_HEADER, ActionGuard.REPORT_GENERATE_ACTION));
            harness.router.handle(new RequestContext(accepted, "goal04-report-accepted"));
            Assert.assertEquals(201, accepted.status(),
                    "the exact action header must reach the handler and produce a report, but the route"
                            + " answered " + accepted.status() + ": " + accepted.bodyText());
            Assert.assertEquals(1, harness.reportFiles().size(),
                    "and exactly one report must exist afterwards: " + harness.reportFiles());
        }
    }

    /** The targeted refresh route demands the exact action header, and a refusal starts no measurement. */
    public void aRefusedTargetedRefreshHasZeroSideEffects() throws Exception {
        try (Harness harness = new Harness()) {
            String path = StatisticsApiRoutes.ITEM_REFRESH_PATH + "/4711/"
                    + StatisticsApiRoutes.ITEM_REFRESH_SEGMENT;

            List<FakeTransport> refusals = new ArrayList<>();
            refusals.add(harness.authorized(FakeTransport.post(path)));
            refusals.add(harness.authorized(FakeTransport.post(path)
                    .withHeader(ActionGuard.ACTION_HEADER, ActionGuard.STATISTICS_REFRESH_ACTION)));
            refusals.add(harness.authorized(FakeTransport.post(path)
                    .withHeader(ActionGuard.ACTION_HEADER, "statistics-item-refresh-extra")));
            refusals.add(harness.authorized(FakeTransport.post(path)
                    .withHeader(ActionGuard.ACTION_HEADER, ActionGuard.STATISTICS_ITEM_REFRESH_ACTION)
                    .withHeader("Content-Type", "text/plain")));

            for (FakeTransport request : refusals) {
                harness.router.handle(new RequestContext(request, "goal04-item-guard"));
                Assert.assertEquals(403, request.status(),
                        "a targeted refresh without the exact action header must be refused with 403, not "
                                + request.status() + ": " + request.bodyText());
            }
            Assert.assertEquals(0, harness.statistics.refreshItemTypeCalls(),
                    "a refused targeted refresh must run NO measurement: refreshItem was called "
                            + harness.statistics.refreshItemTypeCalls() + " time(s)");

            FakeTransport accepted = harness.authorized(FakeTransport.post(path)
                    .withHeader(ActionGuard.ACTION_HEADER, ActionGuard.STATISTICS_ITEM_REFRESH_ACTION));
            harness.router.handle(new RequestContext(accepted, "goal04-item-accepted"));
            Assert.assertFalse(accepted.status() == 403,
                    "the exact header must not be refused: " + accepted.bodyText());
            Assert.assertEquals(1, harness.statistics.refreshItemTypeCalls(),
                    "and the exact header is what reaches the port; status was " + accepted.status());
            Assert.assertEquals(4711, harness.statistics.lastRefreshedItemTypeId(),
                    "with the ItemType id from the path, validated as a typed identifier first");
        }
    }

    // ------------------------------------------------------------------ bounded lists

    /** A list request can never become an unbounded read, whatever the caller sends. */
    public void listLimitsAreBounded() throws Exception {
        try (Harness harness = new Harness()) {
            Assert.assertTrue(HistoryApiRoutes.MAX_LIMIT > 0
                            && HistoryApiRoutes.MAX_LIMIT <= 1000,
                    "the documented history list bound must be a real bound, but it is "
                            + HistoryApiRoutes.MAX_LIMIT);
            Assert.assertTrue(ReportApiRoutes.MAX_LIMIT > 0
                            && ReportApiRoutes.MAX_LIMIT <= 1000,
                    "and so must the report list bound, but it is " + ReportApiRoutes.MAX_LIMIT);
            Assert.assertTrue(HistoryApiRoutes.DEFAULT_LIMIT > 0
                            && HistoryApiRoutes.DEFAULT_LIMIT <= HistoryApiRoutes.MAX_LIMIT,
                    "and the default must sit inside the bound");

            List<String> hostile = List.of("100000", "2147483647", "-1", "0", "abc", "", "1e9", " ",
                    "999999999999999999999");
            for (String value : hostile) {
                harness.history.receivedLimits.clear();
                FakeTransport request = harness.authorized(get(HistoryApiRoutes.HISTORY_PATH,
                        Map.of(HistoryApiRoutes.LIMIT_PARAMETER, value)));
                harness.router.handle(new RequestContext(request, "goal04-limit"));

                if (request.status() == 200) {
                    Assert.assertFalse(harness.history.receivedLimits.isEmpty(),
                            "a 200 list response must have asked the port for rows");
                    // One look-ahead row above the page size is the documented way to answer "is there a
                    // next page"; anything more is the unbounded read this rule exists to prevent. The
                    // request itself is clamped first, which is what makes the look-ahead bounded: an
                    // unclamped 100000 would reach the port as 100001 and fail here.
                    int portCeiling = HistoryApiRoutes.MAX_LIMIT + 1;
                    for (int limit : harness.history.receivedLimits) {
                        Assert.assertTrue(limit >= 1 && limit <= portCeiling,
                                "the limit '" + value + "' reached the port as " + limit + ", which is"
                                        + " outside 1.." + portCeiling + " (the page bound plus its one"
                                        + " look-ahead row): an unbounded history read is what the bound"
                                        + " exists to prevent");
                    }
                    // The effective page size is what the payload declares, and it must be the clamped
                    // one: asking the port for one look-ahead row above it is bounded, while asking for the
                    // caller's own value would be the unbounded read this rule prevents.
                    java.util.regex.Matcher declared = java.util.regex.Pattern
                            .compile("\"limit\"\\s*:\\s*(\\d+)").matcher(request.bodyText());
                    Assert.assertTrue(declared.find(),
                            "a list response must declare the effective bounded limit it used: "
                                    + request.bodyText());
                    int effective = Integer.parseInt(declared.group(1));
                    Assert.assertTrue(effective >= 1 && effective <= HistoryApiRoutes.MAX_LIMIT,
                            "the requested limit '" + value + "' must be CLAMPED into 1.."
                                    + HistoryApiRoutes.MAX_LIMIT + " rather than passed through, but the"
                                    + " response declares " + effective + ": " + request.bodyText());
                } else {
                    Assert.assertEquals(400, request.status(),
                            "limit='" + value + "' must either be clamped inside the bound or be refused as"
                                    + " a bad request, but the route answered " + request.status() + ": "
                                    + request.bodyText());
                }
            }

            // The report list is the same rule over the other list route, so it is asserted the same way
            // rather than assumed to inherit it.
            for (String value : hostile) {
                FakeTransport request = harness.authorized(get(ReportApiRoutes.REPORTS_PATH,
                        Map.of(ReportApiRoutes.LIMIT_PARAMETER, value)));
                harness.router.handle(new RequestContext(request, "goal04-report-limit"));

                if (request.status() == 200) {
                    Match declared = declaredLimit(request.bodyText());
                    Assert.assertTrue(declared.matched(),
                            "a report list response must declare the effective bounded limit it used: "
                                    + request.bodyText());
                    Assert.assertTrue(declared.value() >= 1
                                    && declared.value() <= ReportApiRoutes.MAX_LIMIT,
                            "the requested report limit '" + value + "' must be CLAMPED into 1.."
                                    + ReportApiRoutes.MAX_LIMIT + ", but the response declares "
                                    + declared.value() + ": " + request.bodyText());
                } else {
                    Assert.assertEquals(400, request.status(),
                            "a report limit of '" + value + "' must either be clamped or be refused as a bad"
                                    + " request, but the route answered " + request.status() + ": "
                                    + request.bodyText());
                }
            }
        }
    }

    /** The declared page size in a list payload, and whether the payload declared one at all. */
    private record Match(boolean matched, int value) {
    }

    private static Match declaredLimit(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"limit\"\\s*:\\s*(\\d+)").matcher(body == null ? "" : body);
        return matcher.find() ? new Match(true, Integer.parseInt(matcher.group(1))) : new Match(false, -1);
    }

    // ------------------------------------------------------------------ identifiers are not paths

    /**
     * Every path-shaped history/report identifier is refused before the port is consulted.
     *
     * <p>An encoded separator is the interesting shape: the router's own {@code ..} check sees the encoded
     * form, so the refusal has to come from the typed identifier - which is exactly what the goal requires
     * ("no route accepts a raw path").
     */
    public void pathAndIdTraversalAttemptsAreRefused() throws Exception {
        try (Harness harness = new Harness()) {
            List<String> hostileIds = List.of(
                    "..%2f..%2fetc%2fpasswd", "%2e%2e%2f", "..", "../../etc/passwd", "report-1.csv",
                    "H1", ".", "~", "a%00b", "0".repeat(80));

            for (String id : hostileIds) {
                String path = HistoryApiRoutes.HISTORY_PATH + "/" + id;
                harness.history.findCalls.set(0);
                FakeTransport request = harness.authorized(FakeTransport.get(path));
                harness.router.handle(new RequestContext(request, "goal04-traversal"));

                Assert.assertTrue(request.status() == 400 || request.status() == 404,
                        "the identifier '" + id + "' must be refused as malformed or unknown, but the route"
                                + " answered " + request.status() + ": " + request.bodyText());
                Assert.assertEquals(0, harness.history.findCalls.get(),
                        "and a malformed identifier must never reach the store: the typed identifier has to"
                                + " refuse it first, not a later sanitization step ('" + id + "')");
            }

            // A well-formed but unknown id DOES reach the port and is answered 404, so the refusals above
            // are the identifier validation and not a route that never reads anything.
            harness.history.findCalls.set(0);
            FakeTransport unknown = harness.authorized(FakeTransport.get(HistoryApiRoutes.HISTORY_PATH + "/h9"));
            harness.router.handle(new RequestContext(unknown, "goal04-traversal-ok"));
            Assert.assertEquals(404, unknown.status(),
                    "an unknown but well-formed history id is a 404: " + unknown.bodyText());
            Assert.assertEquals(1, harness.history.findCalls.get(),
                    "and it does reach the port, which is what makes the malformed cases above meaningful");

            // The report download route is the same shape.
            for (String id : List.of("..%2f..%2fetc%2fpasswd", "report-1.csv", "H1", ".")) {
                FakeTransport request = harness.authorized(FakeTransport.get(
                        ReportApiRoutes.REPORTS_PATH + "/" + id + "/" + ReportApiRoutes.DOWNLOAD_SEGMENT));
                harness.router.handle(new RequestContext(request, "goal04-download-traversal"));
                Assert.assertTrue(request.status() == 400 || request.status() == 404,
                        "a download for the identifier '" + id + "' must be refused, but answered "
                                + request.status() + ": " + request.bodyText());
            }
            Assert.assertTrue(harness.reportFiles().isEmpty(),
                    "and no traversal attempt may have produced or exposed a file: " + harness.reportFiles());
        }
    }

    // ------------------------------------------------------------------ repository switch

    /**
     * A repository switch discards the old context's live detail data.
     *
     * <p>The detail cache is per context (Goal 04 section 10): a targeted detail measured against the old
     * repository must never be shown beside the new repository's snapshot.
     */
    public void aRepositorySwitchDiscardsOldLiveDetailData() throws Exception {
        AnalyticsOperationGate gate = new AnalyticsOperationGate();
        StatisticsEngine engine = new StatisticsEngine() {
            @Override
            public LocalDate databaseCurrentDate(ScanCancellation cancellation) {
                return LocalDate.of(2024, 6, 15);
            }

            @Override
            public ItemTypeAggregate aggregate(ItemTypeSummary itemType, ScanWindows windows,
                                               ScanCancellation cancellation) {
                return new ItemTypeAggregate(7L, MetricValue.available(1L), MetricValue.available(2L),
                        MetricValue.available(3L), MetricValue.available(4L));
            }
        };
        TargetedRefreshService oldContextService = new TargetedRefreshService(gate,
                new StatisticsSettings(true, 2, Duration.ofSeconds(5), Duration.ofSeconds(20)), "alpha",
                () -> List.of(new ItemTypeSummary("A", "d", 4711, "RAW_SAP", "SAP", "")), engine,
                FreshnessThreshold.ofSeconds(300));
        try {
            Assert.assertEquals(TargetedRefreshResult.Outcome.REFRESHED, oldContextService.refresh(4711).outcome(),
                    "the old context measured its detail");
            Assert.assertEquals(1, oldContextService.detailCount(), "and published it");

            RepositoryManager manager = new RepositoryManager(profile -> "alpha".equals(profile.id())
                    ? new RepositoryContext(profile, List.of(oldContextService))
                    : new RepositoryContext(profile, List.of()));
            try {
                manager.switchTo(TestSupport.profile("alpha"));
                Assert.assertEquals(1, oldContextService.detailCount(),
                        "the live detail data is present while that repository is active");

                manager.switchTo(TestSupport.profile("beta"));
                Assert.assertEquals(0, oldContextService.detailCount(),
                        "a repository switch must DISCARD the old context's live detail data: otherwise a"
                                + " targeted measurement of one repository would be rendered beside another"
                                + " repository's dashboard");
                Assert.assertTrue(oldContextService.details().isEmpty(), "the cache is empty");
                Assert.assertTrue(oldContextService.detail(4711).isEmpty(),
                        "and the old ItemType's detail is gone");
                Assert.assertFalse(gate.isHeld(),
                        "and the switch left the analytics gate free for the new context");
            } finally {
                manager.close();
            }
        } finally {
            oldContextService.close();
        }
    }

    // ------------------------------------------------------------------ harness

    /** A GET with query parameters. */
    private static FakeTransport get(String path, Map<String, String> query) {
        return new FakeTransport("GET", path, query, new byte[0]);
    }

    /** A POST with query parameters. */
    private static FakeTransport post(String path, Map<String, String> query) {
        return new FakeTransport("POST", path, query, new byte[0]);
    }

    private static Map<String, String> historyRequestParameters() {
        return Map.of(ReportApiRoutes.SOURCE_PARAMETER, ReportApiRoutes.SOURCE_HISTORY,
                ReportApiRoutes.HISTORY_PARAMETER, "h1",
                ReportApiRoutes.FORMAT_PARAMETER, "csv");
    }

    /** The composed API, exactly as the startup path installs it, over counting capabilities. */
    private static final class Harness implements AutoCloseable {

        final Router router = new Router();
        final CountingHistory history = new CountingHistory();
        final CountingAnalytics analytics = new CountingAnalytics();
        final CountingStatistics statistics = new CountingStatistics();
        final Path reportsDir;
        final ReportService reports;
        private final RepositoryManager manager;
        private final Path scratch;

        Harness() throws Exception {
            scratch = TestSupport.newTempDir("goal04-api-");
            reportsDir = scratch.resolve("reports");
            reports = new ReportService(reportsDir);
            manager = new RepositoryManager(requested -> new RepositoryContext(requested, List.of(),
                    new RepositoryServices(null, null, null, statistics)));
            manager.switchTo(TestSupport.profile("alpha"));

            router.bindAuthenticator(new Authenticator(TestSupport.credentials(USER, PASSWORD),
                    new LoginThrottle(5, Duration.ofMinutes(1), 64)));
            new CmApiRoutes(manager, List.of(TestSupport.profile("alpha")),
                    IbmCmAdapterRegistry.discover(), analytics, history, reports).install(router);
        }

        FakeTransport authorized(FakeTransport transport) {
            return transport.withHeader("Authorization", TestSupport.basic(USER, PASSWORD));
        }

        List<FakeTransport> everyNewRouteUnauthenticated() {
            List<FakeTransport> requests = new ArrayList<>();
            requests.add(FakeTransport.get(HistoryApiRoutes.HISTORY_PATH));
            requests.add(FakeTransport.get(ReportApiRoutes.REPORTS_PATH));
            requests.add(post(ReportApiRoutes.REPORTS_PATH, historyRequestParameters()));
            requests.add(FakeTransport.get(ReportApiRoutes.REPORTS_PATH + "/h1/"
                    + ReportApiRoutes.DOWNLOAD_SEGMENT));
            requests.add(FakeTransport.post(StatisticsApiRoutes.ITEM_REFRESH_PATH + "/4711/"
                    + StatisticsApiRoutes.ITEM_REFRESH_SEGMENT));
            return requests;
        }

        List<String> reportFiles() throws Exception {
            if (!Files.isDirectory(reportsDir)) {
                return List.of();
            }
            try (java.util.stream.Stream<Path> entries = Files.list(reportsDir)) {
                return entries.map(path -> path.getFileName().toString()).sorted().toList();
            }
        }

        void assertNoSideEffects(String when) {
            Assert.assertEquals(0, history.listCalls.get(),
                    "the history list must not be read for " + when);
            Assert.assertEquals(0, history.findCalls.get(),
                    "and no stored snapshot may be looked up for " + when);
            Assert.assertEquals(0, analytics.refreshCalls.get(),
                    "and no full scan may be requested for " + when);
            Assert.assertEquals(0, statistics.refreshItemTypeCalls(),
                    "and no targeted refresh may run for " + when);
        }

        @Override
        public void close() throws Exception {
            manager.close();
            TestSupport.deleteRecursively(scratch);
        }
    }

    /** A history port that counts what the HTTP tier asked it to read. */
    private static final class CountingHistory implements HistoryApi {

        final AtomicInteger listCalls = new AtomicInteger();
        final AtomicInteger findCalls = new AtomicInteger();
        final List<Integer> receivedLimits = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public State state() {
            return State.AVAILABLE;
        }

        @Override
        public String reason() {
            return "";
        }

        @Override
        public List<HistorySummary> list(String repositoryId, int limit) {
            listCalls.incrementAndGet();
            receivedLimits.add(limit);
            return List.of(detail().summary());
        }

        @Override
        public List<HistorySummary> listAfter(String repositoryId, HistorySummary before, int limit) {
            listCalls.incrementAndGet();
            receivedLimits.add(limit);
            return List.of(detail().summary());
        }

        @Override
        public Optional<HistoryDetail> find(HistoryId id) {
            findCalls.incrementAndGet();
            return "h1".equals(id.value()) ? Optional.of(detail()) : Optional.empty();
        }

        @Override
        public long count(String repositoryId) {
            return 1L;
        }

        @Override
        public Optional<HistorySummary> latest(String repositoryId) {
            return Optional.of(detail().summary());
        }

        private static HistoryDetail detail() {
            HistoryItemType row = new HistoryItemType(4711, "ItemType-4711", "SAP", "Retention-A",
                    HistoryItemType.Status.OK, HistoryMetric.available(12L), HistoryMetric.available(1L),
                    HistoryMetric.available(2L), HistoryMetric.available(3L), HistoryMetric.available(4L),
                    5L, "");
            HistorySummary summary = new HistorySummary(new HistoryId("h1"), "alpha", "Repository alpha",
                    "DB2", CAPTURED_AT, CAPTURED_AT.minusSeconds(60), 60_000L, LocalDate.of(2024, 6, 16), 1L,
                    1, 0, true, 12L);
            return new HistoryDetail(summary, List.of(row), "");
        }
    }

    /** An analytics port that counts refresh requests instead of performing them. */
    private static final class CountingAnalytics implements AnalyticsApi {

        final AtomicInteger refreshCalls = new AtomicInteger();
        private final AtomicInteger refreshItemCalls = new AtomicInteger();
        private final AtomicInteger lastItemTypeId = new AtomicInteger(-1);

        int refreshItemCalls() {
            return refreshItemCalls.get();
        }

        int lastRefreshedItemTypeId() {
            return lastItemTypeId.get();
        }


        @Override
        public State state() {
            return State.AVAILABLE;
        }

        @Override
        public String reason() {
            return "";
        }

        @Override
        public Optional<Snapshot> snapshot() {
            return Optional.empty();
        }

        @Override
        public Scan scan() {
            return Scan.idle();
        }

        @Override
        public RefreshOutcome refresh() {
            refreshCalls.incrementAndGet();
            return RefreshOutcome.UNAVAILABLE;
        }

        @Override
        public Jdbc jdbc() {
            return new Jdbc(State.AVAILABLE, "", Driver.unknown(), false, Optional.empty(), Scan.idle(),
                    null);
        }
    }

    /** The repository's analytics port, with the targeted refresh counted instead of performed. */
    private static final class CountingStatistics implements StatisticsRepository {

        private final AtomicInteger itemRefreshCalls = new AtomicInteger();
        private final AtomicInteger lastItemTypeId = new AtomicInteger(-1);

        int refreshItemTypeCalls() {
            return itemRefreshCalls.get();
        }

        int lastRefreshedItemTypeId() {
            return lastItemTypeId.get();
        }

        @Override
        public TargetedRefreshResult refreshItemType(int itemTypeId) {
            itemRefreshCalls.incrementAndGet();
            lastItemTypeId.set(itemTypeId);
            ItemTypeSummary itemType = new ItemTypeSummary("ItemType-" + itemTypeId, "description",
                    itemTypeId, "RAW_SAP", "SAP", "");
            ItemTypeStatistics measured = ItemTypeStatistics.measured("alpha", itemType, CAPTURED_AT,
                    CAPTURED_AT, 5L, ItemTypeStatistics.SOURCE_JDBC, new ItemTypeAggregate(7L,
                            MetricValue.available(1L), MetricValue.available(2L), MetricValue.available(3L),
                            MetricValue.available(4L)));
            TargetedItemTypeDetail detail = new TargetedItemTypeDetail("alpha", itemTypeId, CAPTURED_AT,
                    LocalDate.of(2024, 6, 16), 5L, measured,
                    FreshnessThreshold.ofSeconds(300).judge(CAPTURED_AT, CAPTURED_AT));
            return TargetedRefreshResult.refreshed(detail);
        }

        @Override
        public StatisticsAvailability availability() {
            return StatisticsAvailability.ready();
        }

        @Override
        public Optional<StatisticsSnapshot> snapshot() {
            return Optional.empty();
        }

        @Override
        public ScanStatus progress() {
            return ScanStatus.idle("alpha");
        }

        @Override
        public boolean isScanInFlight() {
            return false;
        }

        @Override
        public ScanStartResult requestScan() {
            return ScanStartResult.STARTED;
        }

        @Override
        public boolean cancelScan() {
            return false;
        }

        @Override
        public boolean awaitScanCompletion(Duration timeout) {
            return true;
        }

        @Override
        public StatisticsDiagnostics diagnostics() {
            return StatisticsDiagnostics.withoutPool(availability(), "test double");
        }
    }}




