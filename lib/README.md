# Runtime libraries

Do not commit proprietary IBM or Oracle binaries to this public repository.

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

The launcher adds these directories to the runtime classpath. The bootstrap skeleton itself compiles with JDK 17 only.
