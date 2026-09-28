# Implementation Plan

## Phase 0 - Bootstrap

- architecture contract
- Java 17 dependency-free skeleton
- bash build/run/doctor/status lifecycle
- STATUS.md checkpoint protocol
- Harness goals

## Phase 1 - Core runtime

- robust configuration loader
- repository profile loader
- bounded generic pool and metrics
- RepositoryManager / RepositoryContext lifecycle
- safe Basic Auth and Web router
- offline UI shell
- unit/self-tests without IBM libraries

## Phase 2 - IBM CM adapter

- local IBM SDK classpath support
- CmSession wrapper around DKDatastoreICM
- bounded CmSessionPool
- health validation / stale rotation / reconnect
- metadata listing
- ItemType details
- retention viewer using proven CM_retention patterns

## Phase 3 - JDBC analytics

- JDBC driver discovery
- bounded JdbcPool
- DB2 and Oracle dialects
- physical root resolver
- verified logical-item/time-window aggregate SQL
- parallel scan coordinator
- per-ItemType partial failure handling
- pool/scan metrics

## Phase 4 - Cache, history and reports

- statistics cache
- persistent H2 history if approved library is supplied locally
- snapshots
- HTML / CSV / XLSX exports
- retention overview report

## Phase 5 - Web UX

- dashboard
- repository selector
- ItemType table
- search/sort/filter
- ItemType Properties drawer
- retention page
- scan progress/freshness
- system diagnostics
- polished offline responsive UI

## Phase 6 - Versions / Parts research gate

No implementation until semantics and queries are independently verified and DATA_MODEL.md is updated.

## Phase 7 - Later extensions

- ItemID/PID lookup
- ICN deep links
- optional retention administration extracted/adapted from CM_retention
- adaptive concurrency only after real measurements

Every phase ends with tests, docs and STATUS.md update.
