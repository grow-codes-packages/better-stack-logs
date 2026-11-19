# ruff: noqa: PT018
"""Client surface against the Better Stack fixture plus the core conformance scenarios."""

from __future__ import annotations

import asyncio
from typing import Any

import pytest
from factories import CORE_CAPABILITIES, async_core_client, core_client, ingest_only_client

import grow_better_stack_logs as sdk
from grow_better_stack_logs.testing import ConformanceRunner, assert_scenario, pytest_params


def test_config_from_env_reads_both_sides(env: dict[str, str]) -> None:
    cfg = sdk.BetterStackConfig.from_env(env)
    assert cfg.has_ingest and cfg.has_query
    assert cfg.source_table == env["BETTERSTACK_SOURCE_TABLE"]
    text = f"{cfg!r} {cfg!s}"
    assert env["BETTERSTACK_SOURCE_TOKEN"] not in text
    assert env["BETTERSTACK_QUERY_PASSWORD"] not in text
    assert cfg.validate() is cfg


def test_bare_ingesting_host_becomes_https(env: dict[str, str]) -> None:
    cfg = sdk.BetterStackConfig.from_env(
        {
            "BETTERSTACK_INGESTING_HOST": env["BETTERSTACK_INGESTING_HOST"],
            "BETTERSTACK_SOURCE_TOKEN": "x-token",
        }
    )
    assert cfg.url == f"https://{env['BETTERSTACK_INGESTING_HOST']}"


def test_health_via_query_ping(client: sdk.BetterStackClient) -> None:
    status = client.health()
    assert status.healthy, status
    assert status.details["endpoint"] == "query"


def test_health_of_ingest_only_client(env: dict[str, str]) -> None:
    with ingest_only_client(env) as c:
        status = c.health()
    assert status.healthy, status
    assert status.details["endpoint"] == "ingest"


def test_ping_with_sql_credentials_and_source_token_only(
    client: sdk.BetterStackClient, env: dict[str, str]
) -> None:
    assert client.ping() >= 0
    with ingest_only_client(env) as c:
        assert c.ping() >= 0
    with core_client(env, source_table=None) as c:  # SELECT 1 without a table
        assert c.ping() >= 0


def test_wrong_source_token_is_authentication(env: dict[str, str]) -> None:
    wrong = "wrong-source-token-integration"
    with (
        ingest_only_client(env, source_token=wrong) as c,
        pytest.raises(sdk.AuthenticationError) as info,
    ):
        c.ping()
    assert info.value.retryable is False
    assert wrong not in str(info.value) and wrong not in repr(info.value)


def test_wrong_query_password_is_authentication(env: dict[str, str]) -> None:
    wrong = "wrong-query-password-integration"
    with (
        core_client(env, query_password=wrong) as c,
        pytest.raises(sdk.AuthenticationError) as info,
    ):
        c.ping()
    assert wrong not in str(info.value)


def test_server_version_is_unsupported(client: sdk.BetterStackClient) -> None:
    assert "version" not in CORE_CAPABILITIES
    with pytest.raises(sdk.UnsupportedError):
        client.server_version()


def test_close_is_idempotent_and_blocks_log_clients(env: dict[str, str]) -> None:
    c = core_client(env)
    c.close()
    c.close()
    with pytest.raises(sdk.CancelledError):
        c.log_client()
    with pytest.raises(sdk.CancelledError):
        c.ping()


def test_async_client_surface(env: dict[str, str]) -> None:
    async def scenario() -> None:
        async with async_core_client(env) as c:
            assert (await c.health()).healthy
            assert await c.ping() >= 0
            with pytest.raises(sdk.UnsupportedError):
                await c.server_version()
        async with sdk.AsyncBetterStackClient(
            sdk.BetterStackConfig(
                url=env["BETTERSTACK_INGESTING_URL"], source_token=env["BETTERSTACK_SOURCE_TOKEN"]
            )
        ) as ingest:
            status = await ingest.health()
            assert status.healthy and status.details["endpoint"] == "ingest"
            assert await ingest.ping() >= 0

    asyncio.run(scenario())


@pytest.mark.parametrize("scenario", pytest_params(categories=["core"]))
def test_core_conformance(env: dict[str, str], scenario: dict[str, Any]) -> None:
    runner = ConformanceRunner(
        {"core": lambda ctx: core_client(env)}, capabilities=CORE_CAPABILITIES
    )
    assert_scenario(runner, scenario)
