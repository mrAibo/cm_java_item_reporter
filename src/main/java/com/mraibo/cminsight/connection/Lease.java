package com.mraibo.cminsight.connection;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class Lease<T> implements AutoCloseable {
    private final T value;
    private final Consumer<T> releaser;
    private final AtomicBoolean closed = new AtomicBoolean();

    Lease(T value, Consumer<T> releaser) {
        this.value = Objects.requireNonNull(value, "value");
        this.releaser = Objects.requireNonNull(releaser, "releaser");
    }

    public T value() {
        if (closed.get()) throw new IllegalStateException("Lease is already closed");
        return value;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) releaser.accept(value);
    }
}
