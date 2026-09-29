package com.mraibo.cminsight.statistics;

import java.time.LocalDate;
import java.util.Optional;

/**
 * The four calendar windows one statistics scan reports, frozen to a single anchor date.
 *
 * <h2>One anchor for the whole scan</h2>
 *
 * <p>The anchor is the <strong>database's</strong> current date, read once when the scan starts. Every
 * ItemType in that snapshot is measured against this same object, so a scan that runs across midnight
 * cannot report an ItemType counted "today" under one calendar and another counted "today" under the next.
 * No JVM-local date participates: the host's clock and time zone are not the database's, and a scan whose
 * boundaries moved with the host would be unreproducible.
 *
 * <h2>Why the boundaries are encoded, not decoded</h2>
 *
 * <p>The stored date key is a fixed-width, chronologically sortable string, so a window test is a string
 * comparison and this class only has to produce the two boundary strings. Nothing parses an ItemID.
 *
 * <h2>An unrepresentable boundary is a real answer</h2>
 *
 * <p>The encoding covers 2000-2199. If the anchor is outside that range - or if an offset walks a boundary
 * out of it - the affected windows cannot be expressed, and {@link #unavailableReason()} says so instead of
 * substituting a different century mapping. In that case the total logical-item count is still available,
 * because it never uses the date key.
 *
 * @param anchor the database's current date, the one calendar anchor for the scan
 * @param todayStart encoded key for {@code anchor}; empty when unrepresentable
 * @param todayEnd   encoded key for {@code anchor + 1 day}; empty when unrepresentable
 * @param last7Start encoded key for {@code anchor - 6 days}; empty when unrepresentable
 * @param last30Start encoded key for {@code anchor - 29 days}; empty when unrepresentable
 * @param yearStart  encoded key for {@code January 1} of the anchor's year; empty when unrepresentable
 * @param yearEnd    encoded key for {@code January 1} of the following year; empty when unrepresentable
 */
public record ScanWindows(
        LocalDate anchor,
        Optional<String> todayStart,
        Optional<String> todayEnd,
        Optional<String> last7Start,
        Optional<String> last30Start,
        Optional<String> yearStart,
        Optional<String> yearEnd) {

    /**
     * Builds the four windows from one database anchor date.
     *
     * <p>Half-open intervals throughout: a window is {@code [start, end)}, so an item created exactly at
     * the start boundary belongs to the window and one at the end boundary does not. The single
     * {@code tomorrow} boundary therefore serves today, the last 7 days and the last 30 days, which is why
     * it is computed once rather than three times.
     *
     * @param anchor the database's current date; never null
     */
    public static ScanWindows anchoredAt(LocalDate anchor) {
        java.util.Objects.requireNonNull(anchor, "anchor");
        return new ScanWindows(
                anchor,
                ItemIdDateKey.encode(anchor),
                ItemIdDateKey.encode(anchor.plusDays(1)),
                ItemIdDateKey.encode(anchor.minusDays(6)),
                ItemIdDateKey.encode(anchor.minusDays(29)),
                ItemIdDateKey.encode(anchor.withDayOfYear(1)),
                ItemIdDateKey.encode(anchor.withDayOfYear(1).plusYears(1)));
    }

    /** True when every window boundary is representable, so all four time metrics can be measured. */
    public boolean fullyRepresentable() {
        return todayStart.isPresent() && todayEnd.isPresent() && last7Start.isPresent()
                && last30Start.isPresent() && yearStart.isPresent() && yearEnd.isPresent();
    }

    /**
     * A fixed, value-free reason naming which boundary could not be encoded, or empty when all four windows
     * are representable.
     *
     * <p>Names the anchor date - which the operator already knows - and never a credential, a URL or a
     * vendor message.
     */
    public Optional<String> unavailableReason() {
        if (fullyRepresentable()) {
            return Optional.empty();
        }
        return Optional.of("the database current date " + anchor
                + " falls outside the documented ItemID date encoding range (2000-2199), so the"
                + " created-today, last-7-day, last-30-day and current-year counts cannot be expressed");
    }

    /**
     * The eight boundary values for one root segment, in the order {@code JdbcDialect.aggregateSql}
     * documents: today lo/hi, 7-day lo/hi, 30-day lo/hi, year lo/hi.
     *
     * @throws IllegalStateException when a boundary is unrepresentable; callers must check
     *         {@link #fullyRepresentable()} first, so this failure is a programming error rather than a
     *         runtime condition
     */
    public java.util.List<Object> parameters() {
        if (!fullyRepresentable()) {
            throw new IllegalStateException("window boundaries are not representable: "
                    + unavailableReason().orElse(""));
        }
        return java.util.List.of(
                todayStart.get(), todayEnd.get(),
                last7Start.get(), todayEnd.get(),
                last30Start.get(), todayEnd.get(),
                yearStart.get(), yearEnd.get());
    }
}
