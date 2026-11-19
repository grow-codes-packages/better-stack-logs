"""Better Stack Logs clients (sync and async), API.md §6, DECISIONS 4, 5 and 9.

This file is owned by the service package (generated once by sdk-kit, then maintained here).
"""

from __future__ import annotations

import contextlib
import dataclasses
import threading
import time
from collections.abc import Mapping
from types import TracebackType
from typing import Any, Literal

import httpx

from ._core import (
    AsyncHttpTransport,
    BetterStackError,
    CancelledError,
    CancelToken,
    ConfigError,
    HealthStatus,
    HttpTransport,
    Request,
    ResourceRegistry,
    Response,
    TimeoutError,
    UnavailableError,
    UnsupportedError,
)
from ._logging import (
    AsyncBatchingLogClient,
    AsyncLogExporter,
    AsyncPollingTail,
    AttrValue,
    BatchingLogClient,
    BatchOptions,
    LogExporter,
    PollingTail,
    RedactionProcessor,
)
from .config import BetterStackConfig, IngestEncoding
from .errors import map_ingest_error
from .exporter import (
    MAX_REQUEST_BYTES,
    AsyncBetterStackLogExporter,
    BetterStackLogExporter,
)
from .otlp import (
    DEFAULT_SCOPE_NAME,
    AsyncBetterStackOtlpLogExporter,
    BetterStackOtlpLogExporter,
    OtlpProtocol,
)
from .query import AsyncBetterStackQuery, BetterStackQuery

__all__ = [
    "AsyncBetterStackClient",
    "AsyncBetterStackLogClient",
    "BetterStackClient",
    "BetterStackLogClient",
    "LogProtocol",
]

LogProtocol = Literal["http", "otlp"]
"""Wire protocol of :meth:`BetterStackClient.log_client`: the HTTP ingest API or OTLP/HTTP."""

_DEFAULT_REDACTION = RedactionProcessor()
_PING_SQL_TABLE = "SELECT count() AS c FROM remote({table}_logs) WHERE 1 = 0"
_PING_SQL = "SELECT 1 AS ok"


def _version_unsupported() -> UnsupportedError:
    return UnsupportedError("server_version: Better Stack does not report a server version")


def _ping_sql(config: BetterStackConfig) -> str:
    if config.source_table:
        return _PING_SQL_TABLE.format(table=config.source_table)
    return _PING_SQL


def _health_query(resp: Response) -> HealthStatus:
    if resp.ok:
        word = resp.text.strip().rstrip(".").lower() or "ok"
        return HealthStatus(healthy=True, status=word, details={"endpoint": "query"})
    return HealthStatus(
        healthy=False,
        status="unhealthy",
        details={"endpoint": "query", "httpStatus": str(resp.status)},
    )


def _health_ingest(resp: Response) -> HealthStatus:
    details = {"endpoint": "ingest", "httpStatus": str(resp.status)}
    if resp.status >= 500:
        return HealthStatus(healthy=False, status="unhealthy", details=details)
    return HealthStatus(healthy=True, status="ok", details=details)


def _health_error(err: BetterStackError, endpoint: str) -> HealthStatus:
    if isinstance(err, (CancelledError, ConfigError)):
        raise err
    word = "unreachable" if isinstance(err, (UnavailableError, TimeoutError)) else "unhealthy"
    return HealthStatus(
        healthy=False, status=word, details={"endpoint": endpoint, "error": err.message}
    )


def _health_request(path: str) -> Request:
    return Request("GET", path, operation="health", raise_for_status=False, max_retries=0)


def _ping_ingest_request(config: BetterStackConfig) -> Request:
    token = config.source_token.reveal() if config.source_token else ""
    return Request(
        "POST",
        "/",
        operation="ping",
        content=b"[]",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        idempotent=True,
    )


def _batch_options(options: BatchOptions | None) -> BatchOptions:
    opts = options or BatchOptions()
    if opts.max_batch_bytes > MAX_REQUEST_BYTES:
        opts = dataclasses.replace(opts, max_batch_bytes=MAX_REQUEST_BYTES)
    return opts


def _check_protocol(protocol: str) -> None:
    if protocol not in ("http", "otlp"):
        raise ConfigError('log_client: protocol must be "http" or "otlp"')


class BetterStackLogClient(BatchingLogClient):
    """:class:`LogClient` that ships records to the source's ingesting host through a
    background :class:`BatchProcessor`. Create it with :meth:`BetterStackClient.log_client`."""

    def __init__(
        self,
        exporter: LogExporter,
        *,
        options: BatchOptions | None = None,
        resource: Mapping[str, AttrValue] | None = None,
        redaction: RedactionProcessor | None = _DEFAULT_REDACTION,
        correlate: bool = True,
    ) -> None:
        super().__init__(
            exporter, options=options, resource=resource, redaction=redaction, correlate=correlate
        )
        #: The exporter used by this client.
        self.exporter = exporter


class AsyncBetterStackLogClient(AsyncBatchingLogClient):
    """Async :class:`BetterStackLogClient`. Create it with
    :meth:`AsyncBetterStackClient.log_client` inside a running event loop."""

    def __init__(
        self,
        exporter: AsyncLogExporter,
        *,
        options: BatchOptions | None = None,
        resource: Mapping[str, AttrValue] | None = None,
        redaction: RedactionProcessor | None = _DEFAULT_REDACTION,
        correlate: bool = True,
    ) -> None:
        super().__init__(
            exporter, options=options, resource=resource, redaction=redaction, correlate=correlate
        )
        #: The exporter used by this client.
        self.exporter = exporter


class BetterStackClient:
    """Synchronous Better Stack Logs client; thread-safe. Use it as a context manager or call
    :meth:`close`.

    Ingestion goes to the source's ingesting host (:meth:`log_client`, :attr:`exporter`,
    :attr:`otlp_exporter`) and SQL to the query host (:meth:`query`), each with its own
    credentials and transport: a source token is never sent to the query host and SQL
    credentials never to the ingest host. I/O methods accept ``cancel=`` (a
    :class:`CancelToken`) to abort from another thread.

    >>> client = BetterStackClient.from_env()  # doctest: +SKIP
    >>> logs = client.log_client(resource={"service.name": "checkout"})  # doctest: +SKIP
    >>> logs.logger().info("order placed", {"order_id": 42})  # doctest: +SKIP
    >>> client.close()  # doctest: +SKIP
    """

    def __init__(
        self,
        config: BetterStackConfig | None = None,
        *,
        http_transport: httpx.BaseTransport | None = None,
        query_http_transport: httpx.BaseTransport | None = None,
    ) -> None:
        if config is None:
            config = BetterStackConfig.from_env()
        #: The immutable configuration.
        self.config = config.validate()
        self._resources = ResourceRegistry()
        self._lock = threading.Lock()
        self._closed = False
        self._log_clients: list[BetterStackLogClient] = []
        self._ingest: HttpTransport | None = None
        self._sql: HttpTransport | None = None
        if self.config.has_ingest:
            self._ingest = HttpTransport(
                self.config.transport_config("ingest"),
                known_secrets=self.config.known_secrets,
                http_transport=http_transport,
            )
        if self.config.has_query:
            self._sql = HttpTransport(
                self.config.transport_config("query"),
                known_secrets=self.config.known_secrets,
                http_transport=query_http_transport or http_transport,
            )
        self._exporter: BetterStackLogExporter | None = None
        self._otlp: BetterStackOtlpLogExporter | None = None
        self._query: BetterStackQuery | None = None

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None, **overrides: Any) -> BetterStackClient:
        """Build a client from ``GROW_BETTER_STACK_LOGS_*``, ``BETTERSTACK_SOURCE_TOKEN``,
        ``BETTERSTACK_INGESTING_HOST``, ``BETTERSTACK_QUERY_*`` … variables."""
        return cls(BetterStackConfig.from_env(env, **overrides))

    @property
    def resources(self) -> ResourceRegistry:
        """Background resources (tails) stopped by :meth:`close`."""
        return self._resources

    def _ingest_transport(self, operation: str) -> HttpTransport:
        if self._ingest is None:
            raise ConfigError(
                f"{operation}: url (the source's ingesting host) and source_token are required "
                "for ingestion"
            )
        return self._ingest

    def _sql_transport(self, operation: str) -> HttpTransport:
        if self._sql is None:
            raise ConfigError(
                f"{operation}: query_url, query_username and query_password are required for "
                "SQL queries"
            )
        return self._sql

    def health(self, *, cancel: CancelToken | None = None) -> HealthStatus:
        """Unauthenticated liveness; never raises for an unhealthy or unreachable service.

        With a query host: ``GET {query_url}/ping`` (``Ok.``). Ingest-only: ``GET {url}/``
        without credentials; any answer below 500 proves the host is reachable and no data is
        sent. ``details["endpoint"]`` is ``"query"`` or ``"ingest"``.
        """
        if self._sql is not None:
            try:
                return _health_query(self._sql.request(_health_request("/ping"), cancel=cancel))
            except BetterStackError as err:
                return _health_error(err, "query")
        transport = self._ingest_transport("health")
        try:
            return _health_ingest(transport.request(_health_request("/"), cancel=cancel))
        except BetterStackError as err:
            return _health_error(err, "ingest")

    def ping(self, *, cancel: CancelToken | None = None) -> float:
        """Authenticated round trip; returns the latency in milliseconds.

        With query credentials: ``SELECT count() AS c FROM remote(<source_table>_logs) WHERE
        1 = 0`` (or ``SELECT 1`` without a ``source_table``). Ingest-only: posts an empty batch
        ``[]`` (no event is stored). Bad credentials raise :class:`AuthenticationError`.
        """
        started = time.monotonic()
        if self._sql is not None:
            self.query().query(_ping_sql(self.config), cancel=cancel)
        else:
            transport = self._ingest_transport("ping")
            try:
                transport.request(_ping_ingest_request(self.config), cancel=cancel)
            except BetterStackError as err:
                raise map_ingest_error(err) from None
        return (time.monotonic() - started) * 1000.0

    def server_version(self) -> str:
        """Always raises :class:`UnsupportedError`: Better Stack is a SaaS without a version
        endpoint (the query host's ClickHouse version is not a Better Stack version)."""
        raise _version_unsupported()

    @property
    def exporter(self) -> BetterStackLogExporter:
        """The HTTP ingest exporter (``config.encoding``, default limits); needs the ingest
        side (:class:`ConfigError` otherwise)."""
        if self._exporter is None:
            self._exporter = BetterStackLogExporter(self._ingest_transport("exporter"), self.config)
        return self._exporter

    @property
    def otlp_exporter(self) -> BetterStackOtlpLogExporter:
        """The OTLP/HTTP protobuf logs exporter (``{url}/v1/logs``); needs the ingest side."""
        if self._otlp is None:
            self._otlp = BetterStackOtlpLogExporter(
                self._ingest_transport("otlp_exporter"), self.config
            )
        return self._otlp

    def new_exporter(
        self,
        *,
        encoding: IngestEncoding | None = None,
        max_request_bytes: int | None = None,
        max_record_bytes: int | None = None,
    ) -> BetterStackLogExporter:
        """An HTTP ingest exporter with its own encoding (default ``config.encoding``) and
        limits (at most 10 MiB each)."""
        return BetterStackLogExporter(
            self._ingest_transport("new_exporter"),
            self.config,
            encoding=encoding,
            max_request_bytes=max_request_bytes,
            max_record_bytes=max_record_bytes,
        )

    def new_otlp_exporter(
        self,
        *,
        protocol: OtlpProtocol = "protobuf",
        scope_name: str = DEFAULT_SCOPE_NAME,
        max_request_bytes: int | None = None,
    ) -> BetterStackOtlpLogExporter:
        """An OTLP exporter with its own payload format (``protobuf`` or ``json``), scope name
        and request limit."""
        return BetterStackOtlpLogExporter(
            self._ingest_transport("new_otlp_exporter"),
            self.config,
            protocol=protocol,
            scope_name=scope_name,
            max_request_bytes=max_request_bytes,
        )

    def log_client(
        self,
        *,
        protocol: LogProtocol = "http",
        exporter: LogExporter | None = None,
        options: BatchOptions | None = None,
        resource: Mapping[str, AttrValue] | None = None,
        redaction: RedactionProcessor | None = _DEFAULT_REDACTION,
        correlate: bool = True,
    ) -> BetterStackLogClient:
        """A batching :class:`BetterStackLogClient` over the HTTP ingest API (default) or OTLP.

        ``exporter`` replaces the default one (for example :meth:`new_exporter` with another
        encoding); ``options.max_batch_bytes`` is capped at 10 MiB. :meth:`close` flushes and
        closes every log client it created. Needs the ingest side.
        """
        with self._lock:
            if self._closed:
                raise CancelledError("log_client: client is closed")
        _check_protocol(protocol)
        if exporter is None:
            exporter = self.otlp_exporter if protocol == "otlp" else self.exporter
        client = BetterStackLogClient(
            exporter,
            options=_batch_options(options),
            resource=resource,
            redaction=redaction,
            correlate=correlate,
        )
        with self._lock:
            self._log_clients.append(client)
        return client

    def query(self) -> BetterStackQuery:
        """The SQL query client; :class:`ConfigError` without query credentials."""
        if self._query is None:
            self._query = BetterStackQuery(
                self._sql_transport("query"), self.config, on_tail=self._track_tail
            )
        return self._query

    def _track_tail(self, tail: PollingTail) -> None:
        self._resources.register(tail)

    def close(self) -> None:
        """Flush and close every log client, stop tails and close connections (idempotent).

        Later calls on the client (and on its exporters and query client) raise
        :class:`CancelledError`.
        """
        with self._lock:
            if self._closed:
                return
            self._closed = True
            clients, self._log_clients = self._log_clients, []
        for client in clients:
            with contextlib.suppress(Exception):
                client.close()
        self._resources.stop_all()
        for transport in (self._ingest, self._sql):
            if transport is not None:
                transport.close()

    def __enter__(self) -> BetterStackClient:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"BetterStackClient(config={self.config!r})"


class AsyncBetterStackClient:
    """Asynchronous Better Stack Logs client; use ``async with`` or call :meth:`aclose`.

    Same surface as :class:`BetterStackClient` with awaitable I/O. Cancel a call by cancelling
    its task: the SDK raises :class:`CancelledError`, which is also an
    :class:`asyncio.CancelledError`.
    """

    def __init__(
        self,
        config: BetterStackConfig | None = None,
        *,
        http_transport: httpx.AsyncBaseTransport | None = None,
        query_http_transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if config is None:
            config = BetterStackConfig.from_env()
        #: The immutable configuration.
        self.config = config.validate()
        self._resources = ResourceRegistry()
        self._closed = False
        self._log_clients: list[AsyncBetterStackLogClient] = []
        self._tails: list[AsyncPollingTail] = []
        self._ingest: AsyncHttpTransport | None = None
        self._sql: AsyncHttpTransport | None = None
        if self.config.has_ingest:
            self._ingest = AsyncHttpTransport(
                self.config.transport_config("ingest"),
                known_secrets=self.config.known_secrets,
                http_transport=http_transport,
            )
        if self.config.has_query:
            self._sql = AsyncHttpTransport(
                self.config.transport_config("query"),
                known_secrets=self.config.known_secrets,
                http_transport=query_http_transport or http_transport,
            )
        self._exporter: AsyncBetterStackLogExporter | None = None
        self._otlp: AsyncBetterStackOtlpLogExporter | None = None
        self._query: AsyncBetterStackQuery | None = None

    @classmethod
    def from_env(
        cls, env: Mapping[str, str] | None = None, **overrides: Any
    ) -> AsyncBetterStackClient:
        """See :meth:`BetterStackClient.from_env`."""
        return cls(BetterStackConfig.from_env(env, **overrides))

    @property
    def resources(self) -> ResourceRegistry:
        """Background resources stopped by :meth:`aclose`."""
        return self._resources

    def _ingest_transport(self, operation: str) -> AsyncHttpTransport:
        if self._ingest is None:
            raise ConfigError(
                f"{operation}: url (the source's ingesting host) and source_token are required "
                "for ingestion"
            )
        return self._ingest

    def _sql_transport(self, operation: str) -> AsyncHttpTransport:
        if self._sql is None:
            raise ConfigError(
                f"{operation}: query_url, query_username and query_password are required for "
                "SQL queries"
            )
        return self._sql

    async def health(self) -> HealthStatus:
        """See :meth:`BetterStackClient.health`."""
        if self._sql is not None:
            try:
                return _health_query(await self._sql.request(_health_request("/ping")))
            except BetterStackError as err:
                return _health_error(err, "query")
        transport = self._ingest_transport("health")
        try:
            return _health_ingest(await transport.request(_health_request("/")))
        except BetterStackError as err:
            return _health_error(err, "ingest")

    async def ping(self) -> float:
        """See :meth:`BetterStackClient.ping`."""
        started = time.monotonic()
        if self._sql is not None:
            await self.query().query(_ping_sql(self.config))
        else:
            transport = self._ingest_transport("ping")
            try:
                await transport.request(_ping_ingest_request(self.config))
            except BetterStackError as err:
                raise map_ingest_error(err) from None
        return (time.monotonic() - started) * 1000.0

    async def server_version(self) -> str:
        """See :meth:`BetterStackClient.server_version` (always unsupported)."""
        raise _version_unsupported()

    @property
    def exporter(self) -> AsyncBetterStackLogExporter:
        """See :attr:`BetterStackClient.exporter`."""
        if self._exporter is None:
            self._exporter = AsyncBetterStackLogExporter(
                self._ingest_transport("exporter"), self.config
            )
        return self._exporter

    @property
    def otlp_exporter(self) -> AsyncBetterStackOtlpLogExporter:
        """See :attr:`BetterStackClient.otlp_exporter`."""
        if self._otlp is None:
            self._otlp = AsyncBetterStackOtlpLogExporter(
                self._ingest_transport("otlp_exporter"), self.config
            )
        return self._otlp

    def new_exporter(
        self,
        *,
        encoding: IngestEncoding | None = None,
        max_request_bytes: int | None = None,
        max_record_bytes: int | None = None,
    ) -> AsyncBetterStackLogExporter:
        """See :meth:`BetterStackClient.new_exporter`."""
        return AsyncBetterStackLogExporter(
            self._ingest_transport("new_exporter"),
            self.config,
            encoding=encoding,
            max_request_bytes=max_request_bytes,
            max_record_bytes=max_record_bytes,
        )

    def new_otlp_exporter(
        self,
        *,
        protocol: OtlpProtocol = "protobuf",
        scope_name: str = DEFAULT_SCOPE_NAME,
        max_request_bytes: int | None = None,
    ) -> AsyncBetterStackOtlpLogExporter:
        """See :meth:`BetterStackClient.new_otlp_exporter`."""
        return AsyncBetterStackOtlpLogExporter(
            self._ingest_transport("new_otlp_exporter"),
            self.config,
            protocol=protocol,
            scope_name=scope_name,
            max_request_bytes=max_request_bytes,
        )

    def log_client(
        self,
        *,
        protocol: LogProtocol = "http",
        exporter: AsyncLogExporter | None = None,
        options: BatchOptions | None = None,
        resource: Mapping[str, AttrValue] | None = None,
        redaction: RedactionProcessor | None = _DEFAULT_REDACTION,
        correlate: bool = True,
    ) -> AsyncBetterStackLogClient:
        """See :meth:`BetterStackClient.log_client`; call it inside a running event loop."""
        if self._closed:
            raise CancelledError("log_client: client is closed")
        _check_protocol(protocol)
        if exporter is None:
            exporter = self.otlp_exporter if protocol == "otlp" else self.exporter
        client = AsyncBetterStackLogClient(
            exporter,
            options=_batch_options(options),
            resource=resource,
            redaction=redaction,
            correlate=correlate,
        )
        self._log_clients.append(client)
        return client

    def query(self) -> AsyncBetterStackQuery:
        """See :meth:`BetterStackClient.query`."""
        if self._query is None:
            self._query = AsyncBetterStackQuery(
                self._sql_transport("query"), self.config, on_tail=self._tails.append
            )
        return self._query

    async def aclose(self) -> None:
        """Flush and close every log client, stop tails and close connections (idempotent)."""
        if self._closed:
            return
        self._closed = True
        clients, self._log_clients = self._log_clients, []
        for client in clients:
            with contextlib.suppress(Exception):
                await client.close()
        tails, self._tails = self._tails, []
        for tail in tails:
            with contextlib.suppress(Exception):
                await tail.stop()
        self._resources.stop_all()
        for transport in (self._ingest, self._sql):
            if transport is not None:
                await transport.aclose()

    close = aclose

    async def __aenter__(self) -> AsyncBetterStackClient:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self.aclose()

    def __repr__(self) -> str:
        return f"AsyncBetterStackClient(config={self.config!r})"
