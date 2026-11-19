# grow-better-stack-logs (Python)

Python SDK for [Better Stack Logs](https://grow-codes-packages.github.io/better-stack-logs/) by Grow Codes.
Unofficial and community maintained; not affiliated with Better Stack, Inc. "Better Stack" is a
trademark of Better Stack, Inc.

```bash
pip install grow-better-stack-logs            # structlog support: grow-better-stack-logs[structlog]
```

Better Stack has two hosts with separate credentials: the source's **ingesting host** (source
token, `Authorization: Bearer`) and the **SQL query host** (ClickHouse HTTP interface of a
connection, HTTP Basic). Configure one side or both:

| Field | Environment (first match wins after an explicit value) |
| - | - |
| `url` | `GROW_BETTER_STACK_LOGS_URL`, `BETTERSTACK_INGESTING_URL`, `BETTERSTACK_INGESTING_HOST` |
| `source_token` | `GROW_BETTER_STACK_LOGS_SOURCE_TOKEN`, `BETTERSTACK_SOURCE_TOKEN` |
| `query_url` | `GROW_BETTER_STACK_LOGS_QUERY_URL`, `BETTERSTACK_QUERY_URL`, `BETTERSTACK_QUERY_HOST` |
| `query_username` / `query_password` | `GROW_BETTER_STACK_LOGS_QUERY_USERNAME` / `_PASSWORD`, `BETTERSTACK_QUERY_USERNAME` / `_PASSWORD` |
| `source_table` | `GROW_BETTER_STACK_LOGS_SOURCE_TABLE`, `BETTERSTACK_SOURCE_TABLE` |
| `encoding` | `GROW_BETTER_STACK_LOGS_ENCODING` (`json` default, `ndjson`, `msgpack`) |

A bare `host[:port]` becomes `https://host[:port]`.

```python
from grow_better_stack_logs import BatchOptions, BetterStackClient

with BetterStackClient.from_env() as client:
    logs = client.log_client(resource={"service.name": "checkout"},
                             options=BatchOptions(flush_interval_s=1.0))
    log = logs.logger("checkout").child({"region": "eu"})
    log.info("order placed", {"order_id": 42})        # batched, gzip, never blocks
    logs.flush(10)

    q = client.query()
    record = q.wait_for_log("order placed", timeout_s=60, interval_s=2)
    page = q.search_logs(contains="order", limit=50)   # hot + cold storage, newest first
    rows = q.query(
        "SELECT count() AS c FROM remote(t1234_myapp_logs) WHERE position(raw, {m:String}) > 0",
        {"m": "order"},                                # bound server side (param_m)
    )
```

| Area | API |
| - | - |
| Client | `BetterStackClient` / `AsyncBetterStackClient`: `health()`, `ping()`, `server_version()` (always `UnsupportedError`), `close()` |
| Ingest | `client.exporter`, `client.new_exporter(encoding=, max_request_bytes=, max_record_bytes=)`: `send_events(events, encoding=, gzip=)`, `export(records)`; `to_better_stack_event`, `encode_events`, `encode_msgpack` |
| OTLP | `client.otlp_exporter`, `client.new_otlp_exporter(protocol="protobuf" or "json")`: `send`, `export` to `{url}/v1/logs` |
| Log client | `client.log_client(protocol="http" or "otlp", exporter=, options=, resource=, redaction=)`: `send`, `send_batch`, `flush`, `close`, `stats`, `logger()`; `LogHandler`, `structlog_processor` |
| Query | `client.query()`: `query(sql, params, settings=)`, `query_raw`, `search_logs(source=, contains=, start=, end=, limit=, order=, cursor=)`, `render_search_sql`, `search`, `wait_for_log`, `tail`, `list_streams`, `list_labels` (unsupported) |

Errors: ingest `403` → `AuthenticationError`, `402` → `RateLimitError(retryable=False)`,
`406`/`413` → `ValidationError`; SQL errors map by ClickHouse exception code (516 →
`AuthenticationError`, 60 → `NotFoundError`, 62/47 → `ValidationError`, …) even when the code follows
a 200 answer. Exports retry on 429, 5xx and network errors (Better Stack does not deduplicate, so a
retry after a lost answer can store a record twice).

- Python 3.10 or newer, typed (`py.typed`), `httpx` based, environment proxies ignored unless
  configured. Sync methods take `cancel=` (a `CancelToken`); async calls cancel with their task.
- Retries with full-jitter backoff and `Retry-After`, connect/request/total timeouts, hooks and a
  silent-by-default `logging` logger named `grow_better_stack_logs`.
- Credentials are wrapped in `Secret` and never appear in `repr()`, logs or error messages.
- `grow_better_stack_logs.testing` ships in-memory fakes and the conformance runner.

Documentation: https://grow-codes-packages.github.io/better-stack-logs/ ·
Source: https://github.com/grow-codes-packages/better-stack-logs · Licence: Apache-2.0
