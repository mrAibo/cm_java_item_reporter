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
 *   <dd>Exactly one provider, which is the only state that permits activation.</dd>
 *
 *   <dt>{@link Availability#AMBIGUOUS}</dt>
 *   <dd>Two or more providers. Deliberately an explicit failure: picking one would decide, silently and by
 *       class-path order, which vendor a production repository connects through. An operator resolves it.</dd>
 *
 *   <dt>{@link Availability#UNAVAILABLE}</dt>
 *   <dd>The service configuration or a provider class could not be loaded, or a provider answered with no
 *       usable id. Reported as this clean status with a generic reason - never as a raw
 *       {@code ClassNotFoundException} and never with a class name or a stack trace, because that text
 *       ends up in an operator-facing diagnostics page.</dd>
 * </dl>
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
     * @param availability   the outcome
     * @param providerId     the provider id, or an empty string when there is no usable provider
     * @param adapterVersion the adapter build version, or an empty string
     * @param sdkRelease     the vendor API release, or an empty string when the adapter does not report one
     * @param reason         one value-free sentence explaining the verdict, never a credential and never a
     *                       class name, stack trace or raw SDK message
     */
    public record Status(Availability availability,
                         String providerId,
                         String adapterVersion,
                         String sdkRelease,
                         String reason) {

        public Status {
            Objects.requireNonNull(availability, "availability");
            providerId = providerId == null ? "" : providerId;
            adapterVersion = adapterVersion == null ? "" : adapterVersion;
            sdkRelease = sdkRelease == null ? "" : sdkRelease;
            reason = reason == null ? "" : reason;
        }

        /** True only for {@link Availability#AVAILABLE}. */
        public boolean available() {
            return availability == Availability.AVAILABLE;
        }

        /** True when activation must be refused, whatever the cause. */
        public boolean refused() {
            return !available();
        }

        /** The vendor API release, or {@code unknown} when the adapter does not report one. */
        public String sdkReleaseOrUnknown() {
            return sdkRelease.isEmpty() ? "unknown" : sdkRelease;
        }

        /**
         * A compact one-line summary for a banner or a structured diagnostics field, for example
         * {@code available (ibm-cm-8.7, CM API 8.7.0.000)}.
         */
        public String summary() {
            if (!available()) {
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
                case UNAVAILABLE -> "the CM adapter provider is not usable"
                        + (reason.isEmpty() ? "" : ": " + reason);
            };
        }

        @Override
        public String toString() {
            return "CmAdapterStatus[" + availability.label()
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

    /** Keeps a provider-declared string bounded and free of control characters. */
    private static final int MAX_DECLARED_LENGTH = 64;

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
            return new IbmCmAdapterRegistry(new Status(Availability.UNAVAILABLE, "", "", "", failure), null);
        }
        if (found.isEmpty()) {
            return new IbmCmAdapterRegistry(new Status(Availability.ABSENT, "", "", "", ""), null);
        }
        if (found.size() > 1) {
            return new IbmCmAdapterRegistry(
                    new Status(Availability.AMBIGUOUS, "", "", "", describeIds(found)), null);
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
                    new Status(Availability.UNAVAILABLE, "", "", "", INCOMPLETE_PROVIDER), null);
        }
        if (id.isEmpty()) {
            return new IbmCmAdapterRegistry(
                    new Status(Availability.UNAVAILABLE, "", version, release, INCOMPLETE_PROVIDER), null);
        }
        return new IbmCmAdapterRegistry(
                new Status(Availability.AVAILABLE, id, version, release, ""), single);
    }

    /** What discovery found. Immutable and safe to cache. */
    public Status status() {
        return status;
    }

    /** The provider, or empty unless {@link Status#available()} is true. */
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
     * Bounds a provider-declared string: trimmed, single-spaced and capped.
     *
     * <p>The three declared fields are the only free text discovery accepts, they are printed in
     * diagnostics, and a provider is third-party code. Capping them keeps a malformed or hostile
     * declaration from bloating a log line or injecting control characters into a terminal.
     */
    private static String sanitise(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(Math.min(value.length(), MAX_DECLARED_LENGTH));
        boolean lastWasSpace = true;
        for (int i = 0; i < value.length() && cleaned.length() < MAX_DECLARED_LENGTH; i++) {
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
