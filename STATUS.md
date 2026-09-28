# Project Status

> Canonical cross-session checkpoint. Read this first when continuing in another chat/model.

## Project state

- Project: CM Insight
- Repository: mrAibo/cm_java_item_reporter
- Active branch: `bootstrap/cm-insight-architecture`
- Goal 01 implementation commit: `fb42a0e55e69b551bffdcf0986714b8110dbdfb6`
- Goal 01A: accepted after review-hardening
- Goal 01B: accepted after Linux lifecycle / close-propagation review
- Goal 01C reviewed remote HEAD: `8dcea7222178fd95aa8b123994187187aa2bd192`
- Stage: **Goal 02 IN PROGRESS - not complete, not pushed yet; see "Goal 02 execution state" below**
- Runtime target: Java 17 LTS / OpenJDK-compatible
- Build/deployment: javac + jar + bash; single JVM
- Product safety mode: read-only V1/V2
- Current approved goal: `harness/GOAL_02_IBM_CM_RETENTION.md`
- Goals 03-05: PROVISIONAL; do not execute
- Next goal after Goal 02: **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

## Goal 02 execution state (IN PROGRESS)

Interface surface frozen in `harness/GOAL_02_IMPLEMENTATION_SPEC.md`; real-SDK API evidence in
`harness/IBM_CM87_SDK_API_SURFACE.md` (rescued from `%TEMP%` deliberately - it is the evidence for the
corrections noted below).

Landed, each its own commit:

| Commit | Contents |
| --- | --- |
| `6dcbfc4` | wave 1: `CreationFailure`, the `BoundedPool` uncertain-create quarantine, `PoolMetrics.createQuarantineFailures`, `ActivationFailedException`, the `RepositoryManager` retained-cleanup path, `CmSessionFactory`/`CmPoolSettings`/`CmPoolDiagnostics`/`RepositoryServices`, `MetadataRepository`/`RetentionRepository`, extended DTOs |
| `b7c5c1d` | `tests/shell/ibm_guard.sh` - the section A (SDK isolation) and section H (read-only) source guards |
| `acc34a1` | build: optional IBM source set, `--require-ibm`, `--check-ibm-isolation`, and `lib/ibm` REMOVED from the core class path so the isolation is structural rather than conventional |
| `2d8c090` | launcher: `CM_INSIGHT_IBM_LIBS`, `CM_INSIGHT_REQUIRE_IBM`, `--check-repository` documented |
| `fb479a5` | stub compilation wired into the build, the `initialize()` rollback fix, the guard narrowing, and the first section C tests |
| `bcf77f9` | 32 signature-only IBM CM 8.7 compile stubs + `EXPECTED_SIGNATURES.txt` + `check-signatures.sh` (ibm-stubs member) |

Still open at the time of writing: the IBM adapter source set (`src/ibm/java`), the provider/registry
wiring, the web API, the remaining test suites and the documentation. Nothing is pushed, so the branch
head is still the accepted review SHA plus local commits.

### Three defects found in the lead's own work, and fixed

Recorded explicitly: each was a real safety defect rather than a typo, and two were found by other
members attacking the lead's code rather than by the lead's own tests.

1. **`BoundedPool.initialize()` lost every never-started reservation** (found by the foundation member).
   `initialize()` reserves all `size` slots in one step and `newEntry()` consumes one per attempt, so
   after the loop aborted the outstanding reservations were the failing attempt PLUS every attempt that
   never started - exactly `size - entries.size()`. The first version resolved only the failing attempt,
   so a size-4 pool failing on its first create leaked THREE reservations permanently: capacity lost for
   the life of the process, and a pool that had never handed anything out reporting itself as still
   creating. A proven-clean activation failure therefore refused every later activation forever, because
   a phantom reservation can never be released.
2. **The same block drove `creatingCount` NEGATIVE on the proven-clean path.** `quarantineUnprovenCreation()`
   decrements `creating` itself, so releasing the failing attempt as well removed one reservation more than
   existed. A negative `creating` over-authorises creations, which is the direction that breaks the hard
   bound. Both branches now account for the failing attempt exactly once. Fixing only the leak would not
   have caught this; it surfaced by writing the arithmetic out for both paths.
3. **The read-only guard refused ordinary Java collections** (found by the tests member). The
   receiver-agnostic `.add(`, `.remove(`, `.update(`, `.delete(`, `.del(` patterns matched the adapter's
   own `List`/`Map` bookkeeping, and a `.set*` pattern matched `AtomicBoolean.set` and `List.set`:
   measured, a file containing nothing but local collection calls produced 7 refusals. A guard that cannot
   pass is a guard that gets disabled, so those names were removed from the scan. The mutating members they
   were meant to catch are excluded STRUCTURALLY instead, one layer earlier and more strongly than a text
   scan can manage: `tests/ibm-stubs` declares only the read-only getters, so such a call does not compile
   at all, and `build.sh` audits that omission against the pinned signature file.

### Accepted contract decisions worth reviewing

- **An UNTYPED exception from `ResourceFactory.create()` still releases the reserved slot.** Four
  committed Goal 01 assertions pin that behaviour, so quarantining by default would have silently narrowed
  a documented contract. Only an explicit `CreationFailure(UNPROVEN)` quarantines. **Residual risk,
  recorded rather than hidden:** a factory that opens a physical resource, fails to clean it up and then
  throws an ordinary exception remains invisible to the pool. CM Insight's own IBM factory always signals;
  a third-party factory must opt in.
- **A create-failure quarantine is folded into the existing `quarantined` counter** rather than becoming a
  sixth slot state, because `PoolMetrics.capacityInUse()` is asserted to equal
  `available + leased + creating + retiring + quarantined` and a sixth state would break that identity and
  `degraded()`. The event counter `createQuarantineFailures()` keeps the two kinds distinguishable, and
  the metrics-contract test was extended deliberately in the same commit.
- **A failed `initialize()` propagates the factory's own exception unchanged**, so a caller sees the
  `CreationFailure` and its cleanup verdict rather than a wrapper that hides it.

## Goal 01C review verdict

**ACCEPTED.**

Reviewed pushed SHA:

`8dcea7222178fd95aa8b123994187187aa2bd192`

Exact-SHA GitHub Actions:

- push run `36482320243`: **success**
- pull_request run `36482326265`: **success**

Accepted core safety properties:

- BoundedPool cannot report terminal-clean while leased/creating/retiring/quarantined physical capacity remains.
- RepositoryContext derives close state on every read instead of freezing a stale clean snapshot.
- late clean lease return can move a closing context to terminal-clean.
- late failed return/quarantine moves it to terminal-uncertain.
- uncertainty is latched monotonically.
- RepositoryManager retains an unpublished previous context and checks it before every retry.
- pending/uncertain previous repository prevents the next-context factory from being called.
- terminal-clean is checked before the retained context is forgotten.
- Goal 01B Linux/process/socket/security behavior remains intact.

No new fail-open or physical-bound blocker was found in the reviewed Goal 01C diff.

## Accepted Goal 02 obligations

### Adapter state/health reads

RepositoryManager reads close state under its lifecycle lock. A broken adapter whose closeState() blocks
can delay switching, but cannot authorize a fail-open switch. Goal 02 must keep closeState and pool
health checks cheap/local and perform network validation outside pool locks.

### Physical uncertainty during create()

A real SDK factory can allocate/connect and then fail cleanup. Goal 02 must add a generic
uncertain-creation signal so BoundedPool quarantines that reservation instead of silently freeing it.

### Partial repository activation

If the production repository factory allocates resources and fails before returning a context, those
resources must remain visible to RepositoryManager. Goal 02 must add a cleanup-context/equivalent
failure path and apply the same pending/uncertain latch rules.

## IBM source evidence

Reviewed working CM_Migrator connection/pool code and CM_retention CmService.

> **Goal 02 progress correction (factual, verified against the real SDK jar).** Two statements in the
> evidence block below could not be confirmed on this machine and must not be relied on:
>
> 1. **`CM_retention` is not present anywhere on this machine.** Recursive case-insensitive searches over
>    `C:\Users\crown\Downloads\Projects` and `C:\Users\crown` found no `CM_retention` directory, no
>    `CmService.java` and no `CmService.class`; no Java source anywhere on the machine calls the retention
>    API at all. The "confirmed read paths from CM_retention" list therefore has no local source backing
>    it. It was replaced as evidence by direct `javap` verification against the real
>    `cmbicmsdk81.jar` (8.7.00.400.44), recorded in `harness/IBM_CM87_SDK_API_SURFACE.md`.
> 2. **`DKDatastoreDefICM.listEntities(...)` as written in that list does not exist.** The real overloads
>    are `listEntities()`, `listEntities(int)` and `listEntities(DKNVPair[])`; there is no
>    `listEntities(String)`, and `DKConstantICM.DK_ICM_ENTITY_TYPE` is a `String` constant that no
>    `listEntities` overload consumes. The correct call is `listEntities(DK_ICM_BASE)`.
>
> Six further facts were corrected the same way; all are recorded in
> `harness/GOAL_02_IMPLEMENTATION_SPEC.md` section 1. The most consequential: `DKDatastoreICM` is in
> `com.ibm.mm.sdk.`**`server`**, the SDK has **no `close()` anywhere** (teardown is `disconnect()` then
> `destroy()`), `datastoreDef()` and `datastoreAdmin()` do not narrow so both casts are load-bearing, the
> retention period-unit "constants" are nested **enums** rather than ints, the ItemType integer id comes
> from `getIntId()` (the inherited `getId()` is a lossy `short`), and `DKSystemException` does not exist
> (the real class is `DKSystemError`).

Useful from CM_Migrator:
- DKDatastoreICM lifecycle and cleanup patterns;
- age/usage ideas.

Rejected:
- emergency connections;
- source/destination pool architecture;
- native JDBC extraction/ad-hoc connections;
- swallowed cleanup failures.

Confirmed read paths from CM_retention:
- DKDatastoreICM.connect(ssid,user,password,"")
- DKDatastoreDefICM.listEntities(...)
- retrieveEntity(name)
- DKDatastoreAdminICM.datastoreAdmin().policyMgmt()
- listRetentionPolicyNames()
- listRetentionPolicies()
- retrieveRetentionPolicy(name)
- listItemTypeNamesByRetentionPolicy(name)
- ItemType property getters.

CM_retention mutating assign/unassign/create/delete/update/commit paths remain forbidden.

## IBM / DB live status

No IBM CM / DB2 / Oracle live validation has yet been performed for CM Insight.
Goal 02 is the first goal allowed to compile/use local IBM SDK JARs when available.
JDBC analytics remains Goal 03.

## Exact next goal

Execute only:

1. `harness/MASTER_GOAL.md`
2. `harness/GOAL_02_IBM_CM_RETENTION.md`

Do not execute Goal 03.

## Resume instruction

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture. Fetch and fast-forward to the current remote state. Read STATUS.md, ARCHITECTURE.md, SECURITY.md, harness/MASTER_GOAL.md and harness/GOAL_02_IBM_CM_RETENTION.md completely. Goal 01C is accepted. Execute only the approved Goal 02. Preserve every Goal 01A-01C hard-bound, fail-closed, Linux lifecycle and security invariant. Do not implement JDBC analytics or Goal 03. At completion run all required validation, commit coherently, push the branch yourself, verify local/remote HEAD equality, require both exact-SHA Actions events green, update STATUS.md, set the next goal to NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED, do not merge PR #1, and stop."

## Mandatory checkpoint rule

At the end of every approved goal record:

- date/time;
- branch;
- last completed work commit described by STATUS;
- exact work completed;
- tests/results;
- real IBM SDK compile performed or explicitly not performed;
- live IBM CM smoke performed or explicitly not performed;
- Actions run IDs/results;
- unresolved risks;
- architecture changes only when approved;
- next goal status;
- final local/remote HEAD in the handoff report after push.
