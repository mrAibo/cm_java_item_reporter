package com.mraibo.cminsight.db;

import java.util.List;
import java.util.Objects;

/**
 * The physical root tables backing one ItemType - <strong>every</strong> expected segment, not just the
 * current one.
 *
 * <p>IBM stores component roots in tables named {@code ICMUT<ComponentTypeID><SegmentID>}, and an ItemType
 * whose current {@code SegmentID} is {@code N} has roots in segments {@code 001..N}. A logical item's
 * ItemID may have been written into any of them over its life, so counting only the current segment
 * silently undercounts a multi-segment ItemType. This type exists to make that undercount impossible to
 * express: it carries the whole ordered list, and its constructor refuses an empty one.
 *
 * <p>That refusal is the point. The reference backfill tool this project drew on intentionally rejected
 * {@code SegmentID != 1} because its <em>mutation</em> logic was single-segment; analytics must not inherit
 * that limitation by quietly using one table.
 *
 * @param itemTypeId     the IBM ItemTypeID, the same integer the metadata layer reports
 * @param itemTypeName   the ItemType name, for diagnostics and DTO construction; never a SQL identifier
 * @param componentTypeId the root component type id read from the ItemType definitions
 * @param currentSegmentId the ItemType's current SegmentID, validated to IBM's documented 1..36 range
 * @param rootTables     the generated physical table names, in segment order, one per segment 1..N, never
 *                       empty
 */
public record PhysicalSchema(
        int itemTypeId,
        String itemTypeName,
        int componentTypeId,
        int currentSegmentId,
        List<String> rootTables) {

    /** IBM documents SegmentID as a value in this inclusive range. */
    public static final int MIN_SEGMENT_ID = 1;
    public static final int MAX_SEGMENT_ID = 36;

    public PhysicalSchema {
        Objects.requireNonNull(itemTypeName, "itemTypeName");
        rootTables = List.copyOf(Objects.requireNonNull(rootTables, "rootTables"));
        if (currentSegmentId < MIN_SEGMENT_ID || currentSegmentId > MAX_SEGMENT_ID) {
            throw new IllegalArgumentException("SegmentID must be between " + MIN_SEGMENT_ID + " and "
                    + MAX_SEGMENT_ID + " for ItemType " + itemTypeId + " but was " + currentSegmentId);
        }
        if (rootTables.isEmpty()) {
            throw new IllegalArgumentException("ItemType " + itemTypeId + " has no physical root table; an"
                    + " ItemType without every expected segment must be reported as a failed mapping, never"
                    + " silently counted from no table");
        }
        if (rootTables.size() != currentSegmentId) {
            throw new IllegalArgumentException("ItemType " + itemTypeId + " has current SegmentID "
                    + currentSegmentId + " but " + rootTables.size() + " root table(s); every segment 1..N"
                    + " must be present or the mapping must fail");
        }
        for (String table : rootTables) {
            if (table == null || table.isBlank()) {
                throw new IllegalArgumentException("ItemType " + itemTypeId
                        + " has a blank root table name");
            }
        }
    }

    /** The number of physical root segments this mapping covers, equal to the current SegmentID. */
    public int segmentCount() {
        return rootTables.size();
    }

    /**
     * True when this ItemType is segmented, i.e. its older versions may live in more than one root table.
     *
     * <p>Simple to read and deliberately not used to skip the multi-segment code path: a single-segment
     * ItemType still goes through the same union-shaped query, so there is only one counting code path to
     * get right rather than two that can disagree.
     */
    public boolean segmented() {
        return rootTables.size() > 1;
    }
}
