# Project Status

> Canonical cross-session checkpoint. Read this first when continuing in another chat/model. Update it at the end of every substantial approved goal.

## Project state

- Project: CM Insight
- Repository: mrAibo/cm_java_item_reporter
- Active branch: `bootstrap/cm-insight-architecture`
- Reviewed remote HEAD before Goal 01B: `9c7168aa238633a004850ba658d546dd3177be52`
- Goal 01 implementation commit: `fb42a0e55e69b551bffdcf0986714b8110dbdfb6`
- Goal 01A review-hardening work completed through the reviewed HEAD above
- Stage: **Goal 01A externally reviewed; Goal 01B correction is APPROVED**
- Runtime target: Java 17 LTS / OpenJDK-compatible
- Build/deployment: javac + jar + bash; single JVM
- Product safety mode: read-only V1/V2
- Current approved goal: `harness/GOAL_01B_LINUX_LIFECYCLE_AND_CLOSE_PROPAGATION.md`
- Goals 02-05: PROVISIONAL; do not execute
- Next goal after 01B: **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

## Goal 01A review verdict

**MOST GOAL 01A CHANGES ACCEPTED, BUT THREE BLOCKING SEAMS REMAIN BEFORE IBM CM/JDBC INTEGRATION.**

### Accepted Goal 01A improvements

- BoundedPool no longer lends an in-flight created resource after close begins.
- Failed physical close is quarantined and consumes capacity.
- pool creation/close metrics are substantially more truthful.
- usage accounting advances automatically.
- RepositoryManager fails closed when RepositoryContext itself reports an uncertain close.
- repository credentials have explicit env/file SecretRef sources.
- secret-file confinement and path handling are substantially improved.
- doctor delegates core credential/exposure decisions to Java.
- non-loopback plaintext Basic Auth requires explicit insecure opt-in and default development credentials remain forbidden remotely.
- Bash lifecycle logic is centralized and exact health-marker matching is used.
- Goal 02 was not started.
- no proprietary IBM/JDBC JARs or credentials were committed.

## Remaining blockers found by external review

### 1. Pool quarantine does not automatically propagate to RepositoryContext

BoundedPool can finish `close()` normally with `metrics().quarantined() > 0`. RepositoryContext only
owns generic AutoCloseables and currently learns uncertainty from thrown close failures. Therefore a
pool can have uncertain physical resources while RepositoryContext reports a clean close.

This must be solved generically in core before adapters are attached. Goal 02 must not be responsible
for remembering a manual metrics check.

### 2. Linux startup identity race is reproduced on current exact SHA

GitHub Actions for exact SHA `9c7168aa238633a004850ba658d546dd3177be52`:

- pull_request run `36443456621`: **SUCCESS**
- push run `36443449216`: **FAILURE**
- failure step: `Start`

The failed job log was retrieved during this review. `start.sh` reported:

```text
ERROR: PID 3451 was reused by a different process before the health check succeeded
```

But the diagnostic immediately afterwards showed the same PID alive as:

```text
java ... -cp .../build/cm-insight.jar com.mraibo.cminsight.app.Main --config ...
```

The application was still starting; moments later it wrote normal startup output and served health.
Thus the recycled-process conclusion is false.

### 3. IPv4-mapped IPv6 listener representation is classified as foreign

The same failed Linux run later had a live exact CM Insight health marker, but cleanup could not prove
ownership because `ss` reported:

```text
::ffff:127.0.0.1:8080
```

for configured `web.bind=127.0.0.1`, and the current address model classified that listener as foreign.

The shell socket layer must recognize mapped IPv6 as an equivalent socket-table representation of the
underlying IPv4 address. This must NOT loosen Java web-exposure rules for a user-configured
`::ffff:127.0.0.1` bind.

## Checkpoint-protocol correction

The previous STATUS attempted to record the final HEAD as `ffc9ced7...`, but the actual reviewed
remote HEAD is `9c7168aa...`.

A commit cannot truthfully contain its own SHA. From Goal 01B onward:

- STATUS records the last completed work/implementation commit it describes;
- authoritative current branch HEAD is read from Git;
- the final handoff report records exact local and verified remote HEAD after the STATUS commit is pushed;
- no self-referential SHA placeholders.

## CI status

CI is **not yet a reliable green gate** for Goal 01A because push and pull_request runs for the exact
same current SHA disagree.

The external review can retrieve Actions job logs and has confirmed the real failure described above.
Goal 01B must make both event runs green on the same final SHA and commit the relevant shell
regressions.

## Exact next goal

Execute only:

1. `harness/MASTER_GOAL.md`
2. `harness/GOAL_01B_LINUX_LIFECYCLE_AND_CLOSE_PROPAGATION.md`

Do not execute Goal 02.

## Resume / handoff instruction

"Continue CM Insight in mrAibo/cm_java_item_reporter on branch bootstrap/cm-insight-architecture. Fetch and fast-forward to the current remote branch, read STATUS.md, harness/MASTER_GOAL.md and harness/GOAL_01B_LINUX_LIFECYCLE_AND_CLOSE_PROPAGATION.md, and execute only Goal 01B. Preserve accepted Goal 01A behavior. At completion commit coherently, push the branch yourself, verify local and remote HEAD equality, require both push and pull_request Actions runs on the same final SHA to pass, update STATUS.md using the non-self-referential checkpoint protocol, set the next goal to NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED, do not merge PR #1, and stop."

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
