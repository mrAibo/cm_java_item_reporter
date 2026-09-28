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

    private static Path copyIbmTree() throws IOException {
        Path source = SourceGuard.repositoryRoot();
        Path copy = TestSupport.newTempDir("ibm-guard-copy");
        copyDirectory(source.resolve(IBM_SOURCE), copy.resolve(IBM_SOURCE));
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
