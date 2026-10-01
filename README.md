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

### Build modes

`build.sh` resolves the IBM CM SDK **once** and uses the result for the rest of the build. There are
two supported modes, and the difference matters enough to be stated plainly: a stub build proves the
adapter compiles, it does **not** validate it against IBM's runtime.

| | Core-only (the default) | IBM-enabled |
| --- | --- | --- |
| IBM SDK JARs | none required | present in `lib/ibm/` |
| `src/ibm/java` | compiled against `tests/ibm-stubs` | compiled against the real SDK |
| What it proves | the adapter typechecks | the adapter compile is checked against the real API |
| Test suite | runs, no IBM SDK needed | runs, plus the IBM suites when `src/ibm-test/java` is present |

In the core-only mode `build.sh` prints a `WARN` saying the IBM source set was compiled against
signature stubs and that this is a compile check, not SDK validation. That line is deliberate: a build
that silently compiled against stubs and shipped would look identical to a validated one.

### The IBM source set and the compile stubs

```text
src/main/java        core runtime - IBM-JAR-free, compiled with the SDK absent by construction
src/ibm/java         the optional IBM source set (com.mraibo.cminsight.ibm.*)
src/ibm/resources    the ServiceLoader registration for the adapter provider
src/test/java        dependency-free core tests
src/ibm-test/java    IBM adapter tests (compiled against the stubs or the real SDK)
tests/ibm-stubs      TEST-ONLY compile stubs for com.ibm.* - never packaged, never shipped
```

`tests/ibm-stubs` is a signature-only mirror of the IBM CM 8.7 API, used so the IBM source set can be
compiled and typechecked on a machine that owns **no** proprietary JAR. It is never on the core class
path and never packaged: `build.sh` compiles the stubs into their own `build/ibm-stub-classes`
directory and uses them only for `src/ibm/java`. Their surface is pinned against
`tests/ibm-stubs/EXPECTED_SIGNATURES.txt`, and `build.sh` fails when they drift, so a stub cannot be
quietly widened into an API the real SDK does not have. Two consequences are worth knowing before you
rely on them:

- the stubs are deliberately **narrower** than the real SDK - they declare only the read-only surface,
  so a mutating call in `src/ibm/java` does not compile at all, and the source guard in
  `tests/shell/ibm_guard.sh` (asserted by `tests/shell/ibm_source_guard_test.sh`) stays the second line
  of defence rather than the only one;
- stub bodies throw `UnsupportedOperationException`, so a test that needs a live SDK object must run
  only when a real JAR is present, or drive the adapter through a fake.

### Placing the IBM JARs, strict mode and the isolation guard

Put the SDK JARs in `lib/ibm/`; `lib/README.md` lists the expected name. They are proprietary and are
never downloaded by this build and never committed (`.gitignore` excludes `*.jar`). The launcher adds
every `*.jar` it finds there to the class path, and `CM_INSIGHT_IBM_LIBS` names an additional
directory when the SDK lives outside the repository - a staging directory, a shared mount or a vendor
installation - so a one-off real-SDK check needs no repository change.

```bash
./build.sh --require-ibm          # fail unless a real IBM CM SDK is present in lib/ibm
./build.sh --check-ibm-isolation  # run only the source guards; no JDK toolchain needed
```

`--require-ibm` is for an operator who requires IBM support: it turns "silently compiled against
stubs" into a hard failure. `--check-ibm-isolation` runs the two Goal 02 source guards - no mutating
IBM CM call under `src/ibm/java`, and no `com.ibm.` reference under `src/main/java` - and exits `0`
when both hold, `2` when one is violated. `build.sh` runs the same guards on every build, so they
cannot be skipped by forgetting a test.

### Smoke command

```bash
./bin/cm-insight --check-repository <repository-id>
```

This walks the **production** path - adapter provider, repository manager, context factory, context,
bounded CM session pool - activates one repository, reports the CM API release, the ItemType count and
the retention policy count, then closes through the repository manager and fails unless that shutdown
is terminal-clean. It never opens a one-off SDK connection: what it proves is that the real wiring
works. It needs an active adapter, so use `bin/cm-insight` (which puts `lib/ibm/*.jar` on the class
path) rather than a bare `java -jar`. Exit codes: `0` clean, `1` activation or shutdown failure
(including a shutdown that is not terminal-clean), `2` usage, `3` configuration error, `4` no usable
adapter or a read it cannot answer. Add `CM_INSIGHT_REQUIRE_IBM=true` to refuse a launch with no SDK
JAR on the class path, so a core-only run cannot be mistaken for a validated one.

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

The Goal 02 repository/itemtype/retention/diagnostics routes are implemented **and registered**, in
addition to the table above: `Main.serve()` calls `WebServer.installCmApiRoutes(...)` before the socket
is opened, so every one of them is served and every one is authenticated. Registration happens even
when no IBM adapter is installed, because "no adapter" must answer with the documented
`503 adapter_unavailable` rather than a `404` that reads like a path typo.

`POST /api/repositories/select` mutates local runtime state and is therefore guarded by a request
header a cross-site HTML form cannot set: a missing or wrong `X-CM-Insight-Action` value is refused
with `403 action_forbidden` before any state changes. See [SECURITY.md](SECURITY.md) for the full route
list.

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
- `profiles.dir`, `secrets.dir`, `data.dir`, `reports.dir` (`logs.dir` remains reserved for a later logging owner)
- `jdbc.pool.*`, `statistics.*`, `cache.statistics.ttl.seconds`
- `feature.history`, `history.max.snapshots.per.repository`
- `repository.auto.activate` to optionally activate one configured repository at startup

### CM adapter, pool and metadata cache

These keys are read by `CmAdapterSettings.from(...)`, the single reader, which range-checks each value
and fails closed on an out-of-range one instead of clamping it. A bad value is a configuration error
(exit `3`) on every path, including `--print-config` and the doctor.

| Key | Default | Range | Meaning |
| --- | --- | --- | --- |
| `cm.pool.size` | `4` | `1..64` | hard bound of the per-repository CM session pool |
| `cm.pool.borrow.timeout.ms` | `5000` | `1..600000` | how long a borrow waits before it times out |
| `cm.pool.max.age.minutes` | `30` | `1..1440` | a session is retired when it is returned after this age |
| `cm.pool.max.operations` | `1000` | `1..1000000` | a session is retired after this many uses |
| `cache.metadata.ttl.seconds` | `600` | `0..86400` | how long a metadata snapshot stays fresh; `0` disables caching |

`repository.auto.activate=<id>` activates one configured repository at startup. It **fails closed**:
if the key names a repository that is not configured the runtime refuses with exit `3`, and if it is
set while no adapter is available - absent, ambiguous or unloadable - startup refuses with exit `4`
rather than publishing an empty placeholder context. Without the key, repository profiles are still
listed and nothing is activated. `bin/doctor.sh` reports the same verdict as a `WARN` when nothing is
configured to activate and an `ERROR` as soon as this key names a repository.

### Analytics freshness, history and reports

Goal 03 activates the bounded `jdbc.pool.*` / `statistics.*` settings. Goal 04 adds
`cache.statistics.ttl.seconds` (default `300`, range `0..86400`) as a freshness judgement only: a stale
completed snapshot remains visible and a GET never triggers a refresh. `0` means any non-zero age is stale.

Persistent history is optional and application-local. `feature.history` defaults to `true` and
`history.max.snapshots.per.repository` defaults to `1000` (range `1..100000`). History data is kept below
`data.dir`; a missing local H2 driver makes history explicitly unavailable without failing repository
activation or live statistics. H2 remains an optional runtime JAR under `lib/app` and is not a compile-time
dependency. The history package is structurally restricted to its fixed local H2 path and may not depend on
the repository JDBC/session/credential path. Generated HTML/CSV/XLSX artifacts are confined below
`reports.dir`; downloads are opened by the report service with `NOFOLLOW_LINKS` and are read from that same
opened handle rather than handing a checked path to the HTTP layer. Both paths use the same application-home
resolution rule described above. `logs.dir` remains reserved until a Java logging owner actually consumes it.

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
