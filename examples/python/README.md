# Python examples

Install the package, point it at a running Better Stack Logs and run an example:

```bash
pip install grow-better-stack-logs
export GROW_BETTER_STACK_LOGS_URL=http://127.0.0.1:8080
export GROW_BETTER_STACK_LOGS_TOKEN=...   # when the service needs one
python examples/python/basic.py
```

| File | Shows |
| - | - |
| `basic.py` | sync client: health, ping, server version, error handling |
| `async_basic.py` | async client, cancellation by task |
| `hooks_and_logging.py` | request hooks, the `grow_better_stack_logs` logger, retries and timeouts |
