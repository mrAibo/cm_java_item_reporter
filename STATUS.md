# Project Status

> Canonical cross-session checkpoint. Read this first when continuing in another chat/model. Update it at the end of every substantial goal.

## Project state

- Project: CM Insight
- Repository: mrAibo/cm_java_item_reporter
- Active branch: bootstrap/cm-insight-architecture
- Current remote HEAD reviewed: `eb49b5615940006d15d9e899e37ea0b8b0d04e90`
- Goal 01 implementation commit: `fb42a0e55e69b551bffdcf0986714b8110dbdfb6`
- Stage: **Goal 01 external architecture/code review completed; Goal 01A hardening is APPROVED**
- Runtime target: Java 17 LTS, OpenJDK-compatible
- Build/deployment: javac + jar + bash; single JVM
- Product safety mode: read-only V1/V2
- Current approved goal: `harness/GOAL_01A_REVIEW_HARDENING.md`
- Goals 02-05: PROVISIONAL; do not execute
- Next goal after 01A: NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED

## Goal 01 review verdict

**CHANGES REQUIRED before IBM CM/JDBC integration.**

The review confirmed that Goal 01 is substantial and generally well structured: the architecture
boundaries are intact, no Maven/Gradle/Spring/container drift occurred, the fake-resource test suite is
large, and the implementation did not prematurely add IBM/JDBC logic.

However, review found defects that become dangerous once real sessions/connections are attached.

### Blocking findings

1. **BoundedPool create-vs-close race**: an in-flight lazy `factory.create()` can complete after
   `pool.close()` and be promoted to a leased resource because `createReserved()` does not re-check
   the closed state before incrementing `leasedCount`.
2. **Failed resource close can violate the physical hard bound**: `closeEntry()` swallows
   `Exception` and capacity is freed even when close outcome is uncertain. A replacement can then be
   created while the old real CM/JDBC connection may still exist. Failed retirement must degrade or
   quarantine capacity instead.
3. **Repository switch continues after close uncertainty**: cleanup failures are recorded but do not
   prevent a new RepositoryContext from being created. With real connections this can compound the
   physical-bound problem.
4. **Unsafe untracked stop**: `bin/stop.sh --untracked` can signal an arbitrary JVM that owns the
   configured port even when the CM Insight health marker is absent. JVM identity alone is not proof
   of CM Insight ownership.
5. **Remote Basic over plaintext HTTP**: non-loopback is allowed with a non-default password even
   though reusable Basic credentials are then sent without transport encryption. Remote plaintext must
   become explicit opt-in/test-only until TLS is provided.

### High-priority findings

6. **Repository secret-file references are accepted but lost**: loader/docs allow
   `repository.*.user.file` / `password.file`, while RepositoryProfile only carries env-name fields.
   File references have no path into the future adapter. Inline `repository.*.user=<value>` can also
   be silently ignored rather than rejected.
7. **Doctor/runtime credential mismatch**: doctor currently says an explicitly configured but missing
   `web.auth.password.env` falls back to admin/admin on loopback. Java correctly fails closed.
8. **Relative path mismatch**: shell scripts resolve application paths from repository root, while Java
   resolves `profiles.dir`, `classifications.file` and `secrets.dir` against caller CWD.
9. **Lifecycle scripts hard-code 127.0.0.1** despite configurable bind; wildcard, other loopback
   literals and explicit remote binds can be diagnosed/probed incorrectly.
10. **Lease usage rotation is still forgettable**: operation-based rotation depends on callers
    explicitly invoking `recordOperation()`; ordinary try-with-resources use does not advance usage.
11. **Pool metrics mislabel initial creation as reconnect** and count attempted closes as closed even
    when close failed.

## Positive review findings

- Java 17 remains a sound baseline. IBM's current CM 8.7 documentation states that the Content Manager
  API supports Java 8, 11 and 17.
- SDK/JDBC types remain outside core business DTOs.
- Only the exact health endpoint is public.
- Router authentication-before-404/405 behavior is intentional and sound.
- Configured-but-missing web secret behavior in Java is fail-closed.
- Versions/Parts remain behind the research gate.
- No proprietary JARs or secrets were committed.
- Goal 02-05 were not executed.

## CI observation

GitHub currently reports no workflow runs/status checks for the reviewed remote HEAD. The local Harness
evidence in the prior checkpoint is useful, but CI must not be described as passing until an actual
GitHub Actions run is observed. Goal 01A must push its own result and record remote verification.

## Exact next goal

Execute only:

1. `harness/MASTER_GOAL.md`
2. `harness/GOAL_01A_REVIEW_HARDENING.md`

Use subagents as required by Goal 01A. Do not start Goal 02.

## Handoff / resume prompt

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture. First git fetch origin and fast-forward to the current remote review checkpoint, then read STATUS.md, harness/MASTER_GOAL.md and harness/GOAL_01A_REVIEW_HARDENING.md. Execute only Goal 01A. This is a correction gate, not IBM CM integration. At completion, run the required validation, update STATUS.md, commit coherently, push the branch yourself to origin, verify remote HEAD equals local HEAD, set the next goal to NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED, and stop. Do not merge PR #1 and do not run Goal 02."

## Mandatory checkpoint rule

At the end of every approved goal record:

- date/time
- branch
- local final HEAD
- verified pushed remote HEAD
- exact work completed
- major files changed
- commands/tests actually run and results
- IBM/DB live tests actually run or explicitly not run
- unresolved risks
- architecture changes only when approved
- next goal status
- copy/paste resume/review instruction
