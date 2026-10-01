package com.mraibo.cminsight.web;

import com.mraibo.cminsight.web.http.RequestContext;

import java.util.Locale;

/**
 * The one implementation of the CSRF-resistant action-header guard.
 *
 * <h2>The threat</h2>
 *
 * <p>An endpoint that changes local process state - selecting a repository, or requesting a statistics
 * scan - is reachable by any page an authenticated operator visits: a cross-site HTML form submission
 * needs no CORS permission and carries the operator's ambient credentials. HTTP authentication is
 * therefore not a guard for a state change, it is only an identity.
 *
 * <h2>The control</h2>
 *
 * <p>The guard is a request header with a fixed value:
 *
 * <pre>
 *   X-CM-Insight-Action: repository-select          POST /api/repositories/select
 *   X-CM-Insight-Action: statistics-refresh         POST /api/statistics/refresh
 *   X-CM-Insight-Action: statistics-item-refresh    POST /api/statistics/item/{itemTypeId}/refresh
 *   X-CM-Insight-Action: report-generate            POST /api/reports
 * </pre>
 *
 * <p>Every state-changing endpoint has its OWN exact value. The values are deliberately not
 * interchangeable: accepting the statistics value on the report route (or the reverse) would mean a page that
 * learned one of them could drive an unrelated action, so a caller that presents the wrong value is refused
 * exactly as if it had presented none.</p>
 *
 * <p>An HTML form cannot set a request header at all - not even with {@code enctype="text/plain"} - so a
 * cross-site submission cannot satisfy it. The value must match EXACTLY: a presence check that accepted
 * any value would be defeatable by a client that learned only the header name. The three content types a
 * form <em>can</em> be told to send are refused as well, which is defence in depth rather than the guard
 * itself, so a forged submission is refused twice over.
 *
 * <p>Callers must consult this <strong>first</strong>, before reading a parameter, resolving a repository
 * or touching any state: a refused request has to have ZERO side effects, and the only way to guarantee
 * that is for the check to precede everything else. This class exists (rather than a private copy in each
 * route class) because two route families now share the control, and a second copy is how the two would
 * drift - one of them losing, say, the content-type rule.
 *
 * <p>CORS stays disabled: no handler emits an {@code Access-Control-*} header and none is ever added to a
 * response, so a browser cannot be made to read one of these endpoints cross-origin even if it could be
 * made to call one.
 */
public final class ActionGuard {

    /**
     * The request header that authorises a local runtime action.
     *
     * <p>Deliberately a custom header with the endpoint's own value: the header name and both values are
     * fixed here, so the routes, the tests and the bundled console cannot drift apart.
     */
    public static final String ACTION_HEADER = "X-CM-Insight-Action";

    /** The only value of {@link #ACTION_HEADER} that authorises a repository selection. */
    public static final String SELECT_ACTION = "repository-select";

    /** The only value of {@link #ACTION_HEADER} that authorises a statistics refresh. */
    public static final String STATISTICS_REFRESH_ACTION = "statistics-refresh";

    /**
     * The only value of {@link #ACTION_HEADER} that authorises a targeted single-ItemType refresh.
     *
     * <p>Distinct from {@link #STATISTICS_REFRESH_ACTION} on purpose: a full multi-ItemType scan and a
     * single-ItemType detail refresh are different amounts of work, so one value may not authorise both.
     */
    public static final String STATISTICS_ITEM_REFRESH_ACTION = "statistics-item-refresh";

    /**
     * The only value of {@link #ACTION_HEADER} that authorises a report generation.
     *
     * <p>Report generation writes a file below {@code reports.dir}, so it is a state change even though
     * nothing in the repository is modified; it is guarded like every other local action.
     */
    public static final String REPORT_GENERATE_ACTION = "report-generate";

    private static final String FORM_URLENCODED = "application/x-www-form-urlencoded";
    private static final String FORM_MULTIPART = "multipart/form-data";
    private static final String FORM_PLAIN = "text/plain";

    private ActionGuard() {
    }

    /**
     * True only when the request carries the action header with exactly {@code expectedAction} and does
     * not declare a body type an HTML form can send.
     *
     * @param ctx            the request
     * @param expectedAction the exact required value, one of {@link #SELECT_ACTION},
     *                       {@link #STATISTICS_REFRESH_ACTION}, {@link #STATISTICS_ITEM_REFRESH_ACTION} or
     *                       {@link #REPORT_GENERATE_ACTION}
     */
    public static boolean authorises(RequestContext ctx, String expectedAction) {
        if (ctx == null || expectedAction == null) {
            return false;
        }
        String action = ctx.header(ACTION_HEADER);
        if (action == null || !expectedAction.equals(action.trim())) {
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

    /** The frozen 403 body text for a refused action: names the control, never the request. */
    public static String refusalMessage(String expectedAction) {
        return "This action requires the header " + ACTION_HEADER + ": " + expectedAction;
    }
}
