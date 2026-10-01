package com.mraibo.cminsight.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Resolves credentials from environment variables or from files under a secrets directory.
 *
 * <p>Secrets are never read from the tracked configuration or a repository profile. A profile stores
 * the <em>name</em> of the environment variable or secret file; the value is looked up at runtime.
 *
 * <p>Resolution is explicit and fails closed: once a level is configured (environment, then file,
 * then inline) it is authoritative. A configured-but-missing environment variable does not silently
 * fall back to a weaker source.
 *
 * <p><strong>Containment of a secret file is REAL, not lexical.</strong> A {@code .file} reference must
 * name a file that really lives inside the secrets directory: the lexical check (relative, no {@code ..})
 * is only the first half, and the resolved file is then compared by its real location, so a symbolic
 * link, a Windows directory junction or any other reparse point placed inside the directory and pointing
 * outside it is REFUSED rather than followed. The directory itself is resolved to its real location too,
 * so a {@code secrets.dir} that is reached through a link still works. Refusals are reported through
 * {@link #warnings()} and produce no value, so a caller fails closed instead of falling back to a weaker
 * source.
 *
 * <p>Known limit: a <em>hard</em> link cannot be detected this way. A hard link has no target to resolve
 * - it is an ordinary directory entry for the same file - so a hard link created inside the secrets
 * directory is inside it by every name-based test. Creating one requires write access to that directory,
 * which is already the permission that grants access to every secret in it.
 */
public final class SecretResolver {

    private static final Set<PosixFilePermission> BROAD_READ =
            Set.of(PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);

    private final Map<String, String> environment;
    private final Path secretDir;
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    /**
     * The real location of {@link #secretDir}, resolved once on first use.
     *
     * <p>Cached because every containment decision must compare against the same resolved directory, and
     * because the resolution is a filesystem call on a path that does not change while the application
     * runs. Deliberately NOT cached when it fails: a secrets directory that does not exist yet must be
     * re-checked, so creating it later starts resolving instead of being permanently refused.
     */
    private volatile Path realSecretDir;

    public static SecretResolver system(Path secretDir) {
        return new SecretResolver(System.getenv(), secretDir);
    }

    public SecretResolver(Map<String, String> environment, Path secretDir) {
        this.environment = environment == null ? Map.of() : Map.copyOf(environment);
        this.secretDir = secretDir == null ? null : secretDir.toAbsolutePath().normalize();
    }

    public Path secretDir() {
        return secretDir;
    }

    /** Non-fatal problems noticed while resolving, safe to print (never contain a value). */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /**
     * Decides where a credential comes from, in the fixed order environment, file, inline, default.
     *
     * @param envKey      configured environment variable name, or {@code null}
     * @param fileKey     configured secret file name (relative to the secrets directory), or {@code null}
     * @param inlineValue value written directly into the configuration, or {@code null}
     * @param label       human-readable field name used in diagnostics, never a value
     */
    public SecretRef classify(String envKey, String fileKey, String inlineValue, String label) {
        String env = trimToNull(envKey);
        if (env != null) {
            String value = trimToNull(environment.get(env));
            if (value == null) {
                return new SecretRef(SecretRef.Source.MISSING,
                        label + ": environment variable '" + env + "' is not set");
            }
            return new SecretRef(SecretRef.Source.ENVIRONMENT, env, value);
        }

        String file = trimToNull(fileKey);
        if (file != null) {
            String value = readSecretFile(file);
            if (value == null) {
                return new SecretRef(SecretRef.Source.MISSING,
                        label + ": secret file '" + file + "' could not be read from " + describeSecretDir());
            }
            return new SecretRef(SecretRef.Source.FILE, file, value);
        }

        String inline = trimToNull(inlineValue);
        if (inline != null) {
            warn("'" + label + "' is stored inline in the configuration file; prefer the '.env' or '.file' indirection.");
            return new SecretRef(SecretRef.Source.INLINE, null, inline);
        }

        return new SecretRef(SecretRef.Source.MISSING, label + ": not configured");
    }

    /** A built-in development default, which the exposure policy treats specially. */
    public SecretRef defaults(String label, String value) {
        warn("'" + label + "' is not configured; the built-in development default is in use.");
        return new SecretRef(SecretRef.Source.DEFAULT, label, value);
    }

    /** Returns the resolved value or {@code null}. */
    public String resolve(SecretRef ref) {
        return ref == null ? null : ref.value();
    }

    /**
     * Reads the first non-empty line of a secret file, or {@code null} when it cannot be read inside the
     * secrets directory.
     *
     * <p>Two containment checks, in this order:
     *
     * <ol>
     *   <li>lexical - the name must stay under the configured directory ({@code ..}, an absolute or
     *       drive-qualified path and anything else that normalises outside it is refused here);</li>
     *   <li>real - the file's real location, with every symbolic link, junction and other reparse point
     *       along the whole path resolved, must still be under the real location of the secrets
     *       directory. This is what a purely lexical check misses: a junction placed inside the
     *       directory and pointing outside it has an inside-looking name and an outside-resolving
     *       location.</li>
     * </ol>
     *
     * <p>A refusal records a warning and returns {@code null}, so {@link #classify} reports the source as
     * unresolved and the caller fails closed. Nothing about the file's contents is ever included.
     */
    private String readSecretFile(String name) {
        if (secretDir == null) {
            return null;
        }
        Path candidate = secretDir.resolve(name).normalize();
        if (!candidate.startsWith(secretDir)) {
            warn("Refusing secret file '" + name + "': it resolves outside " + secretDir + ".");
            return null;
        }

        Path realDir = realSecretDir();
        if (realDir == null) {
            return null;
        }

        final Path real;
        try {
            real = candidate.toRealPath();
        } catch (NoSuchFileException e) {
            // Missing file, or a link whose target does not exist: classify() reports the source as
            // unresolved, so there is nothing to add here.
            return null;
        } catch (IOException e) {
            warn("Refusing secret file '" + name + "': its real location could not be determined ("
                    + e.getClass().getSimpleName() + ").");
            return null;
        }
        if (!real.startsWith(realDir)) {
            warn("Refusing secret file '" + name + "': it resolves to " + real + ", which is outside the"
                    + " secrets directory " + realDir + " (a link, junction or other reparse point inside"
                    + " that directory does not extend it).");
            return null;
        }
        if (!Files.isRegularFile(real)) {
            return null;
        }

        warnIfBroadlyReadable(real);
        // Read through the VALIDATED real path, with NOFOLLOW_LINKS so that a final component swapped
        // for a link between the check above and this open cannot be followed into an outside target.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        } catch (IOException e) {
            warn("Could not read secret file " + real + ": " + e.getMessage());
            return null;
        }
        warn("Secret file " + real + " contains no usable value.");
        return null;
    }

    /**
     * The real location of the configured secrets directory, or {@code null} when it does not exist or
     * cannot be resolved - in which case nothing under it can be read either, which is the fail-closed
     * direction.
     */
    private Path realSecretDir() {
        Path cached = realSecretDir;
        if (cached != null) {
            return cached;
        }
        if (!Files.isDirectory(secretDir)) {
            return null;
        }
        try {
            Path real = secretDir.toRealPath();
            realSecretDir = real;
            return real;
        } catch (IOException e) {
            warn("Could not resolve the secrets directory " + secretDir + " to a real path: "
                    + e.getMessage());
            return null;
        }
    }

    private void warnIfBroadlyReadable(Path file) {
        try {
            PosixFileAttributes attributes = Files.readAttributes(file, PosixFileAttributes.class);
            Set<PosixFilePermission> permissions = attributes.permissions();
            if (permissions.stream().anyMatch(BROAD_READ::contains)) {
                warn("Secret file " + file + " is readable by group or other; restrict it with chmod 600.");
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // Not a POSIX filesystem (for example Windows): there is nothing to check.
        }
    }

    private String describeSecretDir() {
        return secretDir == null ? "(no secrets directory configured)" : secretDir.toString();
    }

    private void warn(String message) {
        warnings.add(message);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public String toString() {
        return "SecretResolver[secretDir=" + describeSecretDir() + "]";
    }
}
