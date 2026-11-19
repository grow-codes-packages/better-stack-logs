"""Integration fixtures: environment from /bootstrap/env and the service URL (SPEC section 8)."""

from __future__ import annotations

import json
import os
import secrets
import urllib.request
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any

import pytest
from factories import OPEN_CLIENTS, URL_VAR, core_client

import grow_better_stack_logs as sdk


def load_env() -> dict[str, str]:
    """Process environment overlaid with the KEY=value lines of the bootstrap file."""
    env = dict(os.environ)
    path = Path(env.get("GROW_BOOTSTRAP_ENV", "/bootstrap/env"))
    if path.is_file():
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if line and not line.startswith("#") and "=" in line:
                key, _, value = line.partition("=")
                env.setdefault(key.strip(), value.strip())
    return env


@pytest.fixture(scope="session")
def env() -> dict[str, str]:
    values = load_env()
    if not values.get(URL_VAR):
        pytest.skip(f"{URL_VAR} is not set (run through docker/compose.yml)")
    return values


@pytest.fixture(scope="session")
def run() -> str:
    """Unique marker of this test run (GROW_TEST_RUN_ID + random suffix)."""
    return f"{os.environ.get('GROW_TEST_RUN_ID', 'local')}-{secrets.token_hex(4)}"


@pytest.fixture
def client(env: dict[str, str]) -> Iterator[sdk.BetterStackClient]:
    with core_client(env) as c:
        yield c


@pytest.fixture
def fault(env: dict[str, str]) -> Callable[[dict[str, Any]], None]:
    """Inject a stub fault (``POST /__stub/fault``) on the ingest or query listener."""

    def inject(body: dict[str, Any]) -> None:
        side = "QUERY" if body.get("target") == "query" else "INGEST"
        req = urllib.request.Request(  # noqa: S310 - test stub URL
            f"{env[f'BETTERSTACK_STUB_{side}_CONTROL_URL']}/fault",
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=10) as resp:  # noqa: S310 - test stub URL
            assert resp.status < 300

    return inject


@pytest.fixture(autouse=True, scope="session")
def close_clients() -> Iterator[None]:
    yield
    for c in OPEN_CLIENTS:
        c.close()
