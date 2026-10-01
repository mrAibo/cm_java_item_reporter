# Goal 01A - Architecture review hardening

**Status: APPROVED / EXECUTE**

This is a correction gate after the external architecture/code review of Goal 01. It is NOT Goal 02 and must not implement IBM CM or JDBC business integration.

## Start condition

The review checkpoint commit is on `bootstrap/cm-insight-architecture`. Before editing:

1. confirm the current branch;
2. `git fetch origin`;
3. fast-forward the local branch to the current remote review checkpoint if needed;
4. read STATUS.md and harness/MASTER_GOAL.md again.

Do not discard or rewrite the Goal 01 commits.

Use subagents for at least:
- connection-pool concurrency/failure semantics;
- security + Bash lifecycle;
- configuration/secret/path semantics;
- independent regression testing.

The lead agent owns the integrated result.

## Why this correction exists

Goal 01 is strong, but the architecture review found defects that matter specifically before real
`DKDatastoreICM` and JDBC resources are attached. Fake-resource tests must not give us guarantees
that fail when a real close/connect operation is uncertain.

## A. Connection-pool correctness — BLOCKING

### A1. Close racing an in-flight lazy creation

Current risk: a borrower may reserve a creation slot, enter `factory.create()`, then another thread
closes the pool. When creation returns, the resource can currently be promoted to `leased` and
returned after the pool has already closed.

Required invariant:

> Once pool close begins, no new lease may be handed out, including a resource whose creation started
> before close.

Implement a deterministic gated-create regression test:
- borrow enters a blocked `factory.create()`;
- another thread closes the pool;
- creation is released;
- borrow must not receive a usable lease;
- the just-created resource must be retired/closed safely;
- final accounting must be coherent.

Also cover `Throwable`/Error during creation so `creating` capacity cannot remain permanently stuck.

### A2. Failed close must NOT silently free physical capacity

Current code counts a resource as closed and frees the slot even when `resource.close()` throws.
That is unsafe for real CM/JDBC resources: a close failure does not prove that the physical session
or connection disappeared. Opening a replacement may therefore exceed the configured physical hard
bound.

Change the semantics to fail safe.

Required invariant:

> A resource whose close outcome is uncertain must not authorize creation of a replacement merely
> because close threw.

Introduce an explicit failed-retirement/quarantine state or equivalent accounting. At minimum expose:
- close attempts;
- close successes;
- close failures;
- quarantined/uncertain resources or capacity slots.

A failed close may degrade pool capacity. Safety is more important than availability.

Add a fake resource whose `close()` throws BEFORE marking itself closed. Prove that:
- a replacement is not created into that uncertain slot;
- physical live count never exceeds configured size;
- diagnostics/metrics make the degraded state visible;
- shutdown still attempts every other resource and does not leak them because one close failed.

Do not preserve the current test assumption that every close exception is safe to refill after.

### A3. Usage/rotation accounting cannot depend on a forgotten manual call

The architecture intended operation/usage rotation to be hard to forget. The current generic lease
only increments usage when a caller explicitly invokes `recordOperation()`.

Choose and document safe semantics such that an ordinary borrow/use/close cycle advances the resource
usage budget automatically. Explicit additional operation counting may remain if useful, but a caller
that follows the normal try-with-resources pattern must not accidentally create a resource that has
zero lifetime usage forever.

Update tests and documentation.

### A4. Metrics must mean what they say

Do not label all creation attempts as reconnect attempts. Initial pool population and replacement/
reconnect creation are different events.

Either:
- track initial creation and replacement/reconnect metrics separately, or
- remove reconnect aliases until the adapter can provide truthful reconnect semantics.

Also distinguish successful close from close attempt/failure.

## B. Repository lifecycle — BLOCKING

A repository switch must not open a fresh repository context after the previous context reported an
uncertain resource close.

Required behavior:
- old context is closed first;
- if closing reports resource failures/quarantined capacity, the switch fails closed;
- no new context/factory creation occurs;
- manager state and diagnostics clearly report the failed switch;
- shutdown remains best-effort and releases every resource it can.

Add a regression test that proves a failed old-context close cannot lead to a new live context.

Do not catch a cleanup failure merely to continue into creating new CM/JDBC connections.

## C. Repository credential references — HIGH

The current loader/documentation accepts `.env` and `.file` repository credential indirection, but
`RepositoryProfile` only preserves the environment-variable fields. File references can therefore be
accepted and then silently lost.

Fix the model before Goal 02.

For each of:
- CM user
- CM password
- JDBC user
- JDBC password

represent the configured credential source explicitly and without storing the secret value. Support
environment-variable and secret-file references consistently. Inline repository credentials remain
forbidden.

Requirements:
- a `.file` reference survives loading and is available to the future adapter;
- conflicting/ambiguous sources have deterministic documented precedence or are rejected;
- a configured-but-missing authoritative source fails closed when resolution is requested;
- direct `repository.*.user=<value>` and password forms must not be silently accepted/ignored;
- `toString()`, diagnostics and STATUS never reveal values;
- tests cover env-only, file-only, conflicting sources, missing source, inline rejection, and secret
  path traversal/symlink behavior as appropriate.

Prefer one reusable credential-reference abstraction rather than adding eight ad-hoc strings.

## D. Path semantics — HIGH

Java runtime paths and shell-script paths must use the same base.

Current risk: shell scripts resolve relative paths such as `profiles.dir=conf/profiles` from the
application root, while Java uses the caller's process working directory.

Define one rule:

> Relative CM Insight operational paths are resolved against the application/runtime home, not the
> directory from which the launcher happened to be invoked.

Implement one central Java path resolver and make the launcher provide/derive the application home.
Cover at least:
- profiles.dir
- classifications.file
- secrets.dir
- future data/reports/logs path hooks where applicable.

Add a test or runnable verification that starts the launcher from a directory outside the repository
and still loads the same configuration paths.

Do not require a specific current working directory in systemd or interactive shells.

## E. Doctor/config parity — HIGH

`bin/doctor.sh` currently says a missing configured `web.auth.password.env` falls back to
`admin/admin` on loopback. The application correctly does the opposite: once a source is configured,
it is authoritative and missing => fail closed.

Make doctor and runtime semantics identical.

Important cases:
- env configured but missing => ERROR, even on loopback;
- env configured and missing while a file source is also configured => follow the SAME precedence as
  Java; do not claim the file saves it if Java would fail on the env source;
- configured file missing => fail closed;
- no credential source at all + loopback => development admin/admin fallback may remain;
- no source + non-loopback => refusal.

Prefer delegating exact configuration validation to Java (or otherwise centralizing the rules) rather
than maintaining a second subtly different parser in Bash.

## F. HTTP exposure policy — SECURITY BLOCKING

HTTP Basic over plain HTTP sends reusable credentials without transport encryption.

Until CM Insight has first-class TLS, make accidental remote plaintext exposure fail closed by default.

Required policy:
- loopback remains the normal/default supported bind;
- non-loopback plain HTTP is refused unless the operator sets an explicitly named **insecure/test
  override** (for example `web.allowInsecureHttp=true`);
- the override must be false by default and produce a prominent startup + doctor warning;
- documentation must say production remote access should use HTTPS termination/reverse proxy or an
  equivalent secure tunnel until embedded TLS is implemented;
- never weaken the existing admin/admin non-loopback refusal.

Do not implement a large TLS subsystem in this correction unless it is clearly simpler than the safe
fail-closed policy above.

Keep the known reverse-proxy throttle limitation documented; do not blindly trust X-Forwarded-For.

## G. Bind-aware operational scripts — HIGH

`start.sh`, `status.sh` and `stop.sh` currently probe/listen-match only `127.0.0.1`, even though
the app accepts other bind values.

Create one shared notion of probe/listener address and use it consistently.

Cover:
- 127.0.0.1 and other 127/8 loopback literals;
- localhost;
- ::1 where supported;
- wildcard bind (probe through a loopback address, but listener matching must recognize wildcard);
- a specific non-loopback address when the explicit insecure override is used.

The health check must verify the exact CM Insight marker, not merely any HTTP 2xx response.

Prefer factoring duplicated shell helpers into a small sourced `bin/lib/*.sh` module if that reduces
drift; do not refactor for aesthetics alone.

## H. Never kill an unrelated JVM — SECURITY/OPS BLOCKING

Current `bin/stop.sh --untracked` can proceed when the configured port is owned by any JVM even when
the CM Insight health marker is absent. That can terminate an unrelated Java/Tomcat/WAS process.

Required invariant:

> A process is never signalled merely because it is a JVM.

For an untracked stop, require positive CM Insight ownership evidence:
- exact CM Insight health marker tied to the same listening socket/process; or
- when health is unavailable, a command line/process identity that explicitly identifies CM Insight.

If ownership cannot be proven, refuse and print manual diagnostic instructions. Never guess.

Add executable tests or reproducible script tests for:
- unrelated JVM on target port => never signalled;
- unrelated non-JVM => never signalled;
- confirmed untracked CM Insight => stoppable;
- stale/recycled PID => never signalled.

Apply the same fail-safe principle to tracked PID cases when process identity cannot be proven; if an
exception is retained for a platform, it must require explicit operator intent and be documented.

## I. CI / validation / push gate

Keep all 123 existing tests green unless a test encoded unsafe semantics that this review explicitly
changes; replace such a test with the corrected invariant.

Add focused regression tests for every item above.

Run at minimum:
- `./build.sh`
- `./tests/selftest.sh`
- `./bin/doctor.sh` in representative good and fail-closed configurations;
- start/status/stop lifecycle from the repository root;
- start/status/stop with the launcher invoked from a different working directory;
- the safe untracked-stop scenarios that can be exercised in the environment.

Do not claim IBM CM/DB validation; this correction still has no IBM/JDBC integration.

The existing GitHub Actions workflow has not produced an observed run yet. After pushing, inspect the
remote status if the environment allows it. If Actions are disabled/unavailable, record that fact
exactly instead of claiming CI passed.

## Completion and GitHub push — MANDATORY

At the end:

1. review the complete integrated diff;
2. update STATUS.md with every fix, every test actually run, and remaining risk;
3. set the next goal back to **NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**;
4. create coherent commit(s);
5. **push the commits yourself** to `origin/bootstrap/cm-insight-architecture`;
6. verify remote branch HEAD equals local HEAD (for example with `git ls-remote`);
7. record the pushed remote SHA in STATUS.md;
8. do NOT merge PR #1 and do NOT execute Goal 02.

If push credentials/network are genuinely unavailable, treat that as an incomplete handoff: record
the exact error and stop. Do not tell the user to perform a routine push unless the environment truly
cannot authenticate.
