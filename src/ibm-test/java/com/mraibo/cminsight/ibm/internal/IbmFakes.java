package com.mraibo.cminsight.ibm.internal;

import com.mraibo.cminsight.config.DatabaseVendor;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.connection.CmSession;
import com.mraibo.cminsight.connection.CreationFailure;
import com.mraibo.cminsight.connection.ResourceFactory;
import com.mraibo.cminsight.core.CmSessionFactory;
import com.mraibo.cminsight.ibm.CmAdapterSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The fakes the IBM adapter suites are driven by.
 *
 * <h2>Why the adapter can be tested at all without a server</h2>
 *
 * <p>No IBM CM server exists on a CI machine. The adapter is nevertheless testable down to the decision
 * that matters, because {@link IbmCmSessionFactory} takes its physical connection source as a constructor
 * argument ({@link IbmCmConnectionFactory}) and {@link IbmCmSession} takes its datastore view as an
 * interface ({@link IcmDatastore}). Both are package-private seams that exist for exactly this. The only
 * things these fakes must imitate are the lifecycle steps - connect, disconnect, destroy, isConnected -
 * which is where the cleanup verdict is decided.
 *
 * <h2>Never instantiate a stub</h2>
 *
 * <p>The SDK stubs under {@code tests/ibm-stubs} are signature-only: their method bodies throw
 * {@link UnsupportedOperationException}. They exist so this source set COMPILES without a proprietary JAR,
 * and they are never executed by a test. Everything here therefore goes through {@link IcmDatastore} and
 * {@link IbmCmConnectionFactory} and never touches a {@code com.ibm} type directly.
 *
 * <h2>Deterministic by construction</h2>
 *
 * <p>No fake sleeps and no interleaving depends on scheduler luck: the connect handshake uses latches, so a
 * test can hold a creation in flight until it has asserted exactly what it needs.
 */
final class IbmFakes {

    /** Environment variable carrying the fake CM user; the profile only names it. */
    static final String CM_USER_ENV = "CM_INSIGHT_TEST_CM_USER";
    /** Environment variable carrying the fake CM password. */
    static final String CM_PASSWORD_ENV = "CM_INSIGHT_TEST_CM_PASSWORD";

    private IbmFakes() {
    }

    /**
     * A profile whose CM credentials come from the two fake environment variables.
     *
     * <p>Uses the same {@code credentialFromEnvironment} indirection a real profile uses, so the factory's
     * credential resolution is exercised rather than bypassed. The JDBC pair is declared but never read:
     * ItemType and retention reads do not open the JDBC side, and demanding a database password to list
     * ItemTypes is exactly what the adapter refuses to do.
     */
    static RepositoryProfile profile(String id) {
        return new RepositoryProfile(
                id,
                "Repository " + id,
                "SSID-" + id,
                DatabaseVendor.DB2,
                "jdbc:db2://db.example:50000/" + id,
                "ICMADMIN",
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.CM_USER_KEY, CM_USER_ENV),
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.CM_PASSWORD_KEY, CM_PASSWORD_ENV),
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.JDBC_USER_KEY, "UNUSED_JDBC_USER"),
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.JDBC_PASSWORD_KEY,
                        "UNUSED_JDBC_PASSWORD"),
                null,
                null);
    }

    /** A resolver that always has the two fake CM credentials, so a failure is never a credential failure. */
    static SecretResolver resolver() {
        return new SecretResolver(Map.of(CM_USER_ENV, "cm-user", CM_PASSWORD_ENV, "cm-password"), null);
    }

    /** A resolver with no credentials at all, for the fail-closed credential path. */
    static SecretResolver emptyResolver() {
        return new SecretResolver(Map.of(), null);
    }

    /** A factory bound to {@code id} that connects through {@code connections}. */
    static IbmCmSessionFactory factory(String id, IbmCmConnectionFactory connections) {
        return new IbmCmSessionFactory(profile(id), CmAdapterSettings.defaults(resolver()), connections);
    }

    /** A factory bound to {@code id} with no CM credential configured. */
    static IbmCmSessionFactory credentialLessFactory(String id, IbmCmConnectionFactory connections) {
        return new IbmCmSessionFactory(profile(id), CmAdapterSettings.defaults(emptyResolver()), connections);
    }

    /**
     * Wraps a {@link CmSessionFactory} as the core pool's resource source.
     *
     * <p>The profile must be the SAME one the factory is bound to: the adapter refuses an open request for a
     * different repository rather than silently connecting the wrong SSID, so a mismatch here would fail the
     * borrow for a reason the test is not about.
     */
    static ResourceFactory<CmSession> resourceFactory(RepositoryProfile profile, CmSessionFactory sessions) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(sessions, "sessions");
        return new ResourceFactory<>() {
            @Override
            public CmSession create() throws Exception {
                return sessions.open(profile);
            }

            @Override
            public boolean isHealthy(CmSession resource) {
                return resource != null && resource.isHealthy();
            }

            @Override
            public String describe() {
                return "IbmFakes.resourceFactory";
            }
        };
    }

    // ------------------------------------------------------------------ fakes

    /**
     * A datastore whose every lifecycle step can be told to fail.
     *
     * <h2>What it counts, and why the counters are the assertion</h2>
     *
     * <p>Two of the adapter's obligations are "this must NOT happen" rules, and a rule that is not counted
     * cannot be asserted: {@link IbmCmSession#isHealthy()} must never call the vendor's
     * {@code isConnected()} (that is a server call made while the pool holds its lock), and an idempotent
     * {@link IbmCmSession#close()} must not run its teardown twice. So every step is counted and the tests
     * assert the counts, not merely that a call returned.
     */
    static final class FakeDatastore implements IcmDatastore {

        /** Records every lifecycle call in order, so a test can assert both content and order. */
        final List<String> calls = new ArrayList<>();

        private final AtomicInteger isConnectedCalls = new AtomicInteger();
        private final AtomicInteger disconnectCalls = new AtomicInteger();
        private final AtomicInteger destroyCalls = new AtomicInteger();

        private volatile boolean connected = true;
        private volatile RuntimeException isConnectedFailure;
        private volatile RuntimeException disconnectFailure;
        private volatile RuntimeException destroyFailure;

        @Override
        public com.ibm.mm.sdk.common.dkDatastoreDef datastoreDef() {
            calls.add("datastoreDef");
            throw new UnsupportedOperationException(
                    "IbmFakes.FakeDatastore.datastoreDef: the read services are exercised by the metadata and"
                            + " retention suites through their own fakes");
        }

        @Override
        public com.ibm.mm.sdk.common.dkDatastoreAdmin datastoreAdmin() {
            calls.add("datastoreAdmin");
            throw new UnsupportedOperationException(
                    "IbmFakes.FakeDatastore.datastoreAdmin: unused by the lifecycle suites");
        }

        @Override
        public com.ibm.mm.sdk.common.DKPolicyMgmtICM policyMgmt() {
            calls.add("policyMgmt");
            throw new UnsupportedOperationException(
                    "IbmFakes.FakeDatastore.policyMgmt: unused by the lifecycle suites");
        }

        @Override
        public boolean isConnected() {
            calls.add("isConnected");
            isConnectedCalls.incrementAndGet();
            RuntimeException failure = isConnectedFailure;
            if (failure != null) {
                throw failure;
            }
            return connected;
        }

        @Override
        public void disconnect() throws com.ibm.mm.sdk.common.DKException, Exception {
            calls.add("disconnect");
            disconnectCalls.incrementAndGet();
            RuntimeException failure = disconnectFailure;
            if (failure != null) {
                throw failure;
            }
            connected = false;
        }

        @Override
        public void destroy() throws com.ibm.mm.sdk.common.DKException, Exception {
            calls.add("destroy");
            destroyCalls.incrementAndGet();
            RuntimeException failure = destroyFailure;
            if (failure != null) {
                throw failure;
            }
        }

        int isConnectedCalls() {
            return isConnectedCalls.get();
        }

        int disconnectCalls() {
            return disconnectCalls.get();
        }

        int destroyCalls() {
            return destroyCalls.get();
        }

        void onDisconnectThrow(RuntimeException failure) {
            this.disconnectFailure = failure;
        }

        void onDestroyThrow(RuntimeException failure) {
            this.destroyFailure = failure;
        }

        void onIsConnectedThrow(RuntimeException failure) {
            this.isConnectedFailure = failure;
        }

        void setConnected(boolean value) {
            this.connected = value;
        }
    }

    /**
     * A connection source that hands out datastores and can refuse exactly one attempt.
     *
     * <p>{@link #failNext(UnexpectedFailure)} is the whole point of this fake: it decides what the physical
     * layer reports, which is the only input to the adapter's cleanup verdict. A test that wants an inverted
     * split to be caught sets the fake to report the other verdict and asserts the resulting
     * {@link CreationFailure.Cleanup}.
     */
    static final class FakeConnections implements IbmCmConnectionFactory {

        /** The failure the next {@code connect} call reports, or {@code null} for success. */
        private volatile UnexpectedFailure nextFailure;

        /**
         * When set, every successful {@code connect} hands out THIS datastore.
         *
         * <p>Lets a test drive the full pool path - borrow, use, return - against a datastore whose teardown
         * it has configured to fail, which is how the session's unproven-teardown rule is shown to reach the
         * pool's capacity accounting.
         */
        volatile FakeDatastore failedDatastore;

        private final AtomicInteger connectAttempts = new AtomicInteger();

        /** Datastores handed out, so a test can inspect what was actually connected. */
        final List<FakeDatastore> issued = new ArrayList<>();

        @Override
        public IcmDatastore connect(String ssid, String user, String password) throws Exception {
            connectAttempts.incrementAndGet();
            UnexpectedFailure failure = nextFailure;
            if (failure == null) {
                FakeDatastore datastore = failedDatastore == null ? new FakeDatastore() : failedDatastore;
                issued.add(datastore);
                return datastore;
            }
            nextFailure = null;
            if (failure.throwable instanceof IbmCmCleanupFailure cleanupFailure) {
                throw cleanupFailure;
            }
            if (failure.throwable instanceof Exception checked) {
                throw checked;
            }
            if (failure.throwable instanceof Error fatal) {
                throw fatal;
            }
            throw new IllegalStateException("the fake was told to fail with a Throwable it cannot throw");
        }

        int connectAttempts() {
            return connectAttempts.get();
        }

        void failNext(UnexpectedFailure failure) {
            this.nextFailure = Objects.requireNonNull(failure, "failure");
        }
    }

    /**
     * What the simulated physical layer reports for one attempt.
     *
     * <p>Naming the two CM failure types explicitly is what makes the verdict assertion meaningful: the
     * adapter distinguishes UNPROVEN from PROVEN_CLEAN by the type it is handed, so a test that used a
     * generic exception could not tell an inverted split from a correct one.
     */
    static final class UnexpectedFailure {

        final Throwable throwable;

        private UnexpectedFailure(Throwable throwable) {
            this.throwable = Objects.requireNonNull(throwable, "throwable");
        }

        /** The teardown of a half-built session did not return normally: the cleanup is UNPROVEN. */
        static UnexpectedFailure cleanupUnproven(String detail) {
            return new UnexpectedFailure(
                    new IbmCmCleanupFailure("connect-cleanup",
                            "connect cm(IllegalStateException); " + detail, null));
        }

        /** Allocation or connection failed and cleanup returned normally: the cleanup is PROVEN_CLEAN. */
        static UnexpectedFailure provenClean(String detail) {
            return new UnexpectedFailure(
                    new IbmCmFailure("cm", "connect cm(IllegalStateException); " + detail, null, true));
        }

        /** An ordinary checked exception, which is not a verdict at all. */
        static UnexpectedFailure ordinaryException(String message) {
            return new UnexpectedFailure(new Exception(message));
        }

        @Override
        public String toString() {
            return "UnexpectedFailure[" + throwable.getClass().getSimpleName() + "]";
        }
    }
}
