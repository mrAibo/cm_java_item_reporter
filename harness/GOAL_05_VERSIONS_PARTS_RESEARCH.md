# Goal 05 - Versions and Parts correctness research gate

## Objective

Resolve the two intentionally deferred metrics before implementing them.

This is a research-and-proof goal first, implementation second.

Use separate subagents for IBM CM data-model research, DB2 proof, Oracle proof, and independent review.

## Required evidence

For Versions:

- precise semantic definition
- relevant CM 8.7 tables/API behavior
- DB2 query
- Oracle query
- treatment of current/historical versions
- proof against trusted CM data

For Parts:

- precise semantic definition
- whether all parts or resource parts are counted
- relevant CM 8.7 tables/API behavior
- DB2 query
- Oracle query
- proof against trusted CM data

## Rules

Do not infer table relationships from names alone.
Do not enable a metric because a query merely returns plausible numbers.
Do not mutate CM data.
If DB2 or Oracle cannot be independently verified, leave that database's metric unavailable and document why.

## Implementation after proof

Only after the evidence is written into DATA_MODEL.md:

- implement dialect-safe queries
- integrate into ItemTypeStatistics
- add totals
- add report/UI columns
- add tests
- measure added scan cost and decide whether Versions/Parts belong to normal refresh or a heavier refresh class

## Acceptance

Either the metrics are implemented with documented verification, or the goal explicitly concludes that one/both remain unavailable. Both outcomes are valid if evidence is honest. Update STATUS.md with the next extension selected by the user.
