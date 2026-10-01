package com.mraibo.cminsight.ibm.internal;

import com.ibm.mm.sdk.common.DKDatastoreDefICM;
import com.ibm.mm.sdk.common.DKItemTypeDefICM;
import com.mraibo.cminsight.config.ClassificationRules;
import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CmSession;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.metadata.ItemTypeInfo;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.metadata.MetadataRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * The read-only ItemType service: IBM CM ItemTypes mapped, immediately, to IBM-free immutable DTOs.
 *
 * <h2>The read chain</h2>
 *
 * <p>This is the proven working path from the reference projects, with nothing added to it:
 * {@code datastoreDef()}, {@code listEntities(DK_ICM_USER_ITEM_TYPES)} for the names, then
 * {@code retrieveEntity(name)} per ItemType for the details. The name list comes from the definition
 * rather than from a hand-written query because the SDK's own listing is the only thing guaranteed to agree
 * with what the server will let this user see.
 *
 * <h2>Every session is leased, and none is retained</h2>
 *
 * <p>Sessions come from the core's {@link BoundedPool}, which is the only session source in the process, and
 * every call releases its lease before it returns - the try-with-resources block is the whole mechanism.
 * The SDK collection is drained into a plain array <em>inside</em> the lease, because a {@code dkCollection}
 * is backed by the live connection and iterating one after the return would be a use of a released
 * resource.
 *
 * <h2>Mapping rules that exist to prevent wrong answers</h2>
 *
 * <ul>
 *   <li><strong>Unknown numeric codes stay visible</strong> as {@code UNKNOWN(&lt;value&gt;)} rather than
 *       being guessed - see {@link IbmEnumNames}. A wrong classification label would be believed.</li>
 *   <li><strong>The integer id comes from {@code getIntId()}</strong>, not the lossy 16-bit
 *       {@code getId()}: an ItemType id above 32767 is a real thing on a large repository, and truncating it
 *       would collide two ItemTypes on one id.</li>
 *   <li><strong>Business classification comes from {@link ClassificationRules}</strong>, which is
 *       configuration. No SAP/NON-SAP rule is hard-coded here, so a customer's own naming scheme works
 *       without a code change.</li>
 *   <li><strong>Sorting is case-insensitive by name</strong>, with the name itself as the tie-breaker, so two
 *       ItemTypes differing only in case have a stable order instead of an arbitrary one.</li>
 * </ul>
 *
 * <h2>Absent versus broken</h2>
 *
 * <p>{@link #itemType(String)} returns {@link Optional#empty()} when the repository has no such ItemType -
 * the SDK's own "not found" answer - and throws only when the repository could not be read. The distinction
 * is what lets the API layer answer {@code 404} for the first and {@code 502} for the second, instead of
 * making a typo in a URL look like an outage.
 *
 * <h2>Every vendor failure retires the session, structurally</h2>
 *
 * <p>This service never classifies or remembers: every vendor call - the datastore definition, the name
 * listing, the per-ItemType retrieve, the retention-policy-name getter, and the mapping reads over the
 * returned ItemType - runs through {@link IbmCmApi} and its session-aware wrappers, which classify a
 * failure and mark the borrowed session unusable in the same method. A {@code DKNotExistException} is the
 * one benign control and retires nothing. There is deliberately no bare vendor accessor call left in this
 * class to forget the rule at.
 */
public final class CmMetadataService implements MetadataRepository {

    private final BoundedPool<CmSession> pool;
    private final ClassificationRules classifications;

    /**
     * @param pool            the initialized CM pool the core built for this activation; the only session
     *                        source
     * @param classifications the configured business-classification rules, or {@code null} to use the
     *                        {@link ClassificationRules} default label
     */
    public CmMetadataService(BoundedPool<CmSession> pool, ClassificationRules classifications) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.classifications = classifications;
    }

    /**
     * {@inheritDoc}
     *
     * <p>One lease covers the whole listing: the name array is drained first, then each ItemType is retrieved
     * on the same session. That keeps a listing of several hundred ItemTypes to one borrow instead of one
     * borrow per element, which is the difference between a page load and an outage on a busy repository.
     */
    @Override
    public List<ItemTypeSummary> listItemTypes() {
        List<ItemTypeInfo> details = loadAll();
        List<ItemTypeSummary> summaries = new ArrayList<>(details.size());
        for (ItemTypeInfo detail : details) {
            summaries.add(detail.summary());
        }
        return List.copyOf(summaries);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A pass-through detail read: it borrows, retrieves, maps and releases. Deliberately not cached here -
     * caching belongs to the per-context {@code MetadataCache}, which owns the freshness policy and the
     * single-flight rule, and a second cache in this class would be a second answer to the same question.
     */
    @Override
    public Optional<ItemTypeInfo> itemType(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String requested = name.trim();
        return execute("itemType(" + requested + ")", () -> {
            try (Lease<CmSession> lease = borrow()) {
                IbmCmSession session = IbmCmSessionFactory.icmSession(lease);
                DKItemTypeDefICM itemType = retrieveItemType(session, requested);
                lease.recordOperation();
                return itemType == null
                        ? Optional.<ItemTypeInfo>empty()
                        : Optional.of(guardedMap(session, itemType));
            }
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reports availability of the SERVICE, not liveness of the server: a cheap local read, no I/O. The
     * pool being open is the honest answer to "can this service answer at all"; whether the server responds
     * is discovered by asking it, and reporting a cached guess about that would be worse than saying nothing.
     */
    @Override
    public boolean available() {
        return pool.closeState() == com.mraibo.cminsight.core.CloseState.NOT_CLOSED;
    }

    @Override
    public String toString() {
        return "CmMetadataService[pool=" + pool.name() + "]";
    }

    // ---------------------------------------------------------------- internals

    /** Loads every ItemType with its details, ordered case-insensitively by name. */
    private List<ItemTypeInfo> loadAll() {
        return execute("listItemTypes", () -> {
            List<ItemTypeInfo> details = new ArrayList<>();
            try (Lease<CmSession> lease = borrow()) {
                IbmCmSession session = IbmCmSessionFactory.icmSession(lease);
                DKDatastoreDefICM definition = IbmCmApi.datastoreDef(session);
                // The scope-filtered listing is IBM's own public/user ItemType view. The unfiltered
                // overload would mix in views and system entities, which are not ItemTypes a viewer shows.
                String[] names = IbmCmApi.read(session, "listEntityNames",
                        () -> definition.listEntityNames(DKDatastoreDefICM.DK_ICM_USER_ITEM_TYPES));
                int read = 0;
                if (names != null) {
                    for (String name : names) {
                        if (name == null || name.isBlank()) {
                            continue;
                        }
                        DKItemTypeDefICM itemType = retrieveItemType(session, name.trim());
                        if (itemType == null) {
                            // Listed but not retrievable: a race with a concurrent definition change, or a
                            // view. Skipping it is right - reporting an empty ItemType would be a lie.
                            continue;
                        }
                        details.add(guardedMap(session, itemType));
                        read++;
                    }
                }
                lease.recordOperations(Math.max(1, read));
            }
            details.sort(ITEM_TYPE_ORDER);
            return List.copyOf(details);
        });
    }

    /**
     * Retrieves one ItemType by name, or {@code null} when the repository has none.
     *
     * <p>The cast to {@link DKItemTypeDefICM} is mandatory: {@code retrieveEntity} returns the base
     * {@code dkEntityDef}, which declares no ItemType properties at all.
     */
    private DKItemTypeDefICM retrieveItemType(IbmCmSession session, String name) {
        DKDatastoreDefICM definition = IbmCmApi.datastoreDef(session);
        Object entity = IbmCmApi.readOrAbsent(session, "retrieveEntity",
                () -> definition.retrieveEntity(name));
        if (entity instanceof DKItemTypeDefICM itemType) {
            return itemType;
        }
        return null;
    }

    /**
     * Maps one SDK ItemType to an immutable DTO, through the session-aware vendor wrapper.
     *
     * <p>The mapping reads a dozen vendor accessors directly, and a failure inside one of them is still a
     * vendor failure: it must retire the session rather than leave it healthy for the next borrower. Rather
     * than asking this method (or each accessor) to remember to call
     * {@link IbmCmSession#markUnusable(String)}, the whole mapping runs through {@link IbmCmApi#read},
     * which classifies AND marks in one place - see the class notes there. A bug in this adapter's own
     * mapping code retires a session too, which costs one re-creation; reusing a session whose vendor
     * object just misbehaved is the expensive mistake.
     */
    private ItemTypeInfo guardedMap(IbmCmSession session, DKItemTypeDefICM itemType) {
        return IbmCmApi.read(session, "mapItemType", () -> map(session, itemType));
    }

    /**
     * Maps one SDK ItemType to an immutable DTO.
     *
     * <p>Every read happens here, while the session is still leased, and every value is copied out: no SDK
     * object survives this method, which is what makes the DTO safe to cache, serialise and hand to the web
     * layer.
     */
    private ItemTypeInfo map(IbmCmSession session, DKItemTypeDefICM itemType) {
        String name = IbmEnumNames.text(itemType.getName());
        if (name.isEmpty()) {
            // A nameless ItemType cannot be addressed, looked up or displayed. Deriving a name from the id
            // would invent an identifier the server does not have, so the id is used as a label and marked as
            // such rather than presented as the real name.
            name = "#" + itemType.getIntId();
        }

        int rawClassification = itemType.getClassification();
        int versionControlCode = itemType.getVersionControl();
        int versioningTypeCode = itemType.getVersioningType();
        int defaultRmCode = itemType.getDefaultRMCode();
        int collectionCode = itemType.getDefaultCollCode();

        // The two retention accessors are the only ones on this class that declare checked exceptions: they
        // ask the policy tables, so a repository with retention disabled can answer "none" by throwing. A
        // retention policy is a nice-to-have here, so an unavailable answer degrades to empty and an
        // unreadable one is reported - never a broken ItemType listing.
        String policyName = retentionPolicyNameOf(session, itemType);

        return new ItemTypeInfo(
                name,
                IbmEnumNames.text(itemType.getDescription()),
                itemType.getIntId(),
                IbmEnumNames.classificationName(rawClassification),
                rawClassification,
                classificationOf(name),
                IbmEnumNames.idText(itemType.getXDOClassID()),
                IbmEnumNames.text(itemType.getXDOClassName()),
                IbmEnumNames.idText(defaultRmCode),
                IbmEnumNames.idText(collectionCode),
                IbmEnumNames.versionControlName(versionControlCode),
                versionControlCode,
                IbmEnumNames.versioningTypeName(versioningTypeCode),
                versioningTypeCode,
                IbmEnumNames.legacyRetentionSummary(itemType.getDefaultItemRetention(),
                        itemType.getDefaultRetentionUnit()),
                policyName);
    }

    /**
     * The assigned retention policy name, or empty.
     *
     * <p>Read through {@link IbmCmApi#readOrAbsent} - the SAME session-aware vendor wrapper as every other
     * SDK call - because "this ItemType has no retention policy" is an answer the SDK may deliver as a
     * {@code DKNotExistException}, and it must not be reported as a failure. Any other failure is surfaced,
     * because silently reporting "no policy" for an unreadable policy table would tell an operator that
     * nothing is retained when the opposite may be true.
     *
     * <p>Going through the wrapper is also what makes this getter obey the Goal 02A invariant: a failure
     * that is not "not found" is classified backend-unusable AND marks this session unusable in the same
     * method, so the lease return retires the session instead of handing a session with an unreadable
     * policy table to the next borrower.
     *
     * <p>Package-private (rather than private) so the IBM test suite can drive this exact read with a fake
     * ItemType, which is otherwise impossible: the vendor class hands out its accessors from a concrete
     * type the suite cannot construct for real. Same seam rationale as {@link IcmDatastore}.
     */
    static String retentionPolicyNameOf(IbmCmSession session, DKItemTypeDefICM itemType) {
        String name = IbmCmApi.readOrAbsent(session, "itemTypeRetentionPolicyName",
                itemType::getItemTypeRetentionPolicyName);
        return IbmEnumNames.text(name);
    }

    /** The configured business classification of one ItemType name. */
    private String classificationOf(String itemTypeName) {
        return classifications == null
                ? ClassificationRules.FALLBACK_LABEL
                : classifications.classify(itemTypeName);
    }

    /**
     * Borrows from the bounded pool, translating backpressure into a clean adapter failure.
     *
     * <p>An exhausted pool is reported as a busy repository, which is what it is. The one thing that must NOT
     * happen here is opening another session: the borrow timeout is the hard bound doing its job, and an
     * out-of-pool session would breach it.
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
     * <p>The translation happens here, once, at the boundary between the service and its consumers. Inside,
     * {@link IbmCmFailure} carries the original SDK failure; outside, only {@link IbmCmReadException} travels,
     * whose message {@link IbmErrorSanitizer} produced. That is what keeps a vendor message out of an HTTP
     * response without asking a handler to remember to redact.
     */
    private <T> T execute(String operation, ServiceCall<T> call) {
        try {
            return call.run();
        } catch (IbmCmReadException alreadyClean) {
            throw alreadyClean;
        } catch (IbmCmFailure failure) {
            throw new IbmCmReadException(failure.category(),
                    "Repository metadata read '" + operation + "' failed: " + failure.getMessage(), failure);
        } catch (RuntimeException unexpected) {
            // An unexpected runtime failure is still sanitised: only its class name travels, because its
            // message is not something this adapter produced and therefore not something it can vouch for.
            throw new IbmCmReadException("cm", "Repository metadata read '" + operation + "' failed: "
                    + unexpected.getClass().getSimpleName(), unexpected);
        }
    }

    /** One service operation. */
    @FunctionalInterface
    private interface ServiceCall<T> {
        T run();
    }

    /** The documented ordering: case-insensitive by name, name as the tie-breaker. */
    private static final Comparator<ItemTypeInfo> ITEM_TYPE_ORDER =
            (left, right) -> IbmEnumNames.byName(left.name(), right.name());
}
