// Shared conformance scenarios (conformance/scenarios) against the Better Stack stub.
//   core:    capabilities [] — "version" is not declared: Better Stack (SaaS) reports no version.
//   logging: capability "query" — records go through the real ingest host and are read back with
//            the SQL query API (searchLogs on the configured collection).

import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { BetterStackClient } from "@grow-codes/better-stack-logs";
import { formatReport, runConformance } from "@grow-codes/better-stack-logs/testing";
import { loadBootstrapEnv, scenariosDir } from "./env.ts";

const clients: BetterStackClient[] = [];

function betterStack(): BetterStackClient {
  const c = BetterStackClient.fromEnv({ retry: { maxRetries: 2, initialBackoffMs: 50 } });
  clients.push(c);
  return c;
}

before(() => {
  loadBootstrapEnv();
});

after(async () => {
  await Promise.all(clients.map((c) => c.close()));
});

test("conformance: core + logging", async () => {
  const report = await runConformance({
    scenariosDir,
    categories: ["core", "logging"],
    factories: {
      core: () => betterStack(),
      logging: (ctx) => {
        const c = betterStack();
        return {
          client: c.logClient({
            batch: { flushIntervalMs: 200, ...(ctx.batchOptions ?? {}) },
            resource: { "service.name": "sdk-ts-conformance" },
          }),
          query: c.query(),
        };
      },
    },
    capabilities: { core: [], logging: ["query"] },
  });
  console.log(formatReport(report));
  assert.equal(report.failed, 0, formatReport(report));
  assert.equal(report.skipped, 0, formatReport(report));
  assert.ok(report.passed >= 4);
});
