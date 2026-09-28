package com.mraibo.cminsight.core;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.ConfigException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves the enabled state of every feature module from configuration.
 *
 * <p>Unknown {@code feature.*} keys are reported rather than ignored: a typo that silently switches a
 * module off is exactly the kind of misconfiguration an operator cannot see.
 *
 * <p>One rule is enforced rather than configurable: {@code feature.retention.admin} cannot be enabled
 * in this goal. Retention administration requires mutation, and V1/V2 are read-only.
 */
public final class FeatureRegistry {

    private final Map<String, Boolean> states;
    private final List<String> warnings;

    public FeatureRegistry(AppConfig config, List<FeatureModule> modules) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(modules, "modules");

        Map<String, Boolean> resolved = new LinkedHashMap<>();
        Set<String> knownIds = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();

        for (FeatureModule module : modules) {
            String id = module.id();
            if (id == null || id.isBlank()) {
                throw new ConfigException("Feature module id must not be blank");
            }
            if (!knownIds.add(id)) {
                throw new ConfigException("Duplicate feature module id: " + id);
            }
            boolean enabled = config.getBoolean(configKey(id), module.enabledByDefault());
            if (FeatureIds.RETENTION_ADMIN.equals(id) && enabled) {
                throw new ConfigException("'" + configKey(id) + "' cannot be enabled: retention administration"
                        + " is disabled until a later explicitly approved goal (see ARCHITECTURE.md, SECURITY.md).");
            }
            resolved.put(id, enabled);
        }

        for (String key : new TreeSet<>(config.keys())) {
            if (!key.startsWith(FeatureIds.CONFIG_PREFIX)) {
                continue;
            }
            String id = key.substring(FeatureIds.CONFIG_PREFIX.length());
            if (!knownIds.contains(id)) {
                notes.add("Unknown feature key '" + key + "' is ignored. Known modules: "
                        + String.join(", ", knownIds));
            }
        }

        this.states = Collections.unmodifiableMap(resolved);
        this.warnings = List.copyOf(notes);
    }

    /** A registry with the built-in CM Insight module set. */
    public static FeatureRegistry defaults(AppConfig config) {
        return new FeatureRegistry(config, defaultModules());
    }

    /** The built-in module set and its default enabled state. */
    public static List<FeatureModule> defaultModules() {
        List<FeatureModule> modules = new ArrayList<>(9);
        modules.add(simple(FeatureIds.DASHBOARD, true));
        modules.add(simple(FeatureIds.ITEMTYPES, true));
        modules.add(simple(FeatureIds.STATISTICS, true));
        modules.add(simple(FeatureIds.RETENTION_VIEWER, true));
        modules.add(simple(FeatureIds.HISTORY, true));
        modules.add(simple(FeatureIds.REPORTS, true));
        modules.add(simple(FeatureIds.SYSTEM_DIAGNOSTICS, true));
        modules.add(simple(FeatureIds.ITEM_LOOKUP, false));
        modules.add(simple(FeatureIds.RETENTION_ADMIN, false));
        return List.copyOf(modules);
    }

    public static String configKey(String id) {
        return FeatureIds.CONFIG_PREFIX + id;
    }

    private static FeatureModule simple(String id, boolean enabledByDefault) {
        return new FeatureModule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean enabledByDefault() {
                return enabledByDefault;
            }

            @Override
            public String toString() {
                return "FeatureModule[" + id + ", default=" + enabledByDefault + "]";
            }
        };
    }

    /** Enabled state per module, in declaration order. */
    public Map<String, Boolean> states() {
        return states;
    }

    /** @throws ConfigException when the id is not a known module */
    public boolean isEnabled(String id) {
        Boolean value = states.get(id);
        if (value == null) {
            throw new ConfigException("Unknown feature module '" + id + "'");
        }
        return value;
    }

    public List<String> enabledIds() {
        List<String> enabled = new ArrayList<>();
        states.forEach((id, on) -> {
            if (Boolean.TRUE.equals(on)) {
                enabled.add(id);
            }
        });
        return List.copyOf(enabled);
    }

    /** Non-fatal configuration observations, safe to print. */
    public List<String> warnings() {
        return warnings;
    }
}
