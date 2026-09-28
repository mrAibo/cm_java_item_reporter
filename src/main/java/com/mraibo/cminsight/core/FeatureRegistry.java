package com.mraibo.cminsight.core;

import com.mraibo.cminsight.config.AppConfig;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FeatureRegistry {
    private final AppConfig config;
    private final Map<String, FeatureModule> modules = new LinkedHashMap<>();

    public FeatureRegistry(AppConfig config) {
        this.config = config;
    }

    public void register(FeatureModule module) {
        if (modules.putIfAbsent(module.id(), module) != null) {
            throw new IllegalArgumentException("Duplicate feature module: " + module.id());
        }
    }

    public Map<String, Boolean> states() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        modules.forEach((id, module) -> result.put(id, module.enabled(config)));
        return Map.copyOf(result);
    }
}
