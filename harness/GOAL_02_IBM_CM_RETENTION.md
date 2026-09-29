# Goal 02 - IBM CM adapter, ItemTypes and read-only retention viewer

**Status: COMPLETED / ARCHITECTURE REVIEWED — CHANGES REQUIRED. DO NOT RE-EXECUTE.**

Goal 01 / 01A / 01B / 01C are accepted core-runtime foundations. This is the first real IBM Content
Manager 8.7 integration.

V1/V2 remain READ-ONLY with respect to IBM CM and the Library Server.

Do not implement JDBC analytics, Versions/Parts SQL, retention administration, content viewing,
migration, delete, update or backfill operations.

## Objective

Implement the production IBM CM Java API adapter so CM Insight can:

- list configured repositories;
- activate one repository at a time through a hard-bounded CM session pool;
- list ItemTypes and read-only properties;
- list/read retention policies and ItemType assignments;
- expose authenticated APIs and diagnostics;
- stay buildable/testable in CI with zero proprietary IBM JARs;
- preserve every Goal 01A-01C hard-bound and fail-closed lifecycle invariant.

## Mandatory source review

Inspect before coding:

### mrAibo/CM_Migrator
- src/com/ibm/ecm/migration/CMConnection.java
- src/com/ibm/ecm/migration/CMConnectionPool.java

Reuse only compatible ideas: DKDatastoreICM lifecycle, cleanup discipline, stale/age/usage concepts.
Do NOT copy emergency connections, dual source/destination pools, global pool singletons, ad-hoc
connections, native JDBC extraction or swallowed cleanup failures.

### mrAibo/CM_retention
- src/CmService.java
- relevant configuration/helpers

The following read paths are proven working source material:

- new DKDatastoreICM()
- connect(ssid,user,password,"")
- (DKDatastoreDefICM) datastore.datastoreDef()
- listEntities(... DK_ICM_ENTITY_TYPE / DK_ICM_BASE ...)
- retrieveEntity(name)
- (DKDatastoreAdminICM) datastoreDef.datastoreAdmin()
- admin.policyMgmt()
- listRetentionPolicyNames()
- listRetentionPolicies()
- retrieveRetentionPolicy(name)
- listItemTypeNamesByRetentionPolicy(name)
- the ItemType property getters used by CM_retention.

CM_retention's mutating assign/unassign/create/delete/update/commit/backfill paths are forbidden.

## Subagent tracks

When AgentTeams is available use independent tracks for:
1. IBM SDK/build isolation;
2. connection/pool lifecycle and uncertain create/close;
3. ItemType/retention mapping;
4. Web/API/security;
5. adversarial testing/review.

The lead agent owns integration.

# A. Optional IBM source set - BLOCKING

IBM SDK types must not enter src/main/java business/core code.

Create a clearly separated optional source set such as:

```
src/ibm/java/...
src/ibm/resources/...
```

Core CI with no `lib/ibm/*.jar` must still compile, test and package successfully. Runtime must report
"IBM adapter unavailable" rather than fail with ClassNotFoundException. Repository profiles may still
be listed. Explicit selection/auto-activation must fail clearly when the adapter is unavailable.

If `repository.auto.activate` is configured while no IBM provider is available, startup must fail;
never publish an empty placeholder RepositoryContext.

When local IBM JARs exist:
- compile the IBM source set with Java 17 against `lib/ibm/*.jar`;
- package its classes/resources for the same single JVM;
- fail clearly when the operator explicitly requires IBM support but the SDK set is missing/incomplete.

Add a documented strict mode such as `./build.sh --require-ibm` or equivalent.
Never download or commit IBM JARs.

## A1. Test-only SDK compile surface

The IBM adapter must not remain syntactically untested merely because GitHub CI has no proprietary SDK.

Add minimal test-only IBM API stubs under a non-production path such as `tests/ibm-stubs/`.
They must contain only signatures the adapter actually uses, derived from the proven working source.
They are never packaged into the application.

CI must compile `src/ibm/java` against these stubs. A compile against real SDK JARs, when available,
is authoritative over the stubs.

# B. Provider boundary - BLOCKING

Introduce a small IBM-independent provider/factory seam and load the optional IBM implementation via
ServiceLoader or an equivalent single JDK-only provider boundary.

Required:
- zero provider: core-only mode, unless activation requested;
- exactly one compatible provider: use it;
- multiple/ambiguous providers: explicit failure;
- provider diagnostics reveal availability/version but never credentials;
- SDK types never escape adapter packages.

Do not scatter reflective com.ibm class lookups through the application.

# C. Creation failures must preserve the physical bound - BLOCKING

A real ResourceFactory.create() may allocate/connect a physical resource and then fail cleanup. The
current generic rule "throw => release the reservation" is unsafe in that case.

Add a vendor-neutral create-failure outcome:
- failure + cleanup proven clean => release the reserved slot;
- failure + cleanup unproven => quarantine the reserved slot and do NOT authorize replacement.

The exact API shape is yours, but it must work for eager initialize() and lazy replacement creation,
preserve the original failure safely, expose honest metrics, and maintain
`capacityInUse() <= configuredSize`.

Add deterministic fake-factory tests, including initialize() failing part-way through several slots.

# D. Partial RepositoryContextFactory activation - BLOCKING

A production factory may allocate a CM pool/resource and then fail before returning a context. The
manager cannot clean what it never received.

Add a generic failure path that can carry a cleanup RepositoryContext (or equivalent close-state-aware
managed resource) back to RepositoryManager.

Required:
- terminal-clean cleanup: activation fails but a later explicit activation may proceed;
- pending cleanup: retained context blocks retries until terminal-clean;
- uncertain cleanup: retained context blocks retries permanently in-process;
- factory is never called again while retained physical resources remain;
- no emergency reset/force-open path.

Add deterministic tests.

# E. IBM session lifecycle - BLOCKING

Implement an adapter-local wrapper around DKDatastoreICM.

## Connect

Use the selected RepositoryProfile and resolve only the CM credentials required by this goal. Do not
require JDBC credentials merely to use ItemType/Retention functions.

Use:

```java
DKDatastoreICM ds = new DKDatastoreICM();
ds.connect(ssid, user, password, "");
```

Requirements:
- nonblank SSID;
- SecretRef/SecretResolver only;
- never log user/password values;
- sanitize IBM failures;
- if connect fails after allocation, attempt cleanup;
- if cleanup is unproven, use section C's uncertain-create signal.

## Close

Idempotent. Attempt all cleanup steps: disconnect when appropriate, then destroy always.
Do not swallow failures. If cleanup is unproven, close must fail so BoundedPool quarantines the slot.
Do not forget/null the datastore before the physical outcome is accounted for.

## Pool health

Goal 01C requires `ResourceFactory.isHealthy()` and `CloseStateAware.closeState()` to be cheap and
non-blocking.

The pool health callback MUST NOT perform a network round trip or potentially blocking CM operation
while BoundedPool holds its lock. Use local/session state only.

If stronger SDK validation is needed, perform it after borrow and outside the pool lock. On failure mark
the session unusable so Lease return retires it. Retry only within an explicit bounded policy. Never
open an emergency/out-of-pool session.

Do not assume DKDatastoreICM.isConnected() is safe under the pool lock unless measured/documented as a
cheap local operation.

## CmSessionPool

Compose the existing BoundedPool with:
- cm.pool.size
- cm.pool.borrow.timeout.ms
- cm.pool.max.age.minutes
- cm.pool.max.operations

No ad-hoc DKDatastoreICM, no discovery connection, no global pool singleton.
The pool is owned by RepositoryContext and participates automatically in CloseState switching rules.

# F. IBM-independent repository services

Create typed core interfaces for at least:
- MetadataRepository
- RetentionRepository

RepositoryContext exposes typed read services without SDK objects. Avoid raw Map<String,Object> service
locators. Future JDBC/statistics services must be addable without redesigning the CM adapter.

# G. ItemType metadata - READ ONLY

Adapt CM_retention's proven listEntities/retrieveEntity path. Map SDK objects immediately to immutable
IBM-independent DTOs.

Expose at minimum:
- name, description, integer id;
- raw IBM classification;
- configured CM Insight business classification;
- XDO class id/name where available;
- default RM and collection code;
- version control;
- versioning type;
- legacy/default retention summary where meaningful;
- assigned retention policy name.

Unknown numeric enum values must remain visible as UNKNOWN(<value>) rather than guessed.
Sort case-insensitively by name.

Use ClassificationRules; never reintroduce hard-coded SAP/NON-SAP rules.

# H. Retention viewer - READ ONLY

Adapt only read paths:
- policyMgmt()
- listRetentionPolicyNames()
- listRetentionPolicies()
- retrieveRetentionPolicy(name)
- listItemTypeNamesByRetentionPolicy(name)
- ItemType -> policy name.

Immutable DTOs only.

At minimum policy details:
- name;
- retention type;
- retention enabled + period/unit;
- expiration enabled + period/unit;
- expiration action;
- assigned ItemTypes.

Where the proven API supplies them, include auto-delete schedule, commit count, max rows/items,
max duration and force-check-in flag.

## Hard write guard

The IBM adapter must not call server-mutating methods such as:
- datastore.commit()
- itemType.update()
- retention policy add/del
- setters that assign/remove policies
- delete/backfill/migration APIs.

Add a committed source-level read-only guard over the IBM source set for the known mutating calls from
CM_retention. No write-shaped core interfaces or HTTP routes.

# I. Small per-context metadata cache

Use `cache.metadata.ttl.seconds` for ItemTypes and retention metadata.

Requirements:
- cache belongs to one RepositoryContext and dies with it;
- never share snapshots across profiles;
- one refresh per snapshot/key at a time;
- failed refresh does not replace a known-good snapshot with partial data;
- expose freshness/data age;
- no persistent history yet;
- no document/user content stored.

Do not pre-implement Goal 04's persistent history system.

# J. Production repository wiring

Replace the Goal 01 placeholder `new RepositoryManager(RepositoryContext::new)` with the provider-backed
production factory.

Activation:
1. select profile;
2. resolve CM secrets only;
3. create CM pool;
4. initialize it;
5. create metadata/retention services/cache;
6. return a fully initialized context;
7. on any failure use sections C/D so allocated resources remain visible to the manager.

No context is published until initialization succeeds.

# K. Authenticated API

Keep /api/health as the only public route.

Add authenticated routes for:
- repository list + adapter availability;
- repository select/activate;
- repository lifecycle/status;
- ItemType list/detail;
- retention policy list/detail/assignments;
- CM/pool/system diagnostics.

Repository selection changes local runtime state. It must be POST (or equivalent non-GET action), with an
application-specific request header/token that a cross-site HTML form cannot set. Keep CORS disabled.

Return clear errors for:
- no active repository;
- adapter unavailable;
- unknown repository;
- pending previous close;
- terminal uncertain previous close;
- sanitized IBM failure.

Never return raw IBM stack traces/messages or secrets.

A minimal functional UI may be added, but polished UI belongs to Goal 04.

# L. Diagnostics and live smoke command

Authenticated diagnostics should expose:
- adapter availability;
- CM API release version when present;
- active repository id/SSID;
- CM pool metrics including create/close failures/quarantine;
- pool/context CloseState;
- metadata cache freshness;
- last sanitized adapter error.

Add a CLI such as:

```
./bin/cm-insight --check-repository <repository-id>
```

It MUST use the same provider -> RepositoryManager -> RepositoryContext -> BoundedPool path as runtime,
never a one-off SDK connection.

Read-only smoke:
- activate repository;
- report CM API version;
- report ItemType count;
- report retention policy count;
- close via RepositoryManager;
- fail if final shutdown is not terminal-clean.

# M. Tests/evidence

Keep every existing Goal 01A-01C test green.

Add IBM-independent deterministic tests for:
- provider absent/present/ambiguous;
- auto-activation with adapter absent;
- uncertain ResourceFactory creation quarantine;
- partial RepositoryContextFactory failure retained across retries;
- clean/pending/uncertain failed activation;
- cache isolation;
- authenticated API and repository action guard;
- no SDK type leaks into core;
- read-only source guard.

CI compiles the IBM source set against test-only stubs.

When real IBM JARs are available, compile against them and record separately.
Never describe stub compilation as real SDK validation.

If a real CM 8.7 environment is available, run the read-only smoke command and record:
- repository id/SSID;
- pool size;
- ItemType count;
- retention policy count;
- CM API release version;
- terminal CloseState;
- quarantine/close-failure counters.

No credentials.

Do not manufacture connection failures against production merely to test quarantine.

# N. Concurrency/performance rules

Metadata/retention calls may run concurrently only up to the CM pool bound.
Do not hold RepositoryManager's switch lock during normal metadata reads.
Every IBM operation uses Lease try-with-resources semantics.
No business method retains a Lease after returning.
No unbounded executor/queue.

# O. Documentation

Update README.md, ARCHITECTURE.md, SECURITY.md as needed, lib/README.md, examples and STATUS.md.

Document:
- core-only vs IBM-enabled build;
- strict IBM build/check mode;
- provider discovery;
- local IBM JAR placement;
- smoke command;
- read-only guarantee;
- no vendor JARs in Git.

# Definition of done

Goal 02 is complete only when:

1. all Goal 01C lifecycle invariants remain green;
2. core build works with zero IBM JARs;
3. IBM source compiles in CI against test-only stubs;
4. real SDK compile is performed when local JARs exist, or absence is stated honestly;
5. SDK classes do not escape adapter packages;
6. no emergency/ad-hoc DKDatastoreICM exists;
7. uncertain creation cannot silently free physical capacity;
8. partial factory allocation cannot become invisible to RepositoryManager;
9. IBM pool health callback is cheap/non-blocking;
10. ItemType + retention reads map to immutable DTOs;
11. write paths are absent and guarded;
12. authenticated API routes pass tests;
13. smoke command uses production bounded-pool path;
14. no secret/vendor binary is committed;
15. ./build.sh, ./tests/selftest.sh, committed shell tests and both exact-SHA Actions events pass;
16. live IBM validation is claimed only if actually performed;
17. STATUS.md is updated;
18. next goal = NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.

# Push / stop

At completion:
1. review integrated diff;
2. commit coherently;
3. push origin/bootstrap/cm-insight-architecture yourself;
4. verify local HEAD == remote HEAD;
5. require push and pull_request Actions for the SAME final SHA to be green;
6. update STATUS.md via the non-self-referential checkpoint protocol;
7. do not merge PR #1;
8. do not execute Goal 03;
9. stop with exact work commit(s), final SHA, Actions run IDs, tests, real-SDK compile status and
   live-CM smoke status.
