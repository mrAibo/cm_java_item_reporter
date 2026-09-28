package com.mraibo.cminsight.ibm;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.core.CmPoolSettings;

import java.time.Duration;
import java.util.Objects;

/**
 * Everything the core hands to a CM adapter: the already-validated pool bounds, the metadata-cache
 * time-to-live, and the one object allowed to turn a credential reference into a credential value.
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
 * <p>This record carries no credential: it holds a resolver and bounds. Its {@link #toString()} shows the
 * bounds and the secrets directory only.
 *
 * @param pool              the validated pool bounds
 * @param metadataCacheTtl  how long a metadata snapshot stays fresh; {@link Duration#ZERO} disables caching
 * @param secrets           the resolver that reads a credential value, never a value itself
 */
public record CmAdapterSettings(CmPoolSettings pool, Duration metadataCacheTtl, SecretResolver secrets) {

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

    public CmAdapterSettings {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(metadataCacheTtl, "metadataCacheTtl");
        if (metadataCacheTtl.isNegative()) {
            throw new IllegalArgumentException(METADATA_TTL_KEY + " must not be negative");
        }
        Objects.requireNonNull(secrets, "secrets");
    }

    /**
     * Reads the adapter settings from configuration, failing closed on every out-of-range value.
     *
     * <p>Uses the same strict integer readers as the rest of the runtime, so a typo such as
     * {@code cm.pool.size=0} or a value above the documented maximum is a configuration error naming the
     * key, never a silently clamped number.
     *
     * @throws com.mraibo.cminsight.config.ConfigException when a value is not an integer or is out of range
     */
    public static CmAdapterSettings from(AppConfig config, SecretResolver secrets) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(secrets, "secrets");

        int size = config.getInt(POOL_SIZE_KEY, DEFAULT_POOL_SIZE, 1, 64);
        int borrowTimeoutMs = config.getInt(BORROW_TIMEOUT_KEY, DEFAULT_BORROW_TIMEOUT_MS, 1, 600_000);
        int maxAgeMinutes = config.getInt(MAX_AGE_KEY, DEFAULT_MAX_AGE_MINUTES, 1, 1_440);
        long maxOperations = config.getLong(MAX_OPERATIONS_KEY, DEFAULT_MAX_OPERATIONS, 1L, 1_000_000L);
        int metadataTtlSeconds = config.getInt(METADATA_TTL_KEY, DEFAULT_METADATA_TTL_SECONDS, 0, 86_400);

        return new CmAdapterSettings(
                new CmPoolSettings(size, Duration.ofMillis(borrowTimeoutMs), Duration.ofMinutes(maxAgeMinutes),
                        maxOperations),
                Duration.ofSeconds(metadataTtlSeconds),
                secrets);
    }

    /** The documented defaults, for a caller that has no configuration to read. */
    public static CmAdapterSettings defaults(SecretResolver secrets) {
        return new CmAdapterSettings(CmPoolSettings.defaults(),
                Duration.ofSeconds(DEFAULT_METADATA_TTL_SECONDS), secrets);
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
                + ", secrets=" + secrets + "]";
    }
}
