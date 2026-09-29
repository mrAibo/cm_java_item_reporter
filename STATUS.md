# Project Status

> Canonical cross-session checkpoint. Read this first when continuing in another chat/model.

## Project state

- Project: CM Insight
- Repository: mrAibo/cm_java_item_reporter
- Active branch: `bootstrap/cm-insight-architecture`
- Goal 01 / 01A / 01B / 01C: accepted core foundation
- Goal 02 reviewed remote HEAD: `87ffeb8e6de9b5ab6577353a7c54a0c6e718dead`
- Goal 02 implementation checkpoint recorded by the execution handoff: `818cdcf45ffff02383915958424e5403ae653e00`
- Stage: **Goal 02 externally reviewed; Goal 02A IBM adapter semantics hardening is APPROVED**
- Current approved goal: `harness/GOAL_02A_IBM_ADAPTER_SEMANTICS_HARDENING.md`
- Goal 03-05: PROVISIONAL; do not execute
- Next goal after 02A: **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

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

## Blocking findings for Goal 02A

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

## Non-blocking correctness finding

RetentionPolicyInfo currently documents its numeric enum-code fields as exact server values while the
implementation intentionally emits -1 because those numeric mappings were not established. Goal 02A must
make the unavailable-code semantics explicit and must not imply -1 came from IBM CM.

## Exact next goal

Execute only:

1. `harness/MASTER_GOAL.md`
2. `harness/GOAL_02A_IBM_ADAPTER_SEMANTICS_HARDENING.md`

Do not execute Goal 03.

## Resume instruction

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture. Fetch and fast-forward to the current remote review checkpoint, then read STATUS.md, harness/MASTER_GOAL.md and harness/GOAL_02A_IBM_ADAPTER_SEMANTICS_HARDENING.md completely. Execute only Goal 02A. Preserve the accepted Goal 02 optional-SDK/read-only/API architecture. Do not implement JDBC analytics or Goal 03. At completion run core, stub, shell and real-SDK validation when available; update STATUS.md; commit coherently; push the branch yourself; verify local and remote HEAD equality; require both exact-SHA Actions events green; set the next goal to NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED; do not merge PR #1; and stop."

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
