# Python examples

Install the package, point it at a Better Stack source (ingest) and a SQL connection (query), then
run an example:

```bash
pip install grow-better-stack-logs
export GROW_BETTER_STACK_LOGS_URL=https://s1234.eu-nbg-2.betterstackdata.com   # source ingesting host
export GROW_BETTER_STACK_LOGS_SOURCE_TOKEN=...
export GROW_BETTER_STACK_LOGS_QUERY_URL=https://eu-nbg-2-connect.betterstackdata.com
export GROW_BETTER_STACK_LOGS_QUERY_USERNAME=... GROW_BETTER_STACK_LOGS_QUERY_PASSWORD=...
export GROW_BETTER_STACK_LOGS_SOURCE_TABLE=t1234_myapp
python examples/python/basic.py
```

The vendor variables `BETTERSTACK_SOURCE_TOKEN`, `BETTERSTACK_INGESTING_HOST`,
`BETTERSTACK_QUERY_HOST`, `BETTERSTACK_QUERY_USERNAME`, `BETTERSTACK_QUERY_PASSWORD` and
`BETTERSTACK_SOURCE_TABLE` work too.

| File | Shows |
| - | - |
| `basic.py` | log client + Logger, `logging` handler, raw events (MessagePack), `wait_for_log`, `search_logs`, SQL with bound parameters, typed errors |
| `async_basic.py` | async client, OTLP log client, cancellation by task |
| `hooks_and_logging.py` | request hooks, the `grow_better_stack_logs` logger, retries, timeouts, `tail` |
