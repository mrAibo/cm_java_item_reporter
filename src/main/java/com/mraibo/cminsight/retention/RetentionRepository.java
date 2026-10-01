package com.mraibo.cminsight.retention;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the retention policies of one active repository.
 *
 * <p><strong>Read-only by contract, and this interface is where the product's most dangerous boundary
 * lives.</strong> Retention is the one area where IBM CM exposes operations that destroy customer data -
 * assigning a policy, creating one, deleting one, or backfilling expiry. V1/V2 expose none of them, so
 * this interface has no mutating method at all: not a create, not an assign, not an unassign, not a
 * delete, not an update, not a backfill. Retention administration is a later goal that requires its own
 * security review, and the read-only source guard over the IBM adapter enforces the same rule at the
 * call level, not only at this interface.
 *
 * <p>A second reason the interface is this narrow: a vendor SDK is free to expose a "list" method that
 * also mutates server-side state as a side effect (a cache rebuild, for example). The adapter is
 * therefore restricted to the calls the guard permits, and a caller cannot request anything else through
 * this type.
 *
 * <p>Implementations borrow a pooled session for the duration of a call and release it before returning.
 * No method retains a session and none blocks indefinitely.
 */
public interface RetentionRepository {

    /**
     * Every retention policy name of the repository, sorted case-insensitively.
     *
     * @throws com.mraibo.cminsight.repository.RepositoryException when the repository cannot be read;
     *         the message never contains a credential or a raw SDK message
     */
    List<String> listPolicyNames();

    /**
     * Every retention policy with its details and assigned ItemTypes, sorted case-insensitively by name.
     *
     * @throws com.mraibo.cminsight.repository.RepositoryException when the repository cannot be read
     */
    List<RetentionPolicyInfo> listPolicies();

    /**
     * One retention policy by name, with its assigned ItemTypes.
     *
     * @return the policy, or empty when the repository has no such policy
     * @throws com.mraibo.cminsight.repository.RepositoryException when the repository cannot be read
     */
    Optional<RetentionPolicyInfo> policy(String name);

    /**
     * The ItemTypes assigned to one retention policy, sorted case-insensitively.
     *
     * <p>Separate from {@link #policy(String)} because it is the reverse direction of the same mapping
     * and a caller may need it without the policy's own details. An unknown policy yields an empty list.
     *
     * @throws com.mraibo.cminsight.repository.RepositoryException when the repository cannot be read
     */
    List<String> itemTypeNamesForPolicy(String policyName);

    /**
     * True when this service can currently answer retention questions.
     *
     * <p>Cheap, no I/O, same contract as
     * {@link com.mraibo.cminsight.metadata.MetadataRepository#available()}.
     */
    boolean available();
}
