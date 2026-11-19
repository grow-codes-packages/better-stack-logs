"""Hooks, logging, retries, timeouts and a polling tail. Hook data and log fields are redacted."""

from __future__ import annotations

import logging
import time

import grow_better_stack_logs as sdk


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    logging.getLogger(sdk.LOGGER_NAME).setLevel(logging.DEBUG)  # silent unless enabled

    hooks = sdk.Hooks(
        on_request=lambda info: print(f"-> {info.method} {info.url} (attempt {info.attempt})"),
        on_response=lambda info: print(f"<- {info.status} in {info.duration_ms:.0f} ms"),
        on_error=lambda info: print(f"!! {info.error} retry={info.will_retry}"),
    )
    config = sdk.BetterStackConfig.from_env(
        hooks=hooks,
        request_timeout=5.0,
        total_timeout=30.0,
        retry=sdk.RetryPolicy(max_retries=2, initial_backoff=0.2),
        user_agent_suffix="example/1.0",
        encoding="ndjson",
    )
    print(config)  # credentials print as [REDACTED]
    with sdk.BetterStackClient(config) as client:
        print(client.health())
        tail = client.query().tail("tail-example", lambda r: print("tail:", r.body))
        client.exporter.send_events([{"message": "tail-example hello"}])
        time.sleep(5)
        tail.stop()


if __name__ == "__main__":
    main()
