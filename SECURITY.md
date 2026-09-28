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
