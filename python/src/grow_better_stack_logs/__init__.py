"""Grow Codes SDK for Better Stack Logs (unofficial; not affiliated with Better Stack, Inc.).

Ingest logs into a Better Stack source (HTTP ingest API with JSON / NDJSON / MessagePack, or
OTLP/HTTP) and read them back through the SQL query API (ClickHouse HTTP interface).

>>> from grow_better_stack_logs import BetterStackClient
>>> client = BetterStackClient.from_env()  # doctest: +SKIP
>>> client.log_client().logger().info("hello")  # doctest: +SKIP
"""

from . import _core
from ._core import *  # noqa: F403
from ._version import __version__
from .client import (
    AsyncBetterStackClient,
    AsyncBetterStackLogClient,
    BetterStackClient,
    BetterStackLogClient,
    LogProtocol,
)
from .config import (
    DEFAULT_URL,
    ENCODINGS,
    IDENTIFIER_PATTERN,
    BetterStackConfig,
    IngestEncoding,
    is_valid_identifier,
    normalize_base_url,
)
from .encoding import (
    CONTENT_TYPES,
    BetterStackEvent,
    encode_events,
    encode_msgpack,
    encoded_event_size,
    event_json,
    to_better_stack_event,
)
from .errors import (
    CLICKHOUSE_EXCEPTION_HEADER,
    clickhouse_code,
    error_for_clickhouse_code,
    map_ingest_error,
    map_query_error,
    query_error_from_response,
)
from .exporter import (
    MAX_RECORD_BYTES,
    MAX_REQUEST_BYTES,
    AsyncBetterStackLogExporter,
    BetterStackLogExporter,
    IngestPayload,
    SplitResult,
)
from .otlp import (
    DEFAULT_SCOPE_NAME,
    OTLP_LOGS_PATH,
    AsyncBetterStackOtlpLogExporter,
    BetterStackOtlpLogExporter,
    OtlpPartialSuccess,
    OtlpProtocol,
    encode_otlp_protobuf,
    otlp_json_request,
    parse_partial_success,
)
from .query import (
    DEFAULT_SEARCH_LIMIT,
    DEFAULT_SEARCH_WINDOW,
    MAX_SEARCH_LIMIT,
    AsyncBetterStackQuery,
    BetterStackQuery,
    QueryParamValue,
    QueryRow,
    escape_param_value,
    format_datetime64,
    json_escape,
    log_record_from_row,
    parse_datetime,
    parse_rows,
    sql_string,
)

__all__ = [
    "__version__",
    # clients + config
    "BetterStackClient",
    "AsyncBetterStackClient",
    "BetterStackLogClient",
    "AsyncBetterStackLogClient",
    "LogProtocol",
    "BetterStackConfig",
    "DEFAULT_URL",
    "ENCODINGS",
    "IDENTIFIER_PATTERN",
    "IngestEncoding",
    "is_valid_identifier",
    "normalize_base_url",
    # ingest
    "CONTENT_TYPES",
    "BetterStackEvent",
    "encode_events",
    "encode_msgpack",
    "encoded_event_size",
    "event_json",
    "to_better_stack_event",
    "MAX_RECORD_BYTES",
    "MAX_REQUEST_BYTES",
    "BetterStackLogExporter",
    "AsyncBetterStackLogExporter",
    "IngestPayload",
    "SplitResult",
    # OTLP
    "DEFAULT_SCOPE_NAME",
    "OTLP_LOGS_PATH",
    "BetterStackOtlpLogExporter",
    "AsyncBetterStackOtlpLogExporter",
    "OtlpPartialSuccess",
    "OtlpProtocol",
    "encode_otlp_protobuf",
    "otlp_json_request",
    "parse_partial_success",
    # SQL query
    "DEFAULT_SEARCH_LIMIT",
    "DEFAULT_SEARCH_WINDOW",
    "MAX_SEARCH_LIMIT",
    "BetterStackQuery",
    "AsyncBetterStackQuery",
    "QueryParamValue",
    "QueryRow",
    "format_datetime64",
    "escape_param_value",
    "json_escape",
    "log_record_from_row",
    "parse_datetime",
    "parse_rows",
    "sql_string",
    # service errors
    "CLICKHOUSE_EXCEPTION_HEADER",
    "clickhouse_code",
    "error_for_clickhouse_code",
    "map_ingest_error",
    "map_query_error",
    "query_error_from_response",
]
__all__ += _core.__all__

from . import _logging  # noqa: E402
from ._logging import *  # noqa: E402, F403

__all__ += _logging.__all__
