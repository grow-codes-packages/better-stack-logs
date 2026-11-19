# Java examples

Install the package into your local Maven repository, point it at a Better Stack source (and optionally
an SQL connection) and run an example:

```bash
make -C ../../java pack            # builds and installs codes.grow.packages:better-stack-logs
export GROW_BETTER_STACK_LOGS_URL=https://s1234.eu-nbg-2.betterstackdata.com   # the source's ingesting host
export GROW_BETTER_STACK_LOGS_SOURCE_TOKEN=...
export GROW_BETTER_STACK_LOGS_QUERY_URL=https://eu-nbg-2-connect.betterstackdata.com   # SQL API (optional)
export GROW_BETTER_STACK_LOGS_QUERY_USERNAME=... GROW_BETTER_STACK_LOGS_QUERY_PASSWORD=...
export GROW_BETTER_STACK_LOGS_SOURCE_TABLE=t1234_myapp
mvn -q compile exec:java                                                  # BasicExample
mvn -q compile exec:java -Dexec.mainClass=codes.grow.packages.betterstack.examples.AsyncExample
```

Against the published package, set `-Dsdk.version=<version>` (or edit `pom.xml`) instead of
installing it locally.

| Class | Shows |
| - | - |
| `BasicExample` | health, ping, log client + Logger facade, `waitForLog`, `searchLogs`, `query` with bound parameters, error handling |
| `AsyncExample` | `…Async` methods (health, ping, searchLogs, query), combining futures, cancellation |
| `HooksAndLoggingExample` | request hooks, an SDK logger, retries and timeouts, MessagePack ingest, OTLP, `java.util.logging` handler |
