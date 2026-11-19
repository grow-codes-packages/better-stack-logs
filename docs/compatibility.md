# Compatibility

## Runtimes

| Language | Supported | Tested in CI |
| - | - | - |
| TypeScript | Node.js ≥ 22.12, ESM and CommonJS | Node 22, 24 |
| Rust | MSRV 1.88, edition 2021, tokio | stable, 1.88 |
| Go | Go ≥ 1.26 | 1.26, 1.27 |
| Python | ≥ 3.10 | 3.10, 3.13, 3.14 |
| Java | ≥ 17 (Java 17 bytecode) | Temurin 17, 21, 25 |

## Better Stack Logs versions

Better Stack Logs is a SaaS without a version to pin (`serverVersion()` raises `UNSUPPORTED`). The
SDK implements the documented HTTP ingest API, the OpenTelemetry endpoint and the SQL query API
(ClickHouse HTTP interface) as of November 2026:

- [HTTP logs ingestion](https://betterstack.com/docs/logs/ingesting-data/http/logs/)
- [OpenTelemetry](https://betterstack.com/docs/logs/open-telemetry/)
- [SQL over HTTP / connect remotely](https://betterstack.com/docs/logs/query-api/connect-remotely/)
- [ClickHouse HTTP interface](https://clickhouse.com/docs/interfaces/http)

There is no self-hosted Better Stack. The Docker suite runs against the **contract-verified stub**
in `docker/betterstack-stub/` (stack catalog entry `local intake stub (contract-verified) +
optional live Better Stack source`): a Python standard-library server with an ingest listener
(`:8080`) and a ClickHouse-HTTP SQL listener (`:8123`), whose behaviour follows Better Stack's
public documentation (see its `README.md`). The stub is test-only and is not shipped in any
package.

!!! warning "Live SaaS checks pending"
    Nothing in CI runs against the real Better Stack service yet. A release-gate live job (send to
    a real source, read the rows back through a real SQL connection) waits for owner-approved
    credentials. Until it runs, these behaviours are taken from the documentation and are
    unverified against the SaaS: server-side `param_<name>` binding through Better Stack's SQL
    proxy, gzip on the raw ingest endpoint, `GET /ping` on the SQL host, the empty-batch `[]` ping
    (and whether it is billed), the HTTP status of SQL authentication failures (401 vs 403), the
    precision of `dt` and the ingest-to-query delay (the live job allows 60 s and serialises
    polling, because the SQL API allows 4 concurrent queries per account).

## What the stub cannot produce

Every public function is called against the stub in the Docker suite. These **cases** cannot be
produced by it and are covered by unit tests in all five languages:

| Case | Reason |
| - | - |
| a ClickHouse exception appended to a `200` SQL response (error after streaming started) | the stub never fails after sending a 200 |
| ClickHouse codes 202 `TOO_MANY_SIMULTANEOUS_QUERIES`, 241 `MEMORY_LIMIT_EXCEEDED`, 497 `ACCESS_DENIED`, 159/160 timeouts, 48 `NOT_IMPLEMENTED` | the stub has no limits, ACL or timeouts; only 516, 60, 47 and 62 are produced for real |
| OTLP `partial_success` with rejected records | the stub accepts every OTLP record |
| hot/cold overlap (dedup of `remote(…_logs)` and `s3Cluster(…_s3)`) | stub cold storage is always empty |
| the real service's headers and statuses beyond the documented ones (for example `Retry-After` values chosen by Better Stack, a 401-vs-403 split on the SQL host) | the stub only knows the documented behaviour; `429`/`503` with `Retry-After` are produced through the stub's own fault control (`POST /__stub/fault`), not by Better Stack semantics |
| HTTPS, mutual TLS and proxies | plain HTTP fixture; the shared transport unit tests cover them |
| UNIX nanosecond `dt` precision | the stub stores microseconds |

No function raises `UNSUPPORTED` except `serverVersion()` and `listLabels()` (no such concept in
Better Stack).

## Service behaviour the SDK relies on or smooths over

| Better Stack behaviour | What the SDK does |
| - | - |
| two hosts with separate credentials (ingest: per-source host + Bearer token; SQL: per-region host + Basic) | two transports; a source token never goes to the SQL host and Basic credentials never go to the ingest host |
| no default ingest host (the legacy `in.logs.betterstack.com` is not used) | `url` is required for ingestion (`CONFIG` otherwise) |
| ingest `403` means an invalid source token | `AUTHENTICATION` (service override of the generic 403 → `PERMISSION_DENIED`) |
| `402 Quota exceeded` | `RATE_LIMITED` with `retryable = false` |
| requests ≤ 10 MiB compressed, records ≤ 10 MiB | batches split before 10 MiB; oversized records rejected before sending |
| no deduplication | exports are retried on 429/5xx/network errors; a retried batch can be stored twice |
| ClickHouse errors carry an exception code that is more precise than the HTTP status | the code wins (header, else body); also detected after a `200` |
| ClickHouse parses `param_<name>` values in the escaped (TSV) text format | string values are escaped (`\` `\t` `\n` `\r`) before sending, so a backslash survives |
| `searchLogs` must stay one runnable statement | it renders escaped SQL literals (`'…'`, `toDateTime64('…', 6, 'UTC')`); only `query(sql, params)` binds server side |
| hot (`remote(…_logs)`) and cold (`s3Cluster(primary, …_s3)`) storage | `searchLogs` queries both (`UNION ALL`) and deduplicates on `(dt, raw)` |
| an unset upper time bound can miss rows the server stamps a few µs later | no upper bound when `end` is unset; `BETWEEN` (inclusive) when given |
| `contains` text is stored inside JSON strings | `contains` is JSON-escaped before `position(raw, …)` |
| no streaming API (Live tail is dashboard-only), no stream listing on the data plane, no labels | `tail` polls `search`; `listStreams` returns `[sourceTable]`; `listLabels` raises `UNSUPPORTED` |
| no health endpoint on the ingest host | `health` uses `GET /ping` on the SQL host, else an unauthenticated `GET /` on the ingest host (below 500 = reachable) |
| 64-bit integers are quoted in ClickHouse JSON output | rows keep them as strings (`"c": "5"`) |

## OTLP scope name

The OTLP exporter sets the instrumentation scope (`ScopeLogs.scope.name`) to the package name of
each language, with the SDK version as `scope.version`. TypeScript, Rust, Python and Java let you
override it (`scopeName`); Go uses the constant `LogScopeName`.

| Language | Scope name |
| - | - |
| TypeScript | `@grow-codes/better-stack-logs` (npm package) |
| Rust | `grow-better-stack-logs` (crate, `DEFAULT_SCOPE_NAME`) |
| Go | `grow-codes-better-stack-logs` (`LogScopeName`) |
| Python | `grow-better-stack-logs` (PyPI distribution, `DEFAULT_SCOPE_NAME`) |
| Java | `grow-codes-better-stack-logs` (`OtlpLogs.DEFAULT_SCOPE_NAME`) |

## Skipped conformance scenarios

| Scenario | Reason |
| - | - |
| — | none; `core.health`, `logging.batch`, `logging.redaction` and `logging.roundtrip` pass in all five languages (capability `query`, no `version`) |

## Language differences

The API is the same in every language; only the conventions differ (naming, durations, async
model). Specific to this SDK:

| Topic | Difference |
| - | - |
| OTLP/JSON | Go sends OTLP/HTTP protobuf only and has no `newOtlpExporter` (fixed scope name); the other languages also send OTLP/JSON (`protocol: "json"`) |
| exporter accessors | TypeScript and Python expose `exporter` / `otlpExporter` as properties, Go `Exporter()` / `OTLPExporter()` (nil without ingest credentials), Rust `exporter()` / `otlp_exporter()` (`Result`), Java `exporter()` / `otlpExporter()` |
| query accessor | Go `Query()` returns nil without SQL credentials (check `client.Config().HasQuery()` first); the other languages raise `CONFIG` from `query()` |
| log client over a custom exporter | Go `NewLogClientWith(exporter, opts)`, Rust `log_client_with(exporter, opts)`, Java `logClient(exporter, options)`, Python `log_client(exporter=…)`, TypeScript `new BetterStackLogClient(exporter, options)` |
| `searchLogs` with SQL settings | Rust `search_logs_with(options, query_options)`; the other languages use `query` / `queryRaw` with `settings` for custom SQL |
| environment | Java cannot change the process environment, so `loadEnv` sets **system properties** instead and returns the names it set |
