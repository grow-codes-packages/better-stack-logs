# Troubleshooting

## The client cannot connect

- `health()` returns `healthy: false, status: "unreachable"`: a host is wrong or unreachable.
  `health()` checks the SQL host (`GROW_BETTER_STACK_LOGS_QUERY_URL` / `BETTERSTACK_QUERY_HOST`)
  when it is configured, otherwise the ingesting host (`GROW_BETTER_STACK_LOGS_URL` /
  `BETTERSTACK_INGESTING_HOST`). There is no default host. A bare `host[:port]` gets `https://`;
  use a full `http://…` URL for a local stub (inside Docker, `127.0.0.1` is the container itself).
- Calls fail with `UNAVAILABLE` after several seconds: the client retried with backoff. Lower
  `maxRetries` or `totalTimeout` to fail faster.
- Behind a corporate proxy: `HTTP_PROXY`/`HTTPS_PROXY` are **ignored** on purpose. Set `proxy`
  (or `GROW_BETTER_STACK_LOGS_PROXY`).

## `CONFIG` errors

The message names the setting or environment variable, never its value. Typical causes: a URL
without `http://` or `https://`, a duration such as `10 sec` (use `10s` or `10000`), a client
certificate without its key, `MAX_RETRIES` below 0.

## `AUTHENTICATION` or `PERMISSION_DENIED`

- `ping()` is the quickest credential check: it makes one authenticated call.
- Ingest calls use the **source token** (`GROW_BETTER_STACK_LOGS_SOURCE_TOKEN` /
  `BETTERSTACK_SOURCE_TOKEN`) of the source whose ingesting host is in `url`; a token of another
  source answers `403` → `AUTHENTICATION`.
- SQL calls use the **connection** username and password (`…_QUERY_USERNAME`, `…_QUERY_PASSWORD`),
  not a source token or an API token; a wrong password is ClickHouse code 516 → `AUTHENTICATION`.
- Explicit config values win over `GROW_BETTER_STACK_LOGS_*`, which win over `BETTERSTACK_*`.
- The SDK never prints credentials, so `toString()` of the config shows `[REDACTED]` even when a
  token is set.

## `CONFIG: … required for ingestion` or `… required for SQL queries`

The client has only one side configured. Ingestion needs `url` + `sourceToken`; `query()`,
`searchLogs`, `waitForLog` and `tail` need `queryUrl` + `queryUsername` + `queryPassword`, and
`search`/`searchLogs` without a `source` need `sourceTable`.

## `NOT_FOUND` from a query

ClickHouse code 60 (`UNKNOWN_TABLE`): the collection name is wrong. Use the source's
`<team_id>_<table_name>` collection (`t1234_myapp`) with the `_logs` (hot) or `_s3` (cold) suffix,
as shown in Better Stack's SQL connection page.

## A log line does not show up in `searchLogs` / `waitForLog`

- Ingestion is asynchronous: allow up to a minute and use `waitForLog` (polls every second).
- `searchLogs` defaults to the last 24 h; pass `start` for older records.
- `contains` is a case-sensitive literal substring of the raw event JSON.
- `RATE_LIMITED` with code 202: more than 4 concurrent SQL queries per account; poll less often.

## `RATE_LIMITED` from ingestion that is not retried

`402 Quota exceeded`: the account reached its quota or spending limit. It is not retried; raise the
limit in Better Stack or drop logs (`onDrop`).

## TLS errors

- Self-signed or private CA: set `tls.caCertPath` (`GROW_BETTER_STACK_LOGS_CA_CERT`).
- Mutual TLS: set both `tls.clientCertPath` and `tls.clientKeyPath`.
- `tls.insecureSkipVerify` only for local tests.

## `RATE_LIMITED`

The service asked the client to slow down. The client waits for `Retry-After` up to
`maxRetryAfter` (60 s); longer waits fail at once with `retryAfter` set on the error so your code
can schedule a later attempt.

## `TIMEOUT`

`requestTimeout` (30 s) bounds one attempt and `totalTimeout` (120 s) the whole call including
retries. Raise them for slow operations, or pass a per-call deadline (signal, ctx, cancel token,
future timeout).

## See what the client does

Turn on the [logger](hooks.md#logger) or add [hooks](hooks.md#hooks): both see every attempt with
redacted headers and URLs.

## Docker integration tests fail

- Run one language: `make test-docker LANGS=go`.
- In CI, download the `compose-logs-<lang>` artifact of the failed job.
- Locally, the stack is removed after each run; rerun with Docker logs open
  (`docker compose -f docker/compose.yml logs -f`) in a second terminal.

## Still stuck?

Open an issue with the language, package version, the Better Stack region and a minimal reproduction
(with secrets removed): <https://github.com/grow-codes-packages/better-stack-logs/issues>.
