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
