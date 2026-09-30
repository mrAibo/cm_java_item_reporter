# Goal 04 - Persistent history, reports, targeted refresh and complete operator Web UI

**Status: COMPLETED / PENDING ARCHITECTURE REVIEW.** Executed against reviewed checkpoint
`fdb8ecd7be04013cd9ca1f3ed5adebc994a1ca96`; implementation commit
`c8a871d00e66cd857e2cd5f4148b0df76b7a1438`, green on both GitHub Actions events for that SHA.
See the Goal 04 execution record at the top of `STATUS.md` for the full evidence, the honest
gaps, and the unresolved risks.

Reviewed implementation baseline:
- Goal 03B implementation: 6fbee64bb047bd6ff841a5a530a0897703ab5b0f
- Goal 03B execution/handoff HEAD: edab82f25e69af2a8170fb3130ec69f7314fb0c8
- Goal 03B is accepted by architecture review before this goal is opened.

Do NOT execute Goal 05.
Do NOT merge PR #1.
Do NOT weaken any Goal 01-03B lifecycle, read-only, counting, cancellation, security or resource invariant.

Use independent subagents for:
1. cache/history and local-storage lifecycle;
2. report generation and output security;
3. targeted ItemType refresh and concurrency;
4. Web UI/UX and accessibility;
5. independent security/adversarial verification.

The lead agent owns integration, final review, commit, push and exact-SHA CI verification.

---

## 1. Objective

Turn the accepted read-only backend into a usable offline administration console while preserving one source of truth for statistics and one active repository.

Goal 04 adds:
- statistics freshness/cache presentation;
- persistent aggregate history;
- targeted single-ItemType refresh for the detail view;
- HTML / CSV / XLSX report capability;
- the complete English operator Web UI;
- history/report/diagnostic APIs needed by that UI.

It must NOT add:
- IBM CM writes;
- repository-database writes;
- retention administration;
- document content access;
- Versions/Parts SQL;
- migration/backfill paths;
- external CDN/runtime Internet dependencies.

---

## 2. Frozen architecture from Goals 01-03B

Preserve exactly:
- Java 17, one JVM modular monolith;
- no Maven/Gradle/Spring/containers/microservices;
- one active RepositoryContext;
- hard-bounded CM/JDBC resources and no emergency connections;
- Goal 01C fail-closed repository switching;
- Goal 02B create/health/close contracts;
- IBM SDK isolation and read-only CM adapter;
- analytics JDBC is optional to repository activation;
- direct repository analytics SQL is SELECT-only;
- logical item = one distinct ItemID;
- all physical root segments 1..N are mandatory;
- creation windows come from immutable ItemID positions 9-14;
- one database date anchor per full scan;
- Versions and Parts remain UNAVAILABLE;
- timeout/cancel/catastrophic full scans publish no replacement snapshot;
- a normally completed full scan may contain per-ItemType errors and publish explicit coverage;
- Goal 03B generation-scoped scan ownership and physical-death gate release.

No Goal 04 feature may create a second path around these rules.

---

## 3. Cache model: one truth, no duplicate mutable snapshot

### L1 metadata

Keep the existing per-RepositoryContext MetadataCache. Do not create a second metadata cache.

### L2 latest statistics

The latest completed full StatisticsSnapshot already published by the statistics service remains the authoritative in-memory statistics snapshot.

Do NOT copy it into another independently mutable cache.

cache.statistics.ttl.seconds becomes a freshness threshold only:
- default 300;
- valid range 0..86400;
- age is always reported;
- when the threshold is exceeded, the snapshot is stale but remains visible;
- 0 means no freshness period: any non-zero age is stale, but the value is still served;
- a stale read never starts a database scan implicitly.

GET requests must never trigger hidden CM/JDBC work merely because a TTL expired.

The dashboard must show the previous snapshot immediately while a refresh is running.

---

## 4. Persistent L3 history

Introduce a typed HistoryStore boundary owned by the application, not by the IBM or repository JDBC layer.

History contains aggregate/read-model data only. Never store:
- document content;
- credentials or Authorization headers;
- JDBC URLs/users;
- raw SQL;
- raw SQLException/vendor messages;
- live IBM/JDBC objects.

Persist only a full scan that reached normal terminal completion and published a StatisticsSnapshot.
A completed scan with per-ItemType ERROR/PARTIAL values may be stored with its coverage.
TIMED_OUT, CANCELLED and catastrophic scans create no history snapshot.

History records must preserve enough context to render the old snapshot without querying CM again:
- repository id and safe display identity;
- database vendor;
- capturedAt, scanStartedAt, scan duration and database anchor date;
- coverage and partial-failure count;
- totals and classification totals;
- per-ItemType id, name, classification, retention policy name when known;
- logical/time metrics with AVAILABLE/UNAVAILABLE/ERROR state;
- Versions/Parts as UNAVAILABLE;
- sanitized warnings/reasons only.

Do not use the context-local scanId as a globally unique persistent key across restarts.
Use a store-owned persistent id and keep scanId only as diagnostic metadata.

### Storage implementation

Preferred V1 persistent implementation: embedded H2 supplied locally under lib/app.

Rules:
- no H2 artifact is downloaded or committed;
- core compilation must not depend on H2 classes;
- discover the driver by class name through java.sql;
- the H2 file lives below data.dir;
- never reuse RepositoryProfile JDBC credentials or the analytics JdbcSession pool;
- local history writes are allowed by SECURITY.md, but repository DB writes remain forbidden;
- the history implementation owns at most one physical H2 connection at a time;
- no per-request ad-hoc connection fan-out;
- schema versioning/migration is explicit and tested;
- transaction failure must not leave a half-written snapshot.

If the H2 driver is absent, history is explicitly UNAVAILABLE while repository activation, metadata, retention and live statistics continue working.

Add a bounded retention setting:
history.max.snapshots.per.repository=1000, valid range 1..100000.
After a successful insert, prune oldest history for that repository to the configured bound.

A real H2 JAR smoke test is separate evidence from dependency-free CI and must be reported honestly if unavailable.

---

## 5. Targeted single-ItemType refresh

The UI must be able to refresh one ItemType without forcing a full scan, but this must not corrupt full-snapshot semantics.

A targeted refresh:
- resolves the ItemType from the active repository metadata;
- reads exactly one database anchor for that targeted operation;
- uses the accepted JdbcStatisticsEngine/physical-schema/counting path;
- has the same cancellation, query-timeout and sanitized-error rules;
- consumes bounded JDBC capacity only;
- must not overlap another full or targeted analytics operation in the same RepositoryContext.

Use the same generation-safe operation gate or an equivalent shared bounded arbiter. Do not create a bypass around ScanCoordinator safety.

Most importantly, a targeted refresh MUST NOT:
- mutate the published full StatisticsSnapshot;
- recalculate or replace dashboard totals;
- be persisted as a full history snapshot;
- make a mixed-anchor snapshot look internally consistent.

Publish targeted results through a separate immutable detail result/cache keyed by ItemType identity and carrying its own capturedAt/anchor/age.

The ItemType Properties view may show that targeted result as fresher detail data while clearly distinguishing it from the full-scan snapshot used by dashboard totals.

---

## 6. Reports: immutable input and safe output

Introduce one immutable report model built from either:
- the current completed full snapshot plus its frozen safe metadata context; or
- one selected persistent history snapshot.

Report generation must not query IBM CM or the repository database to fill missing values.
If a field was not captured/proven, render it as unavailable/unknown rather than doing hidden I/O.

Required formats:
- HTML;
- CSV;
- XLSX.

HTML and CSV must work with JDK-only code.

For XLSX:
- use an approved local library under lib/app if one is already available and can be loaded without compile-time coupling; OR
- implement a minimal dependency-free OOXML writer;
- if neither is completed in this goal, the API/UI must expose XLSX as UNAVAILABLE, not emit CSV with an .xlsx extension or a fake workbook.

No report may contain formulas, macros, external links or active content.

### Output security

All reports are written below reports.dir using generated opaque ids/file names.
No HTTP parameter becomes a filesystem path.

Use temporary-file + atomic/replace move semantics where supported so an interrupted export is not presented as complete.

HTML:
- escape every dynamic value;
- no inline untrusted HTML;
- no external assets.

CSV:
- correct quoting/escaping;
- defend against spreadsheet formula injection for text beginning with =, +, -, @, tab or carriage-return;
- numeric metrics remain numeric where safe.

XLSX:
- write text as text and metrics as numbers;
- never create formula cells from data;
- no external relationships.

Report error messages are sanitized and value-free.

---

## 7. Report/history APIs

All routes remain authenticated except the existing minimal /api/health.

Add at minimum:
GET  /api/history
GET  /api/history/{id}
POST /api/reports
GET  /api/reports
GET  /api/reports/{id}/download
POST /api/statistics/item/{itemTypeId}/refresh

State-changing POST routes require the existing cross-site-form-resistant action-header pattern before any state is read or changed.

Use distinct exact action values, for example:
- report-generate;
- statistics-item-refresh.

Keep the three form-sendable content types refused for state-changing endpoints.

History ids, report ids and ItemType ids are validated as typed identifiers.
No route accepts a raw path, raw SQL, schema name or repository JDBC value.

List APIs use explicit bounded limits and deterministic ordering.
Do not return an unbounded history/report collection.

---

## 8. Web UI

English, responsive and fully offline. No CDN, external fonts, analytics scripts or runtime Internet access.

Required views:
- Repository selection;
- Dashboard;
- ItemTypes;
- ItemType Properties drawer/dialog;
- Retention;
- History;
- Reports;
- System / Diagnostics.

### Dashboard

Show:
- active repository;
- analytics availability;
- latest full-scan capturedAt and age;
- fresh/stale state from cache.statistics.ttl.seconds;
- logical totals and classification totals with coverage;
- explicit partial/error warnings;
- scan phase/progress/rate/duration;
- refresh control.

Never present a covered subtotal as a complete total.

### ItemTypes

Table supports:
- search;
- stable sort;
- classification;
- retention policy;
- statistics status/freshness;
- keyboard-accessible row/detail activation.

Properties view contains General, Statistics and Retention sections.

If a targeted refresh exists, label it as targeted detail data with its own timestamp.
Dashboard totals continue to cite the latest full scan.

### Retention

Read-only policy overview using the accepted IBM adapter. No administration controls.

### History

List persistent full snapshots newest first with bounded pagination/limit.
Allow opening one historical snapshot and generating a report from it.

### Reports

Show available formats/capabilities, generated reports and safe download actions.
Unavailable XLSX must be visible as unavailable rather than silently omitted or faked.

### System / Diagnostics

Show only safe existing diagnostics plus:
- application/Java version;
- active repository;
- CM adapter/runtime status;
- DB vendor/driver readiness;
- CM/JDBC pool metrics;
- scan/draining facts;
- history capability/storage status;
- cache freshness;
- report capability.

Never expose secrets, JDBC URL/user, raw SQL, raw exception text or local secret paths.

---

## 9. Browser/output safety

Prefer DOM textContent and explicit element creation for dynamic values.
Do not inject server-controlled strings through innerHTML.

Keep the existing restrictive CSP and no-store response behavior unless a documented local-static-asset exception is strictly required.

Validate:
- JSON escaping;
- HTML escaping;
- CSV escaping/formula neutralization;
- report file-name/path confinement;
- URL/path parameter decoding;
- error-body sanitation.

No document content is displayed or exported.

---

## 10. Lifecycle and repository switching

History storage is application-local and may outlive RepositoryContext switches.
Repository-specific live caches/results must not.

On repository switch:
- the accepted Goal 03B operation gate drains first;
- targeted-detail cache belonging to the old context is discarded;
- no old-context refresh/report builder may call the new context;
- latest full snapshot semantics remain per context;
- history remains addressable by repository id without activating that repository.

A report captures its immutable input before writing.
It must not hold CM/JDBC leases while rendering a file.

Shutdown closes local history/report resources cleanly and reports failure without weakening repository-resource close semantics.

---

## 11. Configuration and readiness

Activate the previously reserved paths/setting through the existing AppPaths/AppConfig readers:
- data.dir;
- reports.dir;
- cache.statistics.ttl.seconds.

Add:
- history.max.snapshots.per.repository (default 1000; 1..100000).

Do not add passwords to application.properties.

Doctor/config output must distinguish:
- history enabled/disabled by feature;
- H2 driver present/absent;
- history store ready/unavailable;
- data/reports directories writable;
- XLSX available/unavailable and why;
- statistics freshness TTL.

Readiness checks are local only. Doctor and public health must not connect to CM, repository DB or H2 merely to print configuration; actual H2 opening occurs in the application-local history capability lifecycle, not in the public health endpoint.

---

## 12. Testing and adversarial controls

All existing tests remain green.

Add dependency-free tests/fakes for at least:

### Cache/history
- stale full snapshot remains visible and no hidden refresh occurs;
- TTL=0 never discards the snapshot;
- only published full scans reach history;
- timed-out/cancelled scans create zero history rows;
- partial-failure completed scan persists exact coverage;
- two repositories never cross-contaminate history/detail cache;
- retention bound prunes only oldest rows of the same repository;
- failed transaction cannot expose half a snapshot;
- missing H2 leaves history unavailable without breaking repository activation.

### Targeted refresh
- targeted and full scans cannot overlap;
- two targeted refreshes cannot overlap;
- targeted refresh uses one anchor and accepted counting semantics;
- targeted result never changes full snapshot/totals/history;
- repository switch cancels/drains it with Goal 03B safety;
- stale old-generation result cannot publish into a new RepositoryContext.

### Reports
- HTML escapes hostile ItemType/classification/retention strings;
- CSV quotes correctly and neutralizes formula prefixes;
- report file cannot escape reports.dir;
- interrupted/failed export leaves no completed artifact;
- reports never contain JDBC URL/user/credential/raw SQL/raw SQLException text;
- historical report uses stored snapshot only and performs zero CM/JDBC calls;
- XLSX capability is a real workbook or explicitly unavailable.

### API/UI/security
- every new route is authenticated;
- every state-changing route requires the exact action header with zero side effects on refusal;
- list limits are bounded;
- path/id traversal attempts are refused;
- UI renders hostile strings as text, not markup;
- no external runtime URL/CDN appears in static assets;
- repository switch clears old live detail data.

Add mutation/opposite controls where they discriminate the contract rather than restating implementation.

---

## 13. Validation

Run serially or in a private checkout because this Windows/MSYS host has no reliable flock protection for concurrent builds.

At minimum:
- ./build.sh
- ./tests/selftest.sh
- ./tests/shell/run.sh
- ./bin/doctor.sh
- ./build.sh --check-ibm-isolation
- bash tests/shell/analytics_guard.sh
- bash tests/shell/analytics_source_guard_test.sh
- IBM suite against committed stubs
- ./build.sh --require-ibm when the local IBM CM 8.7 SDK is available.

Also verify:
- zero tracked proprietary JARs/credentials;
- no external Web dependency;
- local history/report output cannot modify IBM CM or repository DB;
- no Goal 03/03A/03B regression;
- PR #1 remains draft/unmerged.

If an H2 JAR is locally available, run a real embedded-H2 persistence smoke separately.
If an XLSX library is locally available, validate a generated workbook separately.
Do not describe an absent local-library check as performed.

No live DB2/Oracle validation is currently required to execute Goal 04, but the existing lack of live analytics validation remains an unresolved project risk and must stay documented.

---

## 14. Completion / handoff

At Goal 04 completion:
1. review the complete integrated diff;
2. run all required validation serially;
3. update architecture/security/data-model/requirements/readme/config docs where behavior changed;
4. update STATUS.md with exact evidence;
5. commit coherently;
6. push origin/bootstrap/cm-insight-architecture yourself;
7. verify local HEAD == remote HEAD;
8. verify push and pull_request Actions are SUCCESSFUL for the SAME final SHA;
9. keep PR #1 open/draft/unmerged;
10. set the next goal to NOT YET APPROVED / ARCHITECTURE REVIEW REQUIRED;
11. do NOT execute Goal 05;
12. stop with architecture-review handoff.
