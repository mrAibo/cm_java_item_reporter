package com.mraibo.cminsight.web;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.core.CloseState;
import com.mraibo.cminsight.core.CmPoolDiagnostics;
import com.mraibo.cminsight.core.MetadataCache;
import com.mraibo.cminsight.ibm.IbmCmAdapterRegistry;
import com.mraibo.cminsight.metadata.ItemTypeInfo;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.metadata.MetadataRepository;
import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryException;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.retention.RetentionPolicyInfo;
import com.mraibo.cminsight.retention.RetentionRepository;
import com.mraibo.cminsight.web.http.HttpMethod;
import com.mraibo.cminsight.web.http.HttpStatus;
import com.mraibo.cminsight.web.http.JsonWriter;
import com.mraibo.cminsight.web.http.RequestContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The authenticated CM read API: repository listing, selection and status, ItemType and retention reads,
 * and CM diagnostics.
 *
 * <h2>The frozen route table</h2>
 *
 * <pre>
 *   GET  /api/repositories              profiles plus adapter availability
 *   POST /api/repositories/select       activation; requires the action-guard header
 *   GET  /api/repositories/status       lifecycle, close state, refusal, retained context
 *   GET  /api/itemtypes                 ItemType list      (requires an active repository)
 *   GET  /api/itemtypes/{name}          one ItemType
 *   GET  /api/retention/policies        retention policies (requires an active repository)
 *   GET  /api/retention/policies/{name} one policy, with its assigned ItemTypes
 *   GET  /api/diagnostics/cm            adapter, pool, cache and the last sanitised error
 * </pre>
 *
 * <p>Every route is registered through the authenticated {@link Router} methods only. {@code /api/health}
 * stays the one public path; nothing here can be reached without a principal, and an unauthenticated
 * caller therefore learns nothing about which repositories, ItemTypes or policies exist.
 *
 * <h2>{@code {name}} without a path-parameter syntax</h2>
 *
 * <p>{@link Router} matches exact paths and one prefix level and has no path parameters, so
 * {@code /api/itemtypes} is registered once as a prefix and the handler parses its own trailing segment.
 * That is also where the segment is validated: anything that is not a plain name - traversal (already
 * refused earlier as a malformed request path), an encoded traversal that survived decoding, an embedded
 * {@code %}, a slash or backslash, a control character, an overlong name - is answered with a clean
 * {@code 400 bad_request} and never reaches a read service. A well-formed but unknown name is a
 * {@code 404}, which is the repository's own "no such object" answer rather than a failure.
 *
 * <h2>The repository action guard</h2>
 *
 * <p>Selecting a repository mutates local runtime state, so it is a POST that requires the request header
 * {@code X-CM-Insight-Action: repository-select}. A missing or wrong value is refused with
 * {@code 403 action_forbidden} <em>before</em> any state is read or changed. The header is the guard
 * because a cross-site HTML form cannot set one at all - not even with {@code enctype="text/plain"}; the
 * three body types a form <em>can</em> be told to send are refused as well, so a forged submission is
 * refused twice over. CORS stays disabled: no handler here emits an {@code Access-Control-*} header, and
 * none is ever added to a response.
 *
 * <h2>Error mapping, value-free</h2>
 *
 * <pre>
 *   no active repository              409  no_active_repository
 *   adapter unavailable               503  adapter_unavailable
 *   adapter ambiguous                 503  adapter_ambiguous
 *   unknown repository                404  unknown_repository
 *   pending previous close            409  repository_pending
 *   terminal uncertain previous close 409  repository_uncertain
 *   missing/wrong action header       403  action_forbidden
 *   sanitised IBM read/activation fail 502 cm_unavailable
 * </pre>
 *
 * <p>{@link RepositoryManager.Refusal#CLOSED} - the manager itself is shut down, which the frozen table
 * does not name separately - is reported as {@code 409 no_active_repository}: it owns no repository and no
 * attempt can ever succeed, so a retryable {@code 503} would be the wrong advice.
 *
 * <p>No response body ever carries a vendor message, an exception message, an exception class name, a
 * stack trace, a credential or a repository's raw failure text. A read failure is answered with fixed
 * text, and the diagnostics endpoint publishes only counts, states, versions and the adapter's own
 * already-sanitised one-line error.
 *
 * <h2>Lock discipline</h2>
 *
 * <p>An ordinary read takes the active context from ONE atomic {@link RepositoryManager#status()} snapshot
 * and then calls the read service directly. The manager's switch lock is never held while a read service
 * runs, so a slow CM session cannot delay a repository switch, and a switch cannot stall a page.
 */
public final class CmApiRoutes {

    /**
     * The request header that authorises a local runtime action.
     *
     * <p>Deliberately a custom header: a browser will not send one on a cross-site form submission, and
     * the value is fixed here so the endpoint, the test and the console cannot drift apart.
     */
    public static final String ACTION_HEADER = "X-CM-Insight-Action";

    /** The only value of {@link #ACTION_HEADER} that authorises a repository selection. */
    public static final String SELECT_ACTION = "repository-select";

    /** Path of the repository list. */
    public static final String REPOSITORIES_PATH = "/api/repositories";
    /** Path of the repository selection action. */
    public static final String SELECT_PATH = "/api/repositories/select";
    /** Path of the lifecycle/refusal status. */
    public static final String STATUS_PATH = "/api/repositories/status";
    /** Path of the ItemType collection, and the prefix of the single-ItemType form. */
    public static final String ITEM_TYPES_PATH = "/api/itemtypes";
    /** Path of the retention policy collection, and the prefix of the single-policy form. */
    public static final String RETENTION_POLICIES_PATH = "/api/retention/policies";
    /** Path of the CM diagnostics endpoint. */
    public static final String CM_DIAGNOSTICS_PATH = "/api/diagnostics/cm";

    /** Query parameter carrying the repository id of a selection. */
    public static final String REPOSITORY_PARAMETER = "repository";

    /** Accepted alias of {@link #REPOSITORY_PARAMETER}. */
    private static final String REPOSITORY_PARAMETER_ALIAS = "id";

    /**
     * 502, kept local rather than added to {@link HttpStatus}: this is the only route family that reports
     * an upstream read failure, and the shared status set stays what the whole web layer emits.
     */
    private static final int CM_UNAVAILABLE = 502;

    /** Longest accepted ItemType or retention policy name. */
    private static final int MAX_NAME_LENGTH = 128;

    /** Longest diagnostic text published verbatim; the adapter's contract keeps it far shorter than this. */
    private static final int MAX_DIAGNOSTIC_TEXT = 200;

    /** Repository ids, exactly as {@link RepositoryProfile} validates them. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /**
     * The ItemType/retention name allow-list: an alphanumeric first character, then letters, digits, space,
     * dot, underscore and dash.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,127}");

    private static final String FORM_URLENCODED = "application/x-www-form-urlencoded";
    private static final String FORM_MULTIPART = "multipart/form-data";
    private static final String FORM_PLAIN = "text/plain";

    private final RepositoryManager repositories;
    private final List<RepositoryProfile> profiles;
    private final IbmCmAdapterRegistry adapters;

    /**
     * @param repositories the manager that owns the single active repository
     * @param profiles     the configured repository profiles, listed without an adapter and selectable
     *                     only through the action guard
     * @param adapters     the discovered adapter verdict, reported as availability/version/release only
     */
    public CmApiRoutes(RepositoryManager repositories,
                       List<RepositoryProfile> profiles,
                       IbmCmAdapterRegistry adapters) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        this.adapters = Objects.requireNonNull(adapters, "adapters");
    }

    /**
     * Registers every route of the frozen table on a {@link Router}.
     *
     * <p>All six registrations are authenticated ones; none of them can be reached without a principal.
     * The collection and the {@code {name}} form of ItemTypes and retention policies share one prefix
     * registration each, because a path parameter is not expressible here.
     */
    public void install(Router router) {
        Objects.requireNonNull(router, "router");
        router.get(REPOSITORIES_PATH, this::listRepositories);
        router.post(SELECT_PATH, this::selectRepository);
        router.get(STATUS_PATH, this::repositoryStatus);
        router.add(HttpMethod.GET, ITEM_TYPES_PATH, true, true, this::itemTypeRoute);
        router.add(HttpMethod.GET, RETENTION_POLICIES_PATH, true, true, this::retentionRoute);
        router.get(CM_DIAGNOSTICS_PATH, this::cmDiagnostics);
    }

    // ---------------------------------------------------------------- repository routes

    private void listRepositories(RequestContext ctx) {
        String activeId = activeRepositoryId();
        List<Object> listed = new ArrayList<>(profiles.size());
        for (RepositoryProfile profile : profiles) {
            // id, display name, SSID and vendor only: a profile's credentials are references, and even a
            // reference is not published here. The JDBC URL is omitted entirely because it can embed one.
            listed.add(JsonWriter.raw(JsonWriter.object(
                    "id", profile.id(),
                    "name", profile.displayName(),
                    "ssid", profile.ssid(),
                    "vendor", profile.databaseVendor().name(),
                    "active", profile.id().equals(activeId))));
        }
        ctx.sendJson(HttpStatus.OK, JsonWriter.object(
                "adapter", JsonWriter.raw(adapterJson()),
                "activeRepositoryId", activeId == null ? "" : activeId,
                "count", profiles.size(),
                "repositories", JsonWriter.raw(JsonWriter.array(listed.toArray()))));
    }

    /**
     * Selects and activates one configured repository.
     *
     * <p>The order is load-bearing: the action guard first (403 before any state is touched), then the
     * requested id (400 when it is missing or malformed), then the adapter verdict (503 before anything is
     * allocated), then the profile lookup (404), and only then activation. No refusal can leave local state
     * behind, because none of the steps before {@code switchTo} changes anything.
     */
    private void selectRepository(RequestContext ctx) {
        if (!actionAuthorised(ctx)) {
            ctx.sendError(HttpStatus.FORBIDDEN, "action_forbidden",
                    "This action requires the header " + ACTION_HEADER + ": " + SELECT_ACTION);
            return;
        }

        String requested = ctx.query(REPOSITORY_PARAMETER);
        if (requested == null) {
            requested = ctx.query(REPOSITORY_PARAMETER_ALIAS);
        }
        String id = safeId(requested);
        if (id == null) {
            ctx.sendError(HttpStatus.BAD_REQUEST, "bad_request",
                    "A repository id is required in the '" + REPOSITORY_PARAMETER + "' query parameter");
            return;
        }

        IbmCmAdapterRegistry.Availability availability = adapters.status().availability();
        if (availability != IbmCmAdapterRegistry.Availability.AVAILABLE) {
            adapterRefused(ctx, availability);
            return;
        }

        RepositoryProfile profile = profileWithId(id);
        if (profile == null) {
            // Reached only by an authenticated caller, so "this id is not configured" is an answer rather
            // than a disclosure. The list endpoint reports the ids that are configured.
            ctx.sendError(HttpStatus.NOT_FOUND, "unknown_repository", "No configured repository has that id");
            return;
        }

        try {
            repositories.switchTo(profile);
        } catch (RepositoryException failure) {
            // The manager's own message is deliberately not returned: it describes resources and close
            // failures, and a response body is not the place to find out whether that text is safe.
            refusal(ctx);
            return;
        }

        ctx.sendJson(HttpStatus.OK, JsonWriter.object(
                "repositoryId", profile.id(),
                "state", repositories.status().state().name()));
    }

    private void repositoryStatus(RequestContext ctx) {
        RepositoryManager.Status status = repositories.status();
        RepositoryContext context = status.context();
        RepositoryContext retained = repositories.closingContext().orElse(null);
        ctx.sendJson(HttpStatus.OK, JsonWriter.object(
                "state", status.state().name(),
                "stateDescription", status.state().description(),
                "usable", status.usable(),
                "repositoryId", context == null ? "" : context.profileUnchecked().id(),
                "contextCloseState", context == null ? "" : stateName(context.closeState()),
                "closeFailureCount", context == null ? 0 : context.closeFailures().size(),
                "uncertainCloseReportCount", context == null ? 0 : context.uncertainCloseReports().size(),
                "refusal", refusalName(),
                // Whether a failure was recorded, never its text: that text names resources and carries the
                // close failure's own message, which is not ours to publish.
                "lastFailureRecorded", repositories.lastFailure().isPresent(),
                "retainedContext", retained == null ? "" : retained.profileUnchecked().id(),
                "retainedCloseState", retained == null ? "" : stateName(retained.closeState())));
    }

    // ---------------------------------------------------------------- read routes

    private void itemTypeRoute(RequestContext ctx) {
        String trailing = trailingSegment(ctx.path(), ITEM_TYPES_PATH);
        if (trailing == null) {
            listItemTypes(ctx);
            return;
        }
        String name = safeName(trailing);
        if (name == null) {
            ctx.sendError(HttpStatus.BAD_REQUEST, "bad_request", "Malformed ItemType name");
            return;
        }
        itemType(ctx, name);
    }

    private void listItemTypes(RequestContext ctx) {
        MetadataRepository service = metadataService(ctx);
        if (service == null) {
            return;
        }
        List<ItemTypeSummary> types;
        try {
            types = service.listItemTypes();
        } catch (RuntimeException failure) {
            cmUnavailable(ctx);
            return;
        }
        if (types == null) {
            // A null list is a broken service, not "this repository has no ItemTypes". Rendering it as an
            // empty list is exactly the "looks like an answer" failure the viewer must never make.
            cmUnavailable(ctx);
            return;
        }
        Object[] listed = new Object[types.size()];
        for (int i = 0; i < types.size(); i++) {
            ItemTypeSummary type = types.get(i);
            listed[i] = type == null ? null : JsonWriter.raw(itemTypeSummaryJson(type));
        }
        ctx.sendJson(HttpStatus.OK, JsonWriter.object(
                "count", types.size(),
                "itemTypes", JsonWriter.raw(JsonWriter.array(listed))));
    }

    private void itemType(RequestContext ctx, String name) {
        MetadataRepository service = metadataService(ctx);
        if (service == null) {
            return;
        }
        Optional<ItemTypeInfo> found;
        try {
            found = service.itemType(name);
        } catch (RuntimeException failure) {
            cmUnavailable(ctx);
            return;
        }
        if (found == null) {
            cmUnavailable(ctx);
            return;
        }
        if (found.isEmpty()) {
            ctx.sendError(HttpStatus.NOT_FOUND, "not_found", "No such ItemType");
            return;
        }
        ctx.sendJson(HttpStatus.OK, JsonWriter.object("itemType", JsonWriter.raw(itemTypeJson(found.get()))));
    }

    private void retentionRoute(RequestContext ctx) {
        String trailing = trailingSegment(ctx.path(), RETENTION_POLICIES_PATH);
        if (trailing == null) {
            listRetentionPolicies(ctx);
            return;
        }
        String name = safeName(trailing);
        if (name == null) {
            ctx.sendError(HttpStatus.BAD_REQUEST, "bad_request", "Malformed retention policy name");
            return;
        }
        retentionPolicy(ctx, name);
    }

    private void listRetentionPolicies(RequestContext ctx) {
        RetentionRepository service = retentionService(ctx);
        if (service == null) {
            return;
        }
        List<RetentionPolicyInfo> policies;
        try {
            policies = service.listPolicies();
        } catch (RuntimeException failure) {
            cmUnavailable(ctx);
            return;
        }
        if (policies == null) {
            cmUnavailable(ctx);
            return;
        }
        Object[] listed = new Object[policies.size()];
        for (int i = 0; i < policies.size(); i++) {
            RetentionPolicyInfo policy = policies.get(i);
            listed[i] = policy == null ? null : JsonWriter.raw(policyJson(policy));
        }
        ctx.sendJson(HttpStatus.OK, JsonWriter.object(
                "count", policies.size(),
                "policies", JsonWriter.raw(JsonWriter.array(listed))));
    }

    private void retentionPolicy(RequestContext ctx, String name) {
        RetentionRepository service = retentionService(ctx);
        if (service == null) {
            return;
        }
        Optional<RetentionPolicyInfo> found;
        try {
            // policy(name) already carries the assigned ItemTypes, so one read answers the whole endpoint.
            found = service.policy(name);
        } catch (RuntimeException failure) {
            cmUnavailable(ctx);
            return;
        }
        if (found == null) {
            cmUnavailable(ctx);
            return;
        }
        if (found.isEmpty()) {
            ctx.sendError(HttpStatus.NOT_FOUND, "not_found", "No such retention policy");
            return;
        }
        ctx.sendJson(HttpStatus.OK, JsonWriter.object("policy", JsonWriter.raw(policyJson(found.get()))));
    }

    // ---------------------------------------------------------------- diagnostics

    /**
     * The CM diagnostics endpoint: adapter verdict, pool counters, cache freshness and the last sanitised
     * adapter error.
     *
     * <p>Answers {@code 200} even with no active repository, which is the case it exists for: "why can this
     * repository not be read" has to be answerable exactly when nothing is active, so it reports
     * {@code "present": false} for the pool and the cache instead of refusing.
     */
    private void cmDiagnostics(RequestContext ctx) {
        RepositoryManager.Status status = repositories.status();
        RepositoryContext context = status.context();
        ctx.sendJson(HttpStatus.OK, JsonWriter.object(
                "adapter", JsonWriter.raw(adapterJson()),
                "repository", JsonWriter.raw(JsonWriter.object(
                        "state", status.state().name(),
                        "active", status.usable(),
                        "repositoryId", context == null ? "" : context.profileUnchecked().id(),
                        "refusal", refusalName())),
                "pool", JsonWriter.raw(poolJson(context)),
                "cache", JsonWriter.raw(cacheJson(context))));
    }

    private String adapterJson() {
        IbmCmAdapterRegistry.Status status = adapters.status();
        // Exactly the four value-free fields discovery is allowed to know, plus the boolean a caller
        // branches on. The registry's reason sentence is deliberately not published.
        return JsonWriter.object(
                "availability", status.availability().label(),
                "available", status.available(),
                "providerId", status.providerId(),
                "adapterVersion", status.adapterVersion(),
                "sdkRelease", status.sdkRelease());
    }

    private static String poolJson(RepositoryContext context) {
        CmPoolDiagnostics pool = context == null ? null : context.cmPool().orElse(null);
        if (pool == null) {
            return JsonWriter.object("present", false);
        }
        return JsonWriter.object(
                "present", true,
                "poolName", pool.poolName(),
                "configuredSize", pool.configuredSize(),
                "capacityInUse", pool.capacityInUse(),
                "available", pool.available(),
                "leased", pool.leased(),
                "creating", pool.creating(),
                "retiring", pool.retiring(),
                "quarantined", pool.quarantined(),
                "createAttempts", pool.createAttempts(),
                "created", pool.created(),
                "createFailures", pool.createFailures(),
                "createQuarantineFailures", pool.createQuarantineFailures(),
                "closeAttempts", pool.closeAttempts(),
                "closeSuccesses", pool.closeSuccesses(),
                "closeFailures", pool.closeFailures(),
                "borrowCount", pool.borrowCount(),
                "borrowTimeoutCount", pool.borrowTimeoutCount(),
                "averageBorrowWaitMillis", finiteOrNull(pool.averageBorrowWaitMillis()),
                "maxBorrowWaitMillis", finiteOrNull(pool.maxBorrowWaitMillis()),
                "closeState", stateName(pool.closeState()),
                "oldestSessionAgeMillis", ageMillis(pool.oldestSessionAge()),
                "degraded", pool.degraded(),
                "lastAdapterError", safeText(pool.lastAdapterError().orElse("")));
    }

    private static String cacheJson(RepositoryContext context) {
        MetadataCache cache = cacheOf(context);
        if (cache == null) {
            return JsonWriter.object("present", false);
        }
        return JsonWriter.object(
                "present", true,
                "ttlSeconds", cache.ttl().toSeconds(),
                "caching", cache.caching(),
                "snapshotLoaded", cache.snapshotAt().isPresent(),
                "ageMillis", cache.age().toMillis(),
                "fresh", cache.fresh(),
                // Whether a refresh failed, never the failure text: the cache's text embeds a service
                // message, and the pool already publishes the one error an adapter sanitises for us.
                "loadFailed", cache.lastError().isPresent());
    }

    /**
     * The per-context cache, found through the context's resource list - the accessor
     * {@link RepositoryContext#resources()} documents as diagnostics-only.
     *
     * <p>Read-only and safe after close, and the honest answer when a context has no cache is "no cache",
     * which is why this returns {@code null} rather than a placeholder.
     */
    private static MetadataCache cacheOf(RepositoryContext context) {
        if (context == null) {
            return null;
        }
        for (AutoCloseable resource : context.resources()) {
            if (resource instanceof MetadataCache cache) {
                return cache;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- resolution and refusals

    /**
     * The active context, or {@code null} after answering with {@code 409 no_active_repository}.
     *
     * <p>One {@link RepositoryManager#status()} read publishes the state and the context together, so the
     * pair cannot straddle a switch. From here on the context is used directly: no manager lock is taken
     * while a read service runs.
     */
    private RepositoryContext activeContext(RequestContext ctx) {
        RepositoryManager.Status status = repositories.status();
        if (!status.usable()) {
            ctx.sendError(HttpStatus.CONFLICT, "no_active_repository", "No repository is active");
            return null;
        }
        return status.context();
    }

    /** The ItemType service of the active repository, or {@code null} after answering the request. */
    private MetadataRepository metadataService(RequestContext ctx) {
        RepositoryContext context = activeContext(ctx);
        if (context == null) {
            return null;
        }
        MetadataRepository service = context.metadata().orElse(null);
        if (service == null || !service.available()) {
            ctx.sendError(HttpStatus.SERVICE_UNAVAILABLE, "adapter_unavailable",
                    "This repository provides no ItemType read service");
            return null;
        }
        return service;
    }

    /** The retention service of the active repository, or {@code null} after answering the request. */
    private RetentionRepository retentionService(RequestContext ctx) {
        RepositoryContext context = activeContext(ctx);
        if (context == null) {
            return null;
        }
        RetentionRepository service = context.retention().orElse(null);
        if (service == null || !service.available()) {
            ctx.sendError(HttpStatus.SERVICE_UNAVAILABLE, "adapter_unavailable",
                    "This repository provides no retention read service");
            return null;
        }
        return service;
    }

    /** Maps the manager's recorded refusal to the frozen code; an unrecorded refusal is a read failure. */
    private void refusal(RequestContext ctx) {
        RepositoryManager.Refusal reason = repositories.refusal().orElse(null);
        if (reason == null) {
            cmUnavailable(ctx);
            return;
        }
        switch (reason) {
            case PENDING -> ctx.sendError(HttpStatus.CONFLICT, "repository_pending",
                    "The previous repository is still shutting down; retry once its resource is released");
            case UNCERTAIN -> ctx.sendError(HttpStatus.CONFLICT, "repository_uncertain",
                    "The previous repository reported an unproven shutdown; no new repository may be activated");
            case CLOSED -> ctx.sendError(HttpStatus.CONFLICT, "no_active_repository",
                    "The repository manager is closed and cannot activate another repository");
            case ACTIVATION_FAILED -> cmUnavailable(ctx);
        }
    }

    private static void adapterRefused(RequestContext ctx, IbmCmAdapterRegistry.Availability availability) {
        if (availability == IbmCmAdapterRegistry.Availability.AMBIGUOUS) {
            ctx.sendError(HttpStatus.SERVICE_UNAVAILABLE, "adapter_ambiguous",
                    "More than one CM adapter is installed; an operator must resolve which one is used");
            return;
        }
        ctx.sendError(HttpStatus.SERVICE_UNAVAILABLE, "adapter_unavailable",
                "No usable CM adapter is installed; a repository can be listed but not activated");
    }

    /**
     * Fixed text for every read failure. The adapter sanitises its own messages, but a response body is not
     * the place to take that on trust: nothing derived from the failure is echoed.
     */
    private static void cmUnavailable(RequestContext ctx) {
        ctx.sendError(CM_UNAVAILABLE, "cm_unavailable", "The repository could not be read");
    }

    private String refusalName() {
        return repositories.refusal().map(RepositoryManager.Refusal::name).orElse("");
    }

    private String activeRepositoryId() {
        RepositoryContext context = repositories.status().context();
        return context == null ? null : context.profileUnchecked().id();
    }

    private RepositoryProfile profileWithId(String id) {
        for (RepositoryProfile profile : profiles) {
            if (profile.id().equals(id)) {
                return profile;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- request validation

    /**
     * True only when the request carries the action header with the exact value <em>and</em> does not
     * declare a body type an HTML form can send.
     *
     * <p>The header is the real guard, because a cross-site form cannot set one. The content-type check is
     * defence in depth: the three types a form can be told to use are refused outright, so a submission
     * that cannot set the header is refused even if some future client smuggles the token into a body.
     */
    private static boolean actionAuthorised(RequestContext ctx) {
        String action = ctx.header(ACTION_HEADER);
        if (action == null || !SELECT_ACTION.equals(action.trim())) {
            return false;
        }
        String contentType = ctx.header("Content-Type");
        if (contentType == null) {
            return true;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        int parameters = type.indexOf(';');
        if (parameters >= 0) {
            type = type.substring(0, parameters);
        }
        type = type.trim();
        return !FORM_URLENCODED.equals(type) && !FORM_MULTIPART.equals(type) && !FORM_PLAIN.equals(type);
    }

    /**
     * The trailing path segment after {@code base}, or {@code null} when the request targets {@code base}
     * itself.
     *
     * <p>The router matched this handler through {@code base} as a prefix, so a non-null result always has
     * at least one character; a deeper path (one containing another slash) is rejected by
     * {@link #safeName(String)} rather than being silently truncated to its first segment.
     */
    private static String trailingSegment(String path, String base) {
        if (path == null || !path.startsWith(base + "/")) {
            return null;
        }
        return path.substring(base.length() + 1);
    }

    /**
     * A validated ItemType or retention policy name, or {@code null} when the segment is not one.
     *
     * <p>An allow-list rather than a block-list of attacks: letters, digits, space, dot, underscore and
     * dash, at most {@value #MAX_NAME_LENGTH} characters. That refuses traversal and an encoded traversal
     * that survived decoding, an embedded {@code %} (so a double-decoded name cannot be smuggled in), a
     * slash or backslash (which would make the segment a path), a control character and an overlong name,
     * without needing a rule per attack. A name outside the list is simply not addressable, and the caller
     * is authenticated before any of this runs.
     */
    private static String safeName(String raw) {
        if (raw == null) {
            return null;
        }
        String candidate = raw.trim();
        if (candidate.isEmpty() || candidate.length() > MAX_NAME_LENGTH || candidate.contains("..")
                || candidate.indexOf('/') >= 0 || candidate.indexOf('\\') >= 0) {
            return null;
        }
        return SAFE_NAME.matcher(candidate).matches() ? candidate : null;
    }

    /** A validated repository id, or {@code null} when the value is absent, blank or malformed. */
    private static String safeId(String raw) {
        if (raw == null) {
            return null;
        }
        return SAFE_ID.matcher(raw.trim()).matches() ? raw.trim() : null;
    }

    // ---------------------------------------------------------------- JSON shapes

    private static String itemTypeSummaryJson(ItemTypeSummary type) {
        return JsonWriter.object(
                "name", type.name(),
                "description", type.description(),
                "itemTypeId", type.itemTypeId(),
                "classification", type.classification(),
                "businessClassification", type.businessClassification(),
                "retentionPolicyName", type.retentionPolicyName());
    }

    private static String itemTypeJson(ItemTypeInfo type) {
        return JsonWriter.object(
                "name", type.name(),
                "description", type.description(),
                "itemTypeId", type.itemTypeId(),
                "classification", type.classification(),
                "rawClassification", type.rawClassification(),
                "businessClassification", type.businessClassification(),
                "xdoClassId", type.xdoClassId(),
                "xdoClassName", type.xdoClassName(),
                "defaultRm", type.defaultRm(),
                "collectionCode", type.collectionCode(),
                "versionControl", type.versionControl(),
                "versionControlCode", type.versionControlCode(),
                "versioningType", type.versioningType(),
                "versioningTypeCode", type.versioningTypeCode(),
                "legacyRetentionSummary", type.legacyRetentionSummary(),
                "retentionPolicyName", type.retentionPolicyName());
    }

    private static String policyJson(RetentionPolicyInfo policy) {
        return JsonWriter.object(
                "name", policy.name(),
                "description", policy.description(),
                "policyId", policy.policyId(),
                "retentionType", policy.retentionType(),
                "retentionTypeCode", policy.retentionTypeCode(),
                "retentionEnabled", policy.retentionEnabled(),
                "retentionPeriod", policy.retentionPeriod(),
                "retentionPeriodValue", policy.retentionPeriodValue(),
                "retentionUnit", policy.retentionUnit(),
                "expirationEnabled", policy.expirationEnabled(),
                "expirationPeriod", policy.expirationPeriod(),
                "expirationPeriodValue", policy.expirationPeriodValue(),
                "expirationUnit", policy.expirationUnit(),
                "expirationAction", policy.expirationAction(),
                "expirationActionCode", policy.expirationActionCode(),
                "autoDeleteSchedule", policy.autoDeleteSchedule(),
                "commitCount", policy.commitCount(),
                "maxRows", policy.maxRows(),
                "maxDuration", policy.maxDuration(),
                "forceCheckIn", policy.forceCheckIn(),
                "assignedItemTypes", JsonWriter.raw(JsonWriter.array(policy.assignedItemTypes().toArray())));
    }

    // ---------------------------------------------------------------- value-free helpers

    private static String stateName(CloseState state) {
        return state == null ? "" : state.name();
    }

    /** A finite duration in whole milliseconds, or {@code null} when the pool has none to report. */
    private static Long ageMillis(Optional<Duration> age) {
        return age == null || age.isEmpty() ? null : age.get().toMillis();
    }

    /** JSON has no representation for NaN or infinity, so a non-finite average is reported as null. */
    private static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    /**
     * Control characters dropped and the length capped, so a value from a third-party adapter cannot inject
     * anything into a log reader's terminal. Only already-sanitised adapter text reaches this.
     */
    private static String safeText(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_DIAGNOSTIC_TEXT));
        for (int i = 0; i < value.length() && out.length() < MAX_DIAGNOSTIC_TEXT; i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    @Override
    public String toString() {
        return "CmApiRoutes[repositories=" + profiles.size()
                + ", adapter=" + adapters.status().availability().label() + "]";
    }
}
