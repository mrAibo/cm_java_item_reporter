# DeepSeek Harness Prompt - Execute CM Insight Goal 04

Copy/paste this prompt into the DeepSeek Harness lead-agent session.

---

Continue CM Insight in:

`mrAibo/cm_java_item_reporter`

Branch:

`bootstrap/cm-insight-architecture`

You are the lead implementation agent. Execute the currently approved durable goal to completion.

Do not ask the user to re-explain the project.
Do not ask the user to push routine changes.
Do not merge PR #1.
Do not execute Goal 05.

## 1. Synchronize before work

First:

```bash
git fetch origin
git checkout bootstrap/cm-insight-architecture
git pull --ff-only origin bootstrap/cm-insight-architecture
git rev-parse HEAD
git status --short
git log -10 --oneline --decorate
```

The architecture-review checkpoint before the handoff documentation was:

`fdb8ecd7be04013cd9ca1f3ed5adebc994a1ca96`

If the branch has advanced beyond that SHA, inspect every intervening commit before changing anything and preserve valid newer work.

## 2. Read completely before implementation

Read in full:

- `CM_INSIGHT_HANDOFF_2026-09-30_GOAL04.md`
- `STATUS.md`
- `README.md`
- `VISION.md`
- `REQUIREMENTS.md`
- `ARCHITECTURE.md`
- `DATA_MODEL.md`
- `SECURITY.md`
- `IMPLEMENTATION_PLAN.md`
- `DEEPSEEK_HARNESS_PLAN.md`
- `harness/MASTER_GOAL.md`
- `harness/GOAL_03_FAST_ANALYTICS.md`
- `harness/GOAL_03A_SCAN_LIFECYCLE_AND_SQL_GUARD_HARDENING.md`
- `harness/GOAL_03B_SCAN_GENERATION_OWNERSHIP_AND_GUARD_CONTRACT.md`
- `harness/GOAL_04_CACHE_REPORTS_UI.md`

Also inspect the current repository tree and recent Git history.

The repository and approved Goal 04 file are authoritative. Do not implement from this prompt alone.

## 3. Goal state

Already completed / reviewed:

- Goals 01, 01A, 01B, 01C.
- Goals 02, 02A, 02B.
- Goal 03 analytics core.
- Goal 03A hardening.
- Goal 03B generation ownership / production anchor test / exact guard-contract closure.

Goal 03B is COMPLETED / REVIEWED / ACCEPTED.

Execute ONLY:

`harness/GOAL_04_CACHE_REPORTS_UI.md`

Goal 04 is APPROVED / EXECUTE.

Goal 05 is PROVISIONAL / DO NOT EXECUTE.

## 4. Non-negotiable inherited invariants

Do not weaken:

- Java 17 single-JVM modular monolith.
- no Maven/Gradle/Spring/containers/microservices.
- offline runtime; no CDN/runtime Internet dependency.
- one active RepositoryContext.
- V1/V2 repository operations read-only.
- IBM SDK types isolated to the optional adapter source set.
- hard-bounded CM/JDBC resources; no emergency/ad-hoc repository connection.
- Goal 01C fail-closed repository switching.
- Goal 02B creation/health/close contracts.
- logical item = DISTINCT ItemID.
- all physical root segments 1..N.
- creation windows from immutable ItemID positions 9-14.
- one database current-date anchor per full scan.
- Versions/Parts remain UNAVAILABLE.
- analytics optional to CM activation.
- timeout/cancel/catastrophic full scan publishes no replacement snapshot.
- no raw SQL/JDBC URL/user/credential/raw SQLException leakage.
- authenticated application surface except exact minimal health route.
- no proprietary vendor JAR committed.

## 5. Goal 03B concurrency contract is load-bearing

Do NOT simplify ScanCoordinator back to self-deregistration.

Every analytics scan generation has generation-local owned threads.

The one-scan gate opens only after an outside observer proves every non-reaper worker/supervisor/watchdog of that generation is physically dead via `Thread.isAlive()==false`.

Old-generation cleanup may never mutate a later generation.

Close remains CLOSING while any coordinator-owned thread is alive.

Deadline/cancel is a publication/status decision, never proof of physical termination.

Production database anchor cancellation must remain reachable through the real:

`ScanCoordinator -> JdbcStatisticsEngine -> JdbcSession -> PreparedStatement.cancel()`

path for deadline, explicit cancel and context close.

## 6. Goal 04 central design decisions

Implement the approved file exactly.

Especially preserve these review decisions:

### Latest statistics

The existing published completed full `StatisticsSnapshot` is the single authoritative L2/latest in-memory snapshot.

Do not build another independently mutable statistics cache.

`cache.statistics.ttl.seconds` judges freshness only.

Expired/stale data remains visible and a GET never triggers a hidden DB scan.

### Persistent history

History stores immutable aggregate/read-model data only.

No document content, credential, JDBC URL/user, raw SQL, raw SQLException/vendor message or live SDK/JDBC object.

Only normally completed/published full snapshots enter history.

Timeout/cancel/catastrophic scans enter no history.

History storage is application-local and must never write to IBM CM or the repository DB.

Preferred H2 support must be optional/local under `lib/app`, not downloaded or committed and not a core compile-time dependency.

Missing H2 makes history unavailable, not repository activation fail.

### Targeted ItemType refresh

A single-ItemType refresh is separate immutable detail data.

It must not overlap a full scan or another targeted analytics operation in one RepositoryContext.

It must not mutate:
- the published full snapshot;
- dashboard totals;
- full-scan coverage;
- persistent full history.

It has its own anchor/capturedAt and must be labelled as targeted detail data.

Repository switch must cancel/drain it using Goal 03B-equivalent generation safety.

### Reports

Reports are built only from immutable completed full snapshot/history data.

Report generation performs no hidden CM/JDBC read.

HTML and CSV are mandatory.

XLSX must be a real workbook or explicit UNAVAILABLE.

All report files stay below `reports.dir`; no request value becomes a path.

HTML escaping, CSV quoting/formula-injection defence and XLSX no-formula/no-macro/no-external-link rules are mandatory.

### API/UI

All new routes authenticated.

State-changing routes require exact action header before state read/change and refuse form-sendable content types.

History/report lists are bounded.

English responsive UI, entirely local/offline.

No content viewer and no retention administration.

## 7. AgentTeams / subagent plan

Use subagents aggressively, with non-overlapping ownership.

Recommended tracks:

1. **history-storage**
   - configuration/path settings;
   - HistoryStore model;
   - optional local H2 implementation;
   - retention/pruning/transaction tests.

2. **targeted-refresh**
   - shared analytics-operation exclusion;
   - targeted result/cache;
   - repository-switch/drain behavior;
   - deterministic concurrency tests.

3. **reports**
   - immutable report model;
   - HTML/CSV;
   - XLSX capability;
   - filesystem confinement and injection controls.

4. **api-ui**
   - authenticated APIs;
   - action-header guards;
   - Dashboard/ItemTypes/Properties/Retention/History/Reports/System views;
   - offline static assets/accessibility.

5. **security-adversarial**
   - hostile strings;
   - output/path traversal;
   - secret leakage;
   - stale-context publication;
   - mutation/opposite controls.

6. **independent-verifier**
   - read-only final review;
   - lifecycle/resource regression review;
   - independent acceptance matrix.

The lead owns cross-cutting interfaces, final architecture choices and integration.

Do not let several members rewrite the same central files independently.

If AgentTeams reports that no active team exists, create the Goal 04 team first; do not treat that message as a reason to skip subagents.

## 8. Suggested integration order

A safe order is:

1. configuration/path contracts;
2. typed history/report/targeted-refresh boundaries;
3. freshness semantics;
4. persistent history and publication integration;
5. targeted refresh / shared operation gate;
6. report model + formats;
7. APIs;
8. Web UI;
9. diagnostics/readiness;
10. adversarial/security/lifecycle tests;
11. independent verification;
12. final docs/STATUS/Git/Actions.

This order is guidance, not permission to omit any Goal 04 requirement.

## 9. Required validation

Run serially or in a private worktree/clone.

At minimum:

```bash
./build.sh
./tests/selftest.sh
./tests/shell/run.sh
./bin/doctor.sh
./build.sh --check-ibm-isolation
bash tests/shell/analytics_guard.sh
bash tests/shell/analytics_source_guard_test.sh
```

Also:

- complete IBM suite against committed stubs;
- `./build.sh --require-ibm` when the local IBM CM 8.7 SDK is available;
- verify zero tracked proprietary JARs/credentials;
- verify no external Web runtime dependency;
- exercise history-unavailable behavior with no H2;
- if H2 is locally available, run real embedded-H2 smoke separately;
- validate real XLSX separately if implemented;
- verify report/history paths cannot escape configured roots;
- verify new state-changing routes have zero side effects without exact action header.

Do not claim live DB2/Oracle or live CM validation unless it was actually performed.

The current known verification gap is still: no live DB2/Oracle CM database is reachable from the execution host.

## 10. Windows/MSYS caveat

Do not run multiple `build.sh` instances concurrently in one working tree.

This host lacks reliable `flock` protection under MSYS, and concurrent builds can delete/recreate each other's outputs and produce false `NoClassDefFoundError`/compile failures.

Use serial validation or a private checkout/worktree.

Validate shell executable bits through Git/Linux semantics because Windows `core.filemode=false` can hide them.

## 11. Definition of done

Goal 04 is complete only when:

- all approved deliverables are integrated, not stubs;
- failure paths are handled;
- no inherited architecture invariant was silently changed;
- dependency-free build path remains green;
- optional H2/XLSX capability reports honestly when absent;
- history/report/targeted-refresh security and lifecycle tests are green;
- docs are accurate;
- STATUS.md records exact commands/results and unresolved risks;
- all changes are committed coherently;
- YOU push all commits to `origin/bootstrap/cm-insight-architecture`;
- local HEAD equals remote branch HEAD;
- BOTH push and pull_request GitHub Actions are SUCCESS for the SAME final SHA;
- PR #1 remains open/draft/unmerged;
- next goal is set to **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**;
- Goal 05 is NOT executed.

Do not stop after code exists but before GitHub/CI/handoff closure.

## 12. Completion response

Stop after Goal 04.

Return an architecture-review handoff containing:

- final local/remote SHA;
- coherent commit list;
- exact implementation summary;
- test/validation evidence;
- stub vs real IBM SDK evidence separately;
- H2/XLSX real-library evidence separately;
- live CM/DB evidence or explicit absence;
- exact push + pull_request Action run IDs/results for the same final SHA;
- remaining risks;
- confirmation PR #1 remains draft/unmerged;
- next goal = NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.

Do not begin Goal 05.
