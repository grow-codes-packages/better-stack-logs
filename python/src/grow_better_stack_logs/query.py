"""SQL query API: the ClickHouse HTTP interface of the query host (API.md §5).

:class:`BetterStackQuery` (sync) and :class:`AsyncBetterStackQuery` (async) run SQL with
parameters bound **server side** (ClickHouse ``{name:Type}`` placeholders + ``param_<name>`` URL
parameters, DECISIONS §10) and implement the logging category's ``LogQueryClient``:
``search`` runs :meth:`~BetterStackQuery.search_logs` on the configured ``source_table``,
``tail`` polls ``search``, ``list_streams`` returns the configured collection and
``list_labels`` is unsupported (Better Stack events have no labels).
"""

from __future__ import annotations

import base64
import json
import math
import re
from collections.abc import Callable, Mapping
from datetime import date, datetime, timedelta, timezone
from typing import Any, TypeAlias

from ._core import (
    AsyncHttpTransport,
    CancelToken,
    ConfigError,
    HttpTransport,
    Request,
    Response,
    UnavailableError,
    UnsupportedError,
    ValidationError,
)
from ._logging import (
    AsyncBaseLogQueryClient,
    AsyncPollingTail,
    AttrValue,
    BaseLogQueryClient,
    LogRecord,
    LogSearchResult,
    PollingTail,
    SearchOrder,
    Severity,
    to_attr_value,
)
from .config import BetterStackConfig, is_valid_identifier
from .errors import clickhouse_code, error_for_clickhouse_code, query_error_from_response

__all__ = [
    "DEFAULT_SEARCH_LIMIT",
    "DEFAULT_SEARCH_WINDOW",
    "MAX_SEARCH_LIMIT",
    "AsyncBetterStackQuery",
    "BetterStackQuery",
    "QueryParamValue",
    "QueryRow",
    "format_datetime64",
    "json_escape",
    "log_record_from_row",
    "parse_datetime",
    "parse_rows",
    "escape_param_value",
    "sql_string",
]

QueryParamValue: TypeAlias = str | int | float | bool | datetime | date | None
"""Value of a bound query parameter; ``None`` is sent as ``\\N`` (NULL)."""

QueryRow = dict[str, Any]
"""One result row (JSON object; ClickHouse quotes 64-bit integers as strings)."""

#: Records per page when no limit is given.
DEFAULT_SEARCH_LIMIT = 100
#: Largest page size of ``search_logs`` / ``search``.
MAX_SEARCH_LIMIT = 10_000
#: Window of ``search_logs`` / ``search`` when no start is given (before ``end`` or now).
DEFAULT_SEARCH_WINDOW = timedelta(hours=24)

_JSON_FORMATS = frozenset({"jsoneachrow", "json", "jsonstringseachrow", "ndjson", "jsonlines"})
_FORMAT_CLAUSE = re.compile(r"\bFORMAT\s+([A-Za-z][A-Za-z0-9_]*)\s*;?\s*$", re.IGNORECASE)
_SQL_ESCAPES = {"\\": "\\\\", "'": "\\'", "\n": "\\n", "\r": "\\r", "\t": "\\t", "\0": "\\0"}
_DT_RE = re.compile(
    r"^(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|z|[+-]\d{2}:?\d{2})?$"
)
_HEX32 = re.compile(r"^[0-9a-f]{32}$")
_HEX16 = re.compile(r"^[0-9a-f]{16}$")
_TOP_LEVEL_KEYS = frozenset(
    {
        "dt",
        "message",
        "msg",
        "level",
        "severity",
        "severity_number",
        "span",
        "trace_id",
        "span_id",
        "trace_flags",
        "resource",
        "attributes",
        "observed_dt",
    }
)


# --------------------------------------------------------------------------- helpers


def sql_string(value: str) -> str:
    """``value`` as a single-quoted ClickHouse string literal (backslash, quote, newline,
    carriage return, tab and NUL escaped with a backslash)."""
    return "'" + "".join(_SQL_ESCAPES.get(ch, ch) for ch in value) + "'"


def json_escape(text: str) -> str:
    """JSON string escaping without the surrounding quotes (quote, backslash, control
    characters), as the text appears inside raw event JSON."""
    return json.dumps(text, ensure_ascii=False)[1:-1]


def _utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def format_datetime64(value: datetime) -> str:
    """``YYYY-MM-DD HH:MM:SS.ffffff`` (UTC), the ``DateTime64(6)`` text form; naive = UTC."""
    return _utc(value).strftime("%Y-%m-%d %H:%M:%S.%f")


def parse_datetime(value: Any) -> datetime | None:
    """Parse a ``DateTime64`` text (``2026-11-18 21:00:00.123456``, UTC, 0-9 fraction digits),
    an RFC 3339 string or UNIX seconds/milliseconds. Returns aware UTC or ``None``."""
    if isinstance(value, datetime):
        return _utc(value)
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if not math.isfinite(value):
            return None
        seconds = value / 1000.0 if abs(value) > 1e12 else float(value)
        try:
            return datetime.fromtimestamp(seconds, tz=timezone.utc)
        except (OverflowError, OSError, ValueError):
            return None
    if not isinstance(value, str):
        return None
    match = _DT_RE.match(value.strip())
    if match is None:
        return None
    day, hour, minute, second, frac, zone = match.groups()
    micros = int((frac or "0")[:6].ljust(6, "0"))
    tz = timezone.utc
    if zone and zone not in ("Z", "z"):
        digits = zone[1:].replace(":", "")
        minutes = int(digits[:2]) * 60 + int(digits[2:])
        tz = timezone(timedelta(minutes=minutes if zone[0] == "+" else -minutes))
    try:
        y, mo, d = (int(p) for p in day.split("-"))
        parsed = datetime(y, mo, d, int(hour), int(minute), int(second), micros, tz)
    except ValueError:
        return None
    return parsed.astimezone(timezone.utc)


_PARAM_ESCAPES = str.maketrans({"\\": "\\\\", "\t": "\\t", "\n": "\\n", "\r": "\\r"})


def escape_param_value(value: str) -> str:
    """Escape a ``param_<name>`` value for ClickHouse (DECISIONS §12).

    ClickHouse parses query parameter values in the escaped (TabSeparated) text format, so a
    backslash becomes ``\\\\``, tab ``\\t``, newline ``\\n`` and carriage return ``\\r``. Applied to
    every ``str`` parameter by :meth:`BetterStackQuery.query`.
    """
    return value.translate(_PARAM_ESCAPES)


def _param_text(name: str, value: QueryParamValue) -> str:
    if value is None:
        return "\\N"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, datetime):
        return format_datetime64(value)
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, float) and not math.isfinite(value):
        raise ValidationError(f"query: parameter {name} must be a finite number")
    if isinstance(value, str):
        return escape_param_value(value)
    if isinstance(value, (int, float)):
        return str(value)
    raise ValidationError(f"query: parameter {name} has an unsupported type")


def _hex_id(value: Any, pattern: re.Pattern[str]) -> str | None:
    if not isinstance(value, str):
        return None
    text = value.strip().lower()
    return text if pattern.match(text) and text.strip("0") else None


def _severity(value: Any) -> Severity | None:
    if isinstance(value, str) and value.strip().isdigit():
        value = int(value.strip())
    if isinstance(value, (int, str)) and not isinstance(value, bool):
        try:
            return Severity.parse(value)
        except ValidationError:
            return None
    return None


def _attrs(source: Mapping[str, Any]) -> dict[str, AttrValue]:
    return {str(k): to_attr_value(v) for k, v in source.items() if v is not None}


def log_record_from_row(row: Mapping[str, Any]) -> LogRecord:
    """Normalize a ``{dt, raw}`` row to a :class:`LogRecord` (API.md §5.3).

    ``body`` = ``message`` (else ``msg``, else the raw text); severity from ``severity_number``,
    else ``level`` / ``severity`` text (default INFO); ``timestamp`` = ``dt``; trace ids from
    ``span.trace_id`` / ``span.span_id`` (or top-level ``trace_id`` / ``span_id``, hex only);
    ``resource`` = ``resource``; every other key plus the ``attributes`` object into
    ``attributes``.
    """
    raw_value = row.get("raw")
    raw_text = raw_value if isinstance(raw_value, str) else json.dumps(raw_value)
    try:
        parsed = json.loads(raw_text)
    except ValueError:
        parsed = None
    event: dict[str, Any] = parsed if isinstance(parsed, dict) else {}
    timestamp = (
        parse_datetime(row.get("dt"))
        or parse_datetime(event.get("dt"))
        or datetime(1970, 1, 1, tzinfo=timezone.utc)
    )
    message = event.get("message", event.get("msg"))
    if message is None:
        message = event.get("msg")
    if isinstance(message, str):
        body = message
    elif message is not None:
        body = json.dumps(message, ensure_ascii=False)
    else:
        body = raw_text
    level_text: str | None = None
    for key in ("level", "severity"):
        candidate = event.get(key)
        if isinstance(candidate, str) and candidate:
            level_text = candidate
            break
    from_number = _severity(event.get("severity_number"))
    from_text = _severity(level_text) if level_text is not None else None
    severity = from_number or from_text or Severity.INFO
    if from_text is not None:
        text = from_text.name
    elif level_text is not None:
        text = level_text
    else:
        text = severity.name
    attributes = _attrs({k: v for k, v in event.items() if k not in _TOP_LEVEL_KEYS})
    if isinstance(event.get("attributes"), Mapping):
        attributes.update(_attrs(event["attributes"]))
    resource = _attrs(event["resource"]) if isinstance(event.get("resource"), Mapping) else {}
    span = event.get("span") if isinstance(event.get("span"), Mapping) else {}
    assert isinstance(span, Mapping)
    trace_id = _hex_id(span.get("trace_id", event.get("trace_id")), _HEX32)
    span_id = None
    flags = None
    if trace_id is not None:
        span_id = _hex_id(span.get("span_id", event.get("span_id")), _HEX16)
        raw_flags = event.get("trace_flags")
        if isinstance(raw_flags, int) and not isinstance(raw_flags, bool) and 0 <= raw_flags < 256:
            flags = raw_flags
    return LogRecord(
        body=body,
        severity=severity,
        timestamp=timestamp,
        severity_text=text,
        attributes=attributes,
        resource=resource,
        observed_timestamp=parse_datetime(event.get("observed_dt")),
        trace_id=trace_id,
        span_id=span_id,
        trace_flags=flags,
    )


def _raise_if_exception(text: str) -> None:
    code = clickhouse_code(text)
    if code is None:
        return
    line = next((ln for ln in text.splitlines() if clickhouse_code(ln) is not None), "")
    message = f"query: {line.strip()[:300]}"
    raise error_for_clickhouse_code(code, message, http_status=200) or UnavailableError(
        message, http_status=200, retryable=False
    )


def parse_rows(text: str) -> list[QueryRow]:
    """Parse a JSON-family response body (``JSONEachRow``, ``JSON``, a JSON array) into rows.

    A ClickHouse exception appended to a 200 response (``Code: N. DB::Exception``) raises the
    mapped error; any other unparseable line raises :class:`UnavailableError`.
    """
    trimmed = text.strip()
    if not trimmed:
        return []
    if trimmed.startswith("["):
        try:
            doc = json.loads(trimmed)
        except ValueError:
            _raise_if_exception(trimmed)
            raise UnavailableError(
                "query: invalid JSON array in response", retryable=False
            ) from None
        return [r for r in doc if isinstance(r, dict)]
    rows: list[QueryRow] = []
    for line in trimmed.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        try:
            parsed = json.loads(stripped)
        except ValueError:
            parsed = None
        if not isinstance(parsed, dict):
            if not rows:
                # FORMAT JSON spans several lines: parse the whole document instead.
                try:
                    doc = json.loads(trimmed)
                except ValueError:
                    doc = None
                if isinstance(doc, dict) and isinstance(doc.get("data"), list):
                    return [r for r in doc["data"] if isinstance(r, dict)]
            _raise_if_exception(stripped)
            raise UnavailableError("query: invalid JSON line in response", retryable=False)
        rows.append(parsed)
    if len(rows) == 1 and isinstance(rows[0].get("data"), list) and "meta" in rows[0]:
        return [r for r in rows[0]["data"] if isinstance(r, dict)]
    return rows


def _encode_cursor(offset: int) -> str:
    raw = json.dumps({"o": offset}, separators=(",", ":")).encode()
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _decode_cursor(cursor: str | None) -> int:
    if not cursor:
        return 0
    try:
        doc = json.loads(base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)))
        offset = doc["o"]
        if isinstance(offset, int) and not isinstance(offset, bool) and offset >= 0:
            return offset
    except (ValueError, TypeError, KeyError):
        pass
    raise ValidationError("logs.search: invalid cursor")


# --------------------------------------------------------------------------- shared core


class _QueryCore:
    """Request building, SQL rendering and result shaping shared by both query clients."""

    def __init__(
        self, config: BetterStackConfig, now: Callable[[], datetime] | None = None
    ) -> None:
        self._config = config
        self._now = now or (lambda: datetime.now(timezone.utc))

    @property
    def config(self) -> BetterStackConfig:
        """The client configuration."""
        return self._config

    def _request(
        self,
        sql: str,
        params: Mapping[str, QueryParamValue] | None,
        settings: Mapping[str, str | int | float | bool] | None,
        operation: str,
    ) -> Request:
        if not sql.strip():
            raise ValidationError(f"{operation}: sql must not be empty")
        query: dict[str, str] = {}
        for key, value in (settings or {}).items():
            if not is_valid_identifier(key):
                raise ValidationError(f"{operation}: invalid setting name {key!r}")
            query[key] = ("true" if value else "false") if isinstance(value, bool) else str(value)
        for name, param in (params or {}).items():
            if not is_valid_identifier(name):
                raise ValidationError(f"{operation}: invalid parameter name {name!r}")
            query[f"param_{name}"] = _param_text(name, param)
        return Request(
            "POST",
            "/",
            operation=operation,
            params=query or None,
            content=sql.encode("utf-8"),
            headers={
                "Authorization": f"Basic {self._config.basic_credentials() or ''}",
                "Content-Type": "text/plain; charset=utf-8",
            },
            idempotent=True,
            raise_for_status=False,
        )

    def _check(self, resp: Response, operation: str) -> str:
        if resp.status >= 400:
            raise query_error_from_response(resp, operation, self._config.known_secrets())
        return resp.text

    @staticmethod
    def _rows_sql(sql: str, operation: str) -> str:
        trimmed = re.sub(r";\s*$", "", sql.strip())
        match = _FORMAT_CLAUSE.search(trimmed)
        if match is None:
            return f"{trimmed} FORMAT JSONEachRow"
        if match.group(1).lower() not in _JSON_FORMATS:
            raise ValidationError(
                f"{operation}: FORMAT {match.group(1)} cannot be parsed into rows; use "
                "query_raw or FORMAT JSONEachRow"
            )
        return trimmed

    def render_search_sql(
        self,
        *,
        source: str | None = None,
        contains: str | None = None,
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int = DEFAULT_SEARCH_LIMIT,
        order: SearchOrder = "desc",
        cursor: str | None = None,
    ) -> str:
        """Render the SQL of :meth:`search_logs`.

        Hot ``remote(<source>_logs)`` UNION ALL cold ``s3Cluster(primary, <source>_s3) WHERE
        _row_type = 1``, the inclusive ``dt`` window (``toDateTime64('…', 6, 'UTC')``; no upper
        bound without ``end``), ``position(raw, <JSON-escaped contains>) > 0``, ``ORDER BY dt``,
        ``LIMIT <limit + 1>`` (to detect a next page) and ``OFFSET`` from the cursor,
        ``FORMAT JSONEachRow``. Values are escaped string literals; ``source`` is validated as
        an identifier.
        """
        table = source if source is not None else self._config.source_table
        if not table:
            raise ConfigError(
                "config: search_logs needs a source or a configured source_table "
                "(GROW_BETTER_STACK_LOGS_SOURCE_TABLE)"
            )
        if not is_valid_identifier(table):
            raise ValidationError("logs.search: source must match ^[A-Za-z_][A-Za-z0-9_]*$")
        if (
            isinstance(limit, bool)
            or not isinstance(limit, int)
            or not (1 <= limit <= MAX_SEARCH_LIMIT)
        ):
            raise ValidationError(
                f"logs.search: limit must be an integer between 1 and {MAX_SEARCH_LIMIT}"
            )
        if order not in ("asc", "desc"):
            raise ValidationError('logs.search: order must be "asc" or "desc"')
        if start is None:
            start = (end if end is not None else self._now()) - DEFAULT_SEARCH_WINDOW
        if end is not None and _utc(start) > _utc(end):
            raise ValidationError("logs.search: start must not be after end")
        offset = _decode_cursor(cursor)

        def ts(value: datetime) -> str:
            return f"toDateTime64({sql_string(format_datetime64(value))}, 6, 'UTC')"

        where = [f"dt >= {ts(start)}" if end is None else f"dt BETWEEN {ts(start)} AND {ts(end)}"]
        if contains:
            where.append(f"position(raw, {sql_string(json_escape(contains))}) > 0")
        sql = (
            f"SELECT dt, raw FROM (SELECT dt, raw FROM remote({table}_logs) UNION ALL "  # noqa: S608
            f"SELECT dt, raw FROM s3Cluster(primary, {table}_s3) WHERE _row_type = 1) "
            f"WHERE {' AND '.join(where)} ORDER BY dt {order.upper()} LIMIT {limit + 1}"
        )
        if offset:
            sql += f" OFFSET {offset}"
        return sql + " FORMAT JSONEachRow"

    @staticmethod
    def _page(rows: list[QueryRow], limit: int, cursor: str | None) -> LogSearchResult:
        seen: set[tuple[str, str]] = set()
        records: list[LogRecord] = []
        for row in rows[:limit]:
            key = (str(row.get("dt")), str(row.get("raw")))
            if key in seen:
                continue
            seen.add(key)
            records.append(log_record_from_row(row))
        next_cursor = _encode_cursor(_decode_cursor(cursor) + limit) if len(rows) > limit else None
        return LogSearchResult(records=records, next_cursor=next_cursor)

    def _tail_start(self, start: datetime | None) -> datetime:
        if not self._config.source_table:
            raise ConfigError("config: tail needs a configured source_table")
        return start if start is not None else self._now()

    def _streams(self) -> list[str]:
        return [self._config.source_table] if self._config.source_table else []


# --------------------------------------------------------------------------- sync


class BetterStackQuery(_QueryCore, BaseLogQueryClient):
    """SQL query client for Better Stack Logs (sync). Get it from
    :meth:`BetterStackClient.query`; thread-safe.

    Better Stack allows 4 concurrent log queries per account and suggests 1-2 s between calls,
    so polling helpers default to a 1 s interval.
    """

    def __init__(
        self,
        transport: HttpTransport,
        config: BetterStackConfig,
        *,
        now: Callable[[], datetime] | None = None,
        on_tail: Callable[[PollingTail], None] | None = None,
    ) -> None:
        super().__init__(config, now)
        self._transport = transport
        self._on_tail = on_tail

    def query_raw(
        self,
        sql: str,
        params: Mapping[str, QueryParamValue] | None = None,
        *,
        settings: Mapping[str, str | int | float | bool] | None = None,
        cancel: CancelToken | None = None,
    ) -> str:
        """Run SQL and return the response body unchanged (any ``FORMAT``).

        ``params`` are bound server side: ``{name:Type}`` placeholders in ``sql`` +
        ``param_<name>`` URL parameters. ``settings`` are ClickHouse settings sent as URL
        parameters (``{"max_execution_time": 30}``). Errors map by ClickHouse exception code.
        ``cancel`` aborts the call from another thread (:class:`CancelledError`).
        """
        return self._run(sql, params, settings, "query", cancel)

    def _run(
        self,
        sql: str,
        params: Mapping[str, QueryParamValue] | None,
        settings: Mapping[str, str | int | float | bool] | None,
        operation: str,
        cancel: CancelToken | None,
    ) -> str:
        req = self._request(sql, params, settings, operation)
        resp = self._transport.request(req, cancel=cancel)
        return self._check(resp, operation)

    def query(
        self,
        sql: str,
        params: Mapping[str, QueryParamValue] | None = None,
        *,
        settings: Mapping[str, str | int | float | bool] | None = None,
        cancel: CancelToken | None = None,
    ) -> list[QueryRow]:
        """Run SQL and return the rows.

        Without a ``FORMAT`` clause ``FORMAT JSONEachRow`` is appended; ``FORMAT JSON`` /
        ``JSONEachRow`` (also with ``output_format_json_array_of_rows``) are parsed, other
        formats raise :class:`ValidationError` (use :meth:`query_raw`). A ClickHouse exception
        appended to a 200 response raises the mapped error.

        >>> q.query(  # doctest: +SKIP
        ...     "SELECT count() AS c FROM remote(t1_app_logs) "
        ...     "WHERE position(raw, {needle:String}) > 0",
        ...     {"needle": "checkout"},
        ... )
        [{'c': '3'}]
        """
        text = self._run(self._rows_sql(sql, "query"), params, settings, "query", cancel)
        return parse_rows(text)

    def search_logs(
        self,
        *,
        source: str | None = None,
        contains: str | None = None,
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int = DEFAULT_SEARCH_LIMIT,
        order: SearchOrder = "desc",
        cursor: str | None = None,
        cancel: CancelToken | None = None,
    ) -> LogSearchResult:
        """Search the hot and cold logs of a collection (see :meth:`render_search_sql`).

        ``source`` defaults to ``config.source_table``; ``contains`` is a case-sensitive literal
        substring of the raw event JSON (JSON-escaped first, so it matches text inside string
        values); ``start``/``end`` are inclusive (default: the last 24 h, no upper bound);
        ``order`` is ``"desc"`` (newest first) or ``"asc"``. Rows are normalized
        (:func:`log_record_from_row`) and de-duplicated on ``(dt, raw)``; ``next_cursor`` is set
        when more rows exist.
        """
        sql = self.render_search_sql(
            source=source,
            contains=contains,
            start=start,
            end=end,
            limit=limit,
            order=order,
            cursor=cursor,
        )
        rows = parse_rows(self._run(sql, None, None, "logs.search", cancel))
        return self._page(rows, limit, cursor)

    def search(
        self,
        query: str,
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int = DEFAULT_SEARCH_LIMIT,
        order: SearchOrder = "desc",
        cursor: str | None = None,
        cancel: CancelToken | None = None,
    ) -> LogSearchResult:
        """``LogQueryClient.search``: ``query`` is the ``contains`` text, searched in the
        configured ``source_table``."""
        return self.search_logs(
            contains=query,
            start=start,
            end=end,
            limit=limit,
            order=order,
            cursor=cursor,
            cancel=cancel,
        )

    def tail(
        self,
        query: str,
        on_record: Callable[[LogRecord], None],
        *,
        interval_s: float | timedelta = 1.0,
        start: datetime | None = None,
        on_error: Callable[[Exception], None] | None = None,
    ) -> PollingTail:
        """Poll :meth:`search` every ``interval_s`` for new records of ``source_table`` that
        contain ``query``, starting at ``start`` (default now), and call ``on_record`` for each
        new record in ``dt`` order. Errors go to ``on_error`` and polling continues. Stop it
        with ``handle.stop()``; :meth:`BetterStackClient.close` stops it too."""
        tail = PollingTail(
            self,
            query,
            on_record,
            interval_s=interval_s,
            start=self._tail_start(start),
            on_error=on_error,
        )
        if self._on_tail is not None:
            self._on_tail(tail)
        return tail

    def list_streams(self) -> list[str]:
        """The configured collections (``[source_table]`` or ``[]``); Better Stack's data plane
        has no listing endpoint."""
        return self._streams()

    def list_labels(self) -> list[str]:
        """Always :class:`UnsupportedError`: Better Stack events are schemaless JSON."""
        raise UnsupportedError("logs.list_labels: Better Stack events have no labels")


# --------------------------------------------------------------------------- async


class AsyncBetterStackQuery(_QueryCore, AsyncBaseLogQueryClient):
    """Async :class:`BetterStackQuery`. Get it from :meth:`AsyncBetterStackClient.query`."""

    def __init__(
        self,
        transport: AsyncHttpTransport,
        config: BetterStackConfig,
        *,
        now: Callable[[], datetime] | None = None,
        on_tail: Callable[[AsyncPollingTail], None] | None = None,
    ) -> None:
        super().__init__(config, now)
        self._transport = transport
        self._on_tail = on_tail

    async def _run(
        self,
        sql: str,
        params: Mapping[str, QueryParamValue] | None,
        settings: Mapping[str, str | int | float | bool] | None,
        operation: str,
    ) -> str:
        req = self._request(sql, params, settings, operation)
        resp = await self._transport.request(req)
        return self._check(resp, operation)

    async def query_raw(
        self,
        sql: str,
        params: Mapping[str, QueryParamValue] | None = None,
        *,
        settings: Mapping[str, str | int | float | bool] | None = None,
    ) -> str:
        """See :meth:`BetterStackQuery.query_raw`."""
        return await self._run(sql, params, settings, "query")

    async def query(
        self,
        sql: str,
        params: Mapping[str, QueryParamValue] | None = None,
        *,
        settings: Mapping[str, str | int | float | bool] | None = None,
    ) -> list[QueryRow]:
        """See :meth:`BetterStackQuery.query`."""
        text = await self._run(self._rows_sql(sql, "query"), params, settings, "query")
        return parse_rows(text)

    async def search_logs(
        self,
        *,
        source: str | None = None,
        contains: str | None = None,
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int = DEFAULT_SEARCH_LIMIT,
        order: SearchOrder = "desc",
        cursor: str | None = None,
    ) -> LogSearchResult:
        """See :meth:`BetterStackQuery.search_logs`."""
        sql = self.render_search_sql(
            source=source,
            contains=contains,
            start=start,
            end=end,
            limit=limit,
            order=order,
            cursor=cursor,
        )
        rows = parse_rows(await self._run(sql, None, None, "logs.search"))
        return self._page(rows, limit, cursor)

    async def search(
        self,
        query: str,
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int = DEFAULT_SEARCH_LIMIT,
        order: SearchOrder = "desc",
        cursor: str | None = None,
    ) -> LogSearchResult:
        """See :meth:`BetterStackQuery.search`."""
        return await self.search_logs(
            contains=query, start=start, end=end, limit=limit, order=order, cursor=cursor
        )

    async def tail(
        self,
        query: str,
        on_record: Callable[[LogRecord], None],
        *,
        interval_s: float | timedelta = 1.0,
        start: datetime | None = None,
        on_error: Callable[[Exception], None] | None = None,
    ) -> AsyncPollingTail:
        """See :meth:`BetterStackQuery.tail`; the polling task runs on the current loop."""
        tail = AsyncPollingTail(
            self,
            query,
            on_record,
            interval_s=interval_s,
            start=self._tail_start(start),
            on_error=on_error,
        )
        if self._on_tail is not None:
            self._on_tail(tail)
        return tail

    async def list_streams(self) -> list[str]:
        """See :meth:`BetterStackQuery.list_streams`."""
        return self._streams()

    async def list_labels(self) -> list[str]:
        """See :meth:`BetterStackQuery.list_labels`."""
        raise UnsupportedError("logs.list_labels: Better Stack events have no labels")
