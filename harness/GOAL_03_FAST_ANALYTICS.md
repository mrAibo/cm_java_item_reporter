# Goal 03 - Fast DB2/Oracle analytics, bounded JDBC pool and snapshot API

**Status: COMPLETED / PUSHED / GREEN - AWAITING ARCHITECTURE REVIEW. DO NOT RE-EXECUTE.**

Implementation commit: `1a52f908741005e82b4bf12eac17aab7373fb489`.
CI fix commit: `dc24dbb31b51619719416cf7da2b51b506cc6cb8` (this carries the green runs).
Push run `36615110466` **success**, pull_request run `36615118636` **success**, both for `dc24dbb`.
Core 341 tests and IBM 51 tests green on the stub path; `--require-ibm` green against the real
IBM CM 8.7 SDK. No live DB2/Oracle database is reachable, so there is NO live SQL validation.

The first JDBC implementation goal, still V1/V2 read-only.

Reviewed prerequisites:

- Goal 01 / 01A / 01B / 01C: accepted core/lifecycle foundation.
- Goal 02 / 02A: IBM CM read adapter and semantics accepted after correction.
- Goal 02B: resource-contract closure accepted at reviewed remote SHA
  `30f8a31aaa41522c2d00b0b894801576f74ed401`.

This is the first JDBC implementation goal.

It is still V1/V2 read-only.

Do NOT execute Goal 04 or Goal 05.
Do NOT implement persistent history, Excel/report export, UI polish, Versions/Parts SQL, retention
administration, document content reads, migration or any IBM CM/database write.

Use AgentTeams/subagents aggressively for independent DB2 SQL review, Oracle SQL review, JDBC
resource-lifecycle/concurrency review, scan-coordinator review, read-only/security review and final
adversarial verification. The lead agent owns integration.

---

# 1. Objective and architecture boundary

Implement the fast statistics half of the existing architecture:

```text
MetadataRepository (IBM CM API)
        |
ItemTypeSummary / ItemTypeID
        |
StatisticsRepository
        |
BoundedPool<JdbcSession>
        |
DB2 or Oracle JDBC
```

There is exactly ONE active RepositoryContext and at most ONE JDBC pool inside it.

Every JDBC connection used by analytics comes from that pool. No ad-hoc DriverManager call is allowed
outside the pool factory, including probes, schema discovery, diagnostics and retries.

The web layer never receives a `java.sql.Connection`, `Statement`, `ResultSet` or driver object.

JDBC/database code is IBM-independent and stays in core/database analytics packages. It must not import
`com.ibm.*`.

---

# 2. Frozen counting semantics - DO NOT REOPEN OR GUESS

The architecture review verified these semantics from IBM CM 8.7 documentation before approving this
goal.

Authoritative IBM documentation:

- component root tables:
  https://www.ibm.com/docs/en/content-manager/8.7.0?topic=tables-icmutnnnnnsss-component-roots
- Items / ItemID bits:
  https://www.ibm.com/docs/en/content-manager/8.7.0?topic=tables-icmstitems-items
- versioning:
  https://www.ibm.com/docs/en/content-manager/8.7.0?topic=concepts-versioning
- ItemType definitions / current SegmentID:
  https://www.ibm.com/docs/en/content-manager/8.7.0?topic=settings-icmstitemtypedefs-item-type-definitions

## 2.1 Logical item

A logical item is ONE DISTINCT `ItemID`.

It is NOT:
- one physical row;
- one `VersionID`;
- one component;
- one resource part.

IBM documents root rows with `ItemID` + `VersionID`, and documents that all versions of one item
share the same ItemID. Therefore:

> `COUNT(*)` on an ICMUT root table is NOT an accepted logical-item count for a versioned ItemType.

The analytics query must deduplicate ItemID across versions before counting.

`versions` and `parts` in `ItemTypeStatistics` stay `MetricValue.UNAVAILABLE` in this goal.

## 2.2 Logical creation date

Do NOT use root-row `CreateTS` as the logical item's original creation time.

IBM defines root `CreateTS` as the timestamp when that physical entry was created. A versioned item can
have several physical root entries, so that timestamp is not proof of the original logical-item creation.

IBM also documents that ItemID is generated when the ITEM is created and carries a timestamp. Goal 03
uses the documented DATE portion of that immutable ItemID.

Positions used:

- 9: century (`A=20`, `B=21`);
- 10-11: two-digit year;
- 12: month (`A=01 ... L=12`);
- 13-14: day.

Define one tested `ItemIdDateKey` encoder for the fixed-width six-character value represented by
`SUBSTR(ItemID, 9, 6)`.

The encoder may cover only the documented 2000-2199 range. If a boundary date cannot be encoded from the
documented rules, the affected time metrics are UNAVAILABLE; do not invent another century mapping.

## 2.3 Reporting windows

Read the database current date ONCE at the start of a scan through the selected dialect. Every ItemType in
that snapshot uses exactly that date; a long scan must not cross midnight into inconsistent windows.

Calendar definitions:

- today: `[today, tomorrow)`
- last 7 days: `[today - 6 days, tomorrow)`
- last 30 days: `[today - 29 days, tomorrow)`
- current calendar year: `[Jan 1 current year, Jan 1 next year)`

No JVM-local date decides these boundaries.

---

# 3. JDBC analytics must be optional to CM activation

Goal 02 deliberately made repository activation depend only on the IBM/CM half it needs. Preserve that.

A repository whose IBM adapter works must remain selectable and its metadata/retention viewer must remain
usable when any of these is true:

- `feature.statistics=false`;
- DB2/Oracle JDBC driver is missing;
- JDBC credential is missing/unreadable;
- analytics schema is unusable;
- the database is temporarily unreachable.

Do NOT make CM repository activation eagerly connect to JDBC.

## Required shape

When statistics are enabled, construct the analytics capability and a hard-bounded JDBC pool WITHOUT
eagerly creating database connections. Connection creation is lazy when a statistics scan needs one.

The RepositoryContext owns the JDBC pool and scan coordinator/resources. On repository switch:

1. current scan is cancelled/drained;
2. JDBC pool is closed;
3. any `CLOSING` or `CLOSED_UNCERTAIN` JDBC outcome participates in the SAME Goal 01C context close-state
   semantics as the CM pool;
4. the next repository is not activated while a physical JDBC resource from the old context is still
   pending or unproven.

Do not hide the pool behind a resource that makes RepositoryContext unable to observe its close outcome.

Add typed accessors/services; do not introduce a `Map<String,Object>` service locator.

---

# 4. Configuration

Add one validated analytics settings object built by core from the already-loaded AppConfig.

Recommended keys and bounds:

```properties
jdbc.pool.size=4
jdbc.pool.borrow.timeout.ms=5000
jdbc.pool.max.age.minutes=30
jdbc.pool.max.operations=1000

statistics.workers=4
statistics.query.timeout.seconds=60
statistics.scan.timeout.seconds=1800
```

Use the same strict integer/range style as `cm.pool.*`.

Required ranges:

- jdbc.pool.size: 1..64
- borrow timeout: 1..600000 ms
- max age: 1..1440 minutes
- max operations: 1..1000000
- workers: 1..64
- query timeout: 1..3600 seconds
- scan timeout: 1..86400 seconds

If `statistics.workers` is omitted, default to `min(4, jdbc.pool.size)`.
If explicitly configured greater than `jdbc.pool.size`, refuse that statistics configuration rather
than pretending the requested parallelism exists.

Do not put a password in AppConfig.

Add `RepositoryProfile.resolveJdbcCredentials(SecretResolver)`, symmetric with
`resolveCmCredentials`, so the JDBC factory resolves ONLY the JDBC pair it needs and re-resolves it for
every new/replacement connection.

A credential that disappears before `DriverManager.getConnection` is a known-clean pre-allocation
failure and must explicitly release its reserved pool slot.

---

# 5. Driver discovery and local/offline runtime

No dependency download and no driver JAR in Git.

Existing local runtime layout remains authoritative:

```text
lib/db2/*.jar
lib/oracle/*.jar
```

The launcher already adds these directories to the class path.

Support:

- DB2: `com.ibm.db2.jcc.DB2Driver`
- Oracle preferred: `oracle.jdbc.OracleDriver`
- Oracle legacy compatibility fallback: `oracle.jdbc.driver.OracleDriver`

Driver discovery/readiness is local only: class loading / DriverManager registration, never a network
connection.

Validate JDBC URL family against `RepositoryProfile.databaseVendor`:
- DB2 -> `jdbc:db2:`
- Oracle -> `jdbc:oracle:`

A mismatch is statistics-unavailable/configuration error, never "try it anyway".

The build and normal tests MUST continue to work with ZERO DB2/Oracle JARs by using java.sql-only fakes /
registered test drivers. Proprietary drivers are optional runtime inputs, never compile-time requirements.

---

# 6. JdbcSession and hard-bounded pool contract

Implement a small `JdbcSession` wrapper around one `java.sql.Connection`.

The raw Connection stays private to the database package.

## 6.1 Health

Goal 02B made `ResourceFactory.isHealthy` mandatory.

For JDBC the pool health probe MUST be a local in-memory flag only.

Do NOT call:
- `Connection.isValid()`;
- metadata/network probes;
- `SELECT 1`;
- any driver round trip

from `ResourceFactory.isHealthy`, because BoundedPool invokes it while holding its internal lock.

A query-path SQL failure should conservatively mark the JdbcSession unusable before its lease is returned,
unless there is an explicitly documented benign class. It is acceptable in this goal to retire on every
`SQLException`: losing one connection is safer than reusing a transaction/driver state that is unknown.

Timeout/cancellation SQL failures are not proof the connection is reusable; conservative retirement is
acceptable.

## 6.2 Creation verdict / allocation boundary

The JDBC allocation boundary is the successful return of a `Connection` from
`DriverManager.getConnection(...)`.

Before that boundary:

- invalid URL/vendor;
- missing driver;
- invalid analytics configuration;
- missing/unreadable JDBC credential

are explicitly `PROVEN_CLEAN`.

If `DriverManager.getConnection` throws without returning a Connection, no application-owned JDBC
Connection exists; report the attempt clean under the JDBC abstraction and preserve the sanitized
SQLException as the cause/diagnostic. Do not expose its raw message.

After a Connection is returned, setup failures must close that exact Connection.

- close returned normally -> `PROVEN_CLEAN`
- close threw -> `UNPROVEN` / quarantine
- genuinely unknown outcome -> conservative / quarantine

Attempt `Connection.setReadOnly(true)` as defense in depth. The Java/JDBC contract defines read-only as a
hint, so it is NOT the read-only security boundary. If the driver explicitly reports
`SQLFeatureNotSupportedException`, record a safe warning and continue under the structural query-only
rules below. Other setup failures follow the cleanup verdict above.

## 6.3 Close

`JdbcSession.close()` calls the underlying `Connection.close()` exactly once.

If close throws, let that failure reach BoundedPool so the capacity slot is quarantined. Never authorize a
replacement while the physical close is unproven.

No emergency or overflow connection.

---

# 7. Structural JDBC read-only guarantee

Direct SQL is read-only in V1/V2. Do not rely on `setReadOnly(true)` alone.

Create a narrow query API whose production path only prepares/executes SELECT statements and drains result
data while the lease is held.

No public/service/web method exposes Connection/Statement/ResultSet.

Add a committed source guard for the analytics JDBC source that refuses write/control paths such as:

- `executeUpdate`, `executeLargeUpdate`, update batches;
- `commit`, `rollback` used by analytics;
- SQL beginning with INSERT, UPDATE, DELETE, MERGE, TRUNCATE, CREATE, ALTER, DROP, GRANT, REVOKE;
- stored procedure/CALL execution unless a later reviewed goal explicitly allows one.

The guard must:
- scan a real, non-empty analytics source tree;
- fail if that tree disappears;
- have a planted positive control proving it detects a forbidden call.

Do not over-broaden the regex until ordinary Java list/map operations become impossible.

Document that the database account should have SELECT-only privileges; application safety still must not
depend on the operator having done so.

---

# 8. DatabaseDialect redesign

The current three-method bootstrap dialect is too weak, and Oracle's `oneRowSuffix()` is
context-sensitive. Replace it rather than building production SQL on it.

Each dialect must provide explicit complete SQL operations or safe SQL fragments for:

- driver identity / supported URL prefix;
- database current DATE query;
- zero-row / existence probe where needed;
- identifier qualification rules;
- the ItemID aggregate query shape.

Do not expose a generic suffix that is only valid when the caller happened to include a WHERE clause.

At minimum verify:

DB2:
- database date query using DB2 syntax;
- fetch/zero-row syntax;
- `SUBSTR` date-key expression.

Oracle:
- database date query using Oracle syntax and DUAL where required;
- ROWNUM / zero-row syntax in a complete query;
- `SUBSTR` date-key expression.

The query builder accepts only validated schema names and generated ICMUT table names. No request parameter,
ItemType name or arbitrary string becomes a SQL identifier.

For V1, supporting safe unquoted schema identifiers is enough. If a configured/driver-derived schema needs
quoted-identifier semantics this implementation has not proven, mark statistics unavailable with an
actionable reason instead of interpolating it.

If `repository.jdbc.schema` is absent, a live JDBC session may use the driver's
`Connection.getSchema()` result. Validate it with the same identifier rule. A blank/unsupported answer is
unavailable, not a guessed `ICMADMIN`.

---

# 9. PhysicalSchemaResolver - all root segments, never current-only

Adapt the proven read query from `mrAibo/CM_retention`:

```sql
SELECT C.COMPONENTTYPEID, I.SEGMENTID
FROM <schema>.ICMSTCOMPDEFS C
JOIN <schema>.ICMSTITEMTYPEDEFS I
  ON I.ITEMTYPEID = C.ITEMTYPEID
WHERE C.ITEMTYPEID = ?
  AND C.PARENTCOMPTYPEID = 0
```

Requirements:

1. bind `ItemTypeID`; never interpolate it;
2. require exactly one root component row;
3. validate ComponentTypeID;
4. validate current SegmentID against IBM's documented 1..36 range;
5. represent the mapping as a list of physical root tables, not a single string;
6. for current SegmentID N generate every table:
   `ICMUT%05d001 ... ICMUT%05d%03d(N)`;
7. validate each generated name against a strict generated-table pattern;
8. verify every expected table exists using safe JDBC metadata or a zero-row SELECT;
9. if any expected segment is absent/inaccessible, fail that ItemType mapping - do not skip it;
10. cache mappings per RepositoryContext only; no cross-repository static cache.

The working CM_retention backfill intentionally refused SegmentID != 1 because its mutation logic was
single-segment. Analytics must not copy that limitation by counting only the current table.

Add adversarial tests for:
- one segment;
- multiple segments;
- no root row;
- multiple root rows;
- segment 0 / >36;
- missing middle segment;
- unsafe schema / generated identifier.

---

# 10. Aggregate query

For each ItemType, issue ONE logical aggregate query that scans each expected physical root segment no
more than once where practical.

Correctness shape:

1. build the logical ItemID set from every segment;
2. deduplicate ItemID across VersionID and across segments;
3. aggregate that deduplicated set.

An acceptable conceptual shape is:

```sql
WITH logical_items AS (
    SELECT ItemID FROM <root-segment-1>
    UNION
    SELECT ItemID FROM <root-segment-2>
    ...
)
SELECT
    COUNT(*) AS total_items,
    COALESCE(SUM(CASE WHEN <date-key> >= ? AND <date-key> < ? THEN 1 ELSE 0 END), 0) AS today_items,
    ...
FROM logical_items
```

For one table, `SELECT DISTINCT ItemID` is an equivalent source.

The actual DB2/Oracle SQL may differ behind the dialect, but the semantic result may not.

Use bind parameters for date keys.

Date-key expression is based on `SUBSTR(ItemID, 9, 6)`; do not parse VersionID or CreateTS to derive
logical creation date.

The four time metrics use the single scan anchor date from section 2.3.

If the total count can be proven but date-key boundaries cannot be represented, return total AVAILABLE and
the affected time metrics UNAVAILABLE rather than failing the whole ItemType.

Versions and Parts remain UNAVAILABLE.

Use `long` for counts end to end.

---

# 11. Statistics DTOs and typed service

The existing `ItemTypeStatistics` / `MetricValue` are bootstrap contracts and may be refined now because
there is still no production consumer.

Required concepts:

- `StatisticsRepository` read/refresh interface;
- immutable `StatisticsSnapshot`;
- immutable scan-progress/status DTO;
- per-ItemType result;
- overall totals;
- totals grouped by existing `ItemTypeSummary.businessClassification()`.

Do NOT recreate SAP/NON-SAP logic. ClassificationRules were already fixed in Goal 02A. Consume the label
already present in metadata.

`MetricValue` must represent at least:
- AVAILABLE(value)
- UNAVAILABLE(no value)
- ERROR(no value)

Never turn ERROR/UNAVAILABLE into numeric zero.

Snapshot totals must report coverage/partial-failure state so a subtotal from 98 successful ItemTypes is
never presented as a complete total for 100.

Versions/Parts stay unavailable in every per-ItemType result and total.

Any stored/displayed database error text is sanitized: fixed operation label + SQLState/vendor code where
safe. Never store or return raw `SQLException.getMessage()`, SQL text, credential, or JDBC URL.

---

# 12. Scan coordinator

One RepositoryContext may have at most ONE in-flight statistics scan.

Configuration:
- fixed worker count from `statistics.workers`;
- workers <= JDBC pool size.

Do not use an unbounded executor queue.

Acceptable implementations:
- N fixed workers sharing an atomic index over an immutable ItemType list; or
- a ThreadPoolExecutor with an explicitly bounded queue and bounded submission.

Each concurrent SQL task owns one JDBC lease for the duration of its query.

Required behavior:

- metadata ItemType list is frozen at scan start;
- database current date is read once at scan start;
- per-query timeout uses `Statement.setQueryTimeout`;
- overall scan deadline is enforced;
- cancellation is best-effort via statement cancellation/interrupt plus normal lease close;
- no worker/executor thread leak after context close;
- one ItemType failure does not abort unrelated ItemTypes;
- progress reports total/completed/failed/current rate/duration;
- scan close/cancel participates in RepositoryContext shutdown before the JDBC pool is closed.

## Snapshot publication rule

While a scan runs, the previous completed snapshot remains visible.

If the scan reaches normal terminal completion after visiting the frozen ItemType list, publish ONE new
immutable snapshot atomically, even when some ItemTypes are ERROR; that snapshot records
`partialFailureCount` and coverage.

A catastrophic coordinator failure, overall-timeout abort, repository switch or cancellation does NOT
replace the previous completed snapshot with an incomplete half-built object.

No persistent history in this goal.

---

# 13. HTTP/API integration

Add minimal authenticated analytics API. UI polish belongs to Goal 04.

Required routes:

```text
GET  /api/statistics
POST /api/statistics/refresh
GET  /api/diagnostics/jdbc
```

All three require authentication.

`POST /api/statistics/refresh` changes local process state, so require the same CSRF-resistant custom
action-header pattern as repository selection:

```text
X-CM-Insight-Action: statistics-refresh
```

Cross-site form content types remain refused.

The POST starts at most one scan; a second request while one is active returns a deterministic conflict
rather than starting duplicate work.

GET statistics returns:
- analytics availability;
- active repository id;
- current scan progress/status;
- latest completed snapshot when present;
- freshness/capturedAt;
- partial failure/coverage information.

Diagnostics returns only safe driver/pool/scan facts:
- database vendor;
- driver installed/ready boolean and safe driver identity;
- pool configured size/capacity states/counters/close state;
- scan counters/timestamps;
- sanitized last JDBC error.

Do NOT return:
- JDBC URL;
- database username;
- schema from an exception;
- raw SQL;
- raw SQLException message;
- secret source contents.

If statistics is disabled or unavailable, metadata/retention routes continue working and the statistics API
returns an explicit unavailable state/error; it must not deactivate the repository.

---

# 14. Readiness and doctor diagnostics

Extend configuration/doctor output so an operator can distinguish:

- statistics feature disabled;
- driver absent;
- driver present;
- JDBC URL vendor mismatch;
- schema configured vs to-be-derived;
- pool/worker bounds accepted.

This is LOCAL readiness only. Doctor/config validation must not open a database connection merely to print
configuration.

Do not call a live database from a public health endpoint.

---

# 15. Tests and adversarial controls

All existing tests must remain green.

Add dependency-free tests using registered fake JDBC drivers / dynamic proxies. Do not require DB2 or Oracle
JARs in CI.

At minimum test:

## Pool/factory
- missing JDBC credential before getConnection -> explicit PROVEN_CLEAN, zero driver connects, no quarantine,
  recovery after credential restoration;
- missing driver / vendor-URL mismatch -> known-clean failure;
- getConnection SQLException before a Connection is returned -> clean under the JDBC abstraction;
- post-open setup failure + successful close -> clean;
- post-open setup failure + close failure -> quarantine;
- query SQLException poisons session before lease return;
- close failure quarantines;
- local isHealthy performs zero JDBC/driver calls;
- hard physical application-owned Connection peak never exceeds configured size.

## SQL/schema
- DB2 current-date query;
- Oracle current-date query;
- no context-sensitive `oneRowSuffix`;
- safe schema validation;
- exact CM_retention root resolver query semantics;
- 1..N segment generation;
- missing middle segment is not skipped;
- one segment aggregate;
- multi-segment aggregate;
- duplicate ItemID across two versions counts once;
- duplicate ItemID across segments counts once;
- empty ItemType returns zeros;
- ItemID date encoder boundaries for 2000, 2099, 2100, 2199;
- invalid/unrepresentable boundary -> time metrics unavailable;
- calendar windows around month/year/leap-year boundaries;
- generated SQL is SELECT-only.

## Coordinator
- concurrency never exceeds worker count or pool size;
- no unbounded queue;
- one scan at a time;
- query timeout -> per-item ERROR and session retirement;
- one item failure does not stop remaining items;
- normal partial-failure scan atomically publishes a snapshot;
- catastrophic cancellation keeps previous snapshot;
- repository/context close cancels scan and leaves no thread;
- late JDBC lease makes context CLOSING and blocks switch until returned;
- JDBC close/quarantine makes context CLOSED_UNCERTAIN and permanently blocks unsafe replacement repository
  activation, exactly like the accepted CM-pool rule.

## API/security
- all analytics routes authenticated;
- refresh requires exact action header;
- wrong/missing header has zero side effects;
- second concurrent refresh conflicts;
- no JDBC URL/user/raw SQL/raw exception text in responses;
- unavailable JDBC does not break ItemType/retention routes.

## Guards
- analytics source tree is non-empty;
- committed tree contains no forbidden write call;
- planted DML/write call is detected;
- zero proprietary DB driver JARs are tracked.

Add mutation/opposite controls where useful; do not make assertions that merely restate the implementation.

---

# 16. Real DB validation and honesty

If DB2 and/or Oracle test databases are available, compare a representative set of ItemTypes against a
trusted source and record:

- item type id/name;
- physical root mapping and segment count;
- distinct ItemID total;
- each calendar-window count;
- old CM_Item_Reporter / DBA query result where comparable;
- duration;
- database family/version.

Important: the old `CM_Item_Reporter` uses `COUNT(*)` and its old ICMSTNLSKEYWORDS table-name shortcut,
so it is NOT authoritative for versioned/multi-segment semantics. A disagreement must be investigated, not
"fixed" by copying the old number.

If no live DB2/Oracle CM database is reachable, say so explicitly. Do not claim live SQL validation.

The implementation may still be completed from the frozen IBM-documented semantics and deterministic query
tests, but STATUS must identify lack of live DB execution as an unresolved verification gap for the next
environment that can reach one.

A driver JAR being loadable is NOT live database validation.

---

# 17. Build / operational validation

Run at minimum:

- `./build.sh`
- `./tests/selftest.sh`
- `./tests/shell/run.sh`
- `./bin/doctor.sh`
- `./build.sh --check-ibm-isolation`
- IBM suite on committed stubs
- `./build.sh --require-ibm` when the same real CM SDK is available

Also exercise the application with no DB driver JARs present: core + IBM metadata must still start, and
statistics must report unavailable rather than causing a startup failure.

If local DB2/Oracle JDBC JARs are available, verify their local discovery separately; do not imply that
discovery proves a server connection.

Verify zero tracked proprietary JARs and credentials.

Both push and pull_request Actions for the SAME final SHA must be green.

PR #1 stays draft/unmerged.

---

# 18. Documentation and handoff

Update:

- `ARCHITECTURE.md`
- `DATA_MODEL.md`
- `REQUIREMENTS.md` if API/config behavior changes its contract;
- `lib/README.md` for driver placement/readiness;
- config examples for every new bounded setting;
- `STATUS.md`.

STATUS must record:

- exact implementation commit(s);
- JDBC pool/resource contract;
- activation behavior when analytics is unavailable;
- DB2/Oracle dialect/query shape;
- PhysicalSchemaResolver evidence and multi-segment rule;
- exact logical-item and date-window semantics;
- tests actually executed;
- real DB2/Oracle validation or explicit absence;
- real IBM SDK validation separately;
- exact push/PR Actions run IDs;
- unresolved risks.

Set the next goal to:

**NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED**

Do not execute Goal 04 or Goal 05.

At completion:

1. review the complete integrated diff;
2. run all available validation;
3. commit coherently;
4. push `origin/bootstrap/cm-insight-architecture`;
5. verify local HEAD == remote HEAD;
6. verify both exact-SHA Actions events are green;
7. do not merge PR #1;
8. stop with the architecture-review handoff.
