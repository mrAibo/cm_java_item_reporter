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

## Performance

- reuse CM and JDBC connections
- one exclusive DKDatastoreICM per concurrent CM operation
- one exclusive JDBC connection per concurrent SQL operation
- hard-bounded pools; no emergency connections
- saturation produces backpressure
- one aggregate query per root table for multiple time windows where practical
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
- retention admin disabled by default

## Deployment

- Linux/Unix-oriented
- Java 17 LTS compatible OpenJDK distribution
- plain javac / jar / bash
- target hosts may have no Internet access
