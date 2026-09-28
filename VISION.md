# Vision

CM Insight is a fast, read-only-first administration and analytics console for IBM Content Manager 8.7.

The product starts as an advanced ItemType reporter but is intentionally structured as a modular administration console so additional CM capabilities can be added without rewriting the core.

## Primary operator flow

1. Authenticate.
2. Select one configured repository.
3. Immediately see cached status and statistics.
4. Trigger a bounded parallel refresh when desired.
5. Inspect ItemType properties and retention policy.
6. Generate reports.
7. Inspect system and connection-pool diagnostics.

Only one repository context is active for a user workflow at a time. Switching repository must close the previous context before the next is activated.

## V1/V2 non-goals

- document mutation
- retention mutation
- document delete
- content viewing
- Resource Manager administration
- microservices / containers
- Maven / Gradle / Spring
- Internet/CDN runtime dependencies

Read-only retention inspection belongs in V1. Administration functions from CM_retention are a later optional module.
