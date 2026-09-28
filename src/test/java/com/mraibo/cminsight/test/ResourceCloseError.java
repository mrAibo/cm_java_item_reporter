package com.mraibo.cminsight.test;

/**
 * An {@link Error} (deliberately not an {@link Exception}) raised by a resource's {@code close()}.
 *
 * <p>Used by the regression tests for the review fix "an Error from a resource close must not burn a
 * capacity slot / must not stop the remaining resources from being released". Before that fix all the
 * cleanup paths caught only {@code Exception}, so an {@code Error} escaped mid-loop.
 */
final class ResourceCloseError extends Error {

    private static final long serialVersionUID = 1L;

    ResourceCloseError(String message) {
        super(message);
    }
}
