# @grow-codes/better-stack-logs

TypeScript SDK for [Better Stack Logs](https://grow-codes-packages.github.io/better-stack-logs/) by Grow Codes.
Node.js 22.12 or newer, ESM and CommonJS, full type definitions.

```sh
npm install @grow-codes/better-stack-logs
```

```ts
import { BetterStackClient } from "@grow-codes/better-stack-logs";

// Reads GROW_BETTER_STACK_LOGS_URL, GROW_BETTER_STACK_LOGS_TOKEN, timeouts, TLS and proxy settings.
const client = BetterStackClient.fromEnv();
try {
  console.log(await client.health()); // { healthy: true, status: "ok", details: {} }
  console.log(`ping: ${(await client.ping()).toFixed(1)} ms`);
} finally {
  await client.close();
}
```

Every error is a `BetterStackError` subclass with a canonical `code` (`NOT_FOUND`,
`AUTHENTICATION`, `RATE_LIMITED`, …), `httpStatus`, `requestId` and `retryable`. Credentials
never appear in messages, logs, hook data or `toString()` output.

Test helpers (in-memory fakes and the conformance runner) live in
`@grow-codes/better-stack-logs/testing`.

Documentation: https://grow-codes-packages.github.io/better-stack-logs/ · Licence: Apache-2.0.
Better Stack Logs is a trademark of Better Stack, Inc.; this package is not affiliated with or endorsed by it.
