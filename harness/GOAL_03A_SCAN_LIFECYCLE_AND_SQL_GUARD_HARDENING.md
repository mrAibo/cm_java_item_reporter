# Goal 03A - Scan lifecycle and SQL read-only hardening

**Status: COMPLETED / REVIEWED — CHANGES REQUIRED. DO NOT RE-EXECUTE; execute Goal 03B only.**

Implementation commit: `7313176fa0b9b97d79516aadaaddada6dbea5a39`.
Core 367 tests and IBM 51 tests green on the stub path; `--require-ibm` green against the real
IBM CM 8.7 SDK. NO LIVE DB2/ORACLE SQL VALIDATION WAS PERFORMED - no database is reachable.

This is a focused correction gate after the architecture review of Goal 03.

Reviewed Goal 03 remote SHA:

`1dcd8f30b20cf57b2abfb688cc12b1e710e4ef6a`

Do NOT re-execute Goal 03.
Do NOT execute Goal 04 or Goal 05.
Do NOT add persistent history, report export, UI redesign, Versions/Parts SQL, retention administration,
document content access or any write operation.

Preserve all accepted Goal 03 work:

- lazy hard-bounded JDBC pool and allocation-boundary semantics;
- JDBC optional to IBM repository activation;
- DB2 + Oracle complete-statement dialects;
- PhysicalSchemaResolver and all-segments 1..N rule;
- distinct ItemID counting across versions/segments;
- immutable ItemID date-key window semantics;
- one database anchor per scan;
- versions/parts unavailable;
- immutable completed snapshots and coverage;
- authenticated statistics API;
- Goal 01C repository-switch/close-state guarantees;
- Goal 02B resource create/health contracts;
- no proprietary JARs in Git.

Use independent subagents for:
1. scan lifecycle/deadline review;
2. JDBC cancellation/connection-health review;
3. SQL read-only boundary hardening;
4. deterministic adversarial tests;
5. final integrated review.

The lead agent owns integration.

---

## A. Never release the one-scan gate while an old worker is alive - BLOCKING

Current Goal 03 code can do this:

1. scan reaches its overall deadline or is cancelled;
2. `joinWorkers(...deadline...)` reports a living worker;
3. coordinator cancels/interupts and gives the worker `DRAIN_GRACE`;
4. the SECOND `joinWorkers` result is ignored;
5. `finishScan()` sets `scanInFlight=false`, clears `active` and clears the thread list;
6. the old worker is still alive, possibly executing JDBC and holding a lease;
7. a new refresh is accepted.

This violates the one-scan invariant and forgets a live resource-owning thread.

### Required invariant

> A RepositoryContext may not start scan N+1 while ANY worker/supervisor work belonging to scan N is still
> alive, regardless of timeout, cancellation or driver behaviour.

A deadline/cancel determines publication/status. It does NOT prove physical execution ended.

Required behavior:

- once deadline expires, mark the scan TIMED_OUT, signal cancellation and publish nothing;
- once external cancellation/context close happens, mark it cancelled and publish nothing;
- if a worker is still alive afterwards, keep the scan ownership/gate retained;
- `requestScan()` continues to return ALREADY_RUNNING (or a dedicated draining conflict) until every old
  worker has actually terminated;
- never clear the tracked thread references while any tracked scan thread is alive;
- once the last lingering worker really exits, release the gate exactly once;
- no second scan may overlap a first scan's JDBC lease;
- no unbounded work queue/executor.

A safe implementation may allow terminal phase TIMED_OUT/CANCELLED while `isScanInFlight()` remains true
with an explicit draining state/fact. Do not lie that the scan is physically gone merely because its result
is terminal.

### Coordinator close state

Because `close()` is intentionally bounded, a stubborn scan thread can outlive the close call.

Make this visible to RepositoryContext independently of the JDBC pool:

- implement `CloseStateAware` (and CloseOutcomeAware if useful) on the coordinator/service resource, or an
  equivalent owned close-aware resource;
- after close begins, a still-live scan thread means `CLOSING`;
- after every tracked scan thread is dead, terminal clean may be reported;
- never report CLOSED_CLEAN while a coordinator-owned thread remains alive;
- a coordinator does not need to invent CLOSED_UNCERTAIN merely because a thread is slow: pending/draining
  is different from uncertain.

This ensures repository switching stays fail-closed even if a worker is stuck outside an active JDBC lease.

### Deterministic tests

Use latches, not sleeps, to build a worker that deliberately ignores:
- scan cancellation;
- thread interruption;
- Statement-style abort callback.

Prove:

1. first scan reaches deadline/cancel;
2. worker remains alive past the old drain grace;
3. snapshot remains unchanged;
4. second refresh is refused while old worker is alive;
5. thread remains tracked;
6. RepositoryContext close state is CLOSING;
7. after releasing the worker, it exits, the old scan gate is released and close can become clean;
8. only then may a new scan/repository proceed.

Add a mutation/opposite control that reproduces the old "second join ignored => new scan overlaps" behavior.

---

## B. Overall scan deadline and cancellation must include the database anchor query - BLOCKING

Current `StatisticsEngine.databaseCurrentDate()` has no cancellation/deadline input. It borrows a JDBC
session and runs the database date query synchronously before workers exist.

Therefore:
- the overall scan deadline does not actively govern this operation;
- explicit cancellation/context close cannot reach its `Statement.cancel()` registration;
- query timeout may be configured longer than scan timeout.

### Required design

Make the database-current-date operation a first-class part of the scan's cancellation/deadline domain.

Requirements:

- the one-anchor-per-scan rule remains unchanged;
- the anchor query registers `JdbcSession.cancelInFlight` through the same ScanCancellation mechanism as
  ItemType queries;
- when the overall deadline expires, the active anchor query is signalled/cancelled;
- an explicit cancel or context close also reaches the anchor query;
- no second database date is read as a retry/fallback;
- no JVM LocalDate is substituted;
- no silent boundary is invented.

The coordinator must own one explicit absolute scan deadline. Query execution may use the configured
per-query timeout as an additional cap, but it must not be the only enforcement of the overall scan timeout.

Do not solve this with a large arbitrary sleep.

A single bounded watchdog/deadline thread per active scan is acceptable. A bounded scheduler is acceptable.
An unbounded executor/queue is not.

If the driver ignores cancellation, section A applies: the scan remains draining/in-flight and blocks a
new scan rather than pretending completion.

### Required tests

- anchor query blocks indefinitely and ignores interrupt;
- deadline expires;
- its registered cancel action is invoked;
- phase becomes TIMED_OUT/no new snapshot;
- scan ownership remains retained until the anchor operation actually returns;
- a second refresh is refused meanwhile;
- releasing the anchor lets final draining finish;
- explicit user cancellation reaches the same anchor cancel action and reports CANCELLED, not TIMED_OUT;
- anchor is still read exactly once.

Test configuration where query timeout is greater than scan timeout: overall deadline still wins as the
scan policy.

---

## C. A real current-schema SQL failure must retire the JDBC session - HIGH

`JdbcSession.currentSchema()` currently leaves the session reusable after every SQLException.

Keep one distinction:

- `SQLFeatureNotSupportedException`, or a clearly documented SQLState equivalent such as `0A000`, means
  "this driver cannot report schema"; it is a capability limitation and may leave the connection reusable;
- any other SQLException / unexpected runtime driver failure while asking the live connection for its
  schema is conservative: mark the JdbcSession unusable before propagating the sanitized failure.

Do not expose the raw message.

Required tests:

1. getSchema unsupported -> operation unavailable/refused, session remains locally healthy;
2. connection/driver SQLException -> session marked unusable;
3. after the lease returns, BoundedPool retires/closes it;
4. a subsequent borrow receives a different physical connection;
5. if that close fails, normal quarantine semantics still apply.

Do not add getSchema to the pool health path.

---

## D. Strengthen the runtime SELECT-only boundary beyond a prefix check - HIGH / READ-ONLY GUARANTEE

Goal 03's current `requireSelect()` admits any SQL whose leading text is SELECT or WITH. The source guard
also mainly catches literals beginning with a write/control verb.

That is not a structural proof that a statement cannot mutate data: a data-changing construct can be nested
inside a SELECT/WITH shape on supported enterprise SQL dialects.

Current production SQL is generated and does not contain such a construct. Preserve all approved SQL.

### Required rule

The analytics query surface must accept only the narrow read-only SQL language this application generates.

At minimum reject, case-insensitively and token-aware:

- INSERT
- UPDATE
- DELETE
- MERGE
- TRUNCATE
- CREATE
- ALTER
- DROP
- GRANT
- REVOKE
- CALL
- BEGIN
- EXEC/EXECUTE where applicable
- statement separators that would permit a second statement;
- SQL comment forms that are not needed by generated analytics SQL and could hide a token.

Do not implement a general DB2/Oracle parser. A strict lexical allow-list/refusal layer for generated SQL is
preferred.

Avoid false security from substring matching:
- identifiers containing letters like `UPDATED_AT` must not be mistaken for UPDATE;
- keywords inside an ordinary quoted literal should either be handled correctly or, if this application
  does not need SQL literals at all, the narrow surface may refuse such literals entirely.

Generated production statements from:
- DB2 current-date query;
- Oracle current-date query;
- root mapping;
- zero-row probes;
- one/multi-segment aggregate;
- total-only aggregate

must all remain accepted.

### Source/build guard

Strengthen the committed source guard in the same direction so it cannot call a SELECT-wrapped DML statement
clean merely because the first keyword is SELECT.

Required planted controls include at least:
- `SELECT * FROM FINAL TABLE (DELETE FROM X ...)` or an equivalent data-change-table-reference shape;
- a WITH/CTE containing a DELETE/UPDATE/INSERT token;
- multi-statement `SELECT ...; DELETE ...`;
- comment-obfuscated write token;
- a normal approved CTE/SELECT that still passes.

Also evaluate generic JDBC `execute(...)` / `PreparedStatement.execute(...)` call patterns. If production
analytics does not need them, forbid them explicitly rather than relying on convention.

---

## E. Preserve the accepted analytics semantics

Do not change these merely while fixing lifecycle:

- logical item = distinct ItemID;
- per-segment SELECT DISTINCT + cross-segment UNION/dedup;
- every expected root segment 1..N verified;
- date windows from ItemID positions 9-14;
- one database anchor per scan;
- total can remain available when date windows are unrepresentable;
- Versions/Parts unavailable;
- one JDBC lease per ItemType query;
- DB2/Oracle dialect SQL and bound identifiers/values;
- no ad-hoc connection;
- no eager pool initialize;
- analytics optional to CM activation;
- partial item failures may publish a completed snapshot with coverage;
- catastrophic/timeout/cancelled scan never replaces previous snapshot;
- metadata classification labels are consumed, not re-derived;
- no raw SQLException/URL/user/SQL leaks;
- authenticated analytics routes and refresh action header;
- no vendor JAR in Git.

Do not weaken Goal 01C or Goal 02B to make scan cleanup easier.

---

## F. Validation

Run at minimum:

- `./build.sh`
- `./tests/selftest.sh`
- `./tests/shell/run.sh`
- `./bin/doctor.sh`
- `./build.sh --check-ibm-isolation`
- complete IBM suite against committed stubs
- `./build.sh --require-ibm` when the same local SDK is available

Add the deterministic tests from A-D.

Run the analytics source guard and planted mutation controls.

If DB2 driver JAR is locally available, driver discovery may be rechecked. If Oracle driver JAR becomes
available, check its local discovery separately.

A driver being loadable is not live DB validation.

No live DB2/Oracle database is currently reachable from the execution host. Do not invent live SQL
validation. If that changes, run the Goal 03 representative comparison and record it.

Verify:
- no tracked vendor JAR/credential;
- RepositoryManager and BoundedPool core safety semantics were not weakened;
- PR #1 remains draft/unmerged;
- both exact-SHA push and pull_request Actions are green.

---

## G. STATUS / handoff

Update STATUS.md with:

- implementation commit(s);
- coordinator draining/gate semantics;
- overall deadline/anchor cancellation semantics;
- coordinator close-state behavior;
- currentSchema health verdict;
- strengthened SQL admission/source guard;
- tests actually executed/results;
- stub + real IBM SDK state separately;
- real DB2/Oracle live validation or explicit absence;
- Actions run IDs/results;
- remaining risks.

Set next goal to:

**NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

Do not execute Goal 04 or Goal 05.

At completion:

1. review complete diff;
2. commit coherently;
3. push `origin/bootstrap/cm-insight-architecture` yourself;
4. verify local HEAD == remote HEAD;
5. require push and pull_request Actions for the SAME final SHA to be green;
6. do not merge PR #1;
7. stop with architecture-review handoff.
