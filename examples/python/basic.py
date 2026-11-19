"""Send logs to Better Stack and read them back with the SQL query API (sync client).

    export GROW_BETTER_STACK_LOGS_URL=https://s1234.eu-nbg-2.betterstackdata.com
    export GROW_BETTER_STACK_LOGS_SOURCE_TOKEN=...
    export GROW_BETTER_STACK_LOGS_QUERY_URL=https://eu-nbg-2-connect.betterstackdata.com
    export GROW_BETTER_STACK_LOGS_QUERY_USERNAME=... GROW_BETTER_STACK_LOGS_QUERY_PASSWORD=...
    export GROW_BETTER_STACK_LOGS_SOURCE_TABLE=t1234_myapp
    python examples/python/basic.py
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timedelta, timezone

import grow_better_stack_logs as sdk


def main() -> None:
    marker = f"example-{uuid.uuid4().hex[:8]}"
    with sdk.BetterStackClient.from_env() as client:
        print(client.config)  # credentials print as [REDACTED]
        print("health:", client.health())
        print(f"ping: {client.ping():.1f} ms")

        # 1. Batching log client + Logger facade (trace ids come from the active OTel span).
        logs = client.log_client(
            options=sdk.BatchOptions(flush_interval_s=0.5),
            resource={"service.name": "example", "deployment.environment": "dev"},
        )
        log = logs.logger("example").child({"marker": marker})
        log.info(f"{marker} order placed", {"order_id": 42, "password": "redacted-before-send"})
        log.error(f"{marker} payment failed", {"order_id": 42})

        # 2. The stdlib logging module through LogHandler.
        std = logging.getLogger("example.std")
        std.addHandler(sdk.LogHandler(logs))
        std.warning("%s from the logging module", marker)
        logs.flush(10)
        print("stats:", logs.stats())

        # 3. Raw events (dt, nested JSON) with another encoding.
        client.new_exporter(encoding="msgpack").send_events(
            [{"message": f"{marker} raw event", "dt": datetime.now(timezone.utc), "n": 7}]
        )

        # 4. Read back: wait, search and SQL with server-side parameter binding.
        q = client.query()
        print("found:", q.wait_for_log(f"{marker} raw event", timeout_s=60, interval_s=2).body)
        page = q.search_logs(
            contains=marker, start=datetime.now(timezone.utc) - timedelta(minutes=5), limit=10
        )
        for record in page.records:
            print(f"  {record.timestamp:%H:%M:%S} {record.severity_text:5} {record.body}")
        table = client.config.source_table
        rows = q.query(
            f"SELECT count() AS c FROM remote({table}_logs) WHERE position(raw, {{m:String}}) > 0",
            {"m": marker},
        )
        print("count:", rows[0]["c"])

        # 5. Errors are typed.
        try:
            client.server_version()
        except sdk.UnsupportedError as err:
            print("server_version:", err)
        try:
            q.query("SELECT dt FROM remote(t_missing_logs)")
        except sdk.NotFoundError as err:
            print("not found:", err.code, err.http_status)


if __name__ == "__main__":
    main()
