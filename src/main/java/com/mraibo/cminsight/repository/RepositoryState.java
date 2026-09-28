package com.mraibo.cminsight.repository;

/** Explicit lifecycle state of the {@link RepositoryManager}. */
public enum RepositoryState {

    /** No repository has been activated yet. */
    NONE("no active repository"),
    /** A new context is being created. Nothing is published while this state holds. */
    INITIALIZING("initializing"),
    /** Exactly one context is published and usable. */
    ACTIVE("active"),
    /** The previous context is being closed as part of a switch. */
    SWITCHING("switching"),
    /** The manager itself is shutting down. */
    CLOSING("closing"),
    /** The last activation attempt failed. No context is published. */
    FAILED("failed"),
    /** The manager is closed and can no longer be used. */
    CLOSED("closed");

    private final String description;

    RepositoryState(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /**
     * True whenever there is no usable (ACTIVE) context, which is every state except {@link #ACTIVE}.
     *
     * <p>Note this is deliberately not the same as "nothing is published": during {@link #SWITCHING}
     * and {@link #CLOSING} a context may still be published while it is being torn down, and it is not
     * usable at that point either.
     */
    public boolean isInactive() {
        return this != ACTIVE;
    }
}
