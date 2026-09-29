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
- Stage: **Goal 02 EXECUTED, PUSHED and GREEN on both Actions events; awaiting architecture review**
- Goal 02 work commits: see "Goal 02 execution state" below
- Next goal: **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED** - do not execute Goal 03

## Goal 02 final state

**Date/time of this checkpoint:** end of the Goal 02 execution session (local time).

**Branch:** `bootstrap/cm-insight-architecture`. **PR #1 deliberately not merged.**

**The checkpoint protocol, applied honestly.** A commit cannot truthfully record its own SHA, and this
file changes the tree, so the section below names the **last completed implementation/work commit it
describes** - the final implementation commit before this documentation update. The authoritative current
branch head is read from Git, and the handoff report records the exact local and verified remote HEAD
after this commit has been pushed:

- last implementation/work commit described here: `818cdcf45ffff02383915958424e5403ae653e00`
- authoritative head: `git rev-parse HEAD` / `git ls-remote origin refs/heads/bootstrap/cm-insight-architecture`

### What was implemented

- **Section A - optional IBM source set.** `src/ibm/java` and `src/ibm-test/java` compile as separate
  source sets into `build/`-external `target/` output, then are packaged into the SAME jar and JVM.
  `lib/ibm` is **removed from the core class path**, so a core source needing an SDK type fails to
  compile rather than drifting into the adapter - isolation is structural, enforced again by a source
  guard. `--require-ibm` fails clearly when IBM support is demanded without the SDK;
  `--check-ibm-isolation` runs the guards alone with no JDK needed.
- **Section A1 - test-only SDK stubs.** 32 signature-only stubs in `tests/ibm-stubs`, compiled into a
  **per-run** directory and pinned against `EXPECTED_SIGNATURES.txt`. Never packaged: verified 0
  `com/ibm/*` classes in the jar.
- **Section B - provider boundary.** `ServiceLoader` discovery with ABSENT / AVAILABLE / AMBIGUOUS /
  UNAVAILABLE; multiple providers is an explicit failure, never a silent pick. `repository.auto.activate`
  with no provider fails startup instead of publishing an empty placeholder context.
- **Section C - uncertain creation preserves the physical bound.** A `CreationFailure` outcome carries
  PROVEN_CLEAN or UNPROVEN; UNPROVEN quarantines the reserved slot. `PoolMetrics.createQuarantineFailures`
  separates a costly failure from a harmless one.
- **Section D - partial activation stays visible.** `ActivationFailedException` carries the partially
  built context back to `RepositoryManager`, which retains it in the same latch a switch uses:
  terminal-clean lets a later activation proceed, pending refuses as PENDING, uncertain refuses
  permanently.
- **Sections E/G/H - the adapter.** `DKDatastoreICM` lifecycle with disconnect-then-destroy and an honest
  three-state close outcome; `isHealthy()` is a cheap local flag read; ItemType and retention read
  services mapping SDK objects immediately to immutable DTOs, with unknown numeric values rendered as
  `UNKNOWN(<value>)`.
- **Sections F/I - typed seams and cache.** `MetadataRepository`/`RetentionRepository` are read-only by
  contract (no write method exists), reached through typed `RepositoryContext` accessors; a per-context
  metadata cache with TTL, one-refresh-per-key and no partial replacement of a good snapshot.
- **Sections J/K/L - production wiring, API, diagnostics.** Provider-backed production factory and
  `--check-repository` smoke CLI; eight authenticated routes wired into `Main.serve()` before the socket
  opens; the `X-CM-Insight-Action` guard on repository selection; value-free pool and adapter diagnostics.
- **Section M - evidence.** 212 core tests and 24 IBM tests, all green, plus the committed shell suite.

### Tests actually run, with results

Run on **Linux with JDK 17.0.20.1**, against a clean copy of the pushed tree, executing CI's own sequence:

- `./build.sh` -> **exit 0**; "Tests run: 212, failures: 0" (core), "Tests run: 24, failures: 0" (IBM),
  "OK: built build/cm-insight.jar".
- `./tests/selftest.sh` -> **exit 0**.
- `./tests/shell/run.sh` -> **exit 0**; all four committed tests pass, including
  `lifecycle_identity_race_test.sh`, which is skipped on Windows because it needs `/proc` argv semantics.
- `./bin/doctor.sh` -> **exit 0** (0 failures, 11 warnings).
- The CI safety guards (exact health marker, structural argv identity, address classification) -> pass.

Also run on Windows with the project JDK: the same suites, plus `./build.sh --check-ibm-isolation` and
`--require-ibm` (which correctly refuses without an SDK).

### IBM SDK compile - both modes, reported separately

- **Against the test-only stubs: exit 0**, 23 adapter classes, zero warnings. This is a compile check and
  is **not** described anywhere as SDK validation.
- **Against the REAL IBM CM 8.7 SDK** (`cmbicmsdk81.jar`, `8.7.00.400.44`, plus
  `cmbxmlmap`/`cmbxmlservice`/`common`): **exit 0, 23 classes, zero warnings.** The real SDK is
  authoritative over the stubs.
- Independently verified by a member who did not write the stubs: 32/32 classes in the same package,
  202/202 method signatures matching on name, arity, parameter types, return type and throws clause,
  35/35 constants matching value, and - the decisive check - compiling all 131 sources against the real
  jar and against the stubs produced **131/131 byte-identical class files**.

### Live IBM CM validation - NOT performed, and not claimed

**No live CM 8.7 environment is available on this machine. No read-only live smoke against a working
repository has been run, and no claim of live validation is made anywhere in this goal.**

What *was* run is a genuine connection attempt to an unreachable SSID through the production path, which
failed inside IBM's own code:

```
adapter       : ibm-cm-8.7 (adapter 0.1.0-SNAPSHOT)
CM API release: 8.7.00.400.44
ERROR activation: ... connect cm-usage(DKUsageError, errorId=7332); cleanup of the allocated
                  datastore did not return normally, so a physical session may still exist
shutdown      : repository CLOSED_UNCERTAIN, retained context CLOSED_UNCERTAIN
CM pool state : cm:smoke-probe size=4 inUse=1 quarantined=1 createQuarantineFailures=1
                closeFailures=0 lastError=... state=CLOSED_UNCERTAIN
RESULT: not clean (activation failed)          EXIT=1
```

That single run exercises the whole chain on real IBM code: the provider is discovered, the pool
**quarantines a real session instead of freeing the slot** (`inUse=1` on a size-4 pool, so the remaining
three are never opened), the retained context reports `CLOSED_UNCERTAIN` so the switch latch refuses every
later activation, the uncertainty is attributed to the **creation** (`createQuarantineFailures=1`,
`closeFailures=0`), and `grep` for both exported credential values found **zero** occurrences.

**Separately verified by measurement** (by a member who wrote neither the pool nor the adapter): with 8
threads x 40 borrow attempts against a measuring factory, peak live physical sessions were **4 = exactly
`cm.pool.size`**, while the same measurement against a mutant that frees capacity on an uncertain creation
reached **316**. The mutant is the evidence; the correct figure alone would prove nothing. Health-probe
cost was measured at **4.32 ns/call with 0 SDK calls**, against a fake whose `isConnected()` blocks 3 s.

### GitHub Actions - both events, same SHA, verified green

| Commit | push run | pull_request run |
| --- | --- | --- |
| `818cdcf` (final implementation commit) | `36500279578` **success** | `36500283510` **success** |

Both runs are exact-SHA: each run's `headSha` equals the commit above, so a green row is evidence about
that revision and no other. Verified through the GitHub API, not from a summary.

**Earlier revisions of this goal failed CI and those failures are recorded rather than hidden**, because
three of them were the same defect class - invisible on a Windows developer machine, fatal only on a
clean Linux checkout:

| Commit | Failure | Cause |
| --- | --- | --- |
| `e040135` | "Verify script permissions" | `tests/shell/ibm_guard.sh` committed 100644 |
| `3d965b7` | "Build" | `target/` is gitignored, so `mktemp -d target/...` had no parent on a clean checkout |
| `e40464b` | "Build" | `tests/ibm-stubs/check-signatures.sh` committed 100644, and build.sh guarded the call with `-x` |

All three were reproduced and fixed by running CI's own commands on a clean **Linux** checkout, which is
now the authoritative local verification path for this repository. The executable-bit problem recurred
because git on Windows reports `core.filemode=false` and does not record the bit from the filesystem, so
a file can be `chmod +x` in a working tree and still be committed `100644`. The last one is now
impossible to reintroduce: build.sh no longer requires a bit for a script it invokes through `bash`.

### Unresolved risks and limitations

1. **No live IBM CM validation.** The read path against a real Library Server is unexercised. This is the
   single largest gap, and it is a data gap, not a code gap.
2. **The discovery seam's environment-specific half is now tested, but only against the packaged jar.**
   `IbmProviderRegistrationTest` asserts the real production class path finds exactly the registered
   provider; the core suite covers the registry's decision logic with descriptors it supplies itself
   because SelfTest runs adapter-free.
3. **An UNTYPED exception from `ResourceFactory.create()` still releases the reserved slot.** Four
   committed Goal 01 assertions pin this, so quarantining by default would have silently narrowed a
   documented contract. Only an explicit `CreationFailure(UNPROVEN)` quarantines. A third-party factory
   that opens a physical resource, fails to clean it up and then throws an ordinary exception remains
   invisible to the pool. CM Insight's own IBM factory always signals.
4. **The classification path is not test-verified.** `CmMetadataService.classificationOf` is private and
   needs an SDK `ItemTypeDef`, so "the business classification comes from `ClassificationRules` and no
   hard-coded SAP rule exists" is source-verified only. `ClassificationRulesTest` covers the rules
   themselves.
5. **`IbmCmSessionFactory.lastAttemptLeftResources()` is production-dead** (no callers outside the
   adapter's own tests) and duplicates what `CreationFailure.cleanupProven()` already carries. Recorded as
   a finding for the architecture review rather than refactored after verification.
6. **Sanitisation rests on an adapter-internal convention.** The boundary is `IbmErrorSanitizer` plus the
   `IbmCmConnectionFactory` contract. A package-private implementation that threw a raw vendor exception
   would bypass it, and `BoundedPool` would then put `throwable.getMessage()` into a `PoolException`. The
   production implementation always wraps.
7. **Which additional SDK JARs the read path needs at runtime is unverified.** Only `cmbicmsdk81.jar` is
   confirmed; `lib/README.md` therefore asserts no list.
8. **Concurrency of the build, not of the product.** `build.sh` now takes an exclusive lock and refuses a
   second concurrent run rather than corrupting the first; without `flock` it says so. This was a real
   source of misleading red builds during the goal, not a product defect.

### Architecture decisions and goal state

No architecture rule was silently changed. The 11 non-negotiable rules in `ARCHITECTURE.md` are byte-unchanged
and still numbered 1-11. Two evidence corrections were recorded rather than quietly applied: `CM_retention`
is **not present on this machine**, so the goal's mandatory source review of it was impossible and was
replaced by direct `javap` verification against the real SDK; and the goal's
`listEntities(... DK_ICM_ENTITY_TYPE ...)` spelling matches no real signature.

**Next goal: NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.** Do not execute Goal 03. Do not merge PR #1.

### Resume / review instruction

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture. Fetch
and fast-forward to the current remote state. Read STATUS.md, harness/MASTER_GOAL.md,
harness/GOAL_02_IBM_CM_RETENTION.md, harness/GOAL_02_IMPLEMENTATION_SPEC.md and
harness/IBM_CM87_SDK_API_SURFACE.md. Goal 02 is executed, pushed and green on both Actions events for the
same SHA; review it before approving anything further. Do not re-execute Goal 02 and do not execute Goal
03. Note the largest open gap: no live IBM CM validation has been performed, because no CM server is
reachable from this machine."

## Build and goal state

- Runtime target: Java 17 LTS / OpenJDK-compatible
- Build/deployment: javac + jar + bash; single JVM, no Maven/Gradle/Spring/containers
- Product safety mode: read-only V1/V2
- Current approved goal: `harness/GOAL_02_IBM_CM_RETENTION.md` (EXECUTED, pushed, green)
- Goals 03-05: PROVISIONAL; do not execute
- Next goal after Goal 02: **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

## Goal 02 execution notes

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

**No IBM CM, DB2 or Oracle LIVE validation has been performed for CM Insight, in this goal or any
earlier one.** No CM 8.7 server and no DB2/Oracle instance is reachable from the machine this goal was
executed on. Goal 02 compiled against the real IBM CM 8.7 SDK and made one genuine connection attempt
that failed at the server; that is recorded in "Goal 02 final state" above and is explicitly **not**
presented as live validation. JDBC analytics remains Goal 03 and was not started.

## Exact next goal

**NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.** Do not execute Goal 03.

1. `harness/MASTER_GOAL.md`
2. `harness/GOAL_02_IBM_CM_RETENTION.md` and `harness/GOAL_02_IMPLEMENTATION_SPEC.md`

Then stop for the architecture review. Do not execute Goal 03.

## Resume instruction

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture. Fetch
and fast-forward to the current remote state. Read STATUS.md, ARCHITECTURE.md, SECURITY.md,
harness/MASTER_GOAL.md, harness/GOAL_02_IBM_CM_RETENTION.md and harness/GOAL_02_IMPLEMENTATION_SPEC.md
completely. Goals 01 through 01C are accepted, and Goal 02 is executed, pushed and green on both Actions
events for the same SHA. REVIEW Goal 02 before approving anything further - do not re-execute it and do
not execute Goal 03. Preserve every Goal 01A-01C hard-bound, fail-closed, Linux lifecycle and security
invariant. Two things to carry forward: the largest open gap is that NO live IBM CM validation has been
performed, because no CM server is reachable from this machine; and this repository is developed on
Windows, where git does not record the executable bit, so validate on Linux with JDK 17 before trusting a
CI-touching change."

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
