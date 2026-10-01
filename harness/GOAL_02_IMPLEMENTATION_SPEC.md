# Goal 02 - frozen implementation specification (lead-authored)

This file is the shared contract for every worker on Goal 02. It is **not** a goal; it is the frozen
interface surface the lead owns. Do not change a signature here without the lead's explicit agreement.

Ground truth used to author it (reconnaissance, all verified against real artifacts):

- `harness/IBM_CM87_SDK_API_SURFACE.md` - the full 842-line API-surface report derived from real `javap`
  output against `cmbicmsdk81.jar` (9,562,412 bytes, class-file major version 52, manifest
  `Implementation-Version: 8.7.00.400.44`). Committed here on purpose: the original lived in `%TEMP%`.
- The committed Goal 01A-01C core, read in full.
- `C:\Users\crown\Downloads\Projects\CM_Migrator\src\com\ibm\ecm\migration\{CMConnection,CMConnectionPool}.java`.

## 0a. Two goal-text corrections (verified, apply them)

The approved goal file lists "the following read paths are proven working source material" including
`listEntities(... DK_ICM_ENTITY_TYPE / DK_ICM_BASE ...)`. That spelling is wrong in two ways and must not
be implemented literally:

1. **There is no `listEntities(String)`.** The real overloads on `DKDatastoreDefICM` are `listEntities()`,
   `listEntities(int)` and `listEntities(DKNVPair[])`. `DKConstantICM.DK_ICM_ENTITY_TYPE` is a `String`
   constant (`"ENTITY_TYPE"`) that none of those overloads consumes, and no bytecode in the SDK's
   datastore classes references that literal at all. Use `listEntities(DK_ICM_BASE)`.
2. **`DKDatastoreICM` declares only the no-arg `listEntities()` and `listEntityNames()`.** Every `(int)`
   overload lives on `DKDatastoreDefICM`, so the cast in the chain is load-bearing, not stylistic.

Also: `CM_retention` and `CmService.java` do **not exist** anywhere on this machine (verified by recursive
case-insensitive search over `C:\Users\crown\Downloads\Projects` and `C:\Users\crown`). The goal's
"Mandatory source review" of that repository therefore cannot be performed. Its role is taken by the
committed API-surface report above, whose chains were verified directly against the JAR. The
`CM_retention` *mutating* paths the goal forbids are forbidden by construction - the adapter simply has
no code for them - and by the source-level guard in section 10. `CM_Migrator` was reviewed in full; that
review is summarised in section 11. A third project, `CM_Tabelle`, does **not** compile against this SDK
(wrong package for `DKItemTypeDefICM`, calls `DKDatastoreICM.listEntityNames(short)` and
`getDefinition(...)` which do not exist, and references `DKConstant.DK_CM_ENTITY_ITEMTYPE` which does not
exist either) and must not be used as source material.

## 0. Non-negotiable invariants (a violation is a failed goal, not a bug)

1. No emergency / overflow / ad-hoc / discovery connection, ever, in any mode.
2. `capacityInUse() <= configuredSize()` at every instant, for every pool.
3. An uncertain physical outcome never frees capacity.
4. No repository switch while a previous physical resource is pending or uncertain.
5. No lost partial-activation resource: everything a factory allocated stays visible to `RepositoryManager`.
6. No IBM CM write call: no `commit()`, no `update()`, no add/del/assign/unassign/remove/backfill/migrate.
7. SDK types never escape `com.mraibo.cminsight.ibm.*`.
8. A health probe called while `BoundedPool` holds its lock is cheap, local and never blocks on I/O.
9. No credential value in any log line, exception message, DTO, diagnostic or test output.
10. `./build.sh` with zero IBM JARs must keep compiling, testing and packaging successfully.

## 1. Verified IBM SDK facts (corrects several earlier assumptions)

| Fact | Consequence |
| --- | --- |
| `DKDatastoreICM` is `com.ibm.mm.sdk.**server**.DKDatastoreICM` | not `common`; every import must use `server` |
| `DKSystemException` does not exist; the real class is `com.ibm.mm.sdk.common.DKSystemError` | never stub or catch `DKSystemException` |
| The SDK has **no** `close()` anywhere | teardown is `disconnect()` then `destroy()`; never invent `close()` |
| `datastoreDef()` returns `dkDatastoreDef` (no narrowing) | explicit cast to `DKDatastoreDefICM` required |
| `datastoreAdmin()` returns `dkDatastoreAdmin`, which has no `policyMgmt()` | explicit cast to `DKDatastoreAdminICM` required |
| Retention/expiration/period-unit "constants" are nested Java **enums** | a stub declaring them `int` will not compile |
| `DKItemTypeDefICM.getIntId()` -> `int` | `getEntityId()`/`getItemTypeId()` do not exist; inherited `getId()` is `short` and lossy |
| `DKException extends com.ibm.mm.sdk.logtool.DKLogException` | stubs must also provide `logtool.DKLogException` + `DKLogMessageInserts` |
| `isConnected()` on the concrete class declares no `throws` | still **not** to be called under the pool lock without measurement |

Exact signatures the adapter may use:

```
com.ibm.mm.sdk.server.DKDatastoreICM
    public DKDatastoreICM() throws DKException, java.lang.Exception
    public void connect(java.lang.String, java.lang.String, java.lang.String, java.lang.String) throws DKException, java.lang.Exception
    public void disconnect() throws DKException, java.lang.Exception
    public void destroy() throws DKException, java.lang.Exception
    public boolean isConnected()
    public com.ibm.mm.sdk.common.dkDatastoreDef datastoreDef() throws DKException, java.lang.Exception
    public void commit() throws DKException, java.lang.Exception          // FORBIDDEN - mutating

com.ibm.mm.sdk.common.DKDatastoreDefICM
    public com.ibm.mm.sdk.common.dkDatastoreAdmin datastoreAdmin() throws DKException, java.lang.Exception
    public dkCollection listEntities(int) throws DKException, java.lang.Exception
    public dkCollection listEntities() throws DKException, java.lang.Exception
    public dkEntityDef retrieveEntity(java.lang.String) throws DKException, java.lang.Exception
    public int getEntityIdByName(java.lang.String) throws DKException, java.lang.Exception
    public java.lang.String getEntityNameById(int) throws DKException, java.lang.Exception

com.ibm.mm.sdk.common.DKDatastoreAdminICM
    public com.ibm.mm.sdk.common.DKPolicyMgmtICM policyMgmt() throws DKException, java.lang.Exception

com.ibm.mm.sdk.common.DKPolicyMgmtICM
    public java.lang.String[] listRetentionPolicyNames() throws DKException, java.lang.Exception
    public com.ibm.mm.sdk.common.dkCollection listRetentionPolicies() throws DKException, java.lang.Exception
    public DKRetentionPolicyDefICM retrieveRetentionPolicy(int) throws DKException, java.lang.Exception
    public DKRetentionPolicyDefICM retrieveRetentionPolicy(java.lang.String) throws DKException, java.lang.Exception
    public java.lang.String[] listItemTypeNamesByRetentionPolicy(java.lang.String) throws DKNotExistException, DKException, java.lang.Exception

com.ibm.mm.sdk.common.DKRetentionPolicyDefICM   (all read-only getters, no `short`)
    java.lang.String getName()                int getID()               java.lang.String getDescription()
    <enum> getRetentionType()                 boolean isRetentionEnabled()
    int getRetentionTimePeriod()              <enum> getDefaultRetentionTimeUnit()
    boolean isExpirationEnabled()             int getExpirationTimePeriod()
    <enum> getDefaultExpirationTimeUnit()     <enum> getExpirationAction()
    java.lang.String getDeleteExpiredItemsScheduleInformation()
    int getDeleteExpiredItemsCommitCount()    int getDeleteExpiredItemsMaximumRows()
    int getDeleteExpiredItemsMaximumDuration()  boolean isDeleteExpiredItemsForceCheckInEnabled()

com.ibm.mm.sdk.common.DKItemTypeDefICM
    int getIntId()                            java.lang.String getName()      java.lang.String getDescription()
    short getClassification()                 short getVersionControl()       short getVersioningType()
```

Constants (`DKConstantICM` is an `interface`):

- `DK_ICM_ENTITY_TYPE` = `String` `"ENTITY_TYPE"`; `DK_ICM_BASE` = `int 1`; `DK_ICM_BASE_AND_VIEW` = `int 3`.
- Classification is `short`: `DK_ICM_ITEMTYPE_CLASS_ITEM=0`, `_RESOURCE_ITEM=1`, `_DOC_MODEL=2`, `_DOC_PART=3`.
- Version control is `int` but the getter is `short`: `NEVER=0`, `ALWAYS=1`, `BY_APPLICATION=2`.
- Versioning type is `short`: `DK_ICM_DOC_NO_VERSIONING=0`, `_ITEM_VERSIONING_OPTIMIZED=1`, `_ITEM_VERSIONING_FULL=2`.

**Parameter names and order are absent from bytecode.** `connect(ssid, user, password, "")` is the
proven working call from the reference implementation; do not "correct" it. The **enum-to-numeric-code
mapping is not recoverable from the class files** - therefore the adapter must never guess a numeric
code for a retention type, time unit or expiration action. Map only what is certain and render
everything else as `UNKNOWN(<value>)`.

Read-only members the adapter is allowed to use beyond the table above (all verified present):

```
DKDatastoreICM            public short validateConnection() throws DKException, java.lang.Exception
DKItemTypeDefICM          getItemTypeRetentionPolicyName() / getItemTypeRetentionPolicyId()
                          getXDOClassID() / getXDOClassName() / getJavaXDOClassName()
                          getDefaultRMCode() / getDefaultCollCode()
                          getClassification() / getVersionControl() / getVersioningType()
                          getIntId()          (getName()/getDescription() are inherited)
DKPolicyMgmtICM           listItemTypesByRetentionPolicy(String)
```

`DKDatastoreICM.connection()` returns a `DKHandle` and is **forbidden**: reaching a native JDBC
connection through it is exactly the reflection trick the reference migrator uses and this project bans.

## 2. Core contract changes (lead-owned, wave 1)

### 2.1 `com.mraibo.cminsight.connection.CreationFailure` (new)

A checked exception carrying the vendor-neutral create-failure outcome required by Goal 02 section C.

```java
public class CreationFailure extends Exception {
    public enum Cleanup { PROVEN_CLEAN, UNPROVEN }
    public CreationFailure(Cleanup cleanup, String message) { ... }
    public CreationFailure(Cleanup cleanup, String message, Throwable cause) { ... }
    public Cleanup cleanup() { ... }
    public boolean cleanupProven() { return cleanup() == Cleanup.PROVEN_CLEAN; }
}
```

Semantics, and why the default is what it is:

- `PROVEN_CLEAN` - the factory allocated nothing, or closed everything it allocated and that close
  returned normally. The reserved slot is released; a later borrow may create a replacement.
- `UNPROVEN` - the factory may have allocated a physical resource and could not prove it is gone. The
  reserved slot is **quarantined**: it stays consumed and no replacement is authorised.
- A plain `Exception`/`Error` from `create()` (no `CreationFailure` signal) keeps the historical
  reading: **release**. This is deliberate and load-bearing - four committed Goal 01 tests assert that
  a throwing `create()` returns its slot (`BoundedPoolLifecycleTest:303-326`,
  `BoundedPoolHardeningTest:133-181`, `:748-776`, `BoundedPoolLifecycleTest:42-83`). Weakening the
  historical reading is not this goal's mandate, and the residual risk is recorded in STATUS.md
  instead of being hidden: a third-party factory that leaks and throws a plain exception is invisible
  to the pool. CM Insight's own IBM factory always signals.

### 2.2 `BoundedPool` changes

- `createReserved()`: on a `CreationFailure` with `UNPROVEN`, quarantine the reserved slot instead of
  releasing it. Implement it as `creatingCount--` plus `quarantinedCount++` in ONE lock section, so the
  capacity identity `available + leased + creating + retiring + quarantined == capacityInUse()` holds at
  every instant. A `PROVEN_CLEAN` failure and every non-`CreationFailure` throwable keep the existing
  `releaseCreationSlot()` behaviour.
- `initialize()` rollback: the blanket `creatingCount -= size` must become per-attempt aware. The
  failing attempt's reservation is released only when its failure was `PROVEN_CLEAN` (or untyped);
  otherwise it is quarantined. Reservations for attempts that were never started still just go away.
  The already-created resources keep closing through `closeAllAndFinish` exactly as today.
- Folding the create-side quarantine into the existing `quarantined` counter is **required**, not a
  shortcut: `PoolMetrics.capacityInUse()` is asserted to equal `available + leased + creating +
  retiring + quarantined` and `degraded()` is `quarantined > 0`, so a sixth state would break both.
- `PoolMetrics` gains exactly one counter, `createQuarantineFailures()` (`long`), placed after
  `createFailures()`. The metrics-contract test in `BoundedPoolHardeningTest` pins the exact accessor
  set and **must be updated in the same commit** as a deliberate, documented contract change. Do not
  delete that test; extend its expected set.
- The `ResourceFactory` javadoc obligation ("release before throwing") must be rewritten to match: an
  implementation that cannot prove cleanup reports `CreationFailure(UNPROVEN, ...)` instead. The old
  text is now wrong and must not survive.
- `rotating` / `release` / `close` paths are untouched.

### 2.3 `RepositoryContextFactory` (section D)

```java
@FunctionalInterface
public interface RepositoryContextFactory {
    RepositoryContext create(RepositoryProfile profile) throws Exception;
}
```

stays a functional interface. The cleanup channel is a **thrown exception**:

```java
public class ActivationFailedException extends RepositoryException {
    public ActivationFailedException(String message, Throwable cause) { ... }
    public ActivationFailedException(String message, RepositoryContext cleanup, Throwable cause) { ... }
    public Optional<RepositoryContext> cleanupContext() { ... }
}
```

`RepositoryManager.switchTo` activation-failure path:

- `ActivationFailedException` with a cleanup context: retain it in the same latch the switch path uses
  (rename the concept from `unpublishedClosing` to a retained-context field readable as
  `closingContext()`), close it with the existing `closeContext(...)` helper, then `maybeReleaseLatch(...)`.
  Net effect: `CLOSED_CLEAN` -> activation failed but a later explicit activation may proceed;
  `CLOSING` -> retained, refuses every retry as `PENDING` until terminal-clean; `CLOSED_UNCERTAIN` ->
  retained, refuses every retry permanently as `UNCERTAIN`.
- Any other throwable: unchanged behaviour (nothing to clean).
- `factory.create` is never called again while a retained context is not terminal-clean. The existing
  `resolveUnpublishedClosing` already enforces that and needs no new escape hatch. No force-open path.

## 3. Source sets and build

```
src/main/java        core runtime, IBM-JAR-free, compiled with the IBM JARs absent by construction
src/ibm/java         the optional IBM source set  (com.mraibo.cminsight.ibm.*)
src/main/resources   core resources
src/ibm/resources    IBM source-set resources (META-INF/services/...)
src/test/java        dependency-free tests (core)  -> com.mraibo.cminsight.test
src/ibm-test/java    IBM adapter tests             -> compiled against stubs or real JARs
tests/ibm-stubs      TEST-ONLY compile stubs for com.ibm.* - never packaged, never shipped
```

`build.sh` phases (order matters):

1. `--help`, `--require-ibm` and (new) `--check-ibm-isolation` argument parsing.
2. `src/main/java` -> `build/classes` with `--release 17 -encoding UTF-8 -Xlint:all` and a class path
   that **never contains an IBM JAR or a stub**. This is what makes the isolation structural.
3. Isolation guard: fail if any file under `src/main/java` matches `com\.ibm\.` or `com/mraibo/cminsight/ibm/`.
   Also exposed standalone as `./build.sh --check-ibm-isolation` for the tests to invoke.
4. SDK resolution: `lib/ibm/*.jar` when present, else `tests/ibm-stubs`.
5. `src/ibm/java` -> `build/ibm-classes` against (2) + the SDK set. In `--require-ibm` mode a missing
   real SDK is a hard failure; otherwise a missing SDK compiles against the stubs.
6. Stub contract check: `javap -public` every `com.ibm.*` stub class and compare against a committed
   expectation file (`tests/ibm-stubs/EXPECTED_SIGNATURES.txt`). This is what keeps the stubs honest
   instead of letting them silently drift into an API that does not exist.
7. `src/test/java` -> `build/test-classes` (core, no IBM types on the class path), then
   `src/ibm-test/java` -> `build/ibm-test-classes` when the IBM source set compiled.
8. resources: `src/main/resources` -> `build/classes`; `src/ibm/resources` -> `build/ibm-classes`.
9. SelfTest on the core class path only, then the IBM suites on `build/test-classes +
   build/ibm-test-classes + build/classes + build/ibm-classes + SDK set`.
10. Read-only source guard over `src/ibm/java` (forbidden mutating calls + forbidden `com.ibm` leaks).
11. Package the single jar from `build/classes` **plus** `build/ibm-classes` when the latter exists.

`--require-ibm` fails clearly when the operator demands IBM support but `lib/ibm/*.jar` is absent or
incomplete. IBM JARs are never downloaded and never committed (`.gitignore` already has `*.jar`).

## 4. Provider boundary (section B)

```java
package com.mraibo.cminsight.ibm;              // core, IBM-JAR-free

public interface CmAdapterProvider {
    String providerId();          // stable id, e.g. "ibm-cm-8.7"
    String adapterVersion();      // adapter build version, never a credential
    String sdkRelease();          // e.g. "8.7.0.000"; "" when unknown, never a credential
    CmSessionFactory sessionFactory(RepositoryProfile profile, CmAdapterSettings settings);
}
```

Discovery: `java.util.ServiceLoader`. `IbmCmAdapterRegistry.discover()` (core) returns a status of
`ABSENT` (zero providers), `AVAILABLE` (exactly one), or `AMBIGUOUS` (two or more -> explicit
failure, never a silent pick). ServiceLoader configuration errors and unloadable provider classes are
reported as `UNAVAILABLE` with a generic reason - never a raw `ClassNotFoundException` stack trace and
never a credential. Diagnostics expose availability, provider id, adapter version and SDK release only.

`repository.auto.activate` set while the provider is `ABSENT`/`AMBIGUOUS`/`UNAVAILABLE` -> startup
FAILS. Never publish an empty placeholder context. Repository profiles are still listed without an
adapter; only selection/activation fails clearly.

`CmAdapterSettings` is read from `AppConfig` by the core:

```
cm.pool.size                 default 4,   1..64
cm.pool.borrow.timeout.ms    default 5000, 1..600000
cm.pool.max.age.minutes      default 30,   1..1440
cm.pool.max.operations       default 1000, 1..1000000
cache.metadata.ttl.seconds   default 600,  0..86400
```

## 5. IBM adapter internals (`src/ibm/java`)

```java
com.mraibo.cminsight.ibm.internal.IbmCmAdapterProvider   implements CmAdapterProvider
com.mraibo.cminsight.ibm.internal.IbmCmSession           implements CmSession   (wraps DKDatastoreICM)
com.mraibo.cminsight.ibm.internal.IbmCmSessionFactory    implements CmSessionFactory, ResourceFactory<CmSession>
com.mraibo.cminsight.ibm.internal.IbmCmApi              // the ONE class that touches com.ibm.* API calls
com.mraibo.cminsight.ibm.internal.CmMetadataService      implements MetadataRepository
com.mraibo.cminsight.ibm.internal.CmRetentionService     implements RetentionRepository
com.mraibo.cminsight.ibm.internal.IbmErrorSanitizer      // SDK failure -> value-free text
com.mraibo.cminsight.ibm.internal.IbmEnumNames           // int/enum -> name, UNKNOWN(<value>) otherwise
```

`IbmCmSessionFactory.create()`:

1. allocate `new DKDatastoreICM()`;
2. `connect(ssid, user, password, "")` with a non-blank SSID check first;
3. on any failure attempt `disconnect()` then `destroy()`, catching everything;
4. cleanup returned normally for every step -> `CreationFailure(PROVEN_CLEAN, ...)`;
   anything about cleanup that did not provably succeed -> `CreationFailure(UNPROVEN, ...)`.
   Never log the user or the password; sanitise the SDK message.

`IbmCmSession.close()` is idempotent: `disconnect()` when `isConnected()`, then `destroy()` always,
attempting every step and **not swallowing failures**. If any step's outcome is unproven the session
must throw, so `BoundedPool` quarantines the slot. Do not null the datastore before the physical
outcome is accounted for.

`IbmCmSession.isHealthy()` - and therefore `IbmCmSessionFactory.isHealthy(session)` - is a **cheap
local read of a volatile flag** only. It must not call `isConnected()`, must not touch the SDK and must
not do I/O, because `BoundedPool` calls it while holding its lock. Stronger validation, when wanted,
happens **after** the borrow and outside the lock and marks the session unusable so the lease return
retires it. This is the Goal 01C risk-1 obligation and it is not negotiable.

## 6. Read services and DTOs

```java
package com.mraibo.cminsight.core;             // core, IBM-JAR-free

public record CmPoolSettings(int size, Duration borrowTimeout, Duration maxAge, long maxOperations) {}
public record CmPoolSnapshot(...)              // see below

public interface CmSessionFactory { CmSession open(RepositoryProfile profile) throws Exception; }

public interface MetadataRepository {
    List<ItemTypeSummary> listItemTypes();
    ItemTypeInfo itemType(String name);
    boolean available();
}

public interface RetentionRepository {
    List<String> listPolicyNames();
    List<RetentionPolicyInfo> listPolicies();
    RetentionPolicyInfo policy(String name);
    List<String> itemTypeNamesForPolicy(String policyName);
    boolean available();
}
```

`MetadataRepository`/`RetentionRepository` are **types, not a `Map<String,Object>` locator**. They are
exposed from `RepositoryContext` through explicit typed accessors, so Goal 03 can add a statistics
service without redesigning anything:

```java
public Optional<MetadataRepository> metadata();
public Optional<RetentionRepository> retention();
public Optional<CmPoolDiagnostics> cmPool();
```

`RepositoryContext` keeps its existing `closeState()`/closeSemantics untouched. Constructing a context
without a metadata/retention service must stay possible (the Goal 01 tests build bare contexts), so the
accessors return `Optional.empty()` rather than throwing.

### 6.1 DTO extensions (all immutable records, all SDK-free)

`ItemTypeInfo` gains: `xdoClassId` (`String`), `xdoClassName` (`String`), `businessClassification`
(`String`), `defaultRm` (`String`), `collectionCode` (`String`), `legacyRetentionSummary` (`String`),
`rawClassification` (`int`), `versionControlCode` (`int`), `versioningTypeCode` (`int`).
Keep the existing component names and order-stable accessors; `classification` keeps its meaning as the
raw IBM classification **name**, and `businessClassification` is the CM Insight label from
`ClassificationRules`. Never reintroduce hard-coded SAP/NON-SAP rules.

`RetentionPolicyInfo` gains: `retentionTypeCode` (`int`), `retentionUnit` (`String`),
`expirationUnit` (`String`), `expirationActionCode` (`int`), `autoDeleteSchedule` (`String`),
`commitCount` (`int`), `maxRows` (`int`), `maxDuration` (`int`), `forceCheckIn` (`boolean`).
`assignedItemTypes` keeps its meaning. Lists are sorted case-insensitively by name.

Unknown numeric values render as `UNKNOWN(<value>)` - never guessed, never blank.

## 7. Metadata cache (section I)

One `MetadataCache` instance per `RepositoryContext`; it dies with the context and is never shared
across profiles. Requirements:

- TTL from `cache.metadata.ttl.seconds`;
- at most one refresh per key at a time (a second caller waits for or observes the in-flight refresh,
  it never launches a duplicate);
- a failed refresh never replaces a known-good snapshot with partial data;
- exposes the snapshot, its age and whether it is fresh;
- no persistent history, no document or user content (that is Goal 04).

## 8. Web/API (section K)

`/api/health` stays the only public route. `Router` refuses every other unauthenticated registration.

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/repositories` | profiles plus adapter availability |
| POST | `/api/repositories/select` | activation; requires the guard header |
| GET | `/api/repositories/status` | lifecycle, `CloseState`, refusal, retained context |
| GET | `/api/itemtypes` | requires an active repository |
| GET | `/api/itemtypes/{name}` | |
| GET | `/api/retention/policies` | |
| GET | `/api/retention/policies/{name}` | includes assigned ItemTypes |
| GET | `/api/diagnostics/cm` | adapter, pool, cache, last sanitised error |

The repository action guard: repository selection mutates local runtime state, so it must be POST with
a request header a cross-site HTML form cannot set. Use `X-CM-Insight-Action: repository-select`
(name fixed here so the endpoint, the test and the UI cannot drift). A missing or wrong value is
refused with `403` and a clean JSON body **before** any state change. CORS stays disabled.

Error mapping, each a distinct clean JSON code with no IBM text and no stack trace:

| Condition | Status | code |
| --- | --- | --- |
| no active repository | 409 | `no_active_repository` |
| adapter unavailable | 503 | `adapter_unavailable` |
| adapter ambiguous | 503 | `adapter_ambiguous` |
| unknown repository | 404 | `unknown_repository` |
| pending previous close | 409 | `repository_pending` |
| terminal uncertain previous close | 409 | `repository_uncertain` |
| missing/wrong action header | 403 | `action_forbidden` |
| sanitised IBM failure | 502 | `cm_unavailable` |

## 9. Diagnostics and smoke CLI (section L)

`CmPoolDiagnostics` exposes, value-free: configured size, capacity in use, available, leased, creating,
retiring, quarantined, create failures, create-failure quarantines, close attempts/successes/failures,
pool `CloseState`, context `CloseState`, adapter availability, provider id, sdk release, cache age, and
the last sanitised adapter error. Never a credential, never an SDK message verbatim.

```
./bin/cm-insight --check-repository <repository-id>
```

MUST walk the production path: provider -> `RepositoryManager` -> `RepositoryContextFactory` ->
`RepositoryContext` -> `BoundedPool`. Never a one-off SDK connection. It activates, reports the CM API
release, the ItemType count and the retention policy count, closes through `RepositoryManager`, and
exits non-zero when the final shutdown is not terminal-clean. Exit codes: `0` clean, `1` activation or
shutdown failure, `2` usage, `3` configuration error, `4` adapter/SDK unavailable.

## 10. Read-only guard (section H, hard requirement)

A committed source-level guard over `src/ibm/java` must fail on any of the known mutating calls:
`commit(`, `rollback(`, `checkIn(`, `checkOut(`, `update(`, `add(`/`addItemType`/`addRetentionPolicy`,
`del`/`delete`/`remove`, `assign`/`unassign`, `backfill`, `migrate`, `moveObject`, `changePassword`,
`makeActive`/`makeInactive`, `reorg`/`rebuild`/`recreate`, and every `set*` call on a
`DKItemTypeDefICM`/`DKRetentionPolicyDefICM`/`DKPolicyMgmtICM` receiver. It is enforced in two places:
as a test (`ReadOnlySourceGuardTest`) and as a build step, so it cannot be skipped by forgetting a test.

A second guard enforces section A: no `com.ibm.` reference and no `com.mraibo.cminsight.ibm.` import
under `src/main/java`.

## 11. Forbidden reuse from the reference repositories

From `CM_Migrator`: emergency connections, out-of-pool/discovery connections, dual source/destination
pools, global pool singletons, swallowed cleanup failures, native JDBC extraction, migration behaviour.
Reusable: `DKDatastoreICM` lifecycle order, cleanup discipline, age/usage/staleness concepts.

From `CM_retention`: every mutating assign/unassign/create/delete/update/commit/backfill path. Reusable:
the proven read chain listed in section 1 and `CM_Itemtype_java`-style classification reuse.

## 12. Ownership map (one writer per file; no two workers edit the same file)

| Area | Owner | Files |
| --- | --- | --- |
| Core contracts + pool + manager | lead | `connection/CreationFailure.java`, `connection/BoundedPool.java`, `connection/ResourceFactory.java`, `connection/PoolMetrics.java`, `repository/RepositoryContextFactory.java`, `repository/ActivationFailedException.java`, `repository/RepositoryManager.java` |
| Core docs/records | lead | `metadata/ItemTypeInfo.java`, `retention/RetentionPolicyInfo.java`, `repository/RepositoryContext.java`, `core/CmSessionFactory.java`, `core/CmPoolSettings.java` |
| Provider seam + wiring + CLI | foundation worker | `ibm/CmAdapterProvider.java`, `ibm/CmAdapterSettings.java`, `ibm/IbmCmAdapterRegistry.java`, `repository/ProductionRepositoryContextFactory.java`, `app/Main.java`, `app/ConfigCheck.java`, `config/RepositoryProfile.java` |
| Web/API | web worker | `web/CmApiRoutes.java` (new) + a minimal, additive hook in `web/WebServer.java` |
| IBM stubs | ibm-stubs worker | `tests/ibm-stubs/**` |
| IBM adapter | ibm-adapter worker | `src/ibm/**` |
| Tests | test worker (new files only) | `src/test/java/**` new suites, `src/ibm-test/java/**`, `tests/shell/**` |
| Documentation | docs worker | `README.md`, `ARCHITECTURE.md`, `SECURITY.md`, `lib/README.md`, `conf/*.example` |

`src/test/java/com/mraibo/cminsight/test/SelfTest.java` is edited **only by the lead**, at integration,
to register the new suites. Workers must not touch it; report the class names instead.
