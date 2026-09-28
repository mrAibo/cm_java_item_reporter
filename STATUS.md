# Project Status

> Canonical cross-session checkpoint. Read this first when continuing in another chat/model. Update it at the end of every substantial goal.

## Project state

- Project: CM Insight
- Repository: mrAibo/cm_java_item_reporter
- Active branch: `bootstrap/cm-insight-architecture`
- Previous remote HEAD (the Goal 01 review checkpoint): `b6efa75b959f245d6fffd2549d81736cd96fc9c0` (short `b6efa75`)
- Goal 01A local final HEAD: `__LOCAL_HEAD__`
- Goal 01A verified pushed remote HEAD: `__REMOTE_HEAD__` (verified with `git ls-remote origin`, equal to local)
- Stage: **Goal 01A review-hardening EXECUTED; awaiting architecture review before Goal 02**
- Runtime target: Java 17 LTS, OpenJDK-compatible
- Build/deployment: javac + jar + bash; single JVM; no Maven/Gradle/Spring/containers/microservices
- Product safety mode: read-only V1/V2
- Current approved goal: `harness/GOAL_01A_REVIEW_HARDENING.md` (executed)
- Goals 02-05: PROVISIONAL; do not execute
- Next goal after 01A: **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

## Goal 01A completion record

- Date/time: 2026-09-28 (single working session)
- Branch: `bootstrap/cm-insight-architecture`
- Local final HEAD: `__LOCAL_HEAD__`
- Verified pushed remote HEAD: `__REMOTE_HEAD__`
- Frozen working-tree fingerprint during review: `489d4c7693e46067e0b5c43b36c4577754ad59912a609de86f7eeb4d94cf619b` (sha256 over per-file hashes of the 40-file change set, manifest in `.tools/goal01a/frozen-tree.txt`, gitignored). Note: `ARCHITECTURE.md` was edited ~19 s after that manifest was written, so its entry predates the final document; the committed SHA above is the authoritative identity.

### Exact work completed

All of section A-I of `harness/GOAL_01A_REVIEW_HARDENING.md`:

| Section | What was done |
|---|---|
| A (blocking) | Pool correctness: `createReserved()` re-checks `closed` under the lock before promoting a created resource to leased; creation failure releases the reserved slot for **every** outcome including `Error`; an UNCERTAIN close (an exception **or** an `Error` from `close()`) no longer frees physical capacity but moves the slot to **quarantine**, which is counted in `capacityInUse()`; usage rotation advances automatically on every borrow/use/close cycle; metrics split initial vs replacement creation and close attempt/success/failure, and the misleading `reconnect*` aliases were removed |
| B | `RepositoryManager` fails CLOSED when the previous context reported an uncertain close: no factory call, nothing published, state `FAILED`, diagnostics name the failures; `close()` stays best-effort |
| C | Explicit repository credential-reference model: one `SecretRef` per credential (CM user/password, JDBC user/password) supporting environment **and** secret-file sources, no values anywhere; `.env` wins and is authoritative; inline values are rejected instead of silently ignored |
| D | One central path resolver (`AppPaths`): `-Dcminsight.home` → `CM_INSIGHT_HOME` → working directory, printed as such; absolute configured paths bypass the home; a supplied-but-invalid home is refused; `bin/cm-insight` passes the home |
| E | doctor/runtime credential parity by DELEGATION: doctor has no credential parser of its own (its Bash secret probe was deleted) and maps the application's own `OK:`/`WARN:`/`ERROR:` lines via `ConfigCheck` |
| F | Non-loopback plain HTTP is refused unless `web.allowInsecureHttp=true` (default false), and the opt-in can never unlock a development-default credential |
| G | Bind-aware lifecycle scripts with an exact health-marker check and one shared address model (`bin/lib/cm-insight-addr.sh`) |
| H | A process is never signalled without positive CM Insight ownership evidence; no override flag; unresolved platform pids are never handed to `kill` |
| I | Regression tests, full validation, this record, commit and push |

### Major files changed

40 files: 32 modified + 8 new.

- New: `bin/lib/cm-insight-addr.sh`, `bin/lib/cm-insight-lifecycle.sh`, `src/main/java/com/mraibo/cminsight/config/AppPaths.java`, `src/main/java/com/mraibo/cminsight/app/ConfigCheck.java`, `src/test/java/com/mraibo/cminsight/test/{AppPathsTest,ConfigCheckTest,BoundedPoolHardeningTest,RepositoryCredentialsTest}.java`
- Modified (main): `connection/BoundedPool.java`, `connection/PoolMetrics.java`, `repository/{RepositoryManager,RepositoryContext,RepositoryState}.java`, `config/{RepositoryProfile,RepositoryProfileLoader,SecretResolver,ClassificationRules}.java`, `security/SecurityPolicy.java`, `web/WebServer.java`, `app/Main.java`
- Modified (scripts): `bin/{start,stop,status,clean,doctor}.sh`, `bin/cm-insight`
- Modified (docs): `README.md`, `SECURITY.md`, `ARCHITECTURE.md`, `conf/application.properties.example`
- Modified (tests): `BoundedPoolLifecycleTest`, `BoundedPoolTest`, `RepositoryManagerTest`, `RepositoryProfileLoaderTest`, `SecurityPolicyTest`, `SecretResolverTest`, `SelfTest`, `TestSupport`, `FakePoolFactory`, `FakeResource`

### Commands actually run and results

Every number below comes from an executed command; nothing in this record is inferred.

- `./build.sh` → exit 0, **`Tests run: 168, failures: 0`**, `build/cm-insight.jar` packaged
- `javac --release 17 -encoding UTF-8 -Xlint:all` over 49 main + 24 test sources → exit 0, zero warnings
- `java -jar build/cm-insight.jar --self-test` → exit 0, `CM Insight self-check: PASS (23 checks)`
- `./bin/doctor.sh` on the shipped configuration → exit 0
- Full lifecycle on a live socket: `start.sh` 0 → `status.sh` 0 → `stop.sh` 0 → `status.sh` 3
- Live HTTP: `GET /api/health` 200 with the exact body `{"status":"UP","service":"cm-insight"}`; `/api/info` 401 unauthenticated and with a wrong password, 200 with the real credentials
- IBM-free compile with an empty classpath → exit 0; 0 proprietary imports in `src/main`
- Section D/E/F through the real CLI: 7 configurations at their expected exit codes, including F-1 (blank password on a non-loopback bind → 3) and the invalid-boolean opt-in → 3
- Lead-owned independent harnesses (52 checks, all pass): `.tools/goal01a/harness/Pool01ACheck.java` (19), `ErrProbeCheck.java` (21), `F7F8F9Check.java` (12)
- Lead-owned marker gate `.tools/goal01a/harness/h1-marker-check.sh` → 17/17, `RESULT: H-1/H-2 CLEAN`
- Shell scenario suite (scripts-engineer, gitignored) → **396 checks, 0 failed, RESULT: PASS**
- Mutation harness (test-engineer, gitignored) → control green at 168/0, **30 of 30 mutants detected, zero evidence gaps**; the two survivors the reliability review flagged are now closed by new tests that provably detect their own mutants (`d-ignores-home-env` — a real child JVM with `CM_INSIGHT_HOME` set — and `a4-alias-under-another-name`, a metrics accessor-set equality so no future alias can hide under any name)
- Reliability mutation table (reliability-reviewer) → 28 mutations, 24 caught, 4 survivors each proven behaviour-changing and classified
- Determinism: 10 consecutive full-suite runs → 10/10 exit 0 with zero failures

### IBM / DB live tests

**Not run, and they cannot be run in this goal.** `lib/{ibm,db2,oracle,app}` is empty, no IBM CM SDK or JDBC driver is present, and no connection is opened anywhere: Goal 01A is a correction gate with no adapter. Pool and repository behaviour is therefore proven with fake resources only. In particular, "close outcome uncertain" is simulated by a fake whose `close()` throws; a real CM session or JDBC connection was never closed, failed to close, or quarantined.

### Independent reviews (none of these reviewers wrote the code they judged)

- **Pool**: 3 adversarial rounds over `BoundedPool`/`PoolMetrics`, final verdict **PASS** with 173/173 invariant checks and zero bound violations across close-throws storms, every-close-fails, degrade-then-refill, a 4000-cycle rotation storm and a 320-round close-vs-create race
- **Security**: 1 round, 7 findings (1 blocker, 1 high, 4 medium, 1 low); after the fixes a re-verification round reports **all 7 FIXED**, with F-1 proven by inversion (the old bypassing config now gives `operator:admin → 401` while the real password gives 200, and the socket is never created)
- **Reliability**: an independent mutation/failure-path review; its CI finding is addressed below

## Defects found by independent review and fixed during Goal 01A

The Goal 01A goal itself was triggered by 5 blocking and 6 high findings from the Goal 01 review. Those are all closed. The reviews that judged the fixes found a further set, all fixed before commit:

| # | Severity | Defect | Fix |
|---|---|---|---|
| A-F1 | blocker | `takeIdle()` removed an entry from the idle set before probing it, so an `Error` from the health probe dropped the slot while the resource stayed alive; measured 6 physically live resources with `configuredSize 1` | The probe is guarded; a failure retires the resource through the normal path |
| A-F7 | high | Introduced BY the A-F1 fix: the scan continued after a probe failure, so a healthy entry could be promoted to leased and then abandoned by the rethrow — leaked, unreachable even by `close()`, capacity burned forever | Stop scanning once a probe failure is recorded |
| F-1 | high | A blank `web.auth.password` with a configured user silently fell back to the published `admin`, which was then live on a non-loopback plaintext bind; doctor agreed it was safe | Refuse when ANY part of the effective credential is default-sourced; a literal `admin` password is refused too |
| H-1 | blocker | The health-marker check was a substring test, so a foreign service whose body merely CONTAINED the fields was "proven" CM Insight and `stop.sh --untracked` killed it | Exact whitespace-stripped equality; 8 lookalike bodies now rejected |
| H-2 | medium | Process identity matched an unanchored substring, so `-Dreview.archive.name=cm-insight.jar` counted as CM Insight | Structural match over real argv tokens |
| H-3 | medium | `start.sh` could report "exited during startup ... Nothing is running" while the instance was healthy and serving (3 of ~8 starts) | Liveness now requires BOTH pid namespaces and the socket table to show nothing |
| H-4 | low | A PID file holding a platform pid made `stop.sh` claim "not running" (exit 0) while the instance served | Namespace-aware liveness; an unmapped pid is never handed to `kill` |
| G-1 | medium | A wildcard bind claimed loopback-only sockets, so a `web.bind=0.0.0.0` stop could stop an instance bound only to `127.0.0.1` | A specific address is `foreign` to a wildcard bind; a new non-authorizing `sibling` class covers the other address family |
| C-1 | medium | Secret-file confinement was lexical, so a junction inside `secrets.dir` was followed and read from outside | Real-path containment (`toRealPath` + `NOFOLLOW_LINKS`) |
| A-F2..F6, F8, F9 | medium/low | Return-path probe `Error` stuck a slot as leased; `Error` paths were not counted as close/create failures; refused borrows were invisible; a failed borrow's wait was reported as 0 ms | Each fixed; the two accounting identities `closeAttempts == successes + failures` and `createAttempts == created + failures` now hold on every path |
| E-parity | medium | doctor reported an escaped secret-file reference as readable while the runtime refused it; and it never predicted the runtime's refusal of an unknown `repository.auto.activate` id | doctor no longer has a Bash credential parser at all; both verdicts come from the runtime's own code |
| R-F2 | medium | `stop.sh` could still exit 0 with "not running" from a stale PID file while the marker answered | See "Unresolved risks" — fixed in the same series |

## CI observation

**Corrected: the previous version of this file claimed GitHub reported no workflow runs. That was wrong.** The `bootstrap-test` workflow (`push` and `pull_request`, ubuntu-latest, JDK 17) has run repeatedly. Observed via the GitHub API:

| Run | SHA | Event | Conclusion |
|---|---|---|---|
| 36428754724 | `b6efa75` | pull_request | **failure** |
| 36428748754 | `b6efa75` | push | **failure** (step `Start` failed) |
| 36426890188 | `eb49b56` | pull_request | success |
| 36426884389 | `eb49b56` | push | **failure** |
| 36413271037 | `070f482` | push | success |

The `b6efa75` failure is the pre-Goal-01A background-start defect (H-3) and it is intermittent, which is why some runs of the same tree succeed. Reproduced locally on the pre-01A scripts: **10 clean start/stop cycles → 8 OK, 2 FAILED** with the exact CI message ("the application exited before writing anything to the log"), versus **10 OK, 0 FAILED** on the Goal 01A scripts. The full CI step sequence (permissions, `bash -n`, `--help`, config preparation, `./build.sh`, doctor, `start.sh --timeout 30`, status, stop) was executed locally against a clean copy of this tree and passed every step.

Goal 01A run for the commit above: `__CI_RESULT__`

CI is only claimed as passing where a run is named above with its conclusion.

## Unresolved risks and accepted limitations

- **No IBM CM / DB2 / Oracle integration was tested, and no real connection was ever opened or closed.** The physical-bound and uncertain-close guarantees are proven only against fake resources. The first real adapter (Goal 02) must re-prove them, and it must make a quarantined pool surface through `RepositoryContext.closeFailures()` — `BoundedPool.close()` currently swallows `Exception` close failures, so a quarantined pool does not yet appear as a context close failure. This is a **Goal 02 integration requirement**, not a Goal 01A defect.
- **Linux-only code paths are unexercised**: `ss -ltnp` parsing, `/proc/<pid>/cmdline`, Linux pid namespaces and systemd `WorkingDirectory=/`. All local runs were Windows 11 + Git Bash/MSYS + OpenJDK 17. The CI workflow runs on ubuntu-latest and is the only Linux coverage.
- **A narrow identity residual, accepted as by-design but measured**: a POSITIONAL argument whose basename is exactly `cm-insight.jar` still counts as identity evidence. Tightening is under way; if it is not in this commit it is listed here deliberately.
- **The shell-side guards are NOT covered by the committed test suite.** The exact health marker, structural argv identity, the `sibling` socket class and dual-namespace liveness are pinned only by the gitignored scenario suite under `.tools/goal01a-scripts/`. A committed artefact would not fail if one regressed. Moving a cheap subset (the static module checks) into `tests/` is a recommended follow-up.
- **H-3 was intermittent**, so its fix is argued by mechanism plus a measured before/after (2/10 → 0/10), not by proof of impossibility.
- **The two-instance mixed-address-family wildcard state is unreachable on this host** (the second bind fails); the `sibling` class makes it non-authorizing if it ever becomes reachable elsewhere, but that is documentation, not construction proof.
- **Documented, undetectable by any path test**: a HARD link inside `secrets.dir` (creating one already requires write access to that directory).
- **No TLS.** Remote access requires a reverse proxy; when one proxy fronts the application all clients share one throttle key, and `X-Forwarded-For` is deliberately not trusted.
- `BoundedPool` is still not instantiated at runtime (Goal 01 boundary: only `SelfCheck` reads `PoolMetrics`); there is no diagnostics/metrics route; the lockout is per source address with no unlock route and gates all non-health routes while health stays green.
- CI does not yet exercise the new fail-closed configurations or the ownership refusals, and `bin/lib/*.sh` was outside the workflow's syntax loops (being added).

## Positive findings retained

- Java 17 remains a sound baseline; SDK/JDBC types stay outside core business DTOs
- Only the exact health endpoint is public; authentication happens before 404/405, so there is no path oracle
- Configured-but-missing web secrets fail closed, and no credential value appears in `--print-config`, the validator, doctor, status, the logs or this file
- Versions/Parts remain behind the research gate; retention-admin stays disabled
- No proprietary JARs and no secrets are tracked; scripts are LF-only
- Goals 02-05 were not executed, and PR #1 was not merged

## Exact next goal

**NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.**

Do not start Goal 02. The next step is an architecture review of this Goal 01A result, and only then a new approved goal file under `harness/`.

## Handoff / resume prompt

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture. First `git fetch origin`, confirm the branch, and fast-forward to the current remote state; do not rewrite or discard the existing Goal 01 or Goal 01A commits. Then read STATUS.md, harness/MASTER_GOAL.md and harness/GOAL_01A_REVIEW_HARDENING.md. Goal 01A is executed and pushed; the next goal is NOT YET APPROVED. Do not execute Goal 02, do not implement IBM CM SDK integration or JDBC analytics, and do not merge PR #1 unless explicitly asked. Keep the hard-bounded pool rule with no emergency connections, Java 17 with javac+jar+bash, and read-only V1/V2. When a new goal is approved, execute only that goal, run its required validation, update STATUS.md, commit coherently, push the branch yourself, and verify remote HEAD equals local HEAD."

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
