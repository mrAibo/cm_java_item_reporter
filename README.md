# CM Insight

CM Insight is a fast, read-only-first, modular administration and analytics console for IBM Content Manager Enterprise Edition 8.7.

## Baseline

- single JVM / modular monolith
- Java 17 LTS / OpenJDK-compatible runtime
- no Maven, Gradle, Spring, containers or microservices
- no CDN / Internet dependency at runtime
- IBM CM Java API for metadata and retention
- bounded JDBC parallelism for high-volume statistics
- DB2 and Oracle support
- reusable hard-bounded connection pools
- read-only V1/V2
- retention administration reserved for a later, disabled-by-default module

CM_Item_Reporter, CM_Migrator and CM_retention are sources of proven ideas and reusable code, not architectural authorities for this repository.

## Build and run

```bash
cp conf/application.properties.example conf/application.properties
./build.sh            # compiles main + test sources and runs the test suite
./bin/doctor.sh       # environment and configuration diagnostics
./bin/start.sh        # starts the JVM and waits for /api/health
./bin/status.sh       # RUNNING / STOPPED / STALE PID FILE / UNREACHABLE, plus health
./bin/stop.sh         # graceful stop; --force to escalate
./bin/clean.sh        # removes build artifacts; --all also clears run/ and logs/
```

`build.sh` requires JDK 17+ and never downloads anything, so it works on a host with no Internet
access. It fails with an actionable message when the JDK is missing or older than 17, when the main
or test sources fail to compile, or when the test sources are absent — tests are never silently
skipped. Configuration problems are reported by `bin/doctor.sh`, which `bin/start.sh` runs as a
pre-flight check, and by the application itself at startup.

`tests/selftest.sh` runs the same suite on its own.

Default development binding: `127.0.0.1:8080`.

## Runtime surface

| Route | Auth | Body |
| --- | --- | --- |
| `GET /api/health` | none | exactly `{"status":"UP","service":"cm-insight"}` |
| `GET /api/info` | required | name, version, mode |
| `GET /` | required | offline landing page |
| `GET /web/*` | required | static assets served from the classpath |

Everything except `/api/health` is authenticated, including error responses, so an unauthenticated
caller cannot tell a protected path from a missing one. `/api/health` is the only path that may be
registered without authentication; the router rejects any other attempt.

## Configuration

Configuration is plain `conf/application.properties`. There is no external configuration framework.

Read by this build:

- `app.name`, `app.version`, `app.mode` — `app.version` and `app.mode` are what authenticated `GET /api/info` reports
- `web.bind`, `web.port` (`0` binds an ephemeral port), `web.threads`, `web.backlog`
- `web.auth.user`, `web.auth.password` and the `web.auth.*.env` / `web.auth.*.file` indirections
- `web.auth.maxFailures`, `web.auth.lockout`, `web.auth.maxTrackedKeys` (brute-force guard)
- `feature.<id>` switches (see `FeatureRegistry`; `feature.retention.admin` cannot be enabled)
- `classification.<name>.label` / `classification.<name>.regex` rules, optionally from `classifications.file`
- `profiles.dir`, `secrets.dir`
- `repository.auto.activate` to optionally activate one configured repository at startup

Reserved for later goals: `cm.pool.*`, `jdbc.pool.*`, `statistics.*`, `cache.*`, `data.dir`,
`reports.dir`, `logs.dir`. They are accepted and carried in the example so the configuration shape is
stable, but no Goal 01 code reads them yet.

Unknown keys are reported at startup instead of being ignored, matched against an exact key list so
that a typo such as `web.prt=8080` is caught rather than passing as part of the `web.` family.
`--print-config` prints the effective configuration with credentials redacted.

## Credentials

Secrets are never stored in tracked configuration. A key is resolved in this fixed order:

1. `<key>.env` names an environment variable;
2. `<key>.file` names a file below `secrets.dir` (default `conf/secrets`), read with a warning if it
   is group- or other-readable (POSIX filesystems only);
3. `<key>` holds the value inline, which is discouraged and warned about.

If you configure any of those, that source must resolve. The application fails closed rather than
downgrading to a weaker credential, and the diagnostic names the field and the exact source that
failed. The built-in `admin`/`admin` development default applies only when nothing at all is
configured, and even then only on a loopback bind; `conf/application.properties.example` therefore
ships the password indirection commented out.

Repository profiles in `conf/profiles/*.properties` name the environment variables that hold their
credentials. A profile is rejected if it contains something that looks like an inline password, or if
its `repository.jdbc.url` embeds a credential in any of the driver-specific shapes
(`//user:password@host`, the Oracle thin `:user/password@host`, `;password=value`); `RepositoryProfile`
masks those when it is printed. `conf/profiles/*.properties.example` files are templates and are not
loaded.

## Connection pools

`BoundedPool` is hard bounded. Every capacity slot is accounted for under one lock as idle, leased,
creating or retiring, so the pool can never overshoot the configured size, there is no emergency
connection path, and exhaustion produces backpressure via the borrow timeout. Refill is lazy: a
retired resource frees its slot and the next borrow creates the replacement, so there are no refill
worker threads to explode. Leases carry operation accounting, and age/operation/health rotation plus
metrics (average and maximum wait, lifecycle and reconnect counters) are built in.

## Tests

The test suite is dependency-free and runs on a bare JDK 17 with no IBM CM SDK, no database and no
external network — the end-to-end test binds `127.0.0.1` only. Scratch files are created under
`build/test-tmp`, so the suite needs no operating-system temporary directory and writes nothing
outside `build/`.

It covers configuration and profile validation, secret resolution, classification rules, the feature
registry, the non-loopback credential guard, pool capacity under real concurrency, borrow timeout,
refill after an unhealthy resource, close and rotation behaviour, `RepositoryManager` switch
lifecycle, authentication failure and lockout, router behaviour through the transport seam, and an
end-to-end check against a real socket.

Run it directly with:

```bash
./tests/selftest.sh          # or ./build.sh, which runs it as part of the build
```

`Main --self-test` runs a smaller artifact self-check that needs no test classpath, which is what
makes it usable on a target host.

## Read first

1. VISION.md
2. REQUIREMENTS.md
3. ARCHITECTURE.md
4. DATA_MODEL.md
5. SECURITY.md
6. IMPLEMENTATION_PLAN.md
7. DEEPSEEK_HARNESS_PLAN.md
8. STATUS.md
9. harness/MASTER_GOAL.md
