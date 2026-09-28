# Goal 04 - Cache/history, reports and complete operator Web UI

**Status: PROVISIONAL DRAFT — DO NOT EXECUTE WITHOUT EXPLICIT APPROVAL AFTER ARCHITECTURE REVIEW**

## Objective

Turn the backend into a usable internal administration console while preserving offline, dependency-light operation.

Use subagents for: UI/UX, persistence/reporting, security review, and end-to-end QA.

## Deliverables

### Cache and history

Implement:

- L1 metadata cache
- L2 statistics cache with configurable TTL
- L3 persistent aggregate snapshots

If H2 is used, it must be supplied as an approved local JAR under lib/app; no Maven download. Keep storage abstraction clean enough to replace later.

Historical data stores aggregates only.

### Refresh behavior

- dashboard renders last cached statistics immediately
- refresh runs asynchronously in-process
- existing data remains visible during scan
- show scan state, progress, start/end, duration and data age
- prevent accidental duplicate full scans per repository
- allow a single ItemType refresh without forcing full scan

### Reports

Implement unified report model and:

- HTML
- CSV
- XLSX when a suitable approved local library is available; otherwise make XLSX capability explicit and do not fake it

Reports include repository, timestamp, CM/database information, scan freshness, totals, per-ItemType statistics, classifications, retention policy names and warnings/partial failures.

### Web UI

English, responsive, fully offline.

Pages/views:

- repository selection
- dashboard
- ItemTypes table
- ItemType Properties drawer/dialog
- Retention
- History
- Reports
- System/Diagnostics

ItemType table supports search, sort, classifications and retention display.

Properties includes General, Statistics and Retention.

Do not implement content viewing.

### Diagnostics

Show:

- application/Java version
- selected repository
- CM adapter status/version when known
- DB vendor/JDBC status
- CM/JDBC pool metrics
- scan metrics
- cache freshness

Do not expose secrets.

### Security QA

Recheck all routes for authentication except minimal health. Validate HTML/JSON escaping and report output handling.

## Acceptance

This draft may be rewritten after earlier-goal review. If later explicitly approved and completed, stop for architecture review again instead of automatically starting another numbered goal.
