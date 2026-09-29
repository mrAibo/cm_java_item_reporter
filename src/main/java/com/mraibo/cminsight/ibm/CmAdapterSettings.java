package com.mraibo.cminsight.ibm;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.ClassificationRules;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.core.CmPoolSettings;

import java.time.Duration;
import java.util.Objects;
import java.util.Properties;

/**
 * Everything the core hands to a CM adapter: the already-validated pool bounds, the metadata-cache
 * time-to-live, the one object allowed to turn a credential reference into a credential value, and the
 * already-parsed ItemType classification rules.
 *
 * <h2>Settings are read once, by the core</h2>
 *
 * <p>The adapter never parses configuration. Every value here has already been range-checked against the
 * documented key and default, so a misconfigured pool cannot reach the physical layer and diagnostics can
 * print exactly the numbers the pool is running with:
 *
 * <pre>
 *   cm.pool.size                 default 4,    1..64
 *   cm.pool.borrow.timeout.ms    default 5000, 1..600000
 *   cm.pool.max.age.minutes      default 30,   1..1440
 *   cm.pool.max.operations       default 1000, 1..1000000
 *   cache.metadata.ttl.seconds   default 600,  0..86400
 * </pre>
 *
 * <h2>Why the classification rules travel in here</h2>
 *
 * <p>{@link ClassificationRules} used to be re-read by the adapter from the DEFAULT configuration path,
 * which meant a runtime started with {@code --config <path>} could label ItemTypes with rules that
 * differ from the ones the launcher printed and validated. Configuration is parsed once, by the core, and
 * the resulting immutable rule set is passed in here - so there is exactly one answer to "which business
 * classification does this ItemType have", and the adapter has no way to guess a config file.
 *
 * <p>The component is mandatory and non-null on purpose. A rule set that the core always supplies (it may
 * be empty, and an empty set has its own documented fallback label) removes the "no rules were passed, so
 * fall back" branch, which is the branch that made the divergence possible.
 *
 * <h2>Why the adapter is given the secret resolver</h2>
 *
 * <p>A credential value is reachable only through {@link SecretResolver#resolve(com.mraibo.cminsight.config.SecretRef)};
 * the profile itself stores where a credential comes from and never the value. An adapter therefore
 * cannot connect with the profile alone, and passing resolved values around as plain strings would spread
 * them across one more object than necessary. So the adapter receives the resolver and reads exactly the
 * two CM credentials it needs, at the moment it needs them, with
 * {@link com.mraibo.cminsight.config.RepositoryProfile#resolveCmCredentials(SecretResolver)}.
 *
 * <p>Consequence, and it is deliberate: a credential that disappears between activation and a later
 * session open fails THAT borrow rather than silently connecting with a stale value.
 *
 * <p>This record carries no credential: it holds a resolver, bounds and classification rules. Its
 * {@link #toString()} shows the bounds, the rule count and the secrets directory only.
 *
 * @param pool              the validated pool bounds
 * @param metadataCacheTtl  how long a metadata snapshot stays fresh; {@link Duration#ZERO} disables caching
 * @param secrets           the resolver that reads a credential value, never a value itself
 * @param classifications   the classification rules the core loaded from the configuration it actually
 *                          selected; never {@code null}, possibly empty (then every ItemType gets the rule
 *                          set's own fallback label)
 */
public record CmAdapterSettings(CmPoolSettings pool,
                                Duration metadataCacheTtl,
                                SecretResolver secrets,
                                ClassificationRules classifications) {

    public static final String POOL_SIZE_KEY = "cm.pool.size";
    public static final String BORROW_TIMEOUT_KEY = "cm.pool.borrow.timeout.ms";
    public static final String MAX_AGE_KEY = "cm.pool.max.age.minutes";
    public static final String MAX_OPERATIONS_KEY = "cm.pool.max.operations";
    public static final String METADATA_TTL_KEY = "cache.metadata.ttl.seconds";

    /** Documented default of {@link #POOL_SIZE_KEY}. */
    public static final int DEFAULT_POOL_SIZE = 4;
    /** Documented default of {@link #BORROW_TIMEOUT_KEY}, in milliseconds. */
    public static final int DEFAULT_BORROW_TIMEOUT_MS = 5_000;
    /** Documented default of {@link #MAX_AGE_KEY}, in minutes. */
    public static final int DEFAULT_MAX_AGE_MINUTES = 30;
    /** Documented default of {@link #MAX_OPERATIONS_KEY}. */
    public static final long DEFAULT_MAX_OPERATIONS = 1_000L;
    /** Documented default of {@link #METADATA_TTL_KEY}, in seconds. */
    public static final int DEFAULT_METADATA_TTL_SECONDS = 600;

    /**
     * The explicit empty rule set of {@link #defaults(SecretResolver)}.
     *
     * <p>Pure and I/O-free by construction: {@link ClassificationRules#fromProperties(java.util.Properties, String)}
     * parses the properties it is handed and never resolves a path, which is exactly why this is allowed
     * in a class the adapter reads while a config-file lookup is not.
     */
    private static final ClassificationRules NO_RULES =
            ClassificationRules.fromProperties(new Properties(), "no configuration was read");

    public CmAdapterSettings {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(metadataCacheTtl, "metadataCacheTtl");
        if (metadataCacheTtl.isNegative()) {
            throw new IllegalArgumentException(METADATA_TTL_KEY + " must not be negative");
        }
        Objects.requireNonNull(secrets, "secrets");
        // Mandatory, and not defaulted: the whole point of section D is that the adapter consumes the rule
        // set the core loaded, so "no rules were supplied" is a programming error rather than a silent
        // fallback to a config file the adapter would have to guess.
        Objects.requireNonNull(classifications, "classifications");
    }

    /**
     * Reads the adapter settings from configuration, failing closed on every out-of-range value.
     *
     * <p>Uses the same strict integer readers as the rest of the runtime, so a typo such as
     * {@code cm.pool.size=0} or a value above the documented maximum is a configuration error naming the
     * key, never a silently clamped number.
     *
     * <p>{@code classifications} must be the instance the caller already loaded from THIS configuration,
     * not a second load: passing the loaded instance is what makes the rules single-source.
     *
     * @throws com.mraibo.cminsight.config.ConfigException when a value is not an integer or is out of range
     */
    public static CmAdapterSettings from(AppConfig config, SecretResolver secrets,
                                        ClassificationRules classifications) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(secrets, "secrets");
        Objects.requireNonNull(classifications, "classifications");

        int size = config.getInt(POOL_SIZE_KEY, DEFAULT_POOL_SIZE, 1, 64);
        int borrowTimeoutMs = config.getInt(BORROW_TIMEOUT_KEY, DEFAULT_BORROW_TIMEOUT_MS, 1, 600_000);
        int maxAgeMinutes = config.getInt(MAX_AGE_KEY, DEFAULT_MAX_AGE_MINUTES, 1, 1_440);
        long maxOperations = config.getLong(MAX_OPERATIONS_KEY, DEFAULT_MAX_OPERATIONS, 1L, 1_000_000L);
        int metadataTtlSeconds = config.getInt(METADATA_TTL_KEY, DEFAULT_METADATA_TTL_SECONDS, 0, 86_400);

        return new CmAdapterSettings(
                new CmPoolSettings(size, Duration.ofMillis(borrowTimeoutMs), Duration.ofMinutes(maxAgeMinutes),
                        maxOperations),
                Duration.ofSeconds(metadataTtlSeconds),
                secrets,
                classifications);
    }

    /**
     * The documented defaults for a caller that has no configuration to read.
     *
     * <p>The rule set is still EXPLICIT: an empty one, whose fallback label is the documented
     * {@link ClassificationRules#FALLBACK_LABEL}. It is not read from a configuration file, because there
     * is none - which is the entire difference this method preserves. Use
     * {@link #defaults(SecretResolver, ClassificationRules)} when rules exist.
     */
    public static CmAdapterSettings defaults(SecretResolver secrets) {
        return defaults(secrets, NO_RULES);
    }

    /** {@link #defaults(SecretResolver)} with rules the caller already holds. */
    public static CmAdapterSettings defaults(SecretResolver secrets, ClassificationRules classifications) {
        return new CmAdapterSettings(CmPoolSettings.defaults(),
                Duration.ofSeconds(DEFAULT_METADATA_TTL_SECONDS), secrets, classifications);
    }

    /** The metadata time-to-live in whole seconds, for diagnostics. */
    public int metadataCacheTtlSeconds() {
        return (int) metadataCacheTtl.toSeconds();
    }

    /** True when metadata snapshots are retained at all; a TTL of 0 means every read loads through. */
    public boolean cachesMetadata() {
        return !metadataCacheTtl.isZero();
    }

    @Override
    public String toString() {
        return "CmAdapterSettings[" + pool
                + ", metadataCacheTtl=" + metadataCacheTtl.toSeconds() + "s"
                + ", classifications=" + classifications.ruleCount() + " rule(s), fallback '"
                + classifications.fallbackLabel() + "'"
                + ", secrets=" + secrets + "]";
    }
}
