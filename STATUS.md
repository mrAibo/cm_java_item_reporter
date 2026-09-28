# Project Status

> This file is the canonical cross-session checkpoint. Update it at the end of every substantial implementation goal and before handing work to another chat/model.

## Project

- Name: CM Insight
- Repository: mrAibo/cm_java_item_reporter
- Stage: Bootstrap / architecture
- Active branch: bootstrap/cm-insight-architecture
- Last known commit: to be filled after bootstrap commit
- Runtime target: Java 17 LTS OpenJDK-compatible
- Build model: javac + jar + bash
- Product mode: read-only V1/V2

## Completed

- Requirements discussion completed.
- Existing CM_Item_Reporter reviewed for UI/statistics ideas.
- Existing CM_Migrator reviewed for connection pooling, concurrency and operational lifecycle ideas.
- Existing CM_retention reviewed for retention API and DB2/Oracle root mapping ideas.
- Architecture decisions captured in repository documentation.
- Decision: hard-bounded pools; no emergency connections.
- Decision: IBM CM API for metadata/retention, JDBC fast path for aggregate statistics.
- Decision: retention viewer in V1; retention administration later and disabled by default.
- Decision: Versions and Parts are explicit research gates; no guessed SQL.

## Current implementation state

Bootstrap files are being created. No production IBM CM adapter or JDBC statistics implementation exists yet.

## Next goal

Implement and validate the dependency-free core runtime skeleton, then execute Harness Goal 01.

## Open technical questions

1. Verify exact logical-item aggregate SQL on DB2 and Oracle.
2. Define and verify Versions semantics.
3. Define and verify Parts semantics.
4. Decide exact persistent history implementation once approved local libraries are available.
5. Benchmark safe production parallelism on a representative Library Server.

## Handoff protocol

When resuming in another session:

1. Read this file.
2. Read ARCHITECTURE.md and IMPLEMENTATION_PLAN.md.
3. Inspect git status/log and confirm Last known commit.
4. Do not repeat completed architecture work.
5. Continue from Next goal.
6. Before stopping, update this file with:
   - branch and commit
   - completed work
   - tests/build results
   - unresolved failures
   - next exact goal
   - any architecture decision changed with user approval
