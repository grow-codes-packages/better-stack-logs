"""OTLP/HTTP logs exporter for the ingesting host: ``POST {url}/v1/logs`` (API.md §4).

Protobuf (default, ``application/x-protobuf``) or OTLP JSON, the same Bearer source token as the
HTTP ingest API, optional gzip. :class:`BetterStackOtlpLogExporter` is sync,
:class:`AsyncBetterStackOtlpLogExporter` async.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import Mapping, Sequence
from datetime import datetime, timezone
from typing import Any, Literal

from opentelemetry.proto.collector.logs.v1 import logs_service_pb2
from opentelemetry.proto.common.v1 import common_pb2
from opentelemetry.proto.logs.v1 import logs_pb2
from opentelemetry.proto.resource.v1 import resource_pb2

from ._core import (
    AsyncHttpTransport,
    BetterStackError,
    CancelToken,
    HttpTransport,
    Request,
    Response,
    UnavailableError,
    ValidationError,
)
from ._logging import AttrValue, ExportResult, LogRecord
from ._version import __version__
from .config import BetterStackConfig
from .errors import map_ingest_error
from .exporter import MAX_REQUEST_BYTES, _IngestCore

__all__ = [
    "DEFAULT_SCOPE_NAME",
    "OTLP_LOGS_PATH",
    "AsyncBetterStackOtlpLogExporter",
    "BetterStackOtlpLogExporter",
    "OtlpPartialSuccess",
    "OtlpProtocol",
    "encode_otlp_protobuf",
    "otlp_json_request",
    "parse_partial_success",
]

#: OTLP logs path on the ingesting host.
OTLP_LOGS_PATH = "/v1/logs"
#: Default instrumentation scope name.
DEFAULT_SCOPE_NAME = "grow-better-stack-logs"

OtlpProtocol = Literal["protobuf", "json"]

_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


def _nanos(value: datetime) -> int:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    delta = value - _EPOCH
    return ((delta.days * 86_400 + delta.seconds) * 1_000_000 + delta.microseconds) * 1000


# --------------------------------------------------------------------------- protobuf


def _pb_any(value: AttrValue) -> common_pb2.AnyValue:
    out = common_pb2.AnyValue()
    if isinstance(value, str):
        out.string_value = value
    elif isinstance(value, bool):
        out.bool_value = value
    elif isinstance(value, int):
        out.int_value = value
    elif isinstance(value, float):
        out.double_value = value
    elif isinstance(value, Mapping):
        out.kvlist_value.values.extend(_pb_kvs(value))
    else:
        out.array_value.values.extend(_pb_any(v) for v in value)
    return out


def _pb_kvs(attrs: Mapping[str, AttrValue]) -> list[common_pb2.KeyValue]:
    return [common_pb2.KeyValue(key=str(k), value=_pb_any(v)) for k, v in attrs.items()]


def _group(records: Sequence[LogRecord]) -> list[tuple[Mapping[str, AttrValue], list[LogRecord]]]:
    groups: dict[str, tuple[Mapping[str, AttrValue], list[LogRecord]]] = {}
    for record in records:
        key = json.dumps(record.resource, sort_keys=True, default=str)
        groups.setdefault(key, (record.resource, []))[1].append(record)
    return list(groups.values())


def _pb_record(record: LogRecord) -> logs_pb2.LogRecord:
    out = logs_pb2.LogRecord(
        time_unix_nano=_nanos(record.timestamp),
        severity_number=int(record.severity),  # type: ignore[arg-type]
        severity_text=record.severity_text,
        body=_pb_any(record.body),
    )
    out.attributes.extend(_pb_kvs(record.attributes))
    if record.trace_flags is not None:
        out.flags = record.trace_flags
    if record.trace_id:
        out.trace_id = bytes.fromhex(record.trace_id)
    if record.span_id:
        out.span_id = bytes.fromhex(record.span_id)
    if record.observed_timestamp is not None:
        out.observed_time_unix_nano = _nanos(record.observed_timestamp)
    return out


def encode_otlp_protobuf(
    records: Sequence[LogRecord], scope_name: str = DEFAULT_SCOPE_NAME
) -> bytes:
    """Encode an ``ExportLogsServiceRequest`` (collector/logs/v1) as protobuf bytes.

    Records are grouped by resource; trace/span ids are hex strings in the record and bytes on
    the wire. Raises :class:`ValidationError` for a record that cannot be encoded.
    """
    req = logs_service_pb2.ExportLogsServiceRequest()
    try:
        for resource, group in _group(records):
            rl = req.resource_logs.add()
            rl.resource.CopyFrom(resource_pb2.Resource(attributes=_pb_kvs(resource)))
            sl = rl.scope_logs.add()
            sl.scope.name = scope_name
            sl.scope.version = __version__
            sl.log_records.extend(_pb_record(r) for r in group)
        return bytes(req.SerializeToString())
    except (TypeError, ValueError, OverflowError) as exc:
        raise ValidationError(f"logs.otlp: record cannot be encoded: {exc}") from None


# --------------------------------------------------------------------------- OTLP JSON


def _json_any(value: AttrValue) -> dict[str, Any]:
    if isinstance(value, str):
        return {"stringValue": value}
    if isinstance(value, bool):
        return {"boolValue": value}
    if isinstance(value, int):
        return {"intValue": str(value)}
    if isinstance(value, float):
        return {"doubleValue": value}
    if isinstance(value, Mapping):
        return {"kvlistValue": {"values": _json_kvs(value)}}
    return {"arrayValue": {"values": [_json_any(v) for v in value]}}


def _json_kvs(attrs: Mapping[str, AttrValue]) -> list[dict[str, Any]]:
    return [{"key": str(k), "value": _json_any(v)} for k, v in attrs.items()]


def otlp_json_request(
    records: Sequence[LogRecord], scope_name: str = DEFAULT_SCOPE_NAME
) -> dict[str, Any]:
    """The OTLP JSON ``ExportLogsServiceRequest`` object (ids as hex, 64-bit ints as strings)."""
    resource_logs = []
    for resource, group in _group(records):
        log_records = []
        for r in group:
            item: dict[str, Any] = {
                "timeUnixNano": str(_nanos(r.timestamp)),
                "severityNumber": int(r.severity),
                "severityText": r.severity_text,
                "body": _json_any(r.body),
                "attributes": _json_kvs(r.attributes),
            }
            if r.observed_timestamp is not None:
                item["observedTimeUnixNano"] = str(_nanos(r.observed_timestamp))
            if r.trace_flags is not None:
                item["flags"] = r.trace_flags
            if r.trace_id:
                item["traceId"] = r.trace_id
            if r.span_id:
                item["spanId"] = r.span_id
            log_records.append(item)
        resource_logs.append(
            {
                "resource": {"attributes": _json_kvs(resource)},
                "scopeLogs": [
                    {
                        "scope": {"name": scope_name, "version": __version__},
                        "logRecords": log_records,
                    }
                ],
            }
        )
    return {"resourceLogs": resource_logs}


# --------------------------------------------------------------------------- partial success


class OtlpPartialSuccess:
    """Partial success reported by an OTLP response (``rejected`` records, optional message)."""

    __slots__ = ("message", "rejected")

    def __init__(self, rejected: int, message: str | None = None) -> None:
        self.rejected = rejected
        self.message = message

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, OtlpPartialSuccess):
            return NotImplemented
        return (self.rejected, self.message) == (other.rejected, other.message)

    def __hash__(self) -> int:
        return hash((self.rejected, self.message))

    def __repr__(self) -> str:
        return f"OtlpPartialSuccess(rejected={self.rejected}, message={self.message!r})"


def parse_partial_success(body: bytes | Mapping[str, Any]) -> OtlpPartialSuccess | None:
    """Read ``partial_success`` from an ``ExportLogsServiceResponse`` (protobuf bytes or a
    JSON object). ``None`` when nothing was rejected or the body cannot be read."""
    if isinstance(body, (bytes, bytearray)):
        try:
            resp = logs_service_pb2.ExportLogsServiceResponse.FromString(bytes(body))
        except Exception:  # noqa: BLE001 - protobuf DecodeError: not a response message
            return None
        rejected = int(resp.partial_success.rejected_log_records)
        message: Any = resp.partial_success.error_message
    else:
        ps = body.get("partialSuccess")
        if not isinstance(ps, Mapping):
            return None
        try:
            rejected = int(ps.get("rejectedLogRecords") or 0)
        except (TypeError, ValueError):
            return None
        message = ps.get("errorMessage")
    if rejected <= 0:
        return None
    return OtlpPartialSuccess(rejected, message if isinstance(message, str) and message else None)


# --------------------------------------------------------------------------- exporters


class _OtlpCore:
    def __init__(
        self,
        config: BetterStackConfig,
        *,
        protocol: str,
        scope_name: str,
        max_request_bytes: int | None,
    ) -> None:
        if protocol not in ("protobuf", "json"):
            raise ValidationError(f"logs.otlp: protocol must be protobuf or json, got {protocol!r}")
        if max_request_bytes is not None and (
            isinstance(max_request_bytes, bool) or max_request_bytes < 1
        ):
            raise ValidationError("logs.otlp: max_request_bytes must be a positive integer")
        #: Payload format.
        self.protocol: OtlpProtocol = protocol  # type: ignore[assignment]
        self.scope_name = scope_name
        self.max_request_bytes = min(max_request_bytes or MAX_REQUEST_BYTES, MAX_REQUEST_BYTES)
        self._ingest = _IngestCore(
            config, encoding=None, max_request_bytes=None, max_record_bytes=None
        )

    def encode(self, records: Sequence[LogRecord]) -> bytes:
        """Encode records as one request body (protobuf bytes or OTLP JSON text)."""
        if self.protocol == "protobuf":
            return encode_otlp_protobuf(records, self.scope_name)
        return json.dumps(
            otlp_json_request(records, self.scope_name), separators=(",", ":"), ensure_ascii=False
        ).encode("utf-8")

    def chunks(self, records: Sequence[LogRecord]) -> tuple[list[list[LogRecord]], int]:
        """Split records into chunks under the request limit; also returns the dropped count."""
        chunks: list[list[LogRecord]] = []
        oversized = 0
        current: list[LogRecord] = []
        total = 0
        for record in records:
            try:
                size = len(self.encode([record]))
            except (ValidationError, TypeError, ValueError):
                oversized += 1
                continue
            if size > self.max_request_bytes:
                oversized += 1
                continue
            if current and total + size > self.max_request_bytes:
                chunks.append(current)
                current, total = [], 0
            current.append(record)
            total += size
        if current:
            chunks.append(current)
        return chunks, oversized

    def request(self, records: Sequence[LogRecord], gzip: bool, timeout_s: float | None) -> Request:
        ctype = "application/x-protobuf" if self.protocol == "protobuf" else "application/json"
        return self._ingest.post(
            OTLP_LOGS_PATH,
            self.encode(records),
            ctype,
            gzip=gzip,
            timeout_s=timeout_s,
            op="logs.otlp",
            accept=ctype,
        )

    def partial(self, resp: Response) -> OtlpPartialSuccess | None:
        if self.protocol == "protobuf":
            return parse_partial_success(resp.content)
        text = resp.text.strip()
        if not text:
            return None
        try:
            doc = json.loads(text)
        except ValueError:
            return None
        return parse_partial_success(doc) if isinstance(doc, Mapping) else None

    def initial(self, oversized: int) -> tuple[int, BetterStackError | None]:
        if not oversized:
            return 0, None
        return oversized, ValidationError(
            f"logs.otlp: {oversized} record(s) dropped: not encodable or larger than "
            f"{self.max_request_bytes} bytes"
        )

    @staticmethod
    def rejected_error(bad: int, partial: OtlpPartialSuccess | None) -> ValidationError:
        suffix = f": {partial.message}" if partial is not None and partial.message else ""
        return ValidationError(f"logs.otlp: the service rejected {bad} record(s){suffix}")

    @staticmethod
    def failure(err: BaseException) -> BetterStackError:
        if isinstance(err, BetterStackError):
            return err
        return UnavailableError("logs.otlp: unexpected failure", cause=err)


class BetterStackOtlpLogExporter(_OtlpCore):
    """:class:`LogExporter` that sends OTLP/HTTP logs to the source's ingesting host
    (``POST {url}/v1/logs``) with the source token.

    Records are grouped by resource; batches are split so that no request exceeds
    ``max_request_bytes``. ``partial_success`` in the response counts as rejected records.
    Retried like idempotent calls (duplicates possible after an ambiguous failure).
    """

    def __init__(
        self,
        transport: HttpTransport,
        config: BetterStackConfig,
        *,
        protocol: OtlpProtocol = "protobuf",
        scope_name: str = DEFAULT_SCOPE_NAME,
        max_request_bytes: int | None = None,
    ) -> None:
        super().__init__(
            config, protocol=protocol, scope_name=scope_name, max_request_bytes=max_request_bytes
        )
        self._transport = transport

    def send(
        self,
        records: Sequence[LogRecord],
        *,
        gzip: bool = True,
        timeout_s: float | None = None,
        cancel: CancelToken | None = None,
    ) -> OtlpPartialSuccess | None:
        """Post one OTLP request for ``records``; returns the partial success when the service
        reports rejected records. Raises the mapped SDK error."""
        try:
            resp = self._transport.request(self.request(records, gzip, timeout_s), cancel=cancel)
        except BetterStackError as err:
            raise map_ingest_error(err) from None
        return self.partial(resp)

    def export(
        self, records: Sequence[LogRecord], *, gzip: bool = True, timeout_s: float = 30.0
    ) -> ExportResult:
        """Send the records; never raises for service errors (see :class:`LogExporter`)."""
        chunks, oversized = self.chunks(records)
        rejected, error = self.initial(oversized)
        accepted = 0
        for chunk in chunks:
            try:
                partial = self.send(chunk, gzip=gzip, timeout_s=timeout_s)
            except Exception as err:  # noqa: BLE001 - reported in the result
                return ExportResult(accepted, len(records) - accepted, self.failure(err))
            bad = min(partial.rejected if partial else 0, len(chunk))
            accepted += len(chunk) - bad
            if bad:
                rejected += bad
                error = self.rejected_error(bad, partial)
        return ExportResult(accepted, rejected, error)

    def shutdown(self) -> None:
        """Nothing to release; the client owns the transport."""


class AsyncBetterStackOtlpLogExporter(_OtlpCore):
    """Async :class:`BetterStackOtlpLogExporter` (an :class:`AsyncLogExporter`)."""

    def __init__(
        self,
        transport: AsyncHttpTransport,
        config: BetterStackConfig,
        *,
        protocol: OtlpProtocol = "protobuf",
        scope_name: str = DEFAULT_SCOPE_NAME,
        max_request_bytes: int | None = None,
    ) -> None:
        super().__init__(
            config, protocol=protocol, scope_name=scope_name, max_request_bytes=max_request_bytes
        )
        self._transport = transport

    async def send(
        self, records: Sequence[LogRecord], *, gzip: bool = True, timeout_s: float | None = None
    ) -> OtlpPartialSuccess | None:
        """See :meth:`BetterStackOtlpLogExporter.send`."""
        try:
            resp = await self._transport.request(self.request(records, gzip, timeout_s))
        except BetterStackError as err:
            raise map_ingest_error(err) from None
        return self.partial(resp)

    async def export(
        self, records: Sequence[LogRecord], *, gzip: bool = True, timeout_s: float = 30.0
    ) -> ExportResult:
        """See :meth:`BetterStackOtlpLogExporter.export`."""
        chunks, oversized = self.chunks(records)
        rejected, error = self.initial(oversized)
        accepted = 0
        for chunk in chunks:
            try:
                partial = await self.send(chunk, gzip=gzip, timeout_s=timeout_s)
            except asyncio.CancelledError:
                raise
            except Exception as err:  # noqa: BLE001 - reported in the result
                return ExportResult(accepted, len(records) - accepted, self.failure(err))
            bad = min(partial.rejected if partial else 0, len(chunk))
            accepted += len(chunk) - bad
            if bad:
                rejected += bad
                error = self.rejected_error(bad, partial)
        return ExportResult(accepted, rejected, error)

    async def shutdown(self) -> None:
        """Nothing to release; the client owns the transport."""
