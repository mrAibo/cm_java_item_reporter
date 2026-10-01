# Goal 04A - Report confinement and local-history boundary closure

**Status: APPROVED correction goal.** Goal 04 architecture review found one report-download
security blocker and one structural-boundary gap that must be closed before Goal 05.

Do not execute Goal 05 in this goal. Keep PR #1 open, draft and unmerged.

## Scope

This is a narrow correction pass. Preserve all accepted Goal 01-03B invariants and all valid
Goal 04 work. Do not redesign history, reporting, targeted refresh, the operator UI or analytics.

The reviewed Goal 04 code revision is:

`c8a871d00e66cd857e2cd5f4148b0df76b7a1438`

The architecture-review handoff HEAD is read from Git; at review start it was
`e8d5a298dc0b7021c1e93f2eef898a541648781e`.

## Blocker A - report download has a check/use symlink race

`ReportService.find()` verifies the candidate with `NOFOLLOW_LINKS` and `toRealPath()`, then
returns a `GeneratedReport` carrying an ordinary `Path`. `ReportApiRoutes.download()` later
calls `Files.readAllBytes(path)`. That second open follows links.

A deterministic review probe proved the defect on Linux: after `find()` returned, replacing the
checked artifact with a symlink to an outside file made the subsequent read return the outside
file's bytes. Therefore the current claim that the real-path check “closes the window” is false.

### Required correction

- The web/download layer must never consume a previously checked filesystem `Path` as authority.
- The component that owns report confinement must also own the content open/read operation.
- The final artifact open used for download must use `NOFOLLOW_LINKS` (for example an opened
  channel with `READ` + `NOFOLLOW_LINKS`), and the bytes must be read from that same open handle.
- Enforce `MAX_REPORT_BYTES` on the opened artifact; do not trust an earlier size stat.
- A symlink or other non-regular final component must be refused without reading its target.
- Preserve generated opaque ids, enum-owned extensions, same-directory temp files and atomic/replace move.
- Do not weaken this into “check again immediately before read”; check-then-open is the same race.
- Update comments/security documentation so they describe the actual open-time guarantee.

### Mandatory evidence

Add a Linux-capable regression that uses a temp directory where symlinks are supported and proves:
1. a normal artifact downloads correctly;
2. a symlink artifact is refused;
3. replacing a previously discovered artifact with a symlink cannot expose the outside target;
4. an oversized artifact is refused while reading from the opened handle;
5. the test has a mutation/control demonstrating that a plain following open would read the outside file.

The Windows development filesystem may refuse symlink creation; that is not a reason to omit the
test. Run it on Linux/CI or another filesystem that supports the primitive and report platform limits honestly.

## Required boundary closure B - the history exemption must stay local-only structurally

The architecture accepts a named `history/` exemption: the local history store legitimately writes
to its own H2 file and therefore cannot obey the repository-database SELECT-only rule.

However, the exemption must not become a future escape hatch for repository-database writes.
Current production wiring is safe, but the “local history cannot modify the repository DB” claim
is presently stronger than the structural guard that enforces it.

Add a committed source guard/test that fails if the history implementation starts depending on the
repository JDBC path or repository credentials. At minimum refuse in `history/`:
- `DriverManager`;
- repository DB driver/session/pool types from `com.mraibo.cminsight.db`;
- repository credential/profile access used to obtain JDBC URL/user/password;
- DB2/Oracle JDBC URL literals or driver class names.

Allow the fixed application-local H2 driver-name / `jdbc:h2:file:` construction required by the store.
Keep the typed `StatisticsSnapshot` / history-recording DTO dependency allowed.

Include planted positive controls: a forbidden repository-JDBC reference in `history/` must fail,
while the committed H2 implementation must pass.
## Contract cleanup C - shell-runner help must match execution

`tests/shell/run.sh` executes with defaults 900 seconds per file and 1800 seconds total, but its
`--help` text still says 300 / 600. Correct the help text and add/retain a test that prevents those
defaults from drifting apart again.

The 900/1800 values themselves are accepted for this filesystem and are not to be reverted merely
to make the numbers smaller.

## Accepted Goal 04 work that must not regress

- one shared analytics-operation gate for full and targeted analytics work;
- Goal 03B physical-death / generation-owned scan gate semantics;
- targeted refresh never mutates the published full snapshot/totals/history;
- one database anchor and accepted distinct-ItemID/all-segments counting semantics;
- history records only normally published full scans and preserves coverage/error state;
- optional H2 must never break repository activation;
- HTML escaping, CSV formula fencing and real non-formula OOXML XLSX;
- all new routes authenticated; state-changing routes keep distinct exact action headers;
- offline UI with local assets and text-only dynamic DOM insertion;
- no proprietary/vendor JARs or credentials committed.

## Validation

Run the full Goal 04 validation set plus the new regression/mutation controls. Re-run real H2 and
real IBM SDK checks when the local prerequisites are available, reported separately. No live CM,
DB2 or Oracle claim may be made without an actually reachable server.

Update STATUS.md with exact tests, exact local/remote HEAD and exact-SHA push + pull_request Actions.

At completion stop with:

**NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

Do not execute Goal 05.
