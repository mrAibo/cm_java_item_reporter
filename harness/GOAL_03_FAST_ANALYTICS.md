# Goal 03 - Fast DB2/Oracle analytics and bounded parallel scan

## Objective

Implement high-performance read-only statistics for large IBM CM repositories.

Use subagents for: DB2 SQL/schema, Oracle SQL/schema, concurrency/performance review, and independent correctness review.

## Critical correctness rule

Do not guess IBM CM schema semantics.

Logical-item counting and timestamp semantics must be verified from existing working code, IBM documentation available to the environment, and/or a real CM schema. If an assumption remains unverified, mark that metric unavailable and document it.

Versions and Parts remain disabled in this goal.

## Deliverables

### JDBC infrastructure

- driver loading from local lib/db2 and lib/oracle
- strict bounded JDBC pool
- one connection per concurrent SQL task
- health validation/reconnect/close
- read-only connection intent where driver permits
- query timeout support
- metrics
- no ad-hoc JDBC connections outside pool

### Database dialects

Complete DB2 and Oracle dialects for required read-only analytics:

- current timestamp
- date/time window expressions
- current calendar year boundary
- one-row probe
- identifier validation/qualification
- any syntax difference needed by the aggregate query

### Physical root resolver

Adapt the working CM_retention approach:

- ICMSTCOMPDEFS
- ICMSTITEMTYPEDEFS
- ItemTypeID
- ComponentTypeID
- SegmentID
- ICMUT physical table name

Validate schema/table identifiers and cache mappings. Handle multi-segment cases explicitly; do not silently count only one segment if that would be wrong.

### Aggregate statistics

For every ItemType, obtain in as few table scans as practical:

- total logical ItemIDs
- today
- last 7 days
- last 30 days
- current calendar year

Use one aggregate query per physical root table where correct.

### Scan coordinator

- fixed configurable worker count
- hard-bounded JDBC pool
- ItemTypes distributed across workers
- no unbounded task queue
- per-ItemType timeout/failure capture
- overall timeout
- cancellation/shutdown behavior
- partial result publication
- scan progress/rate/duration
- old cached data remains available until successful new result publication

### Configurable classifications

Replace hard-coded SAP/NON-SAP behavior with ordered regex classification rules from configuration.

### Verification

For a representative set of ItemTypes, compare new logical counts with a trusted result (old CM_Item_Reporter, direct DBA count, or another verified source) on both database families when environments are available.

Document evidence in STATUS.md.

## Performance principle

Optimize elapsed scan time under a bounded, production-safe load. Do not maximize thread count blindly.

## Acceptance

A refresh scans ItemTypes in bounded parallel fashion on DB2 and Oracle adapters, reports partial failures, and publishes freshness/duration. Versions/Parts remain unavailable. Update STATUS.md; next goal Goal 04.
