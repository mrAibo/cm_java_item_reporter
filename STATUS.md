# Project Status

> Canonical cross-session checkpoint. Read this first when continuing in another chat/model.

## Project state

- Project: CM Insight
- Repository: mrAibo/cm_java_item_reporter
- Active branch: `bootstrap/cm-insight-architecture`
- Goal 01 / 01A / 01B / 01C: accepted core foundation
- Goal 02 reviewed remote HEAD: `87ffeb8e6de9b5ab6577353a7c54a0c6e718dead`
- Goal 02 implementation checkpoint recorded by the execution handoff: `818cdcf45ffff02383915958424e5403ae653e00`
- Goal 02A reviewed checkpoint (the review that required this goal): `67d058c1d26d706ce75b73011e959f23c00c5868`
- Goal 02A implementation commit (the commit this section describes): `4b810b361c3c34b0b9118ddcbc560ee47d21560b`
- Stage: **Goal 02A EXECUTED, PUSHED and GREEN on both Actions events; awaiting architecture review**
- Current approved goal: `harness/GOAL_02A_IBM_ADAPTER_SEMANTICS_HARDENING.md` (executed)
- Goals 03-05: PROVISIONAL; do not execute
- Next goal: **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

## Goal 02A execution record

Date/time: end of the Goal 02A execution session (local time). Branch:
`bootstrap/cm-insight-architecture`. **PR #1 deliberately not merged.**

Checkpoint protocol: a commit cannot truthfully record its own SHA, and this file
changes the tree, so the commit above is the last completed **implementation** commit
this section describes. The authoritative head is read from Git
(`git rev-parse HEAD` / `git ls-remote origin refs/heads/bootstrap/cm-insight-architecture`),
and the handoff report records the exact local and verified remote HEAD after this
documentation commit has been pushed.

### The five mandatory corrections, and how each was verified

**A. Unknown create failure now fails safe - the review's headline finding.** This was
the lead's own decision in Goal 02: the pool released the reserved slot for any creation
failure that was not an explicit `UNPROVEN`, and STATUS.md recorded that as accepted
residual risk. The review rejected it, correctly: a plain exception is not proof that
nothing was allocated, and requiring every future adapter author to remember a special
exception type is the fail-open dependency the generic pool exists to remove.

**The generic create-failure default after this correction:** a creation reservation is
released after a failed `create()` **ONLY** when the factory explicitly proves a clean
cleanup with `CreationFailure(PROVEN_CLEAN)`. Every other outcome - a plain checked
exception, a `RuntimeException`, an `Error`, an `InterruptedException` or an explicit
`UNPROVEN` verdict - **quarantines** the slot. This is the deliberate **opposite** of the
pre-02A default. A factory that knows it failed before allocating anything must now say
`PROVEN_CLEAN` explicitly.

Measured **with its control**, by a verifier who wrote none of the code: the shipped rule
peaked at **2 live physical resources against a configured size of 2**; the same class
with the OLD rule restored in a copy peaked at **180 > 2**, with `quarantined=0` and
`capacityInUse=0`. The mutant is the evidence - a correct peak on its own would prove
nothing. Capacity-identity sampling: 0 violations.

The four Goal 01 assertions that encoded the old rule were **renamed and re-expressed,
not deleted**: `anErrorDuringACreationQuarantinesTheSlotBeforeTheErrorPropagates`,
`initializeFailureClosesWhatItCreatedAndQuarantinesTheFailedSlot`,
`aFactoryFailureOnBorrowSurfacesAsAPoolExceptionAndQuarantinesTheSlot` (and one
unchanged in name), with no net loss (34 -> 34 across the two suites) and stronger
assertions. The verifier confirmed independently that they encode the new contract.

**B. The IBM cleanup verdict now follows IBM's documented lifecycle.** The production
factory unconditionally disconnected after a failed connect and required BOTH disconnect
and destroy to succeed, so an ordinary failed login could become a **permanent
quarantine**. IBM CM 8.7 documents `isConnected()` as true only when `connect()`
completed successfully, `destroy()` as performing the datastore cleanup if needed, and
`connect -> disconnect -> destroy` as the successful lifecycle.

**The cleanup proof rule:** one implementation,
`IbmCmCleanupFailure.releaseQuietly(IcmDatastore)`, now owns the documented order and is
used by BOTH the failed-connect path and `IbmCmSession.close()`. Order: `isConnected()`
outside any pool lock, then `disconnect()` only when connected or when the answer is
unreadable, then `destroy()` **always**. **A successful `destroy()` is the final cleanup
proof**, because IBM documents it as performing the cleanup if needed; a disconnect
problem is retained as a sanitized diagnostic but no longer quarantines, and a **failing
destroy still quarantines regardless** of the disconnect result. The justification is
written at the release site, because this deliberately weakens a safety rule in one
documented direction and a reviewer must be able to see why.

Measured on the **real IBM CM 8.7 SDK**, the same unreachable-SSID failure Goal 02 used:

| | Goal 02 | Goal 02A |
| --- | --- | --- |
| message | `cleanup ... did not return normally` | `connect cm-usage(DKUsageError, errorId=7332)` |
| shutdown | `repository CLOSED_UNCERTAIN, retained context CLOSED_UNCERTAIN` | `repository (none), nothing retained` |
| pool | `quarantined=1 createQuarantineFailures=1` | no quarantine |

The same server failure no longer manufactures a permanent quarantine. This is a **failed
connection** and is **not** presented as live CM validation.

**C. Provider installed is not the same as runtime ready.** A core-only build packages
the adapter and its ServiceLoader descriptor even with no vendor JARs, so discovery
reported `AVAILABLE` while `sessionFactory()` later found `DKDatastoreICM` missing. This
is the defect class that once hid a completely unreachable adapter behind a message that
looked like legitimate core-only mode.

`CmAdapterProvider.Readiness` is a **cheap, local, no-network, no-connection** probe with
a **fail-closed default** (a provider that does not report readiness is NOT ready), and
the registry's activation verdict now requires it: an installed adapter whose SDK is
absent reports `providerInstalled=true` with activation **UNAVAILABLE**. Verified: with
no SDK, `--check-repository` exits **4** through the adapter-unavailable path instead of
1 through activation-failure; auto-activation refuses with exit 4; a **half-visible SDK**
(`DKDatastoreICM` present with a missing superclass) still yields the fixed sanitized
reason with **zero escaping** `NoClassDefFoundError`/`LinkageError`; a real SDK class
path reports ready. A tripwire proved the probe does **not** initialise the vendor class
while activation does.

**D. Classification rules come from the real runtime configuration.** The adapter
independently reopened the DEFAULT `application.properties`, so a runtime started with a
custom `--config` could display business classifications that differed from the rules
Main had loaded and validated.

`ClassificationRules` is now loaded **once** by the core and passed through
`CmAdapterSettings`, and **`src/ibm` reads no configuration at all** - verified by grep
(`AppPaths`/`AppConfig`/`configurationFile`/`ClassificationRules.load`: NONE in
`src/ibm/java`) and by a new source guard with a planted positive control. Verified at app
level: a custom config with a distinctive rule reaches
`ItemTypeInfo.businessClassification` through the production provider, and the fallback
label comes only from the rule set the core supplied.

**E. Backend-unusable is now structural.** `IbmCmFailure.backendUnusable` existed but
several read paths raised an unusable failure while leaving the session healthy, so
try-with-resources returned it to the idle pool for reuse.

The read wrappers now own **both** the classification and the marking, so a caller cannot
classify without marking: `DKNotExist` is the one explicit benign control; every other
vendor failure retires the session; an unexpected `RuntimeException` retires it; an
`Error` is marked **before** it propagates; an `InterruptedException` restores the flag
and follows the conservative rule; and a wrong or absent vendor type goes through
`requireIcmType`, which marks first. Verified adversarially for every case, with the
decisive check being that a **subsequent borrow received a DIFFERENT session** - and that
`DKNotExist` left the session reusable (the same session came back).

**F. No unmapped enum code masquerades as an IBM number.**
`RetentionPolicyInfo` documented its numeric enum-code fields as "exactly what the server
returned" while emitting `-1`, because the SDK exposes enum constants with no recoverable
numeric mapping (`javap` shows those classes carry no numeric code field at all).
**Retention numeric-code representation: the two fields are now nullable `Integer`, with
JSON `null` meaning "no numeric CM code has been established"; negative inputs normalise
to `null`, so a stale `-1` cannot become a number; and the readable field still carries
IBM's certain constant name (`UNKNOWN(FIXED_TIME)`, `AUTO_DELETE`).** No enum ordinal is
used as a code anywhere in `src`. `IbmEnumNames.UNMAPPED_CODE` is deleted - it had no
remaining production caller, and its normalise-to-null rule would have hidden a missed
call site from the compiler.

### Two further defects found while integrating, both invisible to stub-only CI

1. **Four committed suites were never registered in `SelfTest.TEST_CLASSES` and had
   never executed a single assertion** - `ProviderDiscoveryTest`,
   `IbmSourceReadOnlyGuardTest`, `CoreIbmIsolationTest`, `RouterActionGuardTest`. The
   build reported green throughout. `IbmSourceReadOnlyGuardTest` is the Java half of the
   read-only guarantee, so for a whole goal the project believed a guard was enforcing
   that was not running at all. `SelfTest` now enumerates its own package and **fails**
   when a class that would have run is missing, proven with a planted unregistered suite
   and confirmed to produce no false positive against the nine legitimate helpers. Core
   registration therefore moved **220 -> 252** tests; of that, 32 tests were pre-existing
   assertions that are only now actually enforced.
2. **`IbmSessionPoisoningTest` anonymously implemented the real vendor interfaces**, so it
   compiled against the deliberately narrower stubs and could not compile against the real
   SDK - a blind spot no stub-only CI run could ever see. The fix builds foreign vendor
   types as a `java.lang.reflect.Proxy`, which names no interface member and is identical
   under both class paths, and a new guard refuses hand-written anonymous vendor types
   with a planted positive control.

### Tests actually run, with results

On **Linux with JDK 17.0.20.1**, against a clean copy of the pushed tree, running CI's own
sequence: `build.sh` **exit 0** (core **252/0**, IBM **41/0**, jar packaged);
`tests/selftest.sh` **exit 0**; `tests/shell/run.sh` **exit 0** (all four committed tests,
including the Linux-only `/proc` argv test); `bin/doctor.sh` **exit 0**.

**IBM suite against the committed stubs:** 41/0, compiled with `--release 17 -Xlint:all`.

**Real IBM SDK compile, reported separately as required:** `./build.sh --require-ibm`
**exit 0** with the real CM 8.7 jars staged, compiling **17 adapter sources and 10 test
sources** against `cmbicmsdk81.jar` (8.7.00.400.44) with `-Xlint:all` clean. Only this is
SDK validation; the stub compile is a compile check and is never described as validation.

**Mutation/control evidence:** section A's physical-bound control (2 vs 180, above);
section E's per-failure-class retirement with the different-session proof; section B's
five verdict combinations with ordered call logs including the tripwire vendor class.

**Independent verification:** the verifier wrote none of the production changes, bound its
evidence to `4b810b3` by SHA256 over all 135 files under `src/`, and ran 123 checks with
0 failures across sections A, B, C, D, E and F. It also confirmed preservation
independently: the optional IBM set compiles against both stubs and the real SDK; core has
no `com.ibm` type; three planted guard violations refused; **0 vendor JARs tracked**; the
POST action guard intact; the Goal 01C latch code unchanged; and the four updated Goal 01
assertions encode the new contract.

### Live CM status - honest

**No live CM server is reachable from this machine, so no live read-only CM validation has
been performed, in this goal or any earlier one.** The real-SDK run above is a genuine
**failed connection** to an unreachable SSID, useful lifecycle evidence and labelled as
such. It is not live CM validation and is never described as it.

### GitHub Actions - both events, same SHA

| Commit | push run | pull_request run |
| --- | --- | --- |
| `4b810b3` (implementation) | `36527200713` **success** | `36527204017` **success** |

Both verified through the GitHub API to have `headSha` equal to the commit above.

### Unresolved risks

1. **No live IBM CM validation** - the largest gap, a data gap rather than a code gap.
2. **A failing `destroy()` cannot be forced against a real server**, so that case is proven
   with a fake handle and a tripwire vendor class only.
3. **Section B's real-SDK PROVEN_CLEAN verdict is inferred** from an empty destroy/
   disconnect diagnostic plus "nothing retained", because `--check-repository` does not
   print pool counters for a failed activation.
4. **Sections D and F were exercised at app level and through the production provider and
   JSON renderer directly**, not over HTTP, because those routes need an activated
   repository.
5. **`ResourceFactory.isHealthy` has a permissive default** (`resource != null`), so a
   composition root that forgets to override it would keep a session already marked
   unusable. The production wiring is correct; the default is a trap worth knowing.
6. **The classification path's private mapping helper** (`CmMetadataService.classificationOf`)
   remains source-verified rather than unit-verified, because it needs a vendor
   `ItemTypeDef`; the rules themselves are covered by `ClassificationRulesTest`.
7. **The SDK needs its own logging configuration**: a real-SDK run emits vendor
   `NoClassDefFoundError`/attention lines for `log4j-core`/`cmblogconfig.properties` on
   stderr because those SDK runtime files are not on the class path. It does not affect
   compilation, tests or the lifecycle verdict, but it means the SDK is not fully
   configured for production logging here.

### Architecture decisions and goal state

No architecture rule was changed. Goal 02A is a correction pass: it **narrows** two
safety defaults (generic create failure, backend-unusable marking), **corrects** one
over-pessimistic rule (IBM cleanup proof) with IBM's documentation cited at the site, and
**removes** two misleading surfaces (installed-vs-ready, unmapped numeric codes).

**Next goal: NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.** Do not execute Goal 03.
Do not merge PR #1.

### Resume / review instruction

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture.
Fetch and fast-forward to the current remote state. Read STATUS.md, ARCHITECTURE.md,
SECURITY.md, harness/MASTER_GOAL.md, harness/GOAL_02A_IBM_ADAPTER_SEMANTICS_HARDENING.md
and harness/GOAL_02_IMPLEMENTATION_SPEC.md completely. Goals 01 through 01C are accepted;
Goal 02 is reviewed with changes required; Goal 02A is executed, pushed and green on both
Actions events. REVIEW Goal 02A before approving anything further - do not re-execute it
and do not execute Goal 03. Two things to carry forward: no live IBM CM validation has
been performed because no CM server is reachable from this machine; and this repository is
developed on Windows, where git does not record the executable bit, so validate on Linux
with JDK 17 before trusting a CI-touching change."

## Goal 02 review record (superseded by the Goal 02A record above)


## Goal 02 external review verdict

**SUBSTANTIAL IMPLEMENTATION ACCEPTED, BUT CHANGES ARE REQUIRED BEFORE JDBC GOAL 03.**

The review verified the pushed tree and exact-SHA CI.

Reviewed SHA:

`87ffeb8e6de9b5ab6577353a7c54a0c6e718dead`

GitHub Actions:
- push run `36501185151`: **success**
- pull_request run `36501190778`: **success**

The real CM 8.7 SDK compile reported by the handoff is useful evidence, and the optional-source-set / stub
architecture is accepted. No live CM server success was claimed.

## Accepted Goal 02 work

- IBM SDK types are isolated under the optional IBM source set.
- core compilation remains IBM-free.
- test-only IBM stubs are not packaged.
- real SDK strict compile path exists.
- ServiceLoader provider boundary exists.
- no emergency/ad-hoc DKDatastoreICM path was found.
- ProductionRepositoryContextFactory creates the hard-bounded pool before services and carries a cleanup
  context on post-allocation activation failure.
- CM pool health is a local volatile read rather than a server round trip under the pool lock.
- ItemType and retention services use immutable IBM-free DTOs and pooled Lease scope.
- ItemType listing was corrected against the real SDK to listEntityNames(DK_ICM_USER_ITEM_TYPES) plus
  retrieveEntity(name).
- read-only interfaces/routes and source/stub guards are in place.
- repository selection is authenticated POST with an application-specific action header.
- metadata cache is per-context.
- smoke command uses the production provider -> RepositoryManager -> BoundedPool path.
- no vendor JAR is tracked.

## Blocking findings for Goal 02A (ALL RESOLVED in `4b810b3` - kept as the review's original wording)

> **Resolved.** Each finding below is addressed by the Goal 02A execution record above: (1) the
> generic create-failure default now quarantines unless the factory explicitly proves
> `PROVEN_CLEAN`; (2) the failed-connect cleanup now skips a meaningless disconnect after a connect
> that never completed, always destroys, and treats a successful destroy as the final cleanup proof;
> (3) provider-installed is separated from runtime-ready, with a fail-closed local readiness probe;
> (4) `ClassificationRules` is loaded once by the core and passed in, and `src/ibm` reads no
> configuration; (5) `backendUnusable` now marks the borrowed session before the lease returns, for
> every failure class including `Error` and wrong vendor types. The text is retained unchanged so the
> review can confirm each item was actually addressed rather than reworded.

### 1. Unknown create failure still releases capacity

BoundedPool quarantines only explicit CreationFailure(UNPROVEN). A plain exception/error still releases
the reservation. That makes the physical-bound guarantee depend on every future external-resource factory
remembering the special type.

Goal 02A changes the generic default: only explicit PROVEN_CLEAN may release a failed creation;
unknown/untyped failure quarantines.

### 2. IBM cleanup verdict is too pessimistic in the wrong place

After a failed connect the production factory unconditionally disconnects and requires disconnect AND
destroy to succeed. IBM documents isConnected() as true only after connect completed successfully and
destroy() as performing datastore cleanup if needed.

Goal 02A must skip meaningless disconnect after a connect that never completed, always destroy, and use
successful destroy as the final cleanup proof. Destroy failure remains unproven/quarantined.

### 3. Provider discovery reports adapter available when SDK runtime is absent

The packaged adapter/provider exists even in a build compiled against stubs. At runtime without IBM JARs,
ServiceLoader can therefore report AVAILABLE while sessionFactory later refuses because DKDatastoreICM is
not loadable.

Goal 02A separates "provider installed" from "runtime ready to activate".

### 4. Business classification can read the wrong config

Main loads ClassificationRules from the actual AppConfig, but IbmCmAdapterProvider independently reopens
the default application.properties path. A custom --config can therefore disagree with ItemType API
classification.

Goal 02A passes the already-loaded rule set through core settings/context and removes adapter config parsing.

### 5. backendUnusable does not always poison the session

Several IBM read failure paths can raise an unusable failure while leaving IbmCmSession.healthy true.
A try-with-resources lease then returns that session to the idle pool.

Goal 02A makes "backend unusable => mark unusable before lease return" structural, including unexpected
RuntimeException/Error and wrong vendor object/type.

## IBM documentation evidence used in review

IBM Content Manager 8.7 DKDatastoreICM documentation states:
- isConnected() is true when connect() was called and completed successfully; it is not a communication
  liveness check.
- destroy() destroys the datastore object and performs datastore cleanup if needed.

The normal sample lifecycle is create -> connect -> disconnect -> destroy.

References:
- https://www.ibm.com/docs/en/content-manager/8.7.0?topic=comibmmmsdkserver-dkdatastoreicm
- https://www.ibm.com/docs/en/content-manager/8.7.0?topic=servers-establishing-connection

## Non-blocking correctness finding (RESOLVED in `4b810b3`)

> **Resolved.** Unmapped numeric enum codes are now represented as a nullable `Integer` with JSON
> `null`, never `-1`; negative inputs normalise to `null`; the readable field still carries IBM's
> certain constant name; and `IbmEnumNames.UNMAPPED_CODE` is deleted. Original wording retained below.

RetentionPolicyInfo currently documents its numeric enum-code fields as exact server values while the
implementation intentionally emits -1 because those numeric mappings were not established. Goal 02A must
make the unavailable-code semantics explicit and must not imply -1 came from IBM CM.

## Exact next goal

**NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.** Do not execute Goal 03.

Review first:

1. `harness/MASTER_GOAL.md`
2. `harness/GOAL_02A_IBM_ADAPTER_SEMANTICS_HARDENING.md` and the Goal 02A execution record above

## Resume instruction

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture.
Fetch and fast-forward to the current remote state. Read STATUS.md, ARCHITECTURE.md, SECURITY.md,
harness/MASTER_GOAL.md, harness/GOAL_02A_IBM_ADAPTER_SEMANTICS_HARDENING.md and
harness/GOAL_02_IMPLEMENTATION_SPEC.md completely. Goals 01 through 01C are accepted; Goal 02 was
reviewed with changes required; Goal 02A is executed, pushed and green on both Actions events for the
same SHA. REVIEW Goal 02A before approving anything further - do not re-execute it and do not execute
Goal 03. Preserve the accepted optional-SDK/read-only/API architecture and every Goal 01A-01C
hard-bound, fail-closed, Linux lifecycle and security invariant. Carry forward two facts: no live IBM
CM validation has been performed, because no CM server is reachable from the execution host; and this
repository is developed on Windows, where git does not record the executable bit, so validate on Linux
with JDK 17 before trusting a CI-touching change."

## Mandatory checkpoint rule

At the end of every approved goal record:
- date/time;
- branch;
- last completed work commit described by STATUS;
- exact work completed;
- tests actually run/results;
- stub compile status;
- real IBM SDK compile status;
- live CM smoke status or explicit absence;
- Actions run IDs/results;
- unresolved risks;
- next goal status;
- final local/remote HEAD in the handoff report after push.
