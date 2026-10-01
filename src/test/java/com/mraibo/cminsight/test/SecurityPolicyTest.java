package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.SecretRef;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.config.WebAuthSettings;
import com.mraibo.cminsight.security.SecurityPolicy;

import java.util.List;
import java.util.Map;
import java.util.Properties;

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

    /**
     * Goal 01A (F): a non-loopback plain-HTTP bind is refused even with real credentials, unless the
     * operator accepts the risk explicitly with {@code web.allowInsecureHttp=true}.
     *
     * <p>This test replaces the Goal 01 test {@code realCredentialsAreAllowedOnANonLoopbackBind}, which
     * asserted the OLD policy: real credentials alone unlocked a non-loopback bind over plain HTTP.
     * Goal 01A F closes that hole, because HTTP Basic sends reusable credentials in cleartext on every
     * request, and it requires the opt-in to be explicit rather than implied by a long password.
     *
     * <p>Fails against the pre-Goal-01A {@code SecurityPolicy}, where the third rule did not exist and
     * the two {@code validateWebExposure(..., false)} calls below returned normally.
     */
    public void realCredentialsNeedTheInsecureOverrideOnANonLoopbackBind() {
        WebAuthSettings real = TestSupport.credentials("ops", "Correct-Horse-8842");
        Assert.assertFalse(SecurityPolicy.usesDefaultCredentials(real), "explicit credentials are not the default");

        IllegalStateException wildcard = Assert.assertThrows(IllegalStateException.class,
                () -> SecurityPolicy.validateWebExposure(real, "0.0.0.0", 8080),
                "a wildcard bind over plain HTTP is refused even with real credentials");
        Assert.assertTrue(wildcard.getMessage().contains(SecurityPolicy.KEY_ALLOW_INSECURE_HTTP),
                "the refusal names the explicit opt-in: " + wildcard.getMessage());
        Assert.assertFalse(wildcard.getMessage().contains("Correct-Horse-8842"),
                "the refusal never prints a credential: " + wildcard.getMessage());

        IllegalStateException named = Assert.assertThrows(IllegalStateException.class,
                () -> SecurityPolicy.validateWebExposure(real, "cm-insight.example", 8443),
                "a host name is not loopback either, so it is refused too");
        Assert.assertTrue(named.getMessage().contains(SecurityPolicy.KEY_ALLOW_INSECURE_HTTP),
                "the refusal names the explicit opt-in: " + named.getMessage());

        // The explicit opt-in is the only thing that accepts the plaintext transport risk.
        SecurityPolicy.validateWebExposure(real, "0.0.0.0", 8080, true);
        SecurityPolicy.validateWebExposure(real, "cm-insight.example", 8443, true);

        // The override never weakens the other two fail-closed rules.
        WebAuthSettings defaults = developmentDefaults();
        IllegalStateException adminAdmin = Assert.assertThrows(IllegalStateException.class,
                () -> SecurityPolicy.validateWebExposure(defaults, "0.0.0.0", 8080, true),
                "the insecure override does not permit admin/admin on a non-loopback bind");
        Assert.assertTrue(adminAdmin.getMessage().contains("admin/admin development credentials"),
                "the refusal still explains the credential problem: " + adminAdmin.getMessage());
        Assert.assertThrows(IllegalStateException.class,
                () -> SecurityPolicy.validateWebExposure(blankCredentials(), "0.0.0.0", 8080, true),
                "the insecure override does not permit blank credentials");
        Assert.assertThrows(IllegalStateException.class,
                () -> SecurityPolicy.validateWebExposure(blankCredentials(), "127.0.0.1", 8080, true),
                "blank credentials stay refused on loopback even with the override");

        Assert.assertTrue(SecurityPolicy.exposureWarnings(real, "0.0.0.0", 8080, true).stream()
                        .anyMatch(warning -> warning.contains(SecurityPolicy.INSECURE_HTTP_MARKER)),
                "an accepted insecure exposure is reported with the shared marker");
        Assert.assertTrue(SecurityPolicy.exposureWarnings(real, "0.0.0.0", 8080).stream()
                        .anyMatch(warning -> warning.contains(SecurityPolicy.KEY_ALLOW_INSECURE_HTTP)),
                "without the override the warning names what would be required");
        Assert.assertTrue(SecurityPolicy.exposureWarnings(real, "127.0.0.1", 8080, true).stream()
                        .anyMatch(warning -> warning.contains("has no effect on the loopback bind")),
                "the override is called out as unnecessary on loopback");
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

    /**
     * Regression (t9, finding F-1): the exposure refusal covers <em>any part</em> of the credential
     * that is the built-in development default, not only the {@code admin}/{@code admin} pair.
     *
     * <p>A blank {@code web.auth.password} is treated as unset and silently falls back to the
     * published development password. The pre-fix policy compared the pair only, so
     * {@code web.bind=0.0.0.0} + {@code web.auth.user=operator} + a blank password + the insecure
     * opt-in started, and {@code GET /api/info} answered {@code 200} to {@code operator}/{@code admin}
     * over plaintext on a routable interface. Fails against that pre-fix policy.
     */
    public void anyPartOfTheCredentialThatIsTheDevelopmentDefaultIsRefusedOffLoopback() {
        SecretResolver resolver = new SecretResolver(Map.of(), null);

        // The hole: a configured user name and a blank, silently defaulted password.
        Properties hole = new Properties();
        hole.setProperty("web.auth.user", "operator");
        hole.setProperty("web.auth.password", "");
        WebAuthSettings defaultPassword = WebAuthSettings.resolve(AppConfig.fromProperties(hole), resolver);
        Assert.assertEquals(SecretRef.Source.DEFAULT, defaultPassword.passwordSource().source(),
                "a blank password is reported as the development default, not as a resolved secret");
        Assert.assertFalse(SecurityPolicy.usesDefaultCredentials(defaultPassword),
                "this is not the admin/admin pair");
        Assert.assertTrue(SecurityPolicy.usesDefaultPassword(defaultPassword),
                "the effective password is still the published development default");
        Assert.assertTrue(SecurityPolicy.usesAnyDevelopmentDefault(defaultPassword),
                "any default part makes the credential a development credential");

        // Loopback keeps the supported development fallback, with a warning that names the field.
        SecurityPolicy.validateWebExposure(defaultPassword, "127.0.0.1", 8080);
        Assert.assertTrue(SecurityPolicy.exposureWarnings(defaultPassword, "127.0.0.1", 8080).stream()
                        .anyMatch(w -> w.contains("built-in development password")),
                "the warning names the defaulted password: "
                        + SecurityPolicy.exposureWarnings(defaultPassword, "127.0.0.1", 8080));

        // Off loopback it is refused, the insecure opt-in cannot unlock it, and the message says why.
        IllegalStateException failure = Assert.assertThrows(IllegalStateException.class,
                () -> SecurityPolicy.validateWebExposure(defaultPassword, "0.0.0.0", 8080, true),
                "a defaulted password is refused on a non-loopback bind even with the insecure opt-in");
        Assert.assertTrue(failure.getMessage().contains("web password"),
                "the refusal names the credential field: " + failure.getMessage());
        Assert.assertTrue(failure.getMessage().contains("built-in development default"),
                "the refusal names the development default: " + failure.getMessage());
        Assert.assertTrue(failure.getMessage().contains(SecurityPolicy.KEY_ALLOW_INSECURE_HTTP),
                "the refusal explains that the opt-in does not help: " + failure.getMessage());
        Assert.assertTrue(failure.getMessage().contains("127.0.0.1"),
                "the refusal offers the loopback alternative: " + failure.getMessage());

        // The mirror image: a blank user name with a real password.
        Properties mirror = new Properties();
        mirror.setProperty("web.auth.password.env", "CM_TEST_WEB_PASSWORD");
        WebAuthSettings defaultUser = WebAuthSettings.resolve(AppConfig.fromProperties(mirror),
                new SecretResolver(Map.of("CM_TEST_WEB_PASSWORD", "Correct-Horse-8842"), null));
        Assert.assertEquals(SecretRef.Source.DEFAULT, defaultUser.userSource().source(),
                "a blank user name is reported as the development default");
        Assert.assertTrue(SecurityPolicy.usesDefaultUser(defaultUser), "the defaulted user is detected");
        Assert.assertTrue(SecurityPolicy.usesAnyDevelopmentDefault(defaultUser),
                "the user half alone is enough to make it a development credential");
        Assert.assertThrows(IllegalStateException.class,
                () -> SecurityPolicy.validateWebExposure(defaultUser, "0.0.0.0", 8080, true),
                "a defaulted user name is refused on a non-loopback bind too");

        // A password written out as the published value is the same credential.
        Properties published = new Properties();
        published.setProperty("web.auth.user", "operator");
        published.setProperty("web.auth.password", "admin");
        WebAuthSettings explicitDefault = WebAuthSettings.resolve(AppConfig.fromProperties(published), resolver);
        Assert.assertTrue(SecurityPolicy.usesDefaultPassword(explicitDefault),
                "the published password is recognised whatever its source");
        Assert.assertThrows(IllegalStateException.class,
                () -> SecurityPolicy.validateWebExposure(explicitDefault, "0.0.0.0", 8080, true),
                "the published password is refused on a non-loopback bind");
    }
}
