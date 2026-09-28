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

## RepositoryContext

```text
RepositoryContext
├── RepositoryProfile
├── CmSessionPool
├── JdbcConnectionPool
├── MetadataRepository
├── StatisticsRepository
├── RetentionRepository
└── caches
```

Modules borrow resources from this context and do not create ad-hoc connections.

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

dashboard, itemtypes, statistics, retention-viewer, history, reports, system-diagnostics, item-lookup (initially off), retention-admin (off).

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
