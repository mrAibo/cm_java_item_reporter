# Project Status

> Canonical cross-session checkpoint. Read this first when continuing in another chat/model. Update it at the end of every substantial goal.

## Project state

- Project: CM Insight
- Repository: mrAibo/cm_java_item_reporter
- Active branch: bootstrap/cm-insight-architecture
- Stage: **Goal 01 (core runtime) implemented, integrated, tested and committed. Awaiting architecture/code review.**
- Runtime target: Java 17 LTS, OpenJDK-compatible
- Build/deployment: javac + jar + bash; single JVM; no build tool, no downloads, no network at runtime
- Product safety mode: read-only V1/V2
- Goal policy: dynamic; only the currently approved goal may execute
- Last approved goal executed: `harness/GOAL_01_CORE_RUNTIME.md`
- Goals 02-05: provisional drafts; architecture review required before any is approved
- **Next goal: NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

## Goal 01 checkpoint

- Date/time: 2026-09-28 15:03 +02:00
- Branch: `bootstrap/cm-insight-architecture`
- Goal 01 commit: **`fb42a0e55e69b551bffdcf0986714b8110dbdfb6`** (`fb42a0e`)
  "feat(goal-01): production-grade core runtime" - 82 files, 12081 insertions, 551 deletions
- Preceding mechanical commit: `cf745cf` "chore: pin LF line endings for text files"
  (line-ending policy only; the index already stored LF for every text file, so no content changed)
- This STATUS.md update is the commit that follows `fb42a0e`.
- Working tree verified clean at `fb42a0e`; no jar, archive, credential or generated directory is
  tracked.
- The goal commit is a small reviewable series of two commits, which MASTER_GOAL's definition of done
  explicitly permits. Neither commit executes GOAL_02 or any other provisional goal.

## Verification of the environment (read this before trusting any test claim)

- The repository was cloned fresh in this session; `main` contains only a one-line README, so all
  project content lives on `bootstrap/cm-insight-architecture`.
- No JDK was installed on the host. A Temurin JDK **17.0.20.1+1** was downloaded once and unpacked to
  the gitignored `.tools/jdk17`. Every compile, test, self-test and script run below used that JDK.
- `git` needed `-c http.sslBackend=openssl` on this host (schannel credential failure), and the
  secrets/tooling directories are gitignored, so nothing from `.tools/` can be committed.

## Goal 01 - what was actually implemented

### Configuration and profiles

- `AppConfig` rewritten: typed validating accessors (int/long/boolean/`Duration` with `ms/s/m/h/d`
  suffixes), blank value treated as unset, range checks, `require`, defensive `raw()` copy, source path.
- `SecretRef` / `SecretResolver` / `WebAuthSettings`: credential indirection `<key>.env` then
  `<key>.file` under `secrets.dir` then inline. Secret file paths are confined to `secrets.dir`, a
  world-readable secret file warns (POSIX only), and `SecretRef`/`WebAuthSettings` override
  `toString()` so a password cannot reach a log line.
- Fail-closed credential rule: a configured source that produces no value aborts startup with a
  diagnostic naming the field and the source. The `admin`/`admin` development default applies only
  when nothing is configured, and only on a loopback bind.
- `RepositoryProfileLoader` for `conf/profiles/*.properties`: full validation, duplicate-id rejection,
  filename/id mismatch warning, `.example` templates ignored, and rejection of inline passwords and of
  JDBC URLs that embed a credential in any of three driver shapes.
- `ClassificationRules`: `classification.<name>.label`/`.regex`, optional `classifications.file`,
  whole-name matching, deterministic order with the `default` rule last, invalid regex rejected.
- `FeatureRegistry` / `FeatureIds` / `FeatureModule`: configurable switches, unknown `feature.*` keys
  reported, and `feature.retention.admin=true` refused outright.
- Startup diagnostics: effective configuration printing with credentials redacted
  (`--print-config`), warnings for unknown keys against an exact key list, and lifecycle notes.

### Repository lifecycle

- `RepositoryManager` with explicit `RepositoryState` (NONE, INITIALIZING, ACTIVE, SWITCHING, CLOSING,
  FAILED, CLOSED), a serialized switch, bounded lifecycle diagnostics, and `statusSnapshot()`.
- Switch semantics: serialized under one lock; the previous context is closed before a new one is
  created; the new context is published only after it is fully initialized and validated; on failure
  nothing is published, any partially built context is closed, and the state becomes FAILED.
- `RepositoryContext` closes its resources in reverse order, keeps going after a failure, records
  `closeFailures()`, and is idempotent. `RepositoryContextFactory` is the documented seam that keeps
  this goal free of any IBM CM SDK dependency.

### Bounded pool hardening

- `BoundedPool` rewritten. Every capacity slot is accounted for under one lock as idle, leased,
  creating, or retiring; a creation is only authorised while that sum is below the configured size, so
  the bound cannot be overshot. Exhaustion produces backpressure via the borrow timeout. There is no
  emergency-connection path.
- Refill is lazy: a retired resource frees its slot and the next borrow creates the replacement, so
  there are no refill worker threads and no refill race.
- `Lease` carries automatic operation accounting and rejects use after close; age, operation and health
  rotation are applied on return and by an explicit `rotateStale()` sweep; `PoolMetrics` exposes
  configured size, available, leased, creating, retiring, borrows, timeouts, average/max wait,
  created/closed, validation failures, rotation counters, operations, and reconnect aliases.
- Graceful shutdown: `close()` stops lending, counts drained resources as retiring until their close
  has returned, and closes them; `awaitQuiescence` waits for outstanding leases and in-flight closes.

### Web / security foundation

- `/api/health` is the ONLY unauthenticated route and returns exactly
  `{"status":"UP","service":"cm-insight"}`; only the exact path is registrable as public - never a
  subtree and never a public prefix.
- Authentication happens before 404/405, so an unauthenticated caller cannot distinguish a protected
  path from a missing one. All errors are clean JSON with no stack trace or exception message.
- `LoginThrottle` per-source lockout with a hard-bounded, LRU-pruned table that prefers evicting
  unlocked entries, so a key flood cannot evict a live lockout; `429` with `Retry-After`; a locked key
  is answered without comparing credentials.
- `Authenticator` uses constant-time comparison, defends against malformed/oversized base64 and wipes
  its buffers. `SecurityPolicy` refuses non-loopback binds with default credentials and blank
  credentials, and its loopback test performs no DNS.
- Route abstraction (`Transport`/`RequestContext`/`Router`) keeps `com.sun.net.httpserver` inside
  `WebServer`; controllers do not see HTTP server details. Security headers and CSP are set in the
  adapter; the web pool is fixed-size with a bounded queue and caller-runs backpressure.

### Operational scripts

- `build.sh` + `bin/*` + `tests/selftest.sh` hardened: JDK 17+ enforcement with actionable messages,
  MSYS-safe `JAVA_HOME`, a documented class-path-separator decision, a REQUIRED test suite that fails
  loudly rather than skipping, jar packaging, doctor/start/stop/restart/status/clean with atomic PID
  files, foreign-PID protection and stale-PID cleanup. No script requires network access.

### Tests

- Dependency-free suite under `src/test/java/com/mraibo/cminsight/test`: `SelfTest` runner plus 13
  registered suites covering config and real profiles, secret resolution, classification, features,
  the non-loopback guard, pool capacity under real concurrency, borrow timeout, refill after an
  unhealthy resource, close/rotation behaviour, the return-while-closing race, repository switch
  lifecycle, authentication failure and lockout, router behaviour through the transport seam, the
  statistics availability contract, and an end-to-end test on an ephemeral socket.
- `Main --self-test` is a separate, smaller artifact self-check that needs no test classpath.

## Deliberately deferred (implemented, but not reachable at runtime yet)

Recorded here rather than silently omitted. None of these is a Goal 01 defect; each is attached by a
later goal, and no Goal 01 requirement depends on them.

| Capability | Status | Attached by |
| --- | --- | --- |
| `BoundedPool` | Implemented and fully tested, but only `SelfCheck` and the pool tests instantiate one; no runtime component creates a pool yet because there is no IBM CM or JDBC resource to hold | the CM adapter and JDBC analytics goals (pool wiring in `RepositoryContextFactory`) |
| `ClassificationRules.classify()` | Implemented and tested; nothing displays a classification yet | the ItemType metadata listing goal |
| `FeatureRegistry.isEnabled()` | Validated and reported at startup, but no feature is gated by it yet because no feature has an endpoint | the first gated feature module |
| `RepositoryManager.statusSnapshot()` | Implemented and tested; there is no diagnostics route yet | the diagnostics web goal |
| `ItemTypeStatistics`, `ItemTypeInfo`, `MetricValue` (incl. `available`/`unavailable`) | DTOs exist and are unit-tested, but nothing produces them yet | the statistics and metadata goals |
| Versions / Parts metrics | No producer and no SQL anywhere; `MetricValue.unavailable()` is the only expressible value | blocked on the documented Versions/Parts research gate |
| `cm.pool.*`, `jdbc.pool.*`, `statistics.*`, `cache.*`, `data.dir`, `reports.dir`, `logs.dir` | Carried in the example configuration so its shape is stable; no Goal 01 code reads them | the adapter, analytics and reporting goals |
| Retention administration | `feature.retention.admin=true` is refused outright | a later, separately reviewed goal |

## Tests and commands actually executed (with real results)

Run with `.tools/jdk17` (Temurin 17.0.20.1+1) unless stated otherwise.

| Command | Result |
| --- | --- |
| `javac --release 17 -encoding UTF-8 -Xlint:all -d build/classes <47 main sources>` | exit 0, zero warnings |
| `javac --release 17 -encoding UTF-8 -Xlint:all -d build/test-classes -cp build/classes <20 test sources>` | exit 0, zero warnings |
| `java -cp "build/test-classes;build/classes" com.mraibo.cminsight.test.SelfTest` | exit 0 - "Tests run: 123, failures: 0" across 13 registered suites |
| `Main --self-test` | exit 0 - "CM Insight self-check: PASS (23 checks)" |
| `bash build.sh` (Git Bash, `JAVA_HOME=.tools/jdk17`) | exit 0 - main+test compile, test suite passed, `build/cm-insight.jar` + `build/.version` written |
| `bash tests/selftest.sh` | exit 0 |
| `bin/doctor.sh` | exit 0 (12 OK / 7 WARN, all 7 actionable) |
| `bin/doctor.sh --strict` | exit 1 by design when the optional IBM/JDBC jars are absent |
| `bin/start.sh` -> `bin/status.sh` -> `bin/stop.sh` against the real server | start exit 0 "health OK"; status exit 0 RUNNING + Health OK; stop exit 0; status after stop exit 3 |
| Live HTTP checks | `/api/health` 200 public with the exact 38-byte body; `/api/info` 401 unauthenticated, 200 with credentials; `/` and `/web/*` 200 with credentials, 401 without; `POST /api/info` 405 with `Allow`; unknown path 401 unauthenticated (no path oracle); 5 bad credentials then 429 with `Retry-After` |
| Failure paths fed through the real app | inline password in a profile, duplicate repository id, missing `repository.ssid`, unsupported vendor, omitted `web.auth.*` source, `127.0.0.1%00` bind, credential-bearing JDBC URL, `feature.retention.admin=true` - each refused with an actionable message and a non-zero exit; no secret value printed in any of them |
| Redaction check | regex scan of the real banner and `--print-config` output for the actual secret values: not present |
| Repetition / flakiness | 10 consecutive suite runs, then 3 more after the security fixes - identical output, exit 0 each time |
| Portability | every shell script verified LF in index and worktree with a clean `#!/usr/bin/env bash` shebang; `.gitattributes` now pins `eol=lf` for all text files |
| Runtime soak (independent) | 3,135,155 requests over 135 s, 0 five-hundreds, 0 transport errors; thread count flat at 8 web threads and zero pool threads; ~2.5 MB retained heap after 3.1M requests; 10/10 lifecycle cycles exit 0; no orphan JVMs, no stale PID files |

## Independent reviews and their outcomes

All three reviews required by GOAL_01 were performed by members who did not write the code under
review, using executable harnesses rather than reading alone.

| Review | Verdict | Outcome |
| --- | --- | --- |
| Concurrency / pooling / resource leaks (independent) | needs_revision, then re-verified to clean | Round 1: 2 medium + 4 low defects, no blocker. Round 2: F1, F2, F4-F7 verified fixed; one residual leak in the same family found. Round 3: **51 of 51 checks PASS, zero open findings, no invariant regressed.** Every non-negotiable rule was proven under adversarial load - live resources <= size in a 32-thread storm with factory failures, poison and rotation, no emergency resource (exhaustion timed out with 0 ms CPU), no lost wakeup, lease exactly-once, never two live repository contexts, and 0 torn state/context samples out of >1.1M and >8.8M samples over 300,000 switches, with a positive control that does catch a deliberately wrong ordering. |
| Security / configuration (independent) | **PASS**, no blocker | Round 1: 1 high (already fixed) + 1 medium + 6 low. Round 2 re-verification: **all eight findings CLOSED** by execution, 2 new low findings. The high was the `LoginThrottle` comparator defect. 22 credential-holding surfaces proven secret-free; fail-closed policy, traversal, malformed-header, lockout, JDBC-URL credential rejection and git-hygiene checks all proven by execution on a live ephemeral-port server. |
| Requirements traceability / architecture drift (independent) | needs_revision, completion-gate items only | All 45 Goal 01 requirement bullets traced to code; no non-negotiable rule violated; both acceptance criteria proven. Only the STATUS/commit items were open, which this update and the goal commit close. |
| Documentation versus code (independent) | 13 of 16 findings closed and independently reproduced | Remaining items were the STATUS.md update and the goal commit (done here) plus one README wording nit (fixed). |
| Test quality / reliability (independent) | completed: **15 of 17 mutations caught** | Mutation-style falsification on two frozen revisions, each run 10/10 green. Caught: capacity overshoot, borrow deadline removal, premature timeout, `RepositoryContext.close()` stopping after the first failure, `switchTo` not closing the previous context, non-loopback treated as loopback, the lockout check removed, drain accounting dropped, health validation dropped, `ACTIVE` published with a null context, unbounded diagnostics, wrong-repository context accepted, blank-credential guard disabled, `getInt`/`getDuration` range guards disabled, duplicate profile id allowed, and `retention.admin` enforceable. Two gaps were NOT caught; **both are now closed** by tests that fail against the mutated code - the `BoundedPool.release()` closed re-check (removing it now yields exactly one failure: "the resource itself was closed exactly once (expected 1 but was 0)", the reviewer's exact signature) and `AppConfig.getLong`'s range guard - with zero collateral, and a third test now pins the Versions/Parts availability contract so a guessed number cannot replace `MetricValue.unavailable()`. Also proven: `build.sh` propagates a failing suite and never writes the jar, fails for missing/empty test sources and a missing entry point, exits 2 for an old or unparseable `javac`, enforces the per-test 30 s timeout against both a 600 s sleep and an interrupt-swallowing spinner, and the concurrency test genuinely overlaps (peakHolders=4, 640 borrows, 0 timeouts). |
| Runtime soak / leak / lifecycle (independent) | completed, 4 findings | 3,135,155 requests over 135 s across four passes (12/12/16/64 workers) with the required mix and a bad-credential stream: **0 five-hundreds and 0 transport errors**; p50/p95/p99 = 1/1/1 ms at 12 workers, 2/3/6 ms at 64, max 601 ms. No leak: exactly 8 web threads even at 64 concurrent clients, zero pool threads, ~2.5 MB retained heap after 3.1M requests, app log +0 bytes over 1.24M requests. Lifecycle: 10/10 start->status->stop cycles exit 0, no bind failures, no stale PID files, no orphan JVMs, port immediately reusable; SIGKILL leaves a stale PID file that `stop.sh` cleans; a second `start.sh` reports "already running" and leaves the PID file unchanged. Found a medium operational defect (a running instance can become untracked if its PID file is lost), which was then repaired and verified: `status.sh` reports `Status: UNTRACKED INSTANCE` (exit 1) and `stop.sh --untracked` recovers it, while `start.sh` refuses to add a second instance and leaves the PID file untouched. That repair also removed the unscoped `/proc` name scan (F1, high), an unbounded `wait` that could hang `start.sh` forever, and a stale-log diagnosis that reported an earlier run's cause. Results in `.tools/soak/RESULTS.md`. |

### Reviewer closing statement (quoted verbatim)

From the independent concurrency/pool/resource-leak reviewer, at the revision whose per-file SHA-256 prefixes are recorded in its log (`.tools/review-pool/evidence/verify-fixes.log`):

> Across three verification rounds the strongest invariant I proved is the hard bound: with instrumentation outside the pool (each resource counting itself in and out of existence and claiming an owner token), the number of live pooled resources never exceeded the configured size under any interleaving - 32- and 64-thread storms with factory failures, unhealthy poisoning, age/operation rotation, concurrent rotateStale, close()-racing-release traffic, initialize()-racing-close and 300,000 repository switches - with no resource ever leased to two threads, no resource closed twice or re-leased after close, and created == closed == live == 0 after graceful shutdown. The one thing I could not test is real IBM CM/JDBC resources: GOAL_01's no-SDK rule forced fake resources, so driver-level behaviour (real session close, JDBC isValid latency, network faults) was outside my reach.

### Defects found by review and fixed in this goal

1. `LoginThrottle.pruneIfNeeded` sorted by a volatile field that other threads mutate, so `TimSort`
   threw `Comparison method violates its general contract!` out of `recordFailure`, turning a
   brute-force flood into HTTP 500s instead of 401/429. Now sorts an immutable snapshot.
2. `LoginThrottle` LRU eviction could discard a live lockout under key pressure. Unlocked entries are
   now evicted first.
3. `BoundedPool.close()` dropped capacity accounting before the resources were actually closed, so
   `awaitQuiescence()` reported quiescence and `capacityInUse()` reported 0 while resources were still
   open. The drain is now accounted as retiring until each close returns.
4. An `Error` from `resource.close()` could permanently burn a capacity slot. Every retirement path now
   guarantees the slot is returned; `RepositoryContext.close()` keeps releasing the rest and rethrows.
5. `RepositoryManager` could expose `state()==ACTIVE` with nothing published (`getAndSet(null)` before
   publishing `SWITCHING`). The state is now published first.
6. `RepositoryManager.diagnostics` was unbounded. Now capped at 512 entries.
7. A configured-but-unresolvable secret silently fell back to `admin/admin`; the shipped template used
   exactly that indirection, so copying it without exporting the variable produced a default-credential
   console. Startup now fails closed and names the failed source.
8. `RepositoryProfile.toString()` printed `jdbcUrl` verbatim and credential-bearing JDBC URLs were
   accepted. Such URLs are now rejected and the printed form is masked.
9. `SecurityPolicy` stripped `%zone` from IPv4 spellings, so `127.0.0.1%00` counted as loopback. The
   zone is now only stripped for genuine IPv6.
10. `Router` accepted `/api/health/<anything>` and a public prefix. Only the exact path is now allowed.
11. Coarse prefix matching silently swallowed typo'd keys such as `web.prt=9999`. Unknown keys are now
    matched against an exact key list.
12. `RepositoryProfileLoader` let an unsupported vendor escape without the file name (it caught
    `IllegalArgumentException` while the parser throws `ConfigException`).
13. `AppConfig.webPort()` rejected `0`, so an ephemeral port was not expressible.
14. `.gitattributes` was missing, so a Windows checkout with `core.autocrlf=true` would have written
    CRLF shebangs and broken every script on Linux.
15. Documentation drift: hyphenated feature ids, a `build.sh` configuration check that does not exist,
    keys documented as read that nothing reads, `CM_INSIGHT_CONFIG` described as application-read, and
    an inaccurate capacity-accounting sentence. All corrected against the code.
16. `BoundedPool`'s retirement loops aborted on the first `Error` thrown by a resource's `close()`, so
    the remaining resources were never closed and their capacity slots stayed reserved. The worst case
    was `initialize()`'s rollback, which left `creatingCount` inflated and `initialized` true so every
    later borrow timed out forever - a permanently dead pool. All four loops now close every entry and
    return every slot before rethrowing the first `Error`.
17. `RepositoryManager` published the lifecycle state and the active context as two independent fields,
    so a reader taking two samples could observe a combination that never existed at any instant.
    They are now published together as one immutable value, and `status()` returns a single atomic
    snapshot for callers that need a consistent pair.
18. `RepositoryProfile.safeJdbcUrl()` could leave a fragment of a password that itself contained `/`,
    and a broader surgical mask then mangled the DB2 property form. A URL that embeds a credential is
    now redacted wholesale, which cannot leak a partial secret.
19. `LoginThrottle` could still evict a live lockout when every tracked key was locked (reachable with
    `web.auth.maxFailures=1`). Eviction now prefers unlocked entries; the residual extreme case is
    documented as a deliberate trade-off rather than left implicit.
20. `BoundedPool.initialize()`'s discarded-drain path was the last plain `close()` loop: if the pool was
    closed while initialization was still creating resources, an `Error` from one resource's `close()`
    left the remaining discarded resources open forever with no accounting entry - a leaked session for
    real CM/JDBC resources. It now uses the same close-everything-then-rethrow pattern.
21. `bin/start.sh` could hang **forever**. The failure path called `wait "${PID}"` to collect the child's
    exit code; because `bin/cm-insight` ends in `exec java ...` the child becomes a native Windows
    process, and under MSYS/Git Bash such a child can sit as a zombie (`/proc` reports state `Z`,
    `kill -0` says it is gone) while `wait` never returns. The script therefore blocked *after* it
    already knew the start had failed, so the operator saw no message at all. Reproduced in 3 of 5
    start/lose-PID/stop-untracked/start cycles by tracing to the exact line. The unbounded `wait` is
    removed; the exit code is deliberately not collected, which the script documents.
22. `bin/start.sh`'s failure diagnosis grepped the **whole** appended log, so a stale cause from an
    earlier run was reported as the reason for the current failure - observed live as an old
    "Configuration file not found" line being blamed for an unrelated failure. The script now records
    the log's byte offset immediately before launch and `startup_reason`, the decisive-lines extractor
    and the tail all read only this attempt's slice.
23. The operational scripts identified the port owner by scanning `/proc` for the first `cm-insight`
    JVM with **no port check**, so any unrelated instance on the host made `start.sh` refuse to start
    and `stop.sh` refuse to stop an already stopped service. Found by the runtime soak. The fallback is
    now strictly port-scoped (`netstat -ano`, `ss -ltnp` filtered on the configured address), with an
    explicit comment that there is no process-name scan.

## Unresolved risks and problems

- **No IBM CM or database has ever been contacted.** No IBM SDK and no DB2/Oracle instance are
  available here, so the CM adapter, JDBC pools, real statistics SQL and the retention viewer are
  untested against a live system. This is by design for Goal 01 and is not a Goal 01 defect.
- **Cleartext credentials on a non-loopback bind.** HTTP Basic over plain HTTP means credentials travel
  in the clear; the fail-closed rule only prevents the *default* pair from being exposed. TLS and
  reverse-proxy deployment guidance remain open.
- **Throttle key granularity.** The lockout is per source address, so clients behind one reverse proxy
  share a key: one abusive client can lock out the others.
- **Lockout eviction under an all-locked table.** Eviction prefers unlocked entries, but if every
  tracked key is locked - reachable with `web.auth.maxFailures=1`, where the first failure locks a key -
  the oldest locked entry is evicted to keep the table hard-bounded. Bounded memory was chosen over
  lockout retention because unbounded growth is the worse failure; keep `maxFailures` above 1 and size
  `web.auth.maxTrackedKeys` for the client population.
- **Lockout gating is global per address, with no unlock route.** While a source address is locked out,
  every non-health route returns 429 - including the authenticated UI and even a request carrying valid
  credentials - and there is no administrative unlock. `/api/health` stays 200, so monitoring will not
  notice. Behind NAT a handful of bad attempts by one client locks out every operator sharing that
  address for the whole window (default 5 failures / 5 minutes). Raise or lower
  `web.auth.maxFailures`/`web.auth.lockout` for the deployment, or front the console with something that
  terminates authentication, and treat an unlock path as follow-up work rather than a Goal 01 item.
- **No log rotation.** `logs/cm-insight.out` grows unbounded across restarts; the app itself writes
  nothing per request (measured: +0 bytes over 1.24M requests), so only startup banners and errors
  accumulate. Rotation belongs to the host's log management or a later operational goal.
- **Average/max borrow-wait arithmetic is asserted only loosely**; the pool reviewer could not verify
  numeric accuracy of those two metrics.
- **Graceful-stop timing is not fully verifiable on Windows.** Git Bash cannot deliver a catchable
  SIGTERM to a native Windows process, so the graceful-timeout and SIGKILL branches were proven with an
  MSYS process matching the command-line pattern, not with the real JVM. On Linux they use ordinary
  `kill -TERM`/`-KILL`.
- **`bin/clean.sh` deletes all of `build/`.** That is correct for a derived-output directory, but it
  also deletes any harness a teammate or reviewer put there. Harnesses belong outside `build/`.
- **CI has never run.** `.github/workflows/bootstrap-test.yml` is human-verified only; no workflow run
  has been observed. Do not claim CI passes until it has.
- **`bin/restart.sh` and `bin/clean.sh` were not both fully exercised as composed scripts.**
  The soak test did run `restart.sh` end to end (exit 0 in 3172 ms, port immediately reusable), but
  `clean.sh` was only run against a contended tree, where it correctly reported an incomplete removal
  because other processes held files open. Its success path on a quiesced tree was not separately
  demonstrated.
- **Two suite weaknesses were found and eliminated rather than accepted.** The lockout-window assertion
  in `AuthenticationTest` no longer reads the wall clock (it uses `LoginThrottle`'s injectable
  nanosecond clock), and `WebServerSocketTest` no longer probes for a free port - it binds
  `web.port=0` and reads the real port back from `WebServer.port()`, which removes the TCP TOCTOU
  instead of retrying around it. The suite therefore no longer contains an upper-bounded wall-clock
  assertion or a race-prone port selection.
- **Instance identification in the scripts took a second pass, and is now port-scoped.** An unrelated
  `cm-insight` JVM anywhere on the host could previously be mistaken for the owner of a free port,
  which made `start.sh` refuse to start and `stop.sh` refuse to stop; the lookup now uses only
  `netstat -ano`/`ss -ltnp` filtered on the configured address. The same work added an explicit
  `UNTRACKED INSTANCE` state, so an instance whose PID file was lost is reported and recoverable
  (`bin/stop.sh --untracked`) instead of being invisible. Not verifiable here: Linux-only paths, since
  Git Bash cannot deliver a catchable SIGTERM to a native JVM.

## Environment limitations

- Host had no JDK; `.tools/jdk17` (Temurin 17.0.20.1+1) was installed locally and is gitignored.
- `git` on this host requires `-c http.sslBackend=openssl`.
- The JVM here cannot write to the operating-system temporary directory, so the test suite keeps its
  scratch data under `build/test-tmp`.
- WSL is unavailable and the ambient `bash` may resolve to a Linux shell; scripts were verified with
  Git Bash (`C:\Program Files\Git\bin\bash.exe`) plus `JAVA_HOME=.tools/jdk17`.
- POSIX file-permission warnings cannot be exercised on Windows.

## Architecture decisions changed

None. No user-approved decision was altered: one JVM modular monolith, Java 17, no Maven/Gradle/Spring/
containers/microservices/CDN, V1/V2 read-only, DB2 + Oracle, hard-bounded pools with no emergency
connections, Versions and Parts unavailable until the documented research gate, retention
administration disabled. `ARCHITECTURE.md` gained one clarification (capacity accounting) that
describes the implementation of the existing rule rather than changing it.

## Next goal status

**NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.**

Goals 02-05 remain provisional drafts. None of them was executed. The next goal is created or approved
only after this result is reviewed.

## Resume instruction for a new session

Use this prompt:

"Continue the CM Insight project in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture. Read STATUS.md first, confirm the branch and HEAD with git log -1, and do not redo completed Goal 01 work. Goal 01 (core runtime) is complete, committed and awaiting architecture review. Do not start GOAL_02 or any other provisional goal until it is explicitly marked APPROVED in its goal file and STATUS.md. If a new goal has been approved, execute only that goal, build and test what is actually available, commit a coherent result, update STATUS.md, and set the next goal back to NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED."

## Mandatory checkpoint rule

At the end of every substantial approved goal update this file with:

- date/time
- branch and verified HEAD/commit reference
- exact work completed
- major files changed
- commands/tests actually run and their results
- live IBM/DB tests actually run or explicitly not run
- unresolved failures and risks
- architecture changes only when user-approved
- next goal status: NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED
- a copy/paste resume/review instruction
