package com.mraibo.cminsight.connection;

/**
 * IBM-independent abstraction. The implementation may wrap DKDatastoreICM,
 * but SDK types must not escape the adapter boundary.
 */
public interface CmSession extends AutoCloseable {
    String repositoryId();
    boolean isHealthy();
    @Override void close();
}
