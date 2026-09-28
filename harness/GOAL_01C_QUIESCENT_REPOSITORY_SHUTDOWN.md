# Goal 01C - Quiescent repository shutdown before switching

**Status: APPROVED / EXECUTE**

This is a narrow final core-lifecycle correction after Goal 01B. It is NOT Goal 02.

Do not add IBM CM SDK integration, JDBC analytics, retention business logic, UI features or new
external dependencies.

## Why this goal exists

Goal 01B correctly added `CloseOutcomeAware` so a quarantine that exists when an owned resource's
`close()` returns can propagate through RepositoryContext to RepositoryManager.

The external review found one remaining core lifecycle gap (Goal 01B review finding F3), and it is
broader than a late quarantine.

Current sequence:

1. RepositoryContext owns a BoundedPool.
2. A caller still holds a Lease when RepositoryManager switches repository.
3. RepositoryContext.close() calls BoundedPool.close().
4. BoundedPool marks itself closed and drains idle resources, but a leased resource remains physically
   live; BoundedPool.close() returns without waiting for that lease.
5. At that instant quarantinedCount may still be 0, so closedWithUncertainResources() reports false.
6. RepositoryContext snapshots a clean result.
7. RepositoryManager is allowed to create the next RepositoryContext while the old physical resource
   is still alive.
8. The old lease may return later and either close cleanly or become quarantined.

Even the clean late return is already too late: the new repository may have opened physical
connections while an old repository connection was still alive.

This violates the architecture rule:

> No new repository context may be created until the previous repository's owned connection resources
> have reached a terminal shutdown state.

## A. Define terminal close state - BLOCKING

Extend the vendor-neutral close lifecycle so the repository layer can distinguish at least:

- close initiated;
- close still in progress / physical resources still outstanding;
- close terminal and clean;
- close terminal but uncertain.

Do not encode this as IBM- or JDBC-specific logic.

You may extend `CloseOutcomeAware`, add a sibling interface/value object, or use an equivalent design,
but the semantics must be explicit and testable.

For BoundedPool:

- after close() begins, no new lease may be issued (existing invariant);
- `leased > 0`, `creating > 0` or `retiring > 0` means shutdown is NOT terminal yet;
- `quarantined > 0` means terminal uncertainty for those slots and must never be described as clean;
- shutdown is terminal-clean only when no leased/creating/retiring/quarantined slot remains;
- a resource that is still physically outstanding must never be represented as "closed cleanly".

Do not redefine "quarantine" to mean merely "still leased". Pending and uncertain are different states.

## B. RepositoryContext must not freeze a stale clean snapshot - BLOCKING

RepositoryContext currently snapshots its close outcome at the end of its first close() call. That is
insufficient for owned resources whose close continues asynchronously through later lease returns.

Required behavior:

- once context close is initiated, its close status must remain able to reflect later changes from
  owned close-aware resources;
- if an owned pool is still draining, the context must report non-terminal/pending rather than clean;
- if a later lease return becomes quarantined, the context must report terminal uncertainty;
- if all late leases return and close cleanly, the context may eventually report terminal-clean;
- diagnostics remain value-free.

Do not silently convert a pending close into a permanent clean result.

## C. RepositoryManager switch must be fail-closed across retries - BLOCKING

The manager must not create the next RepositoryContext while the previous one is pending or uncertain.

This must hold not only for the first failed switch but for every retry.

Required invariant:

> A failed/pending previous-context shutdown cannot be forgotten merely because the active context was
> unpublished.

Design the lifecycle so the manager retains enough state to re-check or remain latched against the
previous closing context.

Acceptable behavior:

- if the old context later reaches terminal-clean, a later explicit switch attempt may proceed;
- if it reaches terminal-uncertain (for example quarantine), switch remains refused;
- if it is still pending, switch remains refused or may wait only within an explicitly bounded policy;
- process shutdown stays best-effort.

Do NOT allow:
- first switch refuses because a lease is outstanding;
- manager drops all reference to the old context;
- second switch creates a new context while the old lease is still live.

Do not add an automatic "emergency reset" or force-open path.

## D. Bounded waiting is optional; safety is mandatory

A short bounded drain wait is allowed if it improves normal repository switching, but it is not
required.

If implemented:

- timeout must be explicit/configurable or clearly documented;
- interruption must be handled correctly;
- timeout means the switch is refused, not that the old resource is assumed gone;
- no unbounded wait.

A non-blocking design that reports "still draining; retry later" is equally acceptable if retries are
safe as specified in section C.

## E. Deterministic regression tests - REQUIRED

Add committed Java tests covering at minimum:

### E1. Outstanding lease blocks switch

1. create a real BoundedPool<FakeResource>;
2. initialize it;
3. borrow and hold one Lease;
4. put the pool inside RepositoryContext A;
5. activate A through RepositoryManager;
6. request switch to B;
7. prove B's factory was NOT called;
8. prove the old fake resource is still physically live;
9. prove manager/context diagnostics say shutdown is pending/not terminal rather than clean.

### E2. Late clean return permits only a later safe switch

Continue from the held-lease scenario:

1. return the lease;
2. fake close succeeds;
3. old pool reaches terminal-clean;
4. a later explicit switch attempt may proceed;
5. prove the old physical live count is zero BEFORE B factory creation.

Record event/order evidence, not only final counts.

### E3. Late failed return remains blocked

1. hold a lease during first switch;
2. first switch is refused;
3. configure the leased fake so its close fails uncertainly;
4. return the lease;
5. pool becomes quarantined;
6. later switch is still refused;
7. B factory invocation count remains zero;
8. diagnostics mention the terminal uncertainty/quarantine.

### E4. In-flight creation/retirement

Cover the same rule for a creation or retirement that is still in progress when context close begins.
Use deterministic latches/barriers, not sleeps.

At least one test must prove that a retry cannot bypass a still-pending old context.

### E5. Clean ordinary context remains unchanged

A context with no outstanding resources still closes and switches normally.

## F. Preserve Goal 01B acceptance

Do not regress:

- both exact-SHA push and pull_request Actions green;
- Linux startup provisional/positive process identity logic;
- /proc starttime corroboration;
- IPv4-mapped IPv6 socket ownership normalization;
- exact health marker;
- unrelated JVM/process never signalled;
- non-loopback HTTP exposure policy;
- secret/path behavior;
- hard-bounded pool and quarantine accounting;
- committed shell regression suite.

Run the entire existing test suite and shell suite.

## G. CI gate

On the final work/head SHA:

- ./build.sh passes;
- ./tests/selftest.sh passes;
- committed shell tests pass;
- both push and pull_request GitHub Actions runs for the same final SHA pass.

Do not call CI green unless both exact-SHA events are green.

No IBM/DB live validation is expected in this goal.

## H. STATUS/checkpoint

Update STATUS.md using the existing non-self-referential protocol.

Record:

- implementation/work commit(s);
- the precise close-state semantics implemented;
- retry behavior after pending clean completion and after quarantine;
- deterministic tests and results;
- Actions run IDs/results;
- any remaining core lifecycle risk;
- IBM/DB live tests explicitly not run;
- next goal = NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED.

## Push and stop - MANDATORY

1. Review the complete diff.
2. Commit coherently.
3. Push to `origin/bootstrap/cm-insight-architecture` yourself.
4. Verify local HEAD == remote branch HEAD.
5. Verify both exact-SHA Actions events are successful.
6. Do not merge PR #1.
7. Do not execute Goal 02.
8. Stop with a concise handoff report.

This goal is complete only when a repository switch cannot overlap old live physical connection
resources, including across failed-switch retries.
