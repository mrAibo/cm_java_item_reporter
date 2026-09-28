# CM Insight

CM Insight is a fast, read-only-first, modular administration and analytics console for IBM Content Manager Enterprise Edition 8.7.

## Baseline

- single JVM / modular monolith
- Java 17 LTS / OpenJDK-compatible runtime
- no Maven, Gradle, Spring, containers or microservices
- no CDN / Internet dependency at runtime
- IBM CM Java API for metadata and retention
- bounded JDBC parallelism for high-volume statistics
- DB2 and Oracle support
- reusable hard-bounded connection pools
- read-only V1/V2
- retention administration reserved for a later, disabled-by-default module

CM_Item_Reporter, CM_Migrator and CM_retention are sources of proven ideas and reusable code, not architectural authorities for this repository.

## Bootstrap

The bootstrap branch contains the architecture contract, operational shell scripts, a dependency-free Java skeleton and DeepSeek Harness execution goals. The skeleton compiles without IBM CM JARs; IBM-specific adapters are implemented behind interfaces in later work packages.

## Build and run

```bash
cp conf/application.properties.example conf/application.properties
./build.sh
./bin/doctor.sh
./bin/start.sh
./bin/status.sh
```

Default development binding: 127.0.0.1:8080.

## Read first

1. VISION.md
2. REQUIREMENTS.md
3. ARCHITECTURE.md
4. DATA_MODEL.md
5. SECURITY.md
6. IMPLEMENTATION_PLAN.md
7. DEEPSEEK_HARNESS_PLAN.md
8. STATUS.md
9. harness/MASTER_GOAL.md
