# ruff: noqa: S608, PT018
"""Every public function of grow_better_stack_logs against the Better Stack fixture.

What the SDK sends to the ingest host is read back through the SQL query API (``query`` /
``search_logs`` / ``wait_for_log``), so each test is a round trip.
"""

from __future__ import annotations

import asyncio
import threading
import time
from collections.abc import Callable
from datetime import datetime, timedelta, timezone
from typing import Any

import pytest
from factories import async_core_client, core_client

import grow_better_stack_logs as sdk

TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"
SPAN = "00f067aa0ba902b7"
Fault = Callable[[dict[str, Any]], None]


def rec(body: str, **kw: Any) -> sdk.LogRecord:
    return sdk.LogRecord(body=body, **kw)


# --------------------------------------------------------------------------- ingest → SQL


@pytest.mark.parametrize("encoding", ["json", "ndjson", "msgpack"])
@pytest.mark.parametrize("gzip", [True, False])
def test_send_events_round_trip(
    client: sdk.BetterStackClient, env: dict[str, str], run: str, encoding: str, gzip: bool
) -> None:
    table = env["BETTERSTACK_SOURCE_TABLE"]
    marker = f"bs-{run}-{encoding}-{gzip}"
    dt = datetime(2026, 1, 2, 3, 4, 5, 678000, tzinfo=timezone.utc)
    client.exporter.send_events(
        [
            {"message": f"{marker} a", "dt": dt, "n": 7, "nested": {"ok": True, "t": "Grüße ✓"}},
            {"message": f"{marker} b", "dt": int(time.time() * 1000)},
        ],
        encoding=encoding,  # type: ignore[arg-type]
        gzip=gzip,
    )
    rows = client.query().query(
        f"SELECT dt, raw, JSONExtractInt(raw, 'n') AS n FROM remote({table}_logs) "
        "WHERE position(raw, {needle:String}) > 0 ORDER BY dt ASC",
        {"needle": marker},
    )
    assert len(rows) == 2
    assert rows[0]["dt"] == "2026-01-02 03:04:05.678000"
    assert str(rows[0]["n"]) == "7"
    first = sdk.log_record_from_row(rows[0])
    assert first.body == f"{marker} a"
    assert first.attributes["nested"] == {"ok": True, "t": "Grüße ✓"}


def test_config_encoding_and_new_exporter(env: dict[str, str], run: str) -> None:
    marker = f"bs-{run}-cfg-encoding"
    with core_client(env, encoding="msgpack") as c:
        assert c.exporter.encoding == "msgpack"
        c.exporter.send_events([{"message": f"{marker} msgpack"}])
        nd = c.new_exporter(encoding="ndjson", max_request_bytes=1 << 20, max_record_bytes=1 << 16)
        assert nd.encoding == "ndjson"
        result = nd.export([rec(f"{marker} ndjson")])
        assert (result.accepted, result.rejected) == (1, 0)
        found = c.query().search_logs(contains=marker, order="asc")
        assert sorted(r.body for r in found.records) == [f"{marker} msgpack", f"{marker} ndjson"]


def test_export_maps_log_records(client: sdk.BetterStackClient, run: str) -> None:
    marker = f"bs-{run}-export"
    result = client.exporter.export(
        [
            rec(
                f"{marker} one",
                severity=sdk.Severity.ERROR,
                trace_id=TRACE,
                span_id=SPAN,
                trace_flags=1,
                attributes={"user": {"id": 42}, "tags": ["a", "b"]},
                resource={"service.name": "sdk-py-it"},
            ),
            rec(f"{marker} two"),
        ],
        gzip=True,
    )
    assert (result.accepted, result.rejected, result.error) == (2, 0, None)
    found = client.query().search_logs(contains=marker, order="asc")
    assert len(found.records) == 2
    one = next(r for r in found.records if r.body.endswith("one"))
    assert one.severity == sdk.Severity.ERROR and one.severity_text == "ERROR"
    assert (one.trace_id, one.span_id, one.trace_flags) == (TRACE, SPAN, 1)
    assert one.resource == {"service.name": "sdk-py-it"}
    assert one.attributes == {"user": {"id": 42}, "tags": ["a", "b"]}


@pytest.mark.parametrize("protocol", ["protobuf", "json"])
def test_otlp_exporter_round_trip(client: sdk.BetterStackClient, run: str, protocol: str) -> None:
    marker = f"bs-{run}-otlp-{protocol}"
    exporter = (
        client.otlp_exporter
        if protocol == "protobuf"
        else client.new_otlp_exporter(protocol="json", scope_name="it-scope")
    )
    result = exporter.export(
        [
            rec(
                f"{marker} x",
                severity=sdk.Severity.WARN,
                trace_id=TRACE,
                span_id=SPAN,
                attributes={
                    "n": 3,
                    "ratio": 0.5,
                    "ok": True,
                    "list": [1, "two"],
                    "map": {"k": "v"},
                },
                resource={"service.name": "sdk-py-otlp"},
            )
        ],
        gzip=protocol == "protobuf",
    )
    assert (result.accepted, result.rejected, result.error) == (1, 0, None)
    assert exporter.send([rec(f"{marker} y")], gzip=False) is None
    r = client.query().wait_for_log(f"{marker} x", timeout_s=10, interval_s=0.2)
    assert r.body == f"{marker} x"
    assert r.severity == sdk.Severity.WARN
    assert (r.trace_id, r.span_id) == (TRACE, SPAN)
    assert r.resource["service.name"] == "sdk-py-otlp"
    assert r.attributes["list"] == [1, "two"]
    assert r.attributes["map"] == {"k": "v"}
    client.query().wait_for_log(f"{marker} y", timeout_s=10, interval_s=0.2)


def test_quota_402_is_rate_limited_not_retryable(
    client: sdk.BetterStackClient, run: str, fault: Fault
) -> None:
    fault({"status": 402, "count": 1, "target": "ingest", "body": "Quota exceeded"})
    with pytest.raises(sdk.RateLimitError) as info:
        client.exporter.send_events([{"message": f"bs-{run}-quota"}])
    assert info.value.retryable is False and info.value.http_status == 402


def test_429_with_retry_after_is_retried(
    client: sdk.BetterStackClient, run: str, fault: Fault
) -> None:
    marker = f"bs-{run}-retry"
    fault({"status": 429, "count": 1, "target": "ingest", "body": "slow down", "retryAfter": 1})
    started = time.monotonic()
    client.exporter.send_events([{"message": marker}])
    assert time.monotonic() - started >= 0.9
    assert client.query().wait_for_log(marker, timeout_s=10, interval_s=0.2).body == marker


def test_unparseable_body_is_validation(client: sdk.BetterStackClient) -> None:
    with pytest.raises(sdk.ValidationError) as info:
        client.exporter.send_events([1], encoding="ndjson")  # type: ignore[list-item]
    assert info.value.http_status == 406


# --------------------------------------------------------------------------- log client


@pytest.mark.parametrize("protocol", ["http", "otlp"])
def test_log_client_logger_and_wait_for_log(
    client: sdk.BetterStackClient, run: str, protocol: str
) -> None:
    logs = client.log_client(
        protocol=protocol,  # type: ignore[arg-type]
        options=sdk.BatchOptions(flush_interval_s=0.1),
        resource={"service.name": f"sdk-py-{protocol}"},
    )
    assert isinstance(logs, sdk.BetterStackLogClient)
    marker = f"bs-{run}-logger-{protocol}"
    log = logs.logger("it").child({"run": run})
    log.warn(f"{marker} warn", {"password": "hunter2-secret"})
    logs.send(sdk.LogInput(message=f"{marker} send", trace_id=TRACE, span_id=SPAN))
    logs.send_batch([sdk.LogInput(message=f"{marker} batch {i}") for i in range(2)])
    logs.flush(10)
    assert logs.stats().sent == 4
    q = client.query()
    warn = q.wait_for_log(f"{marker} warn", timeout_s=10, interval_s=0.2)
    assert warn.severity == sdk.Severity.WARN
    assert "hunter2-secret" not in repr(warn)
    assert warn.resource["service.name"] == f"sdk-py-{protocol}"
    sent = q.wait_for_log(f"{marker} send", timeout_s=10, interval_s=0.2)
    assert (sent.trace_id, sent.span_id) == (TRACE, SPAN)
    assert len(q.search(marker, limit=10).records) == 4
    logs.close()


def test_log_client_with_custom_exporter_closed_by_client(env: dict[str, str], run: str) -> None:
    marker = f"bs-{run}-custom-exporter"
    with core_client(env) as c:
        logs = c.log_client(exporter=c.new_exporter(encoding="msgpack"))
        logs.logger().info(marker)
    # close() flushed the log client
    with core_client(env) as c:
        assert c.query().wait_for_log(marker, timeout_s=10, interval_s=0.2).body == marker


# --------------------------------------------------------------------------- SQL


def test_query_binds_parameters_format_json_and_query_raw(
    client: sdk.BetterStackClient, env: dict[str, str], run: str
) -> None:
    table = env["BETTERSTACK_SOURCE_TABLE"]
    marker = f"bs-{run}-params"
    client.exporter.send_events(
        [{"message": marker, "n": 5, "dt": datetime(2025, 5, 5, 5, 5, 5, tzinfo=timezone.utc)}]
    )
    q = client.query()
    rows = q.query(
        f"SELECT count() AS c FROM remote({table}_logs) WHERE position(raw, {{needle:String}}) > 0 "
        "AND JSONExtractInt(raw, 'n') = {n:Int64} AND dt >= {since:DateTime64(6)} "
        "AND {flag:Bool} = {flag2:Bool}",
        {
            "needle": marker,
            "n": 5,
            "since": datetime(2025, 1, 1, tzinfo=timezone.utc),
            "flag": True,
            "flag2": True,
        },
    )
    assert rows == [{"c": "1"}]
    as_json = q.query(
        f"SELECT count() AS c FROM remote({table}_logs) "
        f"WHERE position(raw, {sdk.sql_string(marker)}) > 0 FORMAT JSON"
    )
    assert as_json == [{"c": "1"}]
    raw = q.query_raw(
        "SELECT 1 AS one FORMAT JSONEachRow", settings={"output_format_pretty_row_numbers": 0}
    )
    assert raw.strip() in ('{"one":1}', '{"one":"1"}')
    # String parameters use ClickHouse's escaped (TSV) format: backslash and quote survive.
    value = f'C:\\tmp\\it\'s "{run}"'
    assert q.query("SELECT {v:String} AS v", {"v": value}) == [{"v": value}]


def test_errors_map_by_clickhouse_code(client: sdk.BetterStackClient, env: dict[str, str]) -> None:
    q = client.query()
    with pytest.raises(sdk.NotFoundError):
        q.query("SELECT dt FROM remote(t_missing_logs)")
    with pytest.raises(sdk.ValidationError):
        q.query("SELEKT nonsense")
    # UNKNOWN_IDENTIFIER (47) is a ValidationError although ClickHouse answers 404.
    with pytest.raises(sdk.ValidationError) as info:
        q.query(f"SELECT nope FROM remote({env['BETTERSTACK_SOURCE_TABLE']}_logs)")
    assert info.value.http_status == 404


def test_503_on_query_host_is_retried(client: sdk.BetterStackClient, fault: Fault) -> None:
    fault({"status": 503, "count": 1, "target": "query", "body": "busy"})
    rows = client.query().query("SELECT 1 AS one")
    assert str(rows[0]["one"]) == "1"


def test_search_logs_window_order_cursor_quotes_isolation(
    client: sdk.BetterStackClient, env: dict[str, str], run: str
) -> None:
    marker = f'bs-{run}-search it\'s \\ "quoted"'
    base = datetime(2024, 3, 1, tzinfo=timezone.utc)
    result = client.exporter.export(
        [rec(f"{marker} {i}", timestamp=base + timedelta(seconds=i)) for i in range(5)]
    )
    assert result.accepted == 5
    q = client.query()
    window = {"start": base, "end": base + timedelta(seconds=10)}
    desc = q.search_logs(contains=marker, limit=2, **window)
    assert [r.body for r in desc.records] == [f"{marker} 4", f"{marker} 3"]
    assert desc.next_cursor
    page2 = q.search_logs(contains=marker, limit=2, cursor=desc.next_cursor, **window)
    assert [r.body for r in page2.records] == [f"{marker} 2", f"{marker} 1"]
    asc = q.search_logs(contains=marker, order="asc", limit=100, **window)
    assert len(asc.records) == 5 and asc.next_cursor is None
    assert asc.records[0].body == f"{marker} 0"
    assert asc.records[0].timestamp == base
    one = base + timedelta(seconds=1)
    narrow = q.search_logs(contains=marker, start=one, end=one)
    assert [r.body for r in narrow.records] == [f"{marker} 1"]
    other = q.search_logs(source=env["BETTERSTACK_SOURCE_TABLE_B"], contains=marker, **window)
    assert other.records == []
    assert "UNION ALL" in q.render_search_sql(contains=marker)


def test_second_source_token_writes_own_collection(
    client: sdk.BetterStackClient, env: dict[str, str], run: str
) -> None:
    table_b = env["BETTERSTACK_SOURCE_TABLE_B"]
    marker = f"bs-{run}-source-b"
    with core_client(
        env, source_token=env["BETTERSTACK_SOURCE_TOKEN_B"], source_table=table_b
    ) as b:
        b.exporter.send_events([{"message": marker}])
        assert len(b.query().search(marker).records) == 1
        assert b.query().list_streams() == [table_b]
    assert client.query().search(marker).records == []


def test_wait_for_log_timeout_labels_unsupported_and_tail(
    client: sdk.BetterStackClient, run: str
) -> None:
    q = client.query()
    with pytest.raises(sdk.TimeoutError):
        q.wait_for_log(f"bs-{run}-never", timeout_s=0.3, interval_s=0.1)
    with pytest.raises(sdk.UnsupportedError):
        q.list_labels()
    marker = f"bs-{run}-tail"
    seen: list[str] = []
    got = threading.Event()

    def on_record(r: sdk.LogRecord) -> None:
        seen.append(r.body)
        got.set()

    handle = q.tail(
        marker, on_record, interval_s=0.1, start=datetime.now(timezone.utc) - timedelta(seconds=1)
    )
    try:
        client.exporter.send_events([{"message": f"{marker} 1"}])
        assert got.wait(10)
        time.sleep(0.3)  # further polls must not deliver the record again
    finally:
        handle.stop()
    assert seen == [f"{marker} 1"]


# --------------------------------------------------------------------------- async


def test_async_exporter_query_log_client_and_tail(env: dict[str, str], run: str) -> None:
    marker = f"bs-{run}-async"

    async def scenario() -> None:
        async with async_core_client(env) as c:
            await c.exporter.send_events([{"message": f"{marker} events"}], encoding="msgpack")
            res = await c.new_exporter(encoding="ndjson").export([rec(f"{marker} export")])
            assert res.accepted == 1
            res = await c.otlp_exporter.export([rec(f"{marker} otlp")])
            assert res.accepted == 1
            assert await c.new_otlp_exporter(protocol="json").send([rec(f"{marker} oj")]) is None
            logs = c.log_client(options=sdk.BatchOptions(flush_interval_s=0.1))
            assert isinstance(logs, sdk.AsyncBetterStackLogClient)
            logs.logger().info(f"{marker} logger")
            await logs.flush(10)
            otlp_logs = c.log_client(protocol="otlp")
            otlp_logs.send(sdk.LogInput(message=f"{marker} client-via-otlp"))
            await otlp_logs.flush(10)
            q = c.query()
            for suffix in ("events", "export", "otlp", "oj", "logger", "client-via-otlp"):
                found = await q.wait_for_log(f"{marker} {suffix}", timeout_s=10, interval_s=0.2)
                assert found.body == f"{marker} {suffix}"
            rows = await q.query(
                f"SELECT count() AS c FROM remote({env['BETTERSTACK_SOURCE_TABLE']}_logs) "
                "WHERE position(raw, {m:String}) > 0",
                {"m": marker},
            )
            assert rows == [{"c": "6"}]
            assert "1" in await q.query_raw("SELECT 1 AS one")
            page = await q.search_logs(contains=marker, limit=4)
            assert len(page.records) == 4 and page.next_cursor
            assert len((await q.search(marker, cursor=page.next_cursor)).records) == 2
            assert await q.list_streams() == [env["BETTERSTACK_SOURCE_TABLE"]]
            with pytest.raises(sdk.UnsupportedError):
                await q.list_labels()
            seen: list[str] = []
            tail = await q.tail(
                f"{marker} tail",
                lambda r: seen.append(r.body),
                interval_s=0.1,
                start=datetime.now(timezone.utc) - timedelta(seconds=1),
            )
            await c.exporter.send_events([{"message": f"{marker} tail 1"}])
            for _ in range(100):
                if seen:
                    break
                await asyncio.sleep(0.1)
            await tail.stop()
            assert seen == [f"{marker} tail 1"]
            with pytest.raises(sdk.NotFoundError):
                await q.query("SELECT dt FROM remote(t_missing_logs)")
        with pytest.raises(sdk.CancelledError):
            c.log_client()

    asyncio.run(scenario())
