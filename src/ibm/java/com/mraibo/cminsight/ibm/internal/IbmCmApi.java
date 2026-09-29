package com.mraibo.cminsight.ibm.internal;

import com.ibm.mm.sdk.common.DKDatastoreAdminICM;
import com.ibm.mm.sdk.common.DKDatastoreDefICM;
import com.ibm.mm.sdk.common.DKNotExistException;
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
 *
 * <h2>This class is the ONLY place a vendor failure is classified, and classifying retires the session</h2>
 *
 * <p>The Goal 02A invariant is <em>"every failure classified backend-unusable MUST mark the borrowed
 * session unusable before the Lease is returned"</em>. The failure mode that broke it was not a missing
 * check in one place but the shape of the code: a caller could classify a failure (through
 * {@link IbmErrorSanitizer#backendUnusable(Throwable)}) and then forget to mark, and four such sites were
 * found in review. The repair is structural rather than a reminder:
 *
 * <ul>
 *   <li>every vendor call goes through {@link #read} or {@link #readOrAbsent}, and those two wrappers own
 *       BOTH halves - one private {@code translate} method classifies a failure and marks the session, so
 *       there is no way to do one without the other;</li>
 *   <li>a vendor object of the wrong or absent type goes through {@link #requireIcmType}, which marks
 *       before it throws, so the "wrong vendor type" paths cannot report a failure and leave the session
 *       idle-and-healthy;</li>
 *   <li>an {@link Error} from a vendor call is marked FIRST and then rethrown unchanged, so the pool can
 *       never observe a healthy session on the way out;</li>
 *   <li>an already-translated {@link IbmCmFailure} is re-checked at this boundary: when the failure's own
 *       classification says {@link IbmCmFailure#backendUnusable()}, the session is marked unusable again
 *       before the rethrow. A lower wrapper normally did that already, and the second marking is an
 *       idempotent local flag write, so the cost is nil in the common case - but the invariant now holds
 *       for a failure built anywhere, including one whose call site forgot;</li>
 *   <li>{@code DKNotExistException} is the one explicit benign control: {@link #readOrAbsent} answers
 *       "absent" (and {@link #read} classifies it {@code cm-not-found}) without retiring anything.</li>
 * </ul>
 *
 * <p>The alternative shape - having {@link IbmCmFailure} mark the session itself - was rejected because
 * that type is also constructed for failures with no live session at all (a blank SSID, an unresolvable
 * credential, a foreign session in the pool), so it cannot own the invariant without inventing a session
 * argument it must sometimes be handed {@code null} for.
 */
final class IbmCmApi {

    private IbmCmApi() {
    }

    /**
     * Wraps a read-only SDK call: runs it and translates any failure into a sanitised
     * {@link IbmCmFailure}, so no {@code com.ibm} type and no vendor text escapes.
     *
     * <p>Classification and session retirement happen together inside {@link #vendorCall}: a failure that
     * means the session must not be reused marks it before the failure leaves, so the lease return retires
     * it. A {@code DKNotExistException} is classified {@code cm-not-found} (benign) and retires nothing.
     *
     * @param session the session the call runs on
     * @param context short operation label used in the sanitised text, for example {@code listItemTypes}
     */
    static <T> T read(IbmCmSession session, String context, ReadCall<T> call) {
        return vendorCall(session, context, false, call);
    }

    /**
     * Wraps a read-only SDK call that is allowed to answer "the repository has no such thing".
     *
     * <p>A {@code DKNotExistException} is not a fault - it is the server correctly saying no - so it is
     * returned as {@code null} instead of becoming a failure. The distinction matters at the API
     * boundary, where "no such ItemType" is a {@code 404} and "the server is broken" is a {@code 502};
     * collapsing them would make a typo in a URL look like an outage.
     *
     * <p>Every OTHER failure is treated exactly as in {@link #read}, which includes retiring the session:
     * "the SDK threw something that is not not-found" is evidence about the session, not about the lookup.
     */
    static <T> T readOrAbsent(IbmCmSession session, String context, ReadCall<T> call) {
        return vendorCall(session, context, true, call);
    }

    /**
     * The one vendor-call guard: runs the call, and makes sure a failure can never reach a caller without
     * the session having been retired when it must be.
     *
     * <p>This includes the failure that arrives already translated: rethrowing an {@link IbmCmFailure}
     * unchanged is only correct when its {@link IbmCmFailure#backendUnusable()} answer has already been
     * applied to the session, and this method is where that is enforced rather than assumed. The check is
     * conditional, idempotent and uses the failure's own sanitised text, so it neither poisons a reusable
     * session nor leaks vendor text.
     *
     * @param absentIsAnAnswer true for {@link #readOrAbsent}, where {@code DKNotExistException} is the
     *                         benign control that returns {@code null} and retires nothing
     */
    private static <T> T vendorCall(IbmCmSession session, String context, boolean absentIsAnAnswer,
            ReadCall<T> call) {
        try {
            return call.run();
        } catch (IbmCmFailure alreadyTranslated) {
            // A nested wrapper (a service mapping an ItemType through read(), for example) normally
            // classified this failure AND marked the session. The invariant, however, is this wrapper's:
            // "every backend-unusable failure leaving a vendor call has already marked the session
            // unusable" must not depend on which call site built the failure. So the classification the
            // failure already carries is reasserted here.
            //
            // Why this is not redundant. Relying on the lower wrapper made correctness a property of
            // call-site memory: any future path that constructs an IbmCmFailure(backendUnusable=true)
            // without marking - a service, a helper, a new adapter - would let a poisoned session go back
            // into the pool, which is precisely the Goal 02A defect shape. Re-checking at the boundary
            // makes the retirement structural.
            //
            // Why it is safe. markUnusable() is documented idempotent: a second call on an already
            // unusable session is a local volatile write that changes no state, and the diagnostic sink
            // keeps only the last sanitised text, so a nested failure cannot accumulate duplicates. The
            // text used here is the failure's OWN already-sanitised message - produced by
            // IbmErrorSanitizer when the failure was constructed - so nothing raw from the vendor is read,
            // re-described or recorded at this point.
            //
            // Why it is conditional. A benign failure must stay benign: DKNotExistException is answered by
            // readOrAbsent (below) and never reaches here, and a failure whose own classification says the
            // backend is usable must leave the session reusable, or every not-found answer would retire a
            // healthy session. Only backendUnusable() == true poisons anything.
            if (alreadyTranslated.backendUnusable()) {
                session.markUnusable(alreadyTranslated.getMessage());
            }
            throw alreadyTranslated;
        } catch (InterruptedException interrupted) {
            // Restore the flag and report the interruption rather than disguising it: swallowing an
            // interrupt turns an orderly shutdown into a hang, which is the opposite of fail-closed. The
            // session follows the documented conservative retirement rule - an interrupt leaves the
            // transaction state of the vendor call unknown, so the session is not reused - and the
            // interrupted thread is NOT blocked by that marking, which is a local flag write.
            Thread.currentThread().interrupt();
            throw retire(session, "interrupted", "interrupted during " + context, interrupted);
        } catch (Error fatal) {
            // An Error is not a CM failure, but it is still a vendor call that did not return normally, so
            // the session's state is unknown: mark it BEFORE the Error leaves, or the pool would see a
            // healthy session and hand it to the next borrower. The Error is then rethrown unchanged - it
            // is a JVM-level fault and must not be disguised as an ordinary adapter failure.
            session.markUnusable(IbmErrorSanitizer.describe(context, fatal));
            throw fatal;
        } catch (Exception failure) {
            if (absentIsAnAnswer && failure instanceof DKNotExistException) {
                // An answer, not a failure: nothing to sanitise, nothing to mark unusable, no error state.
                return null;
            }
            throw translate(session, context, failure);
        }
    }

    /**
     * The ONE place a vendor failure is classified, and the same place that retires the session when the
     * classification says the session must not be reused.
     *
     * <p>Keeping the two halves in one private method is the structural half of the Goal 02A repair: a
     * caller cannot classify a failure and then forget the marking, because no caller classifies at all.
     */
    private static IbmCmFailure translate(IbmCmSession session, String context, Exception failure) {
        boolean unusable = IbmErrorSanitizer.backendUnusable(failure);
        String detail = IbmErrorSanitizer.describe(context, failure);
        if (unusable) {
            session.markUnusable(detail);
        }
        return new IbmCmFailure(IbmErrorSanitizer.category(failure), detail, failure, unusable);
    }

    /**
     * Marks the session unusable and returns the failure to throw, for the paths that do not translate an
     * SDK exception ({@link #datastoreDef}, {@link #policyMgmt}, a wrong vendor type).
     *
     * <p>The order is not incidental: the marking happens before the exception object is even built,
     * because an unmarked failure is the exact defect this class was repaired for.
     */
    private static IbmCmFailure retire(IbmCmSession session, String category, String context,
            Throwable failure) {
        String detail = IbmErrorSanitizer.describe(context, failure);
        session.markUnusable(detail);
        return new IbmCmFailure(category, detail, failure, true);
    }

    /**
     * Requires a vendor object to be the ICM type this adapter can actually use, retiring the session when
     * it is not.
     *
     * <p>A non-ICM (or absent) datastore definition or administration interface means this adapter is
     * talking to something it does not understand - a wrong SSID, a server version that no longer matches,
     * a partially initialised object. Any later call on such a session is guesswork, so the session is
     * marked unusable BEFORE the failure leaves and the lease return retires it. Doing that here, once,
     * instead of at each cast site is what makes "remember to call markUnusable()" unnecessary.
     *
     * @param session     the session the vendor object came from; marked unusable when the type is wrong
     * @param description human-readable expectation, without the observed type
     * @param candidate   the vendor object, or {@code null} when the SDK answered "nothing"
     * @param icmType     the ICM type the adapter requires
     * @throws IbmCmFailure when the object is not of {@code icmType}
     */
    static <T> T requireIcmType(IbmCmSession session, String description, Object candidate,
            Class<T> icmType) {
        if (icmType.isInstance(candidate)) {
            return icmType.cast(candidate);
        }
        String message = description + " ("
                + (candidate == null ? "null" : candidate.getClass().getSimpleName()) + ")";
        session.markUnusable(message);
        throw new IbmCmFailure("cm", message, null, true);
    }

    /**
     * The datastore definition of a live session.
     *
     * <p>Exists as a named method because BOTH the ItemType and the retention chain start here, and
     * because the interaction is easy to get wrong: {@code datastoreDef()} is not narrowed in the SDK, so
     * the cast to {@link DKDatastoreDefICM} is mandatory and its wrong-type path is a session retirement
     * ({@link #requireIcmType}), never a bare {@code ClassCastException} and never a failure that leaves
     * the session reusable.
     */
    static DKDatastoreDefICM datastoreDef(IbmCmSession session) {
        IcmDatastore handle = session.handle();
        dkDatastoreDef definition = read(session, "datastoreDef", handle::datastoreDef);
        return requireIcmType(session, "the datastore is not an ICM datastore definition", definition,
                DKDatastoreDefICM.class);
    }

    /**
     * The retention policy management entry point of a live session.
     *
     * <p>{@code datastoreAdmin()} is not narrowed either: {@code dkDatastoreAdmin} declares no
     * {@code policyMgmt()}, so the cast to {@link DKDatastoreAdminICM} is mandatory. Both the wrong-type
     * admin and an admin that answers "no policy management" retire the session before failing - the
     * method never returns {@code null}, so a caller cannot reach a retention call on a session whose
     * administration interface was not what this adapter requires.
     */
    static DKPolicyMgmtICM policyMgmt(IbmCmSession session) {
        DKPolicyMgmtICM direct = read(session, "policyMgmt", session.handle()::policyMgmt);
        if (direct != null) {
            return direct;
        }
        dkDatastoreAdmin admin = read(session, "datastoreAdmin", session.handle()::datastoreAdmin);
        DKDatastoreAdminICM icmAdmin = requireIcmType(session,
                "the datastore has no ICM administration interface", admin, DKDatastoreAdminICM.class);
        return requireIcmType(session, "the ICM administration interface exposes no retention policy"
                + " management", read(session, "policyMgmt", icmAdmin::policyMgmt), DKPolicyMgmtICM.class);
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
