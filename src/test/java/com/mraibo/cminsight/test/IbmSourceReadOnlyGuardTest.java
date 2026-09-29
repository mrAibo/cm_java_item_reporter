package com.mraibo.cminsight.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Goal 02 section H, hard requirement: the IBM source set is READ-ONLY, and this is where that is asserted.
 *
 * <h2>Why a source guard rather than a code review</h2>
 *
 * <p>V1/V2 must never change server state. The IBM CM SDK exposes the mutating members right beside the
 * read ones - {@code commit()}, {@code checkIn()}, {@code del()} - so "we only call the readers" is a claim
 * about every line of a source set that will keep growing, and a claim made in review does not survive the
 * next commit. The guard makes it a property of the tree.
 *
 * <h2>What makes this test more than a formality</h2>
 *
 * <p>Three failure modes, and each is asserted rather than assumed:
 *
 * <ul>
 *   <li><strong>A guard that scans nothing.</strong> A missing or empty source directory must FAIL, not
 *       pass: a rename that empties the tree would otherwise disable the rule silently forever.</li>
 *   <li><strong>A guard that cannot see a real violation.</strong> The positive cases plant a forbidden call
 *       into a temporary copy of the tree and require it to be found, so the scanner is proven to bite.</li>
 *   <li><strong>A pattern table that quietly shrinks.</strong> The table is parsed out of the committed shell
 *       guard and must be non-empty and usable as a regex.</li>
 * </ul>
 *
 * <p>A private {@code getNativeConnection()} style reflection path is included in the rules even though it is
 * not a mutating call: it would hand the viewer a raw JDBC connection and invite exactly the analytics Goal 02
 * excludes, so the shell guard forbids it and this suite asserts the tree is clean of it.
 */
public class IbmSourceReadOnlyGuardTest {

    /** The optional source set the guard covers. */
    private static final String IBM_SOURCE = "src/ibm/java";

    /**
     * The optional source set's TEST tree.
     *
     * <p>Scanned by the real-SDK guard, because a test fake that anonymously implements a vendor interface
     * is invisible to every check that only compiles against the committed stubs.
     */
    private static final String IBM_TEST_SOURCE = "src/ibm-test/java";

    /** The committed tree holds no forbidden call, and that is the acceptance case. */
    public void theCommittedIbmSourceSetHoldsNoMutatingCall() {
        Path root = SourceGuard.repositoryRoot();

        List<String> violations = SourceGuard.findMutatingCalls(root, IBM_SOURCE);

        Assert.assertTrue(violations.isEmpty(),
                "the read-only adapter must contain no IBM CM mutating call, but found " + violations.size()
                        + ": " + violations + " (patterns applied: " + SourceGuard.describePatterns() + ")");
    }

    /** The guard has something to scan, and the table it applies is real. */
    public void theGuardScansARealTreeWithANonEmptyPatternTable() {
        Path root = SourceGuard.repositoryRoot();
        Path ibm = root.resolve(IBM_SOURCE);

        Assert.assertTrue(Files.isDirectory(ibm),
                "the IBM source set " + ibm + " must exist for this guard to mean anything");
        List<Path> sources = SourceGuard.javaFiles(ibm);
        Assert.assertTrue(sources.size() >= 5,
                "the guard must see the adapter's sources, but found only " + sources.size() + " .java file(s)"
                        + " under " + ibm + "; a guard scanning nothing reports success");
        Assert.assertTrue(SourceGuard.forbiddenCallPatterns().size() >= 10,
                "the forbidden call table parsed to only " + SourceGuard.forbiddenCallPatterns().size()
                        + " pattern(s): the rules were weakened, or the table moved and this guard no longer"
                        + " reads it");
    }

    /**
     * A planted mutating call IS found, so the absence above means something.
     *
     * <p>Planted in a temporary COPY of the tree: the real source set is never modified by a test, and the
     * scanner is proven to work on the same layout it will be pointed at.
     */
    public void aPlantedMutatingCallIsDetectedInACopiedTree() throws IOException {
        Path copy = copyIbmTree();
        Path planted = copy.resolve(IBM_SOURCE).resolve("com/mraibo/cminsight/ibm/internal/PlantedViolation.java");
        Files.createDirectories(planted.getParent());
        Files.writeString(planted, """
                package com.mraibo.cminsight.ibm.internal;

                /** Planted by IbmSourceReadOnlyGuardTest: a forbidden server mutation. */
                final class PlantedViolation {
                    void writeThrough(Object session) {
                        // a mutating call the guard must refuse
                    }
                }
                """.replace("// a mutating call the guard must refuse", "session.commit();"),
                StandardCharsets.UTF_8);

        List<String> violations = SourceGuard.findMutatingCalls(copy, IBM_SOURCE);

        Assert.assertFalse(violations.isEmpty(),
                "the guard found nothing in a tree containing 'session.commit();': the scanner does not bite,"
                        + " so its silence on the real tree proves nothing");
        Assert.assertTrue(violations.stream().anyMatch(entry -> entry.contains("PlantedViolation.java")),
                "and the violation must name the file that contains it, so a failure is actionable. Found: "
                        + violations);
    }

    /**
     * A tree with no {@code src/ibm/java} at all FAILS the guard instead of passing.
     *
     * <p>This is the failure mode that would disable the rule without anybody noticing: a rename, a moved
     * source set, or a build that only compiles the core. Reporting success for an unscanned tree is the one
     * outcome the guard must never produce.
     */
    public void aMissingIbmSourceTreeFailsInsteadOfPassing() throws IOException {
        Path copy = copyIbmTree();
        deleteRecursively(copy.resolve(IBM_SOURCE));

        AssertionError failure = Assert.assertThrows(AssertionError.class,
                () -> SourceGuard.findMutatingCalls(copy, IBM_SOURCE),
                "scanning a tree with no " + IBM_SOURCE + " must fail loudly, not report a clean result");

        Assert.assertTrue(failure.getMessage().replace('\\', '/').contains(IBM_SOURCE),
                "and the failure must name the directory it could not scan: " + failure.getMessage());
    }

    /** The native-JDBC reflection path is refused by name, so the exclusion is not incidental. */
    public void theNativeJdbcExtractionPathIsRefusedByName() {
        Path root = SourceGuard.repositoryRoot();
        Path shellGuard = SourceGuard.shellGuard();
        String script = readString(shellGuard);

        // The rule is enforced by the shell guard, which this suite mirrors; what is asserted here is that the
        // rule is still PRESENT and still names both shapes, so removing it from the guard fails a test rather
        // than quietly widening what the adapter may do.
        Assert.assertTrue(script.contains("getNativeConnection"),
                "the committed guard must keep forbidding the native JDBC extraction (getNativeConnection), which"
                        + " would hand the viewer a raw java.sql.Connection");
        Assert.assertTrue(script.contains("forbidden native JDBC extraction"),
                "and it must keep reporting that refusal, so the message an operator sees still explains it");

        List<String> violations = nativeJdbcExtractions(root);
        Assert.assertTrue(violations.isEmpty(),
                "and the committed IBM source set must be clean of it: " + violations);
    }

    /**
     * The same native-JDBC rule the shell guard applies, expressed over the tree.
     *
     * <p>Deliberately narrow, matching the shell guard: reflection onto a native connection, or a
     * {@code .connection()} call followed by a member access. Both hand the caller a raw JDBC handle, which
     * would invite exactly the analytics Goal 02 excludes.
     */
    private static List<String> nativeJdbcExtractions(Path root) {
        Path ibm = root.resolve(IBM_SOURCE);
        Assert.assertTrue(Files.isDirectory(ibm),
                "the native-JDBC guard must scan " + ibm + ", which does not exist; an unscanned tree is a"
                        + " layout failure, not a clean result");
        java.util.regex.Pattern rule = java.util.regex.Pattern.compile(
                "getNativeConnection|\\.connection[ \t]*\\([ \t]*\\)[ \t]*\\.");
        List<String> violations = new java.util.ArrayList<>();
        for (Path file : SourceGuard.javaFiles(ibm)) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new AssertionError("cannot read " + file + ": " + failure, failure);
            }
            for (int index = 0; index < lines.size(); index++) {
                if (rule.matcher(lines.get(index)).find()) {
                    violations.add(root.relativize(file).toString().replace('\\', '/')
                            + ":" + (index + 1) + ": " + lines.get(index).trim());
                }
            }
        }
        return violations;
    }

    /**
     * Goal 02A section D: the adapter source set never reads application configuration.
     *
     * <h2>Why this is a source property and not a behavioural one</h2>
     *
     * <p>The defect was a SECOND configuration load: {@code IbmCmAdapterProvider} resolved
     * {@code AppPaths.resolve().configurationFile(null)} itself while {@code Main} loaded the rules from the
     * configuration the operator actually selected. A behavioural test can only catch that when both paths
     * disagree on a case somebody thought to write; the property that makes the divergence impossible is that
     * the adapter's source cannot reach a configuration file at all. That is checkable, so it is checked.
     *
     * <p>The rule is deliberately narrow: exactly the app-configuration entry points. A generic ban on
     * {@code java.nio.file} would be a different (and wrong) rule - the adapter legitimately reads nothing
     * today, but the reason is this one, not a fear of I/O.
     */
    public void theAdapterSourceSetNeverReadsApplicationConfiguration() {
        Path root = SourceGuard.repositoryRoot();

        List<String> violations = configurationReads(root);

        Assert.assertTrue(violations.isEmpty(),
                "D: the adapter must consume the ClassificationRules the core loaded, so its source may not"
                        + " reach application configuration on its own, but found " + violations.size() + ": "
                        + violations);
    }

    /**
     * A planted configuration read IS found in a copied tree, so the clean result above means something.
     *
     * <p>Same shape as the read-only guard's positive control: planted in a temporary copy (the real source
     * set is never modified) and asserted to be reported with its file and line.
     */
    public void aPlantedConfigurationReadIsDetectedInACopiedTree() throws IOException {
        Path copy = copyIbmTree();
        Path planted = copy.resolve(IBM_SOURCE).resolve("com/mraibo/cminsight/ibm/internal/PlantedConfig.java");
        Files.writeString(planted, """
                package com.mraibo.cminsight.ibm.internal;

                import com.mraibo.cminsight.config.AppPaths;

                /** Planted by IbmSourceReadOnlyGuardTest: a second configuration load. */
                final class PlantedConfig {
                    Object rules() {
                        return AppPaths.resolve().configurationFile(null);
                    }
                }
                """, StandardCharsets.UTF_8);

        List<String> violations = configurationReads(copy);

        Assert.assertFalse(violations.isEmpty(),
                "the guard found nothing in a tree containing 'AppPaths.resolve().configurationFile(null)':"
                        + " the scanner does not bite, so its silence on the real tree proves nothing");
        Assert.assertTrue(violations.stream().anyMatch(entry -> entry.contains("PlantedConfig.java")),
                "and the violation names the file that contains it, so a failure is actionable. Found: "
                        + violations);
    }

    /**
     * The IBM TEST tree never hand-writes an anonymous {@code com.ibm} type, so the real-SDK compile
     * cannot be broken by a fake that only CI can check.
     *
     * <h2>The blind spot this closes</h2>
     *
     * <p>The committed stubs under {@code tests/ibm-stubs} are deliberately NARROWER than the real SDK: they
     * omit the mutating members so a mutating call cannot compile in {@code src/ibm/java}. A test fake that
     * anonymously implements a vendor INTERFACE must therefore satisfy fewer abstract methods than the real
     * interface declares - so it compiles against the stubs, passes every CI run, and fails only for the
     * person who owns the proprietary jars. Measured for real in this tree:
     * {@code IbmSessionPoisoningTest} anonymously implemented {@code dkDatastoreDef} and
     * {@code dkDatastoreAdmin}, which compiled here and broke {@code ./build.sh --require-ibm} with
     * "does not override abstract method clearCache()".
     *
     * <p>The safe shape is a {@link java.lang.reflect.Proxy}, which names no member of the interface and is
     * therefore identical under both surfaces - see
     * {@code IbmFakes.foreignVendorType(Class)}. A named subclass of a concrete vendor CLASS
     * ({@code FakeDatastoreDef extends DKDatastoreDefICM}, {@code FakeItemTypeDef extends DKItemTypeDefICM})
     * is fine and is not what this rule targets: those are proven to compile AND run against the real 8.7
     * jars, and the rule is deliberately about the anonymous shape that hides javac's obligation.
     *
     * <p>A rule that cannot be broken on purpose is not a rule, so the positive control below plants an
     * anonymous vendor type in a copied tree and requires it to be found.
     */
    public void theIbmTestTreeNeverHandWritesAnAnonymousVendorType() {
        Path root = SourceGuard.repositoryRoot();

        List<String> violations = anonymousVendorTypes(root);

        Assert.assertTrue(violations.isEmpty(),
                "an anonymous com.ibm type in the IBM test tree is a fake that CI cannot fully check: the"
                        + " stubs are narrower than the real SDK, so it compiles here and fails on the"
                        + " --require-ibm path. Use IbmFakes.foreignVendorType(...) instead. Found "
                        + violations.size() + ": " + violations);
    }

    /** A planted anonymous vendor type IS found in a copied test tree, so the clean result means something. */
    public void aPlantedAnonymousVendorTypeIsDetectedInACopiedTree() throws IOException {
        Path copy = copyTree(IBM_TEST_SOURCE);
        Path planted = copy.resolve(IBM_TEST_SOURCE)
                .resolve("com/mraibo/cminsight/ibm/internal/PlantedForeignVendor.java");
        Files.writeString(planted, """
                package com.mraibo.cminsight.ibm.internal;

                /** Planted by IbmSourceReadOnlyGuardTest: an anonymous vendor interface implementation. */
                final class PlantedForeignVendor {
                    Object foreign() {
                        return new com.ibm.mm.sdk.common.dkDatastoreAdmin() {
                        };
                    }
                }
                """, StandardCharsets.UTF_8);

        List<String> violations = anonymousVendorTypes(copy);

        Assert.assertFalse(violations.isEmpty(),
                "the guard found nothing in a tree containing an anonymous dkDatastoreAdmin: the scanner does"
                        + " not bite, so its silence on the real tree proves nothing");
        Assert.assertTrue(violations.stream().anyMatch(entry -> entry.contains("PlantedForeignVendor.java")),
                "and the violation names the file that contains it, so a failure is actionable. Found: "
                        + violations);
    }

    /**
     * Every line of {@code src/ibm-test/java} that anonymously extends a {@code com.ibm} type.
     *
     * @return one {@code file:line: text} entry per offending line, relative to {@code root}
     */
    private static List<String> anonymousVendorTypes(Path root) {
        Path tests = root.resolve(IBM_TEST_SOURCE);
        Assert.assertTrue(Files.isDirectory(tests),
                "the real-SDK guard must scan " + tests + ", which does not exist; an unscanned tree is a"
                        + " layout failure, not a clean result");
        // An anonymous subclass or implementation of a vendor type: "new com.ibm.<anything>(<args>) {". The
        // argument list is matched without crossing a statement boundary, so an ordinary allocation
        // followed by a later block cannot be mistaken for one.
        java.util.regex.Pattern rule = java.util.regex.Pattern.compile(
                "new[ \\t]+com\\.ibm\\.[A-Za-z0-9_.]*[ \\t]*\\([^;{}]*\\)[ \\t]*\\{");
        List<String> violations = new java.util.ArrayList<>();
        for (Path file : SourceGuard.javaFiles(tests)) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new AssertionError("cannot read " + file + ": " + failure, failure);
            }
            for (int index = 0; index < lines.size(); index++) {
                if (rule.matcher(lines.get(index)).find()) {
                    violations.add(root.relativize(file).toString().replace('\\', '/')
                            + ":" + (index + 1) + ": " + lines.get(index).trim());
                }
            }
        }
        return violations;
    }

    /**
     * Every line of {@code src/ibm/java} that reaches an application-configuration entry point.
     *
     * @return one {@code file:line: text} entry per offending line, relative to {@code root}
     */
    private static List<String> configurationReads(Path root) {
        Path ibm = root.resolve(IBM_SOURCE);
        Assert.assertTrue(Files.isDirectory(ibm),
                "the section-D guard must scan " + ibm + ", which does not exist; an unscanned tree is a"
                        + " layout failure, not a clean result");
        java.util.regex.Pattern rule = java.util.regex.Pattern.compile(
                "AppPaths|AppConfig|configurationFile[ \t]*\\(|classificationsFile[ \t]*\\(|"
                        + "ClassificationRules[ \t]*\\.[ \t]*load[ \t]*\\(");
        List<String> violations = new java.util.ArrayList<>();
        for (Path file : SourceGuard.javaFiles(ibm)) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new AssertionError("cannot read " + file + ": " + failure, failure);
            }
            for (int index = 0; index < lines.size(); index++) {
                if (rule.matcher(lines.get(index)).find()) {
                    violations.add(root.relativize(file).toString().replace('\\', '/')
                            + ":" + (index + 1) + ": " + lines.get(index).trim());
                }
            }
        }
        return violations;
    }

    private static Path copyIbmTree() throws IOException {
        return copyTree(IBM_SOURCE);
    }

    /** A temporary copy of one tree, at the same relative path, so a guard scans the layout it expects. */
    private static Path copyTree(String relative) throws IOException {
        Path source = SourceGuard.repositoryRoot();
        Path copy = TestSupport.newTempDir("ibm-guard-copy");
        copyDirectory(source.resolve(relative), copy.resolve(relative));
        return copy;
    }

    private static void copyDirectory(Path from, Path to) throws IOException {
        try (var stream = Files.walk(from)) {
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
        try (var stream = Files.walk(root)) {
            paths = stream.sorted(java.util.Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
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
