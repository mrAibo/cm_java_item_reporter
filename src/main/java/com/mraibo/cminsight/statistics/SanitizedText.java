package com.mraibo.cminsight.statistics;

/**
 * One-line sanitizer for operator-facing free text in the statistics package.
 *
 * <p>Every string that reaches a snapshot, a progress report or an HTTP response passes through here. The
 * rules are deliberately absolute and cheap: control characters (including CR/LF, which could forge a log
 * line or a JSONL record) are replaced by a space, runs of whitespace collapse, the text is trimmed and
 * truncated to a fixed bound.
 *
 * <p>This is NOT a redactor: it cannot tell a SQLState from a password. A producer is therefore still
 * required to hand in structurally safe text - a fixed operation label plus a SQLState or vendor code - and
 * this class is the belt to that braces, not a replacement for it. See
 * {@link StatisticsQueryException#sanitizedReason()} for the one shape the JDBC layer produces.
 */
final class SanitizedText {

    private SanitizedText() {
    }

    /** Never {@code null}: a missing reason is the empty string. */
    static String clean(String text, int maxLength) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(Math.min(text.length(), maxLength));
        boolean lastWasSpace = true;
        for (int i = 0; i < text.length() && cleaned.length() < maxLength; i++) {
            char c = text.charAt(i);
            // A control character becomes a SPACE rather than being deleted: deleting a newline would glue
            // the words on either side of it together ("user\nname" becoming "username"), which both reads
            // wrong and could join two harmless fragments into something that looks like a value.
            if (c < 0x20 || c == 0x7f || Character.isWhitespace(c)) {
                if (lastWasSpace) {
                    continue;
                }
                lastWasSpace = true;
                cleaned.append(' ');
                continue;
            }
            lastWasSpace = false;
            cleaned.append(c);
        }
        return cleaned.toString().trim();
    }
}
