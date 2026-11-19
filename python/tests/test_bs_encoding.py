"""LogRecord → event mapping, JSON / NDJSON / MessagePack encodings and error mapping."""

from __future__ import annotations

import json
import struct
from datetime import date, datetime, timedelta, timezone
from typing import Any

import httpx
import pytest

import grow_better_stack_logs as sdk

UTC = timezone.utc
TS = datetime(2026, 11, 18, 21, 0, 0, 123456, tzinfo=UTC)


def unpack(data: bytes) -> Any:
    """Minimal MessagePack decoder for the tests (timestamps → datetime)."""
    value, pos = _read(data, 0)
    assert pos == len(data)
    return value


def _read(b: bytes, i: int) -> tuple[Any, int]:
    t = b[i]
    i += 1
    if t <= 0x7F:
        return t, i
    if t >= 0xE0:
        return t - 0x100, i
    if 0xA0 <= t <= 0xBF:
        n = t & 0x1F
        return b[i : i + n].decode(), i + n
    if 0x90 <= t <= 0x9F:
        return _array(b, i, t & 0x0F)
    if 0x80 <= t <= 0x8F:
        return _map(b, i, t & 0x0F)
    fixed = {
        0xCC: ">B", 0xCD: ">H", 0xCE: ">I", 0xCF: ">Q",
        0xD0: ">b", 0xD1: ">h", 0xD2: ">i", 0xD3: ">q", 0xCB: ">d",
    }  # fmt: skip
    if t in fixed:
        size = struct.calcsize(fixed[t])
        return struct.unpack(fixed[t], b[i : i + size])[0], i + size
    if t in (0xC0, 0xC2, 0xC3):
        return {0xC0: None, 0xC2: False, 0xC3: True}[t], i
    if t in (0xD9, 0xDA, 0xDB, 0xC4, 0xC5, 0xC6):
        width = {0xD9: 1, 0xDA: 2, 0xDB: 4, 0xC4: 1, 0xC5: 2, 0xC6: 4}[t]
        n = int.from_bytes(b[i : i + width], "big")
        i += width
        raw = b[i : i + n]
        return (raw.decode() if t >= 0xD9 else raw), i + n
    if t in (0xDC, 0xDD):
        width = 2 if t == 0xDC else 4
        return _array(b, i + width, int.from_bytes(b[i : i + width], "big"))
    if t in (0xDE, 0xDF):
        width = 2 if t == 0xDE else 4
        return _map(b, i + width, int.from_bytes(b[i : i + width], "big"))
    if t == 0xD6:
        assert b[i] == 0xFF
        return datetime.fromtimestamp(struct.unpack(">I", b[i + 1 : i + 5])[0], UTC), i + 5
    if t == 0xD7:
        assert b[i] == 0xFF
        v = struct.unpack(">Q", b[i + 1 : i + 9])[0]
        sec, nsec = v & (2**34 - 1), v >> 34
        return datetime.fromtimestamp(sec, UTC) + timedelta(microseconds=nsec // 1000), i + 9
    if t == 0xC7:
        assert b[i : i + 2] == b"\x0c\xff"
        nsec, sec = struct.unpack(">Iq", b[i + 2 : i + 14])
        epoch = datetime(1970, 1, 1, tzinfo=UTC)
        return epoch + timedelta(seconds=sec, microseconds=nsec // 1000), i + 14
    raise AssertionError(f"unknown type {t:#x}")


def _array(b: bytes, i: int, n: int) -> tuple[list[Any], int]:
    out = []
    for _ in range(n):
        v, i = _read(b, i)
        out.append(v)
    return out, i


def _map(b: bytes, i: int, n: int) -> tuple[dict[Any, Any], int]:
    out = {}
    for _ in range(n):
        k, i = _read(b, i)
        v, i = _read(b, i)
        out[k] = v
    return out, i


def test_event_mapping() -> None:
    record = sdk.LogRecord(
        body="hello",
        severity=sdk.Severity.ERROR,
        timestamp=TS,
        attributes={"user": {"id": 42}},
        resource={"service.name": "checkout"},
        observed_timestamp=TS,
        trace_id="4bf92f3577b34da6a3ce929d0e0e4736",
        span_id="00f067aa0ba902b7",
        trace_flags=1,
    )
    event = sdk.to_better_stack_event(record)
    assert event == {
        "dt": TS,
        "message": "hello",
        "level": "error",
        "severity_number": 17,
        "span": {"trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "span_id": "00f067aa0ba902b7"},
        "trace_flags": 1,
        "resource": {"service.name": "checkout"},
        "attributes": {"user": {"id": 42}},
        "observed_dt": TS,
    }
    assert json.loads(sdk.event_json(event))["dt"] == "2026-11-18T21:00:00.123456Z"
    minimal = sdk.to_better_stack_event(
        sdk.LogRecord(body="x", timestamp=TS, trace_id="4bf92f3577b34da6a3ce929d0e0e4736")
    )
    assert set(minimal) == {"dt", "message", "level", "severity_number", "span"}
    assert minimal["span"] == {"trace_id": "4bf92f3577b34da6a3ce929d0e0e4736"}


def test_json_and_ndjson_bodies() -> None:
    events = [
        {"message": "a", "dt": TS, "n": float("nan"), "t": (1, 2), "d": date(2026, 1, 2)},
        {"message": "Grüße ✓"},
    ]
    body = sdk.encode_events(events, "json")
    assert json.loads(body) == [
        {
            "message": "a",
            "dt": "2026-11-18T21:00:00.123456Z",
            "n": None,
            "t": [1, 2],
            "d": "2026-01-02",
        },
        {"message": "Grüße ✓"},
    ]
    assert "Grüße".encode() in body
    nd = sdk.encode_events(events, "ndjson").decode().splitlines()
    assert [json.loads(line)["message"] for line in nd] == ["a", "Grüße ✓"]
    assert sdk.encode_events([], "json") == b"[]"
    assert sdk.encoded_event_size({"a": 1}, "json") == len(b'{"a":1}') + 1
    assert sdk.encoded_event_size({"a": 1}, "msgpack") == len(sdk.encode_msgpack({"a": 1}))
    assert dict(sdk.CONTENT_TYPES) == {
        "json": "application/json",
        "ndjson": "application/x-ndjson",
        "msgpack": "application/msgpack",
    }
    with pytest.raises(sdk.ValidationError, match="encoding"):
        sdk.encode_events(events, "xml")  # type: ignore[arg-type]
    with pytest.raises(sdk.ValidationError, match="JSON"):
        sdk.event_json({"x": object()})


def test_msgpack_values() -> None:
    value = {
        "none": None,
        "t": True,
        "f": False,
        "ints": [0, 127, 128, 255, 256, 65535, 65536, 2**32 - 1, 2**32, 2**64 - 1],
        "neg": [-1, -32, -33, -128, -129, -32768, -32769, -(2**31), -(2**31) - 1, -(2**63)],
        "float": 1.5,
        "short": "x" * 31,
        "str8": "y" * 40,
        "str16": "z" * 300,
        "bin": b"\x00\x01",
        "bin16": bytes(300),
        "list16": list(range(20)),
        "date": date(2026, 1, 2),
        "tuple": (1, "a"),
    }
    assert unpack(sdk.encode_msgpack(value)) == {**value, "date": "2026-01-02", "tuple": [1, "a"]}
    big_map = {f"k{i}": i for i in range(20)}
    assert unpack(sdk.encode_msgpack(big_map)) == big_map
    huge = "w" * 70000
    assert unpack(sdk.encode_msgpack([huge])) == [huge]
    assert len(unpack(sdk.encode_msgpack(bytes(70000)))) == 70000
    assert unpack(sdk.encode_msgpack(list(range(70000))))[-1] == 69999
    assert len(unpack(sdk.encode_msgpack({str(i): 0 for i in range(70000)}))) == 70000
    for bad in (2**64, -(2**63) - 1):
        with pytest.raises(sdk.ValidationError):
            sdk.encode_msgpack(bad)
    with pytest.raises(sdk.ValidationError, match="cannot encode"):
        sdk.encode_msgpack(object())
    nested: Any = []
    for _ in range(105):
        nested = [nested]
    with pytest.raises(sdk.ValidationError, match="deeply"):
        sdk.encode_msgpack(nested)


@pytest.mark.parametrize(
    ("value", "prefix"),
    [
        (datetime(2026, 1, 1, tzinfo=UTC), b"\xd6\xff"),
        (TS, b"\xd7\xff"),
        (datetime(2026, 1, 1), b"\xd6\xff"),  # naive = UTC
        (datetime(1960, 1, 1, 0, 0, 0, 5, tzinfo=UTC), b"\xc7\x0c\xff"),
        (datetime(2600, 1, 1, tzinfo=UTC), b"\xc7\x0c\xff"),
    ],
)
def test_msgpack_timestamps(value: datetime, prefix: bytes) -> None:
    data = sdk.encode_msgpack(value)
    assert data.startswith(prefix)
    expected = value if value.tzinfo else value.replace(tzinfo=UTC)
    assert unpack(data) == expected


# --------------------------------------------------------------------------- errors


def test_map_ingest_error() -> None:
    forbidden = sdk.PermissionDeniedError("Unauthorized", http_status=403, request_id="r1")
    mapped = sdk.map_ingest_error(forbidden)
    assert isinstance(mapped, sdk.AuthenticationError)
    assert (mapped.http_status, mapped.request_id, mapped.retryable) == (403, "r1", False)
    quota = sdk.map_ingest_error(sdk.ValidationError("Quota exceeded", http_status=402))
    assert isinstance(quota, sdk.RateLimitError)
    assert quota.retryable is False
    other = sdk.ValidationError("x", http_status=406)
    assert sdk.map_ingest_error(other) is other
    plain = ValueError("x")
    assert sdk.map_ingest_error(plain) is plain


@pytest.mark.parametrize(
    ("code", "cls", "retryable"),
    [
        (516, sdk.AuthenticationError, False),
        (194, sdk.AuthenticationError, False),
        (497, sdk.PermissionDeniedError, False),
        (60, sdk.NotFoundError, False),
        (81, sdk.NotFoundError, False),
        (62, sdk.ValidationError, False),
        (47, sdk.ValidationError, False),
        (457, sdk.ValidationError, False),
        (241, sdk.ValidationError, False),
        (48, sdk.UnsupportedError, False),
        (159, sdk.TimeoutError, True),
        (202, sdk.RateLimitError, True),
    ],
)
def test_error_for_clickhouse_code(code: int, cls: type, retryable: bool) -> None:
    err = sdk.error_for_clickhouse_code(code, "query: boom", http_status=500, request_id="r")
    assert type(err) is cls
    assert err is not None
    assert (err.http_status, err.request_id, err.retryable) == (500, "r", retryable)
    if code == 241:
        assert "narrow the time range" in err.message


def test_clickhouse_code_and_unknown_codes() -> None:
    assert sdk.clickhouse_code("Code: 60. DB::Exception: x") == 60
    assert sdk.clickhouse_code("nothing here") is None
    assert sdk.error_for_clickhouse_code(999, "x") is None
    err = sdk.UnavailableError("query: Code: 60. DB::Exception: missing", http_status=500)
    mapped = sdk.map_query_error(err)
    assert isinstance(mapped, sdk.NotFoundError)
    assert mapped.http_status == 500
    for unchanged in (
        sdk.UnavailableError("no code"),
        sdk.UnavailableError("Code: 999. DB::Exception: odd"),
        KeyError("x"),
    ):
        assert sdk.map_query_error(unchanged) is unchanged


def test_query_error_from_response() -> None:
    def resp(status: int, body: str, **headers: str) -> sdk.Response:
        return sdk.Response(
            status, httpx.Headers({"content-type": "text/plain", **headers}), body.encode()
        )

    header_wins = resp(404, "Code: 62. DB::Exception: x", **{"X-ClickHouse-Exception-Code": "60"})
    assert isinstance(sdk.query_error_from_response(header_wins, "q", []), sdk.NotFoundError)
    body_code = resp(500, "Code: 47. DB::Exception: Missing columns (secret-pw)")
    err = sdk.query_error_from_response(body_code, "q", ["secret-pw"])
    assert isinstance(err, sdk.ValidationError)
    assert "secret-pw" not in err.message
    plain = sdk.query_error_from_response(resp(503, "busy", **{"Retry-After": "2"}), "q", [])
    assert isinstance(plain, sdk.UnavailableError)
    assert plain.retry_after == 2.0
    odd = sdk.query_error_from_response(resp(500, "Code: 999. DB::Exception: odd"), "q", [])
    assert isinstance(odd, sdk.UnavailableError)
    bad_header = resp(400, "x", **{"X-ClickHouse-Exception-Code": "abc"})
    assert isinstance(sdk.query_error_from_response(bad_header, "q", []), sdk.ValidationError)
