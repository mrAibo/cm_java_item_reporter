package com.mraibo.cminsight.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Goal 03 section 7, hard requirement: the analytics JDBC source is structurally READ-ONLY, and this is
 * where that is asserted - together with section 15's "Guards" list.
 *
 * <h2>Why a source guard rather than a code review</h2>
 *
 * <p>{@code Connection.setReadOnly(true)} is a HINT under the JDBC contract: a driver may ignore it, and a
 * database account with write privileges would happily accept a {@code DELETE}. The only guarantee this
 * project can make is structural - the analytics source cannot express a write - and that is a property of a
 * tree that keeps growing, so it is checked rather than reviewed. The committed shell guard
 * {@code tests/shell/analytics_guard.sh} is the authority (it is the one a build step can run); this class
 * is the same rules expressed where the suite can assert them, including the mutation cases that make the
 * guard's silence on the real tree mean something.
 *
 * <h2>Four failure modes, each asserted rather than assumed</h2>
 *
 * <ul>
 *   <li><strong>A guard that scans nothing.</strong> A missing or empty analytics tree must FAIL, not pass:
 *       a rename that emptied it would otherwise disable the rule silently and forever.</li>
 *   <li><strong>A guard that cannot see a real violation.</strong> Positive cases plant a JDBC write call, a
 *       write-shaped statement literal and a stored-procedure escape into a temporary COPY of the tree - the
 *       real source set is never modified - and require each to be found and named.</li>
 *   <li><strong>A pattern table that quietly shrinks.</strong> The forbidden call table is parsed out of the
 *       committed shell guard, so a pattern deleted there fails {@link #forbiddenCallPatterns()} loudly
 *       instead of silently widening what the analytics layer may do.</li>
 *   <li><strong>The other direction - a rule so broad it cannot pass.</strong> Ordinary Java operations
 *       (list add/remove, an atomic set, a getter called {@code updateCount}) must NOT be refused. A guard
 *       that cannot pass is a guard that gets disabled, and this project has paid for that lesson once.</li>
 * </ul>
 *
 * <h2>The stronger half of the statement-literal rule lives here</h2>
 *
 * <p>The shell guard is line oriented, so a multi-line Java text block whose keyword is on its own line
 * escapes it. This class applies the same rule to the WHOLE file text with a multi-line pattern, and it runs
 * on every build, so the stronger check is the one that always runs.
 *
 * <h2>No relative paths</h2>
 *
 * <p>The tree is located from this class's own compiled location, not from {@code user.dir}: a suite that
 * only works from one working directory is a suite that silently scans the wrong tree when somebody runs it
 * another way - and scanning the wrong tree reports success.
 */
public class AnalyticsSourceReadOnlyGuardTest {

    /** The analytics JDBC source tree: the guard's subject, and required to be non-empty. */
    private static final String ANALYTICS_JDBC_SOURCE = "src/main/java/com/mraibo/cminsight/db";

    /** The scan coordinator's package: scanned as well, because a query is issued from here. */
    private static final String ANALYTICS_STATISTICS_SOURCE = "src/main/java/com/mraibo/cminsight/statistics";

    /** The committed shell guard whose tables and rules this suite mirrors. */
    private static final String SHELL_GUARD = "tests/shell/analytics_guard.sh";

    /** The whole core source tree, used for the "no driver type escapes" rule. */
    private static final String CORE_SOURCE = "src/main/java";

    /** The package that is allowed to hold a {@code java.sql} driver type at all. */
    private static final String DATABASE_PACKAGE = "src/main/java/com/mraibo/cminsight/db";

    /**
     * A statement literal that begins with a write/control keyword.
     *
     * <p>Multi-line and anchored on the opening quote, so it catches a Java text block whose keyword is on
     * the following line - the case a line-oriented scan cannot see - while leaving ordinary English prose
     * and the generated SELECT statements alone.
     */
    private static final Pattern WRITE_STATEMENT_LITERAL = Pattern.compile(
            "\"{1,3}[ \t\r\n]*(INSERT|UPDATE|DELETE|MERGE|TRUNCATE|CREATE|ALTER|DROP|GRANT|REVOKE|CALL)"
                    + "\\b",
            Pattern.MULTILINE);

    /** The JDBC stored-procedure escape, which only ever precedes a CALL. */
    private static final Pattern JDBC_CALL_ESCAPE = Pattern.compile("[{][ \t]*[Cc][Aa][Ll][Ll][^A-Za-z0-9_]");

    /**
     * The {@code java.sql} driver types that must never appear in the analytics layer's published surface
     * outside the database package.
     *
     * <p>Deliberately narrow: {@code SQLException} is not in the list, because a service may legitimately
     * translate one, and this rule is about the types that would hand a caller a live driver handle.
     */
    private static final Pattern DRIVER_HANDLE_TYPE = Pattern.compile(
            "java\\.sql\\.(Connection|Statement|PreparedStatement|CallableStatement|ResultSet)\\b");

    // ------------------------------------------------------------------ acceptance

    /** The committed analytics source holds no forbidden write call, no write literal and no CALL escape. */
    public void theCommittedAnalyticsSourceHoldsNoForbiddenWriteCall() {
        Path root = repositoryRoot();

        List<String> violations = findForbiddenWrites(root);

        Assert.assertTrue(violations.isEmpty(),
                "the analytics source must be structurally read-only, but found " + violations.size()
                        + " forbidden construct(s): " + violations + " (patterns applied: "
                        + describePatterns() + ")");
    }

    /** The guard has a real, non-empty subject, and the table it applies is real. */
    public void theGuardScansARealNonEmptyAnalyticsTree() {
        Path root = repositoryRoot();
        Path jdbc = root.resolve(ANALYTICS_JDBC_SOURCE);

        Assert.assertTrue(Files.isDirectory(jdbc),
                "the analytics JDBC source " + jdbc + " must exist for this guard to mean anything");
        List<Path> jdbcSources = javaFiles(jdbc);
        Assert.assertTrue(jdbcSources.size() >= 8,
                "the guard must see the analytics JDBC sources, but found only " + jdbcSources.size()
                        + " .java file(s) under " + jdbc + "; a guard scanning nothing reports success");
        List<Path> statisticsSources = javaFiles(root.resolve(ANALYTICS_STATISTICS_SOURCE));
        Assert.assertTrue(statisticsSources.size() >= 10,
                "and the scan coordinator's package too, but found only " + statisticsSources.size()
                        + " .java file(s)");

        Assert.assertTrue(forbiddenCallPatterns().size() >= 8,
                "the forbidden call table parsed to only " + forbiddenCallPatterns().size() + " pattern(s):"
                        + " the rules were weakened, or the table moved and this guard no longer reads it");
    }

    /** The committed shell guard still carries the rules, so the build step and this suite cannot drift. */
    public void theCommittedShellGuardStillCarriesTheRules() {
        Path script = repositoryRoot().resolve(SHELL_GUARD);
        Assert.assertTrue(Files.isRegularFile(script), "the committed shell guard " + SHELL_GUARD
                + " is the authority for these rules, and it is missing at " + script);
        String text = readString(script);

        for (String required : List.of("FORBIDDEN_CALL_PATTERNS=(", "executeUpdate", "prepareCall", "commit",
                "rollback", "SQL_KEYWORD_PATTERN", "JDBC_CALL_ESCAPE_PATTERN", ANALYTICS_JDBC_SOURCE)) {
            Assert.assertTrue(text.contains(required),
                    "the committed guard must keep '" + required + "'; removing it would widen what the"
                            + " analytics layer may do while this suite reported success");
        }
        Assert.assertTrue(text.contains("set -euo pipefail"),
                "and it must keep its fail-fast prologue, or a failing grep would not stop it");
    }

    // ------------------------------------------------------------------ mutation cases

    /**
     * A planted write call IS found in a copied tree, so the clean result above means something.
     *
     * <p>Three plants, one per rule family: a JDBC write call, a statement literal that begins with a write
     * keyword, and the stored-procedure escape. Each must be reported and each report must NAME the file, so a
     * failure is actionable.
     */
    public void aPlantedWriteCallIsDetectedInACopiedTree() throws IOException {
        Path copy = copyAnalyticsTree();
        Path planted = copy.resolve(ANALYTICS_JDBC_SOURCE).resolve("PlantedViolation.java");
        Files.writeString(planted, """
                package com.mraibo.cminsight.db;

                /** Planted by AnalyticsSourceReadOnlyGuardTest: a forbidden JDBC write call. */
                final class PlantedViolation {
                    void writeThrough(java.sql.Statement statement) throws Exception {
                        statement.executeUpdate("DELETE FROM ICMUT00001001");
                    }
                }
                """, StandardCharsets.UTF_8);

        List<String> violations = findForbiddenWrites(copy);
        Assert.assertFalse(violations.isEmpty(),
                "the guard found nothing in a tree containing 'statement.executeUpdate(...)': the scanner"
                        + " does not bite, so its silence on the real tree proves nothing");
        Assert.assertTrue(violations.stream().anyMatch(entry -> entry.contains("PlantedViolation.java")),
                "and the violation must name the file that contains it, so a failure is actionable. Found: "
                        + violations);
    }

    /** A planted statement literal is found in a copied tree, including the multi-line text-block shape. */
    public void aPlantedWriteStatementLiteralIsDetectedInACopiedTree() throws IOException {
        Path copy = copyAnalyticsTree();
        Path planted = copy.resolve(ANALYTICS_JDBC_SOURCE).resolve("PlantedLiteral.java");
        Files.writeString(planted, """
                package com.mraibo.cminsight.db;

                /** Planted by AnalyticsSourceReadOnlyGuardTest: a write-shaped statement literal. */
                final class PlantedLiteral {
                    String statement() {
                        return \"\"\"
                                DELETE FROM ICMUT00001001
                                \"\"\";
                    }
                }
                """, StandardCharsets.UTF_8);

        List<String> violations = findForbiddenWrites(copy);
        Assert.assertTrue(violations.stream().anyMatch(entry -> entry.contains("PlantedLiteral.java")),
                "a write-shaped literal - here in a text block whose keyword is on the NEXT line, which a"
                        + " line-oriented scan cannot see - must be refused and named. Found: " + violations);
    }

    /** The JDBC CALL escape is found in a copied tree. */
    public void aPlantedStoredProcedureCallIsDetectedInACopiedTree() throws IOException {
        Path copy = copyAnalyticsTree();
        Path planted = copy.resolve(ANALYTICS_JDBC_SOURCE).resolve("PlantedCall.java");
        Files.writeString(planted, """
                package com.mraibo.cminsight.db;

                /** Planted by AnalyticsSourceReadOnlyGuardTest: a stored-procedure escape. */
                final class PlantedCall {
                    String statement() {
                        return "{call DO_SOMETHING(1)}";
                    }
                }
                """, StandardCharsets.UTF_8);

        List<String> violations = findForbiddenWrites(copy);
        Assert.assertTrue(violations.stream().anyMatch(entry -> entry.contains("PlantedCall.java")),
                "the JDBC CALL escape must be refused and named. Found: " + violations);
    }

    /**
     * The pre-plant control: a copy of the UNMODIFIED tree passes.
     *
     * <p>Without it, a scanner that refuses everything would satisfy every mutation case above while making
     * the acceptance case meaningless.
     */
    public void aCopyOfTheUnmodifiedTreePassesSoThePlantsAreTheCause() throws IOException {
        Path copy = copyAnalyticsTree();
        List<String> violations = findForbiddenWrites(copy);
        Assert.assertTrue(violations.isEmpty(),
                "an unmodified copy of the analytics tree must pass, otherwise the planted cases above prove"
                        + " only that the scanner refuses everything: " + violations);
    }

    /**
     * Ordinary Java operations are NOT refused.
     *
     * <p>The assertion that stops the next person from "fixing" a false positive by deleting the guard - and
     * the assertion that keeps the SQL-keyword rule anchored on the opening quote, so English prose and a
     * getter called {@code updateCount()} stay legal.
     */
    public void ordinaryJavaOperationsAreNotRefused() throws IOException {
        Path copy = copyAnalyticsTree();
        Files.writeString(copy.resolve(ANALYTICS_JDBC_SOURCE).resolve("LocalOnly.java"), """
                package com.mraibo.cminsight.db;

                import java.util.ArrayList;
                import java.util.HashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.atomic.AtomicBoolean;

                /**
                 * Scratch fixture: a commit is never issued here, and a rollback is not either - the words
                 * appear in prose on purpose, so the guard must be anchored on a call parenthesis.
                 */
                final class LocalOnly {
                    private final List<String> names = new ArrayList<>();
                    private final Map<String, Long> counters = new HashMap<>();
                    private final AtomicBoolean closed = new AtomicBoolean(false);

                    int resembling() {
                        return names.size() + counters.size() + (closed.get() ? 1 : 0);
                    }

                    String selectOnly() {
                        return "SELECT COUNT(DISTINCT ITEMID) FROM ICMADMIN.ICMUT00001001";
                    }

                    void bookkeeping() {
                        names.add("itemType");
                        names.remove(0);
                        names.clear();
                        counters.put("reads", 1L);
                        counters.remove("reads");
                        closed.set(true);
                    }
                }
                """, StandardCharsets.UTF_8);

        List<String> violations = findForbiddenWrites(copy);
        Assert.assertTrue(violations.isEmpty(),
                "ordinary java.util and wrapper operations, a SELECT-only literal and English prose must not"
                        + " be refused; a guard that cannot pass gets disabled. Found: " + violations);
    }

    /**
     * A tree with no analytics source FAILS the guard instead of passing.
     *
     * <p>The failure mode that would disable the rule without anybody noticing: a rename, a moved source set,
     * or a build that only compiles the core. Reporting success for an unscanned tree is the one outcome this
     * guard must never produce.
     */
    public void aMissingAnalyticsTreeFailsInsteadOfPassing() throws IOException {
        Path copy = copyAnalyticsTree();
        deleteRecursively(copy.resolve(ANALYTICS_JDBC_SOURCE));

        AssertionError failure = Assert.assertThrows(AssertionError.class,
                () -> findForbiddenWrites(copy),
                "scanning a tree with no " + ANALYTICS_JDBC_SOURCE + " must fail loudly, not report a clean"
                        + " result");
        Assert.assertTrue(failure.getMessage().replace('\\', '/').contains(ANALYTICS_JDBC_SOURCE),
                "and the failure must name the directory it could not scan: " + failure.getMessage());
    }

    // ------------------------------------------------------------------ the API boundary

    /**
     * Section 7: no public/service/web method exposes {@code Connection}, {@code Statement} or
     * {@code ResultSet}.
     *
     * <p>Asserted as a property of the tree: outside the database package, no CODE line may name one of those
     * types. A javadoc {@code {@link}} is not a published type - it hands nobody a handle - so purely
     * documentary mentions are excluded, and the rule stays narrow enough to be satisfiable.
     */
    public void noJdbcDriverHandleTypeEscapesTheDatabasePackage() {
        Path root = repositoryRoot();
        Path core = root.resolve(CORE_SOURCE);
        Assert.assertTrue(Files.isDirectory(core),
                "the boundary guard must scan " + core + ", which does not exist; an unscanned tree is a"
                        + " layout failure, not a clean result");

        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(core)) {
            String relative = relative(root, file);
            if (relative.startsWith(DATABASE_PACKAGE)) {
                continue;
            }
            List<String> lines = readLines(file);
            for (int index = 0; index < lines.size(); index++) {
                if (isCommentLine(lines.get(index))) {
                    continue;
                }
                if (DRIVER_HANDLE_TYPE.matcher(lines.get(index)).find()) {
                    violations.add(relative + ":" + (index + 1) + ": " + lines.get(index).trim());
                }
            }
        }
        Assert.assertTrue(violations.isEmpty(),
                "no code outside " + DATABASE_PACKAGE + " may name a JDBC driver handle type, or the web layer"
                        + " could receive one: " + violations);
    }

    /** True for a line that is entirely a comment or javadoc continuation, which publishes no type. */
    private static boolean isCommentLine(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*");
    }

    // ------------------------------------------------------------------ tracked artefacts

    /**
     * Zero proprietary DB driver JARs are tracked, and zero exist in the working tree.
     *
     * <p>Goal 03 section 5: vendor JARs are an OPTIONAL runtime input and are never downloaded or committed.
     * Both halves are checked, because they fail for different reasons: a tracked jar is a repository defect,
     * while a jar present but untracked is a local artefact that `.gitignore` must already cover.
     */
    public void noProprietaryDatabaseDriverJarIsTrackedOrPresent() throws Exception {
        Path root = repositoryRoot();
        List<String> tracked = gitLines(root, "ls-files");
        Assert.assertFalse(tracked.isEmpty(),
                "the tracked-file list came back empty, so this check cannot distinguish 'nothing tracked'"
                        + " from 'git did not answer'");

        List<String> trackedJars = new ArrayList<>();
        for (String entry : tracked) {
            if (entry.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                trackedJars.add(entry);
            }
        }
        Assert.assertTrue(trackedJars.isEmpty(),
                "no JAR may be committed, least of all a proprietary database driver: " + trackedJars);

        // Scoped to lib/ on purpose: an unscoped `--ignored` walks the whole working tree, including the
        // build output, and that took over 25 seconds here - long enough to risk the suite's per-test
        // timeout and turn a green guard into a red build for the wrong reason.
        List<String> ignored = gitLines(root, "status", "--porcelain", "--ignored", "--", "lib");
        for (String entry : ignored) {
            String path = entry.length() > 3 ? entry.substring(3).trim() : entry.trim();
            String lower = path.toLowerCase(Locale.ROOT);
            Assert.assertFalse(lower.endsWith(".jar") && (lower.contains("db2") || lower.contains("oracle")
                            || lower.contains("jdbc") || lower.contains("lib/")),
                    "a vendor driver JAR must not even exist in the working tree as an ignored artefact;"
                            + " proprietary drivers are placed by the operator at runtime: " + path);
        }

        for (String vendorDirectory : List.of("lib/db2", "lib/oracle")) {
            Path directory = root.resolve(vendorDirectory);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(directory)) {
                List<String> jars = entries.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .sorted()
                        .toList();
                Assert.assertTrue(jars.isEmpty(),
                        vendorDirectory + " must hold no driver JAR in this checkout: " + jars);
            }
        }
    }

    /** The output lines of a git command run in the repository root. */
    private static List<String> gitLines(Path root, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        Assert.assertEquals(0, exit, "git " + String.join(" ", arguments) + " failed, so the tracked-artefact"
                + " check has no subject; output: " + output.replace('\n', ' ').trim());
        List<String> lines = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------ the scanner

    /**
     * Every forbidden construct under the analytics trees, as {@code path:line: text} entries.
     *
     * @param root the tree to scan, which may be a temporary copy
     */
    private static List<String> findForbiddenWrites(Path root) {
        Path jdbc = root.resolve(ANALYTICS_JDBC_SOURCE);
        Assert.assertTrue(Files.isDirectory(jdbc),
                "the analytics read-only guard must scan " + jdbc + ", which does not exist. A missing source"
                        + " directory is a LAYOUT failure, not a clean result: a guard that scans nothing"
                        + " reports success forever");
        List<Path> directories = new ArrayList<>();
        directories.add(jdbc);
        Path statistics = root.resolve(ANALYTICS_STATISTICS_SOURCE);
        if (Files.isDirectory(statistics)) {
            directories.add(statistics);
        }

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
        for (Path directory : directories) {
            for (Path file : javaFiles(directory)) {
                String relative = relative(root, file);
                List<String> lines = readLines(file);
                String text = String.join("\n", lines);

                for (int index = 0; index < lines.size(); index++) {
                    for (Pattern pattern : patterns) {
                        Matcher matcher = pattern.matcher(lines.get(index));
                        if (matcher.find()) {
                            violations.add(relative + ":" + (index + 1) + ": " + lines.get(index).trim());
                            break;
                        }
                    }
                }
                violations.addAll(matchWholeFile(relative, text, WRITE_STATEMENT_LITERAL,
                        "a statement literal beginning with a write/control SQL keyword"));
                violations.addAll(matchWholeFile(relative, text, JDBC_CALL_ESCAPE,
                        "the JDBC stored-procedure CALL escape"));
            }
        }
        return violations;
    }

    /**
     * Whole-file matches, reported with their line number.
     *
     * <p>Deliberately not line-based: a Java text block puts the keyword on a line of its own, and a
     * line-oriented scan cannot see it. This is the stronger half of the rule.
     */
    private static List<String> matchWholeFile(String relative, String text, Pattern pattern, String what) {
        List<String> violations = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int line = 1;
            for (int index = 0; index < matcher.start() && index < text.length(); index++) {
                if (text.charAt(index) == '\n') {
                    line++;
                }
            }
            violations.add(relative + ":" + line + ": " + what);
        }
        return violations;
    }

    /**
     * The forbidden call patterns, parsed from the committed shell guard's own table.
     *
     * <p>One table, one place to change it: a pattern added to the shell guard is enforced here immediately,
     * and a pattern deleted there makes this method fail loudly rather than silently shrinking the rule.
     */
    static List<String> forbiddenCallPatterns() {
        Path script = repositoryRoot().resolve(SHELL_GUARD);
        Assert.assertTrue(Files.isRegularFile(script),
                "the committed shell guard " + SHELL_GUARD + " is the authority for the forbidden call table,"
                        + " and it is missing at " + script);
        List<String> patterns = new ArrayList<>();
        boolean inTable = false;
        for (String line : readLines(script)) {
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
                "the forbidden call table in " + SHELL_GUARD + " parsed to zero patterns; this guard would"
                        + " then enforce nothing while reporting success");
        return patterns;
    }

    /** What the guard covers, for a failure message: an operator reading it must know which table applied. */
    private static String describePatterns() {
        Set<String> names = new TreeSet<>();
        for (String pattern : forbiddenCallPatterns()) {
            names.add(pattern.replace("[[:space:]]*\\(", "()").replace("\\(", "()"));
        }
        Set<String> described = new TreeSet<>(names);
        described.add("statement literals: INSERT/UPDATE/DELETE/MERGE/TRUNCATE/CREATE/ALTER/DROP/GRANT/REVOKE"
                + "/CALL");
        described.add("the JDBC {call ...} escape");
        return String.join(", ", described);
    }

    // ------------------------------------------------------------------ filesystem helpers

    /** The repository root, located from this class's own compiled location. */
    static Path repositoryRoot() {
        try {
            java.security.CodeSource codeSource =
                    AnalyticsSourceReadOnlyGuardTest.class.getProtectionDomain().getCodeSource();
            Assert.assertNotNull(codeSource,
                    "the test classes must have a code source; without one the source tree cannot be located,"
                            + " and a guard that cannot locate its subject would have to guess");
            Path classes = java.nio.file.Paths.get(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
            Path current = classes;
            while (current != null) {
                if (Files.isDirectory(current.resolve("src"))
                        && Files.isRegularFile(current.resolve(SHELL_GUARD))) {
                    return current;
                }
                current = current.getParent();
            }
            throw new AssertionError("could not locate the repository root above " + classes
                    + ": expected an ancestor holding src/ and " + SHELL_GUARD);
        } catch (java.net.URISyntaxException failure) {
            throw new AssertionError("the code source location is not a usable path: " + failure, failure);
        }
    }

    private static Path copyAnalyticsTree() throws IOException {
        Path source = repositoryRoot();
        Path copy = TestSupport.newTempDir("analytics-guard-copy");
        for (String relative : List.of(ANALYTICS_JDBC_SOURCE, ANALYTICS_STATISTICS_SOURCE)) {
            copyDirectory(source.resolve(relative), copy.resolve(relative));
        }
        return copy;
    }

    private static void copyDirectory(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(from)) {
            for (Path path : stream.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    /** Every {@code .java} file under a directory, in stable order. */
    private static List<Path> javaFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new AssertionError("the guard must scan " + directory + ", which does not exist; an"
                    + " unscanned tree is a layout failure, not a clean result");
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException failure) {
            throw new AssertionError("cannot walk " + directory + ": " + failure, failure);
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("cannot read " + file + ": " + failure, failure);
        }
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("cannot read " + file + ": " + failure, failure);
        }
    }
}
