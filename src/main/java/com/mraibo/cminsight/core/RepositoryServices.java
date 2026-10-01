package com.mraibo.cminsight.core;

import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.metadata.MetadataRepository;
import com.mraibo.cminsight.retention.RetentionRepository;
import com.mraibo.cminsight.retention.RetentionPolicyInfo;
import com.mraibo.cminsight.statistics.StatisticsRepository;

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
 * <h2>Analytics is a service like any other, and still optional</h2>
 *
 * <p>{@code statistics} is the typed analytics service (Goal 03). It is nullable exactly like the others:
 * when {@code feature.statistics=false}, when the JDBC driver is absent, when the JDBC credential is
 * missing or when the database is unreachable, the IBM CM halves must stay fully usable, so a context with
 * no analytics capability is a normal context rather than a broken one. The three-argument constructor is
 * kept so every pre-Goal-03 construction site and test compiles unchanged.
 *
 * @param metadata      the read-only ItemType metadata service, or {@code null} when unavailable
 * @param retention     the read-only retention viewer service, or {@code null} when unavailable
 * @param cmDiagnostics the CM pool and adapter diagnostics, or {@code null} when there is no CM pool
 * @param statistics    the analytics read/refresh service, or {@code null} when analytics is disabled or
 *                      unavailable; its presence never affects the CM halves
 */
public record RepositoryServices(
        MetadataRepository metadata,
        RetentionRepository retention,
        CmPoolDiagnostics cmDiagnostics,
        StatisticsRepository statistics) {

    /** No services at all: core-only mode, or a context built before an adapter exists. */
    public static final RepositoryServices NONE = new RepositoryServices(null, null, null);

    /** The pre-Goal-03 shape, kept so existing construction sites and tests are unchanged. */
    public RepositoryServices(MetadataRepository metadata,
                              RetentionRepository retention,
                              CmPoolDiagnostics cmDiagnostics) {
        this(metadata, retention, cmDiagnostics, null);
    }

    /**
     * True when no read service at all is available, so a repository cannot answer any read question.
     *
     * <p>Analytics counts: a context that can only report statistics is still a context a caller can read
     * something from, and reporting it as "no read services" would send a reader looking for a broken
     * activation instead of a disabled metadata half.
     */
    public boolean hasReadServices() {
        return metadata != null || retention != null || statistics != null;
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
     * The analytics read/refresh service, or empty when this repository has no analytics capability.
     *
     * <p>Empty is the honest answer in four different situations that must all leave the CM halves working:
     * the feature is switched off, the JDBC driver is absent, the JDBC credential is missing or unreadable,
     * and the database is unreachable. A caller that needs to distinguish them asks the service's own
     * availability verdict - which is why an empty optional here is not an error state.
     */
    public Optional<StatisticsRepository> statisticsRepository() {
        return Optional.ofNullable(statistics);
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
