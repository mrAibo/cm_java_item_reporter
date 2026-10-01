package com.mraibo.cminsight.ibm;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CmSession;
import com.mraibo.cminsight.core.CmPoolDiagnostics;
import com.mraibo.cminsight.core.CmSessionFactory;
import com.mraibo.cminsight.metadata.MetadataRepository;
import com.mraibo.cminsight.retention.RetentionRepository;

import java.util.Objects;
import java.util.Optional;

/**
 * The seam that makes a vendor adapter optional: the core discovers one implementation through
 * {@link java.util.ServiceLoader}, asks it for a session factory, and never names a vendor type itself.
 *
 * <h2>Availability is explicit, and a silent pick is forbidden</h2>
 *
 * <p>Zero providers means a core-only build that can list repositories but cannot activate one. Exactly
 * one provider is the supported deployment. Two or more is an EXPLICIT failure
 * ({@link IbmCmAdapterRegistry.Availability#AMBIGUOUS}) rather than a first-one-wins choice: which vendor
 * a repository connects through is a security-relevant decision, and a class path that contains two
 * adapters must be resolved by an operator, not by iteration order. {@link IbmCmAdapterRegistry} reports
 * all three cases without ever surfacing a raw class-loading failure.
 *
 * <h2>Installed is not the same as ready</h2>
 *
 * <p>These provider classes are packaged in the SAME artifact as the core, so a build with no vendor JARs
 * still has a provider that loads and describes itself. "An adapter is installed" therefore says nothing
 * about whether a repository can be activated through it, and conflating the two is the defect
 * {@link #readiness()} exists to remove: a registry verdict of {@code AVAILABLE}, an API answer of
 * {@code available=true}, and then a class-loading failure several layers down at the moment of
 * activation - or, worse, a refusal whose message looks like legitimate core-only mode.
 *
 * <p>So the seam carries two independent answers: the declared provider values and
 * {@link #readiness()}, a cheap LOCAL statement about the vendor runtime. Activation follows readiness,
 * never installation.
 *
 * <h2>What a provider may expose</h2>
 *
 * <p>Only three things, all value-free: a stable id, the adapter build version and the advertised release
 * of the vendor API it talks to. None of them is a credential, and none of them is allowed to become one -
 * diagnostics print exactly these fields, so there is no field a secret could hide in.
 *
 * <h2>What the core never does</h2>
 *
 * <p>The core never opens a connection of its own, never creates a session outside a
 * {@link BoundedPool}, and never falls back to an ad-hoc or emergency connection when a provider is
 * missing. A missing, ambiguous or unloadable adapter fails activation with a clean status; it never
 * quietly degrades into "connect anyway".
 */
public interface CmAdapterProvider {

    /** A stable provider id, for example {@code ibm-cm-8.7}. Never a credential. */
    String providerId();

    /** The adapter build version. Never a credential. */
    String adapterVersion();

    /** The vendor API release the adapter talks to, for example {@code 8.7.0.000}; empty when unknown. */
    String sdkRelease();

    /**
     * The fixed reason published when an adapter does not answer {@link #readiness()}.
     *
     * <p>A constant sentence rather than an exception message: the registry only ever publishes text from
     * a fixed set, so a diagnostics page cannot be reached by an unexpected value.
     */
    String READINESS_NOT_REPORTED =
            "the adapter does not report whether its vendor runtime is ready to activate a repository";

    /**
     * Whether this adapter's vendor RUNTIME can activate a repository in this JVM right now.
     *
     * <h2>Two concepts, and this is the second one</h2>
     *
     * <p>"The adapter's classes are on the class path" and "the adapter can open a CM session" are
     * different facts, and only the second may decide activation. This method answers the second one, and
     * {@link IbmCmAdapterRegistry} refuses activation when the answer is negative - BEFORE any repository
     * manager, session factory or pool exists.
     *
     * <h2>What an implementation must and must not do</h2>
     *
     * <ul>
     *   <li><strong>Cheap and local.</strong> A class-visibility probe at most. No connection, no
     *       round trip, no credential and no I/O beyond what the class loader already holds. A readiness
     *       check runs on a diagnostics read, so anything expensive would turn a status page into an
     *       outage.</li>
     *   <li><strong>Total.</strong> It must never throw. A missing vendor class, a half-visible vendor
     *       class path and a linkage error all mean the same thing to the caller - runtime not ready -
     *       and every one of them must become {@link Readiness#unavailable(String)} with a FIXED,
     *       sanitized reason. A raw {@code NoClassDefFoundError} escaping from here would turn a missing
     *       optional JAR into an unexplained process failure, which is exactly the defect this seam
     *       removes.</li>
     *   <li><strong>Value-free.</strong> {@link Readiness#reason()} ends up on an operator page: it names
     *       a missing dependency or a linkage problem, never a credential and never a raw vendor
     *       message.</li>
     * </ul>
     *
     * <h2>Why the default is "not ready" rather than "ready"</h2>
     *
     * <p>An adapter that does not answer has made no statement about its runtime, and the core may not
     * invent one: assuming readiness is fail-open, and it is the exact shape of the defect where a
     * completely unusable adapter advertised itself as available. The fail-closed default costs one
     * method in a new adapter's implementation and produces a clear, actionable refusal instead of a
     * failure at the first borrow.
     */
    default Readiness readiness() {
        return Readiness.unavailable(READINESS_NOT_REPORTED);
    }

    /**
     * The session factory for one activation.
     *
     * <p>Called once per activation attempt, before any pool exists. The returned factory is the resource
     * source of the bounded pool the core builds, so every session the application ever uses comes from
     * that pool - there is no other path to a connection.
     *
     * <p>Implementations resolve the credentials they need from the profile with
     * {@link RepositoryProfile#resolveCmCredentials(com.mraibo.cminsight.config.SecretResolver)} and the
     * resolver in {@link CmAdapterSettings#secrets()}, and must never log, print or embed a value.
     *
     * @throws Exception when the factory itself cannot be built; the core turns that into a clean
     *         activation failure, not a published context
     */
    CmSessionFactory sessionFactory(RepositoryProfile profile, CmAdapterSettings settings);

    /**
     * The read services and pool diagnostics of one activation, built over the pool the core created.
     *
     * <p>This is an optional capability with a safe default, not a fifth mandatory method: an adapter that
     * implements only the session seam keeps compiling and is still reported honestly, as a repository
     * whose ItemType and retention reads are unavailable. The core calls it AFTER the pool is initialized
     * and hands the pool over, because the read services borrow their sessions from it - that is what keeps
     * "no session is created outside the pool for normal application work" true for reads as well.
     *
     * <p>An implementation must not retain a session across a call and must not open a connection of its
     * own; {@link com.mraibo.cminsight.connection.Lease#close()} returns the borrow, and the pool decides
     * whether the session is reused or retired.
     *
     * @param context the profile, the settings and the pool this activation owns
     */
    default AdapterServices services(AdapterContext context) {
        return AdapterServices.NONE;
    }

    /**
     * The adapter's own answer to "can I activate a repository in this JVM", and the reason when it cannot.
     *
     * <p>Two things are deliberately NOT part of this answer:
     *
     * <ul>
     *   <li><strong>Nothing has been connected.</strong> Readiness is asked before any repository is
     *       selected, so {@link #ready()} means "my vendor runtime is loadable", never "the server
     *       answered". Reachability is discovered by an ordinary activation later, and pretending
     *       otherwise here would make a diagnostics read depend on the network.</li>
     *   <li><strong>No credential, and no vendor text.</strong> {@link #reason()} is published as an
     *       operator-facing sentence. It names a missing dependency or a linkage problem, as fixed text
     *       produced by the adapter - never a secret, never an SDK exception message, never a stack
     *       trace.</li>
     * </ul>
     *
     * @param ready  true when this adapter can build sessions against its vendor runtime in this JVM
     * @param reason why it cannot, as one fixed sanitized sentence; empty when {@code ready} is true
     */
    record Readiness(boolean ready, String reason) {

        /** The one shared ready answer. */
        public static final Readiness READY = new Readiness(true, "");

        public Readiness {
            reason = reason == null ? "" : reason.trim();
        }

        /**
         * Not ready, with a fixed value-free reason.
         *
         * @param reason the adapter's own sentence; a blank or absent one is replaced by the documented
         *               {@link CmAdapterProvider#READINESS_NOT_REPORTED} text, so the published reason is
         *               never empty
         */
        public static Readiness unavailable(String reason) {
            return new Readiness(false, reason == null || reason.isBlank() ? READINESS_NOT_REPORTED : reason);
        }

        /** A one-line description for diagnostics, a banner or a refusal message. */
        public String describe() {
            return ready ? "ready" : "not ready: " + reason;
        }

        @Override
        public String toString() {
            return "CmAdapterReadiness[" + describe() + "]";
        }
    }

    /**
     * What an adapter needs in order to build its services: the profile, the settings and the initialized
     * CM session pool it must borrow from.
     *
     * @param profile  the repository being activated
     * @param settings the validated bounds and the credential resolver
     * @param pool     the bounded pool this activation owns; the only source of sessions
     */
    record AdapterContext(RepositoryProfile profile, CmAdapterSettings settings, BoundedPool<CmSession> pool) {

        public AdapterContext {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(pool, "pool");
        }
    }

    /**
     * The read services of one activation. Every component is optional, so an adapter may support metadata
     * reads without retention, or neither.
     *
     * @param metadata    the read-only ItemType service, or {@code null} when the adapter has none
     * @param retention   the read-only retention service, or {@code null} when the adapter has none
     * @param diagnostics the CM pool diagnostics, or {@code null} when the adapter reports none - in which
     *                    case the core publishes its own pool-derived diagnostics rather than none at all
     */
    record AdapterServices(MetadataRepository metadata,
                           RetentionRepository retention,
                           CmPoolDiagnostics diagnostics) {

        /** An adapter that provides no read services at all. */
        public static final AdapterServices NONE = new AdapterServices(null, null, null);

        public Optional<MetadataRepository> metadataService() {
            return Optional.ofNullable(metadata);
        }

        public Optional<RetentionRepository> retentionService() {
            return Optional.ofNullable(retention);
        }

        public Optional<CmPoolDiagnostics> diagnosticsService() {
            return Optional.ofNullable(diagnostics);
        }

        /** True when the adapter provides nothing at all, so the core publishes its own diagnostics. */
        public boolean empty() {
            return metadata == null && retention == null && diagnostics == null;
        }
    }
}
