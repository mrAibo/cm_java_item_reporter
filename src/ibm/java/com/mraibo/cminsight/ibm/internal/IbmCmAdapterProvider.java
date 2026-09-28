package com.mraibo.cminsight.ibm.internal;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.AppPaths;
import com.mraibo.cminsight.config.ClassificationRules;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CmSession;
import com.mraibo.cminsight.core.CmSessionFactory;
import com.mraibo.cminsight.ibm.CmAdapterProvider;
import com.mraibo.cminsight.ibm.CmAdapterSettings;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * The registered IBM CM 8.7 adapter provider: the one class {@link java.util.ServiceLoader} instantiates,
 * and therefore the entry point of everything else in this source set.
 *
 * <h2>Registration</h2>
 *
 * <p>This class is named by
 * {@code src/ibm/resources/META-INF/services/com.mraibo.cminsight.ibm.CmAdapterProvider}, which the build
 * copies into {@code build/ibm-classes} and packages into the same jar as the adapter's classes. That file
 * is the whole wiring, and its location matters: it lives in the OPTIONAL source set's resources, never in
 * {@code src/main/resources}, because a registration under the core's resources would put a reference to an
 * optional adapter on the core class path - the exact isolation rule the build and the committed source
 * guard enforce.
 *
 * <h2>Nothing here may throw from a static initialiser</h2>
 *
 * <p>A provider that fails while the class is being initialised would surface as a
 * {@code ServiceConfigurationError} inside {@code ServiceLoader}, which
 * {@code IbmCmAdapterRegistry} correctly reports as {@code UNAVAILABLE} - but the operator then sees
 * "not usable" with no way to tell a missing vendor jar from a programming error. So this class has no
 * static state that can fail: every field is on the instance, every optional lookup is caught, and an
 * unreadable manifest degrades to an empty release string rather than an exception.
 *
 * <h2>What the adapter declares about itself</h2>
 *
 * <p>Only three value-free strings, and they are the only free text this class ever produces:
 * {@link #providerId()}, {@link #adapterVersion()} and {@link #sdkRelease()}. None is a credential, and
 * {@link #toString()} shows nothing else.
 *
 * <h2>Why this class is not a second pool owner</h2>
 *
 * <p>{@link #services(AdapterContext)} receives the pool the core built, already initialized and already
 * registered as an owned resource of the repository context. This provider never creates a session itself
 * and never stores one: it only builds the read services over the pool it was handed, which is what keeps
 * "no session outside the bound" true. The single exception is {@link #sessionFactory}, and it returns a
 * recipe for a session rather than a session.
 */
public final class IbmCmAdapterProvider implements CmAdapterProvider {

    /** The stable provider id used by diagnostics and by the acceptance test. */
    public static final String PROVIDER_ID = "ibm-cm-8.7";

    /**
     * The advertised adapter build version.
     *
     * <p>A fixed string rather than a manifest read of this project's own package: the adapter is part of
     * the application's build and shares its versioning, so a value read from a third-party manifest would
     * describe the wrong artifact. It exists so diagnostics can distinguish two adapter builds, exactly as
     * {@code CmAdapterProvider#adapterVersion()} requires.
     */
    public static final String ADAPTER_VERSION = "0.1.0-SNAPSHOT";

    /**
     * The CM API release as reported by the SDK jar's own manifest, or empty when it cannot be read.
     *
     * <h2>Why the manifest and not the SDK</h2>
     *
     * <p>The release has to be reportable while nothing is connected - diagnostics ask "which CM SDK is this
     * process running" long before any repository is activated - so it cannot come from a session. Reading a
     * manifest attribute is a JDK-only operation that needs no SDK class on the class path and no network
     * round trip, and it reports what IBM actually shipped rather than a string this project guessed.
     *
     * <p>Resolution order is deliberate: the SDK jar is located by its own class resource (the most precise
     * answer), and only if that yields nothing does the code fall back to the first {@code META-INF/MANIFEST.MF}
     * visible on the class path. Every step is wrapped; an unreadable or absent value degrades to empty, which
     * {@code Status#sdkReleaseOrUnknown()} renders as {@code unknown}.
     */
    private volatile String sdkRelease;

    /**
     * True once the release lookup has run, so a miss is not retried on every diagnostics read.
     */
    private volatile boolean sdkReleaseResolved;

    /**
     * Whether the SDK's own entry-point class is loadable, resolved once.
     *
     * <p>This adapter's classes name no {@code com.ibm} type, so a provider can be discovered and reported
     * {@code AVAILABLE} on a class path where the vendor jars are absent - the registration is in the jar, the
     * SDK is not. Discovery is not the place to fix that (the registry's job is to report what is registered),
     * but an ACTIVATION failure is, and the honest message is "the SDK is not on the class path" rather than a
     * generic failure.
     *
     * <p>The probe is a class lookup, not a class initialisation, so it does not load the SDK: probing must not
     * make this adapter's own discovery depend on the vendor jars. A negative result is cached, because the
     * class path does not change while the process runs.
     */
    private volatile boolean sdkPresentResolved;
    private volatile boolean sdkPresent;

    /**
     * The factory created by {@link #sessionFactory}, reused by {@link #services}.
     *
     * <p>The two methods are called at different moments of one activation - the factory before the pool
     * exists, the services after it is initialized - and they must agree, because the factory is what records
     * each session's creation time for the diagnostics age reading. Holding the last one is safe here because
     * a provider instance is created per activation and activations are serialised by the repository manager;
     * a stale entry is harmless in any case, since {@link #services} simply builds a fresh factory rather
     * than failing.
     */
    private volatile IbmCmSessionFactory sessionFactory;

    /** Cached classification rules, or {@code null} for the documented fallback label. Resolved lazily. */
    private volatile ClassificationRules classificationRules;

    private volatile boolean classificationRulesResolved;

    /**
     * The service registration needs a public no-argument constructor, and it must not do any work: a
     * {@code ServiceLoader} instantiation happens during discovery, before any repository is chosen, and
     * anything expensive or fallible there would make "is an adapter installed" depend on the SDK.
     */
    public IbmCmAdapterProvider() {
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String adapterVersion() {
        return ADAPTER_VERSION;
    }

    @Override
    public String sdkRelease() {
        if (!sdkReleaseResolved) {
            sdkRelease = readSdkRelease();
            sdkReleaseResolved = true;
        }
        String release = sdkRelease;
        return release == null ? "" : release;
    }

    /**
     * The session factory for one activation.
     *
     * <p>Returns the {@link CmSessionFactory} the core wraps in its own resource factory, so the pool that
     * ends up owning every session is filled by this object's {@link IbmCmSessionFactory#create()} - which is
     * where an unproven creation cleanup becomes {@code CreationFailure(UNPROVEN)} and quarantines a slot.
     *
     * <p>Note what this does NOT do: it opens nothing. No connection is attempted until the core's pool asks
     * for its first resource, so a repository that is selected but never used costs nothing.
     */
    @Override
    public CmSessionFactory sessionFactory(RepositoryProfile profile, CmAdapterSettings settings) {
        // Fail here, at the start of an activation, with a message that names the real problem. Letting the
        // pool discover a missing vendor jar would produce a class-loading failure several layers down, and an
        // operator reading "could not create and initialize its CM pool" cannot tell a missing SDK from a
        // server that refused the connection.
        if (!sdkPresent()) {
            throw new IbmCmFailure("sdk-missing",
                    "the IBM Content Manager SDK is not on the class path, so no repository can be activated;"
                            + " place the CM 8.7 SDK jars where the launcher looks for them",
                    null, false);
        }
        IbmCmSessionFactory factory = new IbmCmSessionFactory(profile, settings);
        sessionFactory = factory;
        return factory;
    }

    /**
     * True when the IBM CM SDK is loadable from this JVM's class path.
     *
     * <p>Exposed for diagnostics and for the smoke command's operator message. A false answer does not mean
     * this adapter is broken: it means the vendor jars the operator must supply are not on the class path, so
     * no repository can be activated through it.
     */
    public boolean sdkPresent() {
        if (!sdkPresentResolved) {
            sdkPresent = probeSdkPresence();
            sdkPresentResolved = true;
        }
        return sdkPresent;
    }

    /**
     * The read services and pool diagnostics of one activation, built over the pool the core owns.
     *
     * <p>Reached only after the pool is initialized, so a service built here can assume its sessions exist.
     * The returned services borrow from that pool for the duration of each call and release before
     * returning; none of them holds a session, and none opens a connection of its own.
     */
    @Override
    public AdapterServices services(AdapterContext context) {
        if (context == null) {
            return AdapterServices.NONE;
        }
        BoundedPool<CmSession> pool = context.pool();
        CmAdapterSettings settings = context.settings();

        IbmCmSessionFactory factory = sessionFactory;
        if (factory == null) {
            // The normal sequence always sets this first. Building one here rather than failing keeps a test
            // that calls services() directly working, at the cost of a session-age reading that starts empty.
            factory = new IbmCmSessionFactory(context.profile(), settings);
        }

        ClassificationRules classifications = classificationRules();
        return new AdapterServices(
                new CmMetadataService(pool, classifications),
                new CmRetentionService(pool),
                new IbmCmPoolDiagnostics(pool, settings, factory));
    }

    /**
     * The operator's configured ItemType classification rules, read once, or {@code null} when unavailable.
     *
     * <h2>Why the adapter reads this itself</h2>
     *
     * <p>{@code CmAdapterSettings} and {@link AdapterContext} carry the pool bounds, the cache TTL and the
     * secret resolver, but not the classification rules - so there is no hand-off to receive them through,
     * and {@code CmMetadataService} would otherwise have to label every ItemType with the fallback. Since
     * {@link RepositoryProfile} already gives the adapter a configuration-derived object, reading the rules
     * from the same application configuration is the consistent behaviour rather than a new dependency:
     * it is the same file, the same keys and the same {@link ClassificationRules} parser the launcher uses,
     * so the adapter cannot disagree with what {@code --print-config} reports.
     *
     * <p>The consequence is that {@code ItemTypeInfo.businessClassification} reflects the operator's real
     * rules. That is the point of the field: a business label no operator can configure would be worse than
     * the generic fallback, and hard-coding a rule set is forbidden outright.
     *
     * <p>Every failure degrades to {@code null} - which {@code CmMetadataService} renders as the documented
     * fallback label - because a classification rule set the adapter could not read must not stop a
     * read-only ItemType listing. The parse is lazy and cached: this runs at most once per provider, never
     * during discovery, and never on a request path.
     *
     * @return the configured rules, or {@code null} to use the fallback label
     */
    private ClassificationRules classificationRules() {
        if (!classificationRulesResolved) {
            classificationRules = loadClassificationRules();
            classificationRulesResolved = true;
        }
        return classificationRules;
    }

    private static ClassificationRules loadClassificationRules() {
        try {
            // The documented default location. The adapter cannot know whether the operator passed --config,
            // and AppPaths resolves a relative default against the application home exactly as the launcher
            // does, so this names the same file whenever the operator used the default configuration.
            Path configFile = AppPaths.resolve().configurationFile(null);
            if (configFile == null || !Files.isRegularFile(configFile)) {
                // No configuration file: nothing configured to read, so the fallback label is correct.
                return null;
            }
            return ClassificationRules.load(AppConfig.load(configFile));
        } catch (IOException | RuntimeException | Error unavailable) {
            // A rule set that cannot be read is not a reason to refuse a read-only ItemType listing; the
            // fallback label is the honest, documented answer. The failure is deliberately not logged here,
            // because this class has no logger and a diagnostics line is the operator-facing surface.
            return null;
        }
    }

    /**
     * Whether the SDK's own entry-point class can be loaded.
     *
     * <p>Uses {@code Class.forName(..., false, loader)}: the {@code false} matters, because it means the class
     * is resolved but NOT initialised. Initialisation would run IBM's static setup - which reads its own
     * property files and configures logging - during a presence check, and a probe that has side effects is not
     * a probe. Everything is caught: a missing class, a linkage error and an unusable class loader all mean the
     * same thing to the caller, and none of them may escape.
     */
    private static boolean probeSdkPresence() {
        try {
            Class.forName("com.ibm.mm.sdk.server.DKDatastoreICM", false, classLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError | RuntimeException absent) {
            return false;
        }
    }

    /**
     * The CM API release of the SDK this JVM can see, or empty when it cannot be determined.
     *
     * <p>Package-visible so {@link IbmCmPoolDiagnostics} reports exactly the value the registry advertised:
     * one implementation, one answer. A second lookup would be a second thing to keep in step.
     */
    static String sdkReleaseOrEmpty() {
        try {
            return readSdkRelease();
        } catch (RuntimeException | Error unavailable) {
            return "";
        }
    }

    /**
     * Reads the CM API release from the SDK jar's manifest.
     *
     * <h2>Why the manifest, and not {@code DKDatastoreICM.getAPIVer()}</h2>
     *
     * <p>Using the SDK's own runtime accessor looks like the more direct answer, and it is the wrong one
     * here. That call lives in {@link IbmCmApi}, whose method signatures name {@code com.ibm} types, so
     * <em>loading that class requires the vendor jars to be present</em>. Calling it from this provider - the
     * one class {@link java.util.ServiceLoader} instantiates during discovery - would make discovery itself
     * depend on the SDK, and a distribution with the registration present but the jars missing would fail with
     * a raw {@code NoClassDefFoundError} instead of the clean {@code UNAVAILABLE} verdict the registry is
     * built to report. A diagnostics lookup must never be the thing that breaks discovery.
     *
     * <p>A manifest read is a JDK-only operation: it names no SDK class, needs no vendor jar, touches no
     * network and performs no I/O beyond reading a local file. It reports what IBM actually shipped rather
     * than a string this project guessed, and it degrades to an empty string - rendered as {@code unknown} -
     * when the SDK is absent, which is precisely the signal an operator needs to tell "no adapter" from
     * "adapter present, vendor jars missing".
     *
     * <p>{@code Specification-Version} is preferred over {@code Implementation-Version} because the latter
     * also carries IBM's build timestamp, and a diagnostics line wants the release rather than a date.
     *
     * @return the release, or an empty string when no manifest could be read
     */
    private static String readSdkRelease() {
        Manifest manifest = readSdkManifest();
        if (manifest == null) {
            return "";
        }
        Attributes attributes = manifest.getMainAttributes();
        return firstNonBlank(
                attributes.getValue("Specification-Version"),
                attributes.getValue("ContentManager-Version"),
                attributes.getValue("Implementation-Version"),
                attributes.getValue("ContentManagerAPI-Version"));
    }

    /**
     * Locates and parses a manifest, preferring the SDK's own jar.
     *
     * <p>The SDK jar is found through a class resource rather than a hard-coded path, so the lookup follows
     * whatever jar the operator actually placed on the class path. A {@code jar:} URL is opened as a
     * {@link java.util.jar.JarURLConnection} purely to reach its manifest - the connection is not attached to
     * anything and no network access is possible for a local file URL.
     */
    private static Manifest readSdkManifest() {
        ClassLoader loader = classLoader();
        URL marker = loader.getResource("com/ibm/mm/sdk/server/DKDatastoreICM.class");
        if (marker != null && "jar".equals(marker.getProtocol())) {
            try {
                URLConnection connection = marker.openConnection();
                if (connection instanceof JarURLConnection jarConnection) {
                    Manifest manifest = jarConnection.getManifest();
                    if (manifest != null) {
                        return manifest;
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Fall through to the class-path manifest; a diagnostics value is not worth an exception.
            }
        }
        // Fallback: a manifest visible on the class path, for a directory (non-jar) SDK layout where the
        // marker resource has no jar URL. It is only accepted when it is demonstrably the SDK's and not
        // this application's, because on the normal Class-Path layout the first class-path manifest IS
        // the launcher jar's own - and reporting CM Insight's own build version as the "CM API release"
        // is worse than reporting nothing: an operator would read "CM API release: 0.1.0-SNAPSHOT" and
        // believe the SDK was present and identified. Measured before this guard: with no SDK jar at all
        // the adapter advertised the application's version as the release.
        try (InputStream stream = loader.getResourceAsStream("META-INF/MANIFEST.MF")) {
            if (stream == null) {
                return null;
            }
            Manifest manifest = new Manifest(stream);
            return looksLikeSdkManifest(manifest) ? manifest : null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * True when a manifest identifies itself as the IBM Content Manager API.
     *
     * <p>Two independent markers are required rather than one, because this decision gates a value an
     * operator uses to tell "no SDK" from "SDK present": a single weak match on a third-party jar that
     * happens to sit on the class path would produce a wrong release banner. The real SDK manifest
     * carries {@code ContentManagerAPI-Version: 0807000400} and {@code Implementation-Title: Content
     * Manager API}, while this application's own manifest carries {@code Implementation-Title: CM
     * Insight} and neither Content Manager attribute.
     */
    private static boolean looksLikeSdkManifest(Manifest manifest) {
        Attributes attributes = manifest.getMainAttributes();
        boolean vendorMarker = isContentManager(attributes.getValue("ContentManagerAPI-Version"))
                || isContentManager(attributes.getValue("ContentManager-Version"));
        boolean titleMarker = containsContentManager(attributes.getValue("Specification-Title"))
                || containsContentManager(attributes.getValue("Implementation-Title"))
                || containsContentManager(attributes.getValue("Application-Name"));
        return vendorMarker && titleMarker;
    }

    private static boolean isContentManager(String value) {
        return value != null && !value.isBlank();
    }

    /** Case-insensitive containment, so {@code "Content Manager API"} and {@code "IBM Content Manager"} both match. */
    private static boolean containsContentManager(String value) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains("content manager");
    }

    private static ClassLoader classLoader() {
        ClassLoader loader = IbmCmAdapterProvider.class.getClassLoader();
        if (loader != null) {
            return loader;
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : ClassLoader.getSystemClassLoader();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    /** Id and versions only. Contains no credential and no class name. */
    @Override
    public String toString() {
        return "IbmCmAdapterProvider[" + PROVIDER_ID + ", adapter=" + ADAPTER_VERSION
                + ", sdkRelease=" + (sdkRelease().isEmpty() ? "unknown" : sdkRelease()) + "]";
    }
}
