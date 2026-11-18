# grow-better-stack-logs (Python)

Python SDK for [Better Stack Logs](https://grow-codes-packages.github.io/better-stack-logs/) by Grow Codes.
Unofficial and community maintained; "Better Stack Logs" is a trademark of Better Stack, Inc..

```bash
pip install grow-better-stack-logs
```

```python
from grow_better_stack_logs import BetterStackClient, BetterStackConfig

# Reads GROW_BETTER_STACK_LOGS_URL, GROW_BETTER_STACK_LOGS_TOKEN, ... from the environment.
with BetterStackClient.from_env() as client:
    print(client.health())
    print(client.server_version())
```

The async client has the same methods:

```python
import asyncio
from grow_better_stack_logs import AsyncBetterStackClient

async def main() -> None:
    async with AsyncBetterStackClient.from_env() as client:
        print(await client.health())

asyncio.run(main())
```

- Python 3.10 or newer, typed (`py.typed`), `httpx` based, environment proxies ignored unless
  configured.
- Retries with full-jitter backoff and `Retry-After`, connect/request/total timeouts,
  cancellation, hooks and a silent-by-default `logging` logger named `grow_better_stack_logs`.
- Credentials are wrapped in `Secret` and never appear in `repr()`, logs or error messages.
- `grow_better_stack_logs.testing` ships in-memory fakes and the conformance runner.

Documentation: https://grow-codes-packages.github.io/better-stack-logs/ ·
Source: https://github.com/grow-codes-packages/better-stack-logs · Licence: Apache-2.0
