// Service-owned (taken over from the sdk-kit skeleton): core + logging conformance scenarios
// against an in-process fake of the two Better Stack hosts (ingest stores events, the SQL host
// answers the searchLogs query by substring), so the shared scenarios run without Docker.

import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { BetterStackClient } from "../../src/client.js";
import {
  type ConformanceOptions,
  conformanceCases,
  formatReport,
  runConformance,
} from "../../src/testing/index.js";
import { type RawServer, rawServer } from "../support/fixtures.js";

const scenariosDir = fileURLToPath(new URL("../../../conformance/scenarios/", import.meta.url));
let ingest: RawServer;
let query: RawServer;
const stored: { dt: string; raw: string }[] = [];

function unescapeSql(s: string): string {
  return s.replace(/\\(.)/g, "$1");
}

beforeAll(async () => {
  ingest = await rawServer((req) => {
    if (req.method !== "POST") return { status: 405 };
    for (const event of JSON.parse(req.text) as Record<string, unknown>[]) {
      stored.push({ dt: String(event.dt).replace("T", " ").replace("Z", "000"), raw: JSON.stringify(event) });
    }
    return { status: 202 };
  });
  query = await rawServer((req) => {
    if (req.path === "/ping") return { status: 200, body: "Ok.\n" };
    const needle = /position\(raw, '((?:[^'\\]|\\.)*)'\) > 0/.exec(req.text)?.[1];
    const limit = Number(/LIMIT (\d+)/.exec(req.text)?.[1] ?? "100");
    const rows =
      needle === undefined
        ? []
        : stored.filter((r) => r.raw.includes(unescapeSql(needle))).slice(0, limit);
    return { status: 200, body: rows.map((r) => `${JSON.stringify(r)}\n`).join("") };
  });
});

afterAll(async () => {
  await ingest.close();
  await query.close();
});

function client(): BetterStackClient {
  return new BetterStackClient({
    url: ingest.url,
    sourceToken: "conformance-token",
    queryUrl: query.url,
    queryUsername: "u",
    queryPassword: "conformance-password",
    sourceTable: "t1_conformance",
  });
}

function options(): ConformanceOptions {
  return {
    scenariosDir,
    categories: ["core", "logging"],
    factories: {
      core: () => client(),
      logging: (ctx) => {
        const c = client();
        return {
          client: c.logClient({ batch: { flushIntervalMs: 50, ...(ctx.batchOptions ?? {}) } }),
          query: c.query(),
        };
      },
    },
    capabilities: { core: [], logging: ["query"] },
  };
}

describe("conformance (core + logging) against an in-process Better Stack fake", () => {
  it("every scenario passes", async () => {
    const report = await runConformance(options());
    expect(report.failed, formatReport(report)).toBe(0);
    expect(report.results.map((r) => r.id)).toContain("core.health");
    expect(report.results.map((r) => r.id)).toContain("logging.roundtrip");
  });

  for (const c of conformanceCases(options())) {
    it.skipIf(c.skip !== undefined)(`case ${c.id}`, async () => {
      expect((await c.run()).status).toBe("passed");
    });
  }
});
