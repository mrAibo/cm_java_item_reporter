package com.mraibo.cminsight.security;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.SecretRef;
import com.mraibo.cminsight.config.WebAuthSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exposure policy for the web interface.
 *
 * <p>Three independent rules, all fail-closed:
 *
 * <ol>
 *   <li>blank effective credentials are refused outright;</li>
 *   <li>any part of the effective credential that is the built-in development default is refused on
 *       any non-loopback bind, because those values are public and documented - the {@code
 *       admin}/{@code admin} pair is the special case of this rule, and no override changes it;</li>
 *   <li>plain HTTP on a non-loopback bind is refused unless the operator accepts it explicitly with
 *       {@link #KEY_ALLOW_INSECURE_HTTP}, because HTTP Basic sends reusable credentials without
 *       transport encryption.</li>
 * </ol>
 *
 * <p>Rule 2 is deliberately broader than "the pair is default": a single blank {@code
 * web.auth.password} in the configuration file is treated as unset and silently falls back to the
 * published development password, so a configuration that looks as though it sets a password would
 * otherwise accept {@code <any user>}/{@code admin} from any reachable host.
 *
 * <p>Loopback detection is a pure string test: {@link #isLoopbackLiteral(String)} performs no DNS, no
 * socket and no {@link java.net.InetAddress} lookup. Anything that is not unmistakably a loopback
 * literal - including {@code 0.0.0.0}, {@code ::}, {@code ::ffff:127.0.0.1} and every host name - is
 * treated as non-loopback, so an unresolvable or cleverly spelled bind address cannot unlock the
 * relaxed path.
 */
public final class SecurityPolicy {

    /**
     * Configuration key of the explicit, insecure opt-in that permits a non-loopback plain-HTTP
     * bind.
     *
     * <p>Absent and {@code false} by default: doing nothing yields the safe answer. The same literal
     * key is read by {@code WebServer}, reported by {@code Main} and surfaced by the read-only doctor
     * entry point, so those surfaces cannot disagree about its name.
     */
    public static final String KEY_ALLOW_INSECURE_HTTP = "web.allowInsecureHttp";

    /**
     * Stable prefix of the warning produced when {@link #KEY_ALLOW_INSECURE_HTTP} permits a
     * non-loopback plain-HTTP bind.
     *
     * <p>{@code WebServer} promotes any warning carrying this marker to a {@code SECURITY WARNING}
     * line, and the doctor surface prints the same sentence, so the two never drift into saying
     * different things.
     */
    public static final String INSECURE_HTTP_MARKER = "INSECURE non-loopback plain HTTP";

    /** Maximum length of a bind address echoed into a diagnostic. */
    private static final int MAX_BIND_TEXT = 64;

    private SecurityPolicy() {
    }

    /**
     * True only for an unmistakable loopback literal: a {@code 127.0.0.0/8} address, {@code ::1} (in
     * short or expanded form, optionally bracketed or zone-qualified) or the name {@code localhost}.
     */
    public static boolean isLoopbackLiteral(String bind) {
        if (bind == null) {
            return false;
        }
        String value = bind.trim();
        if (value.isEmpty()) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(value)) {
            return true;
        }
        String host = value;
        if (host.length() > 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            host = host.substring(1, host.length() - 1);
        }
        // A zone identifier (%eth0, %25eth0) is an IPv6 concept. Stripping "%..." from an IPv4-looking
        // spelling would promote "127.0.0.1%00" to a loopback literal, so the zone is only removed when
        // the value is genuinely IPv6.
        if (host.indexOf(':') >= 0) {
            int zone = host.indexOf('%');
            if (zone >= 0) {
                host = host.substring(0, zone);
            }
        }
        if ("::1".equals(host) || "0:0:0:0:0:0:0:1".equals(host)) {
            return true;
        }
        return isIpv4LoopbackLiteral(host);
    }

    /**
     * Reads {@link #KEY_ALLOW_INSECURE_HTTP} strictly.
     *
     * <p>Absent or blank means {@code false}; a value that is neither {@code true} nor {@code false} is
     * a configuration error rather than a silent no. This is the only place the key is parsed, so the
     * runtime, {@code Main} and the read-only doctor entry point cannot drift apart on its meaning -
     * including the fail-closed behaviour for a typo such as {@code web.allowInsecureHttp=yes}.
     *
     * @throws com.mraibo.cminsight.config.ConfigException when the configured value is not a boolean
     */
    public static boolean allowInsecureHttp(AppConfig config) {
        Objects.requireNonNull(config, "config");
        return config.getBoolean(KEY_ALLOW_INSECURE_HTTP, false);
    }

    /**
     * Refuses a web exposure that must not start.
     *
     * @param allowInsecureHttp the effective value of {@link #KEY_ALLOW_INSECURE_HTTP} (see
     *                          {@link #allowInsecureHttp(AppConfig)}); {@code false} (the default)
     *                          refuses every non-loopback plain-HTTP bind
     * @throws IllegalStateException when credentials are blank, when any part of the effective
     *                               credential is the built-in development default and the bind is
     *                               not loopback, or when plain HTTP would be bound beyond loopback
     *                               without the explicit insecure opt-in
     */
    public static void validateWebExposure(WebAuthSettings auth, String bindAddress, int port,
                                           boolean allowInsecureHttp) {
        Objects.requireNonNull(auth, "auth");
        if (isBlank(auth.user()) || isBlank(auth.password())) {
            throw new IllegalStateException("Refusing to start the web interface with blank credentials: "
                    + "configure web.auth.user and web.auth.password (directly, via web.auth.*.env or via "
                    + "web.auth.*.file)");
        }
        // Checked first and unconditionally: the insecure opt-in exists to accept plaintext transport
        // risk, never to accept a public, documented credential on a reachable address. Any default
        // part refuses the bind, not only the admin/admin pair - a blank configured value is treated
        // as unset and silently falls back to the published development password.
        if (!isLoopbackLiteral(bindAddress) && usesAnyDevelopmentDefault(auth)) {
            throw new IllegalStateException(developmentCredentialRefusal(auth, bindAddress, port));
        }
        if (!isLoopbackLiteral(bindAddress) && !allowInsecureHttp) {
            throw new IllegalStateException("Refusing to bind the web interface to '" + describeBind(bindAddress)
                    + "':" + port + " over plain HTTP: HTTP Basic sends reusable credentials in cleartext, "
                    + "so anyone on the network path can capture and replay them. Keep web.bind=127.0.0.1 and "
                    + "reach the application through an HTTPS reverse proxy or an equivalent secure tunnel, or "
                    + "- only for an explicitly accepted insecure test/development exposure - set "
                    + KEY_ALLOW_INSECURE_HTTP + "=true.");
        }
    }

    /**
     * Fail-closed overload for callers that do not read {@link #KEY_ALLOW_INSECURE_HTTP} from
     * configuration: an override that was never read is treated as absent.
     */
    public static void validateWebExposure(WebAuthSettings auth, String bindAddress, int port) {
        validateWebExposure(auth, bindAddress, port, false);
    }

    /**
     * Non-fatal observations about the current exposure, safe to print at startup. Never contains a
     * credential value, only which source it came from.
     *
     * @param allowInsecureHttp the effective value of {@link #KEY_ALLOW_INSECURE_HTTP}
     */
    public static List<String> exposureWarnings(WebAuthSettings auth, String bindAddress, int port,
                                                boolean allowInsecureHttp) {
        Objects.requireNonNull(auth, "auth");
        List<String> warnings = new ArrayList<>();
        if (isBlank(auth.user()) || isBlank(auth.password())) {
            warnings.add("Web authentication credentials are blank; the interface will refuse to start.");
        }
        if (usesAnyDevelopmentDefault(auth)) {
            warnings.add(developmentDefaultWarning(auth));
        } else if (!isLoopbackLiteral(bindAddress)) {
            if (allowInsecureHttp) {
                warnings.add(insecureHttpWarning(bindAddress, port));
            } else {
                warnings.add("The web interface is configured on the non-loopback address '"
                        + describeBind(bindAddress) + "':" + port + " over plain HTTP; startup will be "
                        + "refused unless " + KEY_ALLOW_INSECURE_HTTP + "=true is set deliberately.");
            }
        }
        if (allowInsecureHttp && isLoopbackLiteral(bindAddress)) {
            warnings.add(KEY_ALLOW_INSECURE_HTTP + "=true has no effect on the loopback bind '"
                    + describeBind(bindAddress) + "':" + port + " (loopback over plain HTTP is the normal, "
                    + "supported configuration). Remove the override so the intent stays unambiguous.");
        }
        return List.copyOf(warnings);
    }

    /**
     * Fail-closed overload for callers that do not read {@link #KEY_ALLOW_INSECURE_HTTP} from
     * configuration.
     */
    public static List<String> exposureWarnings(WebAuthSettings auth, String bindAddress, int port) {
        return exposureWarnings(auth, bindAddress, port, false);
    }

    /**
     * The exact sentence describing an accepted insecure exposure.
     *
     * <p>Public on purpose: {@code bin/doctor.sh} prints the same warning text instead of maintaining a
     * second, subtly different wording. Starts with {@link #INSECURE_HTTP_MARKER}.
     */
    public static String insecureHttpWarning(String bindAddress, int port) {
        return INSECURE_HTTP_MARKER + " is enabled by " + KEY_ALLOW_INSECURE_HTTP + "=true: the web "
                + "interface is bound to '" + describeBind(bindAddress) + "':" + port + ", and HTTP Basic "
                + "sends reusable credentials in cleartext. Anyone who can reach that address can capture "
                + "them. Use it only on a trusted network, and prefer an HTTPS reverse proxy or an "
                + "equivalent secure tunnel in front of the application.";
    }

    /** True when the effective credentials are the built-in development pair. */
    public static boolean usesDefaultCredentials(WebAuthSettings auth) {
        Objects.requireNonNull(auth, "auth");
        return auth.defaultCredentials()
                || (WebAuthSettings.DEFAULT_USER.equals(auth.user())
                && WebAuthSettings.DEFAULT_PASSWORD.equals(auth.password()));
    }

    /**
     * True when the effective password is the built-in development default: it either fell back to
     * that default (blank, unset or otherwise unresolved {@code web.auth.password}) or was configured
     * to the published default password.
     *
     * <p>This is the half that must never be reachable from another host, because the value is public
     * and documented.
     */
    public static boolean usesDefaultPassword(WebAuthSettings auth) {
        Objects.requireNonNull(auth, "auth");
        return auth.passwordSource().source() == SecretRef.Source.DEFAULT
                || WebAuthSettings.DEFAULT_PASSWORD.equals(auth.password());
    }

    /**
     * True when the effective user name silently fell back to the built-in development default
     * (blank, unset or otherwise unresolved {@code web.auth.user}).
     *
     * <p>Deliberately source-based only: a user name the operator wrote down - even {@code admin}
     * alongside a real password - is an explicit choice, and the name itself is not a secret. What
     * must never escape loopback is the published <em>password</em>, which
     * {@link #usesDefaultPassword(WebAuthSettings)} covers.
     */
    public static boolean usesDefaultUser(WebAuthSettings auth) {
        Objects.requireNonNull(auth, "auth");
        return auth.userSource().source() == SecretRef.Source.DEFAULT;
    }

    /**
     * True when any part of the effective credential is, or silently fell back to, the built-in
     * development default. This is the exposure rule: off loopback such a credential is refused.
     */
    public static boolean usesAnyDevelopmentDefault(WebAuthSettings auth) {
        Objects.requireNonNull(auth, "auth");
        return usesDefaultUser(auth) || usesDefaultPassword(auth);
    }

    /**
     * Names which halves of the effective credential are the built-in development default: exactly one
     * of {@code "none"}, {@code "user only"}, {@code "password only"} or {@code "both"}.
     *
     * <p>Reported by {@code Main --print-config} next to the redacted credential sources. A single
     * boolean is not enough there: a partially defaulted credential is not {@code false}, and printing
     * "false" directly beneath "[built-in development default]" invites exactly the misreading the
     * F-1 finding was about. Safe to print - it never contains a credential value.
     */
    public static String describeDevelopmentDefaults(WebAuthSettings auth) {
        Objects.requireNonNull(auth, "auth");
        if (usesDefaultCredentials(auth) || (usesDefaultUser(auth) && usesDefaultPassword(auth))) {
            return "both";
        }
        if (usesDefaultPassword(auth)) {
            return "password only";
        }
        if (usesDefaultUser(auth)) {
            return "user only";
        }
        return "none";
    }

    /**
     * The refusal for a credential that is (partly) the published development default on a non-loopback
     * bind.
     *
     * <p>Names exactly which part fell back, because "the password line in my configuration file is
     * there" is exactly what the operator believes in the blank-value case.
     */
    private static String developmentCredentialRefusal(WebAuthSettings auth, String bindAddress, int port) {
        final String problem;
        if (usesDefaultCredentials(auth)) {
            problem = "with the built-in admin/admin development credentials ("
                    + auth.userSource().describe() + " / " + auth.passwordSource().describe()
                    + "), which are public and documented";
        } else if (usesDefaultPassword(auth)) {
            problem = "because the effective web password is the built-in development default ("
                    + auth.passwordSource().describe() + "), which is a public, documented password";
        } else {
            problem = "because the effective web user is the built-in development default ("
                    + auth.userSource().describe() + "), which is a public, documented user name";
        }
        return "Refusing to bind the web interface to '" + describeBind(bindAddress) + "':" + port + " "
                + problem + ". A blank or unset value in the configuration file is treated as unset and "
                + "falls back to the development default, so configure a real value (web.auth.user, "
                + "web.auth.password, or the web.auth.*.env / web.auth.*.file indirections), or bind "
                + "web.bind=127.0.0.1. " + KEY_ALLOW_INSECURE_HTTP + "=true does not permit a built-in "
                + "development credential on a non-loopback bind.";
    }

    /** The startup warning for a credential that is (partly) the built-in development default. */
    private static String developmentDefaultWarning(WebAuthSettings auth) {
        if (usesDefaultCredentials(auth)) {
            return "The web interface is using the built-in admin/admin development credentials "
                    + "(" + auth.userSource().describe() + " / " + auth.passwordSource().describe()
                    + "). Set web.auth.password before exposing it beyond the loopback interface.";
        }
        if (usesDefaultPassword(auth)) {
            return "The web interface is using the built-in development password ("
                    + auth.passwordSource().describe() + "): web.auth.password is blank or unset, so the "
                    + "published development password applies. Configure a real password (web.auth.password, "
                    + "web.auth.password.env or web.auth.password.file) before exposing the interface beyond "
                    + "the loopback interface.";
        }
        return "The web interface is using the built-in development user name '"
                + WebAuthSettings.DEFAULT_USER + "' (" + auth.userSource().describe()
                + "): web.auth.user is blank or unset, so the development default applies. Configure a real "
                + "user name (web.auth.user, web.auth.user.env or web.auth.user.file).";
    }

    private static boolean isIpv4LoopbackLiteral(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (int i = 0; i < octets.length; i++) {
            String octet = octets[i];
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            int value = 0;
            for (int j = 0; j < octet.length(); j++) {
                char c = octet.charAt(j);
                if (c < '0' || c > '9') {
                    return false;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return false;
            }
            if (i == 0 && value != 127) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String describeBind(String bind) {
        if (bind == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(bind.length(), MAX_BIND_TEXT));
        for (int i = 0; i < bind.length() && out.length() < MAX_BIND_TEXT; i++) {
            char c = bind.charAt(i);
            out.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        return out.toString().trim();
    }
}
