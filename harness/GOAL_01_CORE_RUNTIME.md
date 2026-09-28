# Goal 01 - Production-grade core runtime

## Objective

Turn the bootstrap core into a robust offline application foundation without requiring IBM CM libraries for core tests.

Use subagents for at least: concurrency/pool review, security/config review, and test/reliability review when AgentTeams is available.

## Deliverables

### Configuration and profiles

- robust AppConfig validation
- RepositoryProfileLoader for conf/profiles/*.properties
- environment/secret indirection; no passwords stored in tracked profiles
- clear startup diagnostics for missing required settings
- configurable feature registry
- classification rule loader
- no external configuration framework

### Repository lifecycle

Implement RepositoryManager with a single active RepositoryContext.

Switching repository must:

1. prevent concurrent switch races;
2. stop/close old context resources;
3. only publish the new context after successful initialization;
4. fail closed and keep no half-initialized context.

Create explicit lifecycle state and diagnostics.

### Bounded pool hardening

Harden BoundedPool:

- strict maximum size
- never create emergency resources
- no capacity overshoot during refill races
- borrow timeout/backpressure
- automatic operation accounting through Lease
- health validation
- age/operation rotation hooks
- safe refill without worker-thread explosion
- graceful shutdown
- metrics including average/max wait and lifecycle counters
- deterministic concurrency tests with fake resources

Do not copy CM_Migrator's emergency connection path.

### Web/security foundation

- keep /api/health minimal and public
- protect all other routes
- credentials from environment/secret-capable config
- retain fail-closed rule for admin/admin on non-loopback
- add basic brute-force/rate-limit protection without external libraries
- no credential logging
- clean JSON errors
- route abstraction so feature controllers do not depend directly on HttpServer details

### Operational scripts

Review and harden:

- build.sh
- bin/compile.sh
- bin/cm-insight
- bin/start.sh
- bin/stop.sh
- bin/restart.sh
- bin/status.sh
- bin/doctor.sh
- bin/clean.sh

Scripts must work without Internet and report actionable failures.

### Tests

Add dependency-free tests/self-tests for:

- config/profile validation
- non-loopback credential guard
- pool capacity under concurrency
- borrow timeout
- refill after unhealthy resource
- close behavior
- RepositoryManager switch lifecycle
- auth failure/lockout behavior

## Acceptance

No IBM SDK is required for this goal's test suite. build.sh and tests must pass on JDK 17. Update STATUS.md and set next goal to Goal 02.
