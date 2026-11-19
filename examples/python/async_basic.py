"""The async client: ingest, OTLP, query; cancelling the task raises the SDK's CancelledError."""

from __future__ import annotations

import asyncio
import uuid

import grow_better_stack_logs as sdk


async def main() -> None:
    marker = f"example-async-{uuid.uuid4().hex[:8]}"
    async with sdk.AsyncBetterStackClient.from_env() as client:
        print(await client.health())
        task = asyncio.create_task(client.ping())
        try:
            print(f"ping: {await asyncio.wait_for(task, timeout=5):.1f} ms")
        except asyncio.TimeoutError:
            print("ping took longer than 5 s and was cancelled")

        # OTLP/HTTP protobuf to {url}/v1/logs through an async batching client.
        logs = client.log_client(protocol="otlp", resource={"service.name": "example-async"})
        logs.logger().warn(f"{marker} via OTLP", {"attempt": 1})
        await logs.flush(10)

        q = client.query()
        record = await q.wait_for_log(marker, timeout_s=60, interval_s=2)
        print("found:", record.severity_text, record.body)


if __name__ == "__main__":
    asyncio.run(main())
