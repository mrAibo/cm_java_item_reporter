package com.mraibo.cminsight.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The committed executable bit is part of the build contract, and Windows cannot check it locally.
 *
 * <h2>The failure this exists for</h2>
 *
 * <p>This project has now shipped the same defect four times: a new shell script committed with mode
 * {@code 100644} instead of {@code 100755}. On the Windows development host git is configured
 * {@code core.filemode=false}, so the mode is simply not recorded and a working tree that runs the script
 * perfectly gives no signal at all; on a clean Linux checkout the script is not executable, and CI fails at
 * its <em>first</em> step - "Verify script permissions" - before it has compiled anything. The practical
 * effect is that a change which is green locally is red remotely, and the reason is nowhere in the code
 * being reviewed. Three previous occurrences were each fixed by hand.
 *
 * <h2>Why this reads the git index instead of the filesystem</h2>
 *
 * <p>On Windows {@code Files.isExecutable} answers from file-extension heuristics and reports {@code true}
 * for a plain text file, so a filesystem check here would pass on the one host where the bug is introduced.
 * The committed MODE, by contrast, is exactly what a Linux checkout materialises and exactly what the CI
 * step tests. Reading it from git is therefore both portable and the same question CI asks.
 *
 * <h2>Scope, and the deliberate exemptions</h2>
 *
 * <p>Only files something INVOKES need the bit. The sourced modules under {@code bin/lib} are read by
 * {@code .} or {@code source} and are never executed, so requiring the bit for them would be cargo cult;
 * they are listed below as an explicit exemption rather than silently skipped, so adding a new sourced
 * module is a deliberate edit to this list.
 *
 * <p>A directory git cannot report on is not a failure: if git is unavailable, or the tree is not a
 * repository, the check reports nothing rather than inventing a verdict - it guards a specific omission and
 * is not a substitute for the build.
 */
public final class ScriptPermissionTest {

    /** Shell scripts that something executes, and which therefore require the committed executable bit. */
    private static final List<String> REQUIRED = List.of(
            "build.sh",
            "tests/selftest.sh",
            "bin/compile.sh",
            "bin/start.sh",
            "bin/stop.sh",
            "bin/restart.sh",
            "bin/status.sh",
            "bin/doctor.sh",
            "bin/clean.sh");

    /**
     * Sourced shell modules, which are read rather than executed and therefore carry no bit requirement.
     *
     * <p>Named individually so that a NEW sourced module has to be added here, rather than being exempted by
     * a wildcard that would also exempt a script somebody forgot to make executable.
     */
    private static final List<String> SOURCED_NOT_EXECUTED = List.of(
            "bin/lib/cm-insight-addr.sh",
            "bin/lib/cm-insight-lifecycle.sh");

    /** True when a path is a shell script the CI permission step walks. */
    private static boolean needsExecutableBit(String path) {
        if (SOURCED_NOT_EXECUTED.contains(path)) {
            return false;
        }
        return path.startsWith("tests/shell/") && path.endsWith(".sh");
    }

    /**
     * Every committed {@code tests/shell/*.sh} carries the executable bit.
     *
     * <p>CI walks exactly this glob and executes each file, so a missing bit is a build failure that is
     * invisible on Windows.
     */
    public void everyCommittedShellRegressionTestIsExecutable() {
        List<String> offenders = missingExecutableBit();
        Assert.assertTrue(offenders.isEmpty(),
                "these committed shell scripts lack the executable bit (git mode 100644) and WILL fail the"
                        + " CI permission step on a clean Linux checkout, even though they run fine on a"
                        + " Windows host with core.filemode=false. Fix with"
                        + " `git update-index --chmod=+x <path>`: " + offenders);
    }

    /**
     * The operational scripts listed by the CI step carry the bit too.
     *
     * <p>These are the long-standing files, so this assertion is mostly a regression guard against a
     * permission change rather than against a new file - but it is the same rule and costs nothing.
     */
    public void theOperationalScriptsAreExecutable() {
        List<String> offenders = new ArrayList<>();
        for (String path : REQUIRED) {
            String mode = committedMode(path);
            if (mode != null && !"100755".equals(mode)) {
                offenders.add(path + " (mode " + mode + ")");
            }
        }
        Assert.assertTrue(offenders.isEmpty(),
                "these operational scripts are not committed executable: " + offenders);
    }

    /**
     * The mode is read from the git index, and the check is meaningful.
     *
     * <p>Asserts that git actually answered, so a future environment where this check silently degrades into
     * "found nothing, therefore passed" is caught rather than trusted. It also asserts that the two
     * exemptions really are sourced modules rather than an accidental blank list.
     */
    public void theCommittedModeIsReadableAndTheExemptionsAreReal() {
        List<String> entries = gitLsFiles();
        if (entries.isEmpty()) {
            // Not a git checkout (an exported tree, for instance). The check cannot see modes, and saying so
            // is better than a false pass or a false failure.
            return;
        }
        long shellTests = committedPaths(entries).stream()
                .filter(ScriptPermissionTest::needsExecutableBit)
                .count();
        Assert.assertTrue(shellTests > 0,
                "no tests/shell/*.sh entry survived the executable-bit filter, so the permission check would"
                        + " pass by scanning nothing - which is the one outcome a guard must never produce."
                        + " Found " + committedPaths(entries).size() + " committed path(s) in total");

        // The predicate is exercised against real committed paths, so a future edit that makes it match
        // nothing is caught here rather than silently turning the whole check into a no-op. This is not
        // hypothetical: the first version of the counting filter above tested the WHOLE index line instead
        // of the path inside it, so it matched nothing and this assertion is what refused to let it pass.
        Assert.assertTrue(committedPaths(entries).stream().anyMatch(ScriptPermissionTest::needsExecutableBit),
                "the executable-bit predicate must match at least one committed path");
        Assert.assertFalse(needsExecutableBit("bin/lib/cm-insight-lifecycle.sh"),
                "a sourced shell module is read rather than executed, so it must stay exempt");
        Assert.assertFalse(needsExecutableBit("tests/shell/README.md"),
                "a non-script file under tests/shell must not be treated as an executed script");
        Assert.assertFalse(needsExecutableBit("build.sh"),
                "the root build script is checked by its own list, not by the tests/shell glob");

        Path root = SourceGuard.repositoryRoot();
        for (String sourced : SOURCED_NOT_EXECUTED) {
            Assert.assertTrue(Files.isRegularFile(root.resolve(sourced)),
                    "the sourced-module exemption names " + sourced + ", which does not exist. An exemption"
                            + " with no file behind it outlives the reason it was written for");
        }
        for (String required : REQUIRED) {
            Assert.assertTrue(Files.isRegularFile(root.resolve(required)),
                    "the required-executable list names " + required + ", which does not exist");
        }
    }

    /**
     * The committed path of each {@code git ls-files -s} index line.
     *
     * <p>An index line is {@code <mode> <hash> <stage>\t<path>}, so the path is the fourth whitespace-separated
     * field. Extracting it here, once, is what keeps the executable-bit predicate from being handed a whole
     * line - a mistake that silently matches nothing, which is the failure mode this whole class is about.
     */
    private static List<String> committedPaths(List<String> entries) {
        List<String> paths = new ArrayList<>();
        for (String entry : entries) {
            String[] parts = entry.split("\\s+");
            if (parts.length >= 4) {
                paths.add(parts[3]);
            }
        }
        return paths;
    }

    /** Committed paths that need the bit and do not have it. */
    private static List<String> missingExecutableBit() {
        List<String> offenders = new ArrayList<>();
        for (String entry : gitLsFiles()) {
            String[] parts = entry.split("\\s+");
            if (parts.length < 4) {
                continue;
            }
            String mode = parts[0];
            String path = parts[3];
            if (needsExecutableBit(path) && !"100755".equals(mode)) {
                offenders.add(path + " (mode " + mode + ")");
            }
        }
        return offenders;
    }

    /** The committed mode for one path, or null when git does not report it. */
    private static String committedMode(String path) {
        for (String entry : gitLsFiles()) {
            String[] parts = entry.split("\\s+");
            if (parts.length >= 4 && parts[3].equals(path)) {
                return parts[0];
            }
        }
        return null;
    }

    /**
     * {@code git ls-files -s} as raw index lines, or an empty list when git cannot answer.
     *
     * <p>An empty list is deliberately indistinguishable from "not a git checkout" here: both mean the check
     * has no subject, and the caller reports that rather than failing.
     */
    private static List<String> gitLsFiles() {
        try {
            ProcessBuilder builder = new ProcessBuilder("git", "ls-files", "-s");
            builder.directory(SourceGuard.repositoryRoot().toFile());
            builder.redirectErrorStream(false);
            Process process = builder.start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            if (process.exitValue() != 0) {
                return List.of();
            }
            List<String> entries = new ArrayList<>();
            for (String line : output.split("\\R")) {
                if (!line.isBlank()) {
                    entries.add(line.trim());
                }
            }
            return entries;
        } catch (IOException | InterruptedException | RuntimeException unavailable) {
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    /** Public no-argument constructor: the shape {@code SelfTest} instantiates suites through. */
    public ScriptPermissionTest() {
    }
}
