package com.mraibo.cminsight.ibm.internal;

import com.ibm.mm.sdk.common.DKException;
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
     * succeeded. The boolean return says so:
     *
     * @return the connected handle
     * @throws IbmCmFailure when allocation or connection failed and nothing was left behind, so the
     *         caller may treat the attempt as empty
     * @throws IbmCmCleanupFailure when allocation or connection failed and a teardown step did not
     *         return normally, so the caller MUST report the attempt as an unproven cleanup
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

            try {
                datastore.connect(ssid, user, password, "");
                return new DkDatastoreHandle(datastore);
            } catch (Exception connectFailure) {
                // A failure here is the interesting case. connect() is a two-step physical operation, so
                // an exception does NOT prove that no session was established - and the parameter names
                // the goal fixes (ssid, user, password, "") tell us nothing about what the SDK achieved
                // before it threw. Cleanup is therefore attempted on the object we hold.
                boolean provenClean = cleanupQuietly(datastore);
                String text = IbmErrorSanitizer.describe("connect", connectFailure);
                if (provenClean) {
                    throw new IbmCmFailure("cm", text, connectFailure, true);
                }
                throw new IbmCmCleanupFailure("connect-cleanup",
                        text + "; cleanup of the allocated datastore did not return normally, so a "
                                + "physical session may still exist",
                        connectFailure);
            }
        }

        /**
         * Attempts {@code disconnect()} then {@code destroy()} and reports whether BOTH returned
         * normally.
         *
         * <p>Every step is attempted even when an earlier one failed - skipping {@code destroy()} because
         * {@code disconnect()} threw is how a half-torn-down session survives - and everything is caught,
         * because a failure while cleaning up must not replace the failure that caused the cleanup.
         */
        private boolean cleanupQuietly(DKDatastoreICM datastore) {
            boolean proven = true;
            try {
                datastore.disconnect();
            } catch (Exception | Error disconnectFailure) {
                proven = false;
            }
            try {
                datastore.destroy();
            } catch (Exception | Error failure) {
                proven = false;
            }
            return proven;
        }

        @Override
        public String toString() {
            return "IbmCmConnectionFactory.SDK";
        }
    };
}
