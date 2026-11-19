"""Factories the integration suite and the conformance runner use (owned by this repository).

Capabilities of the Better Stack fixture:

* ``query`` - records are read back through the SQL query host (ClickHouse HTTP interface).
* no ``version`` - Better Stack is a SaaS without a server version (``server_version`` raises
  ``UnsupportedError``, asserted in ``test_core.py``).
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

import grow_better_stack_logs as sdk
from grow_better_stack_logs.testing import LoggingTarget
from grow_better_stack_logs.testing.logging_conformance import batch_options_for

URL_VAR = "GROW_BETTER_STACK_LOGS_URL"

#: Capabilities of the deployment under test for the core scenarios ("version" = serverVersion).
CORE_CAPABILITIES: set[str] = set()

#: Capabilities for the logging scenarios: query, tail, streams, labels.
LOGGING_CAPABILITIES: set[str] = {"query"}

#: Clients created by :func:`logging_target`; closed by the ``close_clients`` fixture.
OPEN_CLIENTS: list[sdk.BetterStackClient] = []

FAST_RETRY = sdk.RetryPolicy(max_retries=2, initial_backoff=0.02, max_backoff=0.2)


def core_client(env: Mapping[str, str], **overrides: Any) -> sdk.BetterStackClient:
    """A client with both sides configured from the test environment (/bootstrap/env)."""
    overrides.setdefault("retry", FAST_RETRY)
    return sdk.BetterStackClient.from_env(env, **overrides)


def async_core_client(env: Mapping[str, str], **overrides: Any) -> sdk.AsyncBetterStackClient:
    overrides.setdefault("retry", FAST_RETRY)
    return sdk.AsyncBetterStackClient.from_env(env, **overrides)


def ingest_only_client(env: Mapping[str, str], **overrides: Any) -> sdk.BetterStackClient:
    """A client with only the ingest side (url + source token)."""
    values: dict[str, Any] = {
        "url": env["BETTERSTACK_INGESTING_URL"],
        "source_token": env["BETTERSTACK_SOURCE_TOKEN"],
        "retry": FAST_RETRY,
    }
    values.update(overrides)
    return sdk.BetterStackClient(sdk.BetterStackConfig(**values))


def logging_target(env: Mapping[str, str], scenario: Mapping[str, Any]) -> LoggingTarget:
    """A batching log client over the ingesting host plus the SQL query client."""
    client = core_client(env)
    OPEN_CLIENTS.append(client)
    logs = client.log_client(
        options=batch_options_for(scenario),
        resource={"service.name": "grow-conformance-python", "deployment.environment": "test"},
    )
    return LoggingTarget(client=logs, query=client.query())
