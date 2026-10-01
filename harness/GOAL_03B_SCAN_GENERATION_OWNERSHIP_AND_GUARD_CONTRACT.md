# Goal 03B - Scan generation ownership and guard-contract closure

**Status: COMPLETED / REVIEWED / ACCEPTED. DO NOT RE-EXECUTE.**

Implementation commit: `6fbee64bb047bd6ff841a5a530a0897703ab5b0f`.
Implementation Actions: push `36711455135` success; pull_request `36711461832` success.
Core 375 tests and IBM 51 tests are green on the stub path; the real IBM CM 8.7 SDK path is also green.
NO LIVE DB2/ORACLE SQL VALIDATION WAS PERFORMED.

Architecture review accepted Goal 03B against final execution/handoff HEAD `edab82f25e69af2a8170fb3130ec69f7314fb0c8`. The three review gaps are closed: generation-scoped physical-death gating, mutation-sensitive production anchor cancellation, and exact-path/literal-only analytics-guard exemption. Goal 04 is separately revised and approved; this goal must not be re-executed.

This is a narrow correction gate after the architecture review of Goal 03A.

Reviewed Goal 03A implementation/final handoff:

- implementation: `7313176fa0b9b97d79516aadaaddada6dbea5a39`
- final handoff HEAD: `b7d190a13178de894d44a7536a8e8b29526f19c7`
- push Action `36648406271`: success
- pull_request Action `36648411573`: success

Do NOT re-execute Goal 03 or Goal 03A wholesale.
Goal 04 is now separately approved by the post-03B architecture review; do not execute it as part of Goal 03B.
Do NOT execute Goal 05.
Do NOT merge PR #1.

Preserve the accepted Goal 03A corrections for:
- database-anchor deadline/cancellation design;
- `JdbcSession.currentSchema()` health semantics;
- runtime `SqlAdmission`;
- existing distinct-ItemID / all-segments / immutable-ItemID-date semantics.

Use independent subagents for:
1. scan-generation/thread-ownership design;
2. production anchor-cancellation regression test;
3. SQL source-guard exemption repair;
4. adversarial deterministic tests;
5. independent final architecture verification.

The lead agent owns integration.

---

## A. Gate release must follow actual worker/supervisor death - BLOCKING

Goal 03A improved timeout/draining behavior but still self-deregisters a scan thread
from the tracked set inside that same thread's `finally`, then may release the gate
before the Java thread has actually terminated.

That is not equivalent to observing `Thread.isAlive() == false`.

The review reproduced the forbidden ordering in a private exact-SHA clone by only
stretching the scheduling window after the existing release point:

- old releaser: `cm-insight-scan-review-supervisor`;
- old supervisor alive while `isScanInFlight()==false`: true;
- scan N+1 `requestScan()`: STARTED;
- old supervisor then created a `-lingering` watcher against scan N+1's thread set.

The pause did not reorder production statements. It only held the old supervisor
between its successful `releaseGateIfDrained()` return and the next existing statement.
A real scheduler may pre-empt at the same point.

Required invariant:

> scan N+1 may not start until every worker and supervisor belonging to scan N is
> physically dead, not merely deregistered or logically complete.

Required correction:

- a worker/supervisor may not prove its own death by removing itself before return;
- keep generation identity explicit: old-generation cleanup must never operate on
  a global thread collection that has already been repopulated by a later scan;
- no old generation may interrupt, watch, clear, classify or otherwise mutate the
  new generation's thread set or status;
- gate release must be performed by an observer that can prove the old
  worker/supervisor threads are no longer alive, or by an equivalent mechanism
  with the same proof;
- `requestScan()` must remain refused until that proof exists;
- `awaitScanCompletion()` must not report physical completion before it exists;
- after `close()`, `CloseState.CLOSING` must remain visible while any
  coordinator-owned thread from the closing context is alive;
- never report `CLOSED_CLEAN` merely because a thread removed its own reference.

A dedicated reaper/observer is acceptable only if:
- it never counts as proof of worker/supervisor death before its joins complete;
- after gate release it cannot act on a later scan's mutable state;
- its own lifetime remains visible to context close-state until it terminates.

Prefer per-generation state over coordinator-global mutable thread identity.

### Deterministic tests

Add a test hook/barrier that pauses an old worker/supervisor after its last normal
production action before actual thread return, without changing action order.

Prove:
1. old supervisor is still `isAlive()`;
2. gate remains latched;
3. scan N+1 is refused;
4. old generation cannot create/watch/interrupt anything belonging to N+1;
5. only after old supervisor death may N+1 start;
6. close state remains CLOSING until all coordinator-owned old threads are dead.

Include an opposite/mutation control that restores self-deregister-before-death.
The committed test must fail that mutant.

---

## B. Prove the production anchor cancellation wiring - REQUIRED

The implementation currently registers:

`cancellation.register(session::cancelInFlight)`

inside `JdbcStatisticsEngine.databaseCurrentDate(...)`, which is the intended design.

However, the committed tests do not discriminate that production wiring.
The review replaced only that registration with a no-op in a private clone and ran
the complete build. It still passed:

- core: 367 / 0 failures;
- IBM stub suite: 51 / 0 failures;
- `./build.sh`: exit 0.

Required correction:

Add a committed regression test that uses the REAL `JdbcStatisticsEngine` and a
fake/registered JDBC driver or connection proxy.

The database-current-date statement must block while executing. Then prove:
- overall scan deadline invokes that exact statement's `cancel()`;
- explicit scan cancellation invokes that exact statement's `cancel()`;
- context close reaches the same production registration;
- query timeout may be larger than scan timeout and the overall deadline still wins;
- the anchor is read exactly once;
- no JVM date or second database anchor appears.

Mutation control: replace the production registration with a no-op. The committed
test must fail for that mutant.

---

## C. Make the analytics guard exemption truly path-scoped - REQUIRED

Goal 03A documents one exact exemption:

`src/main/java/com/mraibo/cminsight/db/SqlAdmission.java`

But the implementation widens it by basename:

- shell: `grep --exclude=SqlAdmission.java`;
- Java twin: `file.getFileName().toString().equals("SqlAdmission.java")`.

The shell helper also applies that exclusion to ALL report patterns, including
JDBC call rules, although the documentation says the exemption is only for
statement-literal vocabulary.

The review planted:

`src/main/java/com/mraibo/cminsight/statistics/review/SqlAdmission.java`

containing a write-shaped SQL literal. Both the standalone analytics guard and the
complete build stayed green. The complete mutated build still reported:

- core: 367 / 0 failures;
- IBM stub suite: 51 / 0 failures.

Required correction:

- compare the exact repository-relative path, never only the basename;
- exempt only the literal-vocabulary checks for the canonical admission file;
- forbidden JDBC call checks must still scan the canonical admission file;
- a same-basename sibling anywhere else in either scanned tree has no exemption;
- shell and Java guard implementations must agree on the exact path and sub-rule.

Required planted controls:

1. canonical `db/SqlAdmission.java` may contain its vocabulary and still pass;
2. canonical `db/SqlAdmission.java` plus `.executeUpdate(...)` fails BOTH guards;
3. sibling `statistics/review/SqlAdmission.java` plus a write-shaped literal fails;
4. sibling `db/review/SqlAdmission.java` plus a write-shaped literal fails;
5. removing/renaming the canonical exempt path makes the exemption stale and fails;
6. `./build.sh` refuses every planted guard violation.

---

## D. Accepted Goal 03A work that must not be reopened

The architecture review accepts these parts unless the Goal 03B fix directly
requires a mechanical adaptation:

- `JdbcSession.currentSchema()`: SQLFeatureNotSupportedException / SQLState 0A000
  may remain reusable; other SQL/runtime driver failures retire the session;
- runtime `SqlAdmission`: single SELECT/WITH statement, no comments, no quoted
  region, no separator, whole-token write/control refusal;
- generic JDBC `execute(...)` / `createStatement(...)` remain forbidden;
- the analytics guard remains invoked by `build.sh`;
- logical item remains DISTINCT ItemID;
- all expected physical segments 1..N remain mandatory;
- ItemID positions 9-14 remain the time source;
- exactly one database date anchor remains required;
- Versions/Parts remain UNAVAILABLE;
- JDBC remains optional and hard-bounded;
- no emergency connection and no write operation.

Do not weaken Goal 01C repository switching or Goal 02B resource contracts.

---

## E. Validation

Run serially or from a private checkout because the Windows/MSYS host has no
working `flock` protection for concurrent builds.

At minimum:

- `./build.sh`
- `./tests/selftest.sh`
- `./tests/shell/run.sh`
- `./bin/doctor.sh`
- `./build.sh --check-ibm-isolation`
- `bash tests/shell/analytics_guard.sh`
- `bash tests/shell/analytics_source_guard_test.sh`
- IBM suite against committed stubs
- `./build.sh --require-ibm` when the local CM 8.7 SDK jars are available.

Verify zero tracked proprietary JARs and credentials.

No live DB2/Oracle database is currently known reachable from the execution host.
Do not present driver discovery as live SQL validation.

---

## F. Completion / handoff

At Goal 03B completion:

1. review the complete integrated diff;
2. run all required tests serially;
3. update STATUS.md;
4. commit coherently;
5. push every Goal 03B commit to
   `origin/bootstrap/cm-insight-architecture`;
6. verify local HEAD == remote HEAD;
7. verify push and pull_request Actions are SUCCESSFUL for the SAME final SHA;
8. keep PR #1 open/draft/unmerged;
9. set the next goal to
   **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**;
10. do NOT execute Goal 04 or Goal 05;
11. stop with a concise architecture-review handoff.
