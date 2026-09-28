# DeepSeek Harness Plan

DeepSeek is the implementation executor. Architecture and next-goal approval are owned by the architecture-review process.

## Operating model

Use large goals, not tiny ping-pong prompts. Each approved goal should ask the lead agent to delegate independent research/review/test tasks to subagents when AgentTeams is available.

The lead agent remains responsible for integration, compilation, tests, documentation and a coherent commit.

Goals after the currently approved goal are planning drafts only. They exist to preserve likely future work, but they must be re-evaluated after the preceding implementation is reviewed.

## Mandatory rules for every goal

1. Read README.md, ARCHITECTURE.md, REQUIREMENTS.md, DATA_MODEL.md, SECURITY.md and STATUS.md first.
2. Execute only a goal explicitly marked APPROVED.
3. Do not change non-negotiable architecture rules without explicit user approval.
4. Do not add Maven/Gradle/Spring/containers/microservices/CDN.
5. Do not invent IBM CM schema semantics.
6. Do not enable Versions/Parts without the documented research gate.
7. Keep V1/V2 repository access read-only.
8. Do not commit proprietary JARs or secrets.
9. Run build/tests before declaring success.
10. Update STATUS.md at the end.
11. Commit one coherent goal result with a clear message.
12. After completing the approved goal, set the next goal to NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED and stop.

## Architecture/code review loop

After each completed approved goal, the result is reviewed outside Harness for:

- architecture drift
- correctness and maintainability
- concurrency/resource leaks
- security regressions
- unnecessary dependencies/complexity
- test quality and failure paths
- deviations from IBM CM constraints
- technical debt introduced by the implementation

The next goal is created or approved only after that review. Existing provisional goal files may be edited, replaced, split or merged.

## Recommended subagent roles

- IBM CM API investigator
- DB2/Oracle SQL investigator
- concurrency/pool reviewer
- security reviewer
- Web UI implementer
- test/QA reviewer
- documentation/status reviewer

Subagents may inspect and propose in parallel. The lead agent decides and integrates.

See harness/MASTER_GOAL.md and goal files.
