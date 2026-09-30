package com.mraibo.cminsight.history;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One stored history snapshot as a list row: enough to render a History table without loading the detail.
 *
 * <h2>Why a summary is a separate type</h2>
 *
 * <p>A history list is a bounded page of rows, and a row needs identity, time, coverage and a total - not
 * several hundred per-ItemType results. Returning full entries for a page would make the list cost grow
 * with the repository rather than with the page, so the detail is fetched only when one snapshot is opened.
 *
 * <h2>Coverage travels with the total</h2>
 *
 * <p>{@code complete} and {@code partialFailureCount} are carried here rather than derived by a caller, so
 * a stored partial scan can never be listed as though its total covered the whole frozen list. That is the
 * same rule the live snapshot applies, restated for a row.
 *
 * @param id                  the store-owned identity
 * @param repositoryId        the repository the snapshot belongs to
 * @param repositoryDisplayName the safe display name captured at scan time, or empty
 * @param databaseVendor      the vendor name captured at scan time, or empty
 * @param capturedAt          when the scan published its snapshot
 * @param scanStartedAt       when the scan started
 * @param scanDurationMs      how long the scan took
 * @param anchorDate          the single database anchor the windows were computed against
 * @param scanId              the context-local scan sequence, diagnostic metadata only
 * @param itemTypeCount       how many ItemTypes the frozen list contained
 * @param partialFailureCount how many were partial or failed
 * @param complete            true when every frozen ItemType was measured completely
 * @param logicalItemsTotal   the distinct-ItemID total over the measured ItemTypes
 */
public record HistorySummary(HistoryId id,
                             String repositoryId,
                             String repositoryDisplayName,
                             String databaseVendor,
                             Instant capturedAt,
                             Instant scanStartedAt,
                             long scanDurationMs,
                             java.time.LocalDate anchorDate,
                             long scanId,
                             int itemTypeCount,
                             int partialFailureCount,
                             boolean complete,
                             long logicalItemsTotal) {

    public HistorySummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(scanStartedAt, "scanStartedAt");
        repositoryId = repositoryId == null ? "" : repositoryId.trim();
        repositoryDisplayName = repositoryDisplayName == null ? "" : repositoryDisplayName.trim();
        databaseVendor = databaseVendor == null ? "" : databaseVendor.trim();
        if (scanDurationMs < 0) {
            throw new IllegalArgumentException("scanDurationMs must not be negative");
        }
        if (itemTypeCount < 0 || partialFailureCount < 0) {
            throw new IllegalArgumentException("item type counts must not be negative");
        }
        if (partialFailureCount > itemTypeCount) {
            throw new IllegalArgumentException("a partial-failure count cannot exceed the ItemType count");
        }
    }

    /** True when this stored snapshot covers its whole frozen list. */
    public boolean covered() {
        return complete;
    }

    /** How long ago this snapshot was captured, in whole milliseconds, at {@code now}. */
    public long ageMillisAt(Instant now) {
        Objects.requireNonNull(now, "now");
        long millis = java.time.Duration.between(capturedAt, now).toMillis();
        return Math.max(millis, 0L);
    }

    /** True when this row belongs to {@code otherRepositoryId}. */
    public boolean belongsTo(String otherRepositoryId) {
        return otherRepositoryId != null && repositoryId.equals(otherRepositoryId.trim());
    }

    /** The display name, falling back to the repository id so a row is never nameless. */
    public String displayName() {
        return repositoryDisplayName.isEmpty() ? repositoryId : repositoryDisplayName;
    }

    /** A short value-free description for diagnostics. */
    public String describe() {
        return "history[" + id + ", " + repositoryId + ", captured " + capturedAt + ", "
                + (complete ? "complete" : partialFailureCount + " incomplete") + "]";
    }

    /** The anchor, when one was captured, for a caller that must not invent one. */
    public Optional<java.time.LocalDate> anchor() {
        return Optional.ofNullable(anchorDate);
    }
}
