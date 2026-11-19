# @grow-codes/better-stack-logs

TypeScript SDK for [Better Stack Logs](https://grow-codes-packages.github.io/better-stack-logs/) by Grow Codes.
Node.js 22.12 or newer, ESM and CommonJS, full type definitions.

```sh
npm install @grow-codes/better-stack-logs
```

```ts
import { BetterStackClient } from "@grow-codes/better-stack-logs";

// Ingest: BETTERSTACK_INGESTING_HOST + BETTERSTACK_SOURCE_TOKEN (or GROW_BETTER_STACK_LOGS_URL /
// _SOURCE_TOKEN). SQL query: BETTERSTACK_QUERY_HOST, _QUERY_USERNAME, _QUERY_PASSWORD, _SOURCE_TABLE.
const client = BetterStackClient.fromEnv();
try {
  const logs = client.logClient({ resource: { "service.name": "checkout" } });
  logs.logger().info("order placed", { orderId: 42 }); // batched, gzipped, redacted
  await logs.flush();

  const found = await client.query().waitForLog("order placed", { timeoutMs: 60_000 });
  console.log(found.timestamp, found.body, found.attributes);
} finally {
  await client.close(); // flushes every log client
}
```

| Area | API |
| - | - |
| Client | `BetterStackClient` (`fromEnv`, `health`, `ping`, `serverVersion` → `UNSUPPORTED`, `close`) |
| Ingest | `logClient({ protocol: "http" \| "otlp" })` → `BetterStackLogClient`, `exporter` (`BetterStackLogExporter`: JSON / NDJSON / MessagePack, gzip, 10 MiB splitting, `sendEvents`), `otlpExporter` (OTLP/HTTP `/v1/logs`, protobuf or JSON) |
| Query | `query()` → `BetterStackQuery`: `query(sql, params)`, `queryRaw`, `searchLogs({ source, contains, start, end, limit, order, cursor })`, `search`, `waitForLog`, `tail` (polling), `listStreams` |
| Logging | `Logger`, `BatchProcessor`, `RedactionProcessor`, pino / winston transports |

Every error is a `BetterStackError` subclass with a canonical `code` (`NOT_FOUND`,
`AUTHENTICATION`, `RATE_LIMITED`, …), `httpStatus`, `requestId` and `retryable`. Credentials
never appear in messages, logs, hook data or `toString()` output.

Test helpers (in-memory fakes and the conformance runner) live in
`@grow-codes/better-stack-logs/testing`.

Documentation: https://grow-codes-packages.github.io/better-stack-logs/ · Licence: Apache-2.0.
Better Stack Logs is a trademark of Better Stack, Inc.; this package is not affiliated with or endorsed by it.
