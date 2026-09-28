# Security

## Trust model

V1/V2 are read-only with respect to IBM CM and the Library Server. The application may only write its own local cache/history/report/run files.

## Web binding

Default binding is 127.0.0.1.

Development credentials admin/admin are allowed only on loopback. Startup must refuse non-loopback binding while default credentials are active.

## Credentials

Repository passwords are supplied through environment variables or a local secret file with restrictive permissions. Profile files contain references to secret names, not passwords.

Never log passwords, Authorization headers or JDBC credential strings.

## Dependencies

Do not commit proprietary IBM or Oracle JAR files to the public repository.

No runtime CDN or external font dependency.

## Future retention administration

Must remain disabled by default and requires a separate security review, CSRF protection, audit logging, dry-run/preview, explicit confirmation and post-operation verification before activation.

## Implemented controls (Goal 01)

Recorded here so the review can check the implementation against the intent above.

- **Loopback detection is offline.** `SecurityPolicy.isLoopbackLiteral` is a pure string test: no DNS, no socket, no `InetAddress`. Only `127.0.0.0/8` literals, `::1` and the name `localhost` qualify. `0.0.0.0`, `::`, `::ffff:127.0.0.1` and every host name are treated as non-loopback, so a name that fails to resolve can never unlock the relaxed path.
- **Fail closed.** Blank effective credentials refuse startup. Default `admin`/`admin` refuses any non-loopback bind. The check runs both in `Main` before anything opens and again in `WebServer.start()` before a socket exists.
- **Secret indirection only, and fail closed.** Resolution order is `<key>.env`, then `<key>.file` under `secrets.dir`, then an inline value (warned about). If one of those is configured but produces no value, startup is REFUSED with a diagnostic naming the field and the source that failed; the application never silently downgrades to the development default. The built-in `admin`/`admin` default applies only when nothing at all is configured, and only on a loopback bind. Secret file paths are confined to `secrets.dir`; a group- or other-readable secret file produces a warning on POSIX filesystems (on Windows the permission check is not performed, because there is no POSIX mode to inspect, so file permissions must be enforced by the hosting platform instead).
- **No credential can be printed.** `SecretRef` and `WebAuthSettings` override `toString()` so a password can never reach a log line, and `SecretRef` exposes the value only through a package-private accessor. Startup output and `--print-config` print `<redacted>` plus the source description.
- **Every route but `/api/health` is authenticated.** `Router` refuses to register any other public path — only the exact path `/api/health`, never its subtree and never a public prefix, so a future route cannot slip an unauthenticated surface under a health-looking name. It authenticates before producing 404 or 405 so there is no path-existence oracle, and renders every error as clean JSON with no stack trace and no exception message. Static assets are served from the classpath through a name allow-list that rejects `..`, sub-directories and unknown extensions.
- **Brute-force guard.** `LoginThrottle` locks a source address after a configurable number of consecutive failures and answers with `429` plus `Retry-After`. Its table is hard bounded by `web.auth.maxTrackedKeys` and pruned least-recently-used, so it cannot grow without limit. A locked key is answered without comparing the submitted credential.
- **Constant-time comparison.** The user and password are compared with `MessageDigest.isEqual`, and both comparisons always run. Decoded credential buffers are zeroed after use.
- **Response hardening.** Every response carries `Content-Type`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer` and a restrictive `Content-Security-Policy`. All assets are local.
- **Retention administration stays off.** `feature.retention.admin=true` is rejected outright rather than merely defaulted off.
