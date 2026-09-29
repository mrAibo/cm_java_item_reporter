package com.mraibo.cminsight.ibm.internal;

import com.mraibo.cminsight.config.ClassificationRules;
import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CmSession;
import com.mraibo.cminsight.metadata.ItemTypeInfo;

import java.util.Optional;
import java.util.Properties;

import static com.mraibo.cminsight.ibm.internal.Assert.assertEquals;
import static com.mraibo.cminsight.ibm.internal.Assert.assertFalse;
import static com.mraibo.cminsight.ibm.internal.Assert.assertTrue;

/**
 * Goal 02A section D: the classification rules the adapter uses are the ones the CORE loaded from the
 * configuration the operator actually selected.
 *
 * <h2>The defect this suite pins</h2>
 *
 * <p>{@code IbmCmAdapterProvider} used to reload the DEFAULT configuration path
 * ({@code AppPaths.resolve().configurationFile(null)}) to obtain its rules, while {@code Main} loaded them
 * from the selected configuration - including a custom {@code --config <path>}. A runtime started with a
 * non-default configuration could therefore print and validate one set of rules and label every ItemType
 * with another, with no error anywhere. Configuration is now parsed once, by the core, and the immutable
 * rule set travels in {@code CmAdapterSettings}.
 *
 * <h2>What is asserted, and why each assertion is separate</h2>
 *
 * <ul>
 *   <li>a distinctive rule reaches {@link ItemTypeInfo#businessClassification()} through the PRODUCTION
 *       mapping code, so the property is asserted on the value an operator would see;</li>
 *   <li>two different rule sets produce different labels for the same ItemType, so a test that passed
 *       because the adapter guessed a constant cannot pass;</li>
 *   <li>the fallback label is the one the SUPPLIED set declares, and while doing so it is asserted not to be
 *       the built-in default - the label must come from the core's rule set, never from the adapter's own
 *       failure to find rules.</li>
 * </ul>
 */
public final class IbmClassificationProvenanceTest {

    /** The distinctive label only a custom configuration can produce. */
    private static final String CUSTOM_LABEL = "CustomBiz";

    /** The fallback the custom configuration declares; deliberately not the built-in default. */
    private static final String CUSTOM_FALLBACK = "AnythingElse";

    /**
     * A rule from the configuration the core selected reaches
     * {@link ItemTypeInfo#businessClassification()}.
     */
    public void aRuleFromTheSuppliedConfigurationReachesBusinessClassification() throws Exception {
        ClassificationRules rules = customRules();
        IbmFakes.FakeReadDatastore datastore = readDatastoreWith(
                new IbmFakes.FakeItemTypeDef("SAP_MATERIAL").classification((short) 0),
                new IbmFakes.FakeItemTypeDef("PlainType").classification((short) 0));
        BoundedPool<CmSession> pool = poolOver(datastore, rules);
        try {
            CmMetadataService service = new CmMetadataService(pool, rules);

            Optional<ItemTypeInfo> matching = service.itemType("SAP_MATERIAL");
            assertTrue(matching.isPresent(), "D: the ItemType is readable through the real mapping path");
            assertEquals(CUSTOM_LABEL, matching.get().businessClassification(),
                    "D: the distinctive rule from the configuration the core selected must reach the DTO; a"
                            + " label from any other source here is the divergence section D removes");

            Optional<ItemTypeInfo> unmatched = service.itemType("PlainType");
            assertTrue(unmatched.isPresent(), "D: the second ItemType is readable too");
            assertEquals(CUSTOM_FALLBACK, unmatched.get().businessClassification(),
                    "D: an ItemType no rule matches gets the fallback the SUPPLIED rule set declares");
            assertFalse(ClassificationRules.FALLBACK_LABEL.equals(unmatched.get().businessClassification()),
                    "D: and NOT the built-in fallback, which is what an adapter that failed to find rules - or"
                            + " that read a different configuration - would produce. Was: "
                            + unmatched.get().businessClassification());
        } finally {
            pool.close();
        }
    }

    /**
     * Two different rule sets cannot silently produce the same answer, which is the "cannot diverge"
     * property stated as a measurement.
     *
     * <p>The same vendor data is mapped twice, once with the custom set and once with an empty one, and the
     * two labels are asserted to differ. A settings object that ignored its rule set - the shape of the
     * original defect - would make them equal and fail here.
     */
    public void twoDifferentRuleSetsProduceDifferentLabelsForTheSameItemType() throws Exception {
        ClassificationRules custom = customRules();
        ClassificationRules empty = ClassificationRules.fromProperties(new Properties(), "no rules");
        IbmFakes.FakeItemTypeDef[] itemTypes = {
                new IbmFakes.FakeItemTypeDef("SAP_MATERIAL"),
                new IbmFakes.FakeItemTypeDef("PlainType")};

        String customLabel = labelOf(itemTypes, custom);
        String emptyLabel = labelOf(itemTypes, empty);

        assertEquals(CUSTOM_LABEL, customLabel, "D: the custom set labels the matching ItemType");
        assertEquals(ClassificationRules.FALLBACK_LABEL, emptyLabel,
                "D: the empty set has no rule for it and no declared fallback, so the documented default"
                        + " applies");
        assertFalse(customLabel.equals(emptyLabel),
                "D: the label MUST follow the supplied rule set - if both produced the same answer, a runtime"
                        + " using the wrong configuration could not be detected at all");
        assertTrue(custom.fallbackLabel().equals(CUSTOM_FALLBACK),
                "D: and the custom set really does declare a distinctive fallback, so the assertion above is"
                        + " about provenance and not about an accidental match");
    }

    /** A rule set whose fallback is declared by the core, with a rule that matches exactly one name. */
    private static ClassificationRules customRules() {
        Properties properties = new Properties();
        properties.setProperty("classification.custom.label", CUSTOM_LABEL);
        properties.setProperty("classification.custom.regex", "SAP_.*");
        properties.setProperty("classification.default.label", CUSTOM_FALLBACK);
        return ClassificationRules.fromProperties(properties, "custom configuration of the test");
    }

    /** Maps the given ItemTypes through the production service and returns the first one's label. */
    private static String labelOf(IbmFakes.FakeItemTypeDef[] itemTypes, ClassificationRules rules)
            throws Exception {
        IbmFakes.FakeReadDatastore datastore = readDatastoreWith(itemTypes);
        BoundedPool<CmSession> pool = poolOver(datastore, rules);
        try {
            CmMetadataService service = new CmMetadataService(pool, rules);
            Optional<ItemTypeInfo> info = service.itemType(itemTypes[0].getName());
            assertTrue(info.isPresent(), "D: the ItemType must be readable for the comparison to mean"
                    + " anything");
            return info.get().businessClassification();
        } finally {
            pool.close();
        }
    }

    private static IbmFakes.FakeReadDatastore readDatastoreWith(IbmFakes.FakeItemTypeDef... itemTypes) {
        IbmFakes.FakeDatastoreDef definition = new IbmFakes.FakeDatastoreDef();
        for (IbmFakes.FakeItemTypeDef itemType : itemTypes) {
            definition.add(itemType);
        }
        IbmFakes.FakeReadDatastore datastore = new IbmFakes.FakeReadDatastore();
        datastore.setDefinition(definition);
        return datastore;
    }

    private static BoundedPool<CmSession> poolOver(IbmFakes.FakeReadDatastore datastore,
                                                   ClassificationRules rules) {
        IbmFakes.FakeConnections connections = new IbmFakes.FakeConnections();
        connections.failedDatastore = datastore;
        return IbmFakes.pool("provenance", IbmFakes.factory("provenance", connections, rules));
    }
}
