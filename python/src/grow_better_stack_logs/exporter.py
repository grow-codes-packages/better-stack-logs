"""The Better Stack ingest exporter: ``POST {ingesting host}/`` (API.md §3).

:class:`BetterStackLogExporter` (sync, for :class:`BatchingLogClient`) and
:class:`AsyncBetterStackLogExporter` (async, for :class:`AsyncBatchingLogClient`).
"""

from __future__ import annotations

import asyncio
import dataclasses
import gzip as _gzip
from collections.abc import Mapping, Sequence
from typing import Any

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
from ._logging import ExportResult, LogRecord
from .config import ENCODINGS, BetterStackConfig, IngestEncoding
from .encoding import (
    CONTENT_TYPES,
    BetterStackEvent,
    encode_events,
    encoded_event_size,
    to_better_stack_event,
)
from .errors import map_ingest_error

__all__ = [
    "MAX_RECORD_BYTES",
    "MAX_REQUEST_BYTES",
    "AsyncBetterStackLogExporter",
    "BetterStackLogExporter",
    "IngestPayload",
    "SplitResult",
]

#: Better Stack limit for one request body (measured compressed): 10 MiB. The exporter never
#: builds a larger uncompressed body, so the limit always holds.
MAX_REQUEST_BYTES = 10 * 1024 * 1024
#: Better Stack limit for one record, uncompressed: 10 MiB.
MAX_RECORD_BYTES = 10 * 1024 * 1024

_FRAME = 8  # upper bound of the framing bytes per request


@dataclasses.dataclass(frozen=True)
class IngestPayload:
    """One request body before compression."""

    events: list[BetterStackEvent]
    bytes: int  #: uncompressed body size estimate including separators


@dataclasses.dataclass(frozen=True)
class SplitResult:
    """Result of :meth:`BetterStackLogExporter.split`."""

    payloads: list[IngestPayload]
    oversized: int = 0  #: records larger than ``max_record_bytes`` (never sent)
    invalid: int = 0  #: records that cannot be encoded (never sent)


def _cap(value: int | None, name: str) -> int:
    if value is None:
        return MAX_REQUEST_BYTES
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise ValidationError(f"logs.exporter: {name} must be a positive integer")
    return min(value, MAX_REQUEST_BYTES)


class _IngestCore:
    """Encoding, splitting and request building shared by the sync and async exporters."""

    def __init__(
        self,
        config: BetterStackConfig,
        *,
        encoding: IngestEncoding | None,
        max_request_bytes: int | None,
        max_record_bytes: int | None,
    ) -> None:
        self.config = config
        enc = encoding if encoding is not None else config.encoding
        if enc not in ENCODINGS:
            raise ValidationError(
                f"logs.exporter: encoding must be json, ndjson or msgpack, got {enc!r}"
            )
        #: Default body encoding (``config.encoding`` unless overridden).
        self.encoding: IngestEncoding = enc
        self.max_request_bytes = _cap(max_request_bytes, "max_request_bytes")
        self.max_record_bytes = _cap(max_record_bytes, "max_record_bytes")

    def split(self, records: Sequence[LogRecord]) -> SplitResult:
        """Map records to events and split them into request bodies (no I/O)."""
        payloads: list[IngestPayload] = []
        oversized = invalid = 0
        current: list[BetterStackEvent] = []
        size_total = _FRAME
        for record in records:
            event = to_better_stack_event(record)
            try:
                size = encoded_event_size(event, self.encoding)
            except ValidationError:
                invalid += 1
                continue
            if size > self.max_record_bytes or size + _FRAME > self.max_request_bytes:
                oversized += 1
                continue
            if current and size_total + size > self.max_request_bytes:
                payloads.append(IngestPayload(current, size_total))
                current, size_total = [], _FRAME
            current.append(event)
            size_total += size
        if current:
            payloads.append(IngestPayload(current, size_total))
        return SplitResult(payloads, oversized, invalid)

    def request(
        self,
        events: Sequence[Mapping[str, Any]] | Sequence[Any],
        *,
        encoding: IngestEncoding | None,
        gzip: bool,
        timeout_s: float | None,
        operation: str = "logs.send",
    ) -> Request:
        enc = encoding if encoding is not None else self.encoding
        body = encode_events(list(events), enc)
        if len(body) > self.max_request_bytes:
            raise ValidationError(
                f"logs.send: request body of {len(body)} bytes exceeds "
                f"{self.max_request_bytes} bytes"
            )
        return self.post(
            "/", body, CONTENT_TYPES[enc], gzip=gzip, timeout_s=timeout_s, op=operation
        )

    def post(
        self,
        path: str,
        body: bytes,
        content_type: str,
        *,
        gzip: bool,
        timeout_s: float | None,
        op: str,
        accept: str | None = None,
    ) -> Request:
        token = self.config.source_token.reveal() if self.config.source_token else ""
        headers = {"Authorization": f"Bearer {token}", "Content-Type": content_type}
        if accept is not None:
            headers["Accept"] = accept
        if gzip:
            headers["Content-Encoding"] = "gzip"
            body = _gzip.compress(body)
        return Request(
            "POST",
            path,
            operation=op,
            content=body,
            headers=headers,
            idempotent=True,
            timeout=timeout_s,
        )

    def dropped_result(self, split: SplitResult, accepted: int, retries: int) -> ExportResult:
        if split.oversized + split.invalid == 0:
            return ExportResult(accepted=accepted, rejected=0, retries=retries)
        reasons = []
        if split.oversized:
            reasons.append(f"{split.oversized} larger than {self.max_record_bytes} bytes")
        if split.invalid:
            reasons.append(f"{split.invalid} not encodable as {self.encoding}")
        return ExportResult(
            accepted=accepted,
            rejected=split.oversized + split.invalid,
            error=ValidationError(f"logs.send: record(s) dropped: {', '.join(reasons)}"),
            retries=retries,
        )


def _failure(err: BaseException) -> BetterStackError:
    if isinstance(err, BetterStackError):
        return err
    return UnavailableError("logs.send: unexpected failure", cause=err)


class BetterStackLogExporter(_IngestCore):
    """:class:`LogExporter` for Better Stack's HTTP ingest API.

    Maps records to events (:func:`to_better_stack_event`), splits batches so that no request
    exceeds 10 MiB, encodes them as JSON, NDJSON or MessagePack, gzips when asked and posts with
    ``Authorization: Bearer <source token>``. Exports are retried like idempotent calls (429,
    5xx, network errors); Better Stack does not deduplicate, so an ambiguous failure may store
    a record twice. Create it with :meth:`BetterStackClient.new_exporter` or use
    :attr:`BetterStackClient.exporter`.
    """

    def __init__(
        self,
        transport: HttpTransport,
        config: BetterStackConfig,
        *,
        encoding: IngestEncoding | None = None,
        max_request_bytes: int | None = None,
        max_record_bytes: int | None = None,
    ) -> None:
        super().__init__(
            config,
            encoding=encoding,
            max_request_bytes=max_request_bytes,
            max_record_bytes=max_record_bytes,
        )
        self._transport = transport

    def _send(self, req: Request, cancel: CancelToken | None = None) -> Response:
        try:
            return self._transport.request(req, cancel=cancel)
        except BetterStackError as err:
            raise map_ingest_error(err) from None

    def send_events(
        self,
        events: Sequence[Mapping[str, Any]],
        *,
        encoding: IngestEncoding | None = None,
        gzip: bool = True,
        cancel: CancelToken | None = None,
    ) -> None:
        """Post raw events (JSON objects; ``dt`` may be a :class:`datetime`, an RFC 3339 string
        or UNIX seconds/ms/ns) as one request, without splitting.

        Raises the mapped SDK error: 403 → :class:`AuthenticationError`, 402 →
        :class:`RateLimitError` (not retryable), 406/413 → :class:`ValidationError`. ``cancel``
        aborts the call from another thread.
        """
        self._send(self.request(events, encoding=encoding, gzip=gzip, timeout_s=None), cancel)

    def export(
        self, records: Sequence[LogRecord], *, gzip: bool = True, timeout_s: float = 30.0
    ) -> ExportResult:
        """Send the records; a failing request stops the export (later records count as
        rejected). Never raises for service errors."""
        split = self.split(records)
        accepted = retries = 0
        for payload in split.payloads:
            try:
                resp = self._send(
                    self.request(payload.events, encoding=None, gzip=gzip, timeout_s=timeout_s)
                )
            except Exception as err:  # noqa: BLE001 - reported in the result
                return ExportResult(
                    accepted=accepted,
                    rejected=len(records) - accepted,
                    error=_failure(err),
                    retries=retries,
                )
            accepted += len(payload.events)
            retries += resp.attempts - 1
        return self.dropped_result(split, accepted, retries)

    def shutdown(self) -> None:
        """Nothing to release; the client owns the transport."""


class AsyncBetterStackLogExporter(_IngestCore):
    """Async :class:`BetterStackLogExporter` (an :class:`AsyncLogExporter`)."""

    def __init__(
        self,
        transport: AsyncHttpTransport,
        config: BetterStackConfig,
        *,
        encoding: IngestEncoding | None = None,
        max_request_bytes: int | None = None,
        max_record_bytes: int | None = None,
    ) -> None:
        super().__init__(
            config,
            encoding=encoding,
            max_request_bytes=max_request_bytes,
            max_record_bytes=max_record_bytes,
        )
        self._transport = transport

    async def _send(self, req: Request) -> Response:
        try:
            return await self._transport.request(req)
        except BetterStackError as err:
            raise map_ingest_error(err) from None

    async def send_events(
        self,
        events: Sequence[Mapping[str, Any]],
        *,
        encoding: IngestEncoding | None = None,
        gzip: bool = True,
    ) -> None:
        """See :meth:`BetterStackLogExporter.send_events`."""
        await self._send(self.request(events, encoding=encoding, gzip=gzip, timeout_s=None))

    async def export(
        self, records: Sequence[LogRecord], *, gzip: bool = True, timeout_s: float = 30.0
    ) -> ExportResult:
        """See :meth:`BetterStackLogExporter.export`."""
        split = self.split(records)
        accepted = retries = 0
        for payload in split.payloads:
            try:
                resp = await self._send(
                    self.request(payload.events, encoding=None, gzip=gzip, timeout_s=timeout_s)
                )
            except asyncio.CancelledError:
                raise
            except Exception as err:  # noqa: BLE001 - reported in the result
                return ExportResult(
                    accepted=accepted,
                    rejected=len(records) - accepted,
                    error=_failure(err),
                    retries=retries,
                )
            accepted += len(payload.events)
            retries += resp.attempts - 1
        return self.dropped_result(split, accepted, retries)

    async def shutdown(self) -> None:
        """Nothing to release; the client owns the transport."""
