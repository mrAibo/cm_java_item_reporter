# Project Status

> Canonical cross-session checkpoint. Read this first when continuing in another chat/model.

## Project state

- Project: CM Insight
- Repository: mrAibo/cm_java_item_reporter
- Active branch: `bootstrap/cm-insight-architecture`
- Goal 01 implementation commit: `fb42a0e55e69b551bffdcf0986714b8110dbdfb6`
- Goal 01A/01B: completed and externally reviewed through `3499129d8a851e07cd5a4cee03bdbd5d81fa224c`
- **Goal 01C implementation/work commits (the commits this file describes):**
  - `4b844bd18b48a3b09edbeab2b713fa03f9e007f9` - quiescent repository shutdown before switching
  - `dd6a34cb7ba0dacb6f0e1c36bf453c40349e880a` - review-finding fixes (latch, lock scope, states)
- Stage: **Goal 01C executed, adversarially reviewed and pushed; awaiting architecture review before Goal 02**
- Runtime target: Java 17 LTS / OpenJDK-compatible
- Build/deployment: javac + jar + bash; single JVM
- Product safety mode: read-only V1/V2
- Current approved goal: `harness/GOAL_01C_QUIESCENT_REPOSITORY_SHUTDOWN.md` (executed)
- Goals 02-05: PROVISIONAL; do not execute
- Next goal: **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

## Checkpoint protocol

A commit cannot truthfully contain its own SHA. Therefore:

- this file records the last completed **implementation/work** commits it describes, above;
- the authoritative current branch head is read from Git:
  `git rev-parse HEAD` / `git ls-remote origin refs/heads/bootstrap/cm-insight-architecture`;
- the final handoff report records the exact local and verified remote HEAD after the STATUS
  commit itself has been pushed;
- no SHA field in this file is a placeholder or describes "the commit that follows".

## Goal 01C: the defect that was closed

`BoundedPool.close()` returned as soon as the IDLE resources were released. A resource still out on a
lease is closed later, by the thread that returns it - so at the instant `close()` returned the pool was
"closed" while a physical session it owned was demonstrably alive, `quarantinedCount` was still 0 and
`closedWithUncertainResources()` said **false**. `RepositoryContext` snapshotted that as a clean close,
and `RepositoryManager` was then free to create the next context on top of a live old connection. That
is Goal 01B review finding F3, and it is broader than a late quarantine: even a lease that comes back
perfectly cleanly comes back too late.

Reproduced against the reviewed pre-goal code before the fix (probe kept out of the repository):
`factoryCalls=[alpha, beta]`, manager `ACTIVE`, alpha's leased resource still physically live. Fixed:
`factoryCalls=[alpha]`, switch refused, `manager latched = true`.

## Close-state semantics implemented

New vendor-neutral vocabulary in `src/main/java/com/mraibo/cminsight/core/`:

- `CloseState`: `NOT_CLOSED`, `CLOSING`, `CLOSED_CLEAN`, `CLOSED_UNCERTAIN` with
  `isTerminal()`, `isTerminalClean()`, `isPending()`, `refusesReuse()`;
- `CloseStateAware.closeState()`, a sibling of `CloseOutcomeAware` (which is unchanged).
- No IBM CM or JDBC type appears in either contract.

`BoundedPool`:

- `CLOSING` = close initiated AND `leased > 0 || creating > 0 || retiring > 0` - an outstanding physical
  resource, so it is neither clean nor uncertain;
- `CLOSED_UNCERTAIN` = finished with `quarantined > 0`; terminal, never improves;
- `CLOSED_CLEAN` = close initiated, every one of leased/creating/retiring/quarantined zero;
- a physically outstanding resource is never reported `CLOSED_CLEAN`, and pending is never called
  uncertainty (an outstanding lease is recoverable, a quarantine is not).
- `closedWithUncertainResources()` is now `closeState() == CLOSED_UNCERTAIN`. **Deliberate narrowing:**
  an OPEN pool with a quarantined slot used to answer true and now answers false, because the question is
  tied to the last shutdown. Audited: no in-repo caller depends on the old reading; `closeState()` is the
  honest question for an in-service pool. Recorded here rather than changed silently.
- `awaitQuiescence()` is deliberately UNCHANGED: it answers "has everything settled", which an open pool
  may legitimately answer true to, and it has zero production callers. The shutdown question is
  `closeState()`.

`RepositoryContext`:

- close outcome is DERIVED on every read, never frozen: `closeState()` walks the owned close-aware
  resources plus a monotone uncertainty latch. No final clean verdict is published by `close()`;
- uncertainty is a real LATCH: uncertainty seen by ANY read - recorded at close time, reported now, or
  observed in between - is permanent. This is deliberately stronger than trusting every implementation to
  be monotone, because a resource whose state flaps would otherwise be refused once and allowed through
  on the retry: a fail-open window across retries;
- `uncertainCloseReports()` is derived the same way, keeping resource REFERENCES so a slot that only
  becomes quarantined after `close()` returned still produces value-free reason text;
- diagnostics remain value-free: counts and sources only.

## Retry behaviour after pending clean completion and after quarantine

`RepositoryManager` retains a previous context it unpublished but could not prove released, in
`unpublishedClosing`, and re-checks it on EVERY switch before the factory is reached:

| Retained context state | Result |
| --- | --- |
| `CLOSING` (lease/creation/retirement outstanding) | refused, `Refusal.PENDING`, state `FAILED` - recoverable, retry once released |
| `CLOSED_UNCERTAIN` (quarantine) | refused, `Refusal.UNCERTAIN`, state `FAILED` - permanent while the process lives |
| `CLOSED_CLEAN` | latch released, the switch proceeds |

- A failed switch can no longer be forgotten merely because the context was unpublished, and the refusal
  is repeatable: the same latch is re-checked on every attempt, so "refused once" cannot become "allowed
  next time".
- `releaseLatch()` is the single place a previous repository is forgotten and it guards on the state, so
  a caller that gets it wrong downgrades to "retained", never to "lost".
- No automatic emergency reset and no force-open path exists.
- `deactivate()` sweeps a retained context instead of reporting `NONE` over a live resource; `close()`
  stays best-effort and terminal.
- `statusSnapshot()` gained `closingRepositoryId`, `closingState` and `refusal`.
- No bounded drain wait was added: the non-blocking "still draining; retry later" design the goal
  explicitly permits was chosen, so there is no new timeout to tune and no unbounded wait.

## Deterministic tests and results

New committed suite `src/test/java/com/mraibo/cminsight/test/RepositorySwitchQuiescenceTest.java`
(9 tests). Each owns a REAL `BoundedPool<FakeResource>` inside repository context A and drives the
interleaving with latches from `FakePoolFactory` - no sleeps to create a race, no scheduler luck:

- E1 `anOutstandingLeaseRefusesTheSwitchAndEveryRetry`
- E2 `aLateCleanReturnPermitsOnlyALaterSafeSwitch` (ordering evidence sampled INSIDE the factory:
  B was built while zero of A's resources were alive)
- E3 `aLateFailedReturnStaysRefusedAndCreatesNothing`
- E4a `anInFlightCreationRefusesTheSwitchUntilItIsRetired`
- E4b `aRetryCannotBypassAStillPendingPreviousContext`
- E5 `anOrdinaryContextStillClosesAndSwitchesWithoutPendingStates`
- `thePoolDistinguishesPendingFromUncertainAndFromClean`
- `anUncertaintyObservedOnceIsLatchedAndNeverRevoked`
- `deactivatingAPendingContextNeverClaimsItWasReleased`

Commands and results (JDK 17.0.20.1, offline, no bash needed for the Java suite):

- `./build.sh` (javac --release 17 -encoding UTF-8 -Xlint:all, then SelfTest, then jar):
  **Tests run: 182, failures: 0** (173 before this goal), jar packaged. Local equivalent driver used
  because MSYS `bash` cannot allocate its shared-memory object in this session's file sandbox.
- Discrimination, not assumed: against the pre-goal main classes the new suite does not compile
  (100 errors - it asks for the vocabulary this goal introduces), so a minimal probe that compiles
  against BOTH revisions was used: pre-goal `RESULT=DEFECT_REPRODUCED` (beta created while alpha's
  resource was live), fixed `RESULT=INVARIANT_HELD`.
- No existing assertion was weakened: every pre-existing test file is byte-identical to `3499129`
  except `SelfTest.java`, whose only change is one added line registering the new suite.

## Independent adversarial review

Performed against the frozen revision by a reviewer that did not write the code (verdict: **YES**, the
critical invariant holds). Held under attack: 15,625 exhaustive operation sequences (depth 6) with an
invariant checkpoint at every factory call; ~5M concurrent factory-call checkpoints under switch /
deactivate / diagnostics hammering; 280k pool state samples (176k of them `CLOSED_CLEAN`) with
concurrent borrowers and an uncertain-close poisoner - zero violations, and the pre-goal code
demonstrably violated. Findings raised were fixed in `dd6a34c`: the latch gap (F2, a real fail-open
window across retries), the lock-scope wedge (F1, measured blocking), inconsistent refusal states (F3),
a non-atomic diagnostics snapshot (F4), a stuck `INITIALIZING` after an `Error` (F6), the undocumented
factory leak obligation (F7), an unused import (F8) and two uncovered routes (F9).

## CI status

Exact-SHA GitHub Actions (`bootstrap-test`, both events) - all green:

| Commits | push run | pull_request run |
| --- | --- | --- |
| `4b844bd` (initial Goal 01C) | `36477237772` success | `36477243761` success |
| `dd6a34c` (review fixes) | `36479626191` success | `36479635585` success |

Each run executed the full job: script permissions, shell syntax, `--help` smoke tests, `./build.sh`
(compile + SelfTest + package), the committed shell regression suite (`tests/shell/run.sh`), `doctor`,
and the bounded 3-cycle start/status/stop lifecycle reliability step with the exact health marker.
Both the push and the pull_request event are required; a single green event is not CI green.

## Unresolved core lifecycle risks

1. **Resource-controlled blocking inside the fail-closed decision (partially mitigated).** The retained
   context's state is now sampled OUTSIDE the switch lock and only revalidated under it, so a resource
   that blocks in `closeState()` delays one caller instead of wedging the manager - but it still delays
   that caller without a bound, because no timeout was added. `CloseStateAware` and
   `ResourceFactory.isHealthy` now state the non-blocking/cheap obligation explicitly. The CM/JDBC
   adapters (Goal 02) must honour it; a bounded probe policy is a later decision, not this goal's.
2. **`CLOSED_CLEAN` is a proof only while factories keep their side of the contract.** A factory that
   opens a physical resource and then throws leaks it invisibly to the pool, which would still report
   `CLOSED_CLEAN`. The obligation is now documented on `ResourceFactory`; enforcing it is not possible
   from inside the pool.
3. **Quarantine is still unrecoverable in-process** (by design): a `CLOSED_UNCERTAIN` previous repository
   refuses every later switch until the process is replaced. Recovering that capacity is an operational
   decision, not a code path - and there is deliberately no emergency reset.
4. **`deactivate()` publishes `FAILED` (not `NONE`) for a pending or uncertain close.** Honest, but a UI
   that treats `FAILED` as "the repository is broken" would over-report; Goal 04 should map states to
   messages.

## IBM / DB live status

**No IBM CM, DB2 or Oracle live validation has been performed.** Vendor JARs are absent. Goal 01C is
vendor-neutral core work and claims no live validation.

## Exact next goal

**NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.**

Do not execute `harness/GOAL_02_IBM_CM_RETENTION.md` or any other provisional goal. Review the Goal 01C
commits first; the architecture owner decides whether to approve, replace, split, merge or rewrite the
next goal.

## Resume instruction

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture. Fetch
and fast-forward to the current remote state. Read STATUS.md, harness/MASTER_GOAL.md and
harness/GOAL_01C_QUIESCENT_REPOSITORY_SHUTDOWN.md. Goal 01C is executed, pushed and green on both
Actions events; do not re-execute it and do not execute Goal 02. Wait for the architecture review of the
Goal 01C commits, then implement only the next goal the architecture owner approves."

## Mandatory checkpoint rule

At the end of every approved goal record:

- date/time;
- branch;
- last completed implementation/work commit described by STATUS;
- exact work completed;
- tests actually run/results;
- Actions run IDs/results where required;
- live IBM/DB tests actually run or explicitly not run;
- unresolved risks;
- architecture changes only when approved;
- next goal status;
- final local/remote HEAD in the handoff report after push.
