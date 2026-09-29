# Goal 02B - Resource contract closure before JDBC

**Status: COMPLETED / PUSHED / GREEN - AWAITING ARCHITECTURE REVIEW. DO NOT RE-EXECUTE.**

Implementation commit: `1049ab4c50c66adc682457971290deaa7dec17de`.
Both exact-SHA GitHub Actions runs are green for it: push `36592065472`, pull_request `36592073280`.
Core 253 tests and IBM 51 tests green on the stub path, and `--require-ibm` green against the real
IBM CM 8.7 SDK with the full IBM suite running on that class path.
No live CM validation has been performed - no server is reachable from the execution host.

This was the final narrow contract-hardening gate after the architecture review of Goal 02A.

Reviewed Goal 02A remote SHA:

`ff4b2aa32617a6037b541143b008947ed095cfdc`

Do NOT re-execute Goal 02 or Goal 02A.

Do NOT implement Goal 03, JDBC analytics, DB2/Oracle SQL, Versions/Parts, retention administration,
content viewing, migration or any IBM CM write.

The purpose of this goal is to make the generic physical-resource seam safe enough that Goal 03 can add a
JDBC pool without inheriting convention-based failure semantics.

Preserve all accepted Goal 01-02A behavior.

---

# A. Known-clean pre-allocation CM failures must explicitly release the creation slot - BLOCKING

Goal 02A correctly changed the generic rule:

> only explicit CreationFailure(PROVEN_CLEAN) releases a failed creation reservation; every untyped
> failure quarantines.

That exposed one IBM factory path that still relies on the OLD implicit rule.

Current shape in `IbmCmSessionFactory.create()`:

```java
validateProfile();
IbmCmCredentials credentials = resolveCredentials();
try {
    IcmDatastore handle = connections.connect(...);
    ...
} catch (IbmCmCleanupFailure ...) {
    // UNPROVEN
} catch (IbmCmFailure ...) {
    // PROVEN_CLEAN
}
```

`validateProfile()` and `resolveCredentials()` run before the verdict-producing try/catch.

The application intentionally resolves credentials again for every replacement session. If a secret/env
value disappears after successful activation, replacement creation fails before any datastore is allocated,
but the plain failure now reaches BoundedPool and quarantines an empty slot permanently.

## Required invariant

> Once BoundedPool reserves a physical-resource creation slot, every failure path that KNOWS no physical
> resource was allocated (or knows it was fully cleaned) must explicitly report PROVEN_CLEAN.

For the IBM CM session factory:

- profile/request validation failure before physical allocation -> PROVEN_CLEAN;
- CM credential resolution failure before physical allocation -> PROVEN_CLEAN;
- explicitly proven connection cleanup -> PROVEN_CLEAN;
- destroy/cleanup not proven -> UNPROVEN;
- failure whose physical outcome is genuinely unknown -> leave conservative/unproven;
- do not relabel an unknown connection-layer failure as clean merely to preserve capacity.

Preserve the original sanitized cause and operator diagnostics.

Make `lastAttemptLeftResources` describe the CURRENT attempt on every failure path; a previous unproven
attempt must not leave this diagnostic stale after a later known-clean pre-allocation failure.

A fatal JVM `Error` before allocation may remain conservative if preserving Error semantics requires it;
if so, document that decision explicitly. Ordinary validation/configuration failures are not allowed to
burn capacity.

## Required regression test

Drive the real production factory/pool seam, not only a standalone helper:

1. create/activate a pool successfully;
2. force one session to retire so replacement creation is needed;
3. make the CM credential source unavailable before that replacement;
4. assert the replacement failure occurs with **zero additional physical connect attempts**;
5. assert `quarantined == 0`, `createQuarantineFailures == 0`, and the reserved slot is released;
6. restore the credential source;
7. assert a subsequent borrow creates a fresh session and the pool recovers to normal capacity.

Also cover direct known-clean validation before allocation where reachable.

Add the opposite control: an unknown failure after the physical allocation boundary must still quarantine.

---

# B. ResourceFactory health validation must be explicit - HIGH / REQUIRED BEFORE GOAL 03

`ResourceFactory.isHealthy(T)` currently has a permissive default:

`resource != null`

That makes safe retirement depend on every composition root remembering to override a method. Goal 03 is
about to introduce another physical-resource factory, so this is the wrong moment to carry that trap
forward.

## Required change

Make health semantics explicit and mandatory.

Preferred shape:

```java
boolean isHealthy(T resource);
```

with no permissive default.

An equivalent explicit health-policy object is acceptable, but no production physical-resource factory
may silently inherit "non-null means healthy".

Update every current factory and test fixture deliberately. Each implementation must either:

- perform the documented cheap/local health check; or
- explicitly return a constant only where that resource type genuinely has no stronger local health state.

Do not add network I/O or a blocking probe: the existing BoundedPool lock rule remains authoritative.

Required tests/compile guards:
- a factory cannot be instantiated without choosing health semantics;
- CM production composition still delegates to the local session flag;
- existing rotation/return tests remain green.

---

# C. Reassert backend-unusable on already-translated IBM failures - HIGH

`IbmCmApi.vendorCall()` currently catches an `IbmCmFailure` and rethrows it unchanged under the
assumption that a lower wrapper already marked the session unusable.

Current production construction sites satisfy that assumption, but it weakens the claimed invariant:

> every backend-unusable failure leaving a vendor call has already marked the session unusable.

Make the rethrow path enforce the invariant idempotently:

- when `alreadyTranslated.backendUnusable()` is true, mark the current session unusable before rethrow;
- when false, leave it reusable;
- do not leak the original/raw vendor text while marking.

Required test:
- a ReadCall throws a preconstructed `IbmCmFailure(..., backendUnusable=true)`;
- by the time it is observable outside `IbmCmApi.read`, the session is unhealthy;
- the same test with `backendUnusable=false` stays reusable.

This must be an invariant of the wrapper, not of call-site memory.

---

# D. IBM test runner must not silently omit future suites - HIGH

Goal 02A discovered four core test classes that had existed but were never registered. The core runner now
detects that condition.

The IBM runner still has a manual `TEST_CLASSES` list and no equivalent guard. A new IBM suite can therefore
be committed, compile successfully, and never execute - the exact defect class Goal 02A just found.

## Required fix

Either:

1. dynamically discover every runnable IBM suite in `com.mraibo.cminsight.ibm.internal` and run them in a
   deterministic order; or
2. keep the explicit list but add an equivalent fail-closed registration guard.

A runnable suite means the same test shape the runner already accepts: public concrete class, public no-arg
constructor, at least one public no-arg void test method.

The guard/discovery must work on both:
- committed stub class path;
- real IBM CM 8.7 SDK class path.

Prove the mechanism with a controlled omitted/planted suite or equivalent test of the discovery logic.

Do not solve this by maintaining a second source-code list that can drift with the first one.

---

# E. Retention code absence must not invent an undocumented sign rule - SHOULD FIX

Goal 02A correctly replaced the misleading `-1` sentinel with nullable numeric fields.

The DTO constructor additionally normalises **every negative value** to null under the assertion that a CM
enum code can never be negative. That premise was not established by the SDK evidence used in Goal 02A.

Absence already has an unambiguous representation: `null`.

Required:
- adapter uses `null` when no numeric mapping is established;
- delete the broad "negative means absent" normalisation unless authoritative IBM evidence proves the sign
  restriction;
- an explicitly supplied numeric code, positive or negative, is preserved as data;
- no `-1` sentinel constant/call site is reintroduced.

The product rule is "do not guess", not "guess that valid codes are non-negative".

---

# F. Preserve all accepted Goal 02A corrections

Do not regress:

- only explicit PROVEN_CLEAN releases an unknown creation reservation;
- unknown/untyped post-allocation failure stays quarantined;
- IBM failed-connect cleanup uses isConnected -> conditional disconnect -> always destroy;
- successful destroy is cleanup proof; failed destroy remains unproven;
- provider-installed vs runtime-ready distinction;
- SDK-free runtime refusal before manager/factory/pool;
- core-loaded ClassificationRules only;
- no application config read under src/ibm/java;
- backend-unusable RuntimeException/Error/wrong vendor types retire the session;
- DKNotExist remains benign;
- nullable retention enum codes and JSON null;
- optional IBM source-set isolation;
- read-only source guards;
- no emergency/ad-hoc CM connection;
- Goal 01C retained-context latch;
- metadata cache/API/security behavior;
- Linux lifecycle/process tests;
- no proprietary JARs in Git.

The current real-SDK cleanup behavior for the unreachable SSID is accepted. Do not reintroduce the Goal 02
permanent quarantine.

---

# G. Validation

Run at minimum:

- `./build.sh`;
- `./tests/selftest.sh`;
- `./tests/shell/run.sh`;
- `./bin/doctor.sh`;
- IBM suite against committed stubs;
- source/isolation/read-only guards;
- `./build.sh --require-ibm` with the real CM 8.7 SDK when the same local SDK is available.

Add focused tests for sections A-D.

If the real SDK is available, compile both production and IBM tests with `-Xlint:all`.

No live CM server is currently available. Do not claim live validation. A real-SDK failed connection remains
useful lifecycle evidence only when labelled accurately.

Verify:
- zero tracked vendor JARs/credentials;
- Goal 01C RepositoryManager/close latch did not change unnecessarily;
- PR #1 remains unmerged.

Both push and pull_request Actions for the SAME final SHA must be green.

---

# H. Handoff

Update STATUS.md with:

- exact implementation commit;
- the known-clean pre-allocation regression and its test;
- resulting ResourceFactory create/health contract;
- backend-unusable reassertion;
- IBM suite registration/discovery behavior;
- retention nullable code semantics;
- core/stub/real-SDK tests actually executed;
- live CM status honestly;
- Actions run ids/results;
- unresolved risks.

Set the next goal to:

**NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

Do not execute Goal 03.

At completion:

1. review the complete integrated diff;
2. commit coherently;
3. push `origin/bootstrap/cm-insight-architecture`;
4. verify local HEAD == remote HEAD;
5. verify both exact-SHA Actions events are green;
6. do not merge PR #1;
7. stop with the architecture-review handoff.
