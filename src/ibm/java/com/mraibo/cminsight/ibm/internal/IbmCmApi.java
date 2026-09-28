package com.mraibo.cminsight.ibm.internal;

import com.ibm.mm.sdk.common.DKDatastoreAdminICM;
import com.ibm.mm.sdk.common.DKDatastoreDefICM;
import com.ibm.mm.sdk.common.DKPolicyMgmtICM;
import com.ibm.mm.sdk.common.dkCollection;
import com.ibm.mm.sdk.common.dkDatastoreAdmin;
import com.ibm.mm.sdk.common.dkDatastoreDef;
import com.ibm.mm.sdk.common.dkIterator;
import com.ibm.mm.sdk.server.DKDatastoreICM;

/**
 * The ONE class in the adapter that performs CMSDK calls, and therefore the one place where the vendor's
 * type system, checked exceptions and object lifetime are handled.
 *
 * <h2>Why everything is funnelled through here</h2>
 *
 * <p>Three properties follow from having a single vendor-facing class, and all three are requirements
 * rather than stylistic preferences:
 *
 * <ul>
 *   <li><strong>Sanitisation cannot be forgotten.</strong> Every SDK call is wrapped by {@link #read} or
 *       {@link #readOrAbsent}, so there is no path on which a raw SDK exception message could reach a
 *       service, the metadata cache, a diagnostics page or an HTTP response. Redacting at the point of
 *       logging is one forgotten call away from leaking a repository name; redacting at the point of
 *       creation cannot be forgotten.</li>
 *   <li><strong>The read-only surface is one screen long.</strong> A reviewer can confirm from this file
 *       alone that the adapter never calls {@code commit}, {@code update}, {@code del}, {@code add},
 *       {@code clearCache} or the native {@code connection()} handle. The test-only SDK stub does not
 *       even declare those members, so such a call cannot compile in this source set.</li>
 *   <li><strong>The services stay ordinary Java.</strong> They receive already-drained arrays, which is
 *       what lets the mapping logic - the part with the real bugs in it - be tested without an IBM
 *       server.</li>
 * </ul>
 *
 * <h2>The {@code disconnect}/{@code destroy} pair is deliberately absent</h2>
 *
 * <p>{@link IbmCmSession} owns the physical lifecycle, because the outcome of teardown ("proven clean"
 * or "unproven") is exactly the difference between releasing a pool capacity slot and quarantining it.
 * Exposing teardown here would put that decision in two places, and a disagreement between them would be
 * a bound breach.
 */
final class IbmCmApi {

    private IbmCmApi() {
    }

    /**
     * Wraps a read-only SDK call: runs it and translates any failure into a sanitised
     * {@link IbmCmFailure}, so no {@code com.ibm} type and no vendor text escapes.
     *
     * @param session the session the call runs on, marked unusable when the failure means the physical
     *                session must not be reused, so the lease return retires it
     * @param context short operation label used in the sanitised text, for example {@code listItemTypes}
     */
    static <T> T read(IbmCmSession session, String context, ReadCall<T> call) {
        try {
            return call.run();
        } catch (InterruptedException interrupted) {
            // Restore the flag and report the interruption rather than disguising it: swallowing an
            // interrupt turns an orderly shutdown into a hang, which is the opposite of fail-closed.
            Thread.currentThread().interrupt();
            throw new IbmCmFailure("interrupted", "interrupted during " + context, interrupted, true);
        } catch (Exception failure) {
            boolean unusable = IbmErrorSanitizer.backendUnusable(failure);
            if (unusable) {
                session.markUnusable();
            }
            throw new IbmCmFailure(IbmErrorSanitizer.category(failure),
                    IbmErrorSanitizer.describe(context, failure), failure, unusable);
        }
    }

    /**
     * Wraps a read-only SDK call that is allowed to answer "the repository has no such thing".
     *
     * <p>A {@code DKNotExistException} is not a fault - it is the server correctly saying no - so it is
     * returned as {@code null} instead of becoming a failure. The distinction matters at the API
     * boundary, where "no such ItemType" is a {@code 404} and "the server is broken" is a {@code 502};
     * collapsing them would make a typo in a URL look like an outage.
     */
    static <T> T readOrAbsent(IbmCmSession session, String context, ReadCall<T> call) {
        try {
            return call.run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IbmCmFailure("interrupted", "interrupted during " + context, interrupted, true);
        } catch (com.ibm.mm.sdk.common.DKNotExistException absent) {
            // An answer, not a failure: nothing to sanitise, nothing to mark unusable, no error state.
            return null;
        } catch (Exception failure) {
            boolean unusable = IbmErrorSanitizer.backendUnusable(failure);
            if (unusable) {
                session.markUnusable();
            }
            throw new IbmCmFailure(IbmErrorSanitizer.category(failure),
                    IbmErrorSanitizer.describe(context, failure), failure, unusable);
        }
    }

    /**
     * The datastore definition of a live session.
     *
     * <p>Exists as a named method because BOTH the ItemType and the retention chain start here, and
     * because the interaction is easy to get wrong: {@code datastoreDef()} is not narrowed in the SDK, so
     * the cast to {@link DKDatastoreDefICM} is mandatory. Without it, {@code listEntities} is not
     * reachable and the whole adapter is dead code.
     */
    static DKDatastoreDefICM datastoreDef(IbmCmSession session) {
        IcmDatastore handle = session.handle();
        dkDatastoreDef definition = read(session, "datastoreDef", handle::datastoreDef);
        if (definition instanceof DKDatastoreDefICM icmDefinition) {
            return icmDefinition;
        }
        // A non-ICM definition means this adapter is connected to something it does not understand.
        // Report that plainly instead of throwing ClassCastException, which would put a stack trace in
        // an HTTP response and tell an operator nothing.
        throw new IbmCmFailure("cm", "the datastore is not an ICM datastore definition ("
                + (definition == null ? "null" : definition.getClass().getSimpleName()) + ")", null, true);
    }

    /**
     * The retention policy management entry point of a live session.
     *
     * <p>{@code datastoreAdmin()} is not narrowed either: {@code dkDatastoreAdmin} declares no
     * {@code policyMgmt()}, so the cast to {@link DKDatastoreAdminICM} is mandatory.
     */
    static DKPolicyMgmtICM policyMgmt(IbmCmSession session) {
        DKPolicyMgmtICM direct = read(session, "policyMgmt", session.handle()::policyMgmt);
        if (direct != null) {
            return direct;
        }
        dkDatastoreAdmin admin = read(session, "datastoreAdmin", session.handle()::datastoreAdmin);
        if (admin instanceof DKDatastoreAdminICM icmAdmin) {
            return read(session, "policyMgmt", icmAdmin::policyMgmt);
        }
        throw new IbmCmFailure("cm", "the datastore has no ICM administration interface ("
                + (admin == null ? "null" : admin.getClass().getSimpleName()) + ")", null, true);
    }

    /**
     * Reads a listed collection into an array, immediately.
     *
     * <p>The copy has to happen while the session is still leased: a {@code dkCollection} is a live SDK
     * object backed by the connection, so iterating it after the lease was returned would be a use of a
     * released resource - and might silently return nothing rather than fail. Doing it here is what
     * allows every service to release its session before it starts mapping, so no business method
     * retains a lease.
     *
     * <p>The iteration protocol is the SDK's own: {@code createIterator()}, then {@code more()} and
     * {@code next()}. {@code retrieveElementAt(iterator)} exists on the interface but is deliberately not
     * used: it requires an already-positioned cursor, whereas {@code next()} both advances and returns,
     * so it cannot be called in the wrong order.
     */
    static Object[] drain(IbmCmSession session, dkCollection collection) {
        if (collection == null) {
            return new Object[0];
        }
        return read(session, "listCollection", () -> {
            dkIterator iterator = collection.createIterator();
            if (iterator == null) {
                return new Object[0];
            }
            int capacity = Math.max(0, collection.cardinality());
            Object[] elements = new Object[capacity];
            int index = 0;
            while (iterator.more()) {
                Object element = iterator.next();
                if (index == elements.length) {
                    // cardinality() under-reported. Growing is the honest response: truncating would
                    // silently hide ItemTypes from the viewer, which is worse than a larger copy.
                    Object[] grown = new Object[Math.max(4, elements.length * 2)];
                    System.arraycopy(elements, 0, grown, 0, index);
                    elements = grown;
                }
                elements[index] = element;
                index++;
            }
            if (index == elements.length) {
                return elements;
            }
            Object[] exact = new Object[index];
            System.arraycopy(elements, 0, exact, 0, index);
            return exact;
        });
    }

    /** A read-only SDK call. Declared so the vendor's checked exceptions stay inside this class. */
    @FunctionalInterface
    interface ReadCall<T> {
        T run() throws Exception;
    }
}
