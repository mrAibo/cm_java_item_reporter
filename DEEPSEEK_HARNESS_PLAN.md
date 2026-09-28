# DeepSeek Harness Plan

DeepSeek is the implementation executor. Architecture is owned by this repository's contract documents.

## Operating model

Use large goals, not tiny ping-pong prompts. Each goal should ask the lead agent to delegate independent research/review/test tasks to subagents when AgentTeams is available.

The lead agent remains responsible for integration, compilation, tests, documentation and a coherent commit.

## Mandatory rules for every goal

1. Read README.md, ARCHITECTURE.md, REQUIREMENTS.md, DATA_MODEL.md, SECURITY.md and STATUS.md first.
2. Do not change non-negotiable architecture rules without explicit user approval.
3. Do not add Maven/Gradle/Spring/containers/microservices/CDN.
4. Do not invent IBM CM schema semantics.
5. Do not enable Versions/Parts without the documented research gate.
6. Keep V1/V2 repository access read-only.
7. Do not commit proprietary JARs or secrets.
8. Run build/tests before declaring success.
9. Update STATUS.md at the end.
10. Commit one coherent goal result with a clear message.

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
