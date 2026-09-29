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

- repositoryId
- itemTypeName
- capturedAt
- totalLogicalItems
- createdToday
- createdLast7Days
- createdLast30Days
- createdCurrentYear
- versions: unavailable until verified
- parts: unavailable until verified
- durationMs
- source
- status
- errorMessage

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

- repositoryId
- capturedAt
- scanStartedAt
- scanDurationMs
- perItemType
- totals
- partialFailureCount

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
