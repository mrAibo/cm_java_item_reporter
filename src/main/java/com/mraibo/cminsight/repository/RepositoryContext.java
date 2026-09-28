package com.mraibo.cminsight.repository;

import com.mraibo.cminsight.config.RepositoryProfile;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RepositoryContext implements AutoCloseable {
    private final RepositoryProfile profile;
    private final AtomicBoolean closed = new AtomicBoolean();

    public RepositoryContext(RepositoryProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public RepositoryProfile profile() {
        if (closed.get()) throw new IllegalStateException("RepositoryContext is closed");
        return profile;
    }

    public boolean isClosed() { return closed.get(); }

    @Override
    public void close() { closed.set(true); }
}
