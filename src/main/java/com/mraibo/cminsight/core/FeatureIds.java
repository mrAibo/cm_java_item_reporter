package com.mraibo.cminsight.core;

/**
 * Canonical feature module identifiers.
 *
 * <p>Each id maps to the configuration key {@code feature.<id>}, for example
 * {@code feature.retention.viewer=true}.
 */
public final class FeatureIds {

    /** Configuration key prefix for every feature switch. */
    public static final String CONFIG_PREFIX = "feature.";

    public static final String DASHBOARD = "dashboard";
    public static final String ITEMTYPES = "itemtypes";
    public static final String STATISTICS = "statistics";
    public static final String RETENTION_VIEWER = "retention.viewer";
    public static final String HISTORY = "history";
    public static final String REPORTS = "reports";
    public static final String SYSTEM_DIAGNOSTICS = "system.diagnostics";
    /** Reserved: ItemID/PID lookup is a later phase and stays off. */
    public static final String ITEM_LOOKUP = "item.lookup";
    /** Reserved: retention administration stays disabled until a later approved goal. */
    public static final String RETENTION_ADMIN = "retention.admin";

    private FeatureIds() {
    }
}
