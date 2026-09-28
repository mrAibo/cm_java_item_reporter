# Goal 02 - IBM CM adapter, ItemTypes and retention viewer

## Objective

Implement the IBM Content Manager 8.7 read-only adapter and retention viewer using the local IBM SDK libraries.

Use subagents for: CM_Migrator connection-pattern review, CM_retention API extraction review, and IBM-specific test/review.

## Source material

Inspect working code in:

- mrAibo/CM_Migrator
  - CMConnection.java
  - CMConnectionPool.java
  - relevant diagnostics
- mrAibo/CM_retention
  - CmService.java
  - Config.java
  - policy/itemtype read paths

Adapt, do not blindly copy.

## Deliverables

### IBM adapter boundary

Implement an adapter wrapping DKDatastoreICM without leaking IBM SDK objects into business DTOs.

Required behaviors:

- connect using selected RepositoryProfile + environment/secret credentials
- isConnected/health validation
- disconnect + destroy on close
- no credentials in logs
- connection age/operation lifecycle integrated with shared bounded pool
- no connection created outside the pool for normal application work

### Metadata

Implement:

- list ItemTypes
- ItemType details mapped to ItemTypeInfo
- description
- ItemType ID
- classification
- version control
- versioning type
- retention policy name
- metadata cache

Use IBM API rather than direct SQL where API is suitable.

### Retention viewer

Adapt proven CM_retention logic for read-only use:

- list policies
- retrieve policy details
- assigned ItemTypes
- ItemType -> policy mapping
- policy -> ItemTypes mapping

Expose business DTOs only.

No create/assign/unassign/delete/backfill endpoints in this goal.

### Repository connection pool

Create the actual CmSessionPool backed by DKDatastoreICM and the shared bounded pool design.

Expose pool diagnostics.

### Web/API

Add authenticated endpoints for:

- repositories
- repository select/status
- ItemTypes
- ItemType properties
- retention policies
- retention policy details
- system/CM diagnostics

Provide clear "IBM adapter unavailable" diagnostics if required local JARs/config are absent.

## Testing

- keep all dependency-free tests working
- add IBM adapter compile gate when SDK JARs are present
- add adapter tests that can run without live CM through boundaries/fakes where possible
- if a real CM 8.7 environment is available, perform a read-only smoke test and record exact result in STATUS.md
- never claim live test success when only mocks were used

## Acceptance

ItemTypes and retention data can be read through the new shared architecture. No write method is exposed. Update STATUS.md; next goal Goal 03.
