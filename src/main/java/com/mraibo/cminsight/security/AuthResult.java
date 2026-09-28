package com.mraibo.cminsight.security;

/**
 * Outcome of one authentication attempt.
 *
 * @param authenticated whether the caller proved the configured credentials
 * @param principal     the authenticated identity, or null on failure
 * @param failureReason short internal reason code ({@code missing_credentials},
 *                      {@code malformed_authorization}, {@code invalid_credentials},
 *                      {@code locked_out}, {@code authentication_not_configured}); never contains
 *                      anything supplied by the caller and never sent verbatim to a client
 * @param lockedOut     true when the caller is currently throttled (429 semantics)
 */
public record AuthResult(boolean authenticated, Principal principal, String failureReason, boolean lockedOut) {

    /** A successful authentication. */
    public static AuthResult success(Principal principal) {
        return new AuthResult(true, principal, null, false);
    }

    /** A failed authentication that is not a lockout. */
    public static AuthResult failure(String reason) {
        return new AuthResult(false, null, reason, false);
    }

    /** A failure caused by the brute-force guard being active. */
    public static AuthResult lockedOut(String reason) {
        return new AuthResult(false, null, reason, true);
    }
}
