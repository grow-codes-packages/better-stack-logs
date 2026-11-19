"""BetterStackConfig: sides, validation, environment precedence, normalization, redaction."""

from __future__ import annotations

import base64
from typing import Any

import pytest

import grow_better_stack_logs as sdk

P = sdk.ENV_PREFIX
INGEST = {"url": "http://ingest:8080", "source_token": "src-token-123"}
QUERY = {
    "query_url": "http://query:8123",
    "query_username": "user",
    "query_password": "query-pass-456",
}


def test_sides_and_defaults() -> None:
    both = sdk.BetterStackConfig(**INGEST, **QUERY).validate()
    assert both.has_ingest and both.has_query
    assert both.encoding == "json"
    assert sdk.DEFAULT_URL == ""
    ingest = sdk.BetterStackConfig(**INGEST).validate()
    assert ingest.has_ingest and not ingest.has_query
    query = sdk.BetterStackConfig(**QUERY).validate()
    assert query.has_query and not query.has_ingest
    assert query.transport_config("query").url == "http://query:8123"
    with pytest.raises(sdk.ConfigError, match="ingestion"):
        query.transport_config("ingest")
    with pytest.raises(sdk.ConfigError, match="SQL"):
        ingest.transport_config("query")
    common = both.transport_config("ingest")
    assert type(common) is sdk.BaseConfig
    assert (common.url, common.retry) == ("http://ingest:8080", both.retry)


@pytest.mark.parametrize(
    ("kwargs", "message"),
    [
        ({}, "set url"),
        ({"url": "http://x"}, "source_token is required"),
        ({"source_token": "t"}, "url (the source's ingesting host) is required"),
        ({"query_url": "http://q"}, "must be given together"),
        ({**INGEST, "query_username": "u"}, "must be given together"),
        ({**INGEST, "source_token": "   "}, "must not be blank"),
        ({**INGEST, "source_table": "bad-name"}, "source_table"),
        ({**INGEST, "source_table": "a b"}, "source_table"),
        ({**INGEST, "encoding": "xml"}, "encoding"),
        ({"url": "ftp://x", "source_token": "t"}, "http or https"),
        ({**QUERY, "query_url": "ftp://q"}, "http or https"),
        ({**INGEST, "connect_timeout": 0}, "connect_timeout"),
    ],
)
def test_validate(kwargs: dict[str, Any], message: str) -> None:
    with pytest.raises(sdk.ConfigError, match=message.replace("(", r"\(").replace(")", r"\)")):
        sdk.BetterStackConfig(**kwargs).validate()


def test_normalization() -> None:
    cfg = sdk.BetterStackConfig(
        url=" s1234.eu-nbg-2.betterstackdata.com/ ",
        source_token="t",
        query_url="eu-nbg-2-connect.betterstackdata.com:443",
        query_username=" user ",
        query_password="p",
        source_table=" t1_app ",
        encoding=" MSGPACK ",  # type: ignore[arg-type]
    ).validate()
    assert cfg.url == "https://s1234.eu-nbg-2.betterstackdata.com"
    assert cfg.query_url == "https://eu-nbg-2-connect.betterstackdata.com:443"
    assert (cfg.query_username, cfg.source_table, cfg.encoding) == ("user", "t1_app", "msgpack")
    assert sdk.normalize_base_url(None) == ""
    assert sdk.normalize_base_url("  ") == ""
    assert sdk.normalize_base_url("http://h:1//") == "http://h:1"
    blank = sdk.BetterStackConfig(source_token="", query_password="", source_table=" ")
    assert (blank.source_token, blank.query_password, blank.source_table) == (None, None, None)
    assert sdk.is_valid_identifier("t1_app")
    assert not sdk.is_valid_identifier("1abc")
    assert sdk.ENCODINGS == ("json", "ndjson", "msgpack")


def test_env_precedence() -> None:
    """core.config.env-precedence"""
    vendor = {
        "BETTERSTACK_INGESTING_HOST": "s1.example.com",
        "BETTERSTACK_SOURCE_TOKEN": "vendor-token",
        "BETTERSTACK_QUERY_HOST": "q.example.com",
        "BETTERSTACK_QUERY_USERNAME": "vendor-user",
        "BETTERSTACK_QUERY_PASSWORD": "vendor-pass",
        "BETTERSTACK_SOURCE_TABLE": "t1_vendor",
    }
    cfg = sdk.BetterStackConfig.from_env(vendor)
    assert cfg.url == "https://s1.example.com"
    assert cfg.query_url == "https://q.example.com"
    assert isinstance(cfg.source_token, sdk.Secret)
    assert cfg.source_token.reveal() == "vendor-token"
    assert cfg.query_password is not None and cfg.query_password.reveal() == "vendor-pass"
    assert (cfg.query_username, cfg.source_table) == ("vendor-user", "t1_vendor")
    # *_URL beats *_HOST
    env = {
        **vendor,
        "BETTERSTACK_INGESTING_URL": "http://ingest:8080",
        "BETTERSTACK_QUERY_URL": "http://query:8123",
    }
    cfg = sdk.BetterStackConfig.from_env(env)
    assert (cfg.url, cfg.query_url) == ("http://ingest:8080", "http://query:8123")
    # GROW_* beats vendor
    env.update(
        {
            P + "URL": "http://grow-ingest:1",
            P + "SOURCE_TOKEN": "grow-token",
            P + "QUERY_URL": "http://grow-query:2",
            P + "QUERY_USERNAME": "grow-user",
            P + "QUERY_PASSWORD": "grow-pass",
            P + "SOURCE_TABLE": "t1_grow",
            P + "ENCODING": "ndjson",
            P + "MAX_RETRIES": "1",
        }
    )
    cfg = sdk.BetterStackConfig.from_env(env)
    assert (cfg.url, cfg.query_url, cfg.query_username) == (
        "http://grow-ingest:1",
        "http://grow-query:2",
        "grow-user",
    )
    assert cfg.source_token is not None and cfg.source_token.reveal() == "grow-token"
    assert (cfg.source_table, cfg.encoding, cfg.retry.max_retries) == ("t1_grow", "ndjson", 1)
    # explicit beats everything
    cfg = sdk.BetterStackConfig.from_env(env, url="http://explicit:3", encoding="msgpack")
    assert (cfg.url, cfg.encoding) == ("http://explicit:3", "msgpack")
    with pytest.raises(sdk.ConfigError) as info:
        sdk.BetterStackConfig.from_env({**env, P + "REQUEST_TIMEOUT": "fast-please"})
    assert "fast-please" not in str(info.value)


def test_repr_and_secrets() -> None:
    """core.redaction.config"""
    cfg = sdk.BetterStackConfig(
        **INGEST,
        **{**QUERY, "query_url": "https://qu:qpw@query.example/"},
        headers={"X-Api-Key": "k-123"},
    )
    for text in (repr(cfg), str(cfg)):
        for secret in ("src-token-123", "query-pass-456", "qpw", "k-123"):
            assert secret not in text
        assert "query.example" in text
    basic = base64.b64encode(b"user:query-pass-456").decode()
    assert cfg.basic_credentials() == basic
    assert sorted(cfg.known_secrets()) == sorted(["src-token-123", "query-pass-456", basic])
    assert sdk.BetterStackConfig(**INGEST).basic_credentials() is None
    assert "query_url=''" in repr(sdk.BetterStackConfig(**INGEST))
