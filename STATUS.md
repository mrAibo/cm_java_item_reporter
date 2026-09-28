# Project Status

> Canonical cross-session checkpoint. Read this first when continuing in another chat/model. Update it at the end of every substantial goal.

## Project state

- Project: CM Insight
- Repository: mrAibo/cm_java_item_reporter
- Active branch: bootstrap/cm-insight-architecture
- Stage: Bootstrap complete; Harness Goal 01 approved for execution
- Runtime target: Java 17 LTS, OpenJDK-compatible
- Build/deployment: javac + jar + bash; single JVM
- Product safety mode: read-only V1/V2
- Goal policy: dynamic; only the current explicitly approved goal may execute
- Current approved goal: harness/GOAL_01_CORE_RUNTIME.md
- Goals 02-05: provisional drafts; architecture review required before any is approved
- Last completed bootstrap checkpoint before this policy update: b8bd42718b6b64161e094aa5fc68257f376d1af5
- On resume confirm actual HEAD with git log -1.

## Completed architecture work

- Reviewed CM_Item_Reporter for ItemType statistics and UI ideas.
- Reviewed CM_Migrator for DKDatastoreICM connection reuse, pooling, concurrency, lifecycle and diagnostics.
- Reviewed CM_retention for working ItemType/retention API usage and DB2/Oracle physical-root mapping.
- Chosen product direction: CM Insight, a modular CM administration/analytics console rather than a narrow reporter rewrite.
- Chosen Java 17/OpenJDK-compatible runtime.
- Fixed no-Maven/no-Gradle/no-Spring/no-container/no-microservice constraint.
- Fixed DB2 + Oracle requirement.
- Fixed hard-bounded connection policy; no emergency connections.
- Fixed IBM Java API for metadata/retention plus JDBC fast path for heavy aggregates.
- Fixed one selected active repository context at a time.
- Fixed retention viewer for V1 and retention administration as a later disabled-by-default module.
- Fixed configurable classification rules instead of hard-coded SAP/NON-SAP.
- Fixed Versions and Parts as research gates: no guessed SQL.
- Fixed dynamic-goal workflow: after each approved Harness goal, stop for architecture/code review before approving the next goal.

## Bootstrap files created

Architecture/documentation:

- README.md
- VISION.md
- REQUIREMENTS.md
- ARCHITECTURE.md
- DATA_MODEL.md
- SECURITY.md
- IMPLEMENTATION_PLAN.md
- DEEPSEEK_HARNESS_PLAN.md
- STATUS.md

Configuration examples:

- conf/application.properties.example
- conf/profiles/example-db2.properties.example
- conf/profiles/example-oracle.properties.example
- conf/classifications.properties.example
- lib/README.md

Operational lifecycle:

- build.sh
- bin/compile.sh
- bin/cm-insight
- bin/start.sh
- bin/stop.sh
- bin/restart.sh
- bin/status.sh
- bin/doctor.sh
- bin/clean.sh
- tests/selftest.sh

Java bootstrap:

- AppConfig and repository/domain DTOs
- generic hard-bounded BoundedPool + Lease + metrics
- IBM-independent CmSession boundary
- DB2/Oracle dialect skeletons
- ItemType/Statistics/Retention DTOs
- RepositoryContext skeleton
- fail-closed web exposure policy
- Basic Auth wrapper
- embedded JDK HttpServer
- offline bootstrap page
- dependency-free SelfTest

Harness:

- harness/MASTER_GOAL.md
- GOAL_01_CORE_RUNTIME.md — APPROVED
- GOAL_02_IBM_CM_RETENTION.md — PROVISIONAL
- GOAL_03_FAST_ANALYTICS.md — PROVISIONAL
- GOAL_04_CACHE_REPORTS_UI.md — PROVISIONAL
- GOAL_05_VERSIONS_PARTS_RESEARCH.md — PROVISIONAL

CI:

- .github/workflows/bootstrap-test.yml
- Intended gate: Java 17 build -> self-test -> doctor -> start -> status/health -> stop.

## Validation status

- GitHub commits and branch creation succeeded.
- A local git clone/build could not be executed from the previous assistant container because that environment could not resolve github.com.
- The bootstrap CI workflow was committed, but no completed workflow run had been observed at the last check. Do not claim CI passed until verified.
- No IBM CM live test has been performed for this new project yet.
- No DB2/Oracle live statistics query has been performed yet.
- Proprietary IBM/Oracle JARs were intentionally not committed.

## Current limitations

- RepositoryProfileLoader and RepositoryManager are not implemented yet.
- Generic BoundedPool is a bootstrap implementation and still needs Goal 01 concurrency hardening/review.
- IBM DKDatastoreICM adapter is not implemented yet.
- Retention viewer business implementation is not implemented yet.
- JDBC pools/root resolver/statistics are not implemented yet.
- Cache/history/reports are not implemented yet.
- Current Web UI is only a bootstrap landing page.
- Versions/Parts remain deliberately unavailable until verified.

## Exact next goal

Execute only:

1. harness/MASTER_GOAL.md
2. harness/GOAL_01_CORE_RUNTIME.md

Goal 01 should use subagents for concurrency/pool review, security/config review, and independent tests/reliability review, with the lead agent integrating all changes.

After Goal 01 completes, Harness must:

- update STATUS.md with exact results;
- set next goal to **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**;
- stop;
- not execute GOAL_02 automatically.

The user will return the Goal 01 result/STATUS/commit for architecture and code review. The next goal will be approved or rewritten based on the actual implementation.

## Resume instruction for a new session

Use this prompt:

"Continue the CM Insight project in mrAibo/cm_java_item_reporter. Read STATUS.md first, then README.md, ARCHITECTURE.md, REQUIREMENTS.md, DATA_MODEL.md, SECURITY.md, IMPLEMENTATION_PLAN.md, DEEPSEEK_HARNESS_PLAN.md and harness/MASTER_GOAL.md. Confirm the current branch/HEAD and do not redo completed bootstrap work. Execute only the explicitly APPROVED goal recorded in STATUS.md. At the end, build/test what is actually available, commit coherent changes, update STATUS.md, set the next goal to NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED, and stop."

## Mandatory checkpoint rule

At the end of every substantial approved goal update this file with:

- date/time
- branch and verified HEAD/commit reference
- exact work completed
- major files changed
- commands/tests actually run and their results
- live IBM/DB tests actually run or explicitly not run
- unresolved failures and risks
- architecture changes only when user-approved
- next goal status: NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED
- a copy/paste resume/review instruction
