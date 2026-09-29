# Runtime libraries

Do not commit proprietary IBM or Oracle binaries to this public repository.

They are never downloaded by the build either: obtain them from your own licensed IBM/Oracle
installation and place them here.

Expected local layout:

```text
lib/
  ibm/
    cmbicmsdk81.jar
    ...required IBM CM 8.7 SDK/runtime JARs...
  db2/
    db2jcc4.jar
  oracle/
    ojdbc8.jar
  app/
    ...approved offline third-party dependencies...
```

## The IBM CM 8.7 SDK JARs

`cmbicmsdk81.jar` is the one name this project relies on: it is the IBM CM API 8.7 SDK archive that
`tests/ibm-stubs` was verified against, and it is the JAR the adapter's `com.ibm.mm.sdk.*` types come
from.

The SDK installs alongside other IBM JARs (XML mapping and service archives, and a shared `common.jar`
among them). **This project has not verified which of those the CM 8.7 read path actually needs at
runtime**, so no list is asserted here: place the JARs your licensed installation ships for a working
CM API 8.7 client. There is no harm in carrying extras - the launcher adds **every** `*.jar` it finds
in `lib/ibm/` to the class path and reports how many, and the application then decides for itself
whether the adapter is available.

`CM_INSIGHT_IBM_LIBS=<dir>` names an additional directory holding SDK JARs, for the case where they
live outside the repository (a staging directory, a shared read-only mount, a vendor installation). It
is additive to `lib/ibm/`, so a one-off real-SDK check needs no repository change. The launcher warns
when no IBM JAR is on the class path - that is a supported core-only run, not an error - and
`CM_INSIGHT_REQUIRE_IBM=true` turns it into a refusal instead.

Building with `./build.sh --require-ibm` fails unless at least one real SDK JAR is present here; without
that flag a missing SDK falls back to the test-only compile stubs in `tests/ibm-stubs`, which typecheck
the adapter but do not validate it against IBM's runtime.

The launcher adds these directories to the runtime classpath. The bootstrap skeleton itself compiles with JDK 17 only.

## The DB2 and Oracle JDBC drivers (optional, analytics only)

Statistics need one of these, and **nothing else in the application does**. Without a driver JAR the
application starts normally, activates a repository normally, and serves its ItemType and retention
routes normally; the analytics routes (`GET /api/statistics`, `POST /api/statistics/refresh`,
`GET /api/diagnostics/jdbc`) answer the documented `UNAVAILABLE` state and `bin/doctor.sh` /
`--print-config` say which driver is missing. There is no download and no driver JAR in Git: the build
and the whole test suite pass with **zero** DB2/Oracle JARs present.

Accepted driver classes, one per vendor family:

```text
DB2     com.ibm.db2.jcc.DB2Driver                    (lib/db2/*.jar)
Oracle  oracle.jdbc.OracleDriver                     (lib/oracle/*.jar)
        oracle.jdbc.driver.OracleDriver              (legacy jar, accepted as a fallback)
```

The JDBC URL must belong to the same family as `repository.db.vendor` (`jdbc:db2:` for DB2,
`jdbc:oracle:` for Oracle). A mismatch is reported as its own verdict - it is never "try it anyway",
because the wrong driver cannot serve the URL and the driver's own error says less than this check does.

### Readiness is local, and it is not a connection test

Readiness (`JdbcDrivers`, used by `bin/doctor.sh`, `--print-config` and `GET /api/diagnostics/jdbc`)
loads the driver class and inspects `DriverManager` registration. It opens **no** connection, resolves
**no** credential, performs no DNS lookup and touches no database. Consequently:

- a driver being loadable is **not** evidence that a database is reachable, and nothing in this
  application reports it as such;
- `--print-config` and `--validate-config` never open a database connection merely to print or validate
  configuration, and no public health endpoint calls a live database;
- the honest "the driver is installed" statement is exactly that, and the "analytics is unavailable"
  state is where a connection failure surfaces.

The launcher adds `lib/db2` and `lib/oracle` to the class path when they exist, so placing a licensed
driver JAR there is all that is needed. `CM_INSIGHT_JDBC_LIBS=<dir>` names an additional directory for
the same purpose. The database account should hold SELECT-only privileges; application safety does not
depend on that having been done, but it is the recommended second line of defence.

