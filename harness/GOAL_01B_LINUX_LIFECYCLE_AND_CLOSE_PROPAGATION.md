# Goal 01B - Close-state propagation and Linux lifecycle reliability

**Status: APPROVED / EXECUTE**

This is the second and intentionally narrow correction gate after Goal 01A. It is NOT Goal 02.
Do not add IBM CM SDK integration, JDBC analytics, retention business logic, or new product features.

## Start condition

1. Work on `bootstrap/cm-insight-architecture`.
2. `git fetch origin` and fast-forward to the current remote review checkpoint.
3. Confirm the starting remote SHA is at least the review checkpoint recorded in STATUS.md.
4. Read STATUS.md, ARCHITECTURE.md, SECURITY.md, harness/MASTER_GOAL.md and this file completely.
5. Keep all accepted Goal 01A behavior unless this goal explicitly changes it.

Use subagents when available for:
- pool/repository lifecycle integration review;
- Linux/Bash process and socket semantics;
- CI reliability and committed regression tests;
- independent final review.

The lead agent integrates everything.

## Review evidence

Goal 01A materially improved the design, but the external review found three remaining integration defects.

### Evidence 1 - pool quarantine is not generically visible to RepositoryContext

`BoundedPool` correctly quarantines a slot when `resource.close()` throws an Exception and does not
free that slot. However, `BoundedPool.close()` may then return normally. `RepositoryContext` owns
resources only as `AutoCloseable` and currently marks an uncertain shutdown only when
`resource.close()` itself throws.

Therefore this sequence is possible in the generic core:

1. RepositoryContext owns a BoundedPool.
2. A pooled physical resource fails close and is quarantined.
3. BoundedPool.close() returns normally with metrics().quarantined() > 0.
4. RepositoryContext sees no exception and reports a clean close.
5. RepositoryManager is allowed to create the next RepositoryContext.

That defeats the fail-closed switch rule before the real CM/JDBC adapters even exist.

### Evidence 2 - exact current SHA still fails Linux startup intermittently

On current reviewed SHA `9c7168aa238633a004850ba658d546dd3177be52`:

- pull_request Actions run `36443456621`: SUCCESS
- push Actions run `36443449216`: FAILURE
- failed step: `Start`

The failed job log is available and shows:

```text
ERROR: PID 3451 was reused by a different process before the health check succeeded
```

Immediately afterwards the diagnostic shows PID 3451 is actually:

```text
java ... -cp .../build/cm-insight.jar
com.mraibo.cminsight.app.Main
--config .../conf/application.properties
```

The JVM was alive and still starting. The log was initially 0 bytes; fractions of a second later the
application wrote its normal startup warnings and served health.

Thus the current Linux startup path can classify the real just-launched CM Insight process as a
different/recycled process before exec/startup has stabilized.

### Evidence 3 - Linux socket address is misclassified

The same failed run later showed a live CM Insight health marker, but `stop.sh` refused ownership
because `ss` represented the loopback listener as:

```text
::ffff:127.0.0.1:8080
```

The current address classifier called that socket `foreign` for configured `web.bind=127.0.0.1`.

Important distinction:

- the socket table may use an IPv4-mapped IPv6 representation for a socket that serves 127.0.0.1;
- this DOES NOT mean `SecurityPolicy.isLoopbackLiteral("::ffff:127.0.0.1")` should be relaxed for
  user configuration.

Socket-equivalence normalization and web-exposure security classification are separate concerns.

## A. Generic uncertain-close propagation - BLOCKING

Do not make Goal 02 adapters remember to manually inspect `metrics().quarantined()`.

Create a generic core contract that lets a resource report the outcome of its close without leaking
pool-specific implementation into RepositoryContext. Possible shapes include a small
`ManagedResource`, `CloseStatusProvider`, or equivalent interface.

Required behavior:

- `RepositoryContext` can own an ordinary `AutoCloseable` unchanged.
- A close-aware resource can additionally report whether shutdown completed with uncertain physical
  resources.
- `BoundedPool` implements/adapts to that contract.
- quarantine after an Exception close is reported as uncertain to RepositoryContext even when
  `BoundedPool.close()` itself returned normally.
- close failures remain diagnostically visible without exposing resource secrets.
- RepositoryManager refuses a switch before calling the new-context factory.
- the generic contract does not depend on IBM SDK or JDBC classes.

Required deterministic regression test:

1. RepositoryContext owns a real `BoundedPool<FakeResource>`.
2. One pooled fake throws from close BEFORE proving itself closed.
3. BoundedPool quarantines the slot.
4. RepositoryContext reports uncertain close.
5. RepositoryManager switch refuses.
6. factory invocation count proves the new RepositoryContext was never created.
7. pool metrics still show the quarantined physical slot.

Also test the clean-close path so this mechanism does not turn every normal pool shutdown into FAILED.

## B. Linux startup identity race - BLOCKING

Fix the real failure demonstrated by Actions run `36443449216`.

Do not hide it with an arbitrary large sleep.

The lifecycle model must account for the launch transition:

- a newly backgrounded launcher/process may briefly be in a state where its command line is not yet a
  stable final JVM identity;
- a single transient `ci_proc_identity == different` immediately after launch is not sufficient proof
  of PID reuse;
- later, once identity/health/socket evidence stabilizes, a truly recycled/foreign PID still must be
  rejected.

Design an explicit startup identity state/grace rule with bounded time and clear evidence. Examples:
- treat identity as provisional until the process has had a short bounded launch grace period or until
  one positive CM Insight identity observation occurs;
- combine process existence, exact health marker and port ownership instead of one early negative
  identity sample;
- after positive identity is established, a later contradictory identity can be treated as recycle.

Do not weaken stop-time ownership checks merely to make startup pass.

### Committed Linux regression

Add a committed test or script under `tests/` that can run on ubuntu-latest and repeatedly exercises
real launch/start/status/stop. It must not live only under ignored `.tools`.

Use a bounded soak, for example 10 start/status/stop cycles on an ephemeral or otherwise isolated
port, with cleanup in every iteration.

The test should fail against the pre-fix behavior when practical and must be part of CI.

## C. IPv4-mapped IPv6 socket representation - BLOCKING

Teach the shell socket/listener layer to recognize IPv4-mapped IPv6 representations such as:

- `::ffff:127.0.0.1`
- bracketed forms returned by tools;
- equivalent fully expanded mapped forms if the parser can encounter them.

For listener ownership purposes only, normalize/classify the mapped address as serving the underlying
IPv4 address.

Required tests:
- configured 127.0.0.1 + socket ::ffff:127.0.0.1 => exact/equivalent ownership, not foreign;
- configured 127.0.0.5 + mapped ::ffff:127.0.0.5 => equivalent;
- mapped 192.0.2.10 must not become 127.0.0.1;
- unrelated mapped IPv4 address remains foreign;
- wildcard semantics remain correct;
- the Java SecurityPolicy input rule remains unchanged: a configured mapped spelling must NOT silently
  gain loopback privileges merely because the socket parser understands it.

Put these tests in the committed suite.

## D. Ownership safety remains non-negotiable

All Goal 01A H guarantees remain:

- never signal a process just because it is a JVM;
- exact health marker only;
- structural process identity only;
- foreign/recycled PID never signalled;
- an unprovable target is refused;
- no permissive emergency/force shortcut.

After fixing mapped socket classification, prove that the exact marker and socket owner can be tied to
the same CM Insight process on Linux.

## E. CI must become a real gate

The current SHA has one success and one failure for the same tree. That is not a reliable gate.

After the fix:

1. push the final work;
2. inspect GitHub Actions for that exact SHA;
3. require both the push and pull_request runs for that exact SHA to complete successfully;
4. re-run failed jobs if useful to test reliability;
5. preferably make the CI lifecycle step execute several bounded start/status/stop cycles so an
   intermittent startup race is much harder to hide;
6. record run IDs and conclusions in STATUS.md.

Do not call CI green because one event passed while the other event failed.

You now have GitHub job-log access through the connected repository tooling in the architecture-review
environment, so leave useful diagnostics in CI but do not assume logs are inaccessible.

Updating `actions/setup-java` from v4 to v5 is allowed if it is a straightforward maintenance change,
but do not let dependency-action cleanup distract from the actual lifecycle defects.

## F. Commit the shell regressions

Goal 01A correctly noted that important shell behavior was verified mostly by gitignored harnesses.
For the behaviors touched by this goal, add lightweight committed tests under a stable path such as:

```text
tests/shell/
```

At minimum pin:
- exact health marker behavior;
- structural argv identity;
- IPv4-mapped IPv6 listener classification;
- unrelated JVM/process is never an ownership proof;
- repeated Linux lifecycle start/status/stop.

CI must execute them.

Do not migrate every historical ad-hoc harness into the repository; keep this focused.

## G. STATUS/checkpoint protocol - HIGH

The current STATUS.md is inaccurate: actual remote HEAD is
`9c7168aa238633a004850ba658d546dd3177be52`, while it records `ffc9ced7...` as the final local/remote
checkpoint.

Avoid trying to make a commit contain its own SHA; that is self-referential.

Use this protocol instead:

- STATUS.md records the **last completed implementation/work commit** that it describes.
- STATUS.md may state that the authoritative branch head must be obtained with `git rev-parse HEAD`
  / `git ls-remote`.
- The final handoff report records the exact local final HEAD and exact verified remote HEAD after the
  STATUS commit has been made and pushed.
- Do not write placeholders or text such as "and the commit that follows it" into a SHA field.

At completion, STATUS.md must accurately identify the implementation commit(s), test evidence, current
review state and the next-goal gate.

## H. Definition of done

Goal 01B is complete only if:

- generic pool quarantine propagates automatically to RepositoryContext/RepositoryManager;
- the repository-switch regression uses an actual BoundedPool fake scenario, not a hand-crafted
  AutoCloseable that simply throws;
- Linux startup no longer falsely declares the newly launched real CM Insight JVM recycled;
- IPv4-mapped listener representation is handled safely;
- ownership safety is not weakened;
- relevant shell regression tests are committed;
- `./build.sh` and `./tests/selftest.sh` pass;
- the committed shell tests pass;
- local lifecycle checks pass;
- both push and pull_request GitHub Actions runs on the same final SHA pass;
- no IBM/DB live validation is claimed;
- STATUS.md is updated truthfully;
- Goal 02 remains NOT APPROVED.

## Push and handoff - MANDATORY

1. Review the complete diff.
2. Commit coherently.
3. Push to `origin/bootstrap/cm-insight-architecture` yourself.
4. Verify `git rev-parse HEAD` equals `git ls-remote origin refs/heads/bootstrap/cm-insight-architecture`.
5. Verify the CI runs for that exact SHA as required above.
6. Update/push STATUS.md according to section G without attempting self-referential SHA recording.
7. Do not merge PR #1.
8. Do not execute Goal 02.
9. Finish with a concise report containing:
   - implementation/work commit(s);
   - final local HEAD;
   - verified remote HEAD;
   - push Actions run ID/result;
   - PR Actions run ID/result;
   - tests actually executed;
   - remaining risks.

Set the next goal state to:

**NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**
