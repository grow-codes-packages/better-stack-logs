# Grow Codes SDK for Better Stack Logs

[![CI](https://github.com/grow-codes-packages/better-stack-logs/actions/workflows/ci.yml/badge.svg)](https://github.com/grow-codes-packages/better-stack-logs/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/grow-codes-packages/better-stack-logs?sort=semver)](https://github.com/grow-codes-packages/better-stack-logs/releases)
[![Docs](https://img.shields.io/badge/docs-grow--codes--packages.github.io-blue)](https://grow-codes-packages.github.io/better-stack-logs/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Grow Codes](https://img.shields.io/badge/Grow%20Codes-grow.codes-2ea44f)](https://grow.codes)

| Language | Package | Coverage |
| - | - | - |
| TypeScript | [![npm](https://img.shields.io/npm/v/@grow-codes/better-stack-logs)](https://www.npmjs.com/package/@grow-codes/better-stack-logs) | [![TypeScript coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/better-stack-logs/badges/coverage-typescript.json)](https://github.com/grow-codes-packages/better-stack-logs/actions/workflows/ci.yml) |
| Rust | [![crates.io](https://img.shields.io/crates/v/grow-better-stack-logs)](https://crates.io/crates/grow-better-stack-logs) [![docs.rs](https://img.shields.io/docsrs/grow-better-stack-logs)](https://docs.rs/grow-better-stack-logs) | [![Rust coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/better-stack-logs/badges/coverage-rust.json)](https://github.com/grow-codes-packages/better-stack-logs/actions/workflows/ci.yml) |
| Go | [![Go Reference](https://pkg.go.dev/badge/github.com/grow-codes-packages/better-stack-logs/go.svg)](https://pkg.go.dev/github.com/grow-codes-packages/better-stack-logs/go) | [![Go coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/better-stack-logs/badges/coverage-go.json)](https://github.com/grow-codes-packages/better-stack-logs/actions/workflows/ci.yml) |
| Python | [![PyPI](https://img.shields.io/pypi/v/grow-better-stack-logs)](https://pypi.org/project/grow-better-stack-logs/) | [![Python coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/better-stack-logs/badges/coverage-python.json)](https://github.com/grow-codes-packages/better-stack-logs/actions/workflows/ci.yml) |
| Java | [![Maven Central](https://img.shields.io/maven-central/v/codes.grow.packages/better-stack-logs)](https://central.sonatype.com/artifact/codes.grow.packages/better-stack-logs) | [![Java coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/better-stack-logs/badges/coverage-java.json)](https://github.com/grow-codes-packages/better-stack-logs/actions/workflows/ci.yml) |

Open-source client libraries for [Better Stack Logs](#trademarks-and-disclaimer) in **TypeScript, Rust, Go,
Python and Java**: one API, five idiomatic packages, tested in Docker against a contract-verified
Better Stack stub.

Better Stack Logs has two hosts with separate credentials, and the SDK keeps them apart:

- **Ingest** (the source's ingesting host, `Authorization: Bearer <source token>`): batched,
  gzip-compressed log shipping as JSON, NDJSON or MessagePack (`dt` timestamps), an OTLP/HTTP logs
  exporter, a logger facade, redaction and adapters for the common logging frameworks of each
  language.
- **SQL query** (the ClickHouse HTTP interface of a Better Stack connection, HTTP Basic):
  `query(sql, params)` with server-side parameter binding, `searchLogs` over hot and cold storage,
  `waitForLog`, a polling `tail` and the logging category's `LogQueryClient`.
- **One client model** in every language: timeouts, retries with jitter, `Retry-After`,
  cancellation, one error model (`BetterStackError` with canonical codes) and credentials that are
  redacted in `toString`, logs, hooks and error messages. A source token is never sent to the query
  host and SQL credentials are never sent to the ingest host.

## Contents

- [Supported service version](#supported-service-version)
- [Install](#install)
- [Quickstart](#quickstart)
- [Configuration](#configuration)
- [Function overview](#function-overview)
- [Testing with Docker](#testing-with-docker)
- [Compatibility](#compatibility)
- [Contributing](#contributing)
- [Security](#security)
- [Licence](#licence)
- [Trademarks and disclaimer](#trademarks-and-disclaimer)
- [About Grow Codes](#about-grow-codes)

## Supported service version

Better Stack Logs is a SaaS, so there is no server version to pin. The SDK implements the
documented [HTTP ingest API](https://betterstack.com/docs/logs/ingesting-data/http/logs/), the
[OpenTelemetry endpoint](https://betterstack.com/docs/logs/open-telemetry/) and the
[SQL query API](https://betterstack.com/docs/logs/query-api/connect-remotely/) (ClickHouse HTTP
interface) as of November 2026. The Grow Codes stack catalog entry is
`local intake stub (contract-verified) + optional live Better Stack source`: CI runs against the
contract-verified stub in `docker/betterstack-stub/`. A live job against a real Better Stack
source is planned and waits for owner-approved credentials; see
[docs/compatibility.md](docs/compatibility.md).

## Install

| Language | Package | Install |
| - | - | - |
| TypeScript (Node.js ≥ 22.12) | `@grow-codes/better-stack-logs` | `npm install @grow-codes/better-stack-logs` |
| Rust (MSRV 1.88) | `grow-better-stack-logs` | `cargo add grow-better-stack-logs` |
| Go (1.26+) | `github.com/grow-codes-packages/better-stack-logs/go` | `go get github.com/grow-codes-packages/better-stack-logs/go` |
| Python (≥ 3.10) | `grow-better-stack-logs` | `pip install grow-better-stack-logs` (`[structlog]` extra for the structlog processor) |
| Java (17+) | `codes.grow.packages:better-stack-logs` | see below |

Maven:

```xml
<dependency>
  <groupId>codes.grow.packages</groupId>
  <artifactId>better-stack-logs</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("codes.grow.packages:better-stack-logs:0.1.0")
```

## Quickstart

Set the source's ingesting host and token (from Better Stack → Sources) and, to read logs back,
the host and credentials of a SQL connection (Better Stack → Integrations → Connect remotely):

```sh
export BETTERSTACK_INGESTING_HOST=s1234.eu-nbg-2.betterstackdata.com   # bare host → https://
export BETTERSTACK_SOURCE_TOKEN=...                                     # never commit real tokens
export BETTERSTACK_QUERY_HOST=eu-nbg-2-connect.betterstackdata.com
export BETTERSTACK_QUERY_USERNAME=... BETTERSTACK_QUERY_PASSWORD=...
export BETTERSTACK_SOURCE_TABLE=t1234_myapp                             # the source's collection
```

### TypeScript

```ts
import { BetterStackClient } from "@grow-codes/better-stack-logs";

const client = BetterStackClient.fromEnv();
try {
  const logs = client.logClient({ resource: { "service.name": "checkout" } });
  logs.logger("orders").info("order placed", { orderId: 42 });
  await logs.flush();

  const record = await client.query().waitForLog("order placed", { timeoutMs: 60_000 });
  console.log(record.severityText, record.body, record.attributes);
} finally {
  await client.close(); // flushes every log client
}
```

### Rust

```rust
use std::time::Duration;
use grow_better_stack_logs::logging::{attrs, LogClient, LogQueryClient, WaitOptions};
use grow_better_stack_logs::{BetterStackClient, LogClientOptions};

#[tokio::main]
async fn main() -> grow_better_stack_logs::Result<()> {
    let client = BetterStackClient::from_env()?;
    let logs = client.log_client(LogClientOptions::default().resource_attribute("service.name", "checkout"))?;
    logs.logger(Some("orders"), attrs! {}).info("order placed", attrs! { "order_id" => 42 });
    logs.flush(None).await?;

    let record = client
        .query()?
        .wait_for_log("order placed", WaitOptions::default().timeout(Duration::from_secs(60)))
        .await?;
    println!("{} {}", record.severity_text, record.body);
    client.close().await;
    Ok(())
}
```

### Go

```go
ctx := context.Background()
client, err := betterstack.NewClientFromEnv()
if err != nil {
	log.Fatal(err)
}
defer client.Close()

logs, err := client.NewLogClient(betterstack.LogClientOptions{
	Resource: map[string]betterstack.AttrValue{"service.name": "checkout"},
})
if err != nil {
	log.Fatal(err)
}
logs.Logger("orders", nil).Info(ctx, "order placed", "order_id", 42)
_ = logs.Flush(ctx)

rec, err := client.Query().WaitForLog(ctx, "order placed", betterstack.WaitForLogOptions{Timeout: time.Minute})
if err != nil {
	log.Fatal(err)
}
fmt.Println(rec.SeverityText, rec.Body)
```

### Python

```python
from grow_better_stack_logs import BetterStackClient

with BetterStackClient.from_env() as client:  # close() flushes every log client
    logs = client.log_client(resource={"service.name": "checkout"})
    logs.logger("orders").info("order placed", {"order_id": 42})
    logs.flush(10)

    record = client.query().wait_for_log("order placed", timeout_s=60)
    print(record.severity_text, record.body, record.attributes)
```

Async code uses `AsyncBetterStackClient` with the same methods (`async with`, `await …`).

### Java

```java
import codes.grow.packages.betterstack.BetterStackClient;
import codes.grow.packages.betterstack.BetterStackLogClientOptions;
import codes.grow.packages.betterstack.logging.WaitForLogOptions;

try (BetterStackClient client = BetterStackClient.fromEnv()) { // close() flushes every log client
  var logs = client.logClient(
      BetterStackLogClientOptions.builder().resource(Map.of("service.name", "checkout")).build());
  logs.logger("orders").info("order placed", Map.of("orderId", 42));
  logs.flush();

  var record = client.query().waitForLog("order placed",
      WaitForLogOptions.of(Duration.ofSeconds(60), Duration.ofSeconds(2)));
  System.out.println(record.severityText() + " " + record.body());
}
```

More complete programs live in [examples/](examples/), one folder per language.

## Configuration

All settings can be passed in code or through environment variables. Precedence: **explicit value >
`GROW_BETTER_STACK_LOGS_*` variable > vendor variable > default.** A present but invalid value raises a
`ConfigError` that names the variable (never its value). The ingest side (`url` + `sourceToken`)
and the SQL side (`queryUrl` + `queryUsername` + `queryPassword`) are each all-or-nothing; one
complete side is enough, and calls that need the missing side raise `ConfigError`.

| Setting | Environment variables (precedence order) | Default |
| - | - | - |
| `url`: the source's ingesting host | `GROW_BETTER_STACK_LOGS_URL`, `BETTERSTACK_INGESTING_URL`, `BETTERSTACK_INGESTING_HOST` | none (required for ingestion) |
| `sourceToken` (secret) | `GROW_BETTER_STACK_LOGS_SOURCE_TOKEN`, `BETTERSTACK_SOURCE_TOKEN` | none |
| `queryUrl`: SQL host of a connection | `GROW_BETTER_STACK_LOGS_QUERY_URL`, `BETTERSTACK_QUERY_URL`, `BETTERSTACK_QUERY_HOST` | none (required for SQL) |
| `queryUsername` | `GROW_BETTER_STACK_LOGS_QUERY_USERNAME`, `BETTERSTACK_QUERY_USERNAME` | none |
| `queryPassword` (secret) | `GROW_BETTER_STACK_LOGS_QUERY_PASSWORD`, `BETTERSTACK_QUERY_PASSWORD` | none |
| `sourceTable`: default collection, e.g. `t1234_myapp` | `GROW_BETTER_STACK_LOGS_SOURCE_TABLE`, `BETTERSTACK_SOURCE_TABLE` | none (`searchLogs` then needs `source`) |
| `encoding`: ingest body `json`, `ndjson` or `msgpack` | `GROW_BETTER_STACK_LOGS_ENCODING` | `json` |
| `connectTimeout` | `GROW_BETTER_STACK_LOGS_CONNECT_TIMEOUT` | 10 s |
| `requestTimeout` (per attempt) | `GROW_BETTER_STACK_LOGS_REQUEST_TIMEOUT` | 30 s |
| `totalTimeout` (including retries) | `GROW_BETTER_STACK_LOGS_TOTAL_TIMEOUT` | 120 s |
| `retry.maxRetries` | `GROW_BETTER_STACK_LOGS_MAX_RETRIES` | 3 |
| `tls.caCertPath` | `GROW_BETTER_STACK_LOGS_CA_CERT` | system roots |
| `tls.clientCertPath` / `tls.clientKeyPath` | `GROW_BETTER_STACK_LOGS_CLIENT_CERT` / `GROW_BETTER_STACK_LOGS_CLIENT_KEY` | none |
| `tls.insecureSkipVerify` (tests only) | `GROW_BETTER_STACK_LOGS_INSECURE_SKIP_VERIFY` | `false` |
| `proxy` | `GROW_BETTER_STACK_LOGS_PROXY` | none (environment proxies are ignored) |
| `headers`, `userAgentSuffix`, `hooks`, `logger` | — | none |

A URL value that contains `://` is used as given; a bare `host[:port]` (the form Better Stack shows
for `BETTERSTACK_INGESTING_HOST` / `BETTERSTACK_QUERY_HOST`) becomes `https://host[:port]`.
Durations accept milliseconds (`1500`) or units (`500ms`, `10s`, `1m30s`); booleans accept
`true/false/1/0/yes/no/on/off`. Field names follow each language's convention
(`source_token` in Python and Rust, `SourceToken` in Go). The full reference is in the
[documentation](https://grow-codes-packages.github.io/better-stack-logs/configuration/).

## Function overview

<!-- service:function-overview — the service fills this section; keep the heading. -->

The same functions exist in every language, named per language convention (`searchLogs` in
TypeScript and Java, `search_logs` in Python and Rust, `SearchLogs` in Go). Every function is
documented with an example in all five languages in the
[documentation](https://grow-codes-packages.github.io/better-stack-logs/functions/).

### Client and status

| Function | Better Stack API | Description |
| - | - | - |
| `fromEnv()` | — | client from `GROW_BETTER_STACK_LOGS_*` / `BETTERSTACK_*` |
| `health()` | `GET {queryUrl}/ping`, else unauthenticated `GET {url}/` | `{healthy, status, details.endpoint}`; never throws for an unhealthy or unreachable service |
| `ping()` | `SELECT count() … FROM remote(<table>_logs) WHERE 1 = 0` (or `SELECT 1`), else `POST {url}/` with `[]` | latency of an authenticated call; `AUTHENTICATION` on a bad token or password |
| `serverVersion()` | — | always `UNSUPPORTED`: the SaaS reports no version |
| `close()` | — | idempotent; flushes log clients, stops tails; later calls raise `CANCELLED` |

<details><summary>Examples in TypeScript, Rust, Go, Python and Java</summary>

TypeScript:

```ts
const health = await client.health(); // { healthy, status, details: { endpoint: "query" } }
console.log(health.healthy, await client.ping(), "ms");
await client.close();
```

Rust:

```rust
let health = client.health().await?;
println!("{} {:?}", health.healthy, client.ping().await?);
client.close().await;
```

Go:

```go
hs, err := client.Health(ctx)
latency, err := client.Ping(ctx)
err = client.Close()
```

Python:

```python
health = client.health()
print(health.healthy, client.ping(), "ms")
client.close()
```

Java:

```java
HealthStatus health = client.health();
System.out.println(health.healthy() + " " + client.ping().toMillis() + " ms");
client.close();
```

</details>

### Ingest

| Function | Better Stack API | Description |
| - | - | - |
| `exporter` / `newExporter({encoding, maxRequestBytes, maxRecordBytes})` | `POST {url}/` | HTTP exporter: JSON array, NDJSON or MessagePack (`dt` as timestamp extension), gzip by default, split at 10 MiB, oversized records rejected |
| `exporter.export(records)` | same | `LogRecord`s → events (`dt`, `message`, `level`, `severity_number`, `span.trace_id`/`span_id`, `resource`, `attributes`) |
| `exporter.sendEvents(events, {encoding, gzip})` | same | raw event objects (nested JSON kept as is) |
| `otlpExporter` / `newOtlpExporter({protocol, scopeName, maxRequestBytes})` | `POST {url}/v1/logs` | OTLP/HTTP protobuf (OTLP/JSON optional); `partial_success` is reported as rejected records |

Ingest errors: `403` → `AUTHENTICATION`, `402 Quota exceeded` → `RATE_LIMITED` (not retryable),
`406`/`413` → `VALIDATION`; 429, 5xx and network errors are retried (Better Stack documents no
deduplication, so a retried batch can arrive twice).

<details><summary>Examples in TypeScript, Rust, Go, Python and Java</summary>

TypeScript:

```ts
await client.newExporter({ encoding: "msgpack" }).sendEvents([{ message: "raw", dt: new Date(), n: 7 }]);
const result = await client.otlpExporter.export([{ timestamp: new Date(), severity: Severity.Info,
  severityText: "INFO", body: "via OTLP", attributes: {}, resource: { "service.name": "api" } }]);
```

Rust:

```rust
let exporter = client.new_exporter(IngestExporterOptions::default().encoding(Encoding::Msgpack))?;
exporter.send_events(&[serde_json::json!({ "message": "raw", "n": 7 })], SendEventsOptions::default()).await?;
let otlp_logs = client.log_client(LogClientOptions::default().protocol(LogProtocol::Otlp))?;
```

Go:

```go
exp, err := client.NewExporter(betterstack.IngestExporterOptions{Encoding: betterstack.EncodingMsgPack})
err = exp.SendEvents(ctx, []map[string]any{{"message": "raw", "n": 7}}, betterstack.SendEventsOptions{})
otlpLogs, err := client.NewLogClientWith(client.OTLPExporter(), betterstack.LogClientOptions{})
```

Python:

```python
client.new_exporter(encoding="msgpack").send_events([{"message": "raw", "dt": datetime.now(timezone.utc), "n": 7}])
otlp_logs = client.log_client(protocol="otlp")
```

Java:

```java
client.newExporter(BetterStackLogExporterOptions.of(IngestEncoding.MSGPACK))
    .sendEvents(List.of(Map.of("message", "raw", "n", 7)));
BatchingLogClient otlpLogs = client.logClient(
    BetterStackLogClientOptions.builder().protocol(LogProtocol.OTLP).build());
```

</details>

### SQL query

| Function | Better Stack API | Description |
| - | - | - |
| `query()` | — | the `BetterStackQuery` client (needs the SQL side) |
| `query(sql, params, {settings})` | `POST {queryUrl}/` (ClickHouse HTTP) | rows; `{name:Type}` placeholders bound server side as `param_<name>`; `FORMAT JSONEachRow` appended when the SQL has no `FORMAT` |
| `queryRaw(sql, params, {settings})` | same | the response text unchanged (any `FORMAT`) |
| `searchLogs({source, contains, start, end, limit, order, cursor})` | `remote(<source>_logs)` UNION ALL `s3Cluster(primary, <source>_s3)` | hot + cold logs, literal substring match, inclusive window (start default now − 24 h, no upper bound without `end`), dedup, opaque cursor |
| `renderSearchSql(options)` | — | the SQL `searchLogs` runs (escaped literals, one runnable statement) |
| `escapeParamValue`, `sqlString`, `jsonEscape` | — | the escaping helpers (TSV parameter text, SQL literal, JSON text) |

SQL errors are mapped by the ClickHouse exception code (header or body, also when it follows a
`200` response): 516 → `AUTHENTICATION`, 497 → `PERMISSION_DENIED`, 60/81 → `NOT_FOUND`, 62/47/… →
`VALIDATION`, 202 → `RATE_LIMITED`, 159/160 → `TIMEOUT`, 48 → `UNSUPPORTED`.

<details><summary>Examples in TypeScript, Rust, Go, Python and Java</summary>

TypeScript:

```ts
const q = client.query();
const rows = await q.query(
  "SELECT count() AS c FROM remote(t1234_myapp_logs) WHERE position(raw, {needle:String}) > 0",
  { needle: "checkout" },
);
const page = await q.searchLogs({ contains: "checkout", start: new Date(Date.now() - 3_600_000), limit: 50 });
```

Rust:

```rust
let q = client.query()?;
let rows = q.query(
    "SELECT count() AS c FROM remote(t1234_myapp_logs) WHERE position(raw, {needle:String}) > 0",
    &query_params! { "needle" => "checkout" },
    &QueryOptions::default(),
).await?;
let page = q.search_logs(SearchLogsOptions::default().contains("checkout").limit(50)).await?;
```

Go:

```go
q := client.Query()
rows, err := q.Query(ctx,
	"SELECT count() AS c FROM remote(t1234_myapp_logs) WHERE position(raw, {needle:String}) > 0",
	map[string]any{"needle": "checkout"})
page, err := q.SearchLogs(ctx, betterstack.SearchLogsOptions{Contains: "checkout", Limit: 50})
```

Python:

```python
q = client.query()
rows = q.query(
    "SELECT count() AS c FROM remote(t1234_myapp_logs) WHERE position(raw, {needle:String}) > 0",
    {"needle": "checkout"},
)
page = q.search_logs(contains="checkout", limit=50)
```

Java:

```java
BetterStackQuery q = client.query();
List<Map<String, Object>> rows = q.query(
    "SELECT count() AS c FROM remote(t1234_myapp_logs) WHERE position(raw, {needle:String}) > 0",
    Map.of("needle", "checkout"));
LogSearchResult page = q.searchLogs(SearchLogsOptions.builder().contains("checkout").limit(50).build());
```

</details>

### Logging category

| Function | Description |
| - | - |
| `logClient({protocol, exporter, otlp, batch, resource, redaction})` | batching `LogClient` over the HTTP ingest API (default) or OTLP: `send`, `sendBatch`, `flush`, `close`, `stats`, `logger` |
| `logger(name).info(message, attributes)`, `child(attributes)` | logger facade with trace correlation from the active OpenTelemetry span |
| `search(query, {start, end, limit, order, cursor})` | `searchLogs` on the configured `sourceTable` (`query` is the `contains` text) |
| `tail(query, onRecord, {start, interval})` | polls `search` (Better Stack has no public streaming API) |
| `listStreams()` | `[sourceTable]`, or `[]` without one |
| `listLabels()` | always `UNSUPPORTED` (Better Stack events have no labels) |
| `waitForLog(query, {timeout, interval})` | polls `search` until a record matches (`TIMEOUT` otherwise) |
| adapters | TypeScript pino + winston transports; Rust `tracing` `LogLayer` + `log` `LogBridge`; Go `slog.Handler` + `zapadapter`; Python `LogHandler` + `structlog_processor`; Java Logback, Log4j2 and `java.util.logging` |

<details><summary>Examples in TypeScript, Rust, Go, Python and Java</summary>

TypeScript:

```ts
const logs = client.logClient({ protocol: "otlp", batch: { flushIntervalMs: 1_000 } });
logs.logger("api").child({ requestId: "r-1" }).warn("slow request", { ms: 950 });
const handle = client.query().tail("slow request", (r) => console.log(r.body), { intervalMs: 2_000 });
handle.stop();
```

Rust:

```rust
let logs = client.log_client(LogClientOptions::default().protocol(LogProtocol::Otlp))?;
logs.logger(Some("api"), attrs! { "request_id" => "r-1" }).warn("slow request", attrs! { "ms" => 950 });
let streams = client.query()?.list_streams().await?;
```

Go:

```go
logs, err := client.NewLogClient(betterstack.LogClientOptions{})
slog.New(betterstack.NewSlogHandler(logs, nil)).Warn("slow request", "ms", 950)
h, err := client.Query().Tail(ctx, "slow request", func(r betterstack.LogRecord) { fmt.Println(r.Body) },
	betterstack.TailOptions{Interval: 2 * time.Second})
h.Stop()
```

Python:

```python
logs = client.log_client(options=BatchOptions(flush_interval_s=1))
logging.getLogger("api").addHandler(LogHandler(logs))
tail = client.query().tail("slow request", lambda r: print(r.body), interval_s=2)
tail.stop()
```

Java:

```java
BatchingLogClient logs = client.logClient();
logs.logger("api").child(Map.of("requestId", "r-1")).warn("slow request", Map.of("ms", 950));
TailHandle handle = client.query().tail("slow request", r -> System.out.println(r.body()),
    TailOptions.defaults());
handle.stop();
```

</details>

<!-- /service:function-overview -->

## Testing with Docker

Better Stack has no self-hosted edition, so the integration suite runs every public function
against the contract-verified Better Stack stub in `docker/betterstack-stub/` (two listeners like
the real service: ingest on `:8080`, ClickHouse-HTTP SQL on `:8123`; behaviour recorded from
Better Stack's public documentation). Logs written through the ingest API are read back through
the SQL API. Each language package is packed and installed into a separate consumer project first,
so the tests use the package exactly as you would.

```sh
make test-docker                     # all five languages
make test-docker LANGS="go python"   # a subset
```

No host ports are published and every run uses its own Compose project, so runs never collide; the
stack is always removed afterwards (`docker compose down -v`). Unit tests need no Docker:
`make test` or `make coverage`.

## Compatibility

| | TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - | - |
| Runtime | Node.js ≥ 22.12, ESM + CJS | stable, MSRV 1.88 | 1.26, 1.27 | 3.10 – 3.14 | 17, 21, 25 |
| Async | `Promise` + `AbortSignal` | `async` (tokio), `blocking` feature | `context.Context` | sync + `asyncio` | sync + `CompletableFuture` |
| Ingest JSON / NDJSON / MessagePack, gzip | yes | yes | yes | yes | yes |
| OTLP protobuf / OTLP JSON | yes / yes | yes / yes | yes / no | yes / yes | yes / yes |
| SQL query, `searchLogs`, `waitForLog`, polling `tail` | yes | yes | yes | yes | yes |
| Tested against | Better Stack stub (Docker) | same | same | same | same |

Cases the stub cannot produce (errors appended to a `200` SQL response, ClickHouse codes 202, 241,
497, 159, 48) are covered by unit tests; the list and the service differences are in
[docs/compatibility.md](docs/compatibility.md).

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) and
[AGENTS.md](AGENTS.md) first. All five languages keep the same API; a change lands in all of them
together. Parts of this repository are generated by
[sdk-kit](https://github.com/grow-codes-packages/sdk-kit) and changed there.

## Security

Please report vulnerabilities privately to [opensource@grow.codes](mailto:opensource@grow.codes).
See [SECURITY.md](SECURITY.md).

## Licence

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attributions.

## Trademarks and disclaimer

This is an independent, community-maintained project. It is **not affiliated with, endorsed or
sponsored by Better Stack, Inc.** Better Stack Logs is a trademark of Better Stack, Inc.; it is used here only to name the
service this SDK connects to. The Better Stack Logs server is licensed separately by its owner
(Proprietary SaaS).

The software is provided "AS IS", without warranties or conditions of any kind. Grow Codes is not
responsible for any damage, data loss, security incident, outage or cost that results from using it.
See [LICENSE](LICENSE) and [NOTICE](NOTICE).

## About Grow Codes

This SDK is built and maintained by [Grow Codes](https://grow.codes). Grow Codes publishes the
client libraries it builds as open source in the
[grow-codes-packages](https://github.com/grow-codes-packages) organisation, generated from one
shared kit so that every service and every language behaves the same way. Learn more at
[grow.codes](https://grow.codes).
