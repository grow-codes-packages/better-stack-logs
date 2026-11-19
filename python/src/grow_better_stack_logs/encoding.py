"""LogRecord → Better Stack event mapping and the three ingest encodings (API.md §3).

JSON (one array per request), NDJSON (one object per line) and MessagePack (one array, ``dt``
as the timestamp extension type -1).
"""

from __future__ import annotations

import json
import math
import struct
from collections.abc import Mapping, Sequence
from datetime import date, datetime, timezone
from typing import Any

from ._core import ValidationError
from ._logging import LogRecord, format_timestamp
from .config import ENCODINGS, IngestEncoding

__all__ = [
    "CONTENT_TYPES",
    "BetterStackEvent",
    "encode_events",
    "encode_msgpack",
    "encoded_event_size",
    "event_json",
    "to_better_stack_event",
]

#: Content-Type sent for each :data:`IngestEncoding`.
CONTENT_TYPES: Mapping[str, str] = {
    "json": "application/json",
    "ndjson": "application/x-ndjson",
    "msgpack": "application/msgpack",
}

BetterStackEvent = dict[str, Any]
"""One Better Stack event: a JSON object; ``dt`` is a :class:`datetime` until it is encoded."""


def to_better_stack_event(record: LogRecord) -> BetterStackEvent:
    """Map a :class:`LogRecord` to the Better Stack event shape.

    ``{dt, message, level, severity_number, span: {trace_id, span_id}, trace_flags, resource,
    attributes, observed_dt}``. ``span.trace_id``/``span.span_id`` are Better Stack's
    log ↔ trace correlation fields. Empty ``resource``/``attributes`` are left out.
    """
    event: BetterStackEvent = {
        "dt": record.timestamp,
        "message": record.body,
        "level": record.severity_text.lower(),
        "severity_number": int(record.severity),
    }
    if record.trace_id:
        span: dict[str, str] = {"trace_id": record.trace_id}
        if record.span_id:
            span["span_id"] = record.span_id
        event["span"] = span
        if record.trace_flags is not None:
            event["trace_flags"] = record.trace_flags
    if record.resource:
        event["resource"] = dict(record.resource)
    if record.attributes:
        event["attributes"] = dict(record.attributes)
    if record.observed_timestamp is not None:
        event["observed_dt"] = record.observed_timestamp
    return event


def _json_value(value: Any) -> Any:
    """JSON-ready copy: datetimes → RFC 3339 UTC, non-finite floats → null, tuples → lists."""
    if isinstance(value, datetime):
        return format_timestamp(value)
    if isinstance(value, float) and not math.isfinite(value):
        return None
    if isinstance(value, Mapping):
        return {str(k): _json_value(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_value(v) for v in value]
    if isinstance(value, date):
        return value.isoformat()
    return value


def event_json(event: Any) -> str:
    """Compact JSON text of one event (:class:`datetime` → RFC 3339 UTC)."""
    try:
        return json.dumps(
            _json_value(event), separators=(",", ":"), ensure_ascii=False, allow_nan=False
        )
    except (TypeError, ValueError) as exc:
        raise ValidationError(f"logs.send: event cannot be encoded as JSON: {exc}") from None


def _check_encoding(encoding: str) -> IngestEncoding:
    if encoding not in ENCODINGS:
        raise ValidationError(f"logs.send: unknown encoding {encoding!r}")
    return encoding  # type: ignore[return-value]


def encode_events(events: Sequence[Any], encoding: IngestEncoding) -> bytes:
    """Encode events as one request body.

    ``json``: a JSON array; ``ndjson``: one object per line; ``msgpack``: a MessagePack array
    with :class:`datetime` values as timestamp extensions (type -1).
    """
    enc = _check_encoding(encoding)
    if enc == "json":
        return ("[" + ",".join(event_json(e) for e in events) + "]").encode("utf-8")
    if enc == "ndjson":
        return "".join(event_json(e) + "\n" for e in events).encode("utf-8")
    return encode_msgpack(list(events))


def encoded_event_size(event: Any, encoding: IngestEncoding) -> int:
    """Encoded size of one event inside a body of ``encoding`` (separators included)."""
    if _check_encoding(encoding) == "msgpack":
        return len(encode_msgpack(event))
    return len(event_json(event).encode("utf-8")) + 1


# --------------------------------------------------------------------------- MessagePack


def _length(out: bytearray, n: int, fix: int | None, fix_max: int, codes: tuple[int, ...]) -> None:
    c8, c16, c32 = codes
    if fix is not None and n <= fix_max:
        out.append(fix | n)
    elif n < 256 and c8:
        out += bytes((c8, n))
    elif n < 65536:
        out.append(c16)
        out += struct.pack(">H", n)
    else:
        out.append(c32)
        out += struct.pack(">I", n)


def _int(out: bytearray, v: int) -> None:
    if 0 <= v < 128:
        out.append(v)
    elif -32 <= v < 0:
        out.append(v & 0xFF)
    elif 0 <= v < 2**8:
        out += bytes((0xCC, v))
    elif 0 <= v < 2**16:
        out.append(0xCD)
        out += struct.pack(">H", v)
    elif 0 <= v < 2**32:
        out.append(0xCE)
        out += struct.pack(">I", v)
    elif 0 <= v < 2**64:
        out.append(0xCF)
        out += struct.pack(">Q", v)
    elif -(2**7) <= v < 0:
        out.append(0xD0)
        out += struct.pack(">b", v)
    elif -(2**15) <= v < 0:
        out.append(0xD1)
        out += struct.pack(">h", v)
    elif -(2**31) <= v < 0:
        out.append(0xD2)
        out += struct.pack(">i", v)
    elif -(2**63) <= v < 0:
        out.append(0xD3)
        out += struct.pack(">q", v)
    else:
        raise ValidationError("msgpack: integer out of range")


def _timestamp(out: bytearray, value: datetime) -> None:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    delta = value - datetime(1970, 1, 1, tzinfo=timezone.utc)
    micros = (delta.days * 86_400 + delta.seconds) * 1_000_000 + delta.microseconds
    sec, rem = divmod(micros, 1_000_000)
    nsec = rem * 1000
    if 0 <= sec < 2**34:
        if nsec == 0 and sec < 2**32:
            out += b"\xd6\xff" + struct.pack(">I", sec)
        else:
            out += b"\xd7\xff" + struct.pack(">Q", (nsec << 34) | sec)
    else:
        out += b"\xc7\x0c\xff" + struct.pack(">Iq", nsec, sec)


def _write(out: bytearray, value: Any, depth: int) -> None:
    if depth > 100:
        raise ValidationError("msgpack: value nested too deeply")
    if value is None:
        out.append(0xC0)
    elif isinstance(value, bool):
        out.append(0xC3 if value else 0xC2)
    elif isinstance(value, int):
        _int(out, value)
    elif isinstance(value, float):
        out.append(0xCB)
        out += struct.pack(">d", value)
    elif isinstance(value, str):
        data = value.encode("utf-8")
        _length(out, len(data), 0xA0, 31, (0xD9, 0xDA, 0xDB))
        out += data
    elif isinstance(value, datetime):
        _timestamp(out, value)
    elif isinstance(value, (bytes, bytearray, memoryview)):
        data = bytes(value)
        _length(out, len(data), None, -1, (0xC4, 0xC5, 0xC6))
        out += data
    elif isinstance(value, Mapping):
        _length(out, len(value), 0x80, 15, (0, 0xDE, 0xDF))
        for k, v in value.items():
            _write(out, str(k), depth + 1)
            _write(out, v, depth + 1)
    elif isinstance(value, (list, tuple)):
        _length(out, len(value), 0x90, 15, (0, 0xDC, 0xDD))
        for item in value:
            _write(out, item, depth + 1)
    elif isinstance(value, date):
        _write(out, value.isoformat(), depth + 1)
    else:
        raise ValidationError(f"msgpack: cannot encode a {type(value).__name__}")


def encode_msgpack(value: Any) -> bytes:
    """Encode a value as MessagePack (:class:`datetime` → timestamp extension, bytes → bin)."""
    out = bytearray()
    _write(out, value, 0)
    return bytes(out)
