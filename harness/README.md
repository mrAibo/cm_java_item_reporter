# Harness Goals

These goals are intentionally large. The lead agent should use subagents for independent investigation, review and testing, then integrate the result itself.

## Approval model

Goals are **not an automatic sequence**.

- GOAL_01_CORE_RUNTIME.md — **COMPLETED / REVIEWED**
- GOAL_01A_REVIEW_HARDENING.md — **COMPLETED / REVIEWED**
- GOAL_01B_LINUX_LIFECYCLE_AND_CLOSE_PROPAGATION.md — **COMPLETED / REVIEWED**
- GOAL_01C_QUIESCENT_REPOSITORY_SHUTDOWN.md — **COMPLETED / ACCEPTED**
- GOAL_02_IBM_CM_RETENTION.md — **COMPLETED / REVIEWED — CHANGES REQUIRED**
- GOAL_02A_IBM_ADAPTER_SEMANTICS_HARDENING.md — **COMPLETED / REVIEWED — CORE CORRECTIONS ACCEPTED, CONTRACT CLOSURE REQUIRED**
- GOAL_02B_RESOURCE_CONTRACT_CLOSURE.md — **COMPLETED / REVIEWED / ACCEPTED**
- GOAL_03_FAST_ANALYTICS.md — **APPROVED / EXECUTE**
- GOAL_04_CACHE_REPORTS_UI.md — **PROVISIONAL DRAFT**
- GOAL_05_VERSIONS_PARTS_RESEARCH.md — **PROVISIONAL DRAFT**

The provisional files preserve design ideas and likely future work. They must be re-reviewed against the actual implementation after each completed goal.

After an approved goal finishes, stop and request architecture review. Do not automatically execute the next numbered file.

Always begin with MASTER_GOAL.md and STATUS.md.
