# Goal 02A - IBM adapter semantics hardening

**Status: COMPLETED / PUSHED / GREEN - AWAITING ARCHITECTURE REVIEW. DO NOT RE-EXECUTE.**

Implementation commit: `4b810b361c3c34b0b9118ddcbc560ee47d21560b`.
Both exact-SHA GitHub Actions runs are green for it: push `36527200713`, pull_request `36527204017`.
Core 252 tests and IBM 41 tests green, plus `--require-ibm` against the real CM 8.7 SDK.
No live CM validation has been performed - no server is reachable from the execution host.

This was a focused correction gate after the architecture/code review of Goal 02.

It is NOT Goal 03. Do not add JDBC analytics, DB2/Oracle counting SQL, Versions/Parts, retention
administration, content viewing, migration or write operations.

Preserve the accepted Goal 02 architecture:
- optional IBM source set;
- test-only IBM SDK compile stubs;
- real-SDK strict build path;
- ServiceLoader provider boundary;
- hard-bounded CM session pool;
- partial activation cleanup context;
- read-only ItemType/Retention services;
- metadata cache;
- authenticated API;
- production-path smoke command;
- Linux lifecycle/security invariants from Goal 01A-01C.

## Why this correction exists

Goal 02 is substantial and most of it is accepted. The review found five semantics gaps that must be
closed before JDBC Goal 03 is allowed to build another physical-resource adapter on the same core.

The exact reviewed Goal 02 SHA is:

`87ffeb8e6de9b5ab6577353a7c54a0c6e718dead`

Both exact-SHA GitHub Actions runs are green:
- push `36501185151`
- pull_request `36501190778`

The correction therefore starts from a working tree and should remain narrow.

---

# A. Unknown ResourceFactory create failures must fail safe by default - BLOCKING

Current behavior:

- `CreationFailure(PROVEN_CLEAN)` releases the reserved slot;
- `CreationFailure(UNPROVEN)` quarantines it;
- any other Exception/Error releases the reserved slot.

The last rule violates the physical hard-bound architecture. A plain exception is not proof that a
factory allocated nothing or cleaned everything it allocated. Goal 03 will introduce JDBC factories,
and requiring every future adapter author to remember a special exception is exactly the kind of
fail-open dependency the generic pool exists to remove.

## Required rule

> A creation reservation may be released after failure ONLY when the factory explicitly proves cleanup
> clean. Unknown/untyped failure is conservative: quarantine.

Implement one clear generic contract. An acceptable shape is:

- explicit `CreationFailure(PROVEN_CLEAN)` -> release;
- explicit `CreationFailure(UNPROVEN)` -> quarantine;
- ordinary Exception, RuntimeException or Error -> quarantine by default.

Equivalent APIs are acceptable if the safety direction is identical.

Update `ResourceFactory` documentation so "plain throw means clean" is no longer a contract.

Factories/tests that KNOW they failed before allocating anything must say PROVEN_CLEAN explicitly.
Do not preserve an unsafe historical test merely for compatibility: update the four Goal 01 assertions
that encoded the old behavior.

## Required tests

- plain checked exception -> quarantined;
- plain RuntimeException -> quarantined;
- plain Error -> quarantined before Error propagates;
- explicit PROVEN_CLEAN -> slot released;
- explicit UNPROVEN -> slot quarantined;
- partial initialize with unknown failure never frees the failed attempt's physical reservation;
- capacity identity holds under concurrent borrow/create/close;
- mutant/control demonstrating that "plain throw => release" can exceed a physical bound when the
  factory allocated before throwing.

The configured physical bound is more important than preserving the previous default.

---

# B. IBM connect/close cleanup verdict must follow IBM's documented lifecycle - BLOCKING

The current production connection factory unconditionally calls `disconnect()` after a failed
`connect()`, and requires BOTH disconnect and destroy to return normally before it calls cleanup
proven.

That can turn an ordinary failed login/connect into a permanent quarantine even when no successful
connection existed or when `destroy()` completed the cleanup.

IBM CM 8.7 documents:

- `DKDatastoreICM.isConnected()` is true only when `connect()` was called and completed successfully;
  it does not validate current communication status.
- `DKDatastoreICM.destroy()` destroys the datastore object and "performs the datastore cleanup if
  needed".
- the normal successful lifecycle is connect -> disconnect -> destroy.

Authoritative references:
- https://www.ibm.com/docs/en/content-manager/8.7.0?topic=comibmmmsdkserver-dkdatastoreicm
- https://www.ibm.com/docs/en/content-manager/8.7.0?topic=servers-establishing-connection

## Required failed-connect cleanup

After `connect()` throws:

1. determine connectedness with `isConnected()` outside any pool lock;
2. when it says false, do not call disconnect just to manufacture a second error;
3. when true, attempt disconnect;
4. when connectedness itself cannot be read, take the conservative path and attempt disconnect;
5. ALWAYS attempt destroy;
6. use IBM's documented destroy-cleanup semantics in the verdict.

A successful `destroy()` is cleanup proof even if an earlier disconnect attempt reported an error;
the disconnect problem should remain in sanitized diagnostics, but it must not create a permanent
physical-capacity quarantine after destroy proved cleanup.

If destroy does not return normally, cleanup remains UNPROVEN and the slot is quarantined.

Apply the same evidence rule to normal `IbmCmSession.close()`.

## Required tests

- failed connect + isConnected false + destroy success -> PROVEN_CLEAN, disconnect not called;
- failed connect + isConnected true + disconnect success + destroy success -> PROVEN_CLEAN;
- failed connect + disconnect fails + destroy succeeds -> PROVEN_CLEAN with sanitized diagnostic;
- connectedness query fails + destroy succeeds -> PROVEN_CLEAN after best-effort disconnect;
- destroy fails -> UNPROVEN/quarantine regardless of disconnect result;
- normal live session close follows the same final-destroy proof rule;
- every cleanup step that should run is still attempted.

Re-run the real-SDK unreachable/server-unavailable smoke if possible. Report what actually happens.
If destroy genuinely fails, quarantine is still correct. Do not force a clean outcome to make the test
look nicer.

---

# C. Adapter installed is not the same as IBM runtime usable - HIGH / ACCEPTANCE

A core-only build currently packages the IBM adapter classes and ServiceLoader descriptor even when
the vendor SDK JARs are absent. The registry therefore reports `AVAILABLE` because it found the provider,
while `IbmCmAdapterProvider.sessionFactory()` later discovers that
`DKDatastoreICM` is unavailable.

This makes operator/API state misleading:

- `/api/repositories` can report `available=true`;
- repository selection then fails later;
- `--check-repository` can enter activation instead of immediately returning adapter-unavailable;
- auto-activation takes the activation-failure path instead of the explicit missing-runtime path.

## Required model

Keep two concepts distinct:

1. provider/adapter code installed;
2. provider runtime ready to activate a repository.

Extend the IBM-independent provider seam with a cheap, local, no-network readiness/capability result.

For the IBM provider:
- SDK classes/dependencies loadable -> runtime ready;
- SDK absent/linkage-incomplete -> runtime unavailable with a fixed sanitized reason;
- no connection attempt is made by readiness.

The registry/API may add an explicit provider-installed field, but its existing
`available/availability` activation verdict must be truthful: when IBM SDK runtime is absent, activation
is unavailable/refused before RepositoryManager is called.

Required tests:
- packaged application without IBM JARs and without test stubs at runtime reports provider installed
  but activation unavailable;
- no repository factory/session attempt occurs on select/check when runtime is unavailable;
- `--check-repository` returns the documented adapter-unavailable exit path;
- auto-activation fails explicitly as adapter/runtime unavailable;
- real SDK classpath reports activation ready;
- test-only stubs remain compile/test inputs and are never mistaken for a production SDK in the packaged
  runtime test.

---

# D. ClassificationRules must come from the actual runtime configuration - HIGH

Current `Main` correctly loads `ClassificationRules` from the selected AppConfig, including a custom
`--config <path>`.

But `IbmCmAdapterProvider` independently reloads the DEFAULT configuration path using
`AppPaths.resolve().configurationFile(null)`.

Therefore a runtime started with a non-default config can display business classifications that differ
from the rules printed/validated by Main.

This also violates the adapter-settings documentation that configuration is parsed once by core.

## Required fix

Load ClassificationRules once in the core and pass the immutable rule set through the provider settings
/context.

Recommended:
- add ClassificationRules to `CmAdapterSettings` or an equivalent IBM-independent activation settings
  object;
- Main constructs settings from the exact already-loaded `AppConfig` and `ClassificationRules`;
- adapter consumes the passed rules;
- remove AppConfig/AppPaths/config-file reading from `IbmCmAdapterProvider`.

The adapter must not guess which config file Main used.

Required tests:
- custom config path with a distinctive classification rule reaches `ItemTypeInfo.businessClassification`;
- default and custom config cannot silently diverge;
- adapter source no longer opens application config files;
- fallback label is used only when the core explicitly supplied a rule set whose fallback says so, not
  because adapter configuration discovery failed.

---

# E. backendUnusable must structurally retire the session before lease return - BLOCKING

The adapter has a useful `IbmCmFailure.backendUnusable` flag, but not every failure path applies it to
the actual session before try-with-resources returns the lease.

Examples found in review:

- `IbmCmApi.read/readOrAbsent` catch Exception but not Error;
- `IbmErrorSanitizer.backendUnusable()` currently treats an unexpected RuntimeException as reusable;
- `IbmCmApi.datastoreDef()` and `policyMgmt()` can create a backend-unusable `IbmCmFailure` after
  the wrapped SDK call without calling `session.markUnusable()`;
- `CmMetadataService.readRetentionPolicyName()` translates a failing SDK getter to an
  `IbmCmFailure` without marking the session unusable.

In these cases the operation can fail and the Lease can still return a session with `healthy=true`,
allowing it to be reused.

## Required invariant

> Every failure classified backend-unusable MUST mark the borrowed session unusable before the Lease is
> returned to BoundedPool.

Centralize this rather than relying on each service to remember it.

For vendor-call wrappers:
- DKNotExist/not-found is the explicit benign control and does NOT poison a session;
- every other SDK/vendor failure is conservative: unusable;
- unexpected RuntimeException from a vendor call is unusable;
- Error from a vendor call marks unusable, then the Error is rethrown;
- InterruptedException restores interrupt status and follows the documented conservative retirement rule.

Wrong vendor object/type (non-ICM datastore definition/admin) must also mark the current session unusable
before failure leaves.

Move `getItemTypeRetentionPolicyName()` through the same session-aware wrapper or explicitly mark before
throwing.

Add adversarial tests proving:
- DKNotExist -> session remains reusable;
- DKSystem/DKUsage/DKDatastoreAccess failure -> session is retired on lease return;
- unexpected RuntimeException -> session is retired;
- Error -> session is marked unusable before Error propagates and is not returned idle;
- wrong datastore definition/admin -> session is retired;
- retention-policy-name getter failure -> session is retired;
- a subsequent borrow never receives the poisoned session.

---

# F. Retention DTO/API must not claim unavailable numeric codes are exact - SHOULD FIX IN THIS PASS

The current `RetentionPolicyInfo` Javadoc says retention type/action numeric fields carry the exact
numeric value returned by the server, while the implementation deliberately uses `-1` because the real
SDK exposes enum constants and the numeric CM mapping was not established.

The implementation's refusal to guess is correct; the contract text/API shape is misleading.

Use an explicit representation for "numeric code unavailable/not mapped", for example:
- nullable/Optional numeric code in the core DTO and JSON `null`, or
- a separate availability flag with a clearly documented sentinel.

Do not expose `-1` under documentation that calls it an exact server code.

The enum CONSTANT identity itself is certain. It is acceptable, and preferable, for the readable field
to expose the IBM constant name such as `FIXED_TIME`, `MONTH`, `AUTO_DELETE` while separately stating
that no numeric CM code has been established.

Do not infer enum ordinal as a server code.

Update docs/tests/API examples consistently.

---

# G. Keep the accepted Goal 02 work intact

Do not regress:
- optional IBM source set and no proprietary JARs in Git;
- real-SDK compile mode;
- test-only stub compile;
- ServiceLoader isolation;
- no com.ibm types in core;
- read-only source/stub guards;
- no emergency/ad-hoc connection;
- partial activation cleanup context;
- Goal 01C retained-context latch;
- local non-blocking pool health flag;
- metadata/retention immutable DTO boundary;
- per-context metadata cache;
- POST repository selection action guard;
- authenticated routes and sanitized HTTP errors;
- production-path smoke command;
- Linux shell/process/security tests.

The working ItemType listing based on the real SDK's `listEntityNames(DK_ICM_USER_ITEM_TYPES)` /
`retrieveEntity(name)` shape is accepted. Do not revert it to the earlier draft signature that did not
match the real SDK.

---

# H. Validation

Run at minimum:

- `./build.sh`
- `./tests/selftest.sh`
- `./tests/shell/run.sh`
- IBM test suite against committed stubs;
- `./build.sh --require-ibm` with the real CM 8.7 SDK if the same local SDK is still available;
- source/isolation guards.

Add mutation/control evidence for sections A and E.

When real SDK is present, verify the optional source set again with `-Xlint:all`.

If the server remains unreachable, do NOT claim a live CM smoke. A real-SDK failed connection attempt is
still useful lifecycle evidence, but label it accurately.

Both exact-SHA GitHub Actions events (push and pull_request) must be green.

---

# I. Status and handoff

Update STATUS.md with:

- exact work commit(s);
- generic create-failure default after this correction;
- IBM cleanup proof rule and IBM documentation basis;
- provider-installed vs runtime-ready behavior;
- classification single-source behavior;
- backend-unusable session retirement tests;
- retention numeric-code representation;
- core/stub/real-SDK test results;
- live CM status honestly;
- Actions run ids;
- unresolved risks.

Set the next goal to:

**NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

Do not execute Goal 03.

At completion:
1. review the full diff;
2. commit coherently;
3. push `origin/bootstrap/cm-insight-architecture` yourself;
4. verify local HEAD == remote HEAD;
5. verify push and pull_request Actions for the SAME final SHA are green;
6. do not merge PR #1;
7. stop with the architecture-review handoff.
