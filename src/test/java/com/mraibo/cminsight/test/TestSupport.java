package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.DatabaseVendor;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.config.WebAuthSettings;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Shared fixtures: temporary directories, credentials and repository profiles. No network, no DB. */
final class TestSupport {

    private TestSupport() {
    }

    /**
     * A fresh directory under the suite's own scratch root; the caller deletes it.
     *
     * <p>The OS temporary directory is deliberately not used: on a locked-down host (and inside this
     * harness's sandbox) the JVM is denied {@code Files.createTempDirectory} under
     * {@code java.io.tmpdir}, while the build tree it was started from is writable. Deriving the
     * scratch root from the compiled test classes keeps the suite runnable everywhere.
     */
    static Path newTempDir(String prefix) throws IOException {
        return Files.createTempDirectory(scratchRoot(), prefix);
    }

    private static Path scratchRoot() throws IOException {
        try {
            CodeSource codeSource = TestSupport.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                Path classesDir = Paths.get(codeSource.getLocation().toURI());
                Path parent = classesDir.getParent();
                if (parent != null) {
                    return Files.createDirectories(parent.resolve("test-tmp"));
                }
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // Fall through to the OS temporary directory.
        }
        return Paths.get(System.getProperty("java.io.tmpdir", "."));
    }

    static Path writeFile(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    /** Best-effort recursive delete: a leftover temporary directory must never fail a test. */
    static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort only.
                }
            }
        } catch (IOException ignored) {
            // Best effort only.
        }
    }

    /** A value for the {@code Authorization} header, exactly as a browser would send it. */
    static String basic(String user, String password) {
        String credentials = user + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a symbolic link, or reports {@code false} when the platform or the process privileges do
     * not allow it (for example a Windows session without Developer Mode or elevation).
     *
     * <p>Used to build the containment case a purely lexical check cannot see: a name that looks inside
     * the secrets directory while the file it names really lives outside it.
     */
    static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return Files.isSymbolicLink(link);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    /**
     * Creates a Windows directory junction, which needs no privileges, and reports whether it worked.
     *
     * <p>A junction is a reparse point too - an inside-looking name with an outside-resolving location -
     * so it is the portable fallback for the containment tests, and it is exactly the shape a Bash
     * {@code [ -r ... ]} probe gets wrong.
     */
    static boolean createWindowsJunction(Path link, Path target) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0 && Files.isDirectory(link);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * A syntactically valid, fully populated repository profile whose four credentials all come from
     * environment variables. No credential VALUE is ever part of a profile (Goal 01A section C).
     */
    static RepositoryProfile profile(String id) {
        String envId = id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
        return new RepositoryProfile(
                id,
                "Repository " + id,
                "SSID" + envId,
                DatabaseVendor.DB2,
                "jdbc:db2://db.example:50000/" + envId,
                "ICMADMIN",
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.CM_USER_KEY, "CM_" + envId + "_USER"),
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.CM_PASSWORD_KEY,
                        "CM_" + envId + "_PASSWORD"),
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.JDBC_USER_KEY,
                        "JDBC_" + envId + "_USER"),
                RepositoryProfile.credentialFromEnvironment(RepositoryProfile.JDBC_PASSWORD_KEY,
                        "JDBC_" + envId + "_PASSWORD"),
                "https://icn.example/icn",
                null);
    }

    /** Web credentials resolved from inline configuration, with an empty environment. */
    static WebAuthSettings credentials(String user, String password) {
        Properties properties = new Properties();
        properties.setProperty("web.auth.user", user);
        properties.setProperty("web.auth.password", password);
        return WebAuthSettings.resolve(AppConfig.fromProperties(properties), new SecretResolver(Map.of(), null));
    }
}
