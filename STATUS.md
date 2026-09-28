# Project Status

> Canonical cross-session checkpoint. Read this first when continuing in another chat/model.

## Project state

- Project: CM Insight
- Repository: mrAibo/cm_java_item_reporter
- Active branch: `bootstrap/cm-insight-architecture`
- Goal 01 implementation commit: `fb42a0e55e69b551bffdcf0986714b8110dbdfb6`
- Goal 01A review-hardening: completed
- Goal 01B implementation/reviewed remote HEAD: `2a8bf32d45444376c0d4a0247e98300e7e4e7247`
- Stage: **Goal 01B externally reviewed; Goal 01C quiescent shutdown correction is APPROVED**
- Runtime target: Java 17 LTS / OpenJDK-compatible
- Build/deployment: javac + jar + bash; single JVM
- Product safety mode: read-only V1/V2
- Current approved goal: `harness/GOAL_01C_QUIESCENT_REPOSITORY_SHUTDOWN.md`
- Goals 02-05: PROVISIONAL; do not execute
- Next goal after 01C: **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

## Goal 01B external review verdict

**MOST GOAL 01B WORK IS ACCEPTED. ONE CORE LIFECYCLE BLOCKER REMAINS BEFORE IBM CM/JDBC INTEGRATION.**

Accepted:

- generic CloseOutcomeAware propagation for quarantine present at close-observation time;
- Linux startup identity race correction;
- process-instance corroboration using /proc/<pid>/stat starttime;
- IPv4-mapped IPv6 socket ownership normalization without weakening web-exposure policy;
- committed shell regression suite;
- exact-marker/process-ownership safety;
- Actions gate requiring both push and pull_request events.

Verified exact-SHA Actions for `2a8bf32d45444376c0d4a0247e98300e7e4e7247`:

- push run `36474039161`: **success**
- pull_request run `36474048951`: **success**

## Remaining blocker: repository switch can overlap an outstanding old lease

Goal 01B finding F3 is a core lifecycle defect, not a Goal 02 adapter decision.

Current BoundedPool.close():

- marks the pool closed;
- closes idle resources;
- returns while leased/creating/retiring resources may still be physically live.

Current BoundedPool.closedWithUncertainResources() reports only `quarantinedCount > 0`.

Therefore RepositoryContext can snapshot a clean close while an old physical resource is still leased.
RepositoryManager can then create the next RepositoryContext before the old lease returns.

Even if that late lease eventually closes successfully, the switch already overlapped physical
connections from old and new repository contexts. If it later fails close, the context's cached clean
snapshot is also stale.

This violates the hard-bounded/fail-closed repository lifecycle.

Goal 01C must establish a terminal close state, keep pending/late outcomes visible, and ensure failed
switch retries cannot forget the still-closing previous context.

## CI status

Current reviewed Goal 01B exact SHA has both event runs green. Preserve that state.

## IBM / DB live status

No IBM CM, DB2 or Oracle live validation has been performed. Vendor JARs are absent. Goal 01C remains
vendor-neutral core work and must not claim live validation.

## Exact next goal

Execute only:

1. `harness/MASTER_GOAL.md`
2. `harness/GOAL_01C_QUIESCENT_REPOSITORY_SHUTDOWN.md`

Do not execute Goal 02.

## Resume instruction

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture. Fetch and fast-forward to the current remote state. Read STATUS.md, harness/MASTER_GOAL.md and harness/GOAL_01C_QUIESCENT_REPOSITORY_SHUTDOWN.md. Execute only Goal 01C. Preserve all accepted Goal 01B Linux/process/socket/security behavior. At completion run all required tests, commit coherently, push the branch yourself, verify local and remote HEAD equality, require both push and pull_request Actions runs for the same final SHA to pass, update STATUS.md using the non-self-referential checkpoint protocol, set the next goal to NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED, do not merge PR #1, do not execute Goal 02, and stop."

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
