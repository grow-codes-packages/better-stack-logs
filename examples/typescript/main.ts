// Better Stack Logs with the TypeScript SDK: send logs through the source's ingesting host and read
// them back with the SQL query API.
//
//   (cd ../../typescript && npm install && npm run build)   # or use the published package
//   npm install
//   BETTERSTACK_INGESTING_HOST=s1234.eu-nbg-2.betterstackdata.com BETTERSTACK_SOURCE_TOKEN=... \
//   BETTERSTACK_QUERY_HOST=eu-nbg-2-connect.betterstackdata.com BETTERSTACK_QUERY_USERNAME=... \
//   BETTERSTACK_QUERY_PASSWORD=... BETTERSTACK_SOURCE_TABLE=t1234_myapp npm start
//
// Against the Docker stub use GROW_BETTER_STACK_LOGS_URL / GROW_BETTER_STACK_LOGS_QUERY_URL
// (full http:// URLs) instead of the *_HOST variables.

import { randomUUID } from "node:crypto";
import { BetterStackClient, BetterStackError } from "@grow-codes/better-stack-logs";

const client = BetterStackClient.fromEnv({
  // Warnings only; the default logger is silent.
  logger: { debug() {}, info() {}, warn: console.warn, error: console.error },
  hooks: {
    onError(info) {
      if (info.willRetry)
        console.error(`retrying ${info.method} ${info.url} (attempt ${info.attempt})`);
    },
  },
});
console.log(`config: ${client.config}`); // credentials print as [REDACTED]

try {
  console.log("health:", await client.health()); // never throws for an unreachable service
  console.log(`ping: ${(await client.ping({ signal: AbortSignal.timeout(10_000) })).toFixed(1)} ms`);

  // 1. Send: batching, gzip, redaction (the password attribute never leaves the process).
  const marker = `example-${randomUUID()}`;
  const logs = client.logClient({ resource: { "service.name": "example", "deployment.environment": "dev" } });
  const log = logs.logger("checkout").child({ marker });
  log.info(`${marker} order placed`, { orderId: 42, password: "hunter2" });
  log.error(`${marker} payment failed`, { reason: "card declined" });
  await logs.flush();
  console.log("stats:", logs.stats());

  // 2. Read back: searchLogs over hot + cold storage, then plain SQL with bound parameters.
  if (client.config.hasQuery) {
    const query = client.query();
    const first = await query.waitForLog(marker, { timeoutMs: 60_000, intervalMs: 2_000 });
    console.log("found:", first.severityText, first.body, first.attributes);
    const page = await query.searchLogs({ contains: marker, order: "asc", limit: 10 });
    console.log(`searchLogs: ${page.records.length} record(s)`);
    const rows = await query.query(
      `SELECT count() AS c FROM remote(${client.config.sourceTable}_logs) WHERE position(raw, {needle:String}) > 0`,
      { needle: marker },
    );
    console.log("count:", rows[0]?.c);
  }
} catch (err) {
  if (err instanceof BetterStackError) {
    // Canonical code + HTTP status + request id; the message never contains credentials.
    console.error(`${err.code}: ${err.message}`, { httpStatus: err.httpStatus, requestId: err.requestId });
    process.exitCode = 1;
  } else {
    throw err;
  }
} finally {
  await client.close(); // flushes every log client, then closes both connections
}
