package com.mraibo.cminsight.repository;

/**
 * Raised when a repository lifecycle operation fails.
 *
 * <p>Checked on purpose: callers must decide what an operator sees when activation fails, and a
 * failed activation leaves the application with no active repository rather than a half-initialized
 * one. Messages must not contain credentials.
 */
public class RepositoryException extends Exception {

    private static final long serialVersionUID = 1L;

    public RepositoryException(String message) {
        super(message);
    }

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
