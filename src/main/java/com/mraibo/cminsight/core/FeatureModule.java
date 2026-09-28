package com.mraibo.cminsight.core;

import com.mraibo.cminsight.config.AppConfig;

public interface FeatureModule {
    String id();
    boolean enabled(AppConfig config);
}
