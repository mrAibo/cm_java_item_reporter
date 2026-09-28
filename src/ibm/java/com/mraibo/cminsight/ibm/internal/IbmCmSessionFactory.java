package com.mraibo.cminsight.ibm.internal;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.connection.CmSession;
import com.mraibo.cminsight.connection.CreationFailure;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.core.CmSessionFactory;
import com.mraibo.cminsight.ibm.CmAdapterSettings;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The adapter's resource source: {@link CmSessionFactory} for the core seam and, through it, the resource
 * the core's {@code BoundedPool} is filled from.
 *
 * <h2>Who owns the pool</h2>
 *
 * <p>The CORE builds the pool, not the adapter. It wraps this factory in its own
 * {@code ResourceFactory}, eagerly initializes a {@code BoundedPool<CmSession>} of
 * {@code cm.pool.size} resources, and only then asks the adapter for its read services over that pool. The
 * consequence is the one that matters: every session the application can ever obtain is created by this
 * factory and accounted for by that pool, so "no session outside the pool" is structural rather than a
 * rule somebody has to remember. There is deliberately no {@code connect()} here that a caller could
 * reach directly, and no emergency connection anywhere in the adapter.
 *
 * <h2>Credentials</h2>
 *
 * <p>Only the two CM credentials are resolved - never the JDBC pair - because ItemType and retention reads
 * never open the JDBC side, and demanding a database password to list ItemTypes would make a read feature
 * depend on configuration it does not use. The values become local variables for the duration of one
 * connect attempt; they are never stored on this object, never logged and never embedded in a message.
 * {@link #toString()} prints the repository id only.
 *
 * <p>Reading the credentials per attempt rather than once at construction is deliberate: a credential that
 * is removed between activation and a later session open fails THAT borrow, instead of the adapter silently
 * reusing a value that no longer exists. The core additionally resolves the CM credentials before any
 * resource is allocated, so the common misconfiguration fails activation rather than a request.
 *
 * <h2>Uncertain creation is reported, never assumed</h2>
 *
 * <p>Connecting is a two-step physical operation: allocate a {@code DKDatastoreICM}, then connect it. An
 * exception therefore does NOT prove that nothing was allocated, and the cleanup of a half-built session can
 * itself fail. This class is the only place that knows which of the two happened, so it is the only place
 * that can report it: {@link IbmCmConnectionFactory} returns an {@link IbmCmCleanupFailure} exactly when a
 * teardown step did not return normally, and only then is the attempt reported as
 * {@link CreationFailure.Cleanup#UNPROVEN}, which makes the pool quarantine the reserved capacity slot. Any
 * other failure has already attempted cleanup and observed it succeed, so it reports
 * {@link CreationFailure.Cleanup#PROVEN_CLEAN} and the slot is released.
 *
 * <h2>Health</h2>
 *
 * <p>{@link #isHealthy(CmSession)} asks {@link IbmCmSession#isHealthy()}, which is one volatile read. It
 * must stay that way: the pool calls it while holding its lock, on the borrow path, the return path and
 * during a rotation sweep, and {@code closeState()} takes the same lock.
 */
public final class IbmCmSessionFactory implements CmSessionFactory, AdapterErrorSink {

    private final RepositoryProfile profile;
    private final CmAdapterSettings settings;
    private final IbmCmConnectionFactory connections;

    /**
     * Sessions created by this factory, for the diagnostics age reading.
     *
     * <p>Kept here rather than in the diagnostics class because only this class ever creates a session, so it
     * is the only place that can record the moment one went live. Values are mutable stamps so liveness can
     * be recorded from a lock-free read without any collection mutator - see
     * {@link IbmCmPoolDiagnostics#oldestSessionAge()}.
     *
     * <p>Volatile and replaced rather than mutated: compaction publishes a fresh map in one write, so a
     * concurrent reader sees either the old complete map or the new one.
     */
    private volatile ConcurrentHashMap<IbmCmSession, IbmCmPoolDiagnostics.SessionStamp> stamps =
            new ConcurrentHashMap<>();

    /** Serialises compaction. The read path and {@link #create()} do not take it. */
    private final Object stampLock = new Object();

    private final AtomicBoolean lastAttemptLeftResources = new AtomicBoolean(false);
    private volatile String lastAdapterError = "";

    /**
     * @param profile     the repository this factory is bound to
     * @param settings    the validated pool bounds and the credential resolver
     * @param connections where physical datastores come from; the SDK source in production, a fake under
     *                    test
     */
    public IbmCmSessionFactory(RepositoryProfile profile,
                               CmAdapterSettings settings,
                               IbmCmConnectionFactory connections) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * The production factory: the real SDK, and the real credential resolver.
     *
     * <p>This is the constructor an adapter provider uses. The injectable one exists for the test suite,
     * because a real IBM CM 8.7 server is not available to CI and a lifecycle this important must be
     * exercised deterministically - including the cases a live server cannot be asked to produce on demand,
     * such as a {@code destroy()} that fails.
     */
    public IbmCmSessionFactory(RepositoryProfile profile, CmAdapterSettings settings) {
        this(profile, settings, IbmCmConnectionFactory.SDK);
    }

    /**
     * {@link CmSessionFactory}: opens one session for the repository this factory was built for.
     *
     * <p>The profile argument is accepted because the interface declares it, and it is verified to be the
     * same repository rather than silently ignored, so a caller cannot connect this factory to a different
     * SSID and believe it worked.
     */
    @Override
    public CmSession open(RepositoryProfile requested) throws Exception {
        if (requested != null && !profile.id().equals(requested.id())) {
            throw new IbmCmFailure("cm", "this session factory is bound to repository '" + profile.id()
                    + "' but was asked to open '" + requested.id() + "'", null, false);
        }
        return create();
    }

    /**
     * Creates one physical session.
     *
     * <p>Called by the core's pool, which is the only caller that can account for the outcome. The failure
     * type is the whole point: {@link CreationFailure} is how the pool learns whether the reserved capacity
     * slot may come back.
     */
    public CmSession create() throws Exception {
        validateProfile();
        IbmCmCredentials credentials = resolveCredentials();
        try {
            // The credential values live only in this call's argument list. Nothing here stores, logs or
            // formats them, and no failure message below can contain them.
            IcmDatastore handle = connections.connect(profile.ssid(), credentials.user(), credentials.password());
            IbmCmSession session = IbmCmSession.live(profile.id(), handle, this);
            stamps.put(session, new IbmCmPoolDiagnostics.SessionStamp(System.nanoTime()));
            lastAttemptLeftResources.set(false);
            return session;
        } catch (IbmCmCleanupFailure cleanupUnproven) {
            lastAttemptLeftResources.set(true);
            recordAdapterError(cleanupUnproven.getMessage());
            throw new CreationFailure(CreationFailure.Cleanup.UNPROVEN,
                    "could not open a CM session for repository '" + profile.id() + "': "
                            + cleanupUnproven.getMessage(), cleanupUnproven);
        } catch (IbmCmFailure failure) {
            lastAttemptLeftResources.set(false);
            recordAdapterError(failure.getMessage());
            throw new CreationFailure(CreationFailure.Cleanup.PROVEN_CLEAN,
                    "could not open a CM session for repository '" + profile.id() + "': "
                            + failure.getMessage(), failure);
        }
    }

    /**
     * Narrows a lease handed out by the pool to the adapter's own session type.
     *
     * <p>Fails loudly rather than casting blindly: a foreign session inside this pool would mean somebody
     * substituted a resource source, and the one thing that must never happen is the adapter issuing SDK
     * calls against a session it does not own.
     */
    static IbmCmSession icmSession(Lease<CmSession> lease) {
        CmSession session = lease.value();
        if (session instanceof IbmCmSession icmSession) {
            return icmSession;
        }
        throw new IbmCmFailure("cm", "the pool handed out a session this adapter does not own ("
                + (session == null ? "null" : session.getClass().getSimpleName()) + ")", null, true);
    }

    /**
     * The cheap liveness probe the core's pool calls while holding its lock.
     *
     * <p>Only an {@link IbmCmSession} can be healthy here; anything else is a programming error and is
     * reported as unhealthy rather than assuming a stranger is alive. The probe itself is one volatile read
     * inside the session - see {@link IbmCmSession#isHealthy()}.
     */
    public boolean isHealthy(CmSession session) {
        return session instanceof IbmCmSession icmSession && icmSession.isHealthy();
    }

    /** True when the most recent creation attempt could not prove it released everything. */
    public boolean lastAttemptLeftResources() {
        return lastAttemptLeftResources.get();
    }

    /** The validated settings this factory was built from, for cache construction and diagnostics. */
    public CmAdapterSettings settings() {
        return settings;
    }

    /** The repository id this factory is bound to. Never a credential. */
    public String repositoryId() {
        return profile.id();
    }

    /** The SSID of the bound repository, for diagnostics of the ACTIVE repository only. */
    public String ssid() {
        return profile.ssid();
    }

    /** The live-session stamps this factory has recorded, for the age reading. */
    ConcurrentHashMap<IbmCmSession, IbmCmPoolDiagnostics.SessionStamp> sessionStamps() {
        return stamps;
    }

    /**
     * Publishes a compacted stamp map, if it is still safe to do so.
     *
     * <p>Called by {@link IbmCmPoolDiagnostics} after it has walked the stamps and marked the finished ones.
     * The swap is rejected when sessions were created concurrently, because those creations landed in the map
     * being replaced and dropping them would lose live sessions from the age reading. Rejecting is the safe
     * direction: the map is simply compacted on the next read instead.
     *
     * @param compacted the replacement map, containing only stamps observed live
     */
    void compactStampsWith(ConcurrentHashMap<IbmCmSession, IbmCmPoolDiagnostics.SessionStamp> compacted) {
        if (compacted == null) {
            return;
        }
        synchronized (stampLock) {
            ConcurrentHashMap<IbmCmSession, IbmCmPoolDiagnostics.SessionStamp> current = stamps;
            if (current.size() != compacted.size()) {
                // The set changed while the replacement was being built; keep what we have.
                return;
            }
            stamps = compacted;
        }
    }

    /**
     * Records the most recent sanitised adapter failure.
     *
     * <p>The argument is required to be already sanitised by {@link IbmErrorSanitizer}, and it is stored
     * verbatim: this class must never append anything of its own, or a repository name taken from a vendor
     * message could reappear one concatenation later.
     */
    @Override
    public void recordAdapterError(String sanitisedFailure) {
        if (sanitisedFailure != null && !sanitisedFailure.isBlank()) {
            lastAdapterError = sanitisedFailure;
        }
    }

    /** The last sanitised failure, or empty. Never a vendor message verbatim. */
    public String lastAdapterErrorText() {
        return lastAdapterError;
    }

    /** Prints the repository id and the connection source for logs; never a credential. */
    public String describe() {
        return "IbmCmSessionFactory[repository=" + profile.id() + ", connections=" + connections + "]";
    }

    @Override
    public String toString() {
        return describe();
    }

    // ---------------------------------------------------------------- internals

    /**
     * Refuses an activation whose SSID is blank before anything is allocated.
     *
     * <p>{@link RepositoryProfile} already requires an SSID, so this is a second, cheap check at the point
     * of use rather than an assumption about a caller. A blank SSID must never reach {@code connect()}: the
     * SDK's behaviour for it is unspecified, and "unspecified" is not a state a fail-closed lifecycle can
     * handle.
     */
    private void validateProfile() {
        String ssid = profile.ssid();
        if (ssid == null || ssid.isBlank()) {
            throw new IbmCmFailure("cm", "repository '" + profile.id()
                    + "' has a blank SSID; refusing to connect", null, false);
        }
    }

    /**
     * Resolves the two CM credentials, fails closed.
     *
     * <p>{@code resolveCmCredentials} raises a configuration exception naming the field and the source when a
     * credential is unconfigured or unreadable, and it never returns a partial value. That exception is
     * translated into an {@link IbmCmFailure} whose message is the configuration text - which names a config
     * key and a source, never a value - so the failure travels through the same sanitised path as any SDK
     * error.
     */
    private IbmCmCredentials resolveCredentials() {
        try {
            RepositoryProfile.CmCredentials resolved = profile.resolveCmCredentials(settings.secrets());
            String user = settings.secrets().resolve(resolved.cmUser());
            String password = settings.secrets().resolve(resolved.cmPassword());
            if (user == null || user.isBlank() || password == null || password.isEmpty()) {
                // A configured-but-empty value is not a usable credential. Report it without echoing it.
                throw new IbmCmFailure("cm", "repository '" + profile.id()
                        + "' resolved an empty CM credential; refusing to connect", null, false);
            }
            return new IbmCmCredentials(user, password);
        } catch (IbmCmFailure failure) {
            throw failure;
        } catch (RuntimeException configurationFailure) {
            throw new IbmCmFailure("configuration",
                    "repository '" + profile.id() + "': " + configurationFailure.getMessage(),
                    configurationFailure, false);
        }
    }
}
