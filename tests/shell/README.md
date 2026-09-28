# Committed shell regression tests

Goal 01B section F: the shell behaviour that Goal 01A proved with gitignored harnesses
under `.tools/` now has a small, committed, offline suite that CI actually gates on.

```text
tests/shell/
  run.sh                              the runner CI executes (single entry point)
  README.md                           this file
  marker_identity_safety_test.sh      exact health marker, structural argv identity,
                                      "an unrelated process/JVM is never an ownership
                                      proof" (including a real foreign JVM on the port)
  addr_socket_class_test.sh           IPv4-mapped IPv6 listener classification and the
                                      unchanged Java loopback rule (section C)
  lifecycle_identity_race_test.sh     bounded Linux start/status/stop soak plus the
                                      launch-transition identity case (section B)
```

Nothing here is a migrated historical harness: only the behaviours this goal touches are
pinned, and every assertion is cheap enough to run on a shared runner.

## Running it

```bash
./tests/shell/run.sh                 # every discovered test; 0 = all passed
./tests/shell/run.sh --list          # what would run
./tests/shell/run.sh --only addr     # one test, by path substring
./tests/shell/marker_identity_safety_test.sh   # a single test, standalone
```

The tests that exercise the real application lifecycle need a JDK 17 and
`build/cm-insight.jar` (`./build.sh` first). Set `JAVA_HOME` if `java` is not on `PATH`:

```bash
JAVA_HOME=/path/to/jdk-17 ./tests/shell/run.sh
```

CI runs `./tests/shell/run.sh` after the build step, and its non-zero exit fails the job.

## Test-file contract

A new regression test is picked up automatically when it follows this contract:

1. **Name**: `tests/shell/<something>_test.sh` (the primary pattern). The runner also
   discovers `tests/shell/<something>.test.sh`, so either spelling works; files are run
   once even if both patterns could match. Helper files without a `_test`/`.test`
   suffix are never discovered.
2. **Executable** and `bash -n` clean (`chmod +x`), one `chmod` bit per file.
3. **No arguments, no required environment**, and it locates the repository root itself:
   ```bash
   REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")/../.." && pwd)"
   ```
4. **Exit code is the verdict**: `0` = pass, any non-zero = fail. Never swallow a
   failure (`|| true`, `2>/dev/null` on the decisive command, a "known failures" list).
5. **Output**: one line per assertion (`ok: ...` / `FAIL: ...`) and a final
   `PASS: <file>` or `FAIL: <file> (N failed)`. Print the elapsed seconds too; the
   runner reports per-file and total runtime.
6. **Dependency-free and offline**: `bash` plus coreutils (`printf`, `sed`, `awk`,
   `grep`, `sort`, `mktemp`, `timeout`). Do not require `python3`, `nc`, `socat`,
   `jq`, GNU-specific flags, or network access. Tests that genuinely need a JDK must
   detect it and print an explicit `skip:` line when it is absent.
7. **Isolated and self-cleaning**: never bind, read or write the repository's
   `conf/application.properties`, `run/`, `logs/`, `data/` or `reports/`; use
   `mktemp -d`, an ephemeral/isolated loopback port, and `CM_INSIGHT_CONFIG`,
   `CM_INSIGHT_RUN_DIR`, `CM_INSIGHT_LOG_DIR` pointed into that temporary tree.
   Kill every process started and remove the temporary tree in a `trap ... EXIT` that
   also runs when an assertion fails.
8. **Bounded**: whole file well under the runner's per-file limit (300s by default),
   every wait loop with a hard iteration/time bound, no unbounded `sleep`.

## Bounds and ports

| Knob | Default | Meaning |
| --- | --- | --- |
| `CM_INSIGHT_TEST_TIMEOUT` | `300` | per-file timeout (the runner enforces it) |
| `CM_INSIGHT_TEST_TOTAL_BUDGET` | `600` | whole-suite budget; tests that cannot start inside it are reported `NOT RUN` and fail the suite |
| `CM_INSIGHT_SOAK_CYCLES` | `10` | lifecycle cycles in `lifecycle_identity_race_test.sh` |
| `CM_INSIGHT_SOAK_TIMEOUT` | script default | per-`start.sh` timeout in the lifecycle test |

Ports are never hard-coded to a fixed number a shared runner might already use: each
test asks for a free loopback port (scanning upwards from a random base) and passes it
through an isolated configuration file.

Measured on this revision, on the local equivalent of `ubuntu-latest` (WSL2 Ubuntu
24.04, Temurin 17.0.20.1, `CI=true`):

| What | Observed | Bound |
| --- | --- | --- |
| whole suite (`./tests/shell/run.sh`) | 48s, 3/3 passed (exit 0) | total budget 600s |
| `addr_socket_class_test.sh` | 1s | 300s |
| `lifecycle_identity_race_test.sh` (10 cycles) | 40s | 300s |
| `marker_identity_safety_test.sh` | 7s | 300s |
| CI lifecycle step (3 cycles, extracted from the workflow and run verbatim) | 12s | ~95s per cycle, job ceiling 20 min |

Ports actually used by those runs: 25630 (lifecycle soak), 31764 (closed-port probe) and
49542 (unrelated-JVM fixture) - all free, all chosen at run time, all released afterwards.
The CI lifecycle step uses whatever `conf/application.properties` declares (the example
ships `web.port=8080`); the replica run used 28641.

A failing test is a failing job: the runner exits non-zero for a failed test, for a test
that exceeds its per-file timeout, and for a test the suite budget could not even start
(`NOT RUN`). Demonstrated with `CM_INSIGHT_TEST_TIMEOUT=1 ./tests/shell/run.sh --only
marker_identity_safety_test.sh` (exit 1, "timed out after 1s") and
`CM_INSIGHT_TEST_TOTAL_BUDGET=0 ./tests/shell/run.sh --only addr_socket_class_test.sh`
(exit 1, "NOT RUN").

## Ownership

One writer per file. `run.sh` and this README are owned by the CI/test-suite engineer;
`addr_socket_class_test.sh` by the socket-address engineer; the lifecycle test by the
Linux identity engineer. A new case for an existing area goes into the existing file for
that area, or into a new file with a new owner - never into somebody else's file.

## What this suite does not do

* It does not re-run the Java unit tests (`tests/selftest.sh` does that, from `build.sh`).
* It does not validate IBM CM or JDBC connectivity; there is no live validation in this
  goal and none is claimed.
* It does not weaken or duplicate the inline CI safety guards: those stay in
  `.github/workflows/bootstrap-test.yml` and continue to fail the build on their own.
