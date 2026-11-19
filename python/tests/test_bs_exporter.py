"""Ingest and OTLP exporters (sync + async) against a mock ingesting host."""

from __future__ import annotations

import gzip
import json
from datetime import datetime, timezone
from typing import Any

import pytest
from opentelemetry.proto.collector.logs.v1 import logs_service_pb2

import grow_better_stack_logs as sdk
from _support import MockServer, Reply, bs_config, fixture, fixture_reply

SRC = "src-token-123"
TS = datetime(2026, 1, 2, 3, 4, 5, 678000, tzinfo=timezone.utc)


def client(server: MockServer, **kw: Any) -> sdk.BetterStackClient:
    return sdk.BetterStackClient(bs_config(server.url, **kw))


def aclient(server: MockServer, **kw: Any) -> sdk.AsyncBetterStackClient:
    return sdk.AsyncBetterStackClient(bs_config(server.url, **kw))


def rec(body: str, **kw: Any) -> sdk.LogRecord:
    return sdk.LogRecord(body=body, timestamp=TS, **kw)


def body_of(seen: Any) -> bytes:
    data: bytes = seen.body
    return gzip.decompress(data) if seen.headers.get("Content-Encoding") == "gzip" else data


# --------------------------------------------------------------------------- HTTP ingest


@pytest.mark.parametrize(
    ("encoding", "ctype"),
    [
        ("json", "application/json"),
        ("ndjson", "application/x-ndjson"),
        ("msgpack", "application/msgpack"),
    ],
)
def test_send_events_encodings(server: MockServer, encoding: str, ctype: str) -> None:
    server.defaults["/"] = fixture_reply("send.json_single")
    with client(server) as c:
        c.exporter.send_events([{"message": "a", "dt": TS}], encoding=encoding)  # type: ignore[arg-type]
        c.exporter.send_events([{"message": "b"}], encoding=encoding, gzip=False)  # type: ignore[arg-type]
    first, second = server.seen
    assert first.headers["Authorization"] == f"Bearer {SRC}"
    assert first.headers["Content-Type"] == ctype
    assert first.headers["Content-Encoding"] == "gzip"
    assert "Content-Encoding" not in second.headers
    assert first.headers["User-Agent"].startswith("grow-codes-better-stack-logs/")
    if encoding == "json":
        assert json.loads(body_of(first)) == [{"message": "a", "dt": "2026-01-02T03:04:05.678000Z"}]
    assert b'"b"' in second.body or b"\xa1b" in second.body


def test_export_splits_and_counts(server: MockServer) -> None:
    server.defaults["/"] = Reply(202, b"")
    with client(server) as c:
        exporter = c.new_exporter(encoding="ndjson", max_request_bytes=400, max_record_bytes=300)
        assert exporter.encoding == "ndjson"
        records = [rec(f"message {i}") for i in range(6)] + [rec("x" * 500)]
        split = exporter.split(records)
        assert split.oversized == 1
        assert len(split.payloads) > 1
        assert all(p.bytes <= 400 for p in split.payloads)
        result = exporter.export(records, gzip=False, timeout_s=5)
    assert (result.accepted, result.rejected) == (6, 1)
    assert isinstance(result.error, sdk.ValidationError)
    assert "larger than 300 bytes" in str(result.error)
    lines = b"".join(s.body for s in server.seen).decode().splitlines()
    assert [json.loads(line)["message"] for line in lines] == [f"message {i}" for i in range(6)]


def test_export_invalid_record_and_retries(server: MockServer) -> None:
    server.enqueue("/", Reply(503, "busy"), Reply(202, b""))
    with client(server) as c:
        exporter = c.new_exporter(encoding="msgpack")
        result = exporter.export([rec("ok"), rec("bad", attributes={"o": object()})])  # type: ignore[dict-item]
    assert (result.accepted, result.rejected, result.retries) == (1, 1, 1)
    assert "not encodable as msgpack" in str(result.error)


def test_export_failure_reports_rejected(server: MockServer) -> None:
    server.enqueue("/", Reply(202, b""), fixture_reply("send.403_bad_token"))
    with client(server) as c:
        exporter = c.new_exporter(max_request_bytes=300)
        result = exporter.export([rec("a" * 100), rec("b" * 100), rec("c" * 100)])
    assert (result.accepted, result.rejected) == (1, 2)
    assert isinstance(result.error, sdk.AuthenticationError)
    exporter.shutdown()


@pytest.mark.parametrize(
    ("name", "cls", "retryable"),
    [
        ("send.403_bad_token", sdk.AuthenticationError, False),
        ("send.403_missing_token", sdk.AuthenticationError, False),
        ("send.402_fault", sdk.RateLimitError, False),
        ("send.406_bad_json", sdk.ValidationError, False),
        ("send.406_bad_msgpack", sdk.ValidationError, False),
        ("send.406_scalar", sdk.ValidationError, False),
        ("send.413_too_large", sdk.ValidationError, False),
        ("send.429_fault", sdk.RateLimitError, True),
        ("send.503_fault", sdk.UnavailableError, True),
    ],
)
def test_send_error_mapping_from_fixtures(
    server: MockServer, name: str, cls: type, retryable: bool
) -> None:
    server.defaults["/"] = fixture_reply(name)
    with client(server, retry=sdk.RetryPolicy(max_retries=0)) as c, pytest.raises(cls) as info:
        c.exporter.send_events([{"message": "x"}])
    assert info.value.retryable is retryable
    assert info.value.http_status == fixture(name)["response"]["status"]
    assert SRC not in str(info.value)


def test_exporter_limits_and_validation(server: MockServer) -> None:
    with client(server) as c:
        with pytest.raises(sdk.ValidationError, match="encoding"):
            c.new_exporter(encoding="xml")  # type: ignore[arg-type]
        for bad in (0, -1, True):
            with pytest.raises(sdk.ValidationError, match="positive"):
                c.new_exporter(max_request_bytes=bad)
        assert c.new_exporter(max_record_bytes=1 << 40).max_record_bytes == sdk.MAX_RECORD_BYTES
        small = c.new_exporter(max_request_bytes=50)
        with pytest.raises(sdk.ValidationError, match="exceeds"):
            small.send_events([{"message": "x" * 100}])
    with client(server, encoding="msgpack") as c:
        assert c.exporter.encoding == "msgpack"
        assert c.exporter is c.exporter
    assert server.seen == []


def test_export_unexpected_failure(server: MockServer, monkeypatch: pytest.MonkeyPatch) -> None:
    with client(server) as c:
        exporter = c.exporter

        def boom(*args: Any, **kwargs: Any) -> Any:
            raise RuntimeError("socket gremlin")

        monkeypatch.setattr(exporter, "_send", boom)
        result = exporter.export([rec("x")])
    assert isinstance(result.error, sdk.UnavailableError)
    assert result.rejected == 1


async def test_async_exporter(server: MockServer) -> None:
    server.enqueue("/", Reply(202, b""), Reply(503, "busy"), Reply(202, b""))
    server.enqueue("/", fixture_reply("send.402_fault"), fixture_reply("send.403_bad_token"))
    async with aclient(server) as c:
        await c.exporter.send_events([{"message": "a"}], encoding="ndjson")
        result = await c.new_exporter(encoding="msgpack").export(
            [rec("b"), rec("bad", attributes={"o": object()})]
        )  # type: ignore[dict-item]
        assert (result.accepted, result.rejected, result.retries) == (1, 1, 1)
        with pytest.raises(sdk.RateLimitError):
            await c.exporter.send_events([{"message": "c"}])
        failed = await c.exporter.export([rec("d")])
        assert isinstance(failed.error, sdk.AuthenticationError)
        assert c.exporter is c.exporter
        await c.exporter.shutdown()
    assert server.seen[0].headers["Content-Type"] == "application/x-ndjson"


# --------------------------------------------------------------------------- OTLP


def decode_request(data: bytes) -> logs_service_pb2.ExportLogsServiceRequest:
    return logs_service_pb2.ExportLogsServiceRequest.FromString(data)


def partial_bytes(rejected: int, message: str = "") -> bytes:
    resp = logs_service_pb2.ExportLogsServiceResponse()
    resp.partial_success.rejected_log_records = rejected
    resp.partial_success.error_message = message
    return bytes(resp.SerializeToString())


def test_encode_otlp_protobuf() -> None:
    records = [
        rec(
            "one",
            severity=sdk.Severity.WARN,
            attributes={"s": "x", "b": True, "i": 3, "f": 0.5, "l": [1, "two"], "m": {"k": "v"}},
            resource={"service.name": "a"},
            trace_id="4bf92f3577b34da6a3ce929d0e0e4736",
            span_id="00f067aa0ba902b7",
            trace_flags=1,
            observed_timestamp=TS,
        ),
        rec("two", resource={"service.name": "b"}),
        rec("three", resource={"service.name": "a"}),
    ]
    req = decode_request(sdk.encode_otlp_protobuf(records, "scope-x"))
    assert len(req.resource_logs) == 2
    first = req.resource_logs[0]
    assert first.resource.attributes[0].value.string_value == "a"
    scope = first.scope_logs[0]
    assert (scope.scope.name, scope.scope.version) == ("scope-x", sdk.__version__)
    one = scope.log_records[0]
    assert [r.body.string_value for r in scope.log_records] == ["one", "three"]
    assert one.time_unix_nano == int(TS.timestamp()) * 10**9 + 678000000
    assert one.observed_time_unix_nano == one.time_unix_nano
    assert (one.severity_number, one.severity_text, one.flags) == (13, "WARN", 1)
    assert one.trace_id.hex() == "4bf92f3577b34da6a3ce929d0e0e4736"
    assert one.span_id.hex() == "00f067aa0ba902b7"
    attrs = {kv.key: kv.value for kv in one.attributes}
    assert attrs["b"].bool_value is True
    assert attrs["i"].int_value == 3
    assert attrs["f"].double_value == 0.5
    assert attrs["l"].array_value.values[1].string_value == "two"
    assert attrs["m"].kvlist_value.values[0].key == "k"
    with pytest.raises(sdk.ValidationError):
        sdk.encode_otlp_protobuf([rec("x", trace_id="not-hex")])


def test_otlp_json_request() -> None:
    doc = sdk.otlp_json_request(
        [
            rec(
                "x",
                attributes={"i": 1, "f": 1.5, "b": False, "l": ["a"], "m": {"k": 2}},
                trace_id="4bf92f3577b34da6a3ce929d0e0e4736",
                span_id="00f067aa0ba902b7",
                trace_flags=1,
                observed_timestamp=TS,
            )
        ]
    )
    item = doc["resourceLogs"][0]["scopeLogs"][0]["logRecords"][0]
    assert item["timeUnixNano"] == item["observedTimeUnixNano"]
    assert item["traceId"] == "4bf92f3577b34da6a3ce929d0e0e4736"
    assert item["flags"] == 1
    assert item["body"] == {"stringValue": "x"}
    values = {kv["key"]: kv["value"] for kv in item["attributes"]}
    assert values["i"] == {"intValue": "1"}
    assert values["f"] == {"doubleValue": 1.5}
    assert values["b"] == {"boolValue": False}
    assert values["l"] == {"arrayValue": {"values": [{"stringValue": "a"}]}}
    assert values["m"] == {"kvlistValue": {"values": [{"key": "k", "value": {"intValue": "2"}}]}}


def test_parse_partial_success() -> None:
    assert sdk.parse_partial_success(partial_bytes(2, "bad")) == sdk.OtlpPartialSuccess(2, "bad")
    assert sdk.parse_partial_success(partial_bytes(0)) is None
    assert sdk.parse_partial_success(b"\xff\xff\xff") is None
    assert sdk.parse_partial_success(b"") is None
    assert sdk.parse_partial_success(
        {"partialSuccess": {"rejectedLogRecords": "3", "errorMessage": ""}}
    ) == sdk.OtlpPartialSuccess(3)
    assert sdk.parse_partial_success({"partialSuccess": {"rejectedLogRecords": "x"}}) is None
    assert sdk.parse_partial_success({}) is None
    p = sdk.OtlpPartialSuccess(1, "m")
    assert "rejected=1" in repr(p)
    assert hash(p) == hash(sdk.OtlpPartialSuccess(1, "m"))
    assert p != "x"


def test_otlp_exporter_protobuf(server: MockServer) -> None:
    server.enqueue("/v1/logs", Reply(200, b""), Reply(200, partial_bytes(1, "too old")))
    with client(server) as c:
        exporter = c.otlp_exporter
        assert exporter is c.otlp_exporter
        assert exporter.protocol == "protobuf"
        assert exporter.export([rec("a")]) == sdk.ExportResult(1, 0, None)
        result = exporter.export([rec("b"), rec("c")], gzip=False)
    assert (result.accepted, result.rejected) == (1, 1)
    assert "rejected 1 record(s): too old" in str(result.error)
    first = server.seen[0]
    assert first.headers["Content-Type"] == "application/x-protobuf"
    assert first.headers["Authorization"] == f"Bearer {SRC}"
    req = decode_request(gzip.decompress(first.body))
    assert req.resource_logs[0].scope_logs[0].log_records[0].body.string_value == "a"


def test_otlp_exporter_json_split_and_errors(server: MockServer) -> None:
    server.defaults["/v1/logs"] = Reply(200, {"partialSuccess": {}})
    with client(server, retry=sdk.RetryPolicy(max_retries=0)) as c:
        exporter = c.new_otlp_exporter(protocol="json", scope_name="s", max_request_bytes=400)
        records = [rec("a"), rec("b"), rec("x" * 1000)]
        result = exporter.export(records, gzip=False)
        assert (result.accepted, result.rejected) == (2, 1)
        assert "dropped" in str(result.error)
        assert len(server.seen) == 2  # one record per request under the 400-byte limit
        server.enqueue("/v1/logs", Reply(200, "not json"), Reply(200, b""), Reply(200, "[1]"))
        for _ in range(3):
            assert exporter.send([rec("c")]) is None
        server.enqueue("/v1/logs", fixture_reply("otlpExport.v1_logs"))
        failed = exporter.export([rec("e")])
        assert isinstance(failed.error, sdk.NotFoundError)
        assert failed.rejected == 1
        exporter.shutdown()
        with pytest.raises(sdk.ValidationError):
            c.new_otlp_exporter(protocol="grpc")  # type: ignore[arg-type]
        with pytest.raises(sdk.ValidationError):
            c.new_otlp_exporter(max_request_bytes=0)
    assert server.seen[0].headers["Content-Type"] == "application/json"
    sent = json.loads(server.seen[0].body)
    assert sent["resourceLogs"][0]["scopeLogs"][0]["scope"]["name"] == "s"


def test_otlp_unexpected_failure(server: MockServer, monkeypatch: pytest.MonkeyPatch) -> None:
    with client(server) as c:
        exporter = c.otlp_exporter

        def boom(*args: Any, **kwargs: Any) -> Any:
            raise RuntimeError("gremlin")

        monkeypatch.setattr(exporter, "send", boom)
        result = exporter.export([rec("x")])
    assert isinstance(result.error, sdk.UnavailableError)


async def test_async_otlp_exporter(server: MockServer) -> None:
    server.enqueue(
        "/v1/logs",
        Reply(200, b""),
        Reply(200, partial_bytes(1)),
        fixture_reply("send.403_bad_token"),
        Reply(200, b""),
    )
    async with aclient(server) as c:
        exporter = c.otlp_exporter
        assert exporter is c.otlp_exporter
        assert (await exporter.export([rec("a")])).accepted == 1
        partial = await exporter.export([rec("b"), rec("bad", trace_id="zz")])
        assert (partial.accepted, partial.rejected) == (0, 2)
        failed = await exporter.export([rec("c")])
        assert isinstance(failed.error, sdk.AuthenticationError)
        assert await c.new_otlp_exporter(protocol="json").send([rec("d")]) is None
        await exporter.shutdown()
