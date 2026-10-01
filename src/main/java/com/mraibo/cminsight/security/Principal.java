package com.mraibo.cminsight.security;

import java.util.Objects;

/**
 * The authenticated caller of a request.
 *
 * <p>Deliberately tiny: the runtime has one static credential pair, and no role model yet. Adding a
 * role here later must not require touching the routing layer.
 */
public record Principal(String user) {

    public Principal {
        Objects.requireNonNull(user, "user");
        if (user.isBlank()) {
            throw new IllegalArgumentException("Principal user must not be blank");
        }
    }
}
