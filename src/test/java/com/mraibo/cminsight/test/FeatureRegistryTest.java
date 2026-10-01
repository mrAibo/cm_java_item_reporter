package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.ConfigException;
import com.mraibo.cminsight.core.FeatureIds;
import com.mraibo.cminsight.core.FeatureModule;
import com.mraibo.cminsight.core.FeatureRegistry;

import java.util.List;
import java.util.Properties;

/** Feature switches: defaults, explicit overrides, unknown keys and the non-negotiable refusal. */
public class FeatureRegistryTest {

    private static AppConfig config(String... keyValuePairs) {
        Properties properties = new Properties();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            properties.setProperty(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return AppConfig.fromProperties(properties);
    }

    private static FeatureModule module(String id, boolean enabledByDefault) {
        return new FeatureModule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean enabledByDefault() {
                return enabledByDefault;
            }
        };
    }

    public void defaultsComeFromTheModuleSet() {
        FeatureRegistry registry = FeatureRegistry.defaults(AppConfig.empty());
        Assert.assertEquals(9, registry.states().size(), "the built-in module set has nine modules");
        Assert.assertEquals(9, FeatureRegistry.defaultModules().size(), "defaultModules() reports the same set");

        Assert.assertTrue(registry.isEnabled(FeatureIds.DASHBOARD), "dashboard is on by default");
        Assert.assertTrue(registry.isEnabled(FeatureIds.ITEMTYPES), "itemtypes is on by default");
        Assert.assertTrue(registry.isEnabled(FeatureIds.STATISTICS), "statistics is on by default");
        Assert.assertTrue(registry.isEnabled(FeatureIds.RETENTION_VIEWER), "the retention viewer is on by default");
        Assert.assertTrue(registry.isEnabled(FeatureIds.HISTORY), "history is on by default");
        Assert.assertTrue(registry.isEnabled(FeatureIds.REPORTS), "reports are on by default");
        Assert.assertTrue(registry.isEnabled(FeatureIds.SYSTEM_DIAGNOSTICS), "diagnostics are on by default");
        Assert.assertFalse(registry.isEnabled(FeatureIds.ITEM_LOOKUP), "item lookup stays off by default");
        Assert.assertFalse(registry.isEnabled(FeatureIds.RETENTION_ADMIN), "retention admin stays off by default");

        Assert.assertTrue(registry.enabledIds().contains(FeatureIds.DASHBOARD), "enabledIds lists dashboard");
        Assert.assertFalse(registry.enabledIds().contains(FeatureIds.ITEM_LOOKUP),
                "enabledIds does not list item lookup");
        Assert.assertFalse(registry.enabledIds().contains(FeatureIds.RETENTION_ADMIN),
                "enabledIds never lists retention admin");
        Assert.assertTrue(registry.warnings().isEmpty(), "a clean configuration warns about nothing: "
                + registry.warnings());
        Assert.assertEquals("feature.reports", FeatureRegistry.configKey(FeatureIds.REPORTS),
                "configKey builds the documented key");
    }

    public void explicitOverridesWinInBothDirections() {
        FeatureRegistry registry = FeatureRegistry.defaults(config(
                "feature.dashboard", "false",
                "feature.item.lookup", "true"));

        Assert.assertFalse(registry.isEnabled(FeatureIds.DASHBOARD), "a module can be switched off");
        Assert.assertTrue(registry.isEnabled(FeatureIds.ITEM_LOOKUP), "a reserved module can be switched on");
        Assert.assertTrue(registry.isEnabled(FeatureIds.REPORTS), "unmentioned modules keep their default");
    }

    public void unknownFeatureKeyProducesAWarning() {
        FeatureRegistry registry = FeatureRegistry.defaults(config("feature.reprot", "false"));
        Assert.assertEquals(1, registry.warnings().size(), "exactly one warning: " + registry.warnings());
        String warning = registry.warnings().get(0);
        Assert.assertTrue(warning.contains("feature.reprot"), "the warning names the typo: " + warning);
        Assert.assertTrue(warning.contains("Unknown feature key"), "the warning says what happened: " + warning);
        Assert.assertTrue(warning.contains(FeatureIds.DASHBOARD), "the warning lists the known modules: " + warning);
        Assert.assertEquals(9, registry.states().size(), "the typo does not add a state");
    }

    public void featureRetentionAdminCannotBeEnabled() {
        ConfigException failure = Assert.assertThrows(ConfigException.class,
                () -> FeatureRegistry.defaults(config("feature.retention.admin", "true")),
                "enabling retention administration must be refused");
        Assert.assertTrue(failure.getMessage().contains("feature.retention.admin"),
                "the message names the key: " + failure.getMessage());
        Assert.assertTrue(failure.getMessage().contains("cannot be enabled"),
                "the message is explicit: " + failure.getMessage());

        Assert.assertThrows(ConfigException.class,
                () -> new FeatureRegistry(config("feature.retention.admin", "true"),
                        List.of(module(FeatureIds.RETENTION_ADMIN, false))),
                "the refusal does not depend on the module default");
        Assert.assertFalse(FeatureRegistry.defaults(config("feature.retention.admin", "false"))
                        .isEnabled(FeatureIds.RETENTION_ADMIN),
                "explicitly disabling it is accepted");
    }

    public void duplicateOrBlankModuleIdsAreRefused() {
        ConfigException duplicate = Assert.assertThrows(ConfigException.class,
                () -> new FeatureRegistry(AppConfig.empty(),
                        List.of(module("dup", true), module("dup", false))),
                "two modules with the same id are refused");
        Assert.assertTrue(duplicate.getMessage().contains("Duplicate feature module id: dup"),
                "the message names the duplicate: " + duplicate.getMessage());

        ConfigException blank = Assert.assertThrows(ConfigException.class,
                () -> new FeatureRegistry(AppConfig.empty(), List.of(module("  ", true))),
                "a blank module id is refused");
        Assert.assertTrue(blank.getMessage().contains("must not be blank"),
                "the message explains the rule: " + blank.getMessage());
    }

    public void unknownModuleLookupFails() {
        FeatureRegistry registry = FeatureRegistry.defaults(AppConfig.empty());
        ConfigException failure = Assert.assertThrows(ConfigException.class,
                () -> registry.isEnabled("not.a.module"), "an unknown module lookup fails");
        Assert.assertTrue(failure.getMessage().contains("Unknown feature module 'not.a.module'"),
                "the message names the module: " + failure.getMessage());
    }

    public void invalidBooleanFeatureValueIsRejected() {
        Assert.assertThrows(ConfigException.class,
                () -> FeatureRegistry.defaults(config("feature.dashboard", "yes")),
                "a non-boolean feature value fails closed");
    }
}
