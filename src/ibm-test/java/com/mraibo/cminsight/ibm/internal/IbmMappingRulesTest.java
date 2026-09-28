package com.mraibo.cminsight.ibm.internal;

import com.mraibo.cminsight.metadata.ItemTypeInfo;
import com.mraibo.cminsight.metadata.ItemTypeSummary;
import com.mraibo.cminsight.retention.RetentionPolicyInfo;

import java.util.ArrayList;
import java.util.List;

import static com.mraibo.cminsight.ibm.internal.Assert.assertEquals;
import static com.mraibo.cminsight.ibm.internal.Assert.assertFalse;
import static com.mraibo.cminsight.ibm.internal.Assert.assertTrue;

/**
 * Goal 02 sections J/G: the adapter's value mapping rules, where a guessed value is worse than an unknown
 * one.
 *
 * <h2>Why these rules are worth pinning</h2>
 *
 * <p>{@link IbmEnumNames} is the single place where a numeric CM code becomes a readable label. Two properties
 * there are correctness requirements rather than presentation choices:
 *
 * <ul>
 *   <li><strong>An unknown code renders as {@code UNKNOWN(<value>)}.</strong> A plausible-looking neighbour, a
 *       blank or a silently defaulted label would be BELIEVED, and the viewer's whole purpose is to tell an
 *       operator what the server actually said. IBM adds codes between releases; a build that guesses
 *       mislabels data it does not understand.</li>
 *   <li><strong>A value the class files cannot map stays unmapped.</strong> The retention enums' numeric
 *       codes are not recoverable from the SDK class files, so those fields must say so rather than invent a
 *       mapping - the constant's own name is carried separately, which is the honest half.</li>
 * </ul>
 *
 * <p>The third rule is about sorting: the ItemType list is ordered case-insensitively by name, because an
 * operator looking for "itemTypeA" must not find it in a different place from "ItemTypeA". The numeric id
 * rule is asserted here too, for the reason given in
 * {@link #theItemTypeIdIsTheWideValueAndIsNotTruncatedThroughTheShortAccessor()}.
 */
public final class IbmMappingRulesTest {

    /**
     * Out-of-range codes must render as {@code UNKNOWN(<value>)} with the exact numeric value in the text.
     *
     * <p>The tempting alternative - returning the nearest known label, or an empty string - is the failure
     * this asserts against: a wrong label is believed, whereas an obviously unknown one is investigated.
     */
    public void anUnknownCodeRendersAsUnknownWithItsValueAndIsNeverGuessed() {
        // A value far outside the documented sets, and one just past the last known code.
        int[] unknownCodes = {999, -7, 4_000_000};

        for (int code : unknownCodes) {
            assertEquals("UNKNOWN(" + code + ")", IbmEnumNames.classificationName(code),
                    "classification code " + code + " is not one IBM documents, so it must be reported as"
                            + " UNKNOWN(<value>) rather than guessed");
            assertEquals("UNKNOWN(" + code + ")", IbmEnumNames.versionControlName(code),
                    "version-control code " + code + " must be reported as UNKNOWN(<value>)");
            assertEquals("UNKNOWN(" + code + ")", IbmEnumNames.versioningTypeName(code),
                    "versioning-type code " + code + " must be reported as UNKNOWN(<value>)");
        }

        // The known codes still map, so UNKNOWN is not simply the only answer this build can give.
        assertEquals("Item", IbmEnumNames.classificationName(0),
                "the documented classification codes must still map; if this fails, UNKNOWN above proves"
                        + " nothing about unknown values");
    }

    /** No unmapped numeric code is ever rendered as a plausible label. */
    public void theUnmappableRetentionEnumsAreReportedAsUnmapped() {
        assertEquals(IbmEnumNames.UNMAPPED, IbmEnumNames.retentionTypeName(null),
                "the retention-type numeric mapping is not recoverable from the SDK, so it must be reported"
                        + " as unmapped rather than invented");
        assertEquals(IbmEnumNames.UNMAPPED, IbmEnumNames.policyTimeUnitName(null),
                "the policy time unit is unmapped for the same reason");
        assertEquals(IbmEnumNames.UNMAPPED, IbmEnumNames.expirationActionName(null),
                "and so is the expiration action");
        assertEquals(-1, IbmEnumNames.UNMAPPED_CODE,
                "the DTO's numeric field must carry a documented sentinel for 'unmapped', not a value that"
                        + " could be mistaken for a real code");
        assertFalse("UNKNOWN".equals(IbmEnumNames.retentionTypeConstantName(null)),
                "the constant's own name is carried separately and must not be collapsed into the sentinel");
    }

    /**
     * The documented ordering is case-insensitive by name, with the name as the tie-breaker.
     *
     * <p>Asserted through the comparator the service actually sorts with, so a change to the sort key is
     * caught here rather than by an operator noticing that the list looks wrong.
     */
    public void theItemTypeOrderIsCaseInsensitiveByName() {
        List<String> names = new ArrayList<>(List.of("zeta", "Alpha", "alpha", "Beta", "beta", "gamma"));
        names.sort(IbmEnumNames::byName);

        List<String> expected = new ArrayList<>(List.of("Alpha", "alpha", "Beta", "beta", "gamma", "zeta"));
        assertTrue(names.indexOf("Alpha") < names.indexOf("Beta"),
                "case must not decide the order: 'Alpha' sorts before 'Beta', got " + names);
        assertTrue(names.indexOf("alpha") < names.indexOf("Beta"),
                "a lower-cased name must not be pushed after an upper-cased later one, got " + names);
        assertEquals(expected.size(), names.size(), "no name is lost by sorting");
        for (int i = 0; i < expected.size(); i++) {
            assertTrue(names.get(i).equalsIgnoreCase(expected.get(i)),
                    "index " + i + " must hold " + expected.get(i) + " (case-insensitively), got " + names);
        }
        int caseOnly = IbmEnumNames.byName("Same", "same");
        assertFalse(caseOnly == 0,
                "the documented tie-breaker is the name itself, so two names that differ only by case are"
                        + " ordered deterministically rather than reported as equal; a comparator that"
                        + " returns 0 for distinct names can leave a list in a different order per run");
        assertEquals(-IbmEnumNames.byName("same", "Same"), caseOnly,
                "and the tie-breaker is antisymmetric, as a comparator must be");
        assertEquals(0, IbmEnumNames.byName(null, ""),
                "a null and a blank name are the same name for ordering purposes, so the sort cannot throw"
                        + " on either");
        assertTrue(IbmEnumNames.byName("Same", "same") == IbmEnumNames.byName("Same", "same"),
                "and the ordering is stable for one pair");
        assertTrue(IbmEnumNames.byName(" a", "b") < 0,
                "the comparison trims first, so leading whitespace does not reorder the list");
    }

    /**
     * The item-type id is the WIDE value: it must not be produced by the inherited 16-bit accessor.
     *
     * <p>The SDK declares both {@code getId()} (a {@code short}) and {@code getIntId()} (an {@code int}) on
     * the ItemType hierarchy, and the adapter maps the wide one. A value above {@link Short#MAX_VALUE} is the
     * observable difference: read through the narrow accessor it would come back negative, and an operator
     * would see a real item type identified by a number the server never issued.
     *
     * <p>What this test can and cannot prove, stated rather than implied: the DTO is an {@code int} and the
     * mapping is read from the SDK accessor in {@code CmMetadataService}, which cannot be exercised here
     * without a live SDK object or a fake of the vendor's concrete class. So this asserts the DTO end of the
     * rule - the wide value survives, in the detail, in the list projection and in the retention policy's own
     * id field - and the accessor choice itself is covered by the source guard and by the adapter's read
     * tests.
     */
    public void theItemTypeIdIsTheWideValueAndIsNotTruncatedThroughTheShortAccessor() {
        int wideId = 70_000;
        assertTrue(wideId > Short.MAX_VALUE,
                "the value must be outside the 16-bit range for this test to say anything");

        ItemTypeInfo info = itemType("ItemTypeWide", wideId);
        assertEquals(wideId, info.itemTypeId(),
                "the item-type id is an int and must keep a value above Short.MAX_VALUE; a negative or"
                        + " truncated id here is what the short accessor would produce");
        assertTrue(info.itemTypeId() > 0,
                "a wide id must stay positive rather than wrapping into a negative number");

        ItemTypeSummary summary = info.summary();
        assertEquals(wideId, summary.itemTypeId(),
                "the list projection must carry the same wide id as the detail");

        int policyId = 65_537;
        RetentionPolicyInfo policy = policy(policyId);
        assertEquals(policyId, policy.policyId(),
                "the retention policy id is an int too and must not be truncated");
    }

    /** The DTO renders an unmapped numeric field through the sentinel and never as a guessed label. */
    public void theRetentionDtoCarriesTheSentinelForUnmappedNumbers() {
        RetentionPolicyInfo policy = policy(7);

        assertEquals(IbmEnumNames.UNMAPPED, policy.retentionType(),
                "the readable retention type is unmapped, not a guessed name");
        assertEquals(IbmEnumNames.UNMAPPED_CODE, policy.retentionTypeCode(),
                "and its numeric field carries the sentinel");
    }

    /** The documented retention DTO, with every unmappable numeric field using the sentinel. */
    private static RetentionPolicyInfo policy(int policyId) {
        return new RetentionPolicyInfo(
                "Policy", "description", policyId,
                IbmEnumNames.UNMAPPED, IbmEnumNames.UNMAPPED_CODE,
                true, "period", 30, "DAY",
                true, "expiration", 90, "DAY",
                IbmEnumNames.UNMAPPED, IbmEnumNames.UNMAPPED_CODE,
                "", 0, 0, -1, false, List.of("ItemTypeA"));
    }

    private static ItemTypeInfo itemType(String name, int id) {
        return new ItemTypeInfo(
                name, "description", id,
                IbmEnumNames.classificationName(0), 0, "SAP",
                "", "", "", "",
                IbmEnumNames.versionControlName(0), 0,
                IbmEnumNames.versioningTypeName(0), 0,
                "", "Policy");
    }
}
