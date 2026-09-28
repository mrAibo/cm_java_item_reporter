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

### Application home and operational paths

Every relative operational path (`profiles.dir`, `classifications.file`, `secrets.dir`, `data.dir`,
`reports.dir`, `logs.dir`, the default configuration file and the `run/` and `logs/` directories used by
the lifecycle scripts) resolves against **one application home**, not against whatever directory the
launcher happened to be invoked from. The home is resolved in this order:

1. `-Dcminsight.home=<dir>` — set automatically by `bin/cm-insight`, so the normal path is fully
   position-independent and a systemd unit or any other launcher works from any working directory;
2. `CM_INSIGHT_HOME=<dir>` — for launchers that do not use `bin/cm-insight`;
3. the current working directory — a documented fallback, printed as such.

An **absolute** configured path bypasses the home entirely. A home that is supplied but is not an
existing directory is refused with a diagnostic naming the home and its source, rather than silently
resolving against the wrong place. `--print-config` and `bin/doctor.sh` both report which home is in
use, and `CM_INSIGHT_RUN_DIR` / `CM_INSIGHT_LOG_DIR` remain explicit overrides for the lifecycle
directories.

### Lifecycle safety

`bin/start.sh` publishes the PID file only after the instance is confirmed healthy, so a failed start
never destroys existing tracking. An instance whose PID file was lost is reported as
`Status: UNTRACKED INSTANCE` (exit 1) and can be recovered with `bin/stop.sh --untracked`. A process is
**never** signalled merely because it is a JVM: stopping requires positive CM Insight ownership evidence
(the exact `{"status":"UP","service":"cm-insight"}` marker tied to the listening socket, or a command
line that explicitly identifies CM Insight), and if ownership cannot be proven the scripts refuse and
print manual instructions instead. Listener lookup is port-scoped (`netstat`/`ss` for the configured
address) and never a process-name scan.

Default development binding: `127.0.0.1:8080`.

Loopback over plain HTTP is the normal, supported configuration. A **non-loopback** bind over plain
HTTP is refused, because HTTP Basic sends reusable credentials without transport encryption; it
requires the explicit insecure opt-in `web.allowInsecureHttp=true` (absent and `false` by default),
which prints a `SECURITY WARNING` at startup. Any part of the credential that is the built-in
development default stays refused beyond loopback even with the override: the `admin`/`admin` pair, a
password set to `admin`, and also a **blank** `web.auth.password` or `web.auth.user`, which is
treated as unset and silently falls back to the published development value. For production remote
access, terminate HTTPS in front of the application with a reverse proxy or an equivalent secure
tunnel — there is no embedded TLS listener yet. See [SECURITY.md](SECURITY.md) for the reverse-proxy
throttle limitation (`X-Forwarded-For` is not trusted).

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
- `web.allowInsecureHttp` — `true` permits a non-loopback plain-HTTP bind (default `false`; see the binding notes above)
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
   is group- or other-readable (POSIX filesystems only); the directory is enforced by **real path**, so
   a symbolic link, Windows junction or other reparse point inside it that resolves outside it is
   refused instead of followed;
3. `<key>` holds the value inline, which is discouraged and warned about.

If you configure any of those, that source must resolve. The application fails closed rather than
downgrading to a weaker credential, and the diagnostic names the field and the exact source that
failed. The built-in `admin`/`admin` development default applies only when nothing at all is
configured, and even then only on a loopback bind; `conf/application.properties.example` therefore
ships the password indirection commented out.

Repository profiles in `conf/profiles/*.properties` never contain a credential. Each of the four
credentials of a repository — CM user, CM password, JDBC user, JDBC password — declares *where* its
value comes from, using the same two shapes as every other secret in the application:

- `repository.cm.user.env` (and the CM password / JDBC user / JDBC password equivalents) names an
  environment variable;
- `repository.cm.user.file` names a file below `secrets.dir`, read at resolution time.

**Precedence:** when both shapes are configured for one credential, the `.env` source wins and is
authoritative — a configured-but-unset environment variable fails closed and is never silently
satisfied from the `.file` source. The loader records a diagnostic naming the ignored `.file` key, so
the shadowed source is visible rather than lost. This is the same order the web credentials use.

Inline values are not part of the model: `repository.cm.user=<value>`, `repository.cm.user.password`
and every other inline credential key are rejected while loading, with a message naming the profile
file and the `.env`/`.file` key to use instead, and the value is never echoed. A profile is also
rejected if its `repository.jdbc.url` embeds a credential in any of the driver-specific shapes
(`//user:password@host`, the Oracle thin `:user/password@host`, `;password=value`); `RepositoryProfile`
masks those when it is printed. A `*.file` reference must name a file below `secrets.dir`: absolute
paths, `~` and `..` traversal are refused while loading, and the read itself is confined by real path,
so a link or junction inside that directory which points outside it is refused as well.
`conf/profiles/*.properties.example` files are templates and are not loaded.

## Connection pools

`BoundedPool` is hard bounded. Every capacity slot is accounted for under one lock as idle, leased,
creating or retiring, so the pool can never overshoot the configured size, there is no emergency
connection path, and exhaustion produces backpressure via the borrow timeout. Refill is lazy: a
retired resource frees its slot and the next borrow creates the replacement, so there are no refill
worker threads to explode. Leases carry operation accounting, and age/operation/health rotation plus
metrics (average and maximum wait, lifecycle and reconnect counters) are built in.

Leaving a repository is equally strict: `RepositoryManager` closes the previous context before it
creates the next one, and when that close is uncertain — a resource refused to close, or `close()`
threw — the switch fails closed instead of opening new connections on top of resources that may still
be alive. The state becomes `FAILED`, the diagnostics and `lastFailure` name the resources that
refused, and no factory call is made. Shutdown (`close()`) stays best-effort: it releases everything it
can and reports what it could not.

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
