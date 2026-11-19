"""Better Stack clients (sync + async): health, ping, close, sides; polling helpers."""

from __future__ import annotations

import asyncio
import time
from typing import Any

import pytest

import grow_better_stack_logs as sdk
from _support import (
    MockServer,
    Reply,
    bs_config,
    closed_port_url,
    fixture_reply,
    make_config,
    spawn_capturing,
)

SRC = "src-token-123"
PW = "query-pass-456"


def client(
    url: str | None = None, query_url: str | None = None, **kw: Any
) -> sdk.BetterStackClient:
    return sdk.BetterStackClient(bs_config(url, query_url, **kw))


def aclient(
    url: str | None = None, query_url: str | None = None, **kw: Any
) -> sdk.AsyncBetterStackClient:
    return sdk.AsyncBetterStackClient(bs_config(url, query_url, **kw))


# --------------------------------------------------------------------------- sync


def test_health_via_query_ping(server: MockServer) -> None:
    server.enqueue("/ping", fixture_reply("health.query_ping"))
    with client(server.url, server.url) as c:
        assert c.health() == sdk.HealthStatus(True, "ok", {"endpoint": "query"})
        assert SRC not in repr(c)
        assert PW not in repr(c)
    assert "Authorization" not in server.seen[0].headers
    server.enqueue("/ping", Reply(503, "down"))
    with client(query_url=server.url) as c:
        assert c.health() == sdk.HealthStatus(
            False, "unhealthy", {"endpoint": "query", "httpStatus": "503"}
        )
        server.enqueue("/ping", Reply(200, ""))
        assert c.health().status == "ok"


def test_health_of_ingest_only_client(server: MockServer) -> None:
    server.enqueue("/", fixture_reply("health.ingest_get_root"))
    with client(server.url) as c:
        status = c.health()
        assert status.healthy
        assert status.details == {"endpoint": "ingest", "httpStatus": "405"}
        server.enqueue("/", Reply(502, "bad gateway"))
        assert c.health() == sdk.HealthStatus(
            False, "unhealthy", {"endpoint": "ingest", "httpStatus": "502"}
        )
    assert server.seen[0].method == "GET"
    assert "Authorization" not in server.seen[0].headers
    assert len(server.seen) == 2  # health never retries


def test_core_health_unreachable() -> None:
    """core.health.unreachable"""
    for c in (client(closed_port_url()), client(query_url=closed_port_url())):
        status = c.health()
        assert status.healthy is False
        assert status.status == "unreachable"
        assert "error" in status.details
        c.close()


def test_health_timeout_is_unreachable(server: MockServer) -> None:
    server.enqueue("/ping", Reply(200, "Ok.", delay=1.0))
    c = client(query_url=server.url, request_timeout=0.1)
    assert c.health().status == "unreachable"


def test_health_raises_only_cancelled_and_config(server: MockServer) -> None:
    c = client(server.url)
    c.close()
    with pytest.raises(sdk.CancelledError):
        c.health()
    token = sdk.CancelToken()
    token.cancel()
    with pytest.raises(sdk.CancelledError):
        client(query_url=server.url).health(cancel=token)
    server.enqueue("/", Reply(401, "no"))
    status = client(server.url).health()  # any answer below 500 means reachable
    assert status.healthy
    bad = client(server.url)
    bad._ingest = sdk.HttpTransport(make_config("ftp://svc"))
    with pytest.raises(sdk.ConfigError):
        bad.health()
    server.enqueue("/ping", Reply(200, "Ok.", delay=0.5))
    q = client(query_url=server.url, request_timeout=5)
    q._sql = sdk.HttpTransport(make_config("ftp://svc"))
    with pytest.raises(sdk.ConfigError):
        q.health()


def test_ping_with_query_credentials(server: MockServer) -> None:
    server.enqueue("/", fixture_reply("ping.fallback_count"))
    with client(query_url=server.url, source_table="t1_grow_sdk") as c:
        assert c.ping() >= 0
    seen = server.seen[0]
    assert (
        seen.body
        == b"SELECT count() AS c FROM remote(t1_grow_sdk_logs) WHERE 1 = 0 FORMAT JSONEachRow"
    )
    assert seen.headers["Authorization"].startswith("Basic ")
    server.enqueue("/", Reply(200, '{"ok":1}\n'))
    with client(query_url=server.url) as c:
        c.ping()
    assert server.seen[1].body == b"SELECT 1 AS ok FORMAT JSONEachRow"


def test_ping_ingest_only_posts_empty_batch(server: MockServer) -> None:
    server.enqueue("/", Reply(202, b""))
    with client(server.url) as c:
        assert c.ping() >= 0
    seen = server.seen[0]
    assert (seen.method, seen.body) == ("POST", b"[]")
    assert seen.headers["Authorization"] == f"Bearer {SRC}"
    assert seen.headers["Content-Type"] == "application/json"


def test_ping_bad_credentials(server: MockServer) -> None:
    server.enqueue("/", fixture_reply("send.403_bad_token"))
    with pytest.raises(sdk.AuthenticationError) as info:
        client(server.url).ping()
    assert info.value.http_status == 403
    assert info.value.retryable is False
    server.enqueue("/", fixture_reply("query.401_bad_password"))
    with pytest.raises(sdk.AuthenticationError) as info2:
        client(query_url=server.url).ping()
    assert PW not in str(info2.value)


def test_server_version_is_unsupported(server: MockServer) -> None:
    with pytest.raises(sdk.UnsupportedError):
        client(server.url).server_version()
    assert server.seen == []


def test_sides_require_their_credentials(server: MockServer) -> None:
    ingest = client(server.url)
    with pytest.raises(sdk.ConfigError, match="query_url"):
        ingest.query()
    query = client(query_url=server.url)
    for fn in (
        lambda: query.exporter,
        lambda: query.otlp_exporter,
        query.new_exporter,
        query.new_otlp_exporter,
        query.log_client,
    ):
        with pytest.raises(sdk.ConfigError, match="source_token"):
            fn()
    with pytest.raises(sdk.ConfigError):
        ingest.log_client(protocol="grpc")  # type: ignore[arg-type]


def test_core_close_idempotent(server: MockServer) -> None:
    """core.close.idempotent"""
    c = client(server.url)
    stopped: list[int] = []

    class Res:
        def stop(self) -> None:
            stopped.append(1)

    c.resources.register(Res())
    c.close()
    c.close()
    assert stopped == [1]
    c.resources.register(Res())  # registering after close stops immediately
    assert stopped == [1, 1]
    with pytest.raises(sdk.ConfigError, match="closed"):
        c.log_client()


def test_close_flushes_log_clients(server: MockServer) -> None:
    server.defaults["/"] = Reply(202, b"")
    c = client(server.url)
    logs = c.log_client(options=sdk.BatchOptions(flush_interval_s=60, max_batch_bytes=1 << 30))
    assert logs.processor.options.max_batch_bytes == sdk.MAX_REQUEST_BYTES
    assert isinstance(logs.exporter, sdk.BetterStackLogExporter)
    logs.logger().info("flushed on close")
    with c:
        pass
    assert b"flushed on close" in __import__("gzip").decompress(server.seen[-1].body)
    otlp = client(server.url).log_client(protocol="otlp")
    assert isinstance(otlp.exporter, sdk.BetterStackOtlpLogExporter)


def test_from_env(server: MockServer, monkeypatch: pytest.MonkeyPatch) -> None:
    env = {
        "BETTERSTACK_INGESTING_URL": server.url,
        "BETTERSTACK_SOURCE_TOKEN": SRC,
    }
    with sdk.BetterStackClient.from_env(env) as c:
        assert c.config.url == server.url
        assert c.config.has_ingest and not c.config.has_query
    monkeypatch.setenv(sdk.ENV_PREFIX + "QUERY_URL", server.url)
    monkeypatch.setenv(sdk.ENV_PREFIX + "QUERY_USERNAME", "u")
    monkeypatch.setenv(sdk.ENV_PREFIX + "QUERY_PASSWORD", PW)
    with sdk.BetterStackClient() as c2:
        assert c2.config.query_url == server.url
    with pytest.raises(sdk.ConfigError):
        sdk.BetterStackClient.from_env({sdk.ENV_PREFIX + "URL": "nope://x"})


# --------------------------------------------------------------------------- async


async def test_async_client(server: MockServer, monkeypatch: pytest.MonkeyPatch) -> None:
    server.enqueue("/ping", Reply(200, "Ok.\n"))
    server.enqueue("/", Reply(200, '{"c":"0"}\n'))
    async with aclient(server.url, server.url, source_table="t1") as c:
        assert (await c.health()).details == {"endpoint": "query"}
        assert await c.ping() >= 0
        with pytest.raises(sdk.UnsupportedError):
            await c.server_version()
        assert SRC not in repr(c)
        assert c.resources is not None
    await c.close()
    with pytest.raises(sdk.CancelledError):
        await c.health()
    with pytest.raises(sdk.ConfigError):
        c.log_client()
    server.enqueue("/", Reply(405, "Method Not Allowed"))
    server.enqueue("/", Reply(202, b""))
    async with aclient(server.url) as ingest:
        status = await ingest.health()
        assert status.details == {"endpoint": "ingest", "httpStatus": "405"}
        assert await ingest.ping() >= 0
        server.enqueue("/", Reply(500, "x"))
        assert not (await ingest.health()).healthy
        server.enqueue("/", fixture_reply("send.403_bad_token"))
        with pytest.raises(sdk.AuthenticationError):
            await ingest.ping()
        with pytest.raises(sdk.ConfigError):
            ingest.query()
    query_only = aclient(query_url=server.url)
    for fn in (
        lambda: query_only.exporter,
        lambda: query_only.otlp_exporter,
        query_only.new_exporter,
        query_only.new_otlp_exporter,
    ):
        with pytest.raises(sdk.ConfigError):
            fn()
    await query_only.aclose()
    monkeypatch.setenv(sdk.ENV_PREFIX + "URL", server.url)
    monkeypatch.setenv(sdk.ENV_PREFIX + "SOURCE_TOKEN", SRC)
    c3 = sdk.AsyncBetterStackClient()
    assert c3.config.url == server.url
    await c3.aclose()
    c4 = sdk.AsyncBetterStackClient.from_env({}, url=server.url, source_token=SRC)
    await c4.aclose()


async def test_async_health_unreachable() -> None:
    """core.health.unreachable (async)"""
    for c in (aclient(closed_port_url()), aclient(query_url=closed_port_url())):
        status = await c.health()
        assert (status.healthy, status.status) == (False, "unreachable")
        await c.aclose()


async def test_async_health_cancel(server: MockServer) -> None:
    server.enqueue("/ping", Reply(200, "Ok.", delay=2.0))
    c = aclient(query_url=server.url)
    errors: list[BaseException] = []
    task = spawn_capturing(c.health(), errors)
    await asyncio.sleep(0.1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert isinstance(errors[0], sdk.CancelledError)
    await c.aclose()


async def test_async_close_flushes_and_stops_tails(server: MockServer) -> None:
    server.defaults["/"] = Reply(200, b"")
    c = aclient(server.url, server.url, source_table="t1")
    logs = c.log_client(options=sdk.BatchOptions(flush_interval_s=60))
    assert isinstance(logs.exporter, sdk.AsyncBetterStackLogExporter)
    otlp = c.log_client(protocol="otlp")
    assert isinstance(otlp.exporter, sdk.AsyncBetterStackOtlpLogExporter)
    logs.logger().info("async flushed on close")
    tail = await c.query().tail("x", lambda r: None, interval_s=0.05)
    await c.aclose()
    assert tail._task.done()
    bodies = [s.body for s in server.seen if s.method == "POST"]
    assert any(
        b.startswith(b"\x1f\x8b") and b"async flushed" in __import__("gzip").decompress(b)
        for b in bodies
    )


# --------------------------------------------------------------------------- polling


def test_poll_until() -> None:
    values = iter([None, [], [1]])
    assert sdk.poll_until(lambda: next(values), interval_s=0.001) == [1]
    attempts = iter([0, 1, 2, 3])
    assert sdk.poll_until(lambda: next(attempts), predicate=lambda v: v >= 2, interval_s=0) == 2
    errors = iter([sdk.UnavailableError("flaky"), "ok"])

    def flaky() -> str:
        item = next(errors)
        if isinstance(item, Exception):
            raise item
        return item

    assert sdk.poll_until(flaky, interval_s=0) == "ok"
    with pytest.raises(sdk.NotFoundError):
        sdk.poll_until(lambda: (_ for _ in ()).throw(sdk.NotFoundError("gone")))
    started = time.monotonic()
    with pytest.raises(sdk.TimeoutError, match=r"wait\.thing") as info:
        sdk.poll_until(
            lambda: (_ for _ in ()).throw(sdk.UnavailableError("down")),
            timeout_s=0.05,
            interval_s=0.01,
            operation="wait.thing",
        )
    assert isinstance(info.value.cause, sdk.UnavailableError)
    assert time.monotonic() - started < 1.0
    with pytest.raises(sdk.TimeoutError):
        sdk.poll_until(lambda: None, timeout_s=0.02, interval_s=0.005)


async def test_apoll_until() -> None:
    values = iter([None, "", "done"])

    async def fetch() -> str | None:
        return next(values)

    assert await sdk.apoll_until(fetch, interval_s=0.001) == "done"

    async def failing() -> str:
        raise sdk.UnavailableError("down")

    with pytest.raises(sdk.TimeoutError):
        await sdk.apoll_until(failing, timeout_s=0.03, interval_s=0.01)

    async def fatal() -> str:
        raise sdk.AuthenticationError("no")

    with pytest.raises(sdk.AuthenticationError):
        await sdk.apoll_until(fatal)

    async def numbers() -> int:
        return 5

    assert await sdk.apoll_until(numbers, predicate=lambda n: n == 5) == 5


def test_resource_registry() -> None:
    reg = sdk.ResourceRegistry()
    calls: list[str] = []

    class Good:
        def stop(self) -> None:
            calls.append("good")

    class Bad:
        def stop(self) -> None:
            raise RuntimeError("ignored")

    good, bad = Good(), Bad()
    reg.register(bad)
    reg.register(good)
    reg.unregister(good)
    reg.unregister(good)
    reg.register(good)
    assert isinstance(good, sdk.Stoppable)
    assert not reg.closed
    reg.stop_all()
    reg.stop_all()
    assert reg.closed and calls == ["good"]
    reg.register(bad)  # stopped immediately, error ignored


def test_get_logger_is_silent() -> None:
    logger = sdk.get_logger()
    assert logger.name == sdk.LOGGER_NAME == "grow_better_stack_logs"
    assert any(type(h).__name__ == "NullHandler" for h in logger.handlers)
