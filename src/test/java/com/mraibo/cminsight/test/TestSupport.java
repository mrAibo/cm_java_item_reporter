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

    /** A syntactically valid, fully populated repository profile with no credentials in it. */
    static RepositoryProfile profile(String id) {
        String envId = id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
        return new RepositoryProfile(
                id,
                "Repository " + id,
                "SSID" + envId,
                DatabaseVendor.DB2,
                "jdbc:db2://db.example:50000/" + envId,
                "ICMADMIN",
                "CM_" + envId + "_USER",
                "CM_" + envId + "_PASSWORD",
                "JDBC_" + envId + "_USER",
                "JDBC_" + envId + "_PASSWORD",
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
