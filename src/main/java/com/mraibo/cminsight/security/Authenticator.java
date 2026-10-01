package com.mraibo.cminsight.security;

import com.mraibo.cminsight.config.WebAuthSettings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * HTTP Basic authentication with a brute-force guard.
 *
 * <p>Rules enforced here:
 *
 * <ul>
 *   <li>only the {@code Basic} scheme is considered, case-insensitively;</li>
 *   <li>base64 decoding is defensive: a malformed value, an oversized value or a decoded value with no
 *       {@code ':'} separator is a plain failure, never an exception with the value in it;</li>
 *   <li>comparison uses {@link MessageDigest#isEqual} on bytes, with {@code &} so both the user and the
 *       password comparison always run;</li>
 *   <li>the throttle key is the source address (a constant when the transport cannot supply one);</li>
 *   <li>a locked key is answered without ever touching the supplied credentials;</li>
 *   <li>no failure path exposes the submitted credential, the {@code Authorization} header or the
 *       decoded value, in any message, exception or result.</li>
 * </ul>
 */
public final class Authenticator {

    /** Realm name used in the {@code WWW-Authenticate} challenge. */
    public static final String REALM = "CM Insight";

    /** Throttle key used when the transport cannot supply a peer address. */
    public static final String UNKNOWN_REMOTE_KEY = "unknown-remote";

    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_AUTHORIZATION_CHARS = 4096;
    private static final int MAX_DECODED_BYTES = 1024;

    private final WebAuthSettings settings;
    private final LoginThrottle throttle;
    private final byte[] expectedUser;
    private final byte[] expectedPassword;
    private final boolean credentialsConfigured;

    public Authenticator(WebAuthSettings settings, LoginThrottle throttle) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.throttle = Objects.requireNonNull(throttle, "throttle");
        String user = settings.user();
        String password = settings.password();
        this.credentialsConfigured = user != null && !user.isBlank()
                && password != null && !password.isBlank();
        this.expectedUser = bytes(user);
        this.expectedPassword = bytes(password);
    }

    /**
     * Authenticates one request.
     *
     * @param authorizationHeader the raw {@code Authorization} header value, or null
     * @param remoteAddress       the peer address, or null
     */
    public AuthResult authenticate(String authorizationHeader, String remoteAddress) {
        String throttleKey = throttleKey(remoteAddress);
        if (throttle.isLocked(throttleKey)) {
            return AuthResult.lockedOut("locked_out");
        }
        if (!credentialsConfigured) {
            // Fail closed: without configured credentials nobody is authenticated, and this is not
            // counted as an attempt because no credential was compared.
            return AuthResult.failure("authentication_not_configured");
        }
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            // Not counted: no credential was submitted, so there is nothing to brute-force.
            return AuthResult.failure("missing_credentials");
        }
        String header = authorizationHeader.trim();
        if (header.length() > MAX_AUTHORIZATION_CHARS) {
            return reject(throttleKey, "malformed_authorization");
        }
        int separator = header.indexOf(' ');
        if (separator <= 0) {
            return reject(throttleKey, "malformed_authorization");
        }
        if (!"basic".equalsIgnoreCase(header.substring(0, separator))) {
            return reject(throttleKey, "malformed_authorization");
        }
        String encoded = header.substring(separator + 1).trim();
        if (encoded.isEmpty()) {
            return reject(throttleKey, "malformed_authorization");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            return reject(throttleKey, "malformed_authorization");
        }
        if (decoded.length == 0 || decoded.length > MAX_DECODED_BYTES) {
            Arrays.fill(decoded, (byte) 0);
            return reject(throttleKey, "malformed_authorization");
        }

        int colon = -1;
        for (int i = 0; i < decoded.length; i++) {
            if (decoded[i] == ':') {
                colon = i;
                break;
            }
        }
        if (colon < 0) {
            Arrays.fill(decoded, (byte) 0);
            return reject(throttleKey, "malformed_authorization");
        }

        byte[] providedUser = Arrays.copyOfRange(decoded, 0, colon);
        byte[] providedPassword = Arrays.copyOfRange(decoded, colon + 1, decoded.length);
        boolean matched = MessageDigest.isEqual(providedUser, expectedUser)
                & MessageDigest.isEqual(providedPassword, expectedPassword);
        Arrays.fill(decoded, (byte) 0);
        Arrays.fill(providedUser, (byte) 0);
        Arrays.fill(providedPassword, (byte) 0);

        if (!matched) {
            return reject(throttleKey, "invalid_credentials");
        }
        throttle.recordSuccess(throttleKey);
        return AuthResult.success(new Principal(settings.user()));
    }

    /** The throttle key for an address; never null, always bounded. */
    public String throttleKey(String remoteAddress) {
        if (remoteAddress == null) {
            return UNKNOWN_REMOTE_KEY;
        }
        String key = remoteAddress.trim();
        if (key.isEmpty()) {
            return UNKNOWN_REMOTE_KEY;
        }
        return key.length() > MAX_KEY_LENGTH ? key.substring(0, MAX_KEY_LENGTH) : key;
    }

    /** Remaining lockout for an address, for the router's {@code Retry-After} header. */
    public Duration retryAfter(String remoteAddress) {
        return throttle.remainingLockout(throttleKey(remoteAddress));
    }

    private AuthResult reject(String throttleKey, String reason) {
        throttle.recordFailure(throttleKey);
        return AuthResult.failure(reason);
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }
}
