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
- Stage: **Goal 01C ACCEPTED; Goal 02 IBM CM read-only integration is APPROVED**
- Runtime target: Java 17 LTS / OpenJDK-compatible
- Build/deployment: javac + jar + bash; single JVM
- Product safety mode: read-only V1/V2
- Current approved goal: `harness/GOAL_02_IBM_CM_RETENTION.md`
- Goals 03-05: PROVISIONAL; do not execute
- Next goal after Goal 02: **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

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
