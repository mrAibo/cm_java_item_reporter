package com.mraibo.cminsight.report;

import com.mraibo.cminsight.metadata.ItemTypeSummary;

import java.util.List;

/**
 * The frozen, already-captured context a report built from the LIVE snapshot needs beyond the snapshot
 * itself.
 *
 * <h2>What the statistics snapshot does not carry</h2>
 *
 * <p>A {@code StatisticsSnapshot} is the analytics engine's own vocabulary: it has the repository id, the
 * measured metrics and the classification and retention-policy text frozen with each result, but it has no
 * repository display name and no database vendor. Those two are part of the safe metadata context the
 * application already holds, and a report that silently omitted them would be less useful than one that
 * states them. The ItemType list is carried here as well, as the fallback for a row whose captured text was
 * empty.
 *
 * <h2>They are handed in, never fetched</h2>
 *
 * <p>This record exists so the report package can receive those values as <em>values</em>. Nothing here
 * reaches a metadata repository, a CM session or a JDBC connection, and {@code ReportModel.fromSnapshot}
 * performs no lookup: a caller captures the context first, exactly as the task requires ("capture the
 * immutable input first"), and the report only ever renders what it was given. An ItemType that is absent
 * from {@link #metadata()} therefore renders an empty retention policy, which is the honest answer
 * "not captured", not a live query to fill it in.
 *
 * @param repositoryDisplayName the safe display name captured with the snapshot, or empty
 * @param databaseVendor        the database vendor name captured with the snapshot, or empty; never a URL
 * @param metadata              the frozen ItemType list captured with the snapshot, or empty
 */
public record ReportContext(String repositoryDisplayName, String databaseVendor,
                            List<ItemTypeSummary> metadata) {

    public ReportContext {
        repositoryDisplayName = repositoryDisplayName == null ? "" : repositoryDisplayName.trim();
        databaseVendor = databaseVendor == null ? "" : databaseVendor.trim();
        metadata = metadata == null ? List.of() : List.copyOf(metadata);
    }

    /** No captured context at all: every field renders its unknown form. */
    public static ReportContext empty() {
        return new ReportContext("", "", List.of());
    }

    /** True when this context carries no display metadata and no ItemType list. */
    public boolean isEmpty() {
        return repositoryDisplayName.isEmpty() && databaseVendor.isEmpty() && metadata.isEmpty();
    }
}
