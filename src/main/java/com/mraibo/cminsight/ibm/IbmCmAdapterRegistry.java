package com.mraibo.cminsight.ibm;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.TreeSet;

/**
 * Discovers the installed {@link CmAdapterProvider} and reports, in one value, whether a repository can be
 * activated at all.
 *
 * <h2>Four outcomes, no fifth</h2>
 *
 * <dl>
 *   <dt>{@link Availability#ABSENT}</dt>
 *   <dd>No provider is installed. This is the normal state of a core-only build: repositories are still
 *       listed and their profiles are still validated, but nothing can be activated, and that is reported
 *       rather than worked around.</dd>
 *
 *   <dt>{@link Availability#AVAILABLE}</dt>
 *   <dd>Exactly one provider, and that provider reports its vendor RUNTIME ready to activate a repository
 *       (see {@link CmAdapterProvider#readiness()}). The only state that permits activation.</dd>
 *
 *   <dt>{@link Availability#AMBIGUOUS}</dt>
 *   <dd>Two or more providers. Deliberately an explicit failure: picking one would decide, silently and by
 *       class-path order, which vendor a production repository connects through. An operator resolves it.</dd>
 *
 *   <dt>{@link Availability#UNAVAILABLE}</dt>
 *   <dd>The service configuration or a provider class could not be loaded, a provider answered with no
 *       usable id, or - the case this registry exists to keep truthful - exactly one provider IS installed
 *       but reports that its vendor runtime is not ready. Reported as this clean status with a generic
 *       reason - never as a raw {@code ClassNotFoundException} and never with a class name or a stack
 *       trace, because that text ends up in an operator-facing diagnostics page.</dd>
 * </dl>
 *
 * <h2>Installed is not the same as ready, and the verdict follows readiness</h2>
 *
 * <p>The adapter's classes are packaged in the same artifact as the core, so a build with no vendor JARs
 * still has a provider that loads and describes itself. Reporting that as {@code AVAILABLE} was the
 * defect: {@code /api/repositories} said {@code available=true}, selection failed later, and
 * {@code --check-repository} walked into activation instead of returning the adapter-unavailable verdict.
 *
 * <p>{@link Status#providerInstalled()} therefore answers "is the adapter's CODE present" and
 * {@link Status#availability()} answers "can it activate a repository". When the runtime is not ready the
 * verdict is {@link Availability#UNAVAILABLE} with {@code providerInstalled=true}, the provider's id and
 * version are still published so an operator can see WHICH adapter is present, and
 * {@link #provider()} exposes nothing - so every activation path refuses before a repository manager,
 * session factory or pool is created.
 *
 * <h2>What diagnostics may contain</h2>
 *
 * <p>{@link Status} carries exactly four value-free fields - availability, provider id, adapter version and
 * vendor API release - plus a reason sentence. There is no field for a credential, and the profile's
 * credentials are never read here: discovery happens before any repository is selected.
 *
 * <p>An instance is immutable and cheap to hold; discovery is not repeated on every read, so a diagnostics
 * page cannot turn into a class-loading operation per request.
 */
public final class IbmCmAdapterRegistry {

    /** How a discovered provider set is classified. */
    public enum Availability {

        /** No CM adapter provider is installed. */
        ABSENT("absent"),

        /** Exactly one provider is installed and usable. */
        AVAILABLE("available"),

        /** More than one provider is installed; activation is refused rather than picking one. */
        AMBIGUOUS("ambiguous"),

        /** The provider configuration or a provider class could not be loaded. */
        UNAVAILABLE("unavailable");

        private final String label;

        Availability(String label) {
            this.label = label;
        }

        /** Lower-case name, for a diagnostic line or a JSON field. */
        public String label() {
            return label;
        }
    }

    /**
     * The discovery verdict: what was found and, when nothing usable was found, why.
     *
     * @param availability      the outcome, which is the ACTIVATION verdict and follows the provider's
     *                          runtime readiness, never merely the presence of provider classes
     * @param providerId        the provider id, or an empty string when there is no usable provider
     * @param adapterVersion    the adapter build version, or an empty string
     * @param sdkRelease        the vendor API release, or an empty string when the adapter does not report one
     * @param reason            one value-free sentence explaining the verdict, never a credential and never a
     *                          class name, stack trace or raw SDK message
     * @param providerInstalled true when a provider satisfied discovery and described itself, whether or not
     *                          its vendor runtime is ready. Independent of {@code availability} on purpose:
     *                          "installed" and "can activate" are different facts
     */
    public record Status(Availability availability,
                         String providerId,
                         String adapterVersion,
                         String sdkRelease,
                         String reason,
                         boolean providerInstalled) {

        public Status {
            Objects.requireNonNull(availability, "availability");
            providerId = providerId == null ? "" : providerId;
            adapterVersion = adapterVersion == null ? "" : adapterVersion;
            sdkRelease = sdkRelease == null ? "" : sdkRelease;
            reason = reason == null ? "" : reason;
        }

        /**
         * True only for {@link Availability#AVAILABLE}: the adapter is installed AND its runtime reported
         * itself ready, which is the single condition that permits activation.
         */
        public boolean available() {
            return availability == Availability.AVAILABLE;
        }

        /** True when activation must be refused, whatever the cause. */
        public boolean refused() {
            return !available();
        }

        /** True when the adapter's code was found and described itself, whatever its runtime says. */
        public boolean providerInstalled() {
            return providerInstalled;
        }

        /** The vendor API release, or {@code unknown} when the adapter does not report one. */
        public String sdkReleaseOrUnknown() {
            return sdkRelease.isEmpty() ? "unknown" : sdkRelease;
        }

        /**
         * A compact one-line summary for a banner or a structured diagnostics field, for example
         * {@code available (ibm-cm-8.7, CM API 8.7.0.000)}.
         *
         * <p>An installed adapter whose runtime is not ready reads {@code installed, unavailable}, so a
         * banner can never be mistaken for the core-only case.
         */
        public String summary() {
            if (!available()) {
                if (providerInstalled && availability == Availability.UNAVAILABLE) {
                    return "installed, unavailable";
                }
                return availability.label();
            }
            return availability.label() + " (" + providerId + ", CM API " + sdkReleaseOrUnknown() + ")";
        }

        /** The full, value-free sentence an operator sees. */
        public String describe() {
            return switch (availability) {
                case AVAILABLE -> "CM adapter " + providerId + " (adapter " + adapterVersion
                        + ", CM API " + sdkReleaseOrUnknown() + ") is available";
                case ABSENT -> "no CM adapter provider is installed"
                        + (reason.isEmpty() ? "" : ": " + reason);
                case AMBIGUOUS -> "more than one CM adapter provider is installed"
                        + (reason.isEmpty() ? "" : ": " + reason);
                case UNAVAILABLE -> providerInstalled
                        ? "the CM adapter " + providerId + " is installed but its runtime is not ready to"
                                + " activate a repository" + (reason.isEmpty() ? "" : ": " + reason)
                        : "the CM adapter provider is not usable"
                                + (reason.isEmpty() ? "" : ": " + reason);
            };
        }

        @Override
        public String toString() {
            return "CmAdapterStatus[" + availability.label()
                    + ", providerInstalled=" + providerInstalled
                    + ", provider=" + (providerId.isEmpty() ? "(none)" : providerId)
                    + ", adapterVersion=" + (adapterVersion.isEmpty() ? "(none)" : adapterVersion)
                    + ", sdkRelease=" + sdkReleaseOrUnknown()
                    + ", reason=" + (reason.isEmpty() ? "(none)" : reason) + "]";
        }
    }

    /**
     * The generic reason for every load failure.
     *
     * <p>Deliberately fixed text: the throwable's own message and class name are exactly what must not
     * reach a diagnostics page, and the operator action is the same for all of them - check that the
     * adapter's own dependencies are on the class path.
     */
    private static final String LOAD_FAILURE =
            "a provider entry could not be loaded; check that the adapter and its dependencies are on the class path";

    private static final String CONFIGURATION_FAILURE =
            "the service configuration could not be read; check the adapter's service registration";

    private static final String INCOMPLETE_PROVIDER =
            "a provider entry answered without a usable id; the adapter build is incomplete or mismatched";

    /**
     * The generic reason for a provider that cannot answer {@link CmAdapterProvider#readiness()}.
     *
     * <p>Fixed text for the same reason as {@link #LOAD_FAILURE}: a provider's own throwable must not
     * reach an operator page, and the operator action is the same whatever it was - the adapter's vendor
     * runtime could not be established.
     */
    private static final String READINESS_FAILURE =
            "the adapter could not answer whether its vendor runtime is ready to activate a repository";

    /** Keeps a provider-declared string bounded and free of control characters. */
    private static final int MAX_DECLARED_LENGTH = 64;

    /**
     * Keeps a provider-declared readiness reason bounded.
     *
     * <p>Longer than {@link #MAX_DECLARED_LENGTH} because a readiness reason is an operator instruction
     * ("place the SDK jars where the launcher looks for them") rather than a version string, and capped at
     * all because it is still third-party text printed on one line.
     */
    private static final int MAX_REASON_LENGTH = 200;

    private final Status status;
    private final CmAdapterProvider provider;

    private IbmCmAdapterRegistry(Status status, CmAdapterProvider provider) {
        this.status = status;
        this.provider = provider;
    }

    /** Discovers the provider on the thread context class loader, falling back to this class's loader. */
    public static IbmCmAdapterRegistry discover() {
        return discover(contextClassLoader());
    }

    /**
     * Discovers the provider on the given class loader.
     *
     * <p>Never throws for a missing, ambiguous or broken provider: every outcome is a {@link Status}, so a
     * caller such as the startup path or a doctor script can always print a clean verdict.
     */
    public static IbmCmAdapterRegistry discover(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");

        List<CmAdapterProvider> found = new ArrayList<>(2);
        String failure = null;
        try {
            ServiceLoader<CmAdapterProvider> services = ServiceLoader.load(CmAdapterProvider.class, loader);
            Iterator<CmAdapterProvider> iterator = services.iterator();
            while (failure == null) {
                final boolean hasNext;
                try {
                    hasNext = iterator.hasNext();
                } catch (ServiceConfigurationError e) {
                    failure = LOAD_FAILURE;
                    break;
                }
                if (!hasNext) {
                    break;
                }
                try {
                    CmAdapterProvider candidate = iterator.next();
                    if (candidate == null) {
                        failure = INCOMPLETE_PROVIDER;
                    } else {
                        found.add(candidate);
                    }
                } catch (ServiceConfigurationError e) {
                    // A provider class that cannot be loaded - a missing vendor jar, a mismatched build, a
                    // wrong type. Reported as one clean status; the class name and the stack trace are
                    // deliberately dropped because they are what an operator page must not print.
                    failure = LOAD_FAILURE;
                }
            }
        } catch (ServiceConfigurationError e) {
            failure = CONFIGURATION_FAILURE;
        } catch (Throwable t) {
            // Defensive net for a descriptor that fails with a linkage error rather than a
            // ServiceConfigurationError. Swallowing it here is the point: "discovery could not run" is a
            // diagnosable status, whereas letting a NoClassDefFoundError escape would turn a missing
            // optional jar into an unexplained process failure.
            failure = CONFIGURATION_FAILURE;
        }

        if (failure != null) {
            return new IbmCmAdapterRegistry(
                    new Status(Availability.UNAVAILABLE, "", "", "", failure, false), null);
        }
        if (found.isEmpty()) {
            return new IbmCmAdapterRegistry(
                    new Status(Availability.ABSENT, "", "", "", "", false), null);
        }
        if (found.size() > 1) {
            // Installed is TRUE here: two providers are present, which is why nothing may be activated.
            // The two facts are independent, and an operator reading "ambiguous" needs to know that an
            // adapter is installed rather than absent.
            return new IbmCmAdapterRegistry(
                    new Status(Availability.AMBIGUOUS, "", "", "", describeIds(found), true), null);
        }

        CmAdapterProvider single = found.get(0);
        final String id;
        final String version;
        final String release;
        try {
            id = sanitise(single.providerId());
            version = sanitise(single.adapterVersion());
            release = sanitise(single.sdkRelease());
        } catch (RuntimeException | Error e) {
            // A provider that cannot even describe itself is not usable, and its own failure must not
            // become a raw exception on the startup path.
            return new IbmCmAdapterRegistry(
                    new Status(Availability.UNAVAILABLE, "", "", "", INCOMPLETE_PROVIDER, false), null);
        }
        if (id.isEmpty()) {
            return new IbmCmAdapterRegistry(
                    new Status(Availability.UNAVAILABLE, "", version, release, INCOMPLETE_PROVIDER, false),
                    null);
        }

        // Two concepts, two answers. The provider's CODE is installed - it loaded and described itself -
        // and its vendor RUNTIME is ready only when the adapter says so. The ACTIVATION verdict follows
        // the runtime answer, because "the provider was found" is precisely what used to be mistaken for
        // "a repository can be activated".
        //
        // The probe is asked once per discovery, here, and never again on a request path: readiness is
        // required to be cheap and local (a class-visibility lookup), and a diagnostics page must not be
        // able to trigger class loading per request.
        final CmAdapterProvider.Readiness readiness;
        try {
            readiness = single.readiness();
        } catch (RuntimeException | Error e) {
            // A readiness answer that throws is NOT ready. This is the second net behind the provider's
            // own totality requirement: even a provider that lets a linkage error escape produces a clean
            // status here instead of a raw NoClassDefFoundError on the startup path.
            return new IbmCmAdapterRegistry(
                    new Status(Availability.UNAVAILABLE, id, version, release, READINESS_FAILURE, true), null);
        }
        if (readiness == null || !readiness.ready()) {
            return new IbmCmAdapterRegistry(
                    new Status(Availability.UNAVAILABLE, id, version, release, readinessReason(readiness), true),
                    null);
        }
        return new IbmCmAdapterRegistry(
                new Status(Availability.AVAILABLE, id, version, release, "", true), single);
    }

    /** What discovery found. Immutable and safe to cache. */
    public Status status() {
        return status;
    }

    /**
     * The provider, or empty unless {@link Status#available()} is true.
     *
     * <p>An installed adapter whose vendor runtime is not ready is exposed NOWHERE. That is the whole
     * point of the readiness verdict: if this returned the provider anyway, a caller could build a session
     * factory from an adapter that already said it cannot activate a repository, and the failure would
     * move from a clean refusal to a class-loading error inside a pool.
     */
    public Optional<CmAdapterProvider> provider() {
        return Optional.ofNullable(provider);
    }

    /** The provider id, or an empty string when there is no usable provider. Never a credential. */
    public String providerId() {
        return status.providerId();
    }

    /** The vendor API release, or an empty string when unknown. Never a credential. */
    public String sdkRelease() {
        return status.sdkRelease();
    }

    // ---------------------------------------------------------------- internals

    private static ClassLoader contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : IbmCmAdapterRegistry.class.getClassLoader();
    }

    /** The declared ids of every found provider, sorted, for an operator to act on. Never a credential. */
    private static String describeIds(List<CmAdapterProvider> providers) {
        TreeSet<String> ids = new TreeSet<>();
        for (CmAdapterProvider candidate : providers) {
            try {
                String id = sanitise(candidate.providerId());
                ids.add(id.isEmpty() ? "(provider without an id)" : id);
            } catch (RuntimeException | Error e) {
                ids.add("(provider that could not describe itself)");
            }
        }
        return ids.size() + " providers are installed (" + String.join(", ", ids)
                + "); exactly one is required and none is chosen automatically";
    }

    /**
     * The provider's own readiness reason, bounded and free of control characters, never empty.
     *
     * <p>A provider is third-party code and this text is printed on an operator page, so it gets exactly
     * the same treatment as the declared fields: whitespace collapsed, control characters dropped, length
     * capped. A blank or absent reason becomes the fixed {@link #READINESS_FAILURE} sentence rather than an
     * empty verdict, because "unavailable, reason: (nothing)" is not an instruction anybody can act on.
     */
    private static String readinessReason(CmAdapterProvider.Readiness readiness) {
        String reason = readiness == null ? "" : sanitise(readiness.reason(), MAX_REASON_LENGTH);
        return reason.isEmpty() ? READINESS_FAILURE : reason;
    }

    /**
     * Bounds a provider-declared string: trimmed, single-spaced and capped.
     *
     * <p>The three declared fields are the only free text discovery accepts, they are printed in
     * diagnostics, and a provider is third-party code. Capping them keeps a malformed or hostile
     * declaration from bloating a log line or injecting control characters into a terminal.
     */
    private static String sanitise(String value) {
        return sanitise(value, MAX_DECLARED_LENGTH);
    }

    /** {@link #sanitise(String)} with an explicit cap, for text that is not a version string. */
    private static String sanitise(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(Math.min(value.length(), maxLength));
        boolean lastWasSpace = true;
        for (int i = 0; i < value.length() && cleaned.length() < maxLength; i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (lastWasSpace) {
                    continue;
                }
                lastWasSpace = true;
                cleaned.append(' ');
                continue;
            }
            lastWasSpace = false;
            cleaned.append(c);
        }
        return cleaned.toString().trim();
    }

    @Override
    public String toString() {
        return "IbmCmAdapterRegistry[" + status + "]";
    }
}
