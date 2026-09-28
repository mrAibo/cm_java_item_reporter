package com.mraibo.cminsight.ibm.internal;

import com.ibm.mm.sdk.common.DKNotExistException;
import com.ibm.mm.sdk.common.DKPolicyMgmtICM;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM;
import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CmSession;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.core.CloseState;
import com.mraibo.cminsight.retention.RetentionPolicyInfo;
import com.mraibo.cminsight.retention.RetentionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * The read-only retention viewer: IBM CM retention policies and their ItemType assignments, mapped to
 * immutable DTOs.
 *
 * <h2>This is the product's most dangerous boundary</h2>
 *
 * <p>Retention is the one area where IBM CM exposes operations that destroy customer data: assigning a
 * policy, creating one, deleting one, backfilling expiry. V1/V2 expose none of them, and that is enforced in
 * three layers rather than by intention:
 *
 * <ol>
 *   <li>this class calls only {@code policyMgmt()}'s read members - {@code listRetentionPolicyNames},
 *       {@code listRetentionPolicies}, {@code retrieveRetentionPolicy}, {@code
 *       listItemTypeNamesByRetentionPolicy};</li>
 *   <li>the test-only SDK stub does not declare {@code add}, {@code update}, {@code del} or
 *       {@code clearCache} at all, so a write call cannot compile in this source set;</li>
 *   <li>the committed source guard fails the build on any of the known mutating member names under
 *       {@code src/ibm/java}.</li>
 * </ol>
 *
 * <h2>Sessions</h2>
 *
 * <p>Every call borrows from the core's {@link BoundedPool} and releases before returning. The SAME lease
 * covers the policy list and its assignments, which is both cheaper and more correct: the reverse mapping is
 * read while the policy it belongs to is still live, instead of from a snapshot taken at a different moment.
 *
 * <h2>Unknown values stay visible, never guessed</h2>
 *
 * <p>The retention type, the period unit and the expiration action are IBM enumerations whose numeric codes
 * are not recoverable from the SDK's class files. The DTO's readable field therefore carries
 * {@code UNKNOWN(<value>)} and the numeric field carries {@link IbmEnumNames#UNMAPPED_CODE}, and the
 * SDK's own constant name is reported alongside. A guessed unit would silently mis-state how long customer
 * data is kept, which is the single mistake a retention viewer must never make.
 *
 * <h2>Absent versus broken</h2>
 *
 * <p>{@link #policy(String)} returns empty when the repository has no such policy - including when the SDK
 * answers with a "not found" exception - and throws only when the repository could not be read.
 */
public final class CmRetentionService implements RetentionRepository {

    private final BoundedPool<CmSession> pool;

    /**
     * @param pool the initialized CM pool the core built for this activation; the only session source
     */
    public CmRetentionService(BoundedPool<CmSession> pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    /**
     * {@inheritDoc}
     *
     * <p>The SDK's own name listing, sorted case-insensitively. Deliberately taken from
     * {@code listRetentionPolicyNames} rather than read off the policy objects, because a name list is what a
     * picker needs and the full object listing is the expensive call.
     */
    @Override
    public List<String> listPolicyNames() {
        return execute("listPolicyNames", () -> {
            List<String> names = new ArrayList<>();
            try (Lease<CmSession> lease = borrow()) {
                IbmCmSession session = IbmCmSessionFactory.icmSession(lease);
                DKPolicyMgmtICM policies = IbmCmApi.policyMgmt(session);
                String[] listed = IbmCmApi.read(session, "listRetentionPolicyNames",
                        policies::listRetentionPolicyNames);
                if (listed != null) {
                    for (String name : listed) {
                        if (name != null && !name.isBlank()) {
                            names.add(name.trim());
                        }
                    }
                }
                lease.recordOperation();
            }
            names.sort(IbmEnumNames::byName);
            return List.copyOf(names);
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>One lease for the whole listing: the policy names are drained, then each policy is retrieved and its
     * ItemType assignments read on the same session. That is both the cheapest shape and the most consistent
     * one - the assignment view and the policy details come from the same live session rather than from two
     * borrows that a concurrent change could separate.
     */
    @Override
    public List<RetentionPolicyInfo> listPolicies() {
        return execute("listPolicies", () -> {
            List<RetentionPolicyInfo> policies = new ArrayList<>();
            try (Lease<CmSession> lease = borrow()) {
                IbmCmSession session = IbmCmSessionFactory.icmSession(lease);
                DKPolicyMgmtICM management = IbmCmApi.policyMgmt(session);
                Object[] listed = IbmCmApi.drain(session, IbmCmApi.read(session, "listRetentionPolicies",
                        management::listRetentionPolicies));
                int read = 0;
                for (Object element : listed) {
                    if (element instanceof DKRetentionPolicyDefICM policy) {
                        policies.add(map(session, management, policy));
                        read++;
                    }
                }
                lease.recordOperations(Math.max(1, read));
            }
            policies.sort((left, right) -> IbmEnumNames.byName(left.name(), right.name()));
            return List.copyOf(policies);
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>A pass-through detail read, borrowing for the duration and releasing before returning. Caching
     * belongs to the per-context {@code MetadataCache}, not here.
     */
    @Override
    public Optional<RetentionPolicyInfo> policy(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String requested = name.trim();
        return execute("policy(" + requested + ")", () -> {
            try (Lease<CmSession> lease = borrow()) {
                IbmCmSession session = IbmCmSessionFactory.icmSession(lease);
                DKPolicyMgmtICM management = IbmCmApi.policyMgmt(session);
                DKRetentionPolicyDefICM policy = IbmCmApi.readOrAbsent(session, "retrieveRetentionPolicy",
                        () -> management.retrieveRetentionPolicy(requested));
                lease.recordOperation();
                if (policy == null) {
                    return Optional.<RetentionPolicyInfo>empty();
                }
                return Optional.of(map(session, management, policy));
            }
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>An unknown policy yields an empty list, which is the SDK's own answer and not a failure. That
     * matters for the mapping direction: a caller walking every ItemType must not have one stale policy name
     * abort the page.
     */
    @Override
    public List<String> itemTypeNamesForPolicy(String policyName) {
        if (policyName == null || policyName.isBlank()) {
            return List.of();
        }
        String requested = policyName.trim();
        return execute("itemTypeNamesForPolicy(" + requested + ")", () -> {
            try (Lease<CmSession> lease = borrow()) {
                IbmCmSession session = IbmCmSessionFactory.icmSession(lease);
                DKPolicyMgmtICM management = IbmCmApi.policyMgmt(session);
                List<String> names = assignmentsOf(session, management, requested);
                lease.recordOperation();
                return names;
            }
        });
    }

    /** {@inheritDoc} A cheap local read: the pool being open, never a server probe. */
    @Override
    public boolean available() {
        return pool.closeState() == CloseState.NOT_CLOSED;
    }

    @Override
    public String toString() {
        return "CmRetentionService[pool=" + pool.name() + "]";
    }

    // ---------------------------------------------------------------- internals

    /**
     * The ItemType names assigned to one policy, sorted case-insensitively.
     *
     * <p>The ONLY method here whose SDK declaration leads with {@code DKNotExistException}, which is the
     * "no such policy" answer: it becomes an empty list, while any other failure propagates so an unreadable
     * assignment table is never reported as "nothing is assigned".
     */
    private List<String> assignmentsOf(IbmCmSession session, DKPolicyMgmtICM management, String policyName) {
        String[] assigned;
        try {
            assigned = IbmCmApi.read(session, "listItemTypeNamesByRetentionPolicy",
                    () -> management.listItemTypeNamesByRetentionPolicy(policyName));
        } catch (IbmCmFailure failure) {
            if (failure.getCause() instanceof DKNotExistException) {
                return List.of();
            }
            throw failure;
        }
        List<String> names = new ArrayList<>();
        if (assigned != null) {
            for (String name : assigned) {
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
        }
        names.sort(IbmEnumNames::byName);
        return List.copyOf(names);
    }

    /** Maps one SDK policy to an immutable DTO, including its assigned ItemTypes. */
    private RetentionPolicyInfo map(IbmCmSession session, DKPolicyMgmtICM management,
            DKRetentionPolicyDefICM policy) {
        String name = IbmEnumNames.text(policy.getName());

        DKRetentionPolicyDefICM.DK_ICM_RETENTION_TYPE retentionType = policy.getRetentionType();
        DKRetentionPolicyDefICM.DK_ICM_POLICY_TIME_UNIT retentionUnit = policy.getDefaultRetentionTimeUnit();
        DKRetentionPolicyDefICM.DK_ICM_POLICY_TIME_UNIT expirationUnit = policy.getDefaultExpirationTimeUnit();
        DKRetentionPolicyDefICM.DK_ICM_EXPIRATION_ACTION_TYPE expirationAction = policy.getExpirationAction();

        int retentionPeriod = policy.getRetentionTimePeriod();
        int expirationPeriod = policy.getExpirationTimePeriod();

        List<String> assigned = name.isEmpty() ? List.of() : assignmentsOf(session, management, name);

        return new RetentionPolicyInfo(
                name,
                IbmEnumNames.text(policy.getDescription()),
                policy.getID(),
                // The readable name is IBM's own constant identity, which is certain, and it is explicitly
                // NOT presented as a CM numeric code - see IbmEnumNames. The numeric field reports the
                // "not recoverable" sentinel together with the constant, so an operator sees exactly what the
                // SDK said and nothing this build invented.
                readableWithConstant(IbmEnumNames.retentionTypeName(retentionType),
                        IbmEnumNames.retentionTypeConstantName(retentionType)),
                IbmEnumNames.UNMAPPED_CODE,
                policy.isRetentionEnabled(),
                periodText(retentionPeriod, IbmEnumNames.policyTimeUnitConstantName(retentionUnit)),
                retentionPeriod,
                constantUnitText(IbmEnumNames.policyTimeUnitName(retentionUnit),
                        IbmEnumNames.policyTimeUnitConstantName(retentionUnit)),
                policy.isExpirationEnabled(),
                periodText(expirationPeriod, IbmEnumNames.policyTimeUnitConstantName(expirationUnit)),
                expirationPeriod,
                constantUnitText(IbmEnumNames.policyTimeUnitName(expirationUnit),
                        IbmEnumNames.policyTimeUnitConstantName(expirationUnit)),
                readableWithConstant(IbmEnumNames.expirationActionName(expirationAction),
                        IbmEnumNames.expirationActionConstantName(expirationAction)),
                IbmEnumNames.UNMAPPED_CODE,
                IbmEnumNames.text(policy.getDeleteExpiredItemsScheduleInformation()),
                policy.getDeleteExpiredItemsCommitCount(),
                policy.getDeleteExpiredItemsMaximumRows(),
                policy.getDeleteExpiredItemsMaximumDuration(),
                policy.isDeleteExpiredItemsForceCheckInEnabled(),
                assigned);
    }

    /**
     * Renders an enum whose CM numeric code is not recoverable.
     *
     * <p>The constant name is certain and the code is not, so the text says which is which instead of
     * blending them. An operator reading {@code UNKNOWN(FIXED_TIME)} knows the SDK's answer and knows this
     * build did not translate it; an operator reading {@code UNKNOWN(5)} would have no idea which.
     */
    private static String readableWithConstant(String unmapped, String constantName) {
        if (constantName == null || constantName.isEmpty()) {
            return unmapped;
        }
        return unmapped + "(" + constantName + ")";
    }

    /** A period as a human reading, keeping the SDK's unit constant visible. */
    private static String periodText(int period, String unitConstant) {
        if (unitConstant == null || unitConstant.isEmpty()) {
            return period < 0 ? "" : Integer.toString(period);
        }
        return period + " " + unitConstant;
    }

    /** A unit whose CM numeric code is not recoverable, with the SDK's constant kept visible. */
    private static String constantUnitText(String unmapped, String unitConstant) {
        if (unitConstant == null || unitConstant.isEmpty()) {
            return unmapped;
        }
        return unmapped + "(" + unitConstant + ")";
    }

    /**
     * Borrows from the bounded pool, translating backpressure into a clean adapter failure.
     *
     * <p>An exhausted pool is reported as a busy repository, which is what it is. Opening a session outside the
     * pool here is exactly what the hard bound exists to prevent.
     */
    private Lease<CmSession> borrow() {
        try {
            return pool.borrow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IbmCmReadException("interrupted",
                    "Interrupted while waiting for a CM session of pool '" + pool.name() + "'", interrupted);
        } catch (TimeoutException exhausted) {
            throw new IbmCmReadException("busy",
                    "All " + pool.configuredSize() + " CM sessions of pool '" + pool.name()
                            + "' are in use; the repository is busy", exhausted);
        }
    }

    /**
     * Runs one service operation, translating an adapter failure into the sanitised unchecked form.
     *
     * <p>The translation happens once, at the boundary between the service and its consumers, so no HTTP
     * handler has to remember to redact a vendor message - see {@link IbmCmReadException}.
     */
    private <T> T execute(String operation, ServiceCall<T> call) {
        try {
            return call.run();
        } catch (IbmCmReadException alreadyClean) {
            throw alreadyClean;
        } catch (IbmCmFailure failure) {
            throw new IbmCmReadException(failure.category(),
                    "Repository retention read '" + operation + "' failed: " + failure.getMessage(), failure);
        } catch (RuntimeException unexpected) {
            throw new IbmCmReadException("cm", "Repository retention read '" + operation + "' failed: "
                    + unexpected.getClass().getSimpleName(), unexpected);
        }
    }

    /** One service operation. */
    @FunctionalInterface
    private interface ServiceCall<T> {
        T run();
    }
}
