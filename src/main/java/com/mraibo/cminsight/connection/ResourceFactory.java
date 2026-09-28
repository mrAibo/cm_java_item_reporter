package com.mraibo.cminsight.connection;

public interface ResourceFactory<T extends AutoCloseable> {
    T create() throws Exception;

    default boolean isHealthy(T resource) {
        return resource != null;
    }
}
