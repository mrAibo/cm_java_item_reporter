package com.mraibo.cminsight.test;

import com.mraibo.cminsight.app.RepositoryAnalyticsApi;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.core.JdbcPoolSettings;
import com.mraibo.cminsight.core.RepositoryServices;
import com.mraibo.cminsight.core.StatisticsSettings;
import com.mraibo.cminsight.db.Db2Dialect;
import com.mraibo.cminsight.ibm.IbmCmAdapterRegistry;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.security.Authenticator;
import com.mraibo.cminsight.security.LoginThrottle;
import com.mraibo.cminsight.statistics.StatisticsCapability;
import com.mraibo.cminsight.statistics.StatisticsService;
import com.mraibo.cminsight.web.ActionGuard;
import com.mraibo.cminsight.web.AnalyticsApi;
import com.mraibo.cminsight.web.CmApiRoutes;
import com.mraibo.cminsight.web.Route;
import com.mraibo.cminsight.web.Router;
import com.mraibo.cminsight.web.StatisticsApiRoutes;
import com.mraibo.cminsight.web.http.RequestContext;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Goal 03 section 13 and section 15 "API/security": the analytics routes are installed, authenticated, guarded
 * by the exact action header, deterministic under a concurrent refresh, and incapable of leaking a JDBC URL,
 * a user name, raw SQL or raw driver text.
 *
 * <h2>What each assertion is really about</h2>
 *
 * <ul>
 *   <li><strong>A route that was written but never installed</strong> answers {@code 404}, which reads like a
 *       path typo. This project has already shipped that defect once with the CM routes, so the paths are
 *       asserted against the INSTALLED router, through the same {@link RequestContext} a socket request
 *       takes, and an unauthenticated request must get {@code 401} rather than a body.</li>
 *   <li><strong>The action header is the whole CSRF control.</strong> A wrong or missing value must be
 *       refused BEFORE any state is read, so the assertion is not "403" alone: a counting analytics port
 *       proves {@code refresh()} was never called. A status code a test can produce without the guard would
 *       pass a weaker test.</li>
 *   <li><strong>Leakage is asserted end to end.</strong> The facade is wired over a REAL statistics
 *       capability whose scan fails inside a REAL JDBC path over {@link FakeJdbc}, whose failure text, user
 *       name and URL all carry markers only this suite can produce. The responses must contain none of them
 *       while still reporting the sanitized SQLSTATE - so "nothing leaked" cannot be satisfied by a response
 *       that simply reports nothing.</li>
 * </ul>
 */
public class StatisticsApiSecurityTest {

    private static final String USER = "stats-ops";

    private static final String PASSWORD = "Stats-Api-Guard-31";

    /** The three analytics paths, as the goal documents them. */
    private static final Set<String> ANALYTICS_PATHS = Set.of(
            StatisticsApiRoutes.STATISTICS_PATH,
            StatisticsApiRoutes.REFRESH_PATH,
            StatisticsApiRoutes.JDBC_DIAGNOSTICS_PATH);

    // ------------------------------------------------------------------ installation and authentication

    /** Every analytics route is installed, and every one requires authentication. */
    public void everyAnalyticsRouteIsInstalledAndAuthenticated() throws Exception {
        RepositoryManager manager = activeManager("alpha");
        try {
            Router router = router(manager, AnalyticsApi.unavailable("not wired in this test"),
                    List.of(TestSupport.profile("alpha")));

            Set<String> installed = new TreeSet<>();
            for (Route route : router.routes()) {
                if (ANALYTICS_PATHS.contains(route.path())) {
                    installed.add(route.path());
                    Assert.assertTrue(route.authRequired(),
                            "every analytics route must require authentication, but " + route.path() + " does"
                                    + " not: the payloads carry repository ids, scan state and driver facts");
                }
            }
            Assert.assertEquals(new TreeSet<>(ANALYTICS_PATHS), installed,
                    "the router must serve exactly the three documented analytics paths; a missing path is the"
                            + " 'written but never installed' defect, and an extra one is an undocumented"
                            + " surface");
        } finally {
            manager.close();
        }
    }

    /** An unauthenticated request is refused on every analytics route, including the POST. */
    public void everyAnalyticsRouteRefusesAnUnauthenticatedRequest() throws Exception {
        RepositoryManager manager = activeManager("alpha");
        try {
            Router router = router(manager, AnalyticsApi.unavailable("not wired in this test"),
                    List.of(TestSupport.profile("alpha")));

            for (String path : ANALYTICS_PATHS) {
                FakeTransport transport = path.equals(StatisticsApiRoutes.REFRESH_PATH)
                        ? FakeTransport.post(path)
                        : FakeTransport.get(path);
                router.handle(new RequestContext(transport, "stats-api-anon"));
                Assert.assertEquals(401, transport.status(),
                        path + " without credentials must be 401, not " + transport.status()
                                + ": the analytics payloads are not public. Body: " + transport.bodyText());
            }
        } finally {
            manager.close();
        }
    }

    // ------------------------------------------------------------------ the action-header guard

    /**
     * The refresh route requires the EXACT action header, and a wrong or missing header has ZERO side
     * effects.
     *
     * <p>Zero side effects is the assertion, not the status code: a counting port proves that
     * {@code refresh()} was never invoked, which is what "refused before any state is read" means. The
     * guard's value is that a cross-site HTML form cannot set a header, so each wrong shape is tried.
     */
    public void theRefreshRouteRequiresTheExactActionHeaderWithZeroSideEffects() throws Exception {
        CountingAnalytics analytics = new CountingAnalytics();
        RepositoryManager manager = activeManager("alpha");
        try {
            Router router = router(manager, analytics, List.of(TestSupport.profile("alpha")));

            List<FakeTransport> refusals = new ArrayList<>();
            // No header at all.
            refusals.add(authorized(FakeTransport.post(StatisticsApiRoutes.REFRESH_PATH)));
            // A wrong value.
            refusals.add(authorized(FakeTransport.post(StatisticsApiRoutes.REFRESH_PATH)
                    .withHeader(ActionGuard.ACTION_HEADER, "repository-select")));
            // The right value on the wrong header name.
            refusals.add(authorized(FakeTransport.post(StatisticsApiRoutes.REFRESH_PATH)
                    .withHeader("X-CM-Insight-Action-Type", ActionGuard.STATISTICS_REFRESH_ACTION)));
            // A case variation: the control is an exact value, and header values are compared exactly.
            refusals.add(authorized(FakeTransport.post(StatisticsApiRoutes.REFRESH_PATH)
                    .withHeader(ActionGuard.ACTION_HEADER, "Statistics-Refresh")));
            // A cross-site form content type, which the guard refuses in its own right.
            refusals.add(authorized(FakeTransport.post(StatisticsApiRoutes.REFRESH_PATH)
                    .withHeader(ActionGuard.ACTION_HEADER, ActionGuard.STATISTICS_REFRESH_ACTION)
                    .withHeader("Content-Type", "application/x-www-form-urlencoded")));

            for (FakeTransport transport : refusals) {
                router.handle(new RequestContext(transport, "stats-api-guard"));
                Assert.assertEquals(403, transport.status(),
                        "a request without the exact action header must be refused with 403, not "
                                + transport.status() + ". Body: " + transport.bodyText());
                Assert.assertTrue(transport.bodyText().contains("action_forbidden"),
                        "and the refusal must carry the documented code so a client can act on it: "
                                + transport.bodyText());
            }
            Assert.assertEquals(0, analytics.refreshCalls(),
                    "a refused request must have ZERO side effects: refresh() was called "
                            + analytics.refreshCalls() + " time(s), which means the guard ran after the state"
                            + " was already touched");

            // The exact header is accepted, and only then does the state change.
            FakeTransport accepted = authorized(FakeTransport.post(StatisticsApiRoutes.REFRESH_PATH)
                    .withHeader(ActionGuard.ACTION_HEADER, ActionGuard.STATISTICS_REFRESH_ACTION));
            router.handle(new RequestContext(accepted, "stats-api-accepted"));
            Assert.assertFalse(accepted.status() == 403,
                    "the exact header must be accepted, but the route still refused it: "
                            + accepted.bodyText());
            Assert.assertEquals(1, analytics.refreshCalls(),
                    "and the exact header is what reaches the analytics port; status was " + accepted.status()
                            + " with body " + accepted.bodyText());
        } finally {
            manager.close();
        }
    }

    /** A second concurrent refresh is a deterministic conflict, not duplicate work. */
    public void aSecondConcurrentRefreshReturnsADeterministicConflict() throws Exception {
        CountingAnalytics analytics = new CountingAnalytics();
        analytics.nextOutcome = AnalyticsApi.RefreshOutcome.STARTED;
        RepositoryManager manager = activeManager("alpha");
        try {
            Router router = router(manager, analytics, List.of(TestSupport.profile("alpha")));

            List<String> bodies = new ArrayList<>();
            List<Integer> statuses = new ArrayList<>();
            for (int attempt = 0; attempt < 2; attempt++) {
                FakeTransport transport = authorized(FakeTransport.post(StatisticsApiRoutes.REFRESH_PATH)
                        .withHeader(ActionGuard.ACTION_HEADER, ActionGuard.STATISTICS_REFRESH_ACTION));
                router.handle(new RequestContext(transport, "stats-api-race"));
                statuses.add(transport.status());
                bodies.add(transport.bodyText());
            }
            Assert.assertEquals(2, analytics.refreshCalls(),
                    "both requests reach the single atomic refresh() call, which is what decides the conflict"
                            + " - a pre-check would be a race");
            // Unboxed once into locals on purpose: `assertEquals(409, boxedInteger, ...)` is ambiguous between
            // the int/long and Object overloads, and a call the compiler has to guess at is a call two readers
            // can disagree about. Asserting on primitives removes the trap instead of silencing it.
            int firstStatus = statuses.get(0);
            int secondStatus = statuses.get(1);
            String firstBody = bodies.get(0);
            String secondBody = bodies.get(1);
            Assert.assertEquals(202, firstStatus,
                    "the first refresh starts a scan and answers 202, but answered " + firstStatus + ": "
                            + firstBody);
            Assert.assertEquals(409, secondStatus,
                    "the second refresh must be a deterministic conflict, but answered " + secondStatus + ": "
                            + secondBody);
            Assert.assertTrue(secondBody.contains("scan_in_progress"),
                    "and it must carry the documented conflict code: " + secondBody);
            Assert.assertFalse(secondBody.isBlank(),
                    "a conflict with no body would be indistinguishable from a generic failure");
        } finally {
            manager.close();
        }
    }

    // ------------------------------------------------------------------ leakage, end to end

    /**
     * No JDBC URL, database user, raw SQL or raw driver text appears in any response, while the sanitized
     * failure IS reported.
     *
     * <p>The capability is real: a real {@code StatisticsService} over a real JDBC pool, a real dialect, a
     * real scan, and a {@link FakeJdbc} driver whose failure message carries {@code RAW-VENDOR-FAILURE-TEXT-…},
     * whose user is {@code FAKEJDBCUSER} and whose URL is {@code jdbc:db2://…}. The scan is made to fail at
     * the aggregate, so the error path really runs; the responses must report the SQLSTATE and none of the
     * markers.
     */
    public void noJdbcUrlUserRawSqlOrRawDriverTextAppearsInAnyResponse() throws Exception {
        FakeJdbc.loadVendorDriverClass(FakeJdbc.DB2_DRIVER_CLASS);
        try (FakeJdbc fake = FakeJdbc.register("jdbc:db2:")) {
            fake.tables("ICMUT00001001");
            fake.answersSqlContaining("FROM ICMADMIN.ICMSTCOMPDEFS",
                    FakeResultTable.oneRow(List.of("COMPONENTTYPEID", "SEGMENTID"), 1, 1));
            fake.answersSqlContaining("WHERE 1 = 0", FakeResultTable.empty("PRESENT"));
            fake.answersSqlContaining("FROM SYSIBM.SYSDUMMY1",
                    FakeResultTable.oneRow(List.of("D"), LocalDate.of(2024, 6, 15)));
            fake.failsQueriesContaining("WITH LOGICAL_ITEMS",
                    () -> FakeJdbc.rawFailure("execute select"));

            RepositoryProfile profile = TestSupport.profile("alpha");
            SecretResolver secrets = new SecretResolver(Map.of("JDBC_ALPHA_USER", "leaky-user",
                    "JDBC_ALPHA_PASSWORD", "leaky-password"), null);

            List<AutoCloseable> resources = new ArrayList<>();
            StatisticsService service = StatisticsCapability.activate(profile, secrets, new Db2Dialect(),
                    new JdbcPoolSettings(4, Duration.ofSeconds(5), Duration.ofMinutes(30), 1_000L),
                    StatisticsSettings.defaults(4),
                    () -> List.of(new ItemTypeSummary("ItemType0", "", 4711, "SAP", "SAP", "")),
                    resources::add);
            Assert.assertTrue(service.availability().available(),
                    "the capability must be available for this test to exercise the failure path: "
                            + service.availability().reason());

            RepositoryContext context = new RepositoryContext(profile, resources,
                    new RepositoryServices(null, null, null, service));
            RepositoryManager manager = new RepositoryManager(requested -> context);
            manager.switchTo(profile);
            try {
                RepositoryAnalyticsApi api = new RepositoryAnalyticsApi(manager, true);
                Router router = router(manager, api, List.of(profile));

                FakeTransport refresh = authorized(FakeTransport.post(StatisticsApiRoutes.REFRESH_PATH)
                        .withHeader(ActionGuard.ACTION_HEADER, ActionGuard.STATISTICS_REFRESH_ACTION));
                router.handle(new RequestContext(refresh, "stats-api-leak"));
                Assert.assertTrue(refresh.status() == 202,
                        "the refresh must start the scan, not fail: " + refresh.status() + " "
                                + refresh.bodyText());
                Assert.assertTrue(service.awaitScanCompletion(Duration.ofSeconds(30)),
                        "the scan must finish so the failure really happened");
                Assert.assertTrue(service.snapshot().isPresent(),
                        "a scan that visited its frozen list publishes a snapshot even with a failed ItemType");

                List<String> bodies = new ArrayList<>();
                for (String path : List.of(StatisticsApiRoutes.STATISTICS_PATH,
                        StatisticsApiRoutes.JDBC_DIAGNOSTICS_PATH)) {
                    FakeTransport transport = authorized(FakeTransport.get(path));
                    router.handle(new RequestContext(transport, "stats-api-leak"));
                    Assert.assertEquals(200, transport.status(),
                            path + " must answer 200 with a sanitized payload, but answered "
                                    + transport.status() + ": " + transport.bodyText());
                    bodies.add(transport.bodyText());
                }

                String configuredUrl = profile.jdbcUrl();
                for (String body : bodies) {
                    assertNoSecretLeak(body, configuredUrl);
                }
                String combined = String.join(" ", bodies);
                Assert.assertTrue(combined.contains("08S01"),
                        "the sanitized SQLSTATE must still reach the operator, otherwise 'nothing leaked'"
                                + " would be indistinguishable from 'nothing was reported': " + combined);
                Assert.assertTrue(combined.contains("ItemType0") || combined.contains("4711"),
                        "and the failing ItemType must be named, so the report is actionable: " + combined);
            } finally {
                manager.close();
            }
        }
    }

    /**
     * Asserts that a response body carries none of the sentinel values only this suite can produce.
     *
     * <p>What is forbidden and what is not is taken from the goal rather than from a general instinct about
     * "sensitive": the CONNECTION URL is forbidden, while the vendor URL PREFIX ({@code jdbc:db2:}) and the
     * driver class name are exactly the safe facts section 13 asks the diagnostics route to publish. An
     * assertion that forbade the prefix would fail on a correct implementation and would have to be weakened
     * later - so the distinction is made here, explicitly.
     */
    private static void assertNoSecretLeak(String body, String configuredJdbcUrl) {
        Assert.assertFalse(body.contains(FakeJdbc.RAW_FAILURE_MARKER),
                "no response may reproduce the driver's raw failure text: " + body);
        Assert.assertFalse(body.contains(FakeJdbc.FAKE_USER),
                "no response may carry the database user name: " + body);
        Assert.assertFalse(body.contains(configuredJdbcUrl),
                "no response may carry the configured JDBC URL: " + body);
        Assert.assertFalse(body.contains("db.example"),
                "and no part of its host may appear either: " + body);
        Assert.assertFalse(body.contains("leaky-user") || body.contains("leaky-password"),
                "no response may carry a credential value: " + body);
        Assert.assertFalse(body.toLowerCase(java.util.Locale.ROOT).contains("delete from")
                        || body.toLowerCase(java.util.Locale.ROOT).contains("substr(itemid")
                        || body.toLowerCase(java.util.Locale.ROOT).contains("with logical_items"),
                "no response may carry raw SQL text: " + body);
        Assert.assertFalse(body.contains("ICMADMIN."),
                "no response may carry a schema qualified table name taken from a statement: " + body);
    }

    // ------------------------------------------------------------------ availability isolation

    /**
     * With analytics unavailable, the statistics routes answer an EXPLICIT unavailable state and the
     * ItemType and retention routes keep working.
     *
     * <p>The goal's rule is that an unusable analytics half must not deactivate the repository: a missing
     * driver, a missing credential or an unreachable database may not turn the metadata viewer into a 404 or
     * a 500. The statistics route must say why, and the read routes must still be dispatched.
     */
    public void unavailableAnalyticsDoesNotBreakTheItemTypeAndRetentionRoutes() throws Exception {
        RepositoryManager manager = activeManager("alpha");
        try {
            Router router = router(manager, AnalyticsApi.unavailable("the JDBC driver is not installed"),
                    List.of(TestSupport.profile("alpha")));

            FakeTransport statistics = authorized(FakeTransport.get(StatisticsApiRoutes.STATISTICS_PATH));
            router.handle(new RequestContext(statistics, "stats-api-unavailable"));
            Assert.assertEquals(200, statistics.status(),
                    "the statistics route must always be served: an unavailable capability is a state, not a"
                            + " missing path. Body: " + statistics.bodyText());
            Assert.assertTrue(statistics.bodyText().contains("\"available\":false"),
                    "and it must report the explicit unavailable state: " + statistics.bodyText());
            Assert.assertTrue(statistics.bodyText().contains("JDBC driver"),
                    "with the reason an operator can act on: " + statistics.bodyText());

            FakeTransport diagnostics = authorized(FakeTransport.get(StatisticsApiRoutes.JDBC_DIAGNOSTICS_PATH));
            router.handle(new RequestContext(diagnostics, "stats-api-unavailable"));
            Assert.assertEquals(200, diagnostics.status(),
                    "the JDBC diagnostics route must be served too: " + diagnostics.bodyText());
            assertNoSecretLeak(diagnostics.bodyText(), TestSupport.profile("alpha").jdbcUrl());

            for (String path : List.of(CmApiRoutes.ITEM_TYPES_PATH, CmApiRoutes.RETENTION_POLICIES_PATH)) {
                FakeTransport transport = authorized(FakeTransport.get(path));
                router.handle(new RequestContext(transport, "stats-api-unavailable"));
                Assert.assertFalse(transport.status() == 404,
                        path + " answered 404 while analytics was unavailable, which would mean the analytics"
                                + " capability deactivated a metadata route: " + transport.bodyText());
                Assert.assertTrue(transport.status() == 200 || transport.status() == 409
                                || transport.status() == 503,
                        path + " must answer 200 or a documented refusal (409/503), but answered "
                                + transport.status() + ": " + transport.bodyText());
            }
        } finally {
            manager.close();
        }
    }

    // ------------------------------------------------------------------ harness

    /** An analytics port that counts the refresh calls it receives, so "no side effect" is measurable. */
    private static final class CountingAnalytics implements AnalyticsApi {

        private final AtomicInteger refreshCalls = new AtomicInteger();
        private volatile RefreshOutcome nextOutcome = RefreshOutcome.STARTED;

        int refreshCalls() {
            return refreshCalls.get();
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
            int call = refreshCalls.incrementAndGet();
            return call == 1 ? nextOutcome : RefreshOutcome.ALREADY_RUNNING;
        }

        @Override
        public Jdbc jdbc() {
            return new Jdbc(State.AVAILABLE, "", Driver.unknown(), false, Optional.empty(), Scan.idle(), null);
        }
    }

    private static Authenticator authenticator() {
        return new Authenticator(TestSupport.credentials(USER, PASSWORD),
                new LoginThrottle(5, Duration.ofMinutes(1), 64));
    }

    private static FakeTransport authorized(FakeTransport transport) {
        return transport.withHeader("Authorization", TestSupport.basic(USER, PASSWORD));
    }

    /**
     * A manager with one ACTIVE repository and no analytics service.
     *
     * <p>The refresh route reads the repository state before it consults availability, so a test that wants
     * to prove the ACTION-HANDLER or the conflict behaviour needs a genuinely active repository: with none,
     * every request would stop at {@code 409 no_active_repository} and the analytics port would never be
     * reached at all - which would make both assertions vacuous. That is measured, not assumed: the first
     * draft of this suite reported {@code refreshCalls() == 0} for exactly that reason.
     */
    private static RepositoryManager activeManager(String repositoryId) throws Exception {
        RepositoryProfile profile = TestSupport.profile(repositoryId);
        RepositoryManager manager = new RepositoryManager(requested -> new RepositoryContext(requested));
        manager.switchTo(profile);
        return manager;
    }

    /** A router with the CM and analytics routes installed, exactly as the startup path installs them. */
    private static Router router(RepositoryManager repositories, AnalyticsApi analytics,
                                 List<RepositoryProfile> profiles) {
        Router router = new Router();
        router.bindAuthenticator(authenticator());
        CmApiRoutes routes = new CmApiRoutes(repositories, profiles, IbmCmAdapterRegistry.discover(), analytics);
        routes.install(router);
        return router;
    }
}
