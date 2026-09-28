# Project Status

> Canonical cross-session checkpoint. Read this first when continuing in another chat/model. Update it at the end of every substantial approved goal.

## Project state

- Project: CM Insight
- Repository: mrAibo/cm_java_item_reporter
- Active branch: `bootstrap/cm-insight-architecture`
- Goal 01 implementation commit: `fb42a0e55e69b551bffdcf0986714b8110dbdfb6`
- Goal 01A review-hardening: completed through reviewed remote HEAD `9c7168aa238633a004850ba658d546dd3177be52`
- **Goal 01B implementation/work commit (the commit this file describes):** `43a3c7035c8165548f8014d57e0f862a9b8e9f00`
- Stage: **Goal 01B executed and pushed; awaiting architecture review before Goal 02**
- Runtime target: Java 17 LTS / OpenJDK-compatible
- Build/deployment: javac + jar + bash; single JVM; no Maven/Gradle/Spring/containers
- Product safety mode: read-only V1/V2
- Current approved goal: `harness/GOAL_01B_LINUX_LIFECYCLE_AND_CLOSE_PROPAGATION.md` (executed)
- Goals 02-05: PROVISIONAL; do not execute
- Next goal: **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

## Checkpoint protocol (from Goal 01B section G)

A commit cannot truthfully contain its own SHA. Therefore:

- this file records the last completed **implementation/work** commit it describes, above;
- the authoritative current branch head is read from Git: `git rev-parse HEAD` /
  `git ls-remote origin refs/heads/bootstrap/cm-insight-architecture`;
- the final handoff report records the exact local and verified remote HEAD after the STATUS
  commit itself has been pushed;
- no SHA field in this file is a placeholder or describes "the commit that follows".

## Goal 01B completion record

- Branch: `bootstrap/cm-insight-architecture`
- Work commit described here: `43a3c7035c8165548f8014d57e0f862a9b8e9f00`
- Start checkpoint: `7bf9007761c50701296eb4910842e5e3118e06c9` (fast-forwarded, tree clean)

### Exact work completed

All of sections A-G of `harness/GOAL_01B_LINUX_LIFECYCLE_AND_CLOSE_PROPAGATION.md`.

| Section | What was done |
|---|---|
| **A** (blocking) | New vendor-neutral core contract `com.mraibo.cminsight.core.CloseOutcomeAware` (reports the OUTCOME of a close; `close()` narrowed so it cannot throw a checked exception). `BoundedPool` implements it — quarantine is the ONLY source of uncertainty, so an ordinary clean shutdown is never turned into FAILED. `RepositoryContext` implements it as well, consults an owned close-aware resource **after** its `close()` returns normally (the case an exception-only check cannot see), keeps reports separate from failures on the new value-free `uncertainCloseReports()`, and treats an unreadable report as uncertain. `RepositoryManager` folds the reports into its `CloseOutcome` and still refuses the switch **before** the factory is called. |
| **B** (blocking) | Linux startup identity race fixed with an explicit `provisional -> positive` identity STATE and a bounded 3 s grace, transition table in `ci_start_identity_next`. A single transient `different` while provisional is recorded as evidence, not acted on; one positive observation (structural argv identity, or existence + exact marker + **port ownership**) ends provisionality immediately; a LATER contradiction after a positive identity IS recycle; a contradiction outliving the grace IS recycle; `rc=2` never moves the state. New refusals `MARKER_UNTIED` and `IDENT_UNPROVEN` mean the grace can never publish a PID file for a pid whose identity was never shown. No new sleeps; stop-time ownership untouched. |
| **C** (blocking) | IPv4-mapped IPv6 listener representations are normalized for **listener ownership only**: `::ffff:127.0.0.1` is `exact` for a configured `127.0.0.1`, `::ffff:127.0.0.5` matches `127.0.0.5`, bracketed and fully expanded spellings are handled, and a mapped non-loopback stays `foreign`. The web-exposure rule is deliberately unchanged. Secondary bug fixed: candidate lists were iterated with `$(...)`, which pathname-expanded the documented wildcard spelling `*` against the current directory. |
| **D** | Ownership safety unchanged: exact marker only, structural identity only, no override flag, an unprovable target refused. `stop.sh` ties the marker to its socket row through the normalized form, demonstrated on a mapped row whose argv was **not** CM Insight, so only the marker↔socket tie could authorise the stop. |
| **E** | CI is a real gate: the committed suite runs as a gate, 3 bounded lifecycle cycles run with the exact marker and port release, `tests/shell` is in the permission and syntax loops, `actions/setup-java` v5, job ceiling 20 min, and the final Stop is no longer swallowed by `|| true`. |
| **F** | Shell regressions are COMMITTED under `tests/shell/` (not under gitignored `.tools/`): `run.sh`, `marker_identity_safety_test.sh` (42 assertions), `addr_socket_class_test.sh` (39), `lifecycle_identity_race_test.sh` (93 assertions, 10 real cycles), plus a README. |
| **G** | This checkpoint protocol. |

### Major files changed

16 files, +2796 / -60.

- New: `src/main/java/com/mraibo/cminsight/core/CloseOutcomeAware.java`, `src/test/java/com/mraibo/cminsight/test/RepositoryClosePropagationTest.java`, `tests/shell/{run.sh,marker_identity_safety_test.sh,addr_socket_class_test.sh,lifecycle_identity_race_test.sh,README.md}`
- Modified: `connection/BoundedPool.java`, `repository/{RepositoryContext,RepositoryManager}.java`, `bin/{start,stop}.sh`, `bin/lib/{cm-insight-addr,cm-insight-lifecycle}.sh`, `.github/workflows/bootstrap-test.yml`, `test/SelfTest.java`

### Commands actually run and results

Every number below comes from an executed command.

- `./build.sh` → exit 0, **`Tests run: 173, failures: 0`**, jar packaged; `./tests/selftest.sh` → exit 0
- `javac --release 17 -encoding UTF-8 -Xlint:all` over 50 main + 25 test sources → exit 0, **zero warnings**
- **Committed shell suite on real Linux** (Ubuntu 24.04 LTS, WSL2 kernel 6.6.87.2, Temurin 17.0.20.1): `CI=true ./tests/shell/run.sh` → exit 0, **3 passed, 0 failed, 0 not run in 66s**; `lifecycle_identity_race_test.sh` alone → **93 assertions, 10 cycles, exit 0**; a 12-cycle run → 105 assertions, exit 0
- **Full CI step sequence replayed on real Linux from a clean copy with NO `build/` directory** → every step passes: permissions (including `tests/shell/*.sh`), syntax, `--help`, `./build.sh` (173/0), the four safety guards, the committed suite, doctor, the 3-cycle lifecycle step, start/status/stop
- Pre-fix gate proofs, so the new tests are genuinely regressive and not vacuous:
  - `lifecycle_identity_race_test.sh` against a HEAD snapshot of `bin/` → **FAIL 16 of 127**, exit 1, with the exact CI message `PID <n> was reused by a different process before the health check succeeded`
  - `addr_socket_class_test.sh` against the pre-fix classifier → **14 assertions fail**
  - the repository regression against the pre-fix wiring (mutated copy) → **`Tests run: 173, failures: 3`**, the decisive test failing as `expected RepositoryException but nothing was thrown`
- On real Linux the stopped listener really is `[::ffff:127.0.0.1]:8080` and `status.sh` now attributes it to the tracked PID ("the SAME socket address as the configured bind 127.0.0.1")

### GitHub Actions — both events, same SHA

For work commit `43a3c7035c8165548f8014d57e0f862a9b8e9f00`:

| Run | Event | Conclusion |
|---|---|---|
| `36469846033` | push | **success — every step passed** |
| `36469852172` | pull_request | **success — every step passed** |

This is the section E requirement: CI is now a reliable gate on both events for the same tree,
observed rather than inferred. The previous failure mode (push red while pull_request green on the
identical SHA) is gone.

### IBM / DB live tests

**Not run, and they cannot be run in this goal.** `lib/{ibm,db2,oracle,app}` is empty, no IBM CM SDK
or JDBC driver is present, and no real connection is ever opened or closed. The physical-bound and
uncertain-close guarantees are therefore proven against fake resources only; in particular
"close outcome uncertain" is simulated by a fake whose `close()` throws.

### Independent review

An independent adversarial reviewer judged the Goal 01B corrections on the committed revision and
returned **verdict PASS, no blocker**. It verified by execution that the three blocking defects are
closed, including the hunts that matter most:

- **A**: 38 blocking checks, covering multi-resource contexts, the silent case where ONLY the pool
  report is uncertain (no close failure at all) and the switch is still refused before the factory
  (invocation count 1 -> 1), hostile report accessors failing closed, a close-aware resource whose
  close also throws, a method-name look-alike that is never consulted, an `Error` escaping close,
  repeated close, and the clean path still switching.
- **C**: a 71-expectation matrix including 15 adversarial spellings (zone suffix, over-long octet,
  nine-group literal, `:8080` suffix, `::ffff:*`, mixed case, hex tails) that all stay foreign, while
  equivalent spellings match; a real `ss` row `[::ffff:127.0.0.1]:55597` classified `exact` with the
  real owner PID; the boundary proven by live run **and** bytecode (`isLoopbackLiteral` false for 8
  mapped spellings, true for 7 controls); and an end-to-end untracked stop through the mapped socket.
- **B**: **negative result, stated plainly** — the reviewer could not construct any case where a
  foreign or recycled PID is accepted because of the grace (never-identifying -> refused;
  positive-then-contradicted -> refused; a foreign exact-marker responder with a non-identifying PID
  -> refused with no PID file). Their own 10-cycle soak was 10/10 and the state machine table 10/10.
- It independently confirmed the Actions runs through the public API rather than trusting this file,
  and confirmed the committed tests discriminate pre-01B code.

Its four non-blocking findings and their disposition:

| # | Severity | Finding | Disposition |
|---|---|---|---|
| F1 | medium | one contradictory sample after a positive identity was conclusive, so a launcher that re-execs through a non-identity image was falsely declared recycled (demonstrated at 2 s and 5 s transitions) | **FIXED.** Corroboration is now DECISIVE rather than counted: `/proc/<pid>/stat` field 22 (process start time) is invariant across `execve` but changes on PID reuse, so a contradiction is latched as recycle only when the process *instance* changed. The suggested "two consecutive samples, or no marker and no socket" alternative was **measured** to still fire inside the same windows and was rejected on that evidence. A genuinely reused PID is now refused FASTER (at the first contradiction, no grace wait). |
| F2 | low | the deciding contradictory sample was never counted, so the log always said "0 contradictory sample(s)" | **FIXED.** Samples are counted and the command line captured before any verdict. |
| F3 | medium | a quarantine occurring AFTER the context's close-time snapshot (a lease returned later) can leave a stale clean report while `metrics().quarantined()` is 1 | **NOT changed in 01B — recorded here as a deliberate Goal 02 decision point.** It is beyond section A's letter, and changing close semantics now would be scope creep. Goal 02 must choose explicitly: re-check quiescence before reporting, or treat outstanding leases as uncertain. |
| F4 | low | `stop.sh --untracked` accepts the exact marker as sole ownership evidence, so a marker-mimicking non-CM-Insight process can be signalled | **Accepted by design**, matching the documented Goal 01A model: the exact marker is the strongest available positive evidence, and the alternative would make recovery impossible. |

The reviewer also disclosed and corrected two flaws in its own harness rather than reporting them as
product defects, which is the standard I want recorded.

## Unresolved risks and accepted limitations

- **No IBM CM / DB2 / Oracle integration was tested, and no real connection was ever opened or
  closed.** The first real adapter (Goal 02) must re-prove the physical-bound and uncertain-close
  guarantees against real sessions, and it must surface a quarantined pool through the context's close
  outcome — the generic contract now exists for exactly that, but it has only ever been exercised with
  fakes.
- **The Linux lifecycle evidence comes from WSL2**, not from GitHub's ubuntu-latest image directly.
  The genuine Actions runs above are the authoritative Linux signal; the local WSL runs are
  corroboration.
- **Windows/Git Bash remains a secondary platform**: the committed suite skips its Linux-only lifecycle
  cases explicitly there and says so, rather than reporting a false pass.
- **A HARD link inside `secrets.dir`** remains undetectable by any path test (documented limitation
  from Goal 01A).
- **No TLS.** Remote access requires a reverse proxy; when one proxy fronts the application all clients
  share one throttle key and `X-Forwarded-For` is deliberately not trusted.
- **`BoundedPool` is still not instantiated at runtime** (no adapter yet); only `SelfCheck` and the
  tests read `PoolMetrics`. There is no diagnostics/metrics route, and the login lockout has no unlock
  route.
- The pre-existing `Start` flake had been reproduced at roughly 2 failures in 10 cycles on the pre-fix
  scripts; the fix is bounded and evidence-based, but no finite number of green runs proves an
  intermittent defect impossible. Two green events plus the committed 10-cycle soak is the strongest
  available statement.

## Exact next goal

**NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.**

Do not execute Goal 02. The next step is an architecture review of this Goal 01B result, and only then
a new approved goal file under `harness/`.

## Resume / handoff instruction

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture. First
`git fetch origin`, confirm the branch, and fast-forward to the current remote state; do not rewrite or
discard the existing Goal 01, 01A or 01B commits. Then read STATUS.md, harness/MASTER_GOAL.md and
harness/GOAL_01B_LINUX_LIFECYCLE_AND_CLOSE_PROPAGATION.md. Goal 01B is executed and pushed; the next
goal is NOT YET APPROVED. Do not execute Goal 02, do not implement IBM CM SDK integration or JDBC
analytics, and do not merge PR #1 unless explicitly asked. Keep the hard-bounded pool rule with no
emergency connections, Java 17 with javac+jar+bash, read-only V1/V2, and the ownership rule that a
process is never signalled without positive CM Insight evidence. When a new goal is approved, execute
only that goal, run its required validation, update STATUS.md using the non-self-referential checkpoint
protocol, commit coherently, push the branch yourself, verify remote HEAD equals local HEAD, and verify
both Actions events for the final SHA."

## Mandatory checkpoint rule

At the end of every approved goal record:

- date/time;
- branch;
- last completed implementation/work commit described by STATUS;
- exact work completed;
- major files changed;
- commands/tests actually run and results;
- live IBM/DB tests actually run or explicitly not run;
- Actions run IDs/results for the final tested SHA where required;
- unresolved risks;
- architecture changes only when approved;
- next goal status;
- final local/remote HEAD in the handoff report after push, not as a self-referential STATUS SHA.
