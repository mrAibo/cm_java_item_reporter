package com.mraibo.cminsight.core;

import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.metadata.MetadataRepository;
import com.mraibo.cminsight.retention.RetentionRepository;
import com.mraibo.cminsight.retention.RetentionPolicyInfo;

import java.util.List;
import java.util.Optional;

/**
 * The read services one repository context owns, in the containers the runtime actually needs.
 *
 * <h2>Why a record instead of a service locator</h2>
 *
 * <p>The obvious alternative - a {@code Map<String, Object>} of named services, or a generic
 * {@code lookup(Class)} - would make every call site a runtime cast and would let a future JDBC
 * statistics service be added without any type noticing. A record gives each service a declared,
 * compile-checked accessor, so a caller cannot obtain a service the context does not own and cannot
 * silently substitute one, and adding Goal 03's statistics service later is a new component rather than
 * a redesign.
 *
 * <p>Every component is optional on purpose. A context with no adapter (core-only mode), a context built
 * by a test, and a context whose adapter does not implement retention all remain constructible, and
 * {@link Optional#empty()} is the honest answer in each case. The alternative - requiring every service -
 * would force a placeholder object, which is exactly the "empty placeholder context" the goal forbids.
 *
 * <h2>Vendor neutrality</h2>
 *
 * <p>Only core and service interfaces appear here. No IBM CM type, and no {@code com.ibm} package, may
 * appear in this file or in any signature it declares; the build enforces that over all of
 * {@code src/main/java}.
 *
 * @param metadata      the read-only ItemType metadata service, or {@code null} when unavailable
 * @param retention     the read-only retention viewer service, or {@code null} when unavailable
 * @param cmDiagnostics the CM pool and adapter diagnostics, or {@code null} when there is no CM pool
 */
public record RepositoryServices(
        MetadataRepository metadata,
        RetentionRepository retention,
        CmPoolDiagnostics cmDiagnostics) {

    /** No services at all: core-only mode, or a context built before an adapter exists. */
    public static final RepositoryServices NONE = new RepositoryServices(null, null, null);

    /** True when neither read service is available, so a repository cannot answer metadata questions. */
    public boolean hasReadServices() {
        return metadata != null || retention != null;
    }

    public Optional<MetadataRepository> metadataService() {
        return Optional.ofNullable(metadata);
    }

    public Optional<RetentionRepository> retentionService() {
        return Optional.ofNullable(retention);
    }

    public Optional<CmPoolDiagnostics> cmDiagnosticsService() {
        return Optional.ofNullable(cmDiagnostics);
    }

    /**
     * The ItemType names assigned to one retention policy, or an empty list when the retention service is
     * unavailable.
     *
     * <p>Exists so a caller that only needs the mapping does not have to branch on the optional itself.
     * An unavailable service is reported as "no assignments known" rather than as an exception, because a
     * viewer must still render the rest of the page - the caller that has to distinguish the two asks
     * {@link #retentionService()} instead.
     */
    public List<String> itemTypeNamesForPolicy(String policyName) {
        return retention == null ? List.of() : retention.itemTypeNamesForPolicy(policyName);
    }

    /** The policies known to the retention service, or an empty list when it is unavailable. */
    public List<RetentionPolicyInfo> policies() {
        return retention == null ? List.of() : retention.listPolicies();
    }

    /** The ItemTypes known to the metadata service, or an empty list when it is unavailable. */
    public List<ItemTypeSummary> itemTypes() {
        return metadata == null ? List.of() : metadata.listItemTypes();
    }
}
