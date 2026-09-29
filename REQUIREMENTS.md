# Requirements

## Functional V1

- multiple configured IBM CM 8.7 repository profiles
- one selected active repository
- DB2 and Oracle Library Server support
- ItemTypes from IBM CM Java API metadata
- ItemType properties
- retention policy name when present
- retention overview with policies and assigned ItemTypes
- per-ItemType statistics:
  - logical items using ItemID semantics
  - created today
  - created during last 7 days
  - created during last 30 days
  - created during current calendar year
- repository totals for supported metrics
- architecture slots for Versions and Parts, disabled until exact CM 8.7 semantics are verified
- configurable ItemType classifications
- bounded parallel refresh
- cached results stay visible while refresh runs
- persistent historical snapshots
- HTML / CSV / XLSX report pipeline
- authenticated offline Web UI
- extension point for later ItemID/PID lookup

### Implemented API contract (Goal 03)

- `GET /api/statistics` (authenticated): analytics availability with a fixed reason, the active repository
  id, the current scan progress/status, the latest completed snapshot, its freshness and its
  coverage/partial-failure information.
- `POST /api/statistics/refresh` (authenticated): starts at most ONE scan. Requires the CSRF-resistant
  header `X-CM-Insight-Action: statistics-refresh`; a missing or wrong header is `403 action_forbidden`
  BEFORE any state is read or changed, so a refused request has zero side effects. A second request while a
  scan is in flight is a deterministic `409 scan_in_progress`; a closing repository is
  `409 repository_closing`; a started scan is `202`.
- `GET /api/diagnostics/jdbc` (authenticated): database vendor, driver installed/ready with a safe driver
  identity, pool configured size/capacity states/counters/close state, scan counters/timestamps, and a
  sanitised last JDBC error. Never the JDBC URL, the database user name, a schema taken from an exception,
  raw SQL or a raw `SQLException` message.
- The three routes are installed unconditionally with the CM routes before the socket is opened, so
  "no JDBC driver is installed" answers the documented `UNAVAILABLE` state rather than `404`.
- `feature.statistics=false`, a missing driver/credential, an unusable schema or an unreachable database
  never deactivates a repository: the metadata and retention routes keep working.
- An out-of-range bounded setting (`jdbc.pool.*`, `statistics.*`), or `statistics.workers` greater than
  `jdbc.pool.size`, is a configuration error (startup exit 3, doctor ERROR) - refused, never clamped.
- `--print-config` and `--validate-config` report local analytics readiness only (feature disabled; driver
  absent; driver present; URL/vendor mismatch; schema configured versus derived; pool/worker bounds
  accepted) and never open a database connection.

## Performance

- reuse CM and JDBC connections
- one exclusive DKDatastoreICM per concurrent CM operation
- one exclusive JDBC connection per concurrent SQL operation
- hard-bounded pools; no emergency connections
- saturation produces backpressure
- one aggregate query per root table for multiple time windows where practical
- at most ONE statistics scan per repository context; a fixed worker count from `statistics.workers` that
  may not exceed `jdbc.pool.size`, over a bounded queue (never an unbounded executor queue)
- one ItemType failure or query timeout does not abort unrelated ItemTypes; a scan that visits its whole
  frozen ItemType list publishes ONE immutable snapshot with its coverage, and a cancelled or timed-out
  scan publishes nothing while the previous snapshot stays visible
- the database current date is read once per scan, so a long scan cannot cross midnight into inconsistent
  calendar windows
- conservative configurable parallelism
- partial scan failures are isolated and visible
- every scan records duration and freshness

## Security

- V1/V2 are read-only
- default bind 127.0.0.1
- admin/admin allowed only for loopback development
- non-loopback + default credentials fails closed
- no credentials or proprietary JARs in Git
- no secrets in logs/reports
- state-changing endpoints require a custom action header a cross-site HTML form cannot set, and refuse the
  three form-sendable content types; the guard runs before any state is read or changed
- no response body may carry a JDBC URL, a database user name, a schema taken from an exception, raw SQL or
  a raw `SQLException` message; diagnostics publish value-free facts and a sanitised error record only
- driver readiness is local (class loading and `DriverManager` inspection) and is never reported as evidence
  of a reachable database; no public health endpoint calls a live database
- the database account should hold SELECT-only privileges, and application safety does not depend on it
- retention admin disabled by default

## Deployment

- Linux/Unix-oriented
- Java 17 LTS compatible OpenJDK distribution
- plain javac / jar / bash
- target hosts may have no Internet access
