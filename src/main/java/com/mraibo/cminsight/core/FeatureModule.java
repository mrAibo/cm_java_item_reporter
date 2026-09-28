package com.mraibo.cminsight.core;

/**
 * A switchable feature area.
 *
 * <p>Modularity is package and service based; these modules are feature boundaries, not separate
 * deployables and not dynamically loaded plugins.
 */
public interface FeatureModule {

    /** Stable identifier, also the suffix of the {@code feature.<id>} configuration key. */
    String id();

    /** Value used when no {@code feature.<id>} key is configured. */
    boolean enabledByDefault();
}
