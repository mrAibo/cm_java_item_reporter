# MASTER GOAL - CM Insight execution contract

You are the lead implementation agent for CM Insight.

Your job is to execute the currently assigned **APPROVED** goal to completion, not redesign the product and not continue automatically into later draft goals.

## Goal approval gate

Only a goal explicitly marked APPROVED in its goal file and/or STATUS.md may be executed.

After completing the approved goal:

1. integrate and test the result;
2. update STATUS.md;
3. set the next goal state to **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**;
4. stop.

Do not automatically begin the next numbered goal, even if a draft file already exists for it. The architecture owner will review the completed implementation and either approve, replace, split, merge or rewrite the next goal.

## Before doing any work

Read completely:

- README.md
- VISION.md
- REQUIREMENTS.md
- ARCHITECTURE.md
- DATA_MODEL.md
- SECURITY.md
- IMPLEMENTATION_PLAN.md
- DEEPSEEK_HARNESS_PLAN.md
- STATUS.md
- the assigned GOAL_*.md file

Inspect the repository and recent git history before changing code.

## Architecture is authoritative

Do not silently change these decisions:

- one JVM, modular monolith
- Java 17
- no Maven/Gradle
- no Spring
- no containers
- no microservices
- no external CDN/runtime Internet dependency
- V1/V2 read-only
- DB2 and Oracle
- IBM Java API for CM metadata/retention
- JDBC fast path for large aggregate statistics
- hard-bounded connection pools
- no emergency connections
- Versions/Parts disabled until independently verified
- retention administration disabled until a later explicit goal

If a goal appears to require violating one of these, stop that part, document the conflict in STATUS.md and complete every safe part you can.

## AgentTeams / subagents

Use subagents when the Harness supports them.

The lead agent owns all final decisions and integration. Subagents should work on separable tracks such as:

- IBM CM API inspection
- DB2/Oracle SQL/schema inspection
- connection/concurrency review
- security review
- Web/UI implementation
- test and failure-path review
- documentation consistency review

Do not let several subagents independently rewrite the same core files. Give them clear ownership or read-only review tasks. Integrate centrally.

## Reuse from example repositories

The following repositories are idea/code sources, not specifications:

- mrAibo/CM_Item_Reporter
- mrAibo/CM_Migrator
- mrAibo/CM_retention

Reuse working logic when it is compatible with ARCHITECTURE.md. Prefer adapting proven CM_retention API calls and CM_Migrator connection safety patterns over re-inventing them, but remove migration-specific behavior such as emergency connections and ad-hoc discovery connections.

## Definition of done for every approved goal

A goal is not complete until:

1. requested functionality is integrated, not just stubbed;
2. failure paths are handled;
3. no architecture rule was silently changed;
4. build.sh succeeds in the available environment;
5. relevant tests/self-tests succeed;
6. scripts remain usable;
7. no secret/proprietary binary is committed;
8. documentation is updated when behavior changes;
9. STATUS.md is updated with exact state and architecture-review handoff;
10. changes are committed as one coherent goal commit or a small reviewable series.

## STATUS.md is mandatory

At the end, update STATUS.md with:

- date/time
- branch
- current HEAD before the status update or final goal commit reference
- exactly what was implemented
- files/areas materially changed
- build/test commands and results
- IBM live environment tests performed or explicitly not performed
- unresolved failures/risks
- architecture decisions changed only if user-approved
- next goal status: NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED
- copy/paste resume/review instruction for another session

Never leave STATUS.md claiming a test passed if it was not run.
Never proceed to a provisional goal without explicit approval after architecture review.
