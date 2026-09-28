package com.mraibo.cminsight.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Configurable ItemType classification rules.
 *
 * <p>Rules are declared as {@code classification.<name>.label} plus {@code classification.<name>.regex}.
 * They live either in the main configuration or, when {@code classifications.file} is set, in a
 * dedicated file whose inline keys still win.
 *
 * <p>A rule matches when its regex matches the <em>entire</em> ItemType name
 * ({@link java.util.regex.Matcher#matches()}), so a rule that should match anywhere must use
 * {@code .*pattern.*}. Full-name matching keeps rule evaluation predictable.
 *
 * <p>Rules are applied in alphabetical order of their name, and the reserved {@code default} rule is
 * always evaluated last as the fallback label.
 */
public final class ClassificationRules {

    /** Prefix of every classification key. */
    public static final String CONFIG_PREFIX = "classification.";
    /** Reserved rule name that supplies the fallback label. */
    public static final String DEFAULT_KEY = "default";
    /** Label used when nothing at all is configured. */
    public static final String FALLBACK_LABEL = "Unclassified";

    private final List<Rule> rules;
    private final String defaultLabel;
    private final List<String> diagnostics;

    private record Rule(String label, Pattern pattern) {
    }

    private ClassificationRules(List<Rule> rules, String defaultLabel, List<String> diagnostics) {
        this.rules = List.copyOf(rules);
        this.defaultLabel = defaultLabel;
        this.diagnostics = List.copyOf(diagnostics);
    }

    /** Loads rules from the optional classifications file, overlaid by inline configuration keys. */
    public static ClassificationRules load(AppConfig config) {
        Objects.requireNonNull(config, "config");
        List<String> diagnostics = new ArrayList<>();
        Properties merged = new Properties();
        String origin = "inline configuration";

        Path file = config.find("classifications.file").map(Path::of).orElse(null);
        if (file != null) {
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    merged.load(in);
                } catch (IOException e) {
                    throw new ConfigException("Could not read classifications file " + file
                            + ": " + e.getMessage(), e);
                }
                origin = file.toString();
            } else {
                diagnostics.add("Classification rules file " + file
                        + " was not found; only inline configuration is used.");
            }
        }

        for (String key : config.keys()) {
            if (key.startsWith(CONFIG_PREFIX)) {
                merged.setProperty(key, config.get(key, ""));
            }
        }

        return parse(merged, origin, diagnostics);
    }

    /** Builds rules directly from properties. Mainly used by tests. */
    public static ClassificationRules fromProperties(Properties properties, String origin) {
        Objects.requireNonNull(properties, "properties");
        return parse(properties, origin == null ? "properties" : origin, new ArrayList<>());
    }

    private static ClassificationRules parse(Properties properties, String origin, List<String> diagnostics) {
        TreeMap<String, String> labels = new TreeMap<>();
        TreeMap<String, String> regexes = new TreeMap<>();

        for (String rawKey : properties.stringPropertyNames()) {
            String key = rawKey.trim();
            if (!key.startsWith(CONFIG_PREFIX)) {
                continue;
            }
            String rest = key.substring(CONFIG_PREFIX.length());
            int dot = rest.lastIndexOf('.');
            if (dot <= 0 || dot == rest.length() - 1) {
                diagnostics.add("Ignoring classification key '" + key
                        + "': expected classification.<name>.label or classification.<name>.regex");
                continue;
            }
            String name = rest.substring(0, dot);
            String field = rest.substring(dot + 1).toLowerCase(Locale.ROOT);
            String value = properties.getProperty(rawKey);
            value = value == null ? "" : value.trim();
            switch (field) {
                case "label" -> labels.put(name, value);
                case "regex" -> regexes.put(name, value);
                default -> diagnostics.add("Ignoring classification key '" + key
                        + "': unknown field '" + field + "'");
            }
        }

        List<Rule> rules = new ArrayList<>();
        String defaultLabel = null;

        Set<String> names = new TreeSet<>(labels.keySet());
        names.addAll(regexes.keySet());

        for (String name : names) {
            String label = labels.get(name);
            String regex = regexes.get(name);

            if (DEFAULT_KEY.equals(name)) {
                if (label != null && !label.isBlank()) {
                    defaultLabel = label;
                }
                continue;
            }
            if (label == null || label.isBlank()) {
                diagnostics.add("Ignoring classification rule '" + name + "': no label configured.");
                continue;
            }
            if (regex == null || regex.isBlank()) {
                diagnostics.add("Ignoring classification rule '" + name + "': no regex configured.");
                continue;
            }
            try {
                rules.add(new Rule(label, Pattern.compile(regex)));
            } catch (PatternSyntaxException e) {
                throw new ConfigException("Classification rule '" + name + "' has an invalid regex: "
                        + e.getDescription() + " (from " + origin + ")");
            }
        }

        if (rules.isEmpty() && defaultLabel == null) {
            diagnostics.add("No classification rules are configured; every ItemType is classified as '"
                    + FALLBACK_LABEL + "'.");
        }

        return new ClassificationRules(rules, defaultLabel, diagnostics);
    }

    /** Classifies an ItemType name; never returns {@code null}. */
    public String classify(String itemTypeName) {
        if (itemTypeName != null && !itemTypeName.isBlank()) {
            for (Rule rule : rules) {
                if (rule.pattern().matcher(itemTypeName).matches()) {
                    return rule.label();
                }
            }
        }
        return fallbackLabel();
    }

    /** The label used when no rule matches. */
    public String fallbackLabel() {
        return defaultLabel == null || defaultLabel.isBlank() ? FALLBACK_LABEL : defaultLabel;
    }

    /** Distinct labels in evaluation order, with the fallback last. */
    public List<String> labels() {
        List<String> result = new ArrayList<>();
        for (Rule rule : rules) {
            if (!result.contains(rule.label())) {
                result.add(rule.label());
            }
        }
        if (!result.contains(fallbackLabel())) {
            result.add(fallbackLabel());
        }
        return List.copyOf(result);
    }

    public int ruleCount() {
        return rules.size();
    }

    /** Non-fatal observations from rule parsing, safe to print. */
    public List<String> diagnostics() {
        return diagnostics;
    }

    @Override
    public String toString() {
        return "ClassificationRules[rules=" + rules.size() + ", fallback=" + fallbackLabel() + "]";
    }
}
