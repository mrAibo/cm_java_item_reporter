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
 * <p>That reading applies AT AND BELOW the allocation boundary - the
 * {@link IbmCmConnectionFactory#connect} call, see {@link #createFor(RepositoryProfile)}. ABOVE it this
 * class reports {@code PROVEN_CLEAN} too, for a stronger reason: nothing physical exists yet, so the attempt
 * is provably empty. Both of those pre-boundary paths are reachable - a blank SSID, and a CM credential that
 * cannot be resolved - and neither may cost the pool a capacity slot. The claim is made per path and never by
 * a catch-all: a failure type this class does not recognise crosses {@code create()} untyped and keeps the
 * pool's conservative quarantine default.
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
     * SSID and believe it worked. That verification happens inside the attempt skeleton below, so a rejected
     * request reports a cleanup verdict like every other pre-allocation failure instead of an untyped
     * failure that would quarantine the pool's reserved slot.
     */
    @Override
    public CmSession open(RepositoryProfile requested) throws Exception {
        return createFor(requested);
    }

    /**
     * Creates one physical session.
     *
     * <p>Called by the core's pool, which is the only caller that can account for the outcome. The failure
     * type is the whole point: {@link CreationFailure} is how the pool learns whether the reserved capacity
     * slot may come back.
     */
    public CmSession create() throws Exception {
        return createFor(null);
    }

    /**
     * One creation attempt, from the reservation the pool made to the verdict it needs.
     *
     * <h2>Every failure path states its verdict, and the allocation boundary decides it</h2>
     *
     * <p>Since Goal 02A {@code BoundedPool} releases a reserved capacity slot <strong>only</strong> for an
     * explicit {@link CreationFailure.Cleanup#PROVEN_CLEAN}; every untyped failure - a plain exception, a
     * runtime exception, an {@link Error} - quarantines it instead. That default is right for a failure
     * whose physical outcome is unknown, but it is wrong for the failures this class can prove allocated
     * nothing, so those paths must say so explicitly:
     *
     * <ul>
     *   <li>a request for a different repository, and a blank SSID - both refused BEFORE anything physical
     *       exists;</li>
     *   <li>a CM credential that cannot be resolved - also BEFORE anything physical exists, and reachable
     *       in NORMAL OPERATION: the adapter deliberately re-resolves credentials for every replacement
     *       session, so a secret removed after activation fails that replacement. Reported as an untyped
     *       failure, this would quarantine an EMPTY slot and permanently cost the pool one CM session,
     *       which is the Goal 02B regression this structure exists to close;</li>
     *   <li>a connect failure whose cleanup the connection layer proved.</li>
     * </ul>
     *
     * <p>The boundary is the {@link IbmCmConnectionFactory#connect} call, and it is the only thing that
     * decides the verdict - never the failure's type alone. Above the boundary nothing physical can exist
     * for this attempt, so it is provably clean. At and below it the physical outcome is unknown unless the
     * connection layer produces its own evidence: {@link IbmCmFailure} means cleanup was attempted and
     * {@code destroy()} returned normally, {@link IbmCmCleanupFailure} means it did not. A failure this
     * method does not recognise is deliberately left untyped, so the pool's conservative default quarantines
     * the slot: relabelling an unknown connection-layer outcome as clean would authorise a replacement
     * beside a session that may still be alive, which is the one mistake Goal 02A exists to prevent.
     *
     * <h2>The fatal-{@code Error}-before-allocation decision, on the record</h2>
     *
     * <p>An {@link Error} raised above the boundary - a JVM-level failure while validating the request or
     * resolving credentials - deliberately stays UNTYPED and therefore quarantines the reserved slot, even
     * though this class knows nothing was allocated. Two reasons, the first binding:
     *
     * <ol>
     *   <li>an {@code Error} must propagate unchanged. Reporting a clean verdict would require wrapping it in
     *       the checked {@link CreationFailure}, which swallows {@code OutOfMemoryError},
     *       {@code StackOverflowError} and their kin - and an {@code Error} means the JVM state is not
     *       trustworthy enough for any part of it to accept this class's "nothing was allocated" claim;</li>
     *   <li>it costs nothing an operator can cause: no ordinary validation or configuration failure is an
     *       {@code Error}. Both pre-boundary paths raise {@link IbmCmFailure}, a {@code RuntimeException},
     *       and those ARE reported clean. Ordinary failures are not allowed to burn capacity; this one may,
     *       and keeping it conservative is what leaves the Error contract intact.</li>
     * </ol>
     *
     * @param requested the repository the caller asked for; {@code null} from {@link #create()}
     */
    private CmSession createFor(RepositoryProfile requested) throws Exception {
        // The attempt diagnostic must describe THIS attempt, so it is written exactly once - in the finally
        // below - from the outcome. It starts conservative because an attempt that ends in an untyped
        // failure or an Error, whose physical outcome this class cannot see, has not proved it left nothing
        // behind; both reported verdicts and a successful return overwrite it with what actually happened.
        // A previous UNPROVEN attempt can therefore never leave it stale after a later known-clean
        // pre-allocation failure, which is the diagnostic defect Goal 02B calls out.
        boolean leftResources = true;
        try {
            CmSession session = attempt(requested);
            // Success is proof too: the physical session leaves this method inside the caller's lease, so
            // this attempt left nothing unaccounted for.
            leftResources = false;
            return session;
        } catch (CreationFailure reported) {
            leftResources = reported.cleanup() == CreationFailure.Cleanup.UNPROVEN;
            throw reported;
        } finally {
            lastAttemptLeftResources.set(leftResources);
        }
    }

    /**
     * Runs one attempt and reports the cleanup verdict of a failure as a {@link CreationFailure}.
     *
     * <p>Split out so there is exactly one site that writes the attempt diagnostic, and so the allocation
     * boundary is visible in one method: everything above the {@code connections.connect(...)} call below is
     * provably before any physical resource for this attempt exists, and everything at or below it is not.
     */
    private CmSession attempt(RepositoryProfile requested) throws Exception {
        // ------------------------------------------------------------ BEFORE the allocation boundary
        if (requested != null && !profile.id().equals(requested.id())) {
            // Request validation, never a physical operation: the factory refuses to connect a different
            // repository's SSID, and the pool's reserved slot must come back.
            throw clean(new IbmCmFailure("cm", "this session factory is bound to repository '" + profile.id()
                    + "' but was asked to open '" + requested.id() + "'", null, false));
        }
        try {
            validateProfile();
        } catch (IbmCmFailure refusal) {
            throw clean(refusal);
        }
        final IbmCmCredentials credentials;
        try {
            credentials = resolveCredentials();
        } catch (IbmCmFailure refusal) {
            // The credential source disappeared or was never usable. Nothing was allocated, so this is a
            // known-clean failure and NOT allowed to consume a capacity slot.
            throw clean(refusal);
        }

        // ------------------------------------------------------------ AT AND BEYOND the boundary
        try {
            // The credential values live only in this call's argument list. Nothing here stores, logs or
            // formats them, and no failure message below can contain them.
            IcmDatastore handle = connections.connect(profile.ssid(), credentials.user(), credentials.password());
            IbmCmSession session = IbmCmSession.live(profile.id(), handle, this);
            stamps.put(session, new IbmCmPoolDiagnostics.SessionStamp(System.nanoTime()));
            return session;
        } catch (IbmCmCleanupFailure cleanupUnproven) {
            // A teardown step did not return normally, so a physical session may still exist: this attempt
            // must NOT claim a clean cleanup, and the pool quarantines the slot.
            throw reported(CreationFailure.Cleanup.UNPROVEN, cleanupUnproven);
        } catch (IbmCmFailure failure) {
            // The connection layer attempted cleanup and observed destroy() return normally, so the slot may
            // be released. Note what this is NOT: it is not a catch-all. A failure of any other type crosses
            // this method untyped and stays conservative in the pool.
            throw reported(CreationFailure.Cleanup.PROVEN_CLEAN, failure);
        }
    }

    /**
     * A failure that provably happened before the allocation boundary: nothing was allocated, so the pool's
     * reserved slot must come back.
     */
    private CreationFailure clean(IbmCmFailure refusal) {
        return reported(CreationFailure.Cleanup.PROVEN_CLEAN, refusal);
    }

    /**
     * The one place a failure becomes the pool's verdict.
     *
     * <p>Records the sanitised cause as the adapter diagnostic and builds the {@link CreationFailure} the pool
     * accounts for. The cause is always the original sanitised failure - never a wrapper, never raw vendor
     * text - and the message is that same sanitised text prefixed with the repository id, so an operator can
     * see which attempt failed and why without any credential being reproduced.
     */
    private CreationFailure reported(CreationFailure.Cleanup cleanup, Throwable cause) {
        String detail = messageOf(cause);
        recordAdapterError(detail);
        return new CreationFailure(cleanup,
                "could not open a CM session for repository '" + profile.id() + "': " + detail, cause);
    }

    /** The sanitised description of a failure: its message, or its type name when it carries none. */
    private static String messageOf(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
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
     *
     * <p>Runs before the allocation boundary, so its {@link IbmCmFailure} is reported as
     * {@link CreationFailure.Cleanup#PROVEN_CLEAN}: nothing was allocated, and the pool's reserved slot must
     * come back rather than be quarantined for a configuration mistake.
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
     *
     * <p>Runs before the allocation boundary, so every failure here is reported as
     * {@link CreationFailure.Cleanup#PROVEN_CLEAN}. That matters in normal operation, not only in theory: a
     * credential removed after a successful activation fails the NEXT replacement session, and this class
     * must not let that cost the pool a capacity slot for a physical resource it never created.
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
