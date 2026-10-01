# Security

## Trust model

V1/V2 are read-only with respect to IBM CM and the Library Server. The application may only write its own local cache/history/report/run files.

## Web binding

Default binding is 127.0.0.1. Loopback over plain HTTP is the normal, supported configuration and is unchanged by the rule below.

A **non-loopback** bind over plain HTTP is refused. HTTP Basic sends reusable credentials with no transport encryption, so one captured request is enough to replay them. Two independent refusals apply beyond loopback:

- **any part** of the effective credential that is the built-in development default is refused even when the override below is set, because those values are public and documented. That is not only the `admin`/`admin` pair: a blank `web.auth.password` (or a password explicitly set to `admin`) is the published development password, and a blank `web.auth.user` is the published development user name. A blank value in the configuration file is treated as *unset* and silently falls back to the published default, so a configuration that looks as though it sets a password would otherwise accept `<any user>`/`admin` from any reachable host;
- any other credentials are refused unless the operator deliberately sets `web.allowInsecureHttp=true`. The key is absent and `false` by default: doing nothing keeps the interface loopback-only. A value that is neither `true` nor `false` is a configuration error, not a silent yes or no. This opt-in accepts plaintext transport risk only; it never unlocks a development credential.

`web.allowInsecureHttp=true` is an explicitly accepted insecure test/development exposure, never a hardening option. Every use prints a `SECURITY WARNING` at startup, and `bin/doctor.sh` surfaces the same warning, because everything on that socket is in cleartext.

### Remote access

Until CM Insight has first-class TLS, production remote access terminates HTTPS in front of the application: an HTTPS reverse proxy, or an equivalent secure tunnel (SSH port forward, VPN). The application itself speaks plain HTTP only — it has no TLS listener and cannot be configured to require one.

Known limitation when a reverse proxy is used: `LoginThrottle` keys its lockout on the TCP source address, so every client arriving through one proxy shares a single lockout bucket — from the application's point of view the proxy is one source address. `X-Forwarded-For` is deliberately **not** trusted: the header is client-controlled, so honouring it would let an attacker forge a source address and either bypass the lockout or lock out other users. For per-client throttling, rate limit at the proxy, or use a tunnel that gives each client a distinct source address.

## Credentials

Repository passwords are supplied through environment variables or a local secret file with restrictive permissions. Profile files contain references to secret names, not passwords. Each of the four repository credentials (CM user, CM password, JDBC user, JDBC password) is modelled as an explicit `SecretRef`: `.env` names an environment variable, `.file` names a file below `secrets.dir`. When both are configured the `.env` source wins and is authoritative — an unset environment variable fails closed and is never satisfied from the shadowed `.file` source, and the loader reports the ignored key. Inline values, including `repository.*.user=<value>`, are refused while loading rather than silently ignored.

Never log passwords, Authorization headers or JDBC credential strings.

## Dependencies

Do not commit proprietary IBM or Oracle JAR files to the public repository.

No runtime CDN or external font dependency.

## Future retention administration

Must remain disabled by default and requires a separate security review, CSRF protection, audit logging, dry-run/preview, explicit confirmation and post-operation verification before activation.

## Implemented controls (Goal 01)

Recorded here so the review can check the implementation against the intent above.

- **Loopback detection is offline.** `SecurityPolicy.isLoopbackLiteral` is a pure string test: no DNS, no socket, no `InetAddress`. Only `127.0.0.0/8` literals, `::1` and the name `localhost` qualify. `0.0.0.0`, `::`, `::ffff:127.0.0.1` and every host name are treated as non-loopback, so a name that fails to resolve can never unlock the relaxed path.
- **Fail closed.** Blank effective credentials refuse startup. Any part of the effective credential that is the built-in development default — the `admin`/`admin` pair, a password that fell back to or was set to the published `admin`, or a user name that fell back to the published `admin` — refuses any non-loopback bind, with or without `web.allowInsecureHttp=true`. The check runs both in `Main` before anything opens and again in `WebServer.start()` before a socket exists.
- **Partially defaulted credentials are visible, not silent.** `SecurityPolicy.usesDefaultPassword`, `usesDefaultUser` and `usesAnyDevelopmentDefault` name exactly which half of the credential is the published default. On loopback the development fallback still works, and the startup warning now names the offending field (`web.auth.password`/`web.auth.user` is blank or unset) instead of only reporting the pair. `Main --print-config` reports `default creds     : none | user only | password only | both`, so the summary can never read `false` directly beneath a credential line that says `[built-in development default]`.
- **Non-loopback plain HTTP is opt-in.** `SecurityPolicy.validateWebExposure` refuses every non-loopback bind unless `web.allowInsecureHttp=true` was set deliberately (`SecurityPolicy.KEY_ALLOW_INSECURE_HTTP`, default `false`). The refusal names the key and the safe alternatives instead of just failing. When the override is used, `WebServer` prints a `SECURITY WARNING` line whose text comes from `SecurityPolicy.insecureHttpWarning`, so `bin/doctor.sh` can surface the identical sentence; on a loopback bind the override is reported as unnecessary rather than silently ignored. No TLS subsystem is implied: the fail-closed opt-in is the whole control.
- **Secret indirection only, and fail closed.** Resolution order is `<key>.env`, then `<key>.file` under `secrets.dir`, then an inline value (warned about). If one of those is configured but produces no value, startup is REFUSED with a diagnostic naming the field and the source that failed; the application never silently downgrades to the development default. The built-in `admin`/`admin` default applies only when nothing at all is configured, and only on a loopback bind. Secret file paths are confined to `secrets.dir` **by real path, not lexically**: the reference must be relative and free of `..` (absolute, drive-qualified and `~`-relative names are refused while loading), and the file's resolved location — with every symbolic link, Windows junction and other reparse point on the whole path followed — must still be inside the resolved location of `secrets.dir`. A link or junction placed inside the directory and pointing outside it is refused with a warning and yields no value, so resolution fails closed instead of reading a secret from outside the directory. A hard link cannot be detected by this rule (it has no target to resolve; creating one requires write access to the secrets directory, which already grants access to every secret in it). A group- or other-readable secret file produces a warning on POSIX filesystems (on Windows the permission check is not performed, because there is no POSIX mode to inspect, so file permissions must be enforced by the hosting platform instead).
- **No credential can be printed.** `SecretRef` and `WebAuthSettings` override `toString()` so a password can never reach a log line, and `SecretRef` exposes the value only through a package-private accessor. Startup output and `--print-config` print `<redacted>` plus the source description.
- **Every route but `/api/health` is authenticated.** `Router` refuses to register any other public path — only the exact path `/api/health`, never its subtree and never a public prefix, so a future route cannot slip an unauthenticated surface under a health-looking name. It authenticates before producing 404 or 405 so there is no path-existence oracle, and renders every error as clean JSON with no stack trace and no exception message. Static assets are served from the classpath through a name allow-list that rejects `..`, sub-directories and unknown extensions.
- **Brute-force guard.** `LoginThrottle` locks a source address after a configurable number of consecutive failures and answers with `429` plus `Retry-After`. Its table is hard bounded by `web.auth.maxTrackedKeys` and pruned least-recently-used, so it cannot grow without limit. A locked key is answered without comparing the submitted credential.
- **Constant-time comparison.** The user and password are compared with `MessageDigest.isEqual`, and both comparisons always run. Decoded credential buffers are zeroed after use.
- **Response hardening.** Every response carries `Content-Type`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer` and a restrictive `Content-Security-Policy`. All assets are local.
- **Retention administration stays off.** `feature.retention.admin=true` is rejected outright rather than merely defaulted off.
- **Repository credentials are references, never values.** `RepositoryProfile` carries one `SecretRef` per credential (CM user, CM password, JDBC user, JDBC password): `.env` names an environment variable, `.file` names a file below `secrets.dir`, and no third shape exists. `resolveCredentials(SecretResolver)` resolves all four in one place and fails closed when a configured source yields nothing; it never returns a partial result. The `.env` source is authoritative over `.file`, the shadowed key is reported at load time, and an inline `repository.*.user=<value>` or password is rejected with the profile file named and the value never echoed. Secret-file references are refused while loading when they are absolute, `~`-relative or traversing, and `SecretResolver` re-checks containment against `secrets.dir` when it reads the file. `RepositoryProfile`, `SecretRef` and the resolved credentials all override `toString()` to print the source, not the value.

## Implemented controls (Goal 02)

Recorded here so the review can check the implementation against the intent above.

- **The read-only guarantee is structural, not a convention.** Two guards enforce it and both are committed. First, the compile stubs under `tests/ibm-stubs` declare **only** the read-only getters on `DKItemTypeDefICM`, `DKRetentionPolicyDefICM` and `DKPolicyMgmtICM`, so a mutating call such as `policy.add(...)` or `itemType.setName(...)` does not compile against `src/ibm/java` at all; the stub surface is pinned against `tests/ibm-stubs/EXPECTED_SIGNATURES.txt` and `build.sh` fails when it drifts, so the omission is audited rather than assumed. Second, `tests/shell/ibm_guard.sh` refuses the unambiguously IBM-specific mutating calls under `src/ibm/java` - `commit(`, `rollback(`, `checkIn(`, `checkOut(`, `backfill(`, `migrate(`, `moveObject(`, `changePassword(`, `makeActive(`, `makeInactive(`, `reorg(`, `recreate(`, `clearCache(`, `assign(`, `unassign(` - and native JDBC extraction through `connection()`, which is the reflection trick the reference migrator uses and this project bans. Names that are also ordinary JDK methods (`add`, `remove`, `update`, `set*`) are deliberately **not** text-matched: matching them by name alone also refuses the adapter's own `List`/`Map`/`AtomicBoolean` bookkeeping, and a guard that cannot pass is a guard that gets disabled. Those members are excluded one layer earlier by the stubs. Run it on its own with `./build.sh --check-ibm-isolation` (exit `0` both guards hold, `2` one is violated); `build.sh` runs it on every build.
- **SDK isolation of the core.** The same guard refuses any `com.ibm.` reference under `src/main/java` and any reference to the adapter's own `...ibm.internal` packages, with exactly one documented exception: a comment in `connection/CmSession.java`, carried as an allow-list of one path so a second allowance cannot appear silently. The enforcement is structural as well - `src/main/java` is compiled with no IBM JAR and no stub on its class path, so a core source that needs an SDK type does not compile.
- **Repository action guard.** Repository selection mutates local runtime state, so it is a `POST` that must carry a request header a cross-site HTML form cannot set: `X-CM-Insight-Action: repository-select` (`CmApiRoutes.ACTION_HEADER` / `SELECT_ACTION`). A missing or wrong value is refused with `403 action_forbidden` **before** any state is read or changed, and CORS stays disabled. Do not weaken this to a query parameter or a custom body field: the whole control is that a form-driven cross-site request cannot set the header.
- **Adapter diagnostics are value-free.** `IbmCmAdapterRegistry` exposes only availability (`ABSENT`, `AVAILABLE`, `AMBIGUOUS`, `UNAVAILABLE`), the provider id, the adapter version and the SDK release - each sanitised, length-bounded and stripped of control characters. A provider that cannot be loaded is reported as one fixed generic reason rather than a raw `ClassNotFoundException` stack trace, because the class name and the message are exactly what an operator page must not print; the same applies to a broken service configuration. `CmPoolDiagnostics` publishes counts, states, durations and versions, never a credential, never an SDK message verbatim, and never document or user content. The launcher reports how many SDK JARs it put on the class path - that is a statement about the class path, not a claim that the adapter is available, which the application determines through its own discovery.
- **IBM JARs are never downloaded and never committed.** The IBM CM 8.7 SDK ships as proprietary JARs; they are a local prerequisite in `lib/ibm/` or a directory named by `CM_INSIGHT_IBM_LIBS`, and `.gitignore` excludes `*.jar`. Nothing in this repository fetches them.
- **Retention administration stays off.** Unchanged from Goal 01 and reaffirmed for the new read path: `feature.retention.admin=true` is rejected outright rather than merely defaulted off, and the adapter is a **reader** of retention policies - it lists policies, retrieves one and lists the ItemTypes assigned to one. No mutating retention path exists in the source set at all.

### Route-registration status note

The Goal 02 repository/API routes are `/api/repositories` (GET), `/api/repositories/select` (POST, guarded by the action header above), `/api/repositories/status`, `/api/itemtypes`, `/api/itemtypes/{name}`, `/api/retention/policies`, `/api/retention/policies/{name}` and `/api/diagnostics/cm`. They are **registered and served**: `WebServer.installCmApiRoutes(...)` installs them on the same authenticated router, and `Main.serve()` calls it before the socket is opened, so no route can be missing from a served request.

Two properties of that wiring are deliberate and worth preserving:

- it is called **unconditionally**, not only when an IBM adapter is available, because "no adapter installed" must answer the repositories and diagnostics questions with the documented `503 adapter_unavailable` rather than a `404` that reads like a path typo;
- it registers through `Router`, which refuses any unauthenticated registration except the exact path `/api/health`, so "every route but health is authenticated" stays a property of the router rather than a convention each new route has to honour.

This note previously stated the opposite - that the routes were implemented but not yet registered - which was true of the working tree at the time it was written and became false when the wiring landed. It is corrected here rather than deleted, because a documented security surface that overstates OR understates what is served is equally misleading.

## Implemented controls (Goal 03 and Goal 04)

Same rule as above: recorded so the review can check the implementation against the intent.

### The analytics read-only guarantee, and its one exemption

Goal 03 added a second scanned tree and a genuinely new boundary. `tests/shell/analytics_guard.sh` (run by `build.sh` on every build, before the toolchain is even resolved) refuses, under `src/main/java/com/mraibo/cminsight/db` **and** `.../statistics`, every `executeUpdate`/`addBatch`/`commit`/`rollback`/`prepareCall`-class call and every string literal carrying a write or control SQL keyword as a whole token. It also refuses any JDBC call that is not a plain `execute(`.

Goal 04 persists application-local history, which necessarily issues `CREATE`, `INSERT`, `DELETE` and `commit` and names `java.sql.Connection`. That store must not be able to reach IBM CM or the repository database, but it must also not require weakening the rule for everything else. So `src/main/java/com/mraibo/cminsight/history` is a **named exempt tree** from the repository-SQL write rules, never a loosened pattern over all of `src/main/java` - widening the rule for every future file in order to accommodate one package is how a guard becomes decoration.

Goal 04A closes the negative half structurally in both the shell guard and its Java twin: code under `history/` may keep the fixed `org.h2.Driver` / `jdbc:h2:file:` local-store path, but `DriverManager`, the repository `com.mraibo.cminsight.db` path, `RepositoryProfile` credential resolution, DB2/Oracle JDBC URLs and DB2/Oracle driver identities are refused there. Planted repository-JDBC references inside `history/` must make the guard fail, while the committed H2 implementation must pass. A separate control still proves a driver-handle type outside the two intended JDBC-owning trees is refused, so the exception cannot silently widen in either direction.

### Report output is untrusted-data-safe in both directions

A generated report carries names, classifications and retention-policy names that originate in IBM CM, so a report is an injection surface as much as the UI is. Three independent defences, each with a mutation control that makes the corresponding test fail when it is removed:

- **HTML** escapes every dynamic value (`& < > " '`, control characters and unpaired surrogates), writes no dynamic value into an attribute, style block or script, and emits no `script`, `link`, `img`, `iframe`, `base` or `svg` element, no remote URL and no `@import`, with a `default-src 'none'` policy of its own.
- **CSV** fences every text value whose first character is `=` `+` `-` `@` TAB or CR with a leading `'` **and** quotes it, because a spreadsheet executes such a cell. Quoting also triggers on the delimiter, quote, CR, LF, TAB and edge spaces, with embedded quotes doubled. Measured metrics stay bare digits so a column is still summable, and an unmeasured metric renders `UNAVAILABLE`/`ERROR` rather than `0`.
- **XLSX** is a real minimal OOXML workbook written with the JDK zip support, and there is **no code path that writes an `<f>` formula cell at all** - so a hostile value cannot become a formula even by omission. Text is written as `t="inlineStr"`, metrics as numeric `<v>` cells, and the package contains no macro part, no `TargetMode="External"` relationship and no hyperlink.

### Report output confinement

`reports.dir` is resolved through `AppPaths`, and every write is confined by `resolve` -> `normalize` -> containment check against a base computed once in the constructor. **No method accepts a name, suffix, subdirectory or path**, and a file is always `report-<validated id>.<extension from an enum>`, so no request text is ever concatenated into a filename. Metadata lookup refuses symbolic-link leaves, but lookup is deliberately **not** download authority: `ReportService` owns the final content open and uses `READ + NOFOLLOW_LINKS`, then enforces `MAX_REPORT_BYTES` while reading from that same already-open handle. The web layer receives bytes plus safe metadata, never a checked `Path` that it can reopen later. This is the Goal 04A closure for the check/use symlink race. The temporary file is created **inside** `reports.dir` so the final move is atomic within one filesystem, and it is deleted on every failure path.

### The new routes

Six routes, all authenticated, all installed by the **same unconditional call** as the CM and analytics routes before the socket opens: `GET /api/history`, `GET /api/history/{id}`, `POST /api/reports`, `GET /api/reports`, `GET /api/reports/{id}/download`, `POST /api/statistics/item/{itemTypeId}/refresh`.

Both state-changing routes carry a **distinct** action header - `report-generate` and `statistics-item-refresh` - checked **before any state is read or changed**, with `application/x-www-form-urlencoded`, `multipart/form-data` and `text/plain` still refused. The committed tests prove the refusal has **zero** side effects with a witness counter rather than by inspection, and a mutation control that neutralises the header check makes those tests fail. List limits are clamped to `[1,100]` with `limit+1` hasMore probing and an **opaque cursor**, so a caller cannot ask for an unbounded read; malformed limits, cursors and identifiers are refused with `400` before reaching the store.

### Two containment decisions worth keeping

- **The history store owns an application-local file and nothing else.** It is created once per process and closed on shutdown, and it reads no repository profile JDBC value and uses no analytics pool connection. Its absence is a normal state: a missing driver, an unwritable directory or a schema from another build all produce a documented `UNAVAILABLE` state with a fixed reason, and repository activation is unaffected.
- **The operator UI has no runtime Internet dependency.** No CDN, no external font, no analytics script and no remote URL appears in any asset, every asset is served from the flat `/web` namespace behind the same authentication as the API, and the existing CSP (`default-src 'self'`) plus `no-store`/`nosniff`/`no-referrer` headers are unchanged. Every dynamic value reaches the DOM through `textContent` and explicit element creation, never `innerHTML`.

### Doctor and configuration readiness are LOCAL statements

`--validate-config` / `bin/doctor.sh` report history and report readiness by loading a driver **class name** and inspecting the data directory. They open no IBM CM session, no repository-database connection and no local history database, and the output says so in as many words. "Expected ready" is never presented as "the store has been opened", because those are different facts and the second is the one that matters.

