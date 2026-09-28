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
