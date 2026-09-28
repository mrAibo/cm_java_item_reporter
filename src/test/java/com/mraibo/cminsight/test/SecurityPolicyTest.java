package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.SecretRef;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.config.WebAuthSettings;
import com.mraibo.cminsight.security.SecurityPolicy;

import java.util.List;
import java.util.Map;

/** The exposure guard: literal-only loopback detection and fail-closed credential checks. */
public class SecurityPolicyTest {

    private static WebAuthSettings developmentDefaults() {
        return WebAuthSettings.resolve(AppConfig.empty(), new SecretResolver(Map.of(), null));
    }

    private static WebAuthSettings blankCredentials() {
        return new WebAuthSettings("", "", missingRef(), missingRef(), false, List.of());
    }

    /** A MISSING reference, which is how the record is built without a package-private constructor. */
    private static SecretRef missingRef() {
        return new SecretResolver(Map.of(), null).classify(null, null, null, "web.auth.credential");
    }

    public void loopbackLiteralsAreRecognised() {
        Assert.assertTrue(SecurityPolicy.isLoopbackLiteral("127.0.0.1"), "127.0.0.1 is loopback");
        Assert.assertTrue(SecurityPolicy.isLoopbackLiteral("127.5.5.5"), "every 127.0.0.0/8 address is loopback");
        Assert.assertTrue(SecurityPolicy.isLoopbackLiteral("127.0.0.0"), "127.0.0.0 is loopback");
        Assert.assertTrue(SecurityPolicy.isLoopbackLiteral("localhost"), "the name localhost is loopback");
        Assert.assertTrue(SecurityPolicy.isLoopbackLiteral("  LOCALHOST  "), "the name is trimmed and case-insensitive");
        Assert.assertTrue(SecurityPolicy.isLoopbackLiteral("::1"), "the short IPv6 loopback is loopback");
        Assert.assertTrue(SecurityPolicy.isLoopbackLiteral("[::1]"), "a bracketed IPv6 loopback is loopback");
        Assert.assertTrue(SecurityPolicy.isLoopbackLiteral("0:0:0:0:0:0:0:1"), "the expanded IPv6 loopback is loopback");
        Assert.assertTrue(SecurityPolicy.isLoopbackLiteral("::1%lo0"), "a zone-qualified IPv6 loopback is loopback");
    }

    public void everythingElseIsTreatedAsNonLoopback() {
        Assert.assertFalse(SecurityPolicy.isLoopbackLiteral("0.0.0.0"), "the wildcard bind is not loopback");
        Assert.assertFalse(SecurityPolicy.isLoopbackLiteral("::"), "the IPv6 wildcard is not loopback");
        Assert.assertFalse(SecurityPolicy.isLoopbackLiteral("::ffff:127.0.0.1"),
                "a mapped address is not treated as loopback");
        Assert.assertFalse(SecurityPolicy.isLoopbackLiteral("192.168.1.10"), "a LAN address is not loopback");
        Assert.assertFalse(SecurityPolicy.isLoopbackLiteral("128.0.0.1"), "only the 127/8 range is loopback");
        Assert.assertFalse(SecurityPolicy.isLoopbackLiteral("127.0.0.256"), "an invalid octet is not loopback");
        Assert.assertFalse(SecurityPolicy.isLoopbackLiteral("127.0.0.1.evil.example"), "a longer name is not loopback");
        Assert.assertFalse(SecurityPolicy.isLoopbackLiteral("localhost.evil.example"), "a suffix trick is not loopback");
        Assert.assertFalse(SecurityPolicy.isLoopbackLiteral("localhost."), "a trailing dot is not the literal");
        Assert.assertFalse(SecurityPolicy.isLoopbackLiteral(""), "an empty bind is not loopback");
        Assert.assertFalse(SecurityPolicy.isLoopbackLiteral("   "), "a blank bind is not loopback");
        Assert.assertFalse(SecurityPolicy.isLoopbackLiteral(null), "a null bind is not loopback");
    }

    public void isLoopbackLiteralNeverResolvesNamesAndNeverThrows() {
        // A resolver-based implementation would throw UnknownHostException here, or time out. The
        // literal-only implementation answers instantly and always with "false".
        String unresolvable = "cm-insight-does-not-exist-4f8a1b.invalid";
        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            Assert.assertFalse(SecurityPolicy.isLoopbackLiteral(unresolvable),
                    "an unresolvable host name must not be loopback");
            Assert.assertFalse(SecurityPolicy.isLoopbackLiteral("127.0.0.x"),
                    "a name that only looks like an address must not be loopback");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        Assert.assertTrue(elapsedMs < 2000,
                "400 pure string checks must not involve a DNS lookup (took " + elapsedMs + " ms)");
    }

    /**
     * Regression (t5 F7): a zone suffix is an IPv6 concept, so stripping {@code %...} from an
     * IPv4-looking spelling must not promote it to loopback.
     *
     * <p>Fails against the pre-fix code, which removed the zone unconditionally and therefore called
     * {@code 127.0.0.1%00} a loopback literal.
     */
    public void ipv4SpellingsWithAZoneSuffixAreNotLoopback() {
        Assert.assertFalse(SecurityPolicy.isLoopbackLiteral("127.0.0.1%00"),
                "a zone suffix on an IPv4 spelling is not a loopback literal");
        Assert.assertFalse(SecurityPolicy.isLoopbackLiteral("127.0.0.1%lo0"),
                "any zone suffix on an IPv4 spelling is not a loopback literal");
        Assert.assertFalse(SecurityPolicy.isLoopbackLiteral("127.5.5.5%eth0"),
                "the rule applies across the whole 127/8 range");
        Assert.assertTrue(SecurityPolicy.isLoopbackLiteral("::1%lo0"),
                "a genuine IPv6 zone-qualified loopback is still accepted");
        Assert.assertTrue(SecurityPolicy.isLoopbackLiteral("[::1%lo0]"),
                "the bracketed IPv6 form is still accepted");
        Assert.assertTrue(SecurityPolicy.isLoopbackLiteral("0:0:0:0:0:0:0:1%eth0"),
                "the expanded IPv6 form is still accepted");
    }

    public void defaultCredentialsAreRefusedOnANonLoopbackBind() {
        WebAuthSettings defaults = developmentDefaults();
        Assert.assertTrue(SecurityPolicy.usesDefaultCredentials(defaults), "admin/admin is recognised");
        Assert.assertTrue(defaults.defaultCredentials(), "the settings flag the default pair");

        SecurityPolicy.validateWebExposure(defaults, "127.0.0.1", 8080);
        SecurityPolicy.validateWebExposure(defaults, "127.5.5.5", 8080);
        SecurityPolicy.validateWebExposure(defaults, "::1", 8080);
        SecurityPolicy.validateWebExposure(defaults, "localhost", 8080);

        IllegalStateException failure = Assert.assertThrows(IllegalStateException.class,
                () -> SecurityPolicy.validateWebExposure(defaults, "0.0.0.0", 8080),
                "admin/admin must be refused on a wildcard bind");
        Assert.assertTrue(failure.getMessage().contains("admin/admin development credentials"),
                "the message explains the refusal: " + failure.getMessage());
        Assert.assertTrue(failure.getMessage().contains("0.0.0.0"), "the message names the bind address: "
                + failure.getMessage());

        Assert.assertThrows(IllegalStateException.class,
                () -> SecurityPolicy.validateWebExposure(defaults, "cm-insight.example", 8080),
                "admin/admin is refused on any host name too");
        Assert.assertThrows(IllegalStateException.class,
                () -> SecurityPolicy.validateWebExposure(defaults, "::", 8080),
                "admin/admin is refused on the IPv6 wildcard too");
    }

    public void realCredentialsAreAllowedOnANonLoopbackBind() {
        WebAuthSettings real = TestSupport.credentials("ops", "Correct-Horse-8842");
        Assert.assertFalse(SecurityPolicy.usesDefaultCredentials(real), "explicit credentials are not the default");
        SecurityPolicy.validateWebExposure(real, "0.0.0.0", 8080);
        SecurityPolicy.validateWebExposure(real, "cm-insight.example", 8443);
    }

    public void blankCredentialsAreRefusedEverywhere() {
        WebAuthSettings blank = blankCredentials();
        IllegalStateException failure = Assert.assertThrows(IllegalStateException.class,
                () -> SecurityPolicy.validateWebExposure(blank, "127.0.0.1", 8080),
                "blank credentials are refused even on loopback");
        Assert.assertTrue(failure.getMessage().contains("blank credentials"),
                "the message explains the refusal: " + failure.getMessage());

        WebAuthSettings blankPassword = new WebAuthSettings("admin", "   ",
                missingRef(), missingRef(), false, List.of());
        Assert.assertThrows(IllegalStateException.class,
                () -> SecurityPolicy.validateWebExposure(blankPassword, "0.0.0.0", 8080),
                "a blank password is refused even with a real user name");
    }

    public void exposureWarningsExplainTheRiskWithoutPrintingSecrets() {
        WebAuthSettings defaults = developmentDefaults();
        List<String> defaultWarnings = SecurityPolicy.exposureWarnings(defaults, "0.0.0.0", 8080);
        Assert.assertTrue(defaultWarnings.stream().anyMatch(w -> w.contains("admin/admin")),
                "the default pair is called out: " + defaultWarnings);

        WebAuthSettings real = TestSupport.credentials("ops", "Correct-Horse-8842");
        List<String> nonLoopback = SecurityPolicy.exposureWarnings(real, "0.0.0.0", 8080);
        Assert.assertTrue(nonLoopback.stream().anyMatch(w -> w.contains("non-loopback address")),
                "a public bind is called out: " + nonLoopback);
        Assert.assertTrue(nonLoopback.stream().noneMatch(w -> w.contains("Correct-Horse-8842")),
                "warnings never contain a credential: " + nonLoopback);

        Assert.assertTrue(SecurityPolicy.exposureWarnings(real, "127.0.0.1", 8080).isEmpty(),
                "loopback with real credentials is unremarkable");
        Assert.assertTrue(SecurityPolicy.exposureWarnings(blankCredentials(), "127.0.0.1", 8080).stream()
                        .anyMatch(w -> w.contains("blank")),
                "blank credentials are called out");
    }
}
