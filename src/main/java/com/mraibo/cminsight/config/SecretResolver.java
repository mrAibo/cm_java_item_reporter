package com.mraibo.cminsight.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 */
public final class SecretResolver {

    private static final Set<PosixFilePermission> BROAD_READ =
            Set.of(PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);

    private final Map<String, String> environment;
    private final Path secretDir;
    private final List<String> warnings = new CopyOnWriteArrayList<>();

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

    private String readSecretFile(String name) {
        if (secretDir == null) {
            return null;
        }
        Path candidate = secretDir.resolve(name).normalize();
        if (!candidate.startsWith(secretDir)) {
            warn("Refusing secret file '" + name + "': it resolves outside " + secretDir + ".");
            return null;
        }
        if (!Files.isRegularFile(candidate)) {
            return null;
        }
        warnIfBroadlyReadable(candidate);
        try {
            for (String line : Files.readAllLines(candidate, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
            warn("Secret file " + candidate + " contains no usable value.");
            return null;
        } catch (IOException e) {
            warn("Could not read secret file " + candidate + ": " + e.getMessage());
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
