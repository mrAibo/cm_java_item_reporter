package com.mraibo.cminsight.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Goal 02 section A: the IBM-independent core is genuinely independent, and this is where that is asserted.
 *
 * <h2>The rule, and why it is structural</h2>
 *
 * <p>{@code src/main/java} must reference no {@code com.ibm} type, with exactly one documented exception: the
 * comment in {@code connection/CmSession.java} that records its implementation may wrap
 * {@code DKDatastoreICM}. The core must also not reach into the optional adapter's package - a single
 * {@code com.mraibo.cminsight.ibm.internal} reference would make an optional source set mandatory, and a
 * core-only build would stop compiling.
 *
 * <p>This is what keeps the two build modes real rather than aspirational: the core compiles with no IBM JAR
 * present, and the adapter is the only thing that changes when one appears.
 *
 * <h2>What makes this test more than a formality</h2>
 *
 * <p>A directory scan that finds nothing looks exactly like a directory scan that scanned nothing, so both
 * halves are asserted:
 *
 * <ul>
 *   <li>a missing {@code src/main/java} FAILS - an unscanned tree is a layout failure, never a pass;</li>
 *   <li>the one allow-listed file must still EXIST, or the allow-list has become a licence for anything;</li>
 *   <li>planted violations in a temporary copy must be FOUND, and a second allowance in another file must be
 *       reported rather than tolerated.</li>
 * </ul>
 */
public class CoreIbmIsolationTest {

    private static final String CORE_SOURCE = "src/main/java";

    /** The acceptance case: no IBM reference escapes into the core. */
    public void theCommittedCoreHoldsNoIbmReference() {
        Path root = SourceGuard.repositoryRoot();

        List<String> violations = SourceGuard.findIbmReferencesInCore(root);

        Assert.assertTrue(violations.isEmpty(),
                "the IBM-independent core must reference no com.ibm type outside "
                        + SourceGuard.ISOLATION_ALLOWED_RELATIVE + ", but found " + violations.size() + ": "
                        + violations);
    }

    /** The core must not reach into the optional adapter's package either. */
    public void theCommittedCoreDoesNotReferenceTheOptionalAdapterPackage() {
        Path root = SourceGuard.repositoryRoot();
        Path core = root.resolve(CORE_SOURCE);
        Assert.assertTrue(Files.isDirectory(core),
                "the isolation guard must scan " + core + ", which does not exist; reporting success for an"
                        + " unscanned tree is the one outcome this guard must never produce");

        List<String> violations = new java.util.ArrayList<>();
        for (Path file : SourceGuard.javaFiles(core)) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new AssertionError("cannot read " + file + ": " + failure, failure);
            }
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).contains(SourceGuard.ADAPTER_PACKAGE_REFERENCE)) {
                    violations.add(root.relativize(file).toString().replace('\\', '/')
                            + ":" + (index + 1) + ": " + lines.get(index).trim());
                }
            }
        }

        Assert.assertTrue(violations.isEmpty(),
                "the core must not name the optional adapter's own package (" + SourceGuard.ADAPTER_PACKAGE_REFERENCE
                        + "): a single reference would make an optional source set mandatory, and a core-only"
                        + " build would stop compiling. Found: " + violations);
    }

    /**
     * The one allowance is real and bounded.
     *
     * <p>Two things are asserted: the allow-listed file still exists (so the allow-list is not a blanket
     * permission with nothing behind it), and the guard's own notion of the allowance names exactly that path.
     * A second allowance appearing in another core file is covered by
     * {@link #aSecondAllowanceInAnotherCoreFileIsReported()}.
     */
    public void theSingleAllowanceStillExistsAndIsBounded() {
        Path root = SourceGuard.repositoryRoot();

        Assert.assertTrue(SourceGuard.isolationAllowanceExists(root),
                "the one allow-listed file " + SourceGuard.ISOLATION_ALLOWED_RELATIVE + " must exist; an"
                        + " allow-list entry with no file behind it means the exception outlived the thing it"
                        + " was written for");

        String script = readString(SourceGuard.shellGuard());
        Assert.assertTrue(script.contains(SourceGuard.ISOLATION_ALLOWED_RELATIVE),
                "the committed shell guard must name the same single allowance, or the two guards disagree"
                        + " about which file is special");
        int allowances = script.split(java.util.regex.Pattern.quote(
                "ISOLATION_ALLOWED_RELATIVE="), -1).length - 1;
        Assert.assertEquals(1, allowances,
                "the shell guard must carry exactly ONE allow-list definition, but carries " + allowances
                        + ": a second one would be a second exception nobody reviewed");
    }

    /** A planted com.ibm reference in a core file is reported. */
    public void aPlantedIbmReferenceInTheCoreIsReported() throws IOException {
        Path copy = copyCoreTree();
        Path planted = copy.resolve(CORE_SOURCE).resolve("com/mraibo/cminsight/core/PlantedIbm.java");
        Files.createDirectories(planted.getParent());
        Files.writeString(planted, """
                package com.mraibo.cminsight.core;

                /** Planted by CoreIbmIsolationTest: a core file that names a vendor type. */
                final class PlantedIbm {
                    // com.ibm.mm.sdk.server.DKDatastoreICM would break the core-only build
                }
                """, StandardCharsets.UTF_8);

        List<String> violations = SourceGuard.findIbmReferencesInCore(copy);

        Assert.assertFalse(violations.isEmpty(),
                "a core file naming a com.ibm type must be reported: without this the clean result on the real"
                        + " tree proves nothing");
        Assert.assertTrue(violations.stream().anyMatch(entry -> entry.contains("PlantedIbm.java")),
                "and the report must name the offending file: " + violations);
    }

    /**
     * A SECOND allowance in another core file is refused.
     *
     * <p>The allowance is one path, not one pattern. A core file that mentions a vendor type in a comment -
     * which is the shape the single exception takes - must be reported unless it IS that one file.
     */
    public void aSecondAllowanceInAnotherCoreFileIsReported() throws IOException {
        Path copy = copyCoreTree();
        Path second = copy.resolve(CORE_SOURCE).resolve("com/mraibo/cminsight/core/SecondAllowance.java");
        Files.createDirectories(second.getParent());
        Files.writeString(second, """
                package com.mraibo.cminsight.core;

                /**
                 * Planted by CoreIbmIsolationTest: a second allowance. The guard tolerates exactly one file,
                 * so this comment reference must be refused: com.ibm. is a vendor package.
                 */
                final class SecondAllowance {
                }
                """, StandardCharsets.UTF_8);

        List<String> violations = SourceGuard.findIbmReferencesInCore(copy);

        Assert.assertFalse(violations.isEmpty(),
                "a second com.ibm allowance must be reported: the allow-list covers one path, and a guard that"
                        + " tolerated a second would tolerate any number");
        Assert.assertTrue(violations.stream().anyMatch(entry -> entry.contains("SecondAllowance.java")),
                "and it must name the second file: " + violations);
    }

    /**
     * A core tree with no {@code src/main/java} FAILS instead of passing.
     *
     * <p>The exact failure mode that would disable the isolation rule without anybody noticing.
     */
    public void aMissingCoreTreeFailsInsteadOfPassing() throws IOException {
        Path copy = copyCoreTree();
        deleteRecursively(copy.resolve(CORE_SOURCE));

        AssertionError failure = Assert.assertThrows(AssertionError.class,
                () -> SourceGuard.findIbmReferencesInCore(copy),
                "scanning a tree with no " + CORE_SOURCE + " must fail loudly, not report a clean result");

        Assert.assertTrue(failure.getMessage().replace('\\', '/').contains(CORE_SOURCE),
                "and the failure must name the directory it could not scan: " + failure.getMessage());
    }

    /** The core is large enough that "no reference" is a statement about a real tree. */
    public void theGuardScansTheWholeCore() {
        Path root = SourceGuard.repositoryRoot();

        List<Path> core = SourceGuard.javaFiles(root.resolve(CORE_SOURCE));
        Assert.assertTrue(core.size() >= 40,
                "the isolation guard must see the whole core, but found only " + core.size() + " .java file(s):"
                        + " a scan of a handful of files would not support the claim it makes");
    }

    private static Path copyCoreTree() throws IOException {
        Path source = SourceGuard.repositoryRoot();
        Path copy = TestSupport.newTempDir("core-isolation-copy");
        copyDirectory(source.resolve(CORE_SOURCE), copy.resolve(CORE_SOURCE));
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
