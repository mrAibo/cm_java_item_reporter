package com.mraibo.cminsight.test;

import com.mraibo.cminsight.statistics.ItemTypeStatistics;
import com.mraibo.cminsight.statistics.MetricValue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Regression: Versions and Parts stay unavailable until the documented research gate.
 *
 * <p>That rule is a project non-negotiable, so it is pinned structurally rather than by a golden
 * number: a metric can only carry a number through an explicit {@code MetricValue.available(n)}, the
 * availability vocabulary has no {@code ESTIMATED}/{@code GUESSED} state, and
 * {@code ItemTypeStatistics} stores versions and parts as a {@link MetricValue} instead of a bare
 * number. A producer that started guessing would therefore have to change this contract first.
 */
public class StatisticsContractTest {

    public void unavailableMetricsCarryNoValue() {
        MetricValue unavailable = MetricValue.unavailable();
        Assert.assertNull(unavailable.value(), "an unavailable metric carries no value at all");
        Assert.assertEquals(MetricValue.Availability.UNAVAILABLE, unavailable.availability(),
                "unavailable() reports UNAVAILABLE");
        Assert.assertFalse(unavailable.value() instanceof Long, "there is no number to mistake for data");
    }

    public void availableMetricsCarryExactlyTheGivenNumber() {
        MetricValue available = MetricValue.available(7L);
        Assert.assertNotNull(available.value(), "an available metric carries a value");
        Assert.assertEquals(7L, available.value().longValue(), "the given number is preserved");
        Assert.assertEquals(MetricValue.Availability.AVAILABLE, available.availability(),
                "available(n) reports AVAILABLE, independently of the number");
        Assert.assertEquals(0L, MetricValue.available(0L).value().longValue(),
                "a legitimate zero is representable and still distinguishable from 'unavailable'");
        Assert.assertEquals(MetricValue.Availability.ERROR, MetricValue.Availability.valueOf("ERROR"),
                "a failed measurement has its own state instead of a number");
        Assert.assertEquals(3, MetricValue.Availability.values().length, "the state set is closed");
    }

    public void thereIsNoGuessedStateAndNoBareNumberForVersionsOrParts() {
        List<String> states = new ArrayList<>();
        for (MetricValue.Availability availability : MetricValue.Availability.values()) {
            states.add(availability.name());
        }
        Assert.assertEquals(List.of("AVAILABLE", "UNAVAILABLE", "ERROR"), states,
                "the availability vocabulary has no ESTIMATED or GUESSED state: " + states);

        List<String> factories = new ArrayList<>();
        for (Method method : MetricValue.class.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && Modifier.isPublic(method.getModifiers())) {
                factories.add(method.getName());
            }
        }
        factories.sort(String::compareTo);
        Assert.assertEquals(List.of("available", "unavailable"), factories,
                "the only ways to build a metric are available(n) and unavailable(): " + factories);

        Assert.assertEquals(MetricValue.class, componentType("versions"),
                "versions stays a MetricValue, so a bare guessed number cannot silently replace it");
        Assert.assertEquals(MetricValue.class, componentType("parts"),
                "parts stays a MetricValue, so a bare guessed number cannot silently replace it");
        Assert.assertNotNull(componentType("logicalItems"),
                "the measured counters keep the same shape as versions and parts");
    }

    /** The declared type of one {@link ItemTypeStatistics} record component. */
    private static Class<?> componentType(String name) {
        for (RecordComponent component : ItemTypeStatistics.class.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component.getType();
            }
        }
        throw new AssertionError("ItemTypeStatistics has no component named " + name);
    }
}
