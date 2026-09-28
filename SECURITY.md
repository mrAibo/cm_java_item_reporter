# Security

## Trust model

V1/V2 are read-only with respect to IBM CM and the Library Server. The application may only write its own local cache/history/report/run files.

## Web binding

Default binding is 127.0.0.1.

Development credentials admin/admin are allowed only on loopback. Startup must refuse non-loopback binding while default credentials are active.

## Credentials

Repository passwords are supplied through environment variables or a local secret file with restrictive permissions. Profile files contain references to secret names, not passwords.

Never log passwords, Authorization headers or JDBC credential strings.

## Dependencies

Do not commit proprietary IBM or Oracle JAR files to the public repository.

No runtime CDN or external font dependency.

## Future retention administration

Must remain disabled by default and requires a separate security review, CSRF protection, audit logging, dry-run/preview, explicit confirmation and post-operation verification before activation.
