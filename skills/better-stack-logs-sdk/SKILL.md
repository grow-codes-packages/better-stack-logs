---
name: better-stack-logs-sdk
description: Add and use the Grow Codes SDK for Better Stack Logs (`better-stack-logs`) in TypeScript, Rust, Go, Python or Java projects. Use when code must ship logs to Better Stack (source ingesting host, source token, JSON/NDJSON/MessagePack, OTLP) or read them back through the Better Stack SQL API (ClickHouse HTTP, query, searchLogs, waitForLog) - installing @grow-codes/better-stack-logs, the grow-better-stack-logs crate or PyPI package, the Go module github.com/grow-codes-packages/better-stack-logs/go or Maven codes.grow.packages:better-stack-logs, configuring it with GROW_BETTER_STACK_LOGS_* variables, handling BetterStackError codes, testing with the bundled fakes, or running the Docker integration suite of this repository.
---

# Better Stack Logs SDK (Grow Codes)

One API for Better Stack Logs in five languages (categories: logging). Pick the package for the
project's language, configure it from the environment, and handle errors by their canonical code.

## 1. Add the package

| Language | Command | Import |
| - | - | - |
| TypeScript | `npm install @grow-codes/better-stack-logs` | `import { BetterStackClient } from "@grow-codes/better-stack-logs"` |
| Rust | `cargo add grow-better-stack-logs` (add `tokio` with `macros,rt-multi-thread`) | `use grow_better_stack_logs::BetterStackClient;` |
| Go | `go get github.com/grow-codes-packages/better-stack-logs/go` | `betterstack "github.com/grow-codes-packages/better-stack-logs/go"` |
| Python | `pip install grow-better-stack-logs` (or `uv add grow-better-stack-logs`) | `from grow_better_stack_logs import BetterStackClient` |
| Java | Maven `codes.grow.packages:better-stack-logs` (Gradle `implementation("codes.grow.packages:better-stack-logs:<version>")`) | `import codes.grow.packages.betterstack.BetterStackClient;` |

Check the latest version in the README badges before pinning one.

## 2. Configure

Better Stack has two hosts with separate credentials. Configure the side(s) you need; each side is
all-or-nothing, and there is no default host. Prefer environment configuration so no credential is
written into code:

```sh
# Ingest side: the source's ingesting host + source token (Better Stack → Sources)
export BETTERSTACK_INGESTING_HOST=s1234.eu-nbg-2.betterstackdata.com   # bare host → https://
export BETTERSTACK_SOURCE_TOKEN=...            # from your secret store, never committed
# SQL side: a connection (Integrations → Connect remotely) + the source's collection
export BETTERSTACK_QUERY_HOST=eu-nbg-2-connect.betterstackdata.com
export BETTERSTACK_QUERY_USERNAME=... BETTERSTACK_QUERY_PASSWORD=...
export BETTERSTACK_SOURCE_TABLE=t1234_myapp
```

| Setting | `GROW_BETTER_STACK_LOGS_*` | Vendor variables |
| - | - | - |
| `url` | `…_URL` | `BETTERSTACK_INGESTING_URL`, `BETTERSTACK_INGESTING_HOST` |
| `sourceToken` | `…_SOURCE_TOKEN` | `BETTERSTACK_SOURCE_TOKEN` |
| `queryUrl` | `…_QUERY_URL` | `BETTERSTACK_QUERY_URL`, `BETTERSTACK_QUERY_HOST` |
| `queryUsername` / `queryPassword` | `…_QUERY_USERNAME` / `…_QUERY_PASSWORD` | `BETTERSTACK_QUERY_USERNAME` / `BETTERSTACK_QUERY_PASSWORD` |
| `sourceTable` | `…_SOURCE_TABLE` | `BETTERSTACK_SOURCE_TABLE` |
| `encoding` (`json` default, `ndjson`, `msgpack`) | `…_ENCODING` | — |

Precedence: explicit config value > `GROW_BETTER_STACK_LOGS_*` > `BETTERSTACK_*` > default. A value
with `://` is used as given (use `http://…` for a local stub).
Other variables: `…_CONNECT_TIMEOUT`, `…_REQUEST_TIMEOUT`, `…_TOTAL_TIMEOUT` (`10s`, `1500`),
`…_MAX_RETRIES`, `…_CA_CERT`, `…_CLIENT_CERT`, `…_CLIENT_KEY`, `…_PROXY`. Environment proxies
(`HTTPS_PROXY`) are ignored unless `proxy` is set.

## 3. Use

Create one client per process and close it on shutdown (TypeScript `await client.close()`, Rust
`client.close().await`, Go `defer client.Close()`, Python `with …`/`close()`, Java
try-with-resources).

- `health()` never throws for an unhealthy service; check `healthy`.
- `ping()` verifies credentials (`AUTHENTICATION` error when they are wrong): a `SELECT … WHERE
  1 = 0` on the SQL host, or an empty batch `[]` on the ingest host for ingest-only clients.
- `serverVersion()` always raises `UNSUPPORTED` (SaaS). Calls after `close()` raise `CANCELLED`.
- All I/O is retried with jitter on 429/502/503/504 and connection errors; only idempotent calls
  are retried on 500.
- Errors carry `code` (`CONFIG`, `AUTHENTICATION`, `PERMISSION_DENIED`, `NOT_FOUND`, `CONFLICT`,
  `RATE_LIMITED`, `VALIDATION`, `UNAVAILABLE`, `TIMEOUT`, `UNSUPPORTED`, `CANCELLED`), `retryable`,
  `httpStatus` and `requestId`. Branch on the code, not on the message text. Go:
  `errors.Is(err, betterstack.ErrNotFound)`.

### Logging

Create the log client once, send records with `send`/`sendBatch` or use the logger facade
(`logger("component").info("message", attrs)`) or the framework adapter for the language (pino or
winston, `tracing`/`log`, `slog`/zap, `logging`/structlog, Logback/Log4j2/JUL). Records are batched
and redacted; call `close()` on shutdown so the queue is flushed.

<!-- service:skill-usage — the service adds its own functions and examples here. -->

### Better Stack specifics

- **Ship logs**: `client.logClient({resource})` (Go `NewLogClient`, Rust/Python `log_client`) over
  the HTTP ingest API; `protocol: "otlp"` sends OTLP/HTTP to `/v1/logs` instead. Raw events:
  `client.newExporter({encoding}).sendEvents([{message, dt, …}])`.
- **Read back**: `client.query()` (Go `Query()`, nil without SQL credentials):
  `waitForLog(text, {timeout})` in tests, `searchLogs({contains, start, end, limit, order,
  cursor})` (hot + cold storage, newest first, last 24 h by default), `query(sql, params)` for
  custom SQL with `{name:Type}` placeholders, e.g.
  `SELECT count() AS c FROM remote(t1234_myapp_logs) WHERE position(raw, {needle:String}) > 0`.
  Tables are `remote(<collection>_logs)` and `s3Cluster(primary, <collection>_s3)`; columns `dt`
  and `raw` (the event JSON).
- Never build SQL by string concatenation with user input: pass values as `params` (sent as
  `param_<name>`, escaped for ClickHouse) or use `sqlString(...)` for literals. Collections are
  identifiers (`^[A-Za-z_][A-Za-z0-9_]*$`).
- Errors: ingest `403` → `AUTHENTICATION` (wrong source token), `402` → `RATE_LIMITED` with
  `retryable = false` (quota); SQL errors follow the ClickHouse code (516 → `AUTHENTICATION`,
  60 → `NOT_FOUND`, 62/47 → `VALIDATION`, 202 → `RATE_LIMITED`).
- The SQL API allows 4 concurrent queries per account: poll at 1 s or more.
- `tail` polls `search`; `listStreams()` returns `[sourceTable]`; `listLabels()` raises
  `UNSUPPORTED`.

| Language | Ship + read back |
| - | - |
| TypeScript | `const logs = client.logClient(); logs.logger("app").info("hi"); await logs.flush(); await client.query().waitForLog("hi")` |
| Rust | `let logs = client.log_client(LogClientOptions::default())?; logs.logger(None, attrs! {}).info("hi", attrs! {}); logs.flush(None).await?; client.query()?.wait_for_log("hi", WaitOptions::default()).await?` |
| Go | `logs, _ := client.NewLogClient(betterstack.LogClientOptions{}); logs.Logger("app", nil).Info(ctx, "hi"); _ = logs.Flush(ctx); client.Query().WaitForLog(ctx, "hi", betterstack.WaitForLogOptions{})` |
| Python | `logs = client.log_client(); logs.logger("app").info("hi"); logs.flush(10); client.query().wait_for_log("hi")` |
| Java | `var logs = client.logClient(); logs.logger("app").info("hi", Map.of()); logs.flush(); client.query().waitForLog("hi", WaitForLogOptions.defaults())` |

## 4. Test code that uses the SDK

Use the in-memory fakes from the testing module instead of a real Better Stack Logs in unit tests:
TypeScript `@grow-codes/better-stack-logs/testing`, Rust feature `testing`, Go package `testkit`, Python
`grow_better_stack_logs.testing`, Java `codes.grow.packages.betterstack.testing`.

## 5. Work on this repository

Read `AGENTS.md` first. Short version: the five languages keep the same API; files with the
`Generated by sdk-kit` header are changed in sdk-kit, not here; `make lint coverage` must pass
(≥ 90 % lines and branches); `make test-docker LANGS="<lang>"` runs the integration suite against
the contract-verified Better Stack stub in `docker/betterstack-stub/` (ingest `:8080`, SQL `:8123`).
