package com.mraibo.cminsight.ibm.internal;

import com.mraibo.cminsight.config.ClassificationRules;
import com.mraibo.cminsight.config.DatabaseVendor;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.connection.BoundedPool;
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

    /**
     * A factory whose settings carry a SPECIFIC classification rule set, for the section D provenance
     * tests.
     *
     * <p>The rules are handed in exactly as the core would hand them: already parsed, from the
     * configuration the launcher actually selected. Nothing here reads a configuration file, which is the
     * property under test.
     */
    static IbmCmSessionFactory factory(String id, IbmCmConnectionFactory connections,
                                       ClassificationRules classifications) {
        return new IbmCmSessionFactory(profile(id),
                CmAdapterSettings.defaults(resolver(), classifications), connections);
    }

    /** A pool over {@code sessions}, sized 1, for the read-service tests. */
    static BoundedPool<CmSession> pool(String id, IbmCmSessionFactory sessions) {
        return new BoundedPool<>("ibm-fakes:" + id, 1, java.time.Duration.ofSeconds(5),
                resourceFactory(profile(id), sessions));
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
         * pool's capacity accounting. Also how the READ services are exercised: a {@code FakeReadDatastore}
         * supplies a datastore definition, so an ItemType listing runs through the real mapping code.
         */
        volatile IcmDatastore failedDatastore;

        private final AtomicInteger connectAttempts = new AtomicInteger();

        /** Datastores handed out, so a test can inspect what was actually connected. */
        final List<IcmDatastore> issued = new ArrayList<>();

        @Override
        public IcmDatastore connect(String ssid, String user, String password) throws Exception {
            connectAttempts.incrementAndGet();
            UnexpectedFailure failure = nextFailure;
            if (failure == null) {
                IcmDatastore datastore = failedDatastore == null ? new FakeDatastore() : failedDatastore;
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

    // ------------------------------------------------------------------ read-path fakes

    /**
     * A FOREIGN vendor object: an instance of a vendor interface that is deliberately NOT the ICM type the
     * adapter requires.
     *
     * <h2>Why this is a dynamic proxy, and must stay one</h2>
     *
     * <p>The obvious way to write this fake - {@code new dkDatastoreDef() { ... }} - is a trap that only
     * bites the person who owns the proprietary jars. The committed stubs under {@code tests/ibm-stubs} are
     * deliberately NARROWER than the real SDK, because they omit the mutating members so that a mutating call
     * cannot compile in {@code src/ibm/java}. An anonymous implementation therefore has fewer abstract
     * methods to satisfy than the real interface declares: it compiles against the stubs, passes every CI
     * run, and fails to compile against the real IBM CM 8.7 jars, where {@code dkDatastoreDef} also declares
     * {@code clearCache()} and friends. Measured: exactly that, in this suite, on the
     * {@code --require-ibm} path.
     *
     * <p>A {@link java.lang.reflect.Proxy} never names a member of the interface, so it compiles and behaves
     * IDENTICALLY against the stubs and against the real SDK - which is why no test in this tree may
     * hand-write an anonymous implementation of a {@code com.ibm} interface.
     *
     * <p>Calling anything on the result is a test defect rather than a supported operation, so it fails
     * loudly: the property these objects exist for is that the adapter rejects them BY TYPE, before any
     * vendor call can reach them. {@code toString}/{@code hashCode}/{@code equals} are answered normally
     * because an error message or a collection may still reach for them.
     *
     * @param vendorInterface the SDK interface to stand in for; must be an interface
     */
    static <T> T foreignVendorType(Class<T> vendorInterface) {
        Objects.requireNonNull(vendorInterface, "vendorInterface");
        if (!vendorInterface.isInterface()) {
            throw new IllegalArgumentException(vendorInterface.getName() + " is not an interface, so a proxy"
                    + " cannot stand in for a foreign vendor object");
        }
        ClassLoader loader = vendorInterface.getClassLoader();
        if (loader == null) {
            loader = ClassLoader.getSystemClassLoader();
        }
        Object proxy = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {vendorInterface},
                (instance, method, args) -> switch (method.getName()) {
                    case "toString" -> "foreign " + vendorInterface.getSimpleName() + " (not an ICM type)";
                    case "hashCode" -> System.identityHashCode(instance);
                    case "equals" -> instance == (args == null || args.length == 0 ? null : args[0]);
                    default -> throw new UnsupportedOperationException("the foreign-type fake must never have "
                            + method.getName() + "() called on it: the assertion is that the adapter rejects"
                            + " this object by TYPE, before any vendor call can reach it");
                });
        return vendorInterface.cast(proxy);
    }

    /**
     * A datastore that answers the READ entry points, so the real services can be driven end to end.
     *
     * <p>{@link FakeDatastore} deliberately throws from {@code datastoreDef()}/{@code policyMgmt()}: the
     * lifecycle suites must never be able to wander into a read. This one is the opposite seam, used by the
     * classification-provenance and session-poisoning suites, where the point is to run the production
     * mapping code over a fake vendor object.
     *
     * <p>Every read entry point is counted, so a test can assert what was asked for rather than only what
     * came back.
     */
    static final class FakeReadDatastore implements IcmDatastore {

        /** Records every read entry point in order. */
        final List<String> calls = new ArrayList<>();

        private final AtomicInteger isConnectedCalls = new AtomicInteger();
        private final AtomicInteger disconnectCalls = new AtomicInteger();
        private final AtomicInteger destroyCalls = new AtomicInteger();

        private volatile com.ibm.mm.sdk.common.dkDatastoreDef definition;
        private volatile com.ibm.mm.sdk.common.dkDatastoreAdmin admin;
        private volatile com.ibm.mm.sdk.common.DKPolicyMgmtICM policyMgmt;
        private volatile RuntimeException definitionFailure;
        private volatile RuntimeException destroyFailure;
        private volatile boolean connected = true;

        @Override
        public com.ibm.mm.sdk.common.dkDatastoreDef datastoreDef() {
            calls.add("datastoreDef");
            RuntimeException failure = definitionFailure;
            if (failure != null) {
                throw failure;
            }
            return definition;
        }

        @Override
        public com.ibm.mm.sdk.common.dkDatastoreAdmin datastoreAdmin() {
            calls.add("datastoreAdmin");
            return admin;
        }

        @Override
        public com.ibm.mm.sdk.common.DKPolicyMgmtICM policyMgmt() {
            calls.add("policyMgmt");
            return policyMgmt;
        }

        @Override
        public boolean isConnected() {
            calls.add("isConnected");
            isConnectedCalls.incrementAndGet();
            return connected;
        }

        @Override
        public void disconnect() {
            calls.add("disconnect");
            disconnectCalls.incrementAndGet();
            connected = false;
        }

        @Override
        public void destroy() {
            calls.add("destroy");
            destroyCalls.incrementAndGet();
            RuntimeException failure = destroyFailure;
            if (failure != null) {
                throw failure;
            }
        }

        void setDefinition(com.ibm.mm.sdk.common.dkDatastoreDef value) {
            this.definition = value;
        }

        void setAdmin(com.ibm.mm.sdk.common.dkDatastoreAdmin value) {
            this.admin = value;
        }

        void setPolicyMgmt(com.ibm.mm.sdk.common.DKPolicyMgmtICM value) {
            this.policyMgmt = value;
        }

        void failDefinitionWith(RuntimeException failure) {
            this.definitionFailure = failure;
        }

        void onDestroyThrow(RuntimeException failure) {
            this.destroyFailure = failure;
        }

        void setConnected(boolean value) {
            this.connected = value;
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
    }

    /**
     * A datastore definition whose ItemTypes the test chooses.
     *
     * <p>Extends the SDK class rather than implementing the interface because the adapter's mandatory cast
     * is to {@code DKDatastoreDefICM}: a fake that implemented only {@code dkDatastoreDef} would be
     * rejected by the adapter's own wrong-type guard - which is asserted separately, on purpose.
     */
    static final class FakeDatastoreDef extends com.ibm.mm.sdk.common.DKDatastoreDefICM {

        private static final long serialVersionUID = 1L;

        /** Records the scope of every listing call. */
        final List<Integer> listScopes = new ArrayList<>();

        private final List<String> names = new ArrayList<>();
        private final java.util.Map<String, com.ibm.mm.sdk.common.dkEntityDef> entities =
                new java.util.HashMap<>();

        FakeDatastoreDef() {
            super(null);
        }

        void add(com.ibm.mm.sdk.common.DKItemTypeDefICM itemType) {
            String name = itemType.getName();
            names.add(name);
            entities.put(name, itemType);
        }

        @Override
        public String[] listEntityNames(int scope) {
            listScopes.add(scope);
            return names.toArray(new String[0]);
        }

        @Override
        public com.ibm.mm.sdk.common.dkEntityDef retrieveEntity(String entityName) {
            return entities.get(entityName);
        }

        @Override
        public String[] listEntityNames() {
            return names.toArray(new String[0]);
        }
    }

    /**
     * One ItemType with every accessor the adapter's mapping reads, so the production mapping code runs
     * unchanged.
     *
     * <p>The SDK stub's own accessors throw {@link UnsupportedOperationException}, so every one of them is
     * overridden here. The list is not a guess: it is every accessor {@code CmMetadataService.map()} reads.
     */
    static final class FakeItemTypeDef extends com.ibm.mm.sdk.common.DKItemTypeDefICM {

        private static final long serialVersionUID = 1L;

        private final String name;
        private String description = "fake item type description";
        private int intId = 1;
        private short classification;
        private short versionControl;
        private short versioningType;
        private short defaultRmCode;
        private short defaultCollCode;
        private int xdoClassId;
        private String xdoClassName = "";
        private int defaultItemRetention;
        private short defaultRetentionUnit;
        private String retentionPolicyName = "";
        private Exception retentionPolicyNameFailure;

        FakeItemTypeDef(String name) {
            this.name = name;
        }

        FakeItemTypeDef id(int value) {
            this.intId = value;
            return this;
        }

        FakeItemTypeDef classification(short value) {
            this.classification = value;
            return this;
        }

        FakeItemTypeDef versionControl(short value) {
            this.versionControl = value;
            return this;
        }

        FakeItemTypeDef versioningType(short value) {
            this.versioningType = value;
            return this;
        }

        FakeItemTypeDef retentionPolicyName(String value) {
            this.retentionPolicyName = value;
            return this;
        }

        /**
         * Makes the retention-policy-name getter throw the given failure, which is the SDK's own declared
         * path: {@code getItemTypeRetentionPolicyName()} declares {@code DKException, Exception}, so both a
         * checked vendor failure and an unchecked one can come out of it.
         */
        FakeItemTypeDef failRetentionPolicyNameWith(Exception failure) {
            this.retentionPolicyNameFailure = failure;
            return this;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public int getIntId() {
            return intId;
        }

        @Override
        public short getClassification() {
            return classification;
        }

        @Override
        public short getVersionControl() {
            return versionControl;
        }

        @Override
        public short getVersioningType() {
            return versioningType;
        }

        @Override
        public short getDefaultRMCode() {
            return defaultRmCode;
        }

        @Override
        public short getDefaultCollCode() {
            return defaultCollCode;
        }

        @Override
        public int getXDOClassID() {
            return xdoClassId;
        }

        @Override
        public String getXDOClassName() {
            return xdoClassName;
        }

        @Override
        public int getDefaultItemRetention() {
            return defaultItemRetention;
        }

        @Override
        public short getDefaultRetentionUnit() {
            return defaultRetentionUnit;
        }

        @Override
        public String getItemTypeRetentionPolicyName() throws com.ibm.mm.sdk.common.DKException, Exception {
            Exception failure = retentionPolicyNameFailure;
            if (failure != null) {
                throw failure;
            }
            return retentionPolicyName;
        }

        @Override
        public String toString() {
            return "FakeItemTypeDef[" + name + "]";
        }
    }
}
