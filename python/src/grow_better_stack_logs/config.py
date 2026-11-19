"""Configuration for the Better Stack Logs client.

This file is owned by the service package (generated once by sdk-kit, then maintained here).

Better Stack Logs has two hosts with separate credentials (API.md §1/§2, DECISIONS 1 and 4):

* the **ingest** host: the source's ingesting host (``s1234.eu-nbg-2.betterstackdata.com``),
  ``Authorization: Bearer <source token>``;
* the **SQL query** host: the ClickHouse HTTP interface of a connection
  (``eu-nbg-2-connect.betterstackdata.com``), HTTP Basic ``<username>:<password>``.

Each side is all-or-nothing and a client needs at least one complete side. Credentials of one
host are never sent to the other.
"""

from __future__ import annotations

import base64
import dataclasses
import re
from collections.abc import Mapping
from typing import Any, ClassVar, Literal, TypeVar, get_args

from ._core import BaseConfig, ConfigError, EnvReader, Secret, redact_url
from ._core.redaction import safe_repr

__all__ = [
    "DEFAULT_URL",
    "ENCODINGS",
    "IDENTIFIER_PATTERN",
    "IngestEncoding",
    "BetterStackConfig",
    "is_valid_identifier",
    "normalize_base_url",
]

#: Better Stack has no global ingest host: every source has its own ingesting host, so ``url``
#: must be set whenever the ingest API is used. The default is therefore empty.
DEFAULT_URL = ""

#: Ingest body encodings accepted by ``POST /`` on the ingesting host.
IngestEncoding = Literal["json", "ndjson", "msgpack"]

#: Every :data:`IngestEncoding`, in documentation order.
ENCODINGS: tuple[str, ...] = get_args(IngestEncoding)

#: Collection (table) names are identifiers: they cannot be bound as SQL parameters.
IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")

_C = TypeVar("_C", bound="BetterStackConfig")


def is_valid_identifier(value: str) -> bool:
    """Whether ``value`` is a safe ClickHouse identifier (``^[A-Za-z_][A-Za-z0-9_]*$``)."""
    return IDENTIFIER_PATTERN.fullmatch(value) is not None


def normalize_base_url(value: str | None) -> str:
    """Normalize a host or URL.

    A value containing ``://`` is used as given, a bare ``host[:port]`` becomes
    ``https://host[:port]``; trailing slashes are removed. ``None`` and blank input give ``""``.
    """
    if value is None:
        return ""
    text = value.strip()
    if not text:
        return ""
    if "://" not in text:
        text = "https://" + text
    return text.rstrip("/")


@dataclasses.dataclass(frozen=True, repr=False)
class BetterStackConfig(BaseConfig):
    """Immutable settings for :class:`~grow_better_stack_logs.BetterStackClient`.

    Common fields (timeouts, ``retry``, ``tls``, ``proxy``, ``headers``, ``user_agent_suffix``,
    ``hooks``, ``logger``) come from :class:`BaseConfig` and apply to both hosts. Durations are
    float seconds; ``datetime.timedelta`` is accepted. ``source_token`` and ``query_password``
    accept a ``str`` and are stored as :class:`Secret`; ``repr()`` never shows them.

    Fields (environment variables of :meth:`from_env` in precedence order after the explicit
    value):

    * ``url`` - ingest base URL, the source's ingesting host. A value without ``://`` gets
      ``https://``. Required for sending logs; there is no default host.
      ``GROW_BETTER_STACK_LOGS_URL``, ``BETTERSTACK_INGESTING_URL``,
      ``BETTERSTACK_INGESTING_HOST``.
    * ``source_token`` - sent as ``Authorization: Bearer`` to the ingest host only.
      ``GROW_BETTER_STACK_LOGS_SOURCE_TOKEN``, ``BETTERSTACK_SOURCE_TOKEN``.
    * ``query_url`` - SQL query base URL (ClickHouse HTTP interface).
      ``GROW_BETTER_STACK_LOGS_QUERY_URL``, ``BETTERSTACK_QUERY_URL``, ``BETTERSTACK_QUERY_HOST``.
    * ``query_username`` / ``query_password`` - HTTP Basic credentials of the SQL connection.
      ``GROW_BETTER_STACK_LOGS_QUERY_USERNAME`` / ``_QUERY_PASSWORD``,
      ``BETTERSTACK_QUERY_USERNAME`` / ``BETTERSTACK_QUERY_PASSWORD``.
    * ``source_table`` - default collection (``t1234_myapp``) for ``search``, ``search_logs``,
      ``tail`` and ``ping``. ``GROW_BETTER_STACK_LOGS_SOURCE_TABLE``,
      ``BETTERSTACK_SOURCE_TABLE``.
    * ``encoding`` - ingest body encoding, ``json`` (default), ``ndjson`` or ``msgpack``.
      ``GROW_BETTER_STACK_LOGS_ENCODING``.

    Common variables: ``CONNECT_TIMEOUT``, ``REQUEST_TIMEOUT``, ``TOTAL_TIMEOUT``,
    ``MAX_RETRIES``, ``CA_CERT``, ``CLIENT_CERT``, ``CLIENT_KEY``, ``INSECURE_SKIP_VERIFY``,
    ``PROXY`` (prefix ``GROW_BETTER_STACK_LOGS_``).
    """

    #: Vendor variables per GROW_* suffix, in precedence order (API.md §2).
    ENV_VENDOR: ClassVar[Mapping[str, tuple[str, ...]]] = {
        "URL": ("BETTERSTACK_INGESTING_URL", "BETTERSTACK_INGESTING_HOST"),
        "SOURCE_TOKEN": ("BETTERSTACK_SOURCE_TOKEN",),
        "QUERY_URL": ("BETTERSTACK_QUERY_URL", "BETTERSTACK_QUERY_HOST"),
        "QUERY_USERNAME": ("BETTERSTACK_QUERY_USERNAME",),
        "QUERY_PASSWORD": ("BETTERSTACK_QUERY_PASSWORD",),
        "SOURCE_TABLE": ("BETTERSTACK_SOURCE_TABLE",),
    }

    url: str = DEFAULT_URL
    source_token: Secret | None = None
    query_url: str = ""
    query_username: str | None = None
    query_password: Secret | None = None
    source_table: str | None = None
    encoding: IngestEncoding = "json"

    def __post_init__(self) -> None:
        super().__post_init__()
        object.__setattr__(self, "url", normalize_base_url(self.url))
        object.__setattr__(self, "query_url", normalize_base_url(self.query_url))
        for name in ("query_username", "source_table"):
            value = getattr(self, name)
            if value is not None:
                object.__setattr__(self, name, value.strip() or None)
        for name in ("source_token", "query_password"):
            if getattr(self, name) is not None and not getattr(self, name).reveal():
                object.__setattr__(self, name, None)
        if isinstance(self.encoding, str):
            object.__setattr__(self, "encoding", self.encoding.strip().lower() or "json")

    @classmethod
    def _env_values(cls, reader: EnvReader) -> dict[str, Any]:
        values = super()._env_values(reader)
        vendor = cls.ENV_VENDOR
        for field, suffix in (
            ("source_token", "SOURCE_TOKEN"),
            ("query_url", "QUERY_URL"),
            ("query_username", "QUERY_USERNAME"),
            ("query_password", "QUERY_PASSWORD"),
            ("source_table", "SOURCE_TABLE"),
            ("encoding", "ENCODING"),
        ):
            value = reader.string(suffix, vendor.get(suffix, ()))
            if value is not None:
                values[field] = Secret(value) if field.endswith(("token", "password")) else value
        return values

    @classmethod
    def from_env(cls: type[_C], env: Mapping[str, str] | None = None, **overrides: Any) -> _C:
        """Build from the environment, then validate (raises :class:`ConfigError`).

        Precedence: explicit keyword > ``GROW_BETTER_STACK_LOGS_<NAME>`` > vendor variables
        (``BETTERSTACK_INGESTING_URL``, ``BETTERSTACK_INGESTING_HOST``,
        ``BETTERSTACK_SOURCE_TOKEN``, ``BETTERSTACK_QUERY_URL``, ``BETTERSTACK_QUERY_HOST``,
        ``BETTERSTACK_QUERY_USERNAME``, ``BETTERSTACK_QUERY_PASSWORD``,
        ``BETTERSTACK_SOURCE_TABLE``) > default. ``env`` defaults to ``os.environ``.
        """
        return super().from_env(env, **overrides)

    # ------------------------------------------------------------------ sides

    @property
    def has_ingest(self) -> bool:
        """Whether the ingest side (``url`` + ``source_token``) is configured."""
        return bool(self.url) and self.source_token is not None

    @property
    def has_query(self) -> bool:
        """Whether the SQL side (``query_url`` + ``query_username`` + ``query_password``) is set."""
        return (
            bool(self.query_url)
            and self.query_username is not None
            and self.query_password is not None
        )

    def common_config(self, url: str) -> BaseConfig:
        """The common settings with ``url`` as base URL (one per host transport)."""
        return BaseConfig(
            url=url,
            connect_timeout=self.connect_timeout,
            request_timeout=self.request_timeout,
            total_timeout=self.total_timeout,
            retry=self.retry,
            tls=self.tls,
            proxy=self.proxy,
            headers=self.headers,
            user_agent_suffix=self.user_agent_suffix,
            hooks=self.hooks,
            logger=self.logger,
        )

    def transport_config(self, side: Literal["ingest", "query"]) -> BaseConfig:
        """The common settings for one side's transport; :class:`ConfigError` when not set."""
        if side == "ingest":
            if not self.has_ingest:
                raise ConfigError(
                    "config: url (the source's ingesting host) and source_token are required "
                    "for ingestion"
                )
            return self.common_config(self.url)
        if not self.has_query:
            raise ConfigError(
                "config: query_url, query_username and query_password are required for SQL queries"
            )
        return self.common_config(self.query_url)

    # ------------------------------------------------------------------ validation

    def validate(self: _C) -> _C:
        """Check the configuration; raises :class:`ConfigError`. Returns ``self``.

        The ingest set (``url`` + ``source_token``) and the query set (``query_url`` +
        ``query_username`` + ``query_password``) are each all-or-nothing and at least one is
        complete; URLs are absolute http(s) URLs; ``source_table`` is an identifier;
        ``encoding`` is json, ndjson or msgpack; the common rules hold.
        """
        ingest_any = bool(self.url) or self.source_token is not None
        query_any = (
            bool(self.query_url)
            or self.query_username is not None
            or self.query_password is not None
        )
        if not ingest_any and not query_any:
            raise ConfigError(
                "config: set url + source_token (ingest) and/or query_url + query_username + "
                "query_password (SQL query)"
            )
        problems: list[str] = []
        if ingest_any and not self.url:
            problems.append("url (the source's ingesting host) is required with source_token")
        if ingest_any and self.source_token is None:
            problems.append("source_token is required with url")
        if query_any and not self.has_query:
            problems.append("query_url, query_username and query_password must be given together")
        if self.source_token is not None and not self.source_token.reveal().strip():
            problems.append("source_token must not be blank")
        if self.source_table is not None and not is_valid_identifier(self.source_table):
            problems.append("source_table must match ^[A-Za-z_][A-Za-z0-9_]*$")
        if self.encoding not in ENCODINGS:
            problems.append("encoding must be json, ndjson or msgpack")
        if problems:
            raise ConfigError("config: " + "; ".join(problems))
        for url in (self.url, self.query_url):
            if url:
                self.common_config(url).validate()
        return self

    # ------------------------------------------------------------------ secrets

    def basic_credentials(self) -> str | None:
        """``base64(username:password)`` for HTTP Basic, or ``None`` without query credentials."""
        if self.query_username is None or self.query_password is None:
            return None
        raw = f"{self.query_username}:{self.query_password.reveal()}".encode()
        return base64.b64encode(raw).decode("ascii")

    def known_secrets(self) -> list[str]:
        """Credential values to redact from error messages and logs."""
        out = super().known_secrets()
        basic = self.basic_credentials()
        if basic is not None:
            out.append(basic)
        return out

    def __repr__(self) -> str:
        text = safe_repr(self)
        if self.query_url:
            text = text.replace(
                f"query_url={self.query_url!r}", f"query_url={redact_url(self.query_url)!r}"
            )
        return text

    __str__ = __repr__
