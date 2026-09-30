# Project Status

> Canonical cross-session checkpoint. Read this first when continuing in another chat/model.

## Project state

- Project: CM Insight
- Repository: mrAibo/cm_java_item_reporter
- Active branch: `bootstrap/cm-insight-architecture`
- Goal 01 / 01A / 01B / 01C: accepted core foundation
- Goal 02 reviewed remote HEAD: `87ffeb8e6de9b5ab6577353a7c54a0c6e718dead`
- Goal 02 implementation checkpoint recorded by the execution handoff: `818cdcf45ffff02383915958424e5403ae653e00`
- Goal 02A reviewed checkpoint (the review that required this goal): `67d058c1d26d706ce75b73011e959f23c00c5868`
- Goal 02A implementation commit (the commit this section describes): `4b810b361c3c34b0b9118ddcbc560ee47d21560b`
- Goal 02A final reviewed remote HEAD: `ff4b2aa32617a6037b541143b008947ed095cfdc`
- Goal 02B reviewed checkpoint (the review that required this goal): `9ff2b474d6a42260bdd0c0a2e311b315e79fe5a5`
- Goal 02B implementation commit (the commit this section describes): `1049ab4c50c66adc682457971290deaa7dec17de`
- Goal 02B final reviewed remote HEAD: `30f8a31aaa41522c2d00b0b894801576f74ed401`
- Goal 03 reviewed checkpoint (the review that approved this goal): `c9751c290dcd7c7879503776befe1be2faec6aae`
- Goal 03 implementation commit: `1a52f908741005e82b4bf12eac17aab7373fb489`
- Goal 03 CI fix commit (the commit this section describes): `dc24dbb31b51619719416cf7da2b51b506cc6cb8`
- Goal 03 final reviewed remote HEAD: `1dcd8f30b20cf57b2abfb688cc12b1e710e4ef6a`
- Goal 03A reviewed checkpoint (the review that approved this goal): `023b47552169d9ac35058461ae07d7788a9fdafb`
- Goal 03A implementation commit: `7313176fa0b9b97d79516aadaaddada6dbea5a39`
- Goal 03A final execution/handoff HEAD: `b7d190a13178de894d44a7536a8e8b29526f19c7`
- Goal 03B reviewed checkpoint (the review that approved this goal): `155379c38e9a3afdc090c4b9991aeaf7e1bf2059`
- Goal 03B implementation commit: `6fbee64bb047bd6ff841a5a530a0897703ab5b0f`
- Stage: **Goal 03B EXECUTED / PUSHED / GREEN — PENDING ARCHITECTURE REVIEW**
- Current approved goal: none; **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**
- Goal 03: accepted analytics core; correction chain closed by Goal 03B implementation, pending review
- Goal 03A: COMPLETED / REVIEWED — its three review findings are implemented in Goal 03B
- Goal 03B: COMPLETED / PUSHED / GREEN — PENDING ARCHITECTURE REVIEW
- Goals 04-05: PROVISIONAL; do not execute

## Goal 03B execution record

Date: 2026-09-30. Branch: `bootstrap/cm-insight-architecture`.
Reviewed/approved checkpoint: `155379c38e9a3afdc090c4b9991aeaf7e1bf2059`.
Implementation commit: `6fbee64bb047bd6ff841a5a530a0897703ab5b0f`.
**PR #1 remains OPEN, draft and unmerged.**

Goal 03B is a correction closure only. It does not add Goal 04/05 functionality and does
not change the frozen analytics counting model.

### A. Scan ownership is generation-scoped and gate release follows physical death

The coordinator no longer lets a scan thread remove its own ownership record and then use
that absence as proof that the Java thread is gone. Each `ActiveScan` now owns its own
thread registry, lock, worker-registration counter and one immutable generation id.
Every owned thread is recorded with an explicit role: reaper, watchdog, supervisor or
worker.

One dedicated daemon reaper belongs to exactly one scan generation. It is the normal-path
owner of gate release. It may release the one-scan gate only when:

- the generation is sealed, so no further worker can be registered;
- no worker batch is mid-registration;
- every non-reaper worker/supervisor/watchdog belonging to that exact generation has
  `Thread.isAlive() == false`;
- the coordinator's active generation is still that generation; and
- that generation has not already released the gate.

After gate release the reaper only signals waiters and returns. It does not clear, watch,
interrupt or otherwise mutate a later generation. Cancellation is likewise generation
scoped: it walks only that scan's owned threads and deliberately does not cancel its
physical-death observer.

Coordinator lifetime accounting is separate from per-generation ownership. Dead owned
references may be pruned, but a live coordinator-owned thread remains visible to
`closeState()`. A bounded `close()` therefore stays `CLOSING` until the held
supervisor and its reaper are actually gone; a slow thread is still not reclassified as
`CLOSED_UNCERTAIN`.

The deterministic regression test places a barrier at the supervisor's final action. The
scan may already have a terminal phase and be sealed, but while that barrier holds the
supervisor Java thread is still alive. The test proves a second scan is refused until the
barrier is released and the supervisor actually terminates.

**Mutation evidence:** in a private copy, the reaper was changed to ignore the
supervisor's `isAlive()` result. The committed suite then failed exactly the relevant
Goal 03B controls:

- `ScanGateLatchTest.closeRemainsClosingUntilTheHeldSupervisorAndItsReaperAreActuallyGone`
- `ScanGateLatchTest.supervisorMustPhysicallyDieBeforeTheNextGenerationCanStart`

Result: core **375 tests / 2 failures**. The correct implementation is **375/0**.

### B. Production database-anchor cancellation is now mutation-sensitive

The existing production wiring remains
`JdbcStatisticsEngine.databaseCurrentDate(ScanCancellation)` registering
`JdbcSession::cancelInFlight`. Goal 03B adds tests that exercise that exact production
path rather than a fake `StatisticsEngine` only:

`ScanCoordinator -> JdbcStatisticsEngine -> BoundedPool<JdbcSession> -> JdbcSession ->
PreparedStatement.executeQuery()`.

A registered fake JDBC driver blocks the DB2 current-date statement
(`SELECT CURRENT DATE FROM SYSIBM.SYSDUMMY1`) and records the real
`PreparedStatement.cancel()` call. The tests prove independently that:

- a 1 s overall scan deadline cancels the blocked anchor even when the statement query
  timeout is 60 s;
- explicit `cancelScan()` reaches the same prepared statement;
- context `close()` reaches the same prepared statement;
- the deadline case remains `TIMED_OUT`, explicit/close cases remain `CANCELLED`;
- no timed-out/cancelled scan publishes a replacement snapshot; and
- the database anchor statement is issued exactly once, with no retry, JVM-date fallback
  or second boundary read.

**Mutation evidence:** replacing only the production anchor registration with a no-op
left all unrelated tests intact but made all three production-wiring tests fail:

- context close: expected one `Statement.cancel()`, observed zero;
- deadline: the prepared anchor never observed cancellation;
- explicit cancel: the prepared anchor never observed cancellation.

Result: core **375 tests / 3 failures**. The correct implementation is **375/0**.

### C. Analytics source-guard exemption is exact-path and literal-rule-only

The one allowed vocabulary declaration is now exactly:

`src/main/java/com/mraibo/cminsight/db/SqlAdmission.java`.

The exemption is compared as a repository-relative path, never by basename. It applies
only to the statement-literal vocabulary rules; the JDBC call/control rules still scan
the canonical file itself.

Committed positive/negative controls prove:

- `statistics/review/SqlAdmission.java` receives no exemption and a write-shaped SQL
  literal is refused;
- `db/review/SqlAdmission.java` receives no exemption and is refused the same way;
- the canonical exempt path containing a real JDBC write call is still refused;
- removing/renaming the canonical exempt target makes the guard fail closed as a stale
  exemption;
- all previously required SELECT-wrapped DML, CTE DML, separator, comment-obfuscation
  and generic JDBC call plants still bite; and
- all approved read-only production forms still pass.

The explicit shell guard regression reports **93 checks / 0 failures**.

### Frozen semantics and safety boundaries preserved

Goal 03B does not change:

- logical item = one distinct `ItemID`;
- per-segment DISTINCT plus cross-segment union/dedup;
- every root segment 1..N and missing-middle-segment failure;
- immutable ItemID creation-date windows and one database date per scan;
- Versions/Parts = `UNAVAILABLE`;
- partial-failure snapshot coverage and atomic publication;
- timeout/cancel/catastrophic scans publish nothing;
- hard-bounded lazy JDBC with no ad-hoc connection;
- JDBC optional to CM repository activation;
- `JdbcSession.currentSchema()` health semantics from Goal 03A;
- runtime `SqlAdmission` from Goal 03A;
- Goal 01C / Goal 02B close/create/health contracts;
- authenticated analytics API and action-header rule; or
- the no-vendor-JAR-in-Git rule.

The implementation diff touches only `ScanCoordinator` plus Goal 03B tests/guard
surfaces. `BoundedPool`, `RepositoryManager`, `ResourceFactory`,
`CreationFailure` and the accepted counting/dialect classes are unchanged.

### Validation actually executed

Validation was run serially in a private clean WSL clone based on the reviewed checkpoint
with the exact implementation diff applied, using OpenJDK **17.0.20.1**:

- `./build.sh` — exit 0; core **375/0**, IBM stub **51/0**, jar packaged.
- `./tests/selftest.sh` — exit 0; core **375/0**, IBM stub **51/0**.
- `./tests/shell/run.sh` — exit 0; **5 passed, 0 failed**, including the 58 s lifecycle
  identity race/soak suite.
- `./bin/doctor.sh` — exit 0 after supplying the normal local ignored
  `conf/application.properties`; **0 failures, 11 warnings**. The warnings are the
  expected local development-credential / absent optional-runtime-library notices.
- `./build.sh --check-ibm-isolation` — exit 0.
- `bash tests/shell/analytics_guard.sh` — exit 0.
- `bash tests/shell/analytics_source_guard_test.sh` — exit 0,
  **93 checks / 0 failures**.
- `git diff --check` — clean.
- tracked proprietary JAR check — **zero tracked `*.jar` files**.

**Real IBM CM 8.7 SDK, separately:** four local SDK JARs were staged only in the private
validation copy and removed from the repository workflow. `./build.sh --require-ibm`
exited 0, compiled **17 IBM adapter sources** and **12 IBM test sources**, ran core
**375/0** and the real-SDK IBM suite **51/0**, and packaged the jar. The SDK printed its
known local logging-configuration warning (`cmbcmenv.properties` / Log4J2 fallback);
that is not a compile/test failure and is unchanged from earlier goals.

### Live environment status

**NO LIVE DB2 OR ORACLE SQL VALIDATION WAS PERFORMED.**
No reachable CM Library Server database is available from the execution host. A fake
JDBC driver and a loadable vendor driver are not presented as live database evidence.

**NO SUCCESSFUL LIVE IBM CM SERVER VALIDATION WAS PERFORMED.**
The real IBM SDK result above is compile/test compatibility evidence, not proof of a live
CM connection.

### GitHub Actions for the implementation commit

Exact implementation SHA `6fbee64bb047bd6ff841a5a530a0897703ab5b0f`:

- push run `36711455135` — **success**;
- pull_request run `36711461832` — **success**.

Both events report the same implementation SHA.

### Remaining risks

1. Live DB2/Oracle query validation remains the largest evidence gap.
2. Live IBM CM server success is still unavailable from this host.
3. A JDBC driver that ignores `Statement.cancel()` intentionally keeps its scan
   generation draining and blocks a later scan until the physical query thread exits.
4. The Windows/MSYS working tree still cannot safely host concurrent builds when
   `flock` is unavailable. Final validation therefore used one private WSL clone
   serially.
5. The IBM SDK still needs its normal production logging configuration; its fallback
   warning is unrelated to Goal 03B.

### Architecture / next-goal state

No product architecture decision changed. Goal 03B makes the already-required one-scan
invariant physically true, closes the production test blind spot, and narrows a guard
exception to the exact scope it documented.

**Next goal: NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.**
Do not execute Goal 04 or Goal 05. Do not merge PR #1.

### Resume / review instruction

"Continue CM Insight in `mrAibo/cm_java_item_reporter` on branch
`bootstrap/cm-insight-architecture`. Fetch and fast-forward first. Read `STATUS.md`,
`ARCHITECTURE.md`, `SECURITY.md`, `DATA_MODEL.md`, `harness/MASTER_GOAL.md`,
`harness/GOAL_03A_SCAN_LIFECYCLE_AND_SQL_GUARD_HARDENING.md` and
`harness/GOAL_03B_SCAN_GENERATION_OWNERSHIP_AND_GUARD_CONTRACT.md` completely.
Goal 03B is executed, pushed and green on both implementation Actions events. REVIEW
Goal 03B before approving anything further; do not re-execute Goal 03/03A/03B and do
not execute Goal 04/05. Preserve the generation-scoped physical-death gate, production
anchor-cancel tests, exact-path literal-only guard exemption, frozen distinct-ItemID
analytics semantics and all accepted Goal 01C/02B resource rules."

## Goal 03A architecture review — changes required

Reviewed tree: `b7d190a13178de894d44a7536a8e8b29526f19c7` on
`bootstrap/cm-insight-architecture`. PR #1 remains open/draft/unmerged.

The review accepts the currentSchema health correction and the runtime SQL admission
correction. It also accepts the intended one-anchor cancellation design as production
code, but requires a production-wiring regression test before that claim is considered
closed. The scan lifecycle correction is **not accepted yet** because a worker/supervisor
can still disappear from tracking before its Java thread actually terminates.

### Blocking lifecycle finding: self-deregister is not physical thread death

`ScanCoordinator` calls `deregisterThread(Thread.currentThread())` from a worker,
supervisor or watchdog `finally`, then may open the one-scan gate from that same still-live
thread. The gate therefore proves "not present in the list", not `Thread.isAlive()==false`.
That distinction is exactly the invariant Goal 03A was meant to make structural.

The review reproduced the forbidden schedule in a private exact-SHA clone by inserting
only a barrier after the existing successful gate-release call. No action order was
changed. With a scheduler delay that made the old supervisor the releaser, the probe
reported:

- `FIRST_RELEASER=cm-insight-scan-review-supervisor`
- `OLD_SUPERVISOR_ALIVE_WITH_GATE_OPEN=true`
- `SECOND_SCAN_STARTED_WHILE_OLD_SUPERVISOR_ALIVE=true`
- `STALE_SUPERVISOR_CREATED_LINGERING_WATCHER=true`
- `SUPERVISOR_GENERATION_VIOLATION=true`

After scan N+1 repopulated the coordinator-global thread list, the still-live old
supervisor resumed and ran `startLingeringWatcher()` against N+1's threads. This proves
that the defect is not only a wording issue: old-generation cleanup can operate on a new
generation's mutable tracking state.

### Required test closure: production anchor cancellation wiring

The production implementation correctly registers
`cancellation.register(session::cancelInFlight)` in
`JdbcStatisticsEngine.databaseCurrentDate(...)`. However, the committed tests currently
prove the cancellation policy through a fake engine, not that production registration.

Review mutation: replacing only the production anchor registration with a no-op left the
complete `./build.sh` green: core **367/0**, IBM stub **51/0**, exit **0**. Goal 03B must
add a real-`JdbcStatisticsEngine` fake-JDBC test whose blocked current-date statement
observes `PreparedStatement.cancel()`, with an opposite mutation that fails.

### Required guard closure: the declared path exemption is basename-wide

The analytics source guard documents one exact literal-vocabulary exemption:
`src/main/java/com/mraibo/cminsight/db/SqlAdmission.java`. The shell implementation uses
`grep --exclude=SqlAdmission.java`, and the Java twin compares only `file.getFileName()`.
The shell helper also applies that basename exclusion to JDBC call rules although the
contract says only the literal-vocabulary sub-rule is exempt.

Review mutation: adding
`src/main/java/com/mraibo/cminsight/statistics/review/SqlAdmission.java` with a
write-shaped SQL literal passed the standalone analytics guard and the entire build.
The mutated build again reported core **367/0**, IBM stub **51/0**, exit **0**. Goal 03B
must make the exemption exact-path-only and literal-rule-only, and must plant same-basename
siblings as negative controls.

### Baseline verification and accepted 03A areas

A clean local clone of exact SHA `b7d190a` under WSL / JDK 17.0.20.1 ran
`./build.sh` successfully: core **367/0**, IBM stub **51/0**, jar built. The earlier
`git archive` and Windows-created-worktree reds were review-environment artefacts (`.git`
metadata unavailable/mis-addressed to WSL), not product failures; the real local clone
removed both artefacts.

Accepted from Goal 03A and not to be reopened in 03B unless mechanically necessary:
`JdbcSession.currentSchema()` health semantics; runtime `SqlAdmission`; generic JDBC
`execute`/`createStatement` refusal; analytics guard integration into `build.sh`; frozen
logical-item/date/segment semantics; optional hard-bounded JDBC; and all Goal 01C/02B
resource rules.

No live DB2 or Oracle SQL validation was performed during this review. A green driver or
SDK compile is not live database evidence.

**Next goal: `harness/GOAL_03B_SCAN_GENERATION_OWNERSHIP_AND_GUARD_CONTRACT.md` — APPROVED / EXECUTE.**
Do not execute Goal 04 or Goal 05. Do not merge PR #1.

### Resume / execution instruction

"Continue CM Insight on `bootstrap/cm-insight-architecture`. Fetch and fast-forward first.
Read `STATUS.md`, `ARCHITECTURE.md`, `SECURITY.md`, `DATA_MODEL.md`, `harness/MASTER_GOAL.md`,
`harness/GOAL_03A_SCAN_LIFECYCLE_AND_SQL_GUARD_HARDENING.md` and
`harness/GOAL_03B_SCAN_GENERATION_OWNERSHIP_AND_GUARD_CONTRACT.md` completely. Execute ONLY Goal 03B.
Do not re-execute Goal 03/03A and do not execute Goal 04/05. Preserve the accepted Goal 03A
currentSchema and runtime SqlAdmission changes. Fix physical scan-generation ownership, add a production
JdbcStatisticsEngine anchor-cancel mutation-sensitive test, and make the analytics guard exemption exact-path
and literal-rule-only. Commit/push yourself, verify local==remote and both exact-SHA Actions green, leave PR #1
draft/unmerged, then stop for architecture review."

## Goal 03A execution record

Date/time: end of the Goal 03A execution session (local time). Branch:
`bootstrap/cm-insight-architecture`. **PR #1 remains OPEN, draft, unmerged.**

Checkpoint protocol: a commit cannot truthfully record its own SHA, and this file
changes the tree, so the commit above is the implementation commit this section
describes. The authoritative head is read from Git, and the handoff report records the
exact local and verified remote HEAD after this documentation commit has been pushed.

### A. The one-scan gate is latched until every worker is dead

**The defect:** the reviewed code computed a second `joinWorkers` result and
**discarded it**, then cleared `scanInFlight` and the tracked-thread list
unconditionally. A worker that ignored cancellation past the drain grace therefore lost
both the gate and its thread reference, and a later scan could start while it still held
a JDBC lease. A deadline or a cancel decides **publication and status**; it never proved
physical execution had ended.

**The fix is structural, not argued:** one `AtomicBoolean` owner plus a CAS on the gate,
taken only when the scan is **sealed** (no worker can be registered again), no worker
batch is mid-start, and **every tracked thread is dead** - with the emptiness test and
the reference clear in one critical section, and only dead references ever pruned. The
last thread to exit releases the gate, whichever it is: a worker's own `finally`, the
supervisor's, or the per-scan watchdog. The no-close lingering case is covered, which is
the case that was broken.

**Three defects surfaced while hardening this**, each found by a deterministic probe
rather than by reading the diff, and the deepest was a **regression of the ordinary
path**:

1. The lingering watcher counted **itself** as live scan work, so it could never observe
   an empty set. A monitor must not be a member of the set it monitors. This wedged the
   coordinator for **any** scan whose worker lingered.
2. The supervisor attempted release **before sealing itself**, so when it was the last
   thread its only attempt was refused and the gate latched for ever.
3. The watchdog read its own deadline-triggered cancel as an **external** stop, so it
   never recorded `TIMED_OUT` at all - which is why abort actions ran while the phase
   stayed `RUNNING`.

The combined result was a coordinator that could never let go: `requestScan()` refused
permanently and `close()` reported `CLOSING` permanently, which `RepositoryManager` maps
to a permanent `Refusal.PENDING` - **repository switching bricked until JVM restart**.
That is strictly worse than the defect being fixed, so it is recorded plainly: section
A's cure briefly became more dangerous than its disease. It also regressed nine
previously-green Goal 03 assertions, so it was a happy-path regression and not an edge
case.

**Draining is a separate readable fact:** `isDraining()` and `lingeringScanThreadCount()`
beside `isScanInFlight()`, with **no new Phase value** and no new `ScanStartResult` value,
so existing exhaustive switches still compile and a reader can distinguish "the result is
final" from "physical work is still running" - the distinction the phase-only model could
not express.

**Coordinator close state:** it implements `CloseStateAware` - `NOT_CLOSED` before close,
`CLOSING` while a scan thread lives, `CLOSED_CLEAN` only once all are dead, and
deliberately **never** `CLOSED_UNCERTAIN` for a merely slow thread. That last point is
load-bearing rather than pedantic: `CLOSED_UNCERTAIN` is permanent and would brick
switching, while `CLOSING` is recoverable `Refusal.PENDING`. No new registration path was
needed; the coordinator is already registered after the pool, and reverse close order
already closes it first.

### B. The overall scan deadline now governs the anchor query

**The defect:** `databaseCurrentDate()` took no cancellation input at all and ran before
any worker existed, so neither the overall deadline nor an explicit cancel could reach its
`Statement.cancel()`.

**The fix:** `databaseCurrentDate(ScanCancellation)` registers its abort action through the
**same** `ScanCancellation` as an ItemType query, so the deadline, an explicit cancel and a
context close all reach it. One **absolute** deadline from `statistics.scan.timeout.seconds`
is enforced by **one bounded daemon watchdog per active scan** - no executor, no queue -
which exits on the first of deadline, cancel/close, or supervisor-complete, the last
signalled from a `finally` so it cannot outlive its scan even on an `Error` path. **No path
reads `statistics.query.timeout.seconds`**; that remains only the JDBC layer's
per-statement cap. The goal's required configuration - per-query cap **larger** than the
scan deadline - is verified to still let the scan deadline win. The anchor is still read
**exactly once**, from the database, with no JVM date and no invented boundary. If the
driver ignores cancellation, section A applies: the scan stays draining and blocks a new
scan rather than pretending it finished.

### C. `currentSchema()` health verdict

**The defect:** it rethrew `SQLException` without marking anything, so the lease return
handed the poisoned physical connection to the next query.

**The rule now:** exactly **one** benign case - `SQLFeatureNotSupportedException`, or the
documented feature-not-supported SQLState `0A000` - meaning the driver cannot report
schema, where the operation is refused and the connection **may** stay reusable. **Any
other** `SQLException` or unexpected runtime driver failure marks the session unusable
**before** the sanitized failure propagates. The sanitized shape keeps the operation label
plus SQLState and vendor code and never the raw message:
`JDBC operation 'read current schema' failed (SQLState=08006, vendorCode=1234)`. The old
javadoc was **narrowed rather than deleted**, so the capability reasoning survives for the
case it is true of and the next reader cannot merge the two catches back into one.

**The assertion that actually discriminates:** through a real pool, a subsequent borrow
receives a **different physical connection** (`sameInstance=false`, physical opens 1→2),
with an opposite control showing the pre-fix class fails that exact test. `isHealthy` was
not touched and still measures **zero driver calls over 1000 answers**.

### D. Strengthened SQL read-only boundary

**The defect:** `requireSelect` was a prefix test - it `stripLeading()`ed and region-matched
the first 4-6 characters against `SELECT`/`WITH`, so anything starting that way was
admitted. That is not a structural proof, because a data-changing construct can be nested
inside a `SELECT`/`WITH` shape.

**The rule now:** `SqlAdmission` admits only the narrow language this application generates
- one statement, no comments, no quoted region, begins with `SELECT` or `WITH`, and no
write/control keyword as a **whole token**. It is token-aware, not substring-based, so
`UPDATED_AT`, `DELETED_FLAG` and `CREATE_TS` are ordinary identifiers and are admitted -
and the false-positive controls assert exactly that, because a rule that refused real
column names would be weakened by whoever hit it.

**Refused:** `SELECT * FROM FINAL TABLE (DELETE FROM ...)`, a DML token inside a CTE,
`SELECT ...; DELETE ...`, and a comment-obfuscated token, plus `INSERT`, `UPDATE`,
`DELETE`, `MERGE`, `TRUNCATE`, `CREATE`, `ALTER`, `DROP`, `GRANT`, `REVOKE`, `CALL`,
`BEGIN`, `EXEC`/`EXECUTE`, `COMMIT`, `ROLLBACK`, `SAVEPOINT`, and the data-change-table
vocabulary `FINAL`/`OLD`/`NEW`. **Accepted:** all nine approved production statement
families, taken from the real dialects rather than hand-copied.

**The generic escape hatch is closed:** production analytics needs no
`execute(`/`createStatement(` - the only driver path is `prepareStatement` then
`executeQuery` - verified by grep and now forbidden in the committed guard, with a control
that each declared pattern still matches a realistic call and is genuinely refused.

**The literal rule was reviewed and found walkable before it shipped**, which is the most
valuable thing in this section. It recognised only a bare apostrophe opener, so a literal
introduced by a vendor **prefixed** form (`N'...'`, `X'...'`, `B'...'`, `q'[...]'`) was not
seen as a literal - and because the tokenizer skips quoted regions, the write verb inside it
was not seen as a token either. `SELECT 1 AS A FROM T WHERE ID = N'DELETE FROM Y'` would
have been **admitted**. The rule now refuses **any quote character**, which is the honest
narrowing rather than a bet that a prefix list is complete: generated analytics SQL contains
no literal and no quoted identifier, so every form refused is a form this application
cannot emit.

### Also fixed en route, all found by running rather than reading

- **The analytics read-only guard was not a build-time control.** Goal 03A section F lists
  it as one, but only the shell suite invoked it, so a tree violating the rule still
  produced a **green `./build.sh`**. `build.sh` now runs it on the same footing as the IBM
  guard, before the toolchain is resolved, as a hard failure. Proven with a planted write:
  **exit 2** naming the file and the rule, and green again once removed.
- **The shell guard and its Java twin had diverged again** - the second time in this
  project. The shell guard exempted the file that declares the forbidden vocabulary while
  the Java twin refused it, so one rule was enforced two ways and only one build failed.
  The Java twin now carries the **same single path-scoped exemption**, applied to the
  keyword-literal rules only and **never** the call rules, and its honesty is asserted in
  both directions: the exempt file must **exist**, and a file combining the exempt
  vocabulary with a real `.executeUpdate(` is **still refused and named**. The two guards'
  agreement is now asserted from both sides so changing one cannot silently leave the
  other behind.
- **An undefined character class.** The guard used `[^A-Za-z0-9_]`, whose `Z-a` is not an
  ascending range and therefore has **undefined** meaning in POSIX. It is now the
  well-defined `[^[:alnum:]_]`. Worth recording that the owner **measured** the causal claim
  rather than accepting mine: reverting only that class did **not** restore the failures,
  because what actually fixed them was the rule's shape - so the class change is
  portability hardening, not the cure, and the test says so. The real lesson is the new
  per-rule **sensitivity control**: every statement-literal rule now has a known-bad sample
  it must refuse **and** a known-good sample it must accept, so a pattern that silently
  matches nothing - indistinguishable from a clean tree - can no longer be declared.
- **Two latent defects in the guard test** were fixed by its owner: a stale variable in the
  text-block control, and a method-name extraction that had degenerated into searching for
  a method called `"("`.

### Frozen semantics preserved

Logical item = **distinct `ItemID`**; per-segment `SELECT DISTINCT` with cross-segment
dedup; every expected root segment 1..N verified with a **missing middle segment failing
the ItemType**; creation windows from the immutable `ItemID` date; **one** database anchor
per scan; total stays `AVAILABLE` when date windows are unrepresentable; **Versions and
Parts remain `UNAVAILABLE`**; an ItemType failure stays distinguishable from a genuine
zero; a completed partial-failure scan may publish with coverage while
timeout/cancel/catastrophic scans publish **nothing** and the previous snapshot stays
visible; JDBC remains optional to CM activation with no eager `initialize` and no ad-hoc
connection; and no raw `SQLException`, SQL, JDBC URL or credential leaks.

**Core safety surfaces are untouched.** `BoundedPool`, `CreationFailure`, `ResourceFactory`,
`RepositoryManager` and `CloseState` are **byte-identical** to the reviewed checkpoint
`023b475`, so the Goal 01C latch and the Goal 02B create/health contract were not weakened
to make scan cleanup easier.

### Tests actually executed, with results

**Stub path**, solo runs on a quiet tree (this matters - see the risk below): `./build.sh`
**exit 0**, core **"Tests run: 367, failures: 0"**, IBM **"Tests run: 51, failures: 0"**, jar
packaged; `./tests/selftest.sh` **exit 0** with the same counts; `./tests/shell/run.sh`
**exit 0**, all five suites; `./bin/doctor.sh` **exit 0** (0 failures, 15 warnings);
`./build.sh --check-ibm-isolation` **exit 0**; `bash tests/shell/analytics_guard.sh`
**exit 0**; `bash tests/shell/analytics_source_guard_test.sh` **exit 0**, **"checks: 88,
failures: 0"**.

**Real IBM CM 8.7 SDK, reported separately:** `./build.sh --require-ibm` **exit 0** with the
real jars staged (4 jars, then removed), compiling **17 adapter and 12 test sources** and
running the full IBM suite **51/0 on that real-SDK class path**.

**Independent verification of the integrated result: PASS, 13/13 criteria, no product
defect**, by a verifier that wrote none of it and pinned the revision into a pristine
`git archive` extract plus its own 123-check adversarial harness. It reproduced the hostile
worker keeping the gate latched (close state `CLOSING`, explicitly **not**
`CLOSED_UNCERTAIN`), exactly one release transition to `CLOSED_CLEAN` with zero threads
leaked, the anchor cancel action **observed invoked** at 1007 ms with a 60 s per-query cap
against a 1 s scan deadline, `TIMED_OUT` with no new snapshot and `CANCELLED` on explicit
cancel, the different-physical-connection identity assertion, 36 write/obfuscation shapes
refused with 11 legitimate reads and all 12 real production statements accepted, and 18/18
compared core files byte-identical to the checkpoint. Its mutation control is the strongest
evidence in this goal: neutralising the aliveness test in `releaseGateIfDrained()` makes its
section A fail 8 checks **and** the authors' `ScanGateLatchTest` fail 2.

### Live DB2/Oracle status - honest

**NO LIVE DB2/ORACLE SQL VALIDATION WAS PERFORMED.** No DB2 or Oracle CM database is
reachable from the execution host, no comparison against a trusted source was run, and
nothing here is presented as live SQL evidence. A driver JAR being loadable is not live
database validation.

### GitHub Actions - both events, same SHA

| Commit | push run | pull_request run |
| --- | --- | --- |
| `7313176` (implementation) | `36648124366` | `36648128810` |

Both runs were **in progress** when this record was written, and are reported as such rather
than assumed either way. The handoff report states their final conclusions, and the previous
goal's precedent is that a red run is diagnosed and repaired before the goal is called done.

### Unresolved risks

1. **No live DB2/Oracle SQL validation** - still the largest gap, and unchanged by this goal.
2. **`build.sh` is not concurrency-safe on this host.** `flock` is unavailable in the MSYS
   shell, so a second `build.sh` deletes and recreates `build/` under a running suite. This
   produced `NoClassDefFoundError` for nested test classes **and** for main classes in
   repeated runs during this goal, and cost several cycles before it was recognised as an
   environment property rather than a defect. **Every count above was measured solo.**
3. **`SqlAdmission` may be broader than strictly required** - it also refuses every whole
   token `OLD`/`NEW`/`FINAL` and every quoted region. Verified not to refuse any production
   statement, but it is a narrowing beyond the literal mandate.
4. **The `requireSelect` prefix weakness is only fixed for generated SQL.** The admission
   rule is a lexical refusal layer for one known-good generator, explicitly **not** a DB2 or
   Oracle parser, so a hand-written statement crafted to pass the lexical rule would not be
   caught by it - which is why the committed source guard and the SELECT-only account
   privileges remain part of the defence.
5. **A driver that ignores `Statement.cancel()` leaves the scan draining.** That is the
   intended conservative behaviour, but it means one uncooperative driver can refuse new
   scans until its query returns.
6. **Concurrent builds remain undetectable on this host** (same root cause as 2), so a
   verifier must work in a private copy or wait for a quiet tree.
7. **The IBM SDK needs its own logging configuration** (unchanged from Goal 02B).
8. **`StatisticsAvailability`'s static `available()` factory is unreferenceable** because the
   record accessor shadows it - cosmetic, reported by the api member in Goal 03.

### Architecture decisions and goal state

No architecture rule was changed. Goal 03A **made one implicit rule explicit** (release the
scan gate only when physical work has ended, and publish that fact as draining), **brought
one operation inside an existing policy** (the anchor query into the scan deadline and
cancellation domain), **narrowed one permissive path** (`getSchema` now retires except for a
single documented capability case), **replaced one prefix test with a lexical admission
rule** for generated SQL, and **promoted one guard to a build-time control**.

**Next goal: NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.** Do not execute Goal 04 or
Goal 05. Do not merge PR #1.

### Resume / review instruction

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture.
Fetch and fast-forward to the current remote state. Read STATUS.md, ARCHITECTURE.md, SECURITY.md,
DATA_MODEL.md, harness/MASTER_GOAL.md and harness/GOAL_03A_SCAN_LIFECYCLE_AND_SQL_GUARD_HARDENING.md
completely. Goals 01 through 01C are accepted; Goal 02 was reviewed with changes required; Goals 02A
and 02B are accepted; Goal 03 is accepted apart from the four corrections Goal 03A closed; Goal 03A is
executed and pushed. REVIEW Goal 03A before approving anything further - do not re-execute it and do
not execute Goal 04 or Goal 05. Preserve the accepted optional-SDK/read-only/API architecture, the
frozen distinct-ItemID counting semantics, the one-anchor-per-scan rule, the hard-bounded lazy JDBC
pool with no connection outside its factory, the latched one-scan gate with its separate draining
fact, and every Goal 01A-01C hard-bound, fail-closed, Linux lifecycle and security invariant. Carry
forward four facts: no live IBM CM validation and no live DB2/Oracle SQL validation has been
performed because neither a CM server nor a database is reachable from the execution host; this
repository is developed on Windows where git does not record the executable bit, so validate on Linux
with JDK 17 before trusting a CI-touching change; for the same reason a second build in one working
tree cannot be detected on that host, so verify in a private copy and treat nested-class
NoClassDefFoundError as a concurrency artifact to re-run solo; and the real IBM CM 8.7 SDK jars are a
local prerequisite that must never be committed."

- Goals 04-05: PROVISIONAL; do not execute

## Goal 03 external architecture review

Reviewed remote SHA:

`1dcd8f30b20cf57b2abfb688cc12b1e710e4ef6a`

Formal evidence verified from GitHub:

- branch HEAD exactly matched `1dcd8f30b20cf57b2abfb688cc12b1e710e4ef6a`;
- final push Actions run `36616502870`: **success**, exact same head SHA;
- final pull_request Actions run `36616509445`: **success**, exact same head SHA;
- PR #1 remained open, draft and unmerged.

### Review verdict

**THE JDBC/SQL ANALYTICS CORE IS SUBSTANTIALLY ACCEPTED. TWO SCAN-LIFECYCLE BLOCKERS AND TWO SAFETY
HARDENING GAPS MUST BE CLOSED BEFORE GOAL 04.**

Accepted after source review:

- lazy hard-bounded JDBC pool; no eager database connection on repository activation;
- one production `DriverManager.getConnection` allocation boundary in `JdbcSessionFactory`;
- known-clean pre-allocation JDBC failures use explicit `PROVEN_CLEAN`, and post-allocation unknown
  cleanup remains conservative/quarantined;
- mandatory local-only `ResourceFactory.isHealthy`;
- DB2/Oracle complete-statement dialect design;
- the DB2 anchor query uses SELECT rather than VALUES, preserving the SELECT-only surface;
- PhysicalSchemaResolver uses the proven CM metadata query, requires exactly one root component and
  enumerates every expected segment 1..N;
- logical counts deduplicate ItemID across versions and segments;
- creation-date windows use the frozen ItemID date-key semantics and one database date per completed scan;
- versions/parts remain unavailable;
- immutable snapshot publication and partial-failure accounting;
- authenticated statistics routes and action guard;
- source/type guards keep JDBC handles inside the db package and vendor JARs out of Git.

No live DB2/Oracle server validation was available, so SQL correctness remains documented semantics +
deterministic fake-driver evidence until a reachable CM database exists.

### BLOCKER 1 — a timed-out/cancelled scan can release the one-scan gate while an old worker is still alive

`ScanCoordinator.runScan()` performs:

1. join workers until the scan deadline;
2. on lingering workers, cancel/interrupt them;
3. perform a second join for the fixed `DRAIN_GRACE`;
4. **ignore whether that second join still found a living worker**;
5. call `finishScan()`.

`finishScan()` then clears `active`, sets `scanInFlight=false` and clears the tracked thread list.

Therefore a worker/driver that ignores `Statement.cancel()` and interruption beyond the grace period can
still be executing and holding a JDBC lease after the coordinator reports no scan in flight. A new refresh
can then start and overlap the old physical query. The old thread is also no longer tracked.

That contradicts the advertised invariant "one scan per RepositoryContext" and the Javadoc claim that the
gate is released only after all workers are gone.

The correction must keep the scan ownership/gate latched until every old scan worker is actually dead. A
deadline may make the scan terminal-for-publication (TIMED_OUT, publish nothing), but it does not prove the
physical work has stopped.

### BLOCKER 2 — the overall scan deadline does not cover the database-current-date query

The coordinator computes `deadlineNanos`, then synchronously calls
`engine.databaseCurrentDate()` before worker startup.

That call:

- has no `ScanCancellation` parameter;
- does not register `JdbcSession.cancelInFlight`;
- is not bounded by the remaining overall scan deadline;
- uses only the independent statement query timeout.

The configuration allows `statistics.query.timeout.seconds` to exceed
`statistics.scan.timeout.seconds`. Therefore the scan can exceed its advertised overall timeout while the
anchor query is blocked, and an explicit scan cancellation/context close cannot reliably reach
`Statement.cancel()` for that query.

The one database anchor remains the correct semantic design, but it must participate in the same
cancellation/deadline mechanism as ItemType queries.

### HIGH 3 — current-schema SQLException can return a broken connection to the idle pool

When no schema is configured, `JdbcSession.currentSchema()` calls `Connection.getSchema()`.
Every query SQLException conservatively poisons a session, but currentSchema currently catches every
SQLException and leaves the session healthy.

A feature-not-supported answer can legitimately leave the connection reusable. A generic SQL failure
(connection loss, driver failure, etc.) is not proof of health and should retire the session before the
lease returns.

Goal 03A must distinguish the benign unsupported case from other SQL failures and add a subsequent-borrow
test proving a poisoned session is not handed out again.

### HIGH 4 — SELECT/WITH prefix checking is not a structural write-proof

The current runtime `requireSelect()` verifies only that SQL begins with `SELECT` or `WITH`.
The committed source guard rejects write statements whose literals begin with a DML/control keyword.

That is useful defence, but it is not sufficient for the product claim that the analytics query surface
cannot express a write. Both DB2 and Oracle SQL families have statement shapes where a SELECT/WITH can
contain a data-changing construct; a prefix-only admission rule therefore proves the first token, not the
absence of a write.

Current production SQL is generated and review-clean, so this is a hardening gap rather than evidence that
Goal 03 currently executes DML. Close it before adding persistent history/report code:

- strengthen the runtime/generated-query gate so dangerous DML/control tokens cannot be smuggled inside an
  admitted SELECT/WITH statement;
- refuse multi-statement/comment-obfuscation shapes that the approved generated queries do not need;
- preserve all currently generated DB2/Oracle SELECT/CTE analytics SQL;
- add planted controls such as a SELECT data-change-table-reference and a WITH/DML shape, proving the
  runtime gate and source guard reject them.

Do not build a general SQL parser. A deliberately narrow allow-list for this application's generated
read-only SQL is preferred to an incomplete permissive parser.

### Non-blocking observations

- Oracle still lacks a local real-driver discovery run and neither database family has live SQL execution.
- ItemID string-window ordering has deterministic tests but still needs representative live DB validation.
- The CI executable-bit defect was fixed by `dc24dbb`; the final exact-SHA runs are green.

### Goal state after review

- Goal 03: completed/reviewed; JDBC/SQL architecture accepted, correction required.
- Goal 03A: **COMPLETED / PENDING ARCHITECTURE REVIEW** (superseded; see the Goal 03A execution record at the top).
- Goals 04-05: **PROVISIONAL / DO NOT EXECUTE**.

## Goal 03 execution record

Date/time: end of the Goal 03 execution session (local time). Branch:
`bootstrap/cm-insight-architecture`. **PR #1 remains OPEN, draft, unmerged.**

Checkpoint protocol: a commit cannot truthfully record its own SHA, and this file
changes the tree, so the commits above are the implementation and CI-fix commits this
section describes. The authoritative head is read from Git (`git rev-parse HEAD` /
`git ls-remote origin refs/heads/bootstrap/cm-insight-architecture`), and the handoff
report records the exact local and verified remote HEAD after this documentation commit
has been pushed.

### Exact logical-item counting semantics

**A logical item is ONE DISTINCT `ItemID`.** IBM documents the component-root table as
carrying `(ItemID, VersionID)` and documents that all versions of one item share the same
`ItemID`, so a bare `COUNT(*)` over an `ICMUT` root table is **not** a logical-item count
and is never used. Every aggregate branch is `SELECT DISTINCT`, branches are combined with
`UNION` (never `UNION ALL`), and the total is `COUNT(DISTINCT ITEMID)` as an independent
second guard. `COUNT(*)` appears nowhere in the analytics production path.

For a segmented ItemType the logical set is the **union of every expected root segment
1..N**. `PhysicalSchema` carries the whole ordered table list and its constructor refuses
an empty list and refuses `rootTables.size() != currentSegmentId`, so "count only the
current segment" is not expressible. A **missing middle segment fails that ItemType's
mapping** and names it (e.g. "segment 2 of 3"); it is never skipped.

This dedup requirement bit twice, and both were invisible from reading one side of a seam:

1. `UNION` deduplicates only **across branches**. For a **single-segment** ItemType there is
   no `UNION` operator, so a versioned item's several root rows each carried their own
   window flag and the `SUM` counted that item once per **version**, inflating every window
   count. That is the most common ItemType shape. Fixed by making every branch
   `SELECT DISTINCT` plus `COUNT(DISTINCT ItemID)`.
2. The verifier independently confirmed the semantics with a live control: for a catalogue
   where `ItemID` A has two versions in segment 1 **and** a row in segment 2, the logical
   total is **1** where a bare `COUNT(*)` shape yields 4, and removing `DISTINCT`/
   `COUNT(DISTINCT)` makes the same statement report 4 instead of 3.

### Exact creation-date and window semantics

The creation date comes from the **immutable `ItemID`**, never root-row `CreateTS`: IBM
records `CreateTS` as when that physical entry was created, and a versioned item has
several root rows, so it is not proof of original logical creation. The documented encoding
is position 9 (`A`=20xx, `B`=21xx), positions 10-11 year, position 12 month `A`..`L`,
positions 13-14 day. The six-character key `SUBSTR(ItemID, 9, 6)` is fixed-width, so it
**sorts chronologically as text** and a window test is a relational comparison on the
encoded string rather than a decode inside SQL.

Only the documented 2000-2199 range is supported. Outside it `ItemIdDateKey.encode`
answers empty - it does **not** invent a century mapping - and the four time metrics become
`UNAVAILABLE` while the **total stays `AVAILABLE`**, because the total never uses the date
key. The verifier decoded the whole range independently (73,049 days) and confirmed the
ordering property and the month/year/leap boundaries.

**One anchor per scan:** the anchor is the **database's** current date read **once** at scan
start through the selected dialect, and every ItemType in that snapshot uses it, so a long
scan cannot cross midnight into inconsistent windows. No JVM-local date decides a boundary
(verified independently: the JVM date is never consulted for a window).

Windows are half-open, `[start, end)`: today `[today, tomorrow)`; last 7 days
`[today-6d, tomorrow)`; last 30 days `[today-29d, tomorrow)`; current year
`[Jan 1, Jan 1 next year)`. One shared `tomorrow` boundary serves the first three.

### JDBC pool and resource contract

`BoundedPool<JdbcSession>` is the **only** source of JDBC connections: exactly **one**
`DriverManager.getConnection` call site exists in the whole tree, inside the JDBC
`ResourceFactory`. There is no emergency, diagnostic, schema-discovery or retry connection
outside the pool.

Creation is **lazy**. The pool object exists so the context can own and close it, but
`initialize()` is **never** called on it, so activation opens no connection at all.

Create/close verdicts follow Goal 02B exactly. The allocation boundary is the successful
**return** of a `Connection` from `DriverManager.getConnection`. Above it - URL/vendor
mismatch, absent driver, driver present but unregistered, invalid configuration, missing
credential, and **any** `SQLException` from `getConnection` - the attempt is explicitly
`CreationFailure(PROVEN_CLEAN)`, with the `SQLException` preserved as a sanitised cause and
its raw message never used. Below it, a setup failure closes that exact `Connection` and the
verdict follows the close: normal -> `PROVEN_CLEAN`, threw -> `UNPROVEN`/quarantine. There
is **no catch-all**, so an unrecognised failure stays conservative.

`isHealthy` is a local volatile flag read and nothing else - no `isValid()`, no `SELECT 1`,
no metadata call - because `BoundedPool` invokes it while holding its lock. Measured: a
probe of 1000 health answers moved the driver-call counter by **exactly 0**, with an
opposite control showing the same counter moves on a real query. A query `SQLException`
retires the session before its lease returns; a `Connection.close()` failure propagates so
the slot is quarantined. Physical peak never exceeds the configured size, measured with a
breach control.

### Activation behaviour when analytics is unavailable

**JDBC is optional to repository activation.** A working IBM CM repository stays selectable
and its metadata/retention routes keep working when the feature is off, the driver is
absent, the driver is present but unregistered, the vendor/URL mismatched, the schema
unusable, the credential unresolvable or the database unreachable. In every one of those
cases the capability returns an unavailable service registering **zero** resources and
opening **zero** connections - verified as **0 driver calls during a successful analytics
activation**. The statistics API reports an explicit unavailable state and never deactivates
the repository.

### DB2 and Oracle dialect, and the resolver

The bootstrap three-method dialect was **replaced, not extended**. It exposed
`oneRowSuffix()`, and the two implementations disagreed about what a suffix is: DB2
returned `" FETCH FIRST 1 ROW ONLY"` (a trailing clause) while Oracle returned
`" AND ROWNUM = 1"` (valid only inside a `WHERE`). Every operation is now a **complete
statement**, so an invalid combination cannot be assembled from correct-looking parts.

| | DB2 | Oracle |
| --- | --- | --- |
| current date | `SELECT CURRENT DATE FROM SYSIBM.SYSDUMMY1` | `SELECT TRUNC(SYSDATE) FROM DUAL` |
| zero-row probe | `... WHERE 1 = 0 FETCH FIRST 1 ROW ONLY` | `... WHERE 1 = 0 AND ROWNUM <= 1` |

The aggregate shape is
`WITH LOGICAL_ITEMS AS (SELECT DISTINCT ITEMID, <four CASE window flags> FROM <segment 1>
UNION SELECT DISTINCT ... <segment N>) SELECT COUNT(DISTINCT ITEMID) AS TOTAL_ITEMS,
COALESCE(SUM(W_...), 0) ... FROM LOGICAL_ITEMS`, with **eight bind markers per segment**
in the order today_lo, today_hi, 7day_lo, today_hi, 30day_lo, today_hi, year_lo, year_hi -
the shared `today_hi` is bound three times. Markers appear in ascending column order, and
`AggregateQuery`'s constructor counts the `?` markers and rejects a mismatch, so SQL text
and bound values cannot drift. Columns 2..5 are `COALESCE(SUM(...), 0)` and are **never
NULL**, so "no rows matched" cannot be read as "not measurable". `ItemTypeID` is always
**bound**, never interpolated.

This replacement immediately paid for itself: DB2's date query was first written as
`VALUES CURRENT DATE`, which is valid DB2 but is **refused by the session's SELECT-only
read-only guard**, so a DB2 scan could never have read its anchor and every DB2 scan would
have failed. Caught by an end-to-end test driving a real session over a fake driver. Fixed
to the documented SELECT form, and the guard was **not** widened - "SELECT or WITH" is the
structural read-only rule this goal rests on.

### Snapshot publication and the scan coordinator

At most **one** scan per context, won by an atomic CAS before any work and released only
after every worker has been joined. Concurrency is exactly `statistics.workers` daemon
threads sharing one atomic index over a `List.copyOf`-ed frozen list - **no ExecutorService
and no queue at all**, so the peak concurrent query count is structurally the worker count.
`statistics.workers` greater than `jdbc.pool.size` is **refused** at validation, naming both
keys, never clamped.

A snapshot is published once, after the whole frozen list was visited, and the snapshot type
**refuses a result count that differs from the frozen count**, so a half-built snapshot is
unrepresentable. Catastrophic cancellation, overall-deadline abort, context close and
coordinator failure publish **nothing**, and the previous completed snapshot stays visible.
Coverage and `partialFailureCount` travel with every totals object, so a subtotal from 98
ItemTypes is never presented as a total for 100, and **Versions and Parts stay
`UNAVAILABLE`** in every per-ItemType result and total.

The JDBC pool is an owned resource registered **before** the coordinator, so the context
closes the scan first and the pool second, and `CLOSING`/`CLOSED_UNCERTAIN` flow through the
existing Goal 01C derivation: a late JDBC lease yields `Refusal.PENDING` and a quarantine
yields `Refusal.UNCERTAIN` permanently. `RepositoryManager` needed **no change** and no CM
pool semantic was weakened.

### Read-only guarantee

Structural, and enforced twice. A committed guard scans a real, non-empty analytics tree,
**fails if the tree disappears**, and refuses `executeUpdate`, `executeLargeUpdate`, update
batches, `commit`, `rollback`, `prepareCall` and SQL beginning with INSERT/UPDATE/DELETE/
MERGE/TRUNCATE/CREATE/ALTER/DROP/GRANT/REVOKE. Its planted control refuses **15** separate
forbidden writes plus a text block whose `DELETE` is on the next line. `Connection.setReadOnly(true)`
is attempted as defence in depth and is explicitly **not** the boundary. No `java.sql` type
escapes the database package: a type-level sweep over all 187 classes found only 4 mentioning
`java.sql`, all in `db/` and all non-public.

### Tests actually executed, with results

**Stub path**, on Linux with JDK 17 from a clean tree: `./build.sh` **exit 0**, core
**"Tests run: 341, failures: 0"**, IBM **"Tests run: 51, failures: 0"**, jar packaged;
`./tests/selftest.sh` **exit 0**; `./tests/shell/run.sh` **exit 0**, 5 passed / 0 failed;
`./bin/doctor.sh` **exit 0** (0 failures, 15 warnings); `./build.sh --check-ibm-isolation`
**exit 0**.

**Real IBM CM 8.7 SDK, reported separately:** `./build.sh --require-ibm` **exit 0** with the
real jars staged (4 jars, then removed), compiling **17 adapter sources and 12 test sources**
against `cmbicmsdk81.jar` (8.7.00.400.44) and running the full IBM suite **51/0 on that real-SDK
class path**. The independent verifier ran with `lib/ibm` empty and therefore reports the
real-SDK path as unverified **in its own run** - both statements are true and neither is a
contradiction: the SDK path was exercised by the lead on this revision and by the verifier's
predecessor pass, but not by the verifier's final run.

**Driver discovery, reported separately from any database claim:** with no driver present,
`--print-config` exits **0** and reports `NOT ready`; with the real `db2jcc4.jar` staged in
`lib/db2` it reports `ready (driver com.ibm.db2.jcc.DB2Driver)`. Discovery is class loading
and `DriverManager` inspection only, the tool prints that it attempted no connection, and a
loadable driver is **not** live database validation.

**Independent verification: PASS on `1a52f90`**, 104 checks, 0 failures, by a verifier that
wrote none of the implementation and pinned the revision into a pristine `git archive`
extract because concurrent builds corrupt the shared `build/` tree here. It reproduced the
counting semantics with a live `COUNT(*)`-shaped control, all nine multi-segment failure
modes, 22 hostile identifiers refused, the independently decoded date encoding, the
read-exactly-once anchor, the pool peak with a breach control, zero-`isHealthy`-driver-calls,
the five activation failure modes with zero connects, the coordinator bounds and atomicity,
and diff-level non-regression (`RepositoryManager`/`BoundedPool`/`CreationFailure`/
`CloseState` byte-identical to `c9751c2`). Two non-blocking observations: `requireSelect` is
a prefix test rather than a full statement parse, and the analytics guard is textual with no
generic `execute()` pattern.

### Live DB2/Oracle status - honest

**No live DB2 or Oracle CM database is reachable from this machine, so no live SQL validation
and no comparison against a trusted source has been performed.** The frozen IBM-documented
semantics plus deterministic query tests are the whole basis of the counting claims, and the
absence of live execution is the **single largest verification gap** for the next environment
that can reach one. A driver JAR being loadable is not live database validation and is not
presented as it.

### GitHub Actions - both events, same SHA

| Commit | push run | pull_request run |
| --- | --- | --- |
| `1a52f90` (implementation) | `36612056614` **failure** | `36612065724` **failure** |
| `dc24dbb` (CI fix) | `36615110466` **success** | `36615118636` **success** |

The `1a52f90` failures were at CI's **first** step, "Verify script permissions":
`tests/shell/analytics_guard.sh` and `tests/shell/analytics_source_guard_test.sh` were
committed `100644`. That mode is invisible on the Windows development host, where git is
configured `core.filemode=false`. This is the **fourth** occurrence of this class in this
project, so `dc24dbb` also adds `ScriptPermissionTest`, which reads the **committed mode from
the git index** rather than the filesystem - `Files.isExecutable` answers from extension
heuristics on Windows and would pass on the very host that introduces the bug. The check is
scoped to files something invokes, explicitly exempts the sourced `bin/lib` modules by name,
and asserts that git actually answered and that its predicate matches at least one real path
so it cannot degrade into "scanned nothing, therefore passed". That self-check caught a real
bug in its own first version, which tested the whole index line instead of the path inside it
and matched nothing. Planted control: removing one bit fails the build naming the file and
the fix.

### Unresolved risks

1. **No live DB2/Oracle validation and no comparison against a trusted source** - the largest
   gap, a data gap rather than a code gap. The counting semantics rest on IBM's documentation
   plus deterministic tests.
2. **Oracle is not exercised beyond generated SQL and fakes** - there is no Oracle driver in
   the local runtime and no Oracle database; the DB2 driver got a real local-discovery check
   and Oracle did not.
3. **`requireSelect` is a prefix test, not a statement parse.** A statement whose first
   keyword is `SELECT`/`WITH` is admitted; the guard does not parse the rest. It is a real
   narrowing rather than a proof, which is why the source guard and the driver-level
   `setReadOnly(true)` exist alongside it.
4. **The analytics read-only guard is textual** and has no generic `execute()` pattern, so a
   write routed through a helper method the guard does not name would not be caught
   textually. The type-level check that no `java.sql` type escapes `db/` limits where such a
   call could live.
5. **No `jshell`-style live smoke of the analytics routes against a real database** - the
   routes were proven over real sockets against the production wiring, but with no database
   behind them.
6. **The full IBM CM 8.7 SDK path was not re-run by the final verifier**, which had `lib/ibm`
   empty; it was run by the lead on this revision.
7. **Concurrent builds in one working tree still corrupt `build/`** on this host because
   `flock` is unavailable in the MSYS shell, producing misleading `NoClassDefFoundError`
   failures. Serialise builds, or verify in a private copy - `flock` does work under WSL.
8. **The SDK needs its own logging configuration** (unchanged from Goal 02B).
9. **`StatisticsAvailability`'s static `available()` factory is unreferenceable** because the
   record accessor shadows it - reported by the api member, cosmetic.

### Architecture decisions and goal state

No architecture rule was changed. Goal 03 **replaced** one bootstrap interface that could not
express its own contract (`DatabaseDialect` -> `JdbcDialect` with complete statements),
**narrowed** one safety default inherited from Goal 02B (the JDBC create-failure verdict),
**made explicit** the distinction between a loadable driver and a reachable database, and
**added** one narrowly scoped exemption to the IBM isolation guard for the DB2 driver's class
name - scoped to the reference, never to a file, with the shell guard and its Java twin now
asserted to agree after they briefly disagreed.

**Next goal: NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.** Do not execute Goal 04 or
Goal 05. Do not merge PR #1.

### Resume / review instruction

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture.
Fetch and fast-forward to the current remote state. Read STATUS.md, ARCHITECTURE.md, SECURITY.md,
DATA_MODEL.md, harness/MASTER_GOAL.md and harness/GOAL_03_FAST_ANALYTICS.md completely. Goals 01
through 01C are accepted; Goal 02 was reviewed with changes required; Goals 02A and 02B are
accepted; Goal 03 is executed, pushed and green on both Actions events for the same SHA.
REVIEW Goal 03 before approving anything further - do not re-execute it and do not execute
Goal 04 or Goal 05. Preserve the accepted optional-SDK/read-only/API architecture, the frozen
distinct-ItemID counting semantics, the one-anchor-per-scan rule, the hard-bounded lazy JDBC
pool with no connection outside its factory, and every Goal 01A-01C hard-bound, fail-closed,
Linux lifecycle and security invariant. Carry forward four facts: no live IBM CM validation and
no live DB2/Oracle SQL validation has been performed because neither a CM server nor a database
is reachable from the execution host; this repository is developed on Windows where git does not
record the executable bit, so validate on Linux with JDK 17 before trusting a CI-touching change;
for the same reason a second build in one working tree cannot be detected on that host, so verify
in a private copy; and the real IBM CM 8.7 SDK jars are a local prerequisite that must never be
committed."

- Next goal after Goal 03: **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

## Goal 02B external architecture review

Reviewed remote SHA:

`30f8a31aaa41522c2d00b0b894801576f74ed401`

Formal evidence verified from GitHub:

- branch HEAD exactly matched `30f8a31aaa41522c2d00b0b894801576f74ed401`;
- final push Actions run `36593216495`: **success**, exact same head SHA;
- final pull_request Actions run `36593224416`: **success**, exact same head SHA;
- implementation push `36592065472` and pull_request `36592073280`: **success**, exact implementation SHA `1049ab4c50c66adc682457971290deaa7dec17de`;
- PR #1 remained open, draft and unmerged;
- zero JAR files are tracked in the reviewed Git tree.

### Review verdict

**ACCEPTED. Goal 02B closes the resource-contract gap found after Goal 02A. Goal 03 may now build the
JDBC analytics half of RepositoryContext on the hardened generic pool contract.**

Source review confirmed:

- pre-allocation request/profile/credential failures now explicitly report
  `CreationFailure(PROVEN_CLEAN)`, so a disappearing credential does not quarantine an empty slot;
- the physical allocation boundary is explicit and unknown post-boundary failures remain untyped and
  therefore quarantined by BoundedPool;
- `lastAttemptLeftResources` is written once per attempt and cannot remain stale from an older failure;
- `ResourceFactory.isHealthy` is abstract, so every future physical-resource factory must state a cheap,
  local health policy;
- an already-translated backend-unusable `IbmCmFailure` idempotently marks the current session before
  it leaves `IbmCmApi`;
- the IBM test runner fails closed on a runnable compiled suite omitted from its explicit list;
- retention numeric-code absence is represented only by `null`; an explicitly supplied number is not
  rewritten merely because it is negative;
- the Goal 01C repository close/quiescence latch was not redesigned by this correction.

The credential-loss regression is strong evidence because it reaches the production factory/pool seam,
measures zero additional physical connection attempts, zero quarantine and successful recovery after the
credential source returns. The opposite post-allocation controls still quarantine.

### One non-blocking evidence wording correction

The real-SDK run proves that the **directory-root** IBM suite registration guard works while the suites
link against the real IBM SDK. It does **not** by itself execute the `jar:` branch of
`IbmSuiteRegistration`: `IbmAdapterTest` is still loaded from the compiled test directory, while the
SDK dependency is the JAR. The jar-root implementation is retained and code-reviewed, but it is not
claimed as measured evidence until the test runner itself is packaged and loaded from a JAR.

This is documentation/evidence precision only, not a Goal 02B code blocker.

### Goal state after review

- Goal 02B: **COMPLETED / REVIEWED / ACCEPTED**.
- Goal 03: **COMPLETED / PUSHED / GREEN - AWAITING ARCHITECTURE REVIEW** (superseded; see the Goal 03 execution record at the top).
- Goals 04-05: provisional; do not execute.

## Goal 02B execution record

Date/time: end of the Goal 02B execution session (local time). Branch:
`bootstrap/cm-insight-architecture`. **PR #1 remains OPEN, draft, unmerged.**

Checkpoint protocol: a commit cannot truthfully record its own SHA, and this file
changes the tree, so the commit above is the last completed **implementation** commit
this section describes. The authoritative head is read from Git
(`git rev-parse HEAD` / `git ls-remote origin refs/heads/bootstrap/cm-insight-architecture`),
and the handoff report records the exact local and verified remote HEAD after this
documentation commit has been pushed.

### A. Known-clean pre-allocation CM failures now release the creation slot

**The blocking regression, and it was a real capacity leak.** `IbmCmSessionFactory.create()`
ran `validateProfile()` and `resolveCredentials()` **before** the verdict-producing
try/catch, and both throw `IbmCmFailure`. Since Goal 02A correctly made every UNTYPED
failure quarantine, a CM credential that disappeared after successful activation made a
replacement creation fail **before any `DKDatastoreICM` was allocated** and nevertheless
burn the pool slot permanently. The application re-resolves credentials for every
replacement session, so this was reachable in normal operation rather than theoretical.

**The allocation boundary is now an explicit concept**: the `connections.connect(...)`
call. **Above** it - request mismatch, blank SSID, unresolvable credential - the attempt
reports `CreationFailure(PROVEN_CLEAN)`, because nothing physical exists. **At or below**
it, Goal 02A semantics are unchanged: `IbmCmFailure` -> PROVEN_CLEAN,
`IbmCmCleanupFailure` -> UNPROVEN, and anything else stays **untyped -> quarantine**,
with no catch-all that could relabel an unknown physical outcome as clean. A fatal `Error`
above the boundary stays conservative, documented at the site with both reasons.
`lastAttemptLeftResources` now has exactly **one** write site, a `finally`, so it always
describes the current attempt instead of going stale after a later clean failure.

**Measured through the real pool and factory**, only the physical layer faked:

| | connectAttempts | quarantined | createQuarantineFailures | capacityInUse |
| --- | --- | --- | --- | --- |
| before the credential-loss replacement | 1 | 0 | 0 | 0 |
| after it | **1 (zero additional)** | **0** | **0** | **0** |
| after the credential is restored | 2 | 0 | 0 | 1 (`available=1`, `replacementCreations=1`) |

**The opposite control, built independently by the verifier with the adapter removed**: an
untyped `RuntimeException` kept its own type and **quarantined** (`1/1/1`, `degraded`). The
fix cannot be abused to relabel every failure clean - relabelling is confined to the
pre-boundary paths. And it is a genuine regression test, not a vacuous pass: the same suite
against the **pre-fix** factory from HEAD is **51/7**.

### B. ResourceFactory health semantics are now mandatory

`ResourceFactory.isHealthy` had a permissive default body `return resource != null`, so safe
retirement depended on every composition root remembering to override it - and Goal 03 is
about to add another physical-resource factory. The method is now **abstract**: no default,
no convenience overload and no "always healthy" policy object.

**This is a deliberate COMPILE-BREAKING change**, and that is the point - it forces every
implementation to state a policy. Verified by enumerating the compiled output: the interface
declares **zero defaults** and **15/15 implementations** declare `isHealthy`. Three delegate
to a real local read; the ones whose resource is a bare live-counter have a **stated constant
with the reason at the site**, because a bare `return true` is indistinguishable from the
oversight this closes. Both composition-root factories were already explicit, so production
behaviour is unchanged. The health check remains cheap and local: production probes are single
local reads performing **zero vendor calls**, which matters because `BoundedPool` calls them
while holding its lock on the borrow path, the return path and a rotation sweep.

### C. Backend-unusable reasserts structurally

`IbmCmApi`'s already-translated rethrow path now reasserts the invariant: when the failure's
own `backendUnusable()` is true it marks the current session unusable **before** rethrowing;
when false it leaves the session reusable, so **`DKNotExist` stays benign**. The property is
now a property **of the wrapper** rather than of call-site memory. The proving test throws a
**preconstructed** translated failure from a `ReadCall` that does **not** mark, so it fails
against the previous rethrow-unchanged implementation; a negative control confirms a benign or
reusable failure poisons nothing. Marking is idempotent and records only the failure's own
already-sanitised text, never raw vendor text.

### D. The IBM runner can no longer silently omit a suite

Goal 02A found four **core** suites that had never executed because the runner's list omitted
them; the core runner now detects that, and the IBM runner had no equivalent. It keeps its
explicit list for deterministic ordering and adds a **fail-closed guard** that derives its
expectation from the **compiled** tree using the runner's own discovery predicate, so the
guard and the runner cannot drift, and it handles a **jar class path explicitly** rather than
silently finding nothing. Proven by planting a real unlisted suite: build **RED** naming it,
then green after removal. It earned its keep immediately - it caught the new section A suite in
the real tree before it was registered, which is exactly the defect class it exists for.

### E. Retention numeric absence is exactly `null`

Goal 02A replaced the misleading `-1` sentinel with nullable fields but **additionally
normalised every negative value to `null`**, on a premise nothing in the SDK evidence
establishes - `javap` showed those enum classes carry **no numeric code field at all**, so the
server's value range is simply unknown. That normalisation is **deleted**: `null` is the only
representation of absent, an explicitly supplied code is **preserved verbatim whether positive
or negative**, and no `-1` sentinel constant or call site returns. Measured through the real
serializer: `-7` and `-23` are preserved as data, `null` renders JSON `null`, `0` is preserved.
The test asserting the deleted rule was **updated, not preserved**.

### Also fixed en route, found by running rather than reading

The new section A suite created its fixtures with `Files.createTempDirectory`, which **this
environment denies** under `java.io.tmpdir`. The project already solved this in Goal 01 -
`TestSupport` documents the denial and derives its scratch root from the compiled test classes -
and the new suite reintroduced the trap, so **six of its tests could not run at all**. Fixed the
same way, with no assertion weakened, and proven by pointing `java.io.tmpdir` at a nonexistent
path: fixed tree **51/0**, same tree with only that line reverted **51/6**. Worth recording
because GitHub's Linux runners allow `/tmp`, so this would have been **green in CI and red only
on a restricted machine** - the mirror image of the Windows executable-bit problem this project
hit three times in Goal 02.

### Tests actually run, with results

**Stub path** (`./build.sh`): **exit 0**, core **253/0**, IBM **51/0** (304 PASS / 0 FAIL),
jar packaged. `./tests/selftest.sh` **exit 0**. `./tests/shell/run.sh` **exit 0** - all four
committed tests. `./bin/doctor.sh` **exit 0** (0 failures). `./build.sh --check-ibm-isolation`
**exit 0**.

**Real IBM CM 8.7 SDK, reported separately as required:** `./build.sh --require-ibm` **exit 0**
with the real jars staged, compiling **17 adapter sources and 12 test sources** against
`cmbicmsdk81.jar` (8.7.00.400.44) and running the **full IBM suite 51/0 on the real-SDK class
path**. This measures the directory-root registration guard while linking against the real SDK; the
separate `jar:` root branch remains code-reviewed but is not claimed as executed evidence. Without the
jars it refuses with **exit 1**. Only this is SDK validation; the stub compile is a compile check
and is never described as validation. No vendor JAR is committed or left untracked.

**Independent verification:** the verifier wrote none of the production changes, hash-verified
its evidence against `1049ab4`, and returned **PASS on every in-scope criterion**: the
physical connect count and pool metrics, an opposite control it built itself, the diagnostics
walk (`true -> false -> true -> false`, so no stale flag), reflection over the compiled
interface and all 15 implementations, the plant/unplant attack on the runner, the serializer
output, and all eleven Goal 02A non-regression items - including confirming
`RepositoryManager.java` is **byte-identical** to the reviewed `ff4b2aa`.

### Live CM status - honest

**No live CM server is reachable from this machine, so no live read-only CM validation has been
performed, in this goal or any earlier one.** The real-SDK work above is compilation plus the
deterministic test suite on a real-SDK class path. No failed-connection smoke was used as
evidence in this goal, and nothing here is described as live CM validation.

### GitHub Actions - both events, same SHA

| Commit | push run | pull_request run |
| --- | --- | --- |
| `1049ab4` (implementation) | `36592065472` **success** | `36592073280` **success** |

Both verified through the GitHub API to have `headSha` equal to the commit above.

### Unresolved risks

1. **No live IBM CM validation** - still the largest gap, a data gap rather than a code gap.
2. **A failing `destroy()` cannot be forced against a real server**, so that case remains proven
   with fakes only.
3. **The fatal-`Error`-before-allocation path stays conservative** (it quarantines). Deliberate
   and documented at the site, but it means an `OutOfMemoryError` during credential resolution
   can still consume a slot.
4. **`ResourceFactory.isHealthy` is now abstract, so any factory added in Goal 03 must state a
   policy** - that is the intent, but it is a new obligation for the JDBC pool.
5. **The classification mapping helper remains source-verified** rather than unit-verified,
   because it needs a vendor `ItemTypeDef`.
6. **Concurrent builds in one working tree still produce misleading red results** on this host,
   because `flock` is unavailable in the MSYS shell so `build.sh` cannot detect the second run.
   Use a private copy for verification; the CI runner has no such contention.
7. **The SDK needs its own logging configuration** - a real-SDK run emits vendor
   attention/`NoClassDefFoundError` lines for `log4j-core`/`cmblogconfig.properties` on stderr.
   It does not affect compilation, tests or lifecycle verdicts.

### Architecture decisions and goal state

No architecture rule was changed. Goal 02B **narrows** one safety default (untyped create
failure), **makes explicit** one previously inherited contract (factory health), **strengthens**
one invariant from convention to structure (backend-unusable marking), **closes** one
diagnostic hole (the attempt flag), **extends** one guard to the second runner, and **removes**
one undocumented guess (negative values meaning absent).

**Next goal: NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.** Do not execute Goal 03.
Do not merge PR #1.

### Resume / review instruction

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture.
Fetch and fast-forward to the current remote state. Read STATUS.md, ARCHITECTURE.md, SECURITY.md,
harness/MASTER_GOAL.md, harness/GOAL_02B_RESOURCE_CONTRACT_CLOSURE.md and
harness/GOAL_02_IMPLEMENTATION_SPEC.md completely. Goals 01 through 01C are accepted; Goal 02 was
reviewed with changes required; Goal 02A is accepted; Goal 02B is executed, pushed and green on
both Actions events for the same SHA. REVIEW Goal 02B before approving anything further - do not
re-execute it and do not execute Goal 03. Preserve the accepted optional-SDK/read-only/API
architecture and every Goal 01A-01C hard-bound, fail-closed, Linux lifecycle and security
invariant. Carry forward three facts: no live IBM CM validation has been performed because no CM
server is reachable from the execution host; this repository is developed on Windows where git
does not record the executable bit; and for the same reason a second build in one working tree
cannot be detected, so verify in a private copy."

## Goal 02A execution record (superseded, retained for the review trail)


## Goal 02A external architecture review

Reviewed remote SHA:

`ff4b2aa32617a6037b541143b008947ed095cfdc`

Formal evidence verified from GitHub:

- branch HEAD exactly matched `ff4b2aa32617a6037b541143b008947ed095cfdc`;
- push Actions run `36527954727`: **success**, exact same head SHA;
- pull_request Actions run `36527957601`: **success**, exact same head SHA;
- PR #1 remained open, draft and unmerged;
- no JAR/vendor binary is tracked.

### Review verdict

**The five Goal 02A corrections are substantively accepted. One resource-contract regression and four
small contract/coverage traps must be closed before Goal 03 may introduce a JDBC resource factory.**

Accepted after source review:

- generic failed creation is now fail-safe: only explicit `PROVEN_CLEAN` releases a reservation;
- IBM teardown uses one documented isConnected -> conditional disconnect -> always destroy rule, with
  successful destroy as cleanup proof and destroy failure as quarantine;
- provider-installed and runtime-ready are separated, and an SDK-free packaged runtime is activation
  unavailable before RepositoryManager/pool creation;
- ClassificationRules now come from the actual core-loaded configuration and the IBM source set does not
  reopen application configuration;
- vendor read failures are conservatively retired, including RuntimeException/Error/wrong vendor types,
  while DKNotExist remains benign;
- unmapped retention enum codes are rendered as JSON null rather than the old numeric sentinel;
- the previously unregistered core suites now really run, and the real-SDK test fake incompatibility was
  corrected.

### Blocking regression found by review

`IbmCmSessionFactory.create()` performs `validateProfile()` and `resolveCredentials()` **before** the
try/catch that translates a clean IBM creation failure to `CreationFailure(PROVEN_CLEAN)`.

This became a real defect only after Goal 02A correctly hardened `BoundedPool`: a plain failure is now
quarantined.

The runtime intentionally re-resolves CM credentials for every new/replacement session. Therefore:

1. the repository can activate successfully;
2. later a CM secret/env source can disappear;
3. a stale/unhealthy session is retired and the pool tries to create its replacement;
4. credential resolution fails **before any DKDatastoreICM is allocated**;
5. the plain `IbmCmFailure` escapes;
6. `BoundedPool` correctly reads the untyped failure as unknown and permanently quarantines an empty slot.

Repeated pre-allocation credential failures can therefore degrade the pool to zero capacity without a
single physical leak. The factory knows this outcome is clean and must say `PROVEN_CLEAN` explicitly.

The same rule applies to any other known-clean pre-allocation validation path. Unknown failures after a
physical allocation boundary must remain conservative.

### Additional contract closure required in the same pass

1. `ResourceFactory.isHealthy()` still has the permissive default `resource != null`. This reintroduces
   "the composition root must remember" as a safety dependency. Make health validation explicit/mandatory
   before JDBC reuses this abstraction.
2. `IbmCmApi.vendorCall()` rethrows an already-translated `IbmCmFailure` without reasserting
   `backendUnusable => markUnusable`. Current production construction sites are safe, but the invariant is
   still convention-dependent. Make the rethrow path idempotently enforce it.
3. The core runner now detects unregistered suites, but `IbmAdapterTest` still has only a manual suite
   list. Close the same silent-test gap in the IBM suite before more adapter/JDBC tests are added.
4. `RetentionPolicyInfo` currently normalises every negative numeric enum code to null based on an
   undocumented "CM enum codes are never negative" premise. Absence should be represented by null directly;
   do not silently discard a future explicitly established negative value unless IBM documentation proves
   it invalid.

These are intentionally one small Goal 02B, not a reopening of Goal 02A.

### Goal state after review

- Goal 02A: completed/reviewed; its main corrections are accepted.
- Goal 02B: **COMPLETED / PUSHED / GREEN - AWAITING ARCHITECTURE REVIEW** (superseded; see the Goal 02B execution record at the top).
- Goal 03: **PROVISIONAL / DO NOT EXECUTE** until 02B review passes.

## Goal 02A execution record

Date/time: end of the Goal 02A execution session (local time). Branch:
`bootstrap/cm-insight-architecture`. **PR #1 deliberately not merged.**

Checkpoint protocol: a commit cannot truthfully record its own SHA, and this file
changes the tree, so the commit above is the last completed **implementation** commit
this section describes. The authoritative head is read from Git
(`git rev-parse HEAD` / `git ls-remote origin refs/heads/bootstrap/cm-insight-architecture`),
and the handoff report records the exact local and verified remote HEAD after this
documentation commit has been pushed.

### The five mandatory corrections, and how each was verified

**A. Unknown create failure now fails safe - the review's headline finding.** This was
the lead's own decision in Goal 02: the pool released the reserved slot for any creation
failure that was not an explicit `UNPROVEN`, and STATUS.md recorded that as accepted
residual risk. The review rejected it, correctly: a plain exception is not proof that
nothing was allocated, and requiring every future adapter author to remember a special
exception type is the fail-open dependency the generic pool exists to remove.

**The generic create-failure default after this correction:** a creation reservation is
released after a failed `create()` **ONLY** when the factory explicitly proves a clean
cleanup with `CreationFailure(PROVEN_CLEAN)`. Every other outcome - a plain checked
exception, a `RuntimeException`, an `Error`, an `InterruptedException` or an explicit
`UNPROVEN` verdict - **quarantines** the slot. This is the deliberate **opposite** of the
pre-02A default. A factory that knows it failed before allocating anything must now say
`PROVEN_CLEAN` explicitly.

Measured **with its control**, by a verifier who wrote none of the code: the shipped rule
peaked at **2 live physical resources against a configured size of 2**; the same class
with the OLD rule restored in a copy peaked at **180 > 2**, with `quarantined=0` and
`capacityInUse=0`. The mutant is the evidence - a correct peak on its own would prove
nothing. Capacity-identity sampling: 0 violations.

The four Goal 01 assertions that encoded the old rule were **renamed and re-expressed,
not deleted**: `anErrorDuringACreationQuarantinesTheSlotBeforeTheErrorPropagates`,
`initializeFailureClosesWhatItCreatedAndQuarantinesTheFailedSlot`,
`aFactoryFailureOnBorrowSurfacesAsAPoolExceptionAndQuarantinesTheSlot` (and one
unchanged in name), with no net loss (34 -> 34 across the two suites) and stronger
assertions. The verifier confirmed independently that they encode the new contract.

**B. The IBM cleanup verdict now follows IBM's documented lifecycle.** The production
factory unconditionally disconnected after a failed connect and required BOTH disconnect
and destroy to succeed, so an ordinary failed login could become a **permanent
quarantine**. IBM CM 8.7 documents `isConnected()` as true only when `connect()`
completed successfully, `destroy()` as performing the datastore cleanup if needed, and
`connect -> disconnect -> destroy` as the successful lifecycle.

**The cleanup proof rule:** one implementation,
`IbmCmCleanupFailure.releaseQuietly(IcmDatastore)`, now owns the documented order and is
used by BOTH the failed-connect path and `IbmCmSession.close()`. Order: `isConnected()`
outside any pool lock, then `disconnect()` only when connected or when the answer is
unreadable, then `destroy()` **always**. **A successful `destroy()` is the final cleanup
proof**, because IBM documents it as performing the cleanup if needed; a disconnect
problem is retained as a sanitized diagnostic but no longer quarantines, and a **failing
destroy still quarantines regardless** of the disconnect result. The justification is
written at the release site, because this deliberately weakens a safety rule in one
documented direction and a reviewer must be able to see why.

Measured on the **real IBM CM 8.7 SDK**, the same unreachable-SSID failure Goal 02 used:

| | Goal 02 | Goal 02A |
| --- | --- | --- |
| message | `cleanup ... did not return normally` | `connect cm-usage(DKUsageError, errorId=7332)` |
| shutdown | `repository CLOSED_UNCERTAIN, retained context CLOSED_UNCERTAIN` | `repository (none), nothing retained` |
| pool | `quarantined=1 createQuarantineFailures=1` | no quarantine |

The same server failure no longer manufactures a permanent quarantine. This is a **failed
connection** and is **not** presented as live CM validation.

**C. Provider installed is not the same as runtime ready.** A core-only build packages
the adapter and its ServiceLoader descriptor even with no vendor JARs, so discovery
reported `AVAILABLE` while `sessionFactory()` later found `DKDatastoreICM` missing. This
is the defect class that once hid a completely unreachable adapter behind a message that
looked like legitimate core-only mode.

`CmAdapterProvider.Readiness` is a **cheap, local, no-network, no-connection** probe with
a **fail-closed default** (a provider that does not report readiness is NOT ready), and
the registry's activation verdict now requires it: an installed adapter whose SDK is
absent reports `providerInstalled=true` with activation **UNAVAILABLE**. Verified: with
no SDK, `--check-repository` exits **4** through the adapter-unavailable path instead of
1 through activation-failure; auto-activation refuses with exit 4; a **half-visible SDK**
(`DKDatastoreICM` present with a missing superclass) still yields the fixed sanitized
reason with **zero escaping** `NoClassDefFoundError`/`LinkageError`; a real SDK class
path reports ready. A tripwire proved the probe does **not** initialise the vendor class
while activation does.

**D. Classification rules come from the real runtime configuration.** The adapter
independently reopened the DEFAULT `application.properties`, so a runtime started with a
custom `--config` could display business classifications that differed from the rules
Main had loaded and validated.

`ClassificationRules` is now loaded **once** by the core and passed through
`CmAdapterSettings`, and **`src/ibm` reads no configuration at all** - verified by grep
(`AppPaths`/`AppConfig`/`configurationFile`/`ClassificationRules.load`: NONE in
`src/ibm/java`) and by a new source guard with a planted positive control. Verified at app
level: a custom config with a distinctive rule reaches
`ItemTypeInfo.businessClassification` through the production provider, and the fallback
label comes only from the rule set the core supplied.

**E. Backend-unusable is now structural.** `IbmCmFailure.backendUnusable` existed but
several read paths raised an unusable failure while leaving the session healthy, so
try-with-resources returned it to the idle pool for reuse.

The read wrappers now own **both** the classification and the marking, so a caller cannot
classify without marking: `DKNotExist` is the one explicit benign control; every other
vendor failure retires the session; an unexpected `RuntimeException` retires it; an
`Error` is marked **before** it propagates; an `InterruptedException` restores the flag
and follows the conservative rule; and a wrong or absent vendor type goes through
`requireIcmType`, which marks first. Verified adversarially for every case, with the
decisive check being that a **subsequent borrow received a DIFFERENT session** - and that
`DKNotExist` left the session reusable (the same session came back).

**F. No unmapped enum code masquerades as an IBM number.**
`RetentionPolicyInfo` documented its numeric enum-code fields as "exactly what the server
returned" while emitting `-1`, because the SDK exposes enum constants with no recoverable
numeric mapping (`javap` shows those classes carry no numeric code field at all).
**Retention numeric-code representation: the two fields are now nullable `Integer`, with
JSON `null` meaning "no numeric CM code has been established"; negative inputs normalise
to `null`, so a stale `-1` cannot become a number; and the readable field still carries
IBM's certain constant name (`UNKNOWN(FIXED_TIME)`, `AUTO_DELETE`).** No enum ordinal is
used as a code anywhere in `src`. `IbmEnumNames.UNMAPPED_CODE` is deleted - it had no
remaining production caller, and its normalise-to-null rule would have hidden a missed
call site from the compiler.

### Two further defects found while integrating, both invisible to stub-only CI

1. **Four committed suites were never registered in `SelfTest.TEST_CLASSES` and had
   never executed a single assertion** - `ProviderDiscoveryTest`,
   `IbmSourceReadOnlyGuardTest`, `CoreIbmIsolationTest`, `RouterActionGuardTest`. The
   build reported green throughout. `IbmSourceReadOnlyGuardTest` is the Java half of the
   read-only guarantee, so for a whole goal the project believed a guard was enforcing
   that was not running at all. `SelfTest` now enumerates its own package and **fails**
   when a class that would have run is missing, proven with a planted unregistered suite
   and confirmed to produce no false positive against the nine legitimate helpers. Core
   registration therefore moved **220 -> 252** tests; of that, 32 tests were pre-existing
   assertions that are only now actually enforced.
2. **`IbmSessionPoisoningTest` anonymously implemented the real vendor interfaces**, so it
   compiled against the deliberately narrower stubs and could not compile against the real
   SDK - a blind spot no stub-only CI run could ever see. The fix builds foreign vendor
   types as a `java.lang.reflect.Proxy`, which names no interface member and is identical
   under both class paths, and a new guard refuses hand-written anonymous vendor types
   with a planted positive control.

### Tests actually run, with results

On **Linux with JDK 17.0.20.1**, against a clean copy of the pushed tree, running CI's own
sequence: `build.sh` **exit 0** (core **252/0**, IBM **41/0**, jar packaged);
`tests/selftest.sh` **exit 0**; `tests/shell/run.sh` **exit 0** (all four committed tests,
including the Linux-only `/proc` argv test); `bin/doctor.sh` **exit 0**.

**IBM suite against the committed stubs:** 41/0, compiled with `--release 17 -Xlint:all`.

**Real IBM SDK compile, reported separately as required:** `./build.sh --require-ibm`
**exit 0** with the real CM 8.7 jars staged, compiling **17 adapter sources and 10 test
sources** against `cmbicmsdk81.jar` (8.7.00.400.44) with `-Xlint:all` clean. Only this is
SDK validation; the stub compile is a compile check and is never described as validation.

**Mutation/control evidence:** section A's physical-bound control (2 vs 180, above);
section E's per-failure-class retirement with the different-session proof; section B's
five verdict combinations with ordered call logs including the tripwire vendor class.

**Independent verification:** the verifier wrote none of the production changes, bound its
evidence to `4b810b3` by SHA256 over all 135 files under `src/`, and ran 123 checks with
0 failures across sections A, B, C, D, E and F. It also confirmed preservation
independently: the optional IBM set compiles against both stubs and the real SDK; core has
no `com.ibm` type; three planted guard violations refused; **0 vendor JARs tracked**; the
POST action guard intact; the Goal 01C latch code unchanged; and the four updated Goal 01
assertions encode the new contract.

### Live CM status - honest

**No live CM server is reachable from this machine, so no live read-only CM validation has
been performed, in this goal or any earlier one.** The real-SDK run above is a genuine
**failed connection** to an unreachable SSID, useful lifecycle evidence and labelled as
such. It is not live CM validation and is never described as it.

### GitHub Actions - both events, same SHA

| Commit | push run | pull_request run |
| --- | --- | --- |
| `4b810b3` (implementation) | `36527200713` **success** | `36527204017` **success** |

Both verified through the GitHub API to have `headSha` equal to the commit above.

### Unresolved risks

1. **No live IBM CM validation** - the largest gap, a data gap rather than a code gap.
2. **A failing `destroy()` cannot be forced against a real server**, so that case is proven
   with a fake handle and a tripwire vendor class only.
3. **Section B's real-SDK PROVEN_CLEAN verdict is inferred** from an empty destroy/
   disconnect diagnostic plus "nothing retained", because `--check-repository` does not
   print pool counters for a failed activation.
4. **Sections D and F were exercised at app level and through the production provider and
   JSON renderer directly**, not over HTTP, because those routes need an activated
   repository.
5. **`ResourceFactory.isHealthy` has a permissive default** (`resource != null`), so a
   composition root that forgets to override it would keep a session already marked
   unusable. The production wiring is correct; the default is a trap worth knowing.
6. **The classification path's private mapping helper** (`CmMetadataService.classificationOf`)
   remains source-verified rather than unit-verified, because it needs a vendor
   `ItemTypeDef`; the rules themselves are covered by `ClassificationRulesTest`.
7. **The SDK needs its own logging configuration**: a real-SDK run emits vendor
   `NoClassDefFoundError`/attention lines for `log4j-core`/`cmblogconfig.properties` on
   stderr because those SDK runtime files are not on the class path. It does not affect
   compilation, tests or the lifecycle verdict, but it means the SDK is not fully
   configured for production logging here.

### Architecture decisions and goal state

No architecture rule was changed. Goal 02A is a correction pass: it **narrows** two
safety defaults (generic create failure, backend-unusable marking), **corrects** one
over-pessimistic rule (IBM cleanup proof) with IBM's documentation cited at the site, and
**removes** two misleading surfaces (installed-vs-ready, unmapped numeric codes).

**Next goal: NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.** Do not execute Goal 03.
Do not merge PR #1.

### Resume / review instruction

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture.
Fetch and fast-forward to the current remote state. Read STATUS.md, ARCHITECTURE.md,
SECURITY.md, harness/MASTER_GOAL.md, harness/GOAL_02A_IBM_ADAPTER_SEMANTICS_HARDENING.md
and harness/GOAL_02_IMPLEMENTATION_SPEC.md completely. Goals 01 through 01C are accepted;
Goal 02 is reviewed with changes required; Goal 02A is executed, pushed and green on both
Actions events. REVIEW Goal 02A before approving anything further - do not re-execute it
and do not execute Goal 03. Two things to carry forward: no live IBM CM validation has
been performed because no CM server is reachable from this machine; and this repository is
developed on Windows, where git does not record the executable bit, so validate on Linux
with JDK 17 before trusting a CI-touching change."

## Goal 02 review record (superseded by the Goal 02A record above)


## Goal 02 external review verdict

**SUBSTANTIAL IMPLEMENTATION ACCEPTED, BUT CHANGES ARE REQUIRED BEFORE JDBC GOAL 03.**

The review verified the pushed tree and exact-SHA CI.

Reviewed SHA:

`87ffeb8e6de9b5ab6577353a7c54a0c6e718dead`

GitHub Actions:
- push run `36501185151`: **success**
- pull_request run `36501190778`: **success**

The real CM 8.7 SDK compile reported by the handoff is useful evidence, and the optional-source-set / stub
architecture is accepted. No live CM server success was claimed.

## Accepted Goal 02 work

- IBM SDK types are isolated under the optional IBM source set.
- core compilation remains IBM-free.
- test-only IBM stubs are not packaged.
- real SDK strict compile path exists.
- ServiceLoader provider boundary exists.
- no emergency/ad-hoc DKDatastoreICM path was found.
- ProductionRepositoryContextFactory creates the hard-bounded pool before services and carries a cleanup
  context on post-allocation activation failure.
- CM pool health is a local volatile read rather than a server round trip under the pool lock.
- ItemType and retention services use immutable IBM-free DTOs and pooled Lease scope.
- ItemType listing was corrected against the real SDK to listEntityNames(DK_ICM_USER_ITEM_TYPES) plus
  retrieveEntity(name).
- read-only interfaces/routes and source/stub guards are in place.
- repository selection is authenticated POST with an application-specific action header.
- metadata cache is per-context.
- smoke command uses the production provider -> RepositoryManager -> BoundedPool path.
- no vendor JAR is tracked.

## Blocking findings for Goal 02A (ALL RESOLVED in `4b810b3` - kept as the review's original wording)

> **Resolved.** Each finding below is addressed by the Goal 02A execution record above: (1) the
> generic create-failure default now quarantines unless the factory explicitly proves
> `PROVEN_CLEAN`; (2) the failed-connect cleanup now skips a meaningless disconnect after a connect
> that never completed, always destroys, and treats a successful destroy as the final cleanup proof;
> (3) provider-installed is separated from runtime-ready, with a fail-closed local readiness probe;
> (4) `ClassificationRules` is loaded once by the core and passed in, and `src/ibm` reads no
> configuration; (5) `backendUnusable` now marks the borrowed session before the lease returns, for
> every failure class including `Error` and wrong vendor types. The text is retained unchanged so the
> review can confirm each item was actually addressed rather than reworded.

### 1. Unknown create failure still releases capacity

BoundedPool quarantines only explicit CreationFailure(UNPROVEN). A plain exception/error still releases
the reservation. That makes the physical-bound guarantee depend on every future external-resource factory
remembering the special type.

Goal 02A changes the generic default: only explicit PROVEN_CLEAN may release a failed creation;
unknown/untyped failure quarantines.

### 2. IBM cleanup verdict is too pessimistic in the wrong place

After a failed connect the production factory unconditionally disconnects and requires disconnect AND
destroy to succeed. IBM documents isConnected() as true only after connect completed successfully and
destroy() as performing datastore cleanup if needed.

Goal 02A must skip meaningless disconnect after a connect that never completed, always destroy, and use
successful destroy as the final cleanup proof. Destroy failure remains unproven/quarantined.

### 3. Provider discovery reports adapter available when SDK runtime is absent

The packaged adapter/provider exists even in a build compiled against stubs. At runtime without IBM JARs,
ServiceLoader can therefore report AVAILABLE while sessionFactory later refuses because DKDatastoreICM is
not loadable.

Goal 02A separates "provider installed" from "runtime ready to activate".

### 4. Business classification can read the wrong config

Main loads ClassificationRules from the actual AppConfig, but IbmCmAdapterProvider independently reopens
the default application.properties path. A custom --config can therefore disagree with ItemType API
classification.

Goal 02A passes the already-loaded rule set through core settings/context and removes adapter config parsing.

### 5. backendUnusable does not always poison the session

Several IBM read failure paths can raise an unusable failure while leaving IbmCmSession.healthy true.
A try-with-resources lease then returns that session to the idle pool.

Goal 02A makes "backend unusable => mark unusable before lease return" structural, including unexpected
RuntimeException/Error and wrong vendor object/type.

## IBM documentation evidence used in review

IBM Content Manager 8.7 DKDatastoreICM documentation states:
- isConnected() is true when connect() was called and completed successfully; it is not a communication
  liveness check.
- destroy() destroys the datastore object and performs datastore cleanup if needed.

The normal sample lifecycle is create -> connect -> disconnect -> destroy.

References:
- https://www.ibm.com/docs/en/content-manager/8.7.0?topic=comibmmmsdkserver-dkdatastoreicm
- https://www.ibm.com/docs/en/content-manager/8.7.0?topic=servers-establishing-connection

## Non-blocking correctness finding (RESOLVED in `4b810b3`)

> **Resolved.** Unmapped numeric enum codes are now represented as a nullable `Integer` with JSON
> `null`, never `-1`; negative inputs normalise to `null`; the readable field still carries IBM's
> certain constant name; and `IbmEnumNames.UNMAPPED_CODE` is deleted. Original wording retained below.

RetentionPolicyInfo currently documents its numeric enum-code fields as exact server values while the
implementation intentionally emits -1 because those numeric mappings were not established. Goal 02A must
make the unavailable-code semantics explicit and must not imply -1 came from IBM CM.

## Exact next goal

**NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.** Do not execute Goal 04 or Goal 05.

Review first:

1. `harness/MASTER_GOAL.md`
2. `harness/GOAL_03A_SCAN_LIFECYCLE_AND_SQL_GUARD_HARDENING.md` and the Goal 03A execution record at the top of this file

## Resume instruction

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture.
Fetch and fast-forward to the current remote state. Read STATUS.md, ARCHITECTURE.md, SECURITY.md,
DATA_MODEL.md, harness/MASTER_GOAL.md and harness/GOAL_03A_SCAN_LIFECYCLE_AND_SQL_GUARD_HARDENING.md
completely. Goals 01 through 01C are accepted; Goal 02 was reviewed with changes required; Goals 02A
and 02B are accepted; Goal 03 is accepted apart from the four corrections Goal 03A closed; Goal 03A is
executed and pushed. REVIEW Goal 03A before approving anything further - do not re-execute it and do not
execute Goal 04 or Goal 05. Preserve the accepted optional-SDK/read-only/API architecture, the frozen
distinct-ItemID counting semantics, the one-anchor-per-scan rule, the hard-bounded lazy JDBC pool with no
connection outside its factory, the latched one-scan gate with its separate draining fact, and every Goal
01A-01C hard-bound, fail-closed, Linux lifecycle and security invariant. Carry forward four facts: no live
IBM CM validation and no live DB2/Oracle SQL validation has been performed because neither a CM server
nor a database is reachable from the execution host; this repository is developed on Windows where git
does not record the executable bit, so validate on Linux with JDK 17 before trusting a CI-touching change;
for the same reason a second build in one working tree cannot be detected on that host, so verify in a
private copy and treat nested-class NoClassDefFoundError as a concurrency artifact to re-run solo; and
the real IBM CM 8.7 SDK jars are a local prerequisite that must never be committed."

## Mandatory checkpoint rule

At the end of every approved goal record:
- date/time;
- branch;
- last completed work commit described by STATUS;
- exact work completed;
- tests actually run/results;
- stub compile status;
- real IBM SDK compile status;
- live CM smoke status or explicit absence;
- Actions run IDs/results;
- unresolved risks;
- next goal status;
- final local/remote HEAD in the handoff report after push.
