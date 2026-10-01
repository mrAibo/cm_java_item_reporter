package com.mraibo.cminsight.web.http;

import java.io.IOException;

/**
 * Raised when a request body is bigger than the caller's byte cap.
 *
 * <p>It is an {@link IOException} so it travels over the pinned {@code readBody(int)} signature, but a
 * distinct type so the router can answer {@code 413} instead of a generic {@code 500}.
 */
public final class BodyLimitExceededException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int maxBytes;

    public BodyLimitExceededException(int maxBytes) {
        super("Request body exceeds the configured limit of " + maxBytes + " bytes");
        this.maxBytes = maxBytes;
    }

    /** The cap that was exceeded, in bytes. */
    public int maxBytes() {
        return maxBytes;
    }
}
