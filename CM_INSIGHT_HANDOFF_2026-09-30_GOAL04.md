# CM Insight — Cross-Session Handoff

**Date:** 2026-09-30
**Repository:** `mrAibo/cm_java_item_reporter`
**Branch:** `bootstrap/cm-insight-architecture`
**Purpose:** continue directly into approved Goal 04 without re-explaining or redoing Goals 01–03B.

---

## 1. Mandatory new-session startup

Do not trust this file's creation SHA as a permanent branch head. The branch may advance after this handoff is committed.

Start every new session with:

```bash
git fetch origin
git checkout bootstrap/cm-insight-architecture
git pull --ff-only origin bootstrap/cm-insight-architecture
git rev-parse HEAD
git status --short
git log -8 --oneline --decorate
```

The architecture-review checkpoint immediately before this handoff was created is:

`fdb8ecd7be04013cd9ca1f3ed5adebc994a1ca96`

Commit:

`docs(review): accept Goal 03B and approve Goal 04`

If the branch is newer, inspect every intervening commit before changing anything and preserve valid newer work.

PR #1 must remain **open, draft and unmerged** unless the user explicitly asks to merge it.

---

## 2. What is accepted and must not be redone

Goal state:

- Goal 01 / 01A / 01B / 01C — accepted foundation.
- Goal 02 / 02A / 02B — IBM adapter/resource corrections completed; Goal 02B accepted.
- Goal 03 — analytics core accepted after correction chain.
- Goal 03A — reviewed; its remaining findings were closed by Goal 03B.
- Goal 03B — **COMPLETED / REVIEWED / ACCEPTED**.
- Goal 04 — **APPROVED / EXECUTE**.
- Goal 05 — **PROVISIONAL / DO NOT EXECUTE**.

Do not re-execute Goal 03, 03A or 03B.

The authoritative Goal 04 contract is:

`harness/GOAL_04_CACHE_REPORTS_UI.md`

The ready-to-use DeepSeek Harness starter prompt is:

`harness/GOAL_04_DEEPSEEK_HARNESS_PROMPT.md`

---

## 3. Current architecture

CM Insight is a Java 17, single-JVM modular monolith for IBM Content Manager 8.7.

Hard constraints:

- Java 17.
- no Maven.
- no Gradle.
- no Spring.
- no containers.
- no microservices.
- no runtime CDN/Internet dependency.
- DB2 + Oracle repository analytics.
- IBM CM Java API for metadata/retention.
- JDBC fast path for aggregate statistics.
- V1/V2 repository operations are read-only.
- one active RepositoryContext.
- hard-bounded resource pools.
- no emergency or ad-hoc repository connections.
- proprietary IBM/DB2/Oracle JARs never committed.
- credentials only through approved env/file indirection.
- Versions/Parts remain unavailable until independently verified.
- retention administration remains disabled.

---

## 4. Accepted resource/lifecycle invariants

These are safety contracts, not implementation suggestions.

### Physical bounds

Every CM/JDBC physical slot is accounted for.

No resource may be created outside its hard bound.

Unknown creation or close outcome is conservative: capacity remains quarantined rather than assumed free.

Only explicitly proven-clean pre-allocation failure may release a creation reservation.

### Health

`ResourceFactory.isHealthy()` is mandatory.

Pool health checks are local/in-memory/non-blocking.

Never put network/database I/O under the pool lock.

### Repository switching

Accepted close states:

- NOT_CLOSED
- CLOSING
- CLOSED_CLEAN
- CLOSED_UNCERTAIN

A previous RepositoryContext may not be replaced while it owns leased, creating, retiring, draining, quarantined or otherwise physically outstanding resources.

A pending physical shutdown is CLOSING and retryable.

An uncertain physical outcome is fail-closed.

---

## 5. Accepted Goal 03/03A analytics semantics

A logical item is exactly one distinct `ItemID`.

Never use a bare root-table `COUNT(*)` as the logical-item count.

Deduplicate ItemID across:

- versions;
- all physical root segments.

If current SegmentID is N, analytics accounts for every root segment `001 .. N`.

A missing middle segment is an ItemType error; never silently count only the latest/current segment.

Creation-date metrics use the immutable ItemID date encoding:

- position 9: century, A=20xx, B=21xx;
- positions 10–11: year;
- position 12: month A..L;
- positions 13–14: day.

Documented supported range: 2000–2199.

Outside that range the time metrics are UNAVAILABLE; the total may remain AVAILABLE.

One database current-date anchor is read per full scan.

Calendar windows:

- today: [today, tomorrow)
- last 7 days: [today - 6d, tomorrow)
- last 30 days: [today - 29d, tomorrow)
- current year: [Jan 1, Jan 1 next year)

No JVM-local date may replace the database anchor.

Versions and Parts remain UNAVAILABLE.

---

## 6. Accepted Goal 03B concurrency/cancellation model

Goal 03B is accepted and must not be simplified back to Goal 03A behavior.

Every scan has generation-local owned-thread state.

Roles are explicit:

- REAPER
- WATCHDOG
- SUPERVISOR
- WORKER

The one-scan gate is released only by an outside observer after the generation is sealed, no worker batch is registering, and every non-reaper owned thread has actually reached `Thread.isAlive() == false`.

A worker/supervisor/watchdog must never prove its own death by self-deregistering before return.

Old-generation cleanup must never inspect, clear, interrupt or otherwise mutate a later generation.

`closeState()` stays CLOSING while any coordinator-owned thread, including the reaper, is still alive.

A deadline/cancel decides result publication/status; it does not prove physical work stopped.

---

## 7. Accepted production anchor-cancellation contract

The full-scan database-current-date read participates in the same `ScanCancellation` as ItemType queries.

Production path:

`ScanCoordinator -> JdbcStatisticsEngine -> BoundedPool<JdbcSession> -> JdbcSession -> PreparedStatement.executeQuery()`

The production anchor registration is mutation-sensitive in committed tests.

Overall scan deadline, explicit cancellation and context close all reach the exact in-flight `PreparedStatement.cancel()`.

A per-query timeout may be larger than the overall scan timeout; the overall scan deadline still wins.

The anchor is read exactly once. No retry anchor and no JVM-date fallback.

If a driver ignores cancellation, the scan remains physically draining and blocks later analytics work until its thread actually exits.

---

## 8. Accepted JDBC session and SQL safety rules

`JdbcSession.currentSchema()`:

- SQLFeatureNotSupportedException or SQLState 0A000 may leave the session reusable;
- any other SQLException/runtime driver failure marks the session unusable before propagation;
- the next borrow must not receive the poisoned physical connection.

Runtime SQL admission is deliberately narrow:

- one SELECT/WITH statement;
- no SQL comments;
- no statement separator;
- no quoted region/literal;
- whole-token refusal of write/control vocabulary;
- generic `execute(...)`, write batches, `createStatement()`, stored-procedure paths are forbidden.

The committed analytics source guard runs in `build.sh`.

Its one literal-vocabulary exemption is the exact path:

`src/main/java/com/mraibo/cminsight/db/SqlAdmission.java`

That exemption does not apply to JDBC write-call checks, and same-basename sibling files receive no exemption.

---

## 9. Goal 03B validation evidence

Implementation commit:

`6fbee64bb047bd6ff841a5a530a0897703ab5b0f`

Final execution/handoff HEAD:

`edab82f25e69af2a8170fb3130ec69f7314fb0c8`

Architecture review commit:

`fdb8ecd7be04013cd9ca1f3ed5adebc994a1ca96`

The architecture reviewer independently reran on the actual final Goal 03B handoff tree:

- `./build.sh` — core 375/0, IBM-stub 51/0.
- `./tests/selftest.sh` — core 375/0, IBM-stub 51/0.
- `./tests/shell/run.sh` — 5/5.
- `./bin/doctor.sh` — success.
- `./build.sh --check-ibm-isolation` — success.
- `bash tests/shell/analytics_guard.sh` — success.
- `bash tests/shell/analytics_source_guard_test.sh` — 93/0.

Goal 03B implementation Actions:

- push `36711455135` — success.
- pull_request `36711461832` — success.

Architecture review checkpoint Actions for `fdb8ecd...`:

- push `36715164729` — success.
- pull_request `36715172535` — success.

Real IBM CM 8.7 SDK validation was separately reported green during Goal 03B execution:

- core 375/0;
- real-SDK IBM suite 51/0.

No successful live IBM CM server validation has been performed.

No live DB2/Oracle CM database validation has been performed.

The absence of live DB execution remains the largest analytics verification gap.

---

## 10. Why Goal 04 was rewritten before approval

The old provisional Goal 04 draft was too vague around several architecture-sensitive decisions.

The approved Goal 04 now explicitly defines:

- the existing published full `StatisticsSnapshot` remains the single in-memory statistics truth;
- `cache.statistics.ttl.seconds` is a freshness judgement, not a second mutable cache and not an implicit refresh trigger;
- stale data remains visible;
- persistent history is application-local aggregate history only;
- H2 is optional/local and must not become a compile-time dependency or reuse repository credentials/pools;
- targeted single-ItemType refresh is separate detail data and may not mutate full-scan totals/history;
- full and targeted analytics operations must not overlap in one RepositoryContext;
- reports are built only from immutable completed full snapshots or stored history, with no hidden CM/JDBC queries;
- HTML/CSV are mandatory JDK-only formats;
- XLSX must be real or explicitly unavailable;
- report output is confined below `reports.dir`, with injection/path protections;
- all new state-changing routes use the existing action-header/CSRF-resistant pattern;
- all UI remains offline and authenticated except minimal health;
- Goal 03B generation/lifecycle semantics apply to any new analytics operation.

Read the complete Goal 04 file before implementation; this summary is not a substitute.

---

## 11. Goal 04 implementation priorities

Execute the full approved goal, but a safe integration order is:

1. configuration/path contracts and typed capability interfaces;
2. statistics freshness semantics without duplicating the full snapshot;
3. local history-store abstraction and persistence lifecycle;
4. publication-to-history integration;
5. targeted ItemType refresh with shared analytics-operation exclusion;
6. immutable report model and HTML/CSV/XLSX capability;
7. history/report/targeted-refresh authenticated APIs;
8. complete offline Web UI;
9. diagnostics/readiness;
10. adversarial security/lifecycle/output tests;
11. full serial validation and independent final review.

Do not silently alter the Goal 04 contract merely because a different order is easier.

---

## 12. Goal 04 key storage/history rules

Persistent history stores aggregate/read-model data only.

Never store:

- document content;
- credentials or Authorization headers;
- JDBC URLs or database usernames;
- raw SQL;
- raw SQLException/vendor text;
- live CM/JDBC objects.

Persist only a full scan that normally completed and published a full StatisticsSnapshot.

A full completed partial-failure snapshot may be stored with exact coverage.

TIMED_OUT, CANCELLED and catastrophic scans create no history entry.

Do not use context-local scanId as the sole persistent identity across restarts.

Preferred V1 history implementation is embedded H2 from a locally supplied `lib/app` JAR.

Core compile/tests must still work with zero H2 JARs.

Missing H2 means history UNAVAILABLE, not repository activation failure.

---

## 13. Goal 04 targeted refresh rules

Targeted single-ItemType refresh is allowed, but it is not a miniature full snapshot.

It must:

- resolve the ItemType from active metadata;
- read one DB anchor for that targeted operation;
- reuse accepted physical-schema/counting/JDBC safety;
- use bounded JDBC;
- not overlap full or another targeted analytics operation;
- publish a separate immutable detail result/cache with its own timestamp/anchor;
- never mutate full snapshot/totals;
- never create a full history snapshot;
- never publish old-context results into a new RepositoryContext.

The UI must label targeted detail data distinctly from the full-scan snapshot used by dashboard totals.

---

## 14. Goal 04 report/security rules

Report input is immutable:

- current completed full snapshot plus frozen safe metadata context; or
- one persistent history snapshot.

Report generation must perform zero hidden CM/JDBC reads.

HTML/CSV mandatory.

XLSX must be a real workbook or explicitly unavailable.

Output paths are generated by the application and confined below `reports.dir`.

HTML escapes every dynamic value.

CSV must prevent spreadsheet formula injection for text beginning with dangerous formula prefixes.

XLSX must never generate formula cells from data and must contain no macros/external relationships.

All report errors are sanitized.

No HTTP parameter may become a filesystem path.

---

## 15. Goal 04 API/UI scope

New minimum authenticated APIs include:

- GET /api/history
- GET /api/history/{id}
- POST /api/reports
- GET /api/reports
- GET /api/reports/{id}/download
- POST /api/statistics/item/{itemTypeId}/refresh

State-changing POST routes require the existing exact action-header pattern before any state is read or changed.

All lists must be bounded/paginated.

Required offline English views:

- Repository selection
- Dashboard
- ItemTypes
- ItemType Properties
- Retention
- History
- Reports
- System / Diagnostics

No document/content viewer.

No retention administration.

---

## 16. Operational facts

Target production:

- SLES/Linux;
- IBM CM 8.7;
- DB2 primary, Oracle supported;
- Java 17;
- offline/local JARs;
- no runtime Internet.

Windows development caveat:

`core.filemode=false` can hide executable-bit errors.

Always validate CI-touching scripts using Git index/Linux semantics.

Build concurrency caveat:

This Windows/MSYS host does not have reliable `flock` protection. Concurrent `build.sh` runs in one tree can delete/recreate each other's build outputs and create false failures.

Run final validation serially or in a private worktree/clone.

---

## 17. Git/Harness workflow

The Harness lead performs its own Git workflow.

Do not ask the user to push routine work.

At Goal 04 completion the lead must:

1. review the full integrated diff;
2. run required tests serially;
3. update architecture/security/data-model/requirements/readme/config docs as appropriate;
4. update STATUS.md with exact evidence;
5. commit coherently;
6. push `origin/bootstrap/cm-insight-architecture` itself;
7. verify local HEAD == remote HEAD;
8. verify BOTH push and pull_request Actions are success on the SAME final SHA;
9. keep PR #1 open/draft/unmerged;
10. set the next goal to **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**;
11. do not execute Goal 05;
12. stop with an architecture-review handoff.

---

## 18. New-session starter prompt

Copy/paste this into a new ChatGPT session:

> Continue CM Insight from the repository, not from memory.
>
> Repository: `mrAibo/cm_java_item_reporter`
> Branch: `bootstrap/cm-insight-architecture`
>
> Do not ask me to re-explain the project.
>
> First fetch, checkout and fast-forward the branch. Inspect all commits newer than architecture-review checkpoint `fdb8ecd7be04013cd9ca1f3ed5adebc994a1ca96`; preserve valid newer work.
>
> Then read completely:
> - `CM_INSIGHT_HANDOFF_2026-09-30_GOAL04.md`
> - `STATUS.md`
> - `ARCHITECTURE.md`
> - `SECURITY.md`
> - `DATA_MODEL.md`
> - `REQUIREMENTS.md`
> - `harness/MASTER_GOAL.md`
> - `harness/GOAL_03A_SCAN_LIFECYCLE_AND_SQL_GUARD_HARDENING.md`
> - `harness/GOAL_03B_SCAN_GENERATION_OWNERSHIP_AND_GUARD_CONTRACT.md`
> - `harness/GOAL_04_CACHE_REPORTS_UI.md`
> - `harness/GOAL_04_DEEPSEEK_HARNESS_PROMPT.md`
>
> Goals 01-03B are completed; Goal 03B is architecture-reviewed and accepted. Goal 04 is the only approved goal and must be executed now. Goal 05 is not approved.
>
> Execute Goal 04 completely. Use AgentTeams/subagents as requested by the goal. You own integration, tests, STATUS, commit, push and exact-SHA Actions verification. Do not ask me to push. Do not merge PR #1.
>
> Preserve every accepted lifecycle/resource/read-only/counting/cancellation invariant described in the handoff. Stop after Goal 04 with next goal set to NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.
