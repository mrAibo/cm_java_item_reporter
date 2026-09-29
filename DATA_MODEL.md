# Data Model

## RepositoryProfile

- id
- displayName
- ssid
- databaseVendor
- JDBC URL and schema references
- environment-variable names for credentials
- optional ICN base URL

## ItemTypeInfo

- name
- description
- itemTypeId
- classification
- versionControl
- versioningType
- retentionPolicyName
- physical root mapping when resolved

## ItemTypeStatistics

One ItemType's result inside one snapshot (implemented record; every metric is a `MetricValue`).

- repositoryId
- itemTypeId (integer, for cross-referencing the metadata list)
- itemTypeName
- businessClassification (the label already present in metadata; totals are grouped by it)
- scanStartedAt, capturedAt
- logicalItems: distinct `ItemID` total
- createdToday, createdLast7Days, createdLast30Days, createdCurrentYear
- versions: always `UNAVAILABLE` in this goal
- parts: always `UNAVAILABLE` in this goal
- durationMs
- source (for example `jdbc`)
- status: `OK`, `PARTIAL` or `ERROR` - DERIVED from the metrics, never asserted by a producer
- errorMessage: sanitised failure text, or an empty string

`MetricValue` has exactly three states and only one of them carries a number: `AVAILABLE(value)`,
`UNAVAILABLE` and `ERROR`. An unavailable or failed metric can never carry a value, so a count nobody
measured cannot be rendered as `0`. A PARTIAL ItemType is one whose total was measured but whose window
boundaries were not representable.

## RetentionPolicyInfo

- name
- retentionType
- retentionEnabled
- retentionPeriod
- expirationEnabled
- expirationPeriod
- expirationAction
- assignedItemTypes

## StatisticsSnapshot

One completed scan's result: immutable, self-consistent, published atomically or not at all. While a scan
runs the previous completed snapshot stays visible; a cancelled, timed-out or catastrophic scan publishes
nothing and never replaces it with a half-built object.

- repositoryId
- scanId (monotonic per repository context)
- capturedAt, scanStartedAt, scanDurationMs
- anchorDate: the single database current date every window of this snapshot was anchored on
- perItemType: one `ItemTypeStatistics` per frozen ItemType, in the frozen order
- totals: `StatisticsTotals`, grouped by the metadata business classification
- coverage: `StatisticsCoverage` - how much of the frozen list is accounted for, and how completely
- partialFailureCount: ItemTypes that were not measured completely

`StatisticsTotals` carries `logicalItemsTotal` (the sum over the ItemTypes that were measured), the four
coverage counts (`countedItemTypes`, `availableItemTypes`, `partialItemTypes`, `errorItemTypes`),
`complete`, and `byBusinessClassification` (`ClassificationTotals` per label). A total therefore always
travels with the coverage that qualifies it: a subtotal over the ItemTypes that happened to succeed is
never presented as the complete total for the frozen list, and a failed ItemType contributes nothing rather
than zero.

### The API's published shape

`GET /api/statistics` publishes this read model through a value-free port (`web.AnalyticsApi`) rather than
the records above directly, so a field that is not declared in the port cannot appear in a response. The
JSON carries the analytics state and its reason, the active repository id, the scan block (running, phase,
started/finished, duration, total/completed/failed, rate, current ItemType, failure reason), the snapshot
with `capturedAt`/`ageMillis`, the coverage block, the totals with their classification groups, and one
object per ItemType whose metrics are `{"available":true,"state":"AVAILABLE","value":N}` or
`{"available":false,"state":"UNAVAILABLE","value":null,...}`. There is no staleness threshold: the age is
reported, not judged.

## Fixed counting semantics

A logical item is one distinct IBM CM `ItemID`, not one physical root row, one version or one resource
part. IBM's component-root table has both `ItemID` and `VersionID`, and IBM's versioning contract says
all versions of one item share the same ItemID. Goal 03 therefore deduplicates ItemID before counting.

For a segmented ItemType the logical ItemID set is the union of every expected root segment from 1 through
the ItemType's current `SegmentID`. A missing or ambiguous segment makes that ItemType's statistics an
error; it is never silently skipped.

### Creation-date windows

The creation date of a logical item comes from the immutable ItemID generated at item creation. IBM
documents positions 9-14 as:

- position 9: century (`A=20`, `B=21`);
- positions 10-11: two-digit year;
- position 12: month (`A=01` through `L=12`);
- positions 13-14: day.

That fixed-width six-character date key is chronologically sortable for the documented 2000-2199 range.
A scan reads the database current date once and encodes its boundaries into the same representation. If a
required boundary cannot be represented by the documented encoding, the affected time metrics are
`UNAVAILABLE`; no alternative encoding is guessed.

The windows are calendar windows in the database's current-date domain:

- `createdToday`: [today, tomorrow)
- `createdLast7Days`: [today - 6 days, tomorrow)
- `createdLast30Days`: [today - 29 days, tomorrow)
- `createdCurrentYear`: [January 1 of current year, January 1 of next year)

The root-table `CreateTS` means the timestamp when that physical entry was created. Because a versioned
logical item can have multiple root entries, Goal 03 must not use root-row CreateTS as a substitute for
original logical-item creation without new independent proof.

Evidence:
- https://www.ibm.com/docs/en/content-manager/8.7.0?topic=tables-icmutnnnnnsss-component-roots
- https://www.ibm.com/docs/en/content-manager/8.7.0?topic=tables-icmstitems-items
- https://www.ibm.com/docs/en/content-manager/8.7.0?topic=concepts-versioning

## Unresolved: Versions

Do not implement guessed SQL. Record exact semantics, DB2 query, Oracle query and verification evidence here before enabling.

## Unresolved: Parts

Do not implement guessed SQL. Record whether resource parts or all parts count, DB2 query, Oracle query and verification evidence here before enabling.

## Historical snapshots

Persist aggregate statistics only. Never store document content, passwords or unnecessary user metadata.
