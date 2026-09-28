package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.ClassificationRules;
import com.mraibo.cminsight.config.ConfigException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** ItemType classification rules: matching semantics, ordering, diagnostics and invalid regexes. */
public class ClassificationRulesTest {

    private static ClassificationRules rules(String... keyValuePairs) {
        Properties properties = new Properties();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            properties.setProperty(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return ClassificationRules.fromProperties(properties, "test-properties");
    }

    public void rulesMatchTheWholeItemTypeName() {
        ClassificationRules rules = rules(
                "classification.alpha.label", "Alpha",
                "classification.alpha.regex", "A.*",
                "classification.default.label", "Other");

        Assert.assertEquals(1, rules.ruleCount(), "only real rules are counted, the default is not one");
        Assert.assertEquals("Alpha", rules.classify("Apple"), "a matching rule supplies its label");
        Assert.assertEquals("Other", rules.classify("Mango"), "an unmatched name uses the default label");
        Assert.assertEquals("Other", rules.classify(""), "a blank name uses the default label");
        Assert.assertEquals("Other", rules.classify(null), "a null name uses the default label");

        ClassificationRules partial = rules(
                "classification.only.label", "Solo",
                "classification.only.regex", "A");
        Assert.assertEquals(ClassificationRules.FALLBACK_LABEL, partial.classify("Apple"),
                "matching is on the whole name, so 'A' does not match 'Apple'");
        Assert.assertEquals("Solo", partial.classify("A"), "an exact whole-name match succeeds");
    }

    public void rulesAreEvaluatedInAlphabeticalOrderWithTheDefaultLast() {
        ClassificationRules rules = rules(
                "classification.zeta.label", "Zeta",
                "classification.zeta.regex", "Z.*",
                "classification.alpha.label", "Alpha",
                "classification.alpha.regex", "A.*",
                "classification.beta.label", "Beta",
                "classification.beta.regex", "B.*",
                "classification.default.label", "Other");
        Assert.assertEquals(List.of("Alpha", "Beta", "Zeta", "Other"), rules.labels(),
                "labels come out in evaluation order, with the fallback last");
        Assert.assertEquals("Other", rules.fallbackLabel(), "the default rule supplies the fallback");

        ClassificationRules overlapping = rules(
                "classification.zzz.label", "Last",
                "classification.zzz.regex", ".*",
                "classification.aaa.label", "First",
                "classification.aaa.regex", ".*");
        Assert.assertEquals("First", overlapping.classify("Anything"),
                "the alphabetically first rule wins when several match");
        Assert.assertEquals(List.of("First", "Last", ClassificationRules.FALLBACK_LABEL), overlapping.labels(),
                "the fallback label is appended when no default rule exists");
    }

    public void fallbackLabelIsConfigurableAndDefaultsSafely() {
        Assert.assertEquals(ClassificationRules.FALLBACK_LABEL,
                rules().fallbackLabel(), "without configuration the built-in label is used");
        Assert.assertEquals(List.of(ClassificationRules.FALLBACK_LABEL), rules().labels(),
                "the fallback is the only label of an empty rule set");
        Assert.assertEquals(ClassificationRules.FALLBACK_LABEL,
                rules("classification.default.label", "   ").fallbackLabel(),
                "a blank default label falls back to the built-in label");
        Assert.assertEquals(ClassificationRules.FALLBACK_LABEL,
                rules("classification.default.regex", ".*").fallbackLabel(),
                "a default regex without a label supplies no label");
        Assert.assertEquals("Custom", rules("classification.default.label", "Custom").fallbackLabel(),
                "a configured default label is used");
    }

    public void invalidRegexIsRejectedWithTheRuleName() {
        ConfigException failure = Assert.assertThrows(ConfigException.class,
                () -> rules("classification.broken.label", "Broken",
                        "classification.broken.regex", "[unclosed"),
                "an invalid regex must fail the rule set");
        Assert.assertTrue(failure.getMessage().contains("invalid regex"),
                "the message explains the rule: " + failure.getMessage());
        Assert.assertTrue(failure.getMessage().contains("broken"), "the rule name is reported: "
                + failure.getMessage());
    }

    public void missingLabelOrRegexIsReportedAndTheRuleIsSkipped() {
        ClassificationRules rules = rules(
                "classification.nolabel.regex", ".*",
                "classification.noregex.label", "NoRegex",
                "classification.bogus", "1",
                "classification.shape.bogus", "1",
                "web.port", "9090");

        Assert.assertEquals(0, rules.ruleCount(), "incomplete rules are skipped");
        Assert.assertEquals(ClassificationRules.FALLBACK_LABEL, rules.classify("Anything"),
                "a skipped rule never classifies anything");
        List<String> diagnostics = rules.diagnostics();
        Assert.assertTrue(diagnostics.stream().anyMatch(d -> d.contains("'nolabel': no label configured.")),
                "the missing label is reported: " + diagnostics);
        Assert.assertTrue(diagnostics.stream().anyMatch(d -> d.contains("'noregex': no regex configured.")),
                "the missing regex is reported: " + diagnostics);
        Assert.assertTrue(diagnostics.stream().anyMatch(d -> d.contains("'classification.bogus'")),
                "a malformed classification key is reported: " + diagnostics);
        Assert.assertTrue(diagnostics.stream().anyMatch(d -> d.contains("unknown field 'bogus'")),
                "an unknown field is reported: " + diagnostics);
        Assert.assertTrue(diagnostics.stream().anyMatch(d -> d.contains("No classification rules are configured")),
                "an empty rule set is reported: " + diagnostics);
        Assert.assertTrue(diagnostics.stream().noneMatch(d -> d.contains("web.port")),
                "unrelated keys are ignored entirely: " + diagnostics);
    }

    public void loadReadsInlineKeysAndTheOptionalFile() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-classification-");
        try {
            Path file = TestSupport.writeFile(dir.resolve("classifications.properties"),
                    "classification.fromfile.label=FileLabel\nclassification.fromfile.regex=F.*\n");
            Properties properties = new Properties();
            properties.setProperty("classifications.file", file.toString());
            properties.setProperty("classification.inline.label", "InlineLabel");
            properties.setProperty("classification.inline.regex", "I.*");
            properties.setProperty("web.port", "9090");

            ClassificationRules rules = ClassificationRules.load(AppConfig.fromProperties(properties));
            Assert.assertEquals(2, rules.ruleCount(), "file and inline rules are both loaded");
            Assert.assertEquals("FileLabel", rules.classify("Fox"), "a rule from the file matches");
            Assert.assertEquals("InlineLabel", rules.classify("Ice"), "an inline rule matches");
            Assert.assertEquals(ClassificationRules.FALLBACK_LABEL, rules.classify("Mango"),
                    "a name matching nothing uses the fallback");
            Assert.assertTrue(rules.labels().contains("FileLabel") && rules.labels().contains("InlineLabel"),
                    "both labels are reported: " + rules.labels());
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    public void missingClassificationsFileIsReportedButInlineRulesStillWork() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-classification-missing-");
        try {
            Properties properties = new Properties();
            properties.setProperty("classifications.file", dir.resolve("absent.properties").toString());
            properties.setProperty("classification.inline.label", "InlineLabel");
            properties.setProperty("classification.inline.regex", "I.*");

            ClassificationRules rules = ClassificationRules.load(AppConfig.fromProperties(properties));
            Assert.assertEquals(1, rules.ruleCount(), "inline rules survive a missing file");
            Assert.assertEquals("InlineLabel", rules.classify("Ice"), "the inline rule still matches");
            Assert.assertTrue(rules.diagnostics().stream().anyMatch(d -> d.contains("was not found")),
                    "the missing file is reported: " + rules.diagnostics());
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    public void inlineKeysOverrideTheClassificationsFile() throws IOException {
        Path dir = TestSupport.newTempDir("cminsight-classification-override-");
        try {
            Path file = TestSupport.writeFile(dir.resolve("classifications.properties"),
                    "classification.shared.label=FromFile\nclassification.shared.regex=FROMFILE\n");
            Properties properties = new Properties();
            properties.setProperty("classifications.file", file.toString());
            properties.setProperty("classification.shared.label", "FromConfig");
            properties.setProperty("classification.shared.regex", "FROMCONFIG");

            ClassificationRules rules = ClassificationRules.load(AppConfig.fromProperties(properties));
            Assert.assertEquals("FromConfig", rules.classify("FROMCONFIG"), "inline keys win over the file");
            Assert.assertEquals(ClassificationRules.FALLBACK_LABEL, rules.classify("FROMFILE"),
                    "the overridden file rule no longer matches");
        } finally {
            TestSupport.deleteRecursively(dir);
        }
    }

    public void constantsMatchTheDocumentedContract() {
        Assert.assertEquals("classification.", ClassificationRules.CONFIG_PREFIX, "the documented prefix");
        Assert.assertEquals("default", ClassificationRules.DEFAULT_KEY, "the reserved rule name");
        Assert.assertEquals("Unclassified", ClassificationRules.FALLBACK_LABEL, "the built-in label");
    }
}
