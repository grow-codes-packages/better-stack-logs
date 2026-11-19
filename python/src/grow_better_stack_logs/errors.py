"""Better Stack specific error mapping on top of the generated transport.

* Ingest host: ``403`` (invalid or missing source token) → :class:`AuthenticationError`
  (DECISIONS 3); ``402`` (quota or spending limit) → :class:`RateLimitError` with
  ``retryable=False`` (DECISIONS 2). ``406``/``413`` stay :class:`ValidationError`.
* SQL host: the ClickHouse exception code (``X-ClickHouse-Exception-Code`` header, else
  ``Code: N. DB::Exception`` in the body) wins over the HTTP status (API.md §5.1).
"""

from __future__ import annotations

import re

import httpx

from ._core import (
    AuthenticationError,
    BetterStackError,
    NotFoundError,
    PermissionDeniedError,
    RateLimitError,
    Response,
    TimeoutError,
    UnsupportedError,
    ValidationError,
    error_from_status,
    parse_retry_after,
)

__all__ = [
    "CLICKHOUSE_EXCEPTION_HEADER",
    "clickhouse_code",
    "error_for_clickhouse_code",
    "map_ingest_error",
    "map_query_error",
    "query_error_from_response",
]

#: Response header carrying the ClickHouse exception code of a failed query.
CLICKHOUSE_EXCEPTION_HEADER = "X-ClickHouse-Exception-Code"

_CODE_RE = re.compile(r"Code:\s*(\d+)\.\s*DB::Exception")
_AUTH_CODES = frozenset({516, 192, 193, 194})
_NOT_FOUND_CODES = frozenset({60, 81})
_VALIDATION_CODES = frozenset({62, 47, 46, 36, 53, 42, 73, 456, 457})
_TIMEOUT_CODES = frozenset({159, 160})


def _copy(err: BetterStackError, cls: type[BetterStackError], **kw: object) -> BetterStackError:
    values: dict[str, object] = {
        "http_status": err.http_status,
        "request_id": err.request_id,
        "retry_after": err.retry_after,
        "cause": err.cause,
    }
    values.update(kw)
    return cls(err.message, **values)  # type: ignore[arg-type]


def map_ingest_error(err: BaseException) -> BaseException:
    """Map an ingest-host error: ``403`` → :class:`AuthenticationError`, ``402`` → non-retryable
    :class:`RateLimitError`. Anything else is returned unchanged."""
    if not isinstance(err, BetterStackError):
        return err
    if err.http_status == 403 and isinstance(err, PermissionDeniedError):
        return _copy(err, AuthenticationError, retryable=False)
    if err.http_status == 402:
        return _copy(err, RateLimitError, retryable=False)
    return err


def clickhouse_code(text: str) -> int | None:
    """The ClickHouse exception code in ``text`` (``Code: 60. DB::Exception: …``), or ``None``."""
    match = _CODE_RE.search(text)
    return int(match.group(1)) if match else None


def error_for_clickhouse_code(
    code: int,
    message: str,
    *,
    http_status: int | None = None,
    request_id: str | None = None,
) -> BetterStackError | None:
    """The SDK error for a ClickHouse exception code, or ``None`` when the code has no mapping.

    516/192/193/194 → authentication, 497 → permission denied, 60/81 → not found,
    62/47/46/36/53/42/73/456/457 → validation, 241 (memory limit) → validation with a hint,
    48 → unsupported, 159/160 → timeout, 202 (too many simultaneous queries) → rate limited
    (retryable).
    """
    kw: dict[str, object] = {"http_status": http_status, "request_id": request_id}
    cls: type[BetterStackError] | None = None
    if code in _AUTH_CODES:
        cls = AuthenticationError
    elif code == 497:
        cls = PermissionDeniedError
    elif code in _NOT_FOUND_CODES:
        cls = NotFoundError
    elif code in _VALIDATION_CODES:
        cls = ValidationError
    elif code == 241:
        cls = ValidationError
        message += " (hint: narrow the time range or sample the cold table)"
    elif code == 48:
        cls = UnsupportedError
    elif code in _TIMEOUT_CODES:
        cls = TimeoutError
    elif code == 202:
        cls = RateLimitError
        kw["retryable"] = True
    if cls is None:
        return None
    return cls(message, **kw)  # type: ignore[arg-type]


def _header_code(headers: httpx.Headers) -> int | None:
    raw = (headers.get(CLICKHOUSE_EXCEPTION_HEADER) or "").strip()
    return int(raw) if raw.isdigit() and int(raw) > 0 else None


def query_error_from_response(
    resp: Response, operation: str, known_secrets: list[str]
) -> BetterStackError:
    """The SDK error for a failed SQL response (status ≥ 400): the HTTP status mapping, replaced
    by the ClickHouse exception code mapping when the code has one."""
    retry_after = None
    if resp.status in (429, 503):
        retry_after = parse_retry_after(resp.headers.get("retry-after"))
    err = error_from_status(
        resp.status,
        operation,
        content=resp.content,
        headers=resp.headers,
        known_secrets=known_secrets,
        retry_after=retry_after,
    )
    code = _header_code(resp.headers) or clickhouse_code(resp.text)
    if code is None:
        return err
    mapped = error_for_clickhouse_code(
        code, err.message, http_status=err.http_status, request_id=err.request_id
    )
    return mapped if mapped is not None else err


def map_query_error(err: BaseException) -> BaseException:
    """Map an SQL-host error by the ClickHouse code found in its message, else keep it."""
    if not isinstance(err, BetterStackError):
        return err
    code = clickhouse_code(err.message)
    if code is None:
        return err
    mapped = error_for_clickhouse_code(
        code, err.message, http_status=err.http_status, request_id=err.request_id
    )
    return mapped if mapped is not None else err
