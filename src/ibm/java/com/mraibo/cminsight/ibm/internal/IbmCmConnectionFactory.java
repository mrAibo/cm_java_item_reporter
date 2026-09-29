package com.mraibo.cminsight.ibm.internal;

import com.ibm.mm.sdk.server.DKDatastoreICM;

/**
 * Allocates the physical {@code DKDatastoreICM} objects the adapter reuses, and connects them.
 *
 * <h2>Why allocation and connection are behind a factory</h2>
 *
 * <p>Two separate obligations meet here.
 *
 * <p>The first is lifecycle arithmetic. {@link IbmCmSession} has to distinguish "this failure left
 * nothing behind" from "this failure may have left a live session behind", because
 * {@link com.mraibo.cminsight.connection.BoundedPool} converts exactly that distinction into "release the
 * capacity slot" versus "quarantine it". Proving the first reading is only possible if the adapter knows
 * whether the physical object existed yet, so creation is a clearly separated step returned to its
 * caller.
 *
 * <p>The second is testability. A tree with a real IBM SDK on the class path can still run against a fake
 * factory, which is what lets the session lifecycle, the pool bounds and the uncertain-creation
 * accounting be exercised deterministically - including the cases a real server cannot be asked to
 * produce on demand, such as a {@code destroy()} that fails.
 *
 * <h2>The failed-connect cleanup is one documented rule, implemented once</h2>
 *
 * <p>A failed {@code connect()} is NOT allowed to decide the verdict by itself, and it is not allowed to
 * manufacture a second error by unconditionally calling {@code disconnect()} on a datastore that never
 * completed a connection. IBM CM 8.7 documents {@code isConnected()} as "connect() was called and
 * completed successfully", {@code destroy()} as "destroys the datastore object and performs the datastore
 * cleanup if needed", and {@code connect -> disconnect -> destroy} as the successful lifecycle. The
 * release therefore runs through {@link IbmCmCleanupFailure#releaseQuietly(IcmDatastore)} - the SAME
 * routine {@link IbmCmSession#close()} uses - and its verdict is that a successful {@code destroy()} is
 * cleanup proof, while a failing one leaves cleanup unproven and quarantines the slot. The full reasoning
 * and the exact step order are written down there, next to the code a reviewer has to check.
 *
 * <h2>No global state</h2>
 *
 * <p>There is deliberately no singleton and no cache of datastores: the pool is the only owner of a
 * physical session in this design, and a second owner would be a second thing to bound. The reference
 * implementation's shared connection registry is exactly the pattern this seam exists to avoid.
 */
interface IbmCmConnectionFactory {

    /**
     * Allocates one datastore object and connects it.
     *
     * <p>On failure the implementation must attempt cleanup and report whether that cleanup provably
     * succeeded. The failure TYPE says so:
     *
     * @return the connected handle
     * @throws IbmCmFailure when allocation or connection failed and cleanup is proven, so the caller may
     *         treat the attempt as empty
     * @throws IbmCmCleanupFailure when allocation or connection failed and {@code destroy()} did not
     *         return normally, so the caller MUST report the attempt as an unproven cleanup. Note that a
     *         failed {@code disconnect()} alone is NOT this case - see
     *         {@link IbmCmCleanupFailure#releaseQuietly(IcmDatastore)}
     */
    IcmDatastore connect(String ssid, String user, String password) throws Exception;

    /** The production factory: the real SDK, and nothing else. */
    IbmCmConnectionFactory SDK = new IbmCmConnectionFactory() {

        @Override
        public IcmDatastore connect(String ssid, String user, String password) throws Exception {
            DKDatastoreICM datastore;
            try {
                // Construction itself declares throws DKException, Exception - the concrete class does
                // not narrow it - so it belongs inside the same failure accounting as connect(). A
                // constructor that threw half-built cannot be destroyed safely, and there is no
                // reference to destroy anyway, so this attempt provably leaves nothing behind.
                datastore = new DKDatastoreICM();
            } catch (Exception allocationFailure) {
                throw new IbmCmFailure("cm", IbmErrorSanitizer.describe("allocate datastore",
                        allocationFailure), allocationFailure, true);
            } catch (Error fatal) {
                // An Error is not a CM failure, but it is still a failed attempt that allocated nothing
                // referenceable. It is recorded so the operator sees it in lastAdapterError, then
                // rethrown so it is never mistaken for an ordinary recoverable failure.
                throw new IbmCmFailure("fatal", "fatal error while allocating the datastore", fatal, true);
            }

            // The live view is built BEFORE connect(), so the SAME handle the connected path returns is
            // also the one the cleanup path releases. That is what lets the failed-connect verdict run
            // through the one documented release routine instead of a second, vendor-typed copy of it.
            IcmDatastore handle = new DkDatastoreHandle(datastore);
            try {
                datastore.connect(ssid, user, password, "");
                return handle;
            } catch (Exception connectFailure) {
                // A failure here is the interesting case. connect() is a two-step physical operation, so
                // an exception does NOT prove that no session was established - and the parameter names
                // the goal fixes (ssid, user, password, "") tell us nothing about what the SDK achieved
                // before it threw. Cleanup is therefore attempted on the object we hold, in the
                // documented order, and the verdict comes from destroy():
                //   - isConnected() false  -> no logical session was ever established, so disconnect() is
                //                             NOT attempted (a second error would prove nothing);
                //   - isConnected() true   -> disconnect() attempted, its problem kept as diagnostics;
                //   - isConnected() throws -> conservative: disconnect() attempted anyway;
                //   - destroy() always, and a successful destroy() is cleanup proof.
                IbmCmCleanupFailure.Teardown teardown = IbmCmCleanupFailure.releaseQuietly(handle);
                String text = IbmErrorSanitizer.describe("connect", connectFailure)
                        + teardown.diagnosticSuffix();
                if (teardown.destroyProven()) {
                    // PROVEN_CLEAN: the slot is released. The disconnect problem - if there was one -
                    // travelled in `text` above and reaches lastAdapterError, but it does not quarantine
                    // the slot, because destroy() performed the cleanup the slot accounts for.
                    throw new IbmCmFailure("cm", text, connectFailure, true);
                }
                throw new IbmCmCleanupFailure("connect-cleanup",
                        text + "; destroy() did not return normally, so a physical session may still exist",
                        connectFailure);
            } catch (Error fatal) {
                // A JVM-level failure during connect is still a failed connect, so the documented cleanup
                // is attempted before the Error leaves - and every step is attempted, exactly as on the
                // Exception path. The Error itself is rethrown unchanged rather than disguised as an
                // adapter verdict: section A's rule sends an untyped Error to the conservative
                // quarantine, so the physical bound holds without this factory inventing a verdict it
                // cannot prove.
                IbmCmCleanupFailure.releaseQuietly(handle);
                throw fatal;
            }
        }

        @Override
        public String toString() {
            return "IbmCmConnectionFactory.SDK";
        }
    };
}
