package com.mraibo.cminsight.report;

/**
 * Where an immutable report model came from.
 *
 * <h2>Two sources, one model</h2>
 *
 * <p>Goal 04 section 6 allows a report to be built from either the current completed full snapshot or one
 * stored history snapshot. Both are mapped into the same immutable model, and this enum is the only place
 * the difference is allowed to matter: it decides the label the artifact states about itself and nothing
 * else. A renderer must never branch on it to fetch something, because there is nothing to fetch - the
 * model carries every value either source produced.
 *
 * <p>The distinction is still worth stating in the artifact. A reader holding a printed file needs to know
 * whether it describes the live repository as of its last completed scan or a snapshot that was recorded
 * and is no longer being updated, and a report that cannot say which of the two it is has no provenance at
 * all.
 */
public enum ReportSource {

    /** Built from the current completed full scan snapshot, mapped into the frozen captured shape. */
    LIVE_SNAPSHOT("current full snapshot"),

    /** Built from one stored persistent history snapshot; renderable with no live read of any kind. */
    HISTORY("stored history snapshot");

    private final String label;

    ReportSource(String label) {
        this.label = label;
    }

    /** A fixed, value-free description for a report heading. */
    public String label() {
        return label;
    }

    /** True when this report describes a stored snapshot rather than the current one. */
    public boolean historical() {
        return this == HISTORY;
    }
}
