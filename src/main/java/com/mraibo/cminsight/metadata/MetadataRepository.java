package com.mraibo.cminsight.metadata;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the ItemType metadata of one active repository.
 *
 * <p>IBM CM has no part in this interface: the CM adapter implements it, and every SDK object is mapped
 * to an immutable DTO before it leaves the adapter. That is what lets the web layer, the tests and the
 * cache work with metadata without an SDK on the class path, and what makes a core-only build possible.
 *
 * <p><strong>Read-only by contract.</strong> There is deliberately no create, update, delete, assign,
 * unassign or write method of any kind, and adding one would break the V1/V2 guarantee the whole product
 * rests on. The HTTP layer offers no write route either.
 *
 * <p>Implementations are expected to borrow a pooled session for the duration of a call and release it
 * before returning - no method may retain a session, and none may block indefinitely. A caller that
 * needs to know whether the service can answer at all asks {@link #available()} rather than discovering
 * it from a failure.
 */
public interface MetadataRepository {

    /**
     * Every ItemType of the repository, sorted case-insensitively by name.
     *
     * @throws com.mraibo.cminsight.repository.RepositoryException when the underlying repository cannot
     *         be read; the message never contains a credential or a raw SDK message
     */
    List<ItemTypeSummary> listItemTypes();

    /**
     * One ItemType by name.
     *
     * @return the details, or empty when the repository has no such ItemType
     * @throws com.mraibo.cminsight.repository.RepositoryException when the repository cannot be read
     */
    Optional<ItemTypeInfo> itemType(String name);

    /**
     * True when this service can currently answer metadata questions.
     *
     * <p>Must be cheap and must not perform I/O: it is read by diagnostics and by route handlers that
     * have to choose between a real answer and an "adapter unavailable" error. It reports availability
     * of the SERVICE, not liveness of the server.
     */
    boolean available();
}
