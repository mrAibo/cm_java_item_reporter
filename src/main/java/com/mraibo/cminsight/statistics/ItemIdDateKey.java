package com.mraibo.cminsight.statistics;

import java.time.LocalDate;

/**
 * Encodes a calendar date into the six-character date key IBM embeds in a Content Manager
 * {@code ItemID}, and into the form used to compare against it in SQL.
 *
 * <h2>The documented encoding</h2>
 *
 * <p>IBM documents {@code ICMSTItems.ItemID} as a 26-character value whose bits carry a timestamp, and
 * documents these positions:
 *
 * <ul>
 *   <li>position 9: {@code A} when the first two year digits are {@code 20}, {@code B} when they are
 *       {@code 21};</li>
 *   <li>positions 10-11: the last two digits of the year;</li>
 *   <li>position 12: the month as {@code A}..{@code L} for {@code 01}..{@code 12};</li>
 *   <li>positions 13-14: the day of the month.</li>
 * </ul>
 *
 * <p>So {@code SUBSTR(ItemID, 9, 6)} is a fixed-width six-character key. Because every position is either a
 * fixed-width digit or an ordered letter, the key sorts chronologically as plain text, which is why a
 * window test is a relational comparison on the encoded string rather than a decode inside SQL.
 *
 * <p>Evidence: {@code https://www.ibm.com/docs/en/content-manager/8.7.0?topic=tables-icmstitems-items}
 *
 * <h2>Only the documented range is supported</h2>
 *
 * <p>IBM documents century {@code A} = 20xx and {@code B} = 21xx, which covers 2000-2199 and nothing else.
 * There is no documented encoding for 1999 or 2200, so this class refuses to invent one: {@link #encode}
 * answers {@link java.util.Optional#empty()} outside that range, and the caller must then report the
 * affected time metrics as {@code UNAVAILABLE} rather than guess a century. The total logical-item count
 * stays available in that case, because it does not depend on the date key at all.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p>It does not decode a date key back into a date, and it does not parse the hour, minute, second or
 * sequence bits. Neither is needed to count items in a calendar window, and an unused decoder would be a
 * second, untested statement of the same encoding that could drift from this one.
 */
public final class ItemIdDateKey {

    /** The number of characters this encoding produces: {@code SUBSTR(ItemID, 9, 6)}. */
    public static final int LENGTH = 6;

    /** The 1-based position in {@code ItemID} where the encoded date begins. */
    public static final int ITEM_ID_OFFSET = 9;

    private static final int FIRST_CENTURY_YEAR = 2000;
    private static final int LAST_CENTURY_YEAR = 2199;

    private ItemIdDateKey() {
    }

    /**
     * Encodes {@code date} as the six-character key, or returns empty when the date falls outside the
     * documented 2000-2199 range.
     *
     * <p>An empty result is a deliberate, meaningful answer rather than a failure: it is how the caller
     * learns that a window boundary cannot be represented, and therefore that the time metrics depending on
     * it must be {@code UNAVAILABLE}.
     */
    public static java.util.Optional<String> encode(LocalDate date) {
        if (date == null) {
            return java.util.Optional.empty();
        }
        int year = date.getYear();
        if (year < FIRST_CENTURY_YEAR || year > LAST_CENTURY_YEAR) {
            return java.util.Optional.empty();
        }
        char century = year < 2100 ? 'A' : 'B';
        String twoDigitYear = String.format("%02d", year % 100);
        char month = (char) ('A' + date.getMonthValue() - 1);
        String day = String.format("%02d", date.getDayOfMonth());
        return java.util.Optional.of(new StringBuilder(LENGTH)
                .append(century)
                .append(twoDigitYear)
                .append(month)
                .append(day)
                .toString());
    }

    /**
     * True when the encoding covers this date, i.e. when the item's creation date can be expressed in the
     * same representation the database comparison uses.
     */
    public static boolean representable(LocalDate date) {
        return encode(date).isPresent();
    }
}
