package com.mraibo.cminsight.connection;

/**
 * Raised when a pool cannot create a resource it has already reserved capacity for.
 *
 * <p>Unchecked on purpose: by the time this surfaces the caller already holds a reserved slot, and
 * there is nothing useful to recover from other than reporting the underlying cause.
 */
public class PoolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PoolException(String message) {
        super(message);
    }

    public PoolException(String message, Throwable cause) {
        super(message, cause);
    }
}
