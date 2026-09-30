package com.mraibo.cminsight.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * The Java implementation of the two Goal 02 source guards, shared by {@link IbmSourceReadOnlyGuardTest}
 * and {@link CoreIbmIsolationTest}.
 *
 * <h2>Why this exists twice</h2>
 *
 * <p>{@code tests/shell/ibm_guard.sh} is the committed shell guard, and it is the one {@code build.sh} runs,
 * so it is the authority. This class is the SAME rules expressed where the test suite can assert them -
 * including the negative cases (a planted violation must be refused) that make the guard's silence
 * meaningful. It is deliberately not a wrapper around the shell script: a test that shells out to the guard
 * cannot prove what happens when a directory goes missing, and that is one of the two failure modes that
 * matters most, because a guard that scans nothing reports success.
 *
 * <h2>The pattern table is read from the shell guard</h2>
 *
 * <p>The forbidden call patterns and the isolation allow-list are parsed out of
 * {@code tests/shell/ibm_guard.sh} at runtime rather than copied here. One table, one place to change it: a
 * pattern added to the shell guard is enforced by this suite immediately, and a pattern deleted there makes
 * {@link #forbiddenCallPatterns()} fail loudly rather than silently shrinking the Java guard.
 *
 * <h2>No relative paths</h2>
 *
 * <p>The tree is located from the compiled {@code Assert} class's own code source, not from
 * {@code user.dir}. {@code build.sh} happens to run from the repository root, but a suite that only works
 * from one working directory is a suite that silently scans the wrong tree when somebody runs it another way -
 * and scanning the wrong tree reports success.
 */
final class SourceGuard {

    /** The shell guard whose tables these assertions use. */
    private static final String GUARD_SCRIPT = "tests/shell/ibm_guard.sh";

    /**
     * The analytics read-only shell guard, whose rules {@link AnalyticsSourceReadOnlyGuardTest} mirrors.
     *
     * <p>Two guards, one rule set: the shell guard is what {@code build.sh} runs, and the Java twin is where
     * the same rules - and the mutation controls that make their silence meaningful - are asserted. Keeping
     * the paths here, beside the IBM guard's, is what makes the agreement itself assertable.
     */
    static final String ANALYTICS_GUARD_SCRIPT = "tests/shell/analytics_guard.sh";

    /**
     * The ONE file exempt from the analytics guard's STATEMENT-LITERAL rules, mirroring
     * {@code EXEMPT_RELS} in {@code tests/shell/analytics_guard.sh} exactly: same one path, asserted in both
     * directions by {@link AnalyticsSourceReadOnlyGuardTest}.
     *
     * <h2>Why an exemption is required at all</h2>
     *
     * <p>Rule 3 of that guard refuses a string literal that carries a write or control SQL keyword as a whole
     * token. {@code SqlAdmission} is the layer that REFUSES that vocabulary, and a refusal layer has to be
     * able to NAME what it refuses - so without an exemption the rule forbids its own enforcement mechanism
     * and cannot pass. A guard that cannot pass gets deleted by whoever hits it first, which is the worse
     * outcome by far.
     *
     * <h2>Why it is a PATH and only a sub-rule</h2>
     *
     * <p>A path, never a pattern: an allow-listed pattern is how an exception silently widens. And the
     * exemption covers ONLY the keyword-literal rule for that file - never the JDBC call rules. A file that
     * combines the exempt vocabulary with a real offending construct is therefore still refused, which
     * {@link AnalyticsSourceReadOnlyGuardTest#aPlantCombiningTheExemptVocabularyWithARealWriteIsStillRefused}
     * proves by planting exactly that.
     */
    static final String ANALYTICS_LITERAL_EXEMPT_RELATIVE =
            "src/main/java/com/mraibo/cminsight/db/SqlAdmission.java";

    /** The basename form of the exemption, which is what a scan compares against. */
    static final String ANALYTICS_LITERAL_EXEMPT_FILENAME = "SqlAdmission.java";

    /** The one file allowed to mention a {@code com.ibm} type under {@code src/main/java}. */
    static final String ISOLATION_ALLOWED_RELATIVE =
            "src/main/java/com/mraibo/cminsight/connection/CmSession.java";

    /**
     * The one {@code com.ibm} reference allowed anywhere under {@code src/main/java}: the DB2 universal
     * JDBC driver's class name, which Goal 03 needs for local offline driver discovery.
     *
     * <p>This is not the IBM CM SDK. The core compiles against no DB2 jar, the name is only ever a String
     * handed to {@code Class.forName}, and the driver ships separately in {@code lib/db2} or is absent
     * entirely - so it is not a dependency on an IBM type, which is what the isolation rule exists to
     * prevent. The exemption is scoped to this REFERENCE, never to a file: an allow-listed file would let a
     * later edit hide a real SDK reference. It must match {@code ISOLATION_ALLOWED_REFERENCE} in
     * {@code tests/shell/ibm_guard.sh}, and that agreement is asserted by the isolation test.
     */
    static final String ISOLATION_ALLOWED_REFERENCE = "com.ibm.db2.jcc.DB2Driver";

    /** A package reference that would make the optional adapter mandatory for the core. */
    static final String ADAPTER_PACKAGE_REFERENCE = "com.mraibo.cminsight.ibm.internal";

    private SourceGuard() {
    }

    /** The repository root, located from this class's own compiled location. */
    static Path repositoryRoot() {
        try {
            java.security.CodeSource codeSource = SourceGuard.class.getProtectionDomain().getCodeSource();
            Assert.assertNotNull(codeSource,
                    "the test classes must have a code source; without one the source tree cannot be located,"
                            + " and a guard that cannot locate its subject would have to guess");
            Path classes = Paths.get(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
            Path current = classes;
            while (current != null) {
                if (Files.isDirectory(current.resolve("src")) && Files.isRegularFile(current.resolve(GUARD_SCRIPT))) {
                    return current;
                }
                current = current.getParent();
            }
            throw new AssertionError("could not locate the repository root above " + classes
                    + ": expected an ancestor holding src/ and " + GUARD_SCRIPT);
        } catch (java.net.URISyntaxException failure) {
            throw new AssertionError("the code source location is not a usable path: " + failure, failure);
        }
    }

    /** The {@code tests/shell/ibm_guard.sh} file, whose tables the Java guard mirrors. */
    static Path shellGuard() {
        return repositoryRoot().resolve(GUARD_SCRIPT);
    }

    /**
     * The forbidden mutating-call patterns, parsed from the shell guard's own table.
     *
     * <p>Fails loudly when the table cannot be read: an empty or unparsable table would make every assertion
     * below pass while enforcing nothing.
     */
    static List<String> forbiddenCallPatterns() {
        Path script = shellGuard();
        Assert.assertTrue(Files.isRegularFile(script),
                "the committed shell guard " + GUARD_SCRIPT + " is the authority for the forbidden call table,"
                        + " and it is missing at " + script);
        List<String> lines;
        try {
            lines = Files.readAllLines(script, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("cannot read " + GUARD_SCRIPT + ": " + failure, failure);
        }

        List<String> patterns = new ArrayList<>();
        boolean inTable = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("FORBIDDEN_CALL_PATTERNS=(")) {
                inTable = true;
                continue;
            }
            if (inTable && trimmed.startsWith(")")) {
                inTable = false;
                continue;
            }
            if (!inTable || trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            Matcher entry = Pattern.compile("^'(.+)'$").matcher(trimmed);
            if (entry.matches()) {
                patterns.add(entry.group(1));
            }
        }
        Assert.assertFalse(patterns.isEmpty(),
                "the forbidden call table in " + GUARD_SCRIPT + " parsed to zero patterns; the Java guard would"
                        + " then enforce nothing while reporting success");
        return patterns;
    }

    // ------------------------------------------------------------------ the analytics read-only guard

    /** The {@code tests/shell/analytics_guard.sh} file, whose rules this suite mirrors. */
    static Path analyticsShellGuard() {
        return repositoryRoot().resolve(ANALYTICS_GUARD_SCRIPT);
    }

    /**
     * The statement-literal patterns of the analytics guard, as Java regexes equivalent to the shell guard's
     * own rules.
     *
     * <p>They are named here rather than copied from the shell guard's text because the shell rules are ERE
     * written against whole lines and to be greppable, while a Java twin has to scan a whole file at once (a
     * Java text block puts a statement's keyword on a line of its own, which a line-oriented scan cannot see).
     * That is a deliberate, documented difference in MECHANISM, not in rule: each pattern below is the same
     * anchored rule - a statement verb after a literal boundary, at the start of the literal, inside
     * parentheses, after a separator, or inside a text block - with the same token-aware character class on
     * the right of the keyword. The agreement in the direction that matters is asserted from the shell
     * guard's own vocabulary by {@link AnalyticsSourceReadOnlyGuardTest}, so a keyword that vanished from one
     * side is caught rather than tolerated.
     *
     * <p>Every keyword these patterns refuse is upper-case and every keyword the runtime rule refuses appears
     * in them: the shell guard's {@code SQL_VERBS} and {@code SqlAdmission.forbiddenKeywords()} name the same
     * list, and that is asserted rather than assumed.
     */
    static List<String> forbiddenLiteralPatterns() {
        return List.of(
                // A literal whose whole content is a bare write/control keyword: "DELETE", "TRUNCATE".
                "[\"'][ \t]*(" + ANALYTICS_VERBS + ")[ \t]*[\"']",
                // A write/control keyword immediately after a literal boundary. This is the SELECT-wrapped
                // case: "SELECT * FROM FINAL TABLE (DELETE FROM X)", "WITH D AS (DELETE FROM X) SELECT ...",
                // and the concatenated form "SELECT " + "DELETE FROM X".
                "[\"'][ \t]*(" + ANALYTICS_VERBS + ")[^A-Za-z0-9_]",
                // The same, immediately after an opening parenthesis: "(DELETE FROM X) SELECT ...".
                "[(][ \t]*(" + ANALYTICS_VERBS + ")[^A-Za-z0-9_]",
                // A second statement after a real separator inside the literal: "SELECT 1; DELETE FROM X".
                "[;][ \t]*(" + ANALYTICS_VERBS + ")[^A-Za-z0-9_]",
                // A statement start inside a text block, which the line-oriented shell guard cannot see.
                "\"\"\"[ \\t\\r\\n]*(" + ANALYTICS_VERBS + ")");
    }

    /**
     * The write/control vocabulary every analytics literal rule is built from.
     *
     * <p>This is the same set {@code SqlAdmission.forbiddenKeywords()} returns, including the
     * data-change-table vocabulary {@code FINAL}/{@code OLD}/{@code NEW}, which is how a SELECT-wrapped
     * mutation is spelled on DB2. The agreement between this list, {@code SqlAdmission.forbiddenKeywords()}
     * and the shell guard's declared vocabulary is asserted by the analytics guard test.
     */
    static final String ANALYTICS_VERBS =
            "INSERT|UPDATE|DELETE|MERGE|TRUNCATE|CREATE|ALTER|DROP|GRANT|REVOKE|CALL|BEGIN|COMMIT|ROLLBACK"
                    + "|EXEC|EXECUTE|SAVEPOINT|FINAL|OLD|NEW";

    /**
     * True when this file is the one file exempt from the analytics STATEMENT-LITERAL rules, by basename so
     * a scan of a copied tree sees the same answer.
     *
     * <p>The exemption covers the literal rules and NOTHING ELSE: the JDBC call rules still apply to this
     * file, which is what stops the exemption from being a hole. The control
     * {@link AnalyticsSourceReadOnlyGuardTest#aPlantCombiningTheExemptVocabularyWithARealWriteIsStillRefused}
     * plants a call into a copy of the exempt file and requires the scanner to report it.
     */
    static boolean isAnalyticsLiteralExempt(Path file) {
        return file != null && ANALYTICS_LITERAL_EXEMPT_FILENAME.equals(file.getFileName().toString());
    }

    /**
     * Files under {@code relativeDirectory} that contain any forbidden mutating call.
     *
     * @param root              the tree to scan, which may be a temporary copy
     * @param relativeDirectory source directory to scan, for example {@code src/ibm/java}
     * @return one entry per offending line, {@code path:line: text}
     */
    static List<String> findMutatingCalls(Path root, String relativeDirectory) {
        Path directory = root.resolve(relativeDirectory);
        Assert.assertTrue(Files.isDirectory(directory),
                "the read-only guard must scan " + directory + ", which does not exist. A missing source"
                        + " directory is a LAYOUT failure, not a clean result: a guard that scans nothing"
                        + " reports success forever");

        List<Pattern> patterns = new ArrayList<>();
        for (String raw : forbiddenCallPatterns()) {
            try {
                patterns.add(Pattern.compile("(" + raw + ")"));
            } catch (PatternSyntaxException failure) {
                throw new AssertionError("the shell guard pattern '" + raw + "' is not usable as a Java"
                        + " regex, so this test cannot enforce it: " + failure.getMessage(), failure);
            }
        }

        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(directory)) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new AssertionError("cannot read " + file + ": " + failure, failure);
            }
            for (int index = 0; index < lines.size(); index++) {
                String text = lines.get(index);
                for (Pattern pattern : patterns) {
                    Matcher matcher = pattern.matcher(text);
                    if (matcher.find()) {
                        violations.add(root.relativize(file).toString().replace('\\', '/')
                                + ":" + (index + 1) + ": " + matcher.group());
                        break;
                    }
                }
            }
        }
        return violations;
    }

    /**
     * Lines under {@code src/main/java} that reference {@code com.ibm.} outside the single allowed file.
     *
     * @return one entry per offending line, {@code relativePath:line: text}
     */
    static List<String> findIbmReferencesInCore(Path root) {
        Path core = root.resolve("src/main/java");
        Assert.assertTrue(Files.isDirectory(core),
                "the isolation guard must scan " + core + ", which does not exist. Without it there is nothing"
                        + " to inspect, and reporting success for an unscanned tree is the one outcome this"
                        + " guard must never produce");

        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(core)) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (ISOLATION_ALLOWED_RELATIVE.equals(relative)) {
                // The single documented exception. Asserted here rather than assumed, so a second allowance
                // cannot appear without this line being edited too.
                continue;
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new AssertionError("cannot read " + file + ": " + failure, failure);
            }
            for (int index = 0; index < lines.size(); index++) {
                String text = lines.get(index);
                if (!text.contains("com.ibm.")) {
                    continue;
                }
                // The DB2 driver class name is exempt, but only where it IS that name. Removing it and
                // re-testing means a line carrying both the allowed name and a real SDK reference is still a
                // violation, which is what stops the exemption widening into a hole.
                String remainder = text.replace(ISOLATION_ALLOWED_REFERENCE, "");
                if (!remainder.contains("com.ibm.")) {
                    continue;
                }
                violations.add(relative + ":" + (index + 1) + ": " + text.trim());
            }
        }
        return violations;
    }

    /** True when the one allowed file still exists, so the allow-list cannot silently become unrestricted. */
    static boolean isolationAllowanceExists(Path root) {
        return Files.isRegularFile(root.resolve(ISOLATION_ALLOWED_RELATIVE));
    }

    /** Every {@code .java} file under a directory, in stable order. */
    static List<Path> javaFiles(Path directory) {
        try (Stream<Path> stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException failure) {
            throw new AssertionError("cannot walk " + directory + ": " + failure, failure);
        }
    }

    /**
     * The forbidden call patterns, as a set, with their shell character classes translated for a description.
     *
     * <p>Only used to report what the guard covers, so an operator reading a failure knows which table was
     * applied.
     */
    static String describePatterns() {
        Set<String> names = new LinkedHashSet<>();
        for (String pattern : forbiddenCallPatterns()) {
            names.add(pattern.replaceAll("\\[\\[:space:\\]\\]", " ").replace("\\(", "(").trim());
        }
        return String.join(", ", names);
    }

    /** Locale-independent lower-casing, matching the shell guard's comparison style. */
    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
