# Architecture

## Style

CM Insight is a modular monolith in one JVM.

```text
Web UI / HTTP API
        |
Application services
        |
+----------------------+----------------------+
| IBM CM API services  | Fast JDBC analytics |
+----------------------+----------------------+
        |                         |
Bounded CmSessionPool       Bounded JdbcPool
        |                         |
 DKDatastoreICM           DB2 / Oracle JDBC
```

Packages are feature boundaries, not separate deployables.

## Non-negotiable rules

1. No Maven, Gradle, Spring, containers, microservices or CDN dependency.
2. Java 17 baseline unless a verified IBM CM compatibility issue forces reconsideration.
3. IBM CM SDK types stay inside IBM adapter packages; business DTOs never expose SDK classes.
4. Database-specific SQL stays behind dialect/repository classes.
5. Web code never directly uses DKDatastoreICM or java.sql.Connection.
6. Repository operations are read-only in V1/V2.
7. Pools are hard bounded. Never create emergency connections when exhausted.
8. Every borrowed resource is returned with AutoCloseable lease semantics.
9. Cache data, never live SDK/JDBC objects.
10. Switching repository closes the previous RepositoryContext first.
11. DeepSeek may implement inside these boundaries but must not silently change them.

## Connection design

CM_Migrator provides useful ideas: BlockingQueue pools, borrow-wait metrics, reconnect/refill, stale rotation, identity validation and graceful shutdown.

CM Insight intentionally changes migration-specific behavior:

- no emergency connections outside configured capacity
- no discovery connections outside the pool
- lease/pool updates operation counters automatically
- one CM pool per active repository, not source/destination pools

Target usage:

```java
try (Lease<CmSession> lease = cmPool.borrow()) {
    CmSession session = lease.value();
}
```

Pool metrics: configured size, available, leased, borrow count, average/max wait, sessions created/closed, reconnect attempts/success/failures, stale rotations and validation failures.

### Capacity accounting

Every capacity slot is accounted for under a single lock as exactly one of five states: idle, leased, being created, retiring, or quarantined. A borrow either takes an idle resource, or, when that sum is below the configured size, reserves a slot and creates one. Because that sum is the only thing that authorises a creation, the bound cannot be exceeded under any interleaving, no capacity is overshot during a refill and no emergency or overflow resource is ever created. Refill is lazy rather than eager: retiring a resource frees its slot and the next borrow creates the replacement, so there are no refill worker threads to explode and no refill race to lose.

### An uncertain close never frees physical capacity

A resource whose `close()` throws has an **unknown** physical outcome: the exception does not prove that the underlying CM session or JDBC connection is gone. Such a slot moves to **quarantine** and stays consumed, so no replacement can be created while the old resource may still exist. This deliberately degrades the pool instead of risking a breach of the configured physical hard bound - safety outranks availability. Only a `close()` that returned normally frees a slot.

`PoolMetrics` keeps the two outcomes distinct and visible: `quarantined`, `degraded`, and the separate `closeAttempts` / `closeSuccesses` / `closeFailures` counters. Initial population and replacement creation are counted separately (`initialCreations`, `replacementCreations`) and there is deliberately **no** "reconnect" counter, because only the CM/JDBC adapter can know whether a creation was a reconnect.

A creation that is still in flight when `close()` begins is never handed out: it is retired through the same path as any other retirement, so its slot is freed or quarantined exactly once.

### Usage rotation cannot be forgotten

Every completed borrow/use/close cycle counts as one usage automatically, so a caller that only uses try-with-resources still advances the operation budget. Explicit counting through the lease adds on top, and `automaticUsages` / `explicitOperations` are reported separately.

### Strictness versus promptness

A retiring resource keeps its slot until its `close()` has returned. A slow close therefore delays capacity instead of letting a replacement open while the old resource is still alive: the hard bound takes precedence over promptness.

There is exactly one capacity path. Nothing in the codebase may bypass the pool to open an ad-hoc or emergency connection.

## RepositoryContext

```text
RepositoryContext
├── RepositoryProfile
├── CmSessionPool              (the IBM adapter's hard-bounded session pool)
├── JdbcConnectionPool         (target shape - later analytics goal)
├── MetadataRepository         (adapter-provided, read-only, IBM-JAR-free interface)
├── StatisticsRepository       (target shape - later analytics goal)
├── RetentionRepository        (adapter-provided, read-only, IBM-JAR-free interface)
└── caches                     (metadata cache, per context)
```

Modules borrow resources from this context and do not create ad-hoc connections.

Goal 02 implements the CM half of that tree. The named CM pool is real and hard bounded, and the
metadata and retention repositories exist as **types**, not as a `Map<String,Object>` locator:
`RepositoryContext` exposes them through explicit typed accessors (`metadata()`, `retention()`,
`cmPool()`), each returning an `Optional`, so a context can still be built without them and Goal 03 can
add a statistics service without redesigning anything. The JDBC pool and the statistics repository are
the remaining target shape and do not exist yet.

### The IBM source set and the provider boundary

The adapter is a **compile-time-optional source set**, not a second deployable:

```text
src/main/java       core runtime - no com.ibm.* type is reachable, by construction
src/ibm/java        the adapter  (com.mraibo.cminsight.ibm.*)  -> build/ibm-classes
src/ibm/resources   META-INF/services/com.mraibo.cminsight.ibm.CmAdapterProvider
tests/ibm-stubs     signature-only com.ibm.* stubs, test-only and never packaged
```

`src/main/java` is compiled with no IBM JAR and no stub on its class path, so a core source that needs
an SDK type does not compile rather than drifting across the boundary. The adapter is discovered at
runtime through `java.util.ServiceLoader` against the core-owned `CmAdapterProvider` interface:
`IbmCmAdapterRegistry.discover()` reports exactly one of `ABSENT`, `AVAILABLE`, `AMBIGUOUS` or
`UNAVAILABLE`, and two or more providers is an explicit failure rather than a silent pick. Both the
source set and the registration are packaged into the single jar, so the adapter is optional at
compile time and at runtime - not a separate process and not a plugin directory.

The adapter knows only the vendor-neutral core contracts (`CmSessionFactory`, `MetadataRepository`,
`RetentionRepository`, `RepositoryContextFactory`, `CmPoolDiagnostics`). Nothing else in the tree may
name an IBM type: rule 3 above is enforced twice, by a source guard and by the compiler.

### Per-context metadata cache

One `MetadataCache` is created with a `RepositoryContext`, published with it and released with it; it
is never shared between repositories and holds only immutable DTOs. A snapshot is fresh for
`cache.metadata.ttl.seconds` (that TTL's `0` disables caching), at most one refresh per key runs at a
time so a burst of requests cannot become a burst of CM sessions, and a failed refresh never replaces
a known-good snapshot with partial data - the previous snapshot keeps being served with a growing age
and a recorded reason. Every load happens outside the CM session pool's lock, so a refresh can never
stall a borrow.

### The read-only guarantee is structural

V1/V2 rule 6 above is enforced two ways, and the first one is stronger than a text scan: the compile
stubs under `tests/ibm-stubs` declare **only** the read-only getters on `DKItemTypeDefICM`,
`DKRetentionPolicyDefICM` and `DKPolicyMgmtICM`, so a call such as `policy.add(...)`,
`itemType.update()` or `itemType.setName(...)` does not compile against the IBM source set at all. The
stub surface is pinned against `tests/ibm-stubs/EXPECTED_SIGNATURES.txt`, so removing that protection
fails the build rather than passing quietly. On top of it, a committed source guard
(`tests/shell/ibm_guard.sh`, run by `build.sh` and by `ibm_source_guard_test.sh`) refuses the
unambiguously IBM-specific mutating calls - `commit(`, `rollback(`, `checkIn(`, `checkOut(`,
`backfill(`, `migrate(`, `moveObject(`, `changePassword(`, `makeActive(`, `makeInactive(`, `reorg(`,
`recreate(`, `clearCache(`, `assign(` and `unassign(` - plus native JDBC extraction through
`connection()`, and a second guard refuses any `com.ibm.` reference under `src/main/java`. Names that
are also ordinary JDK methods (`add`, `remove`, `update`, `set*`) are deliberately not text-matched,
because a guard that refuses the adapter's own `List`/`Map`/`AtomicBoolean` bookkeeping cannot pass and
would end up disabled; those members are excluded one layer earlier by the stubs' omission, which is
the stronger guarantee. The retention *administration* module stays disabled by default and cannot be
enabled.

## Statistics

Metadata comes from IBM CM Java API. High-volume counts use JDBC.

Root physical table mapping starts from the working CM_retention approach using ICMSTCOMPDEFS + ICMSTITEMTYPEDEFS and COMPONENTTYPEID / SEGMENTID, then caches the result.

One aggregate query should calculate total + today + 7d + 30d + current year where practical. DB2/Oracle syntax differences live in DatabaseDialect.

## Versions and Parts

Required product metrics, but exact counting semantics are intentionally unresolved at bootstrap. DeepSeek must not invent SQL.

Before enabling either metric, DATA_MODEL.md must contain a verified definition, DB2 query, Oracle query and comparison against a trusted CM result.

Until then values are UNAVAILABLE rather than guessed.

## Retention

Read-only retention viewer is V1. CM_retention contains proven API usage for DKDatastoreAdminICM, DKDatastoreDefICM, DKItemTypeDefICM, DKPolicyMgmtICM and DKRetentionPolicyDefICM.

Adapt that logic to RepositoryContext. Do not launch CM_retention as a subprocess.

Retention administration is a later feature and disabled by default.

## Feature modules

`feature.dashboard`, `feature.itemtypes`, `feature.statistics`, `feature.retention.viewer`, `feature.history`, `feature.reports`, `feature.system.diagnostics`, `feature.item.lookup` (initially off), `feature.retention.admin` (off, and it cannot be enabled).

Dynamic third-party JAR plugins are not required. Modularity is package/service based.

## Caching

- L1 metadata cache
- L2 current statistics cache
- L3 persistent historical snapshots

Web UI displays cached data immediately while a refresh runs.

## Web

Start with JDK embedded HttpServer. Routing stays separate from application services.

Only /api/health may be unauthenticated and it must expose minimal data. Repository metadata, statistics, diagnostics and report actions require authentication.

All static assets are local.
