"""SQL query client (sync + async): binding, parsing, error mapping, search, tail."""

from __future__ import annotations

import asyncio
import json
import threading
from datetime import date, datetime, timedelta, timezone
from typing import Any
from urllib.parse import parse_qs, urlsplit

import pytest

import grow_better_stack_logs as sdk
from _support import MockServer, Reply, bs_config, fixture, fixture_reply

UTC = timezone.utc
NOW = datetime(2026, 11, 18, 12, 0, 0, tzinfo=UTC)
PW = "query-pass-456"


def client(server: MockServer, **kw: Any) -> sdk.BetterStackClient:
    kw.setdefault("source_table", "t1_grow_sdk")
    return sdk.BetterStackClient(bs_config(query_url=server.url, **kw))


def aclient(server: MockServer, **kw: Any) -> sdk.AsyncBetterStackClient:
    kw.setdefault("source_table", "t1_grow_sdk")
    return sdk.AsyncBetterStackClient(bs_config(query_url=server.url, **kw))


def params_of(seen: Any) -> dict[str, list[str]]:
    return parse_qs(urlsplit(seen.path).query, keep_blank_values=True)


def row(message: str, dt: str = "2026-11-18 11:00:00.000000", **extra: Any) -> str:
    return json.dumps({"dt": dt, "raw": json.dumps({"message": message, **extra})})


# --------------------------------------------------------------------------- helpers


def test_sql_helpers() -> None:
    assert sdk.sql_string("it's \\ \n\r\t\0") == "'it\\'s \\\\ \\n\\r\\t\\0'"
    assert sdk.json_escape('say "hi" \\ ✓\n') == 'say \\"hi\\" \\\\ ✓\\n'
    assert sdk.format_datetime64(NOW) == "2026-11-18 12:00:00.000000"
    assert sdk.format_datetime64(datetime(2026, 1, 1, 1)) == "2026-01-01 01:00:00.000000"
    tz = timezone(timedelta(hours=2))
    assert sdk.format_datetime64(datetime(2026, 1, 1, 3, tzinfo=tz)) == "2026-01-01 01:00:00.000000"


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("2026-11-18 21:00:00.123456789", datetime(2026, 11, 18, 21, 0, 0, 123456, tzinfo=UTC)),
        ("2026-11-18 21:00:00", datetime(2026, 11, 18, 21, tzinfo=UTC)),
        ("2026-11-18T21:00:00Z", datetime(2026, 11, 18, 21, tzinfo=UTC)),
        ("2026-11-18T23:00:00+02:00", datetime(2026, 11, 18, 21, tzinfo=UTC)),
        ("2026-11-18T19:30:00-0130", datetime(2026, 11, 18, 21, tzinfo=UTC)),
        (1700000000, datetime(2023, 11, 14, 22, 13, 20, tzinfo=UTC)),
        (1700000000123, datetime(2023, 11, 14, 22, 13, 20, 123000, tzinfo=UTC)),
        (datetime(2026, 1, 1), datetime(2026, 1, 1, tzinfo=UTC)),
        ("2026-13-01 00:00:00", None),
        ("nope", None),
        (float("inf"), None),
        (1e30, None),
        (True, None),
        (None, None),
    ],
)
def test_parse_datetime(value: Any, expected: datetime | None) -> None:
    assert sdk.parse_datetime(value) == expected


def test_log_record_from_row() -> None:
    raw = {
        "message": "hello",
        "level": "warning",
        "severity_number": 17,
        "span": {"trace_id": "4BF92F3577B34DA6A3CE929D0E0E4736", "span_id": "00f067aa0ba902b7"},
        "trace_flags": 1,
        "resource": {"service.name": "x", "drop": None},
        "attributes": {"user": {"id": 42}},
        "observed_dt": "2026-11-18T12:00:00Z",
        "extra": [1, None, "a"],
        "nothing": None,
    }
    r = sdk.log_record_from_row({"dt": "2026-11-18 11:00:00.500000", "raw": json.dumps(raw)})
    assert r.body == "hello"
    assert r.severity == sdk.Severity.ERROR  # severity_number wins
    assert r.severity_text == "WARN"
    assert r.timestamp == datetime(2026, 11, 18, 11, 0, 0, 500000, tzinfo=UTC)
    assert r.observed_timestamp == NOW
    assert (r.trace_id, r.span_id, r.trace_flags) == (
        "4bf92f3577b34da6a3ce929d0e0e4736",
        "00f067aa0ba902b7",
        1,
    )
    assert r.resource == {"service.name": "x"}
    assert r.attributes == {"extra": [1, "a"], "user": {"id": 42}}


def test_log_record_from_row_fallbacks() -> None:
    msg = sdk.log_record_from_row(
        {"raw": '{"msg": "via msg", "severity": "debug", "dt": 1700000000}'}
    )
    assert (msg.body, msg.severity, msg.severity_text) == ("via msg", sdk.Severity.DEBUG, "DEBUG")
    assert msg.timestamp == datetime(2023, 11, 14, 22, 13, 20, tzinfo=UTC)
    obj = sdk.log_record_from_row(
        {"raw": '{"message": {"a": 1}, "level": "custom", "severity_number": "13"}'}
    )
    assert (obj.body, obj.severity, obj.severity_text) == ('{"a": 1}', sdk.Severity.WARN, "custom")
    plain = sdk.log_record_from_row({"dt": "bad", "raw": "not json"})
    assert (plain.body, plain.severity, plain.severity_text) == (
        "not json",
        sdk.Severity.INFO,
        "INFO",
    )
    assert plain.timestamp == datetime(1970, 1, 1, tzinfo=UTC)
    nested = sdk.log_record_from_row({"raw": {"message": "dict raw", "trace_id": "0" * 32}})
    assert nested.body == "dict raw"
    assert nested.trace_id is None  # all-zero ids are invalid
    top = sdk.log_record_from_row(
        {
            "raw": json.dumps(
                {"message": "m", "trace_id": "a" * 32, "span_id": "b" * 15, "trace_flags": True}
            )
        }
    )
    assert (top.trace_id, top.span_id, top.trace_flags) == ("a" * 32, None, None)
    bad_severity = sdk.log_record_from_row({"raw": '{"message": "m", "severity_number": 99}'})
    assert bad_severity.severity == sdk.Severity.INFO


def test_parse_rows_formats_and_errors() -> None:
    each = fixture("query.json_each_row")["response"]["body"]
    assert len(sdk.parse_rows(each)) == 11
    assert sdk.parse_rows(json.dumps(fixture("query.json_format")["response"]["body"])) == [
        {"c": "11"}
    ]
    pretty = json.dumps(fixture("query.json_format")["response"]["body"], indent=2)
    assert sdk.parse_rows(pretty) == [{"c": "11"}]
    assert len(sdk.parse_rows(json.dumps(fixture("query.array_of_rows")["response"]["body"]))) == 2
    assert sdk.parse_rows("  \n") == []
    assert sdk.parse_rows('{"a":1}\n\n{"a":2}\n') == [{"a": 1}, {"a": 2}]
    with pytest.raises(sdk.NotFoundError):
        sdk.parse_rows('{"a":1}\nCode: 60. DB::Exception: Table gone. (UNKNOWN_TABLE)\n')
    with pytest.raises(sdk.UnavailableError) as info:
        sdk.parse_rows('{"a":1}\nCode: 999. DB::Exception: weird\n')
    assert (info.value.http_status, info.value.retryable) == (200, False)
    with pytest.raises(sdk.UnavailableError, match="invalid JSON line"):
        sdk.parse_rows('{"a":1}\ngarbage')
    with pytest.raises(sdk.UnavailableError, match="invalid JSON line"):
        sdk.parse_rows("garbage")
    with pytest.raises(sdk.ValidationError):
        sdk.parse_rows('[{"a":1}, Code: 62. DB::Exception: oops')
    with pytest.raises(sdk.UnavailableError, match="array"):
        sdk.parse_rows("[1, 2")


# --------------------------------------------------------------------------- query


def test_query_binds_parameters_server_side(server: MockServer) -> None:
    server.enqueue("/", Reply(200, '{"c":"1"}\n'))
    with client(server) as c:
        rows = c.query().query(
            "SELECT count() AS c FROM remote(t1_logs) WHERE position(raw, {needle:String}) > 0;",
            {
                "needle": "it's",
                "n": 5,
                "f": 1.5,
                "b": False,
                "since": datetime(2025, 1, 1, tzinfo=UTC),
                "day": date(2025, 1, 2),
                "missing": None,
            },
            settings={"max_execution_time": 30, "readonly": True},
        )
    assert rows == [{"c": "1"}]
    seen = server.seen[0]
    assert seen.body.endswith(b"> 0 FORMAT JSONEachRow")
    assert b"it's" not in seen.body
    assert params_of(seen) == {
        "max_execution_time": ["30"],
        "readonly": ["true"],
        "param_needle": ["it's"],
        "param_n": ["5"],
        "param_f": ["1.5"],
        "param_b": ["false"],
        "param_since": ["2025-01-01 00:00:00.000000"],
        "param_day": ["2025-01-02"],
        "param_missing": ["\\N"],
    }
    assert seen.headers["Content-Type"] == "text/plain; charset=utf-8"
    assert seen.headers["Authorization"].startswith("Basic ")
    assert "src-token" not in json.dumps(seen.headers)


def test_escape_param_value() -> None:
    assert sdk.escape_param_value("plain 'quoted' \"text\"") == "plain 'quoted' \"text\""
    assert sdk.escape_param_value("a\\b\tc\nd\re") == "a\\\\b\\tc\\nd\\re"
    assert sdk.escape_param_value("\\N") == "\\\\N"


def test_query_escapes_string_parameters_tsv(server: MockServer) -> None:
    server.enqueue("/", Reply(200, ""))
    with client(server) as c:
        c.query().query("SELECT {v:String} AS v", {"v": 'C:\\tmp\\it\'s "x"\n'})
    assert params_of(server.seen[0]) == {"param_v": ['C:\\\\tmp\\\\it\'s "x"\\n']}


def test_query_validation(server: MockServer) -> None:
    with client(server) as c:
        q = c.query()
        assert c.query() is q
        with pytest.raises(sdk.ValidationError, match="empty"):
            q.query_raw("  ")
        with pytest.raises(sdk.ValidationError, match="parameter name"):
            q.query("SELECT 1", {"bad-name": 1})
        with pytest.raises(sdk.ValidationError, match="setting name"):
            q.query("SELECT 1", settings={"x;y": 1})
        with pytest.raises(sdk.ValidationError, match="finite"):
            q.query("SELECT 1", {"x": float("nan")})
        with pytest.raises(sdk.ValidationError, match="unsupported type"):
            q.query("SELECT 1", {"x": [1]})  # type: ignore[dict-item]
        with pytest.raises(sdk.ValidationError, match="FORMAT CSV"):
            q.query("SELECT 1 FORMAT CSV")
    assert server.seen == []


def test_query_formats_and_raw(server: MockServer) -> None:
    server.enqueue("/", fixture_reply("query.json_format"), Reply(200, "1\tx\n"))
    with client(server) as c:
        q = c.query()
        assert q.query("SELECT count() AS c FROM remote(t_logs) FORMAT JSON") == [{"c": "11"}]
        assert q.query_raw("SELECT 1, 'x' FORMAT TSV") == "1\tx\n"
    assert server.seen[0].body.endswith(b"FORMAT JSON")
    assert params_of(server.seen[1]) == {}


@pytest.mark.parametrize(
    ("name", "cls", "status"),
    [
        ("query.400_syntax", sdk.ValidationError, 400),
        ("query.401_bad_password", sdk.AuthenticationError, 401),
        ("query.401_no_auth", sdk.AuthenticationError, 401),
        ("query.404_unknown_table", sdk.NotFoundError, 404),
        ("query.404_unknown_column", sdk.ValidationError, 404),
        ("query.params_binding", sdk.ValidationError, 400),
        ("query.503_fault", sdk.UnavailableError, 503),
    ],
)
def test_query_error_mapping_from_fixtures(
    server: MockServer, name: str, cls: type, status: int
) -> None:
    server.defaults["/"] = fixture_reply(name)
    with client(server, retry=sdk.RetryPolicy(max_retries=0)) as c, pytest.raises(cls) as info:
        c.query().query("SELECT 1")
    assert type(info.value) is cls
    assert info.value.http_status == status
    assert PW not in str(info.value)


def test_query_retries_503(server: MockServer) -> None:
    server.enqueue("/", fixture_reply("query.503_fault"), Reply(200, '{"one":1}\n'))
    with client(server) as c:
        assert c.query().query("SELECT 1 AS one") == [{"one": 1}]
    assert len(server.seen) == 2


def test_query_cancel(server: MockServer) -> None:
    server.enqueue("/", Reply(200, "{}", delay=2.0))
    token = sdk.CancelToken()
    threading.Timer(0.1, token.cancel).start()
    with client(server) as c, pytest.raises(sdk.CancelledError):
        c.query().query("SELECT 1", cancel=token)


# --------------------------------------------------------------------------- search


def test_render_search_sql() -> None:
    q = sdk.BetterStackQuery(
        None,  # type: ignore[arg-type]
        bs_config(query_url="http://q", source_table="t1_app"),
        now=lambda: NOW,
    )
    sql = q.render_search_sql(contains='it\'s "x"', limit=10)
    assert sql == (
        "SELECT dt, raw FROM (SELECT dt, raw FROM remote(t1_app_logs) UNION ALL "
        "SELECT dt, raw FROM s3Cluster(primary, t1_app_s3) WHERE _row_type = 1) "
        "WHERE dt >= toDateTime64('2026-11-17 12:00:00.000000', 6, 'UTC') "
        "AND position(raw, 'it\\'s \\\\\"x\\\\\"') > 0 ORDER BY dt DESC LIMIT 11 FORMAT JSONEachRow"
    )
    end = datetime(2026, 1, 2, tzinfo=UTC)
    windowed = q.render_search_sql(source="t2_other", end=end, order="asc", cursor=_cursor(20))
    assert "remote(t2_other_logs)" in windowed
    assert (
        "dt BETWEEN toDateTime64('2026-01-01 00:00:00.000000', 6, 'UTC') "
        "AND toDateTime64('2026-01-02 00:00:00.000000', 6, 'UTC')"
    ) in windowed
    assert "position" not in windowed
    assert windowed.endswith("ORDER BY dt ASC LIMIT 101 OFFSET 20 FORMAT JSONEachRow")
    for kwargs, match in (
        ({"source": "bad name"}, "source"),
        ({"limit": 0}, "limit"),
        ({"limit": 10_001}, "limit"),
        ({"limit": True}, "limit"),
        ({"order": "sideways"}, "order"),
        ({"start": end, "end": end - timedelta(seconds=1)}, "start"),
        ({"cursor": "%%%"}, "cursor"),
        ({"cursor": _b64('{"o": -1}')}, "cursor"),
        ({"cursor": _b64('{"x": 1}')}, "cursor"),
    ):
        with pytest.raises(sdk.ValidationError, match=match):
            q.render_search_sql(**kwargs)  # type: ignore[arg-type]
    no_table = sdk.BetterStackQuery(None, bs_config(query_url="http://q"))  # type: ignore[arg-type]
    with pytest.raises(sdk.ConfigError, match="source_table"):
        no_table.render_search_sql()
    assert no_table.list_streams() == []
    with pytest.raises(sdk.ConfigError, match="tail"):
        no_table.tail("x", lambda r: None)
    assert q.config.source_table == "t1_app"


def _b64(text: str) -> str:
    import base64

    return base64.urlsafe_b64encode(text.encode()).decode().rstrip("=")


def _cursor(offset: int) -> str:
    return _b64(json.dumps({"o": offset}, separators=(",", ":")))


def test_search_logs_pages_and_dedups(server: MockServer) -> None:
    body = "\n".join(
        [
            row("a", "2026-11-18 11:00:03.000000"),
            row("a", "2026-11-18 11:00:03.000000"),  # hot/cold overlap
            row("b", "2026-11-18 11:00:02.000000"),
            row("c", "2026-11-18 11:00:01.000000"),
        ]
    )
    server.enqueue("/", Reply(200, body), fixture_reply("searchLogs.contains"))
    with client(server) as c:
        q = c.query()
        page = q.search_logs(contains="x", limit=3)
        assert [r.body for r in page.records] == ["a", "b"]
        assert page.next_cursor == _cursor(3)
        page2 = q.search("needle", limit=5, cursor=page.next_cursor)
        assert [r.body for r in page2.records] == [
            "needle-prep-208cdca json-array 2",
            "needle-prep-208cdca json-array 1",
        ]
        assert page2.next_cursor is None
    assert b"OFFSET 3" in server.seen[1].body
    assert params_of(server.seen[0]) == {}


def test_search_empty_and_streams(server: MockServer) -> None:
    server.enqueue("/", fixture_reply("searchLogs.empty"))
    with client(server) as c:
        q = c.query()
        assert q.search("nothing").records == []
        assert q.list_streams() == ["t1_grow_sdk"]
        with pytest.raises(sdk.UnsupportedError, match="labels"):
            q.list_labels()


def test_wait_for_log(server: MockServer) -> None:
    server.enqueue("/", Reply(200, ""), Reply(200, row("found it")))
    with client(server) as c:
        found = c.query().wait_for_log("found", timeout_s=5, interval_s=0.01)
        assert found.body == "found it"
        server.defaults["/"] = Reply(200, "")
        with pytest.raises(sdk.TimeoutError):
            c.query().wait_for_log("never", timeout_s=0.05, interval_s=0.01)


def test_tail_polls_and_close_stops_it(server: MockServer) -> None:
    server.defaults["/"] = Reply(200, row("tail 1", "2030-01-01 00:00:00.000000"))
    seen: list[str] = []
    got = threading.Event()
    errors: list[Exception] = []
    c = client(server)

    def on_record(r: sdk.LogRecord) -> None:
        seen.append(r.body)
        got.set()

    tail = c.query().tail("tail", on_record, interval_s=0.02, on_error=errors.append)
    assert got.wait(5)
    c.close()
    assert not tail._thread.is_alive()
    assert seen == ["tail 1"]
    assert b"ORDER BY dt ASC" in server.seen[0].body


# --------------------------------------------------------------------------- async


async def test_async_query(server: MockServer) -> None:
    server.enqueue(
        "/",
        Reply(200, '{"c":"2"}\n'),
        Reply(200, "raw text"),
        Reply(200, row("a") + "\n" + row("b")),
        Reply(200, row("c")),
        fixture_reply("query.404_unknown_table"),
        Reply(200, row("waited")),
    )
    async with aclient(server) as c:
        q = c.query()
        assert c.query() is q
        assert await q.query("SELECT count() AS c FROM t WHERE x = {x:String}", {"x": "y"}) == [
            {"c": "2"}
        ]
        assert await q.query_raw("SELECT 1 FORMAT TSV", settings={"a": 1}) == "raw text"
        page = await q.search_logs(contains="z", limit=1)
        assert [r.body for r in page.records] == ["a"]
        assert page.next_cursor
        assert [r.body for r in (await q.search("z", cursor=page.next_cursor)).records] == ["c"]
        with pytest.raises(sdk.NotFoundError):
            await q.query("SELECT 1")
        assert (await q.wait_for_log("waited", timeout_s=5, interval_s=0.01)).body == "waited"
        assert await q.list_streams() == ["t1_grow_sdk"]
        with pytest.raises(sdk.UnsupportedError):
            await q.list_labels()
    assert params_of(server.seen[0]) == {"param_x": ["y"]}
    assert params_of(server.seen[1]) == {"a": ["1"]}


async def test_async_tail(server: MockServer) -> None:
    server.defaults["/"] = Reply(200, row("async tail", "2030-01-01 00:00:00.000000"))
    async with aclient(server) as c:
        seen: list[str] = []
        tail = await c.query().tail("async", lambda r: seen.append(r.body), interval_s=0.02)
        for _ in range(200):
            if seen:
                break
            await asyncio.sleep(0.01)
        await tail.stop()
    assert seen == ["async tail"]
