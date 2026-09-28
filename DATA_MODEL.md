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

Logical items means ItemID-level logical objects, not versions and not resource parts.

Time-window metrics refer to logical items created in the specified period. Exact physical timestamp SQL must be verified against CM 8.7 before production enablement.

## Unresolved: Versions

Do not implement guessed SQL. Record exact semantics, DB2 query, Oracle query and verification evidence here before enabling.

## Unresolved: Parts

Do not implement guessed SQL. Record whether resource parts or all parts count, DB2 query, Oracle query and verification evidence here before enabling.

## Historical snapshots

Persist aggregate statistics only. Never store document content, passwords or unnecessary user metadata.
