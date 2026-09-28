package com.mraibo.cminsight.security;

import com.mraibo.cminsight.config.WebAuthSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exposure policy for the web interface.
 *
 * <p>Two independent rules, both fail-closed:
 *
 * <ol>
 *   <li>blank effective credentials are refused outright;</li>
 *   <li>the built-in {@code admin}/{@code admin} development pair is refused on any non-loopback
 *       bind, because that combination is a public, documented credential.</li>
 * </ol>
 *
 * <p>Loopback detection is a pure string test: {@link #isLoopbackLiteral(String)} performs no DNS, no
 * socket and no {@link java.net.InetAddress} lookup. Anything that is not unmistakably a loopback
 * literal - including {@code 0.0.0.0}, {@code ::}, {@code ::ffff:127.0.0.1} and every host name - is
 * treated as non-loopback, so an unresolvable or cleverly spelled bind address cannot unlock the
 * relaxed path.
 */
public final class SecurityPolicy {

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
     * Refuses a web exposure that must not start.
     *
     * @throws IllegalStateException when credentials are blank, or when the default administrator
     *                               credentials would be reachable from a non-loopback address
     */
    public static void validateWebExposure(WebAuthSettings auth, String bindAddress, int port) {
        Objects.requireNonNull(auth, "auth");
        if (isBlank(auth.user()) || isBlank(auth.password())) {
            throw new IllegalStateException("Refusing to start the web interface with blank credentials: "
                    + "configure web.auth.user and web.auth.password (directly, via web.auth.*.env or via "
                    + "web.auth.*.file)");
        }
        if (usesDefaultCredentials(auth) && !isLoopbackLiteral(bindAddress)) {
            throw new IllegalStateException("Refusing to bind the web interface to '" + describeBind(bindAddress)
                    + "':" + port + " with the built-in admin/admin development credentials. Bind "
                    + "web.bind=127.0.0.1, or configure a real password (web.auth.password, "
                    + "web.auth.password.env or web.auth.password.file).");
        }
    }

    /**
     * Non-fatal observations about the current exposure, safe to print at startup. Never contains a
     * credential value, only which source it came from.
     */
    public static List<String> exposureWarnings(WebAuthSettings auth, String bindAddress, int port) {
        Objects.requireNonNull(auth, "auth");
        List<String> warnings = new ArrayList<>();
        if (isBlank(auth.user()) || isBlank(auth.password())) {
            warnings.add("Web authentication credentials are blank; the interface will refuse to start.");
        }
        if (usesDefaultCredentials(auth)) {
            warnings.add("The web interface is using the built-in admin/admin development credentials "
                    + "(" + auth.userSource().describe() + " / " + auth.passwordSource().describe()
                    + "). Set web.auth.password before exposing it beyond the loopback interface.");
        } else if (!isLoopbackLiteral(bindAddress)) {
            warnings.add("The web interface is bound to the non-loopback address '"
                    + describeBind(bindAddress) + "':" + port + "; make sure only trusted networks can reach it.");
        }
        return List.copyOf(warnings);
    }

    /** True when the effective credentials are the built-in development pair. */
    public static boolean usesDefaultCredentials(WebAuthSettings auth) {
        Objects.requireNonNull(auth, "auth");
        return auth.defaultCredentials()
                || (WebAuthSettings.DEFAULT_USER.equals(auth.user())
                && WebAuthSettings.DEFAULT_PASSWORD.equals(auth.password()));
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
