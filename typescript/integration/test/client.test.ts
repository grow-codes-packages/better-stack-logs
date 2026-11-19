// Integration tests against the contract-verified Better Stack stub from docker/compose.yml
// (ingest host :8080 + ClickHouse-HTTP SQL host :8123). Every public function of
// @grow-codes/better-stack-logs is called at least once with a round trip: what the SDK sends to
// the ingest host is read back through the SQL query API (query / searchLogs / waitForLog).

import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { after, before, describe, test } from "node:test";
import {
  BetterStackClient,
  BetterStackConfig,
  BetterStackError,
  BetterStackLogClient,
  Severity,
  logRecordFromRow,
  sqlString,
  type LogRecord,
} from "@grow-codes/better-stack-logs";
import { loadBootstrapEnv, runId } from "./env.ts";

const run = `${runId}-${randomBytes(4).toString("hex")}`;
const TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
const SPAN = "00f067aa0ba902b7";
const clients: BetterStackClient[] = [];
let table: string;

function client(overrides: ConstructorParameters<typeof BetterStackClient>[0] = {}) {
  const c = BetterStackClient.fromEnv({
    retry: { maxRetries: 2, initialBackoffMs: 20 },
    ...(overrides as object),
  });
  clients.push(c);
  return c;
}

function env(name: string): string {
  const v = process.env[name];
  assert.ok(v, `${name} must be set (bootstrap env)`);
  return v;
}

function ingestOnly(overrides: ConstructorParameters<typeof BetterStackClient>[0] = {}) {
  const c = new BetterStackClient({
    url: env("BETTERSTACK_INGESTING_URL"),
    sourceToken: env("BETTERSTACK_SOURCE_TOKEN"),
    retry: { maxRetries: 2, initialBackoffMs: 20 },
    ...(overrides as object),
  });
  clients.push(c);
  return c;
}

async function fault(body: Record<string, unknown>): Promise<void> {
  const target = body.target === "query" ? "QUERY" : "INGEST";
  const res = await fetch(`${env(`BETTERSTACK_STUB_${target}_CONTROL_URL`)}/fault`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  assert.ok(res.ok, `fault injection failed: ${res.status}`);
}

function rec(body: string, extra: Partial<LogRecord> = {}): LogRecord {
  return {
    timestamp: new Date(),
    severity: Severity.Info,
    severityText: "INFO",
    body,
    attributes: {},
    resource: {},
    ...extra,
  };
}

function expectCode(code: string, retryable?: boolean) {
  return (err: unknown): boolean => {
    assert.ok(err instanceof BetterStackError, String(err));
    assert.equal(err.code, code, err.message);
    if (retryable !== undefined) assert.equal(err.retryable, retryable);
    return true;
  };
}

before(() => {
  loadBootstrapEnv();
  table = env("BETTERSTACK_SOURCE_TABLE");
});

after(async () => {
  await Promise.all(clients.map((c) => c.close()));
});

describe("config", () => {
  test("fromEnv reads both sides and redacts credentials", () => {
    const cfg = BetterStackConfig.fromEnv();
    assert.equal(cfg.hasIngest, true);
    assert.equal(cfg.hasQuery, true);
    assert.equal(cfg.sourceTable, table);
    const text = `${cfg.toString()} ${JSON.stringify(cfg)}`;
    assert.ok(!text.includes(env("BETTERSTACK_SOURCE_TOKEN")));
    assert.ok(!text.includes(env("BETTERSTACK_QUERY_PASSWORD")));
    cfg.validate();
  });

  test("bare BETTERSTACK_INGESTING_HOST becomes https://host:port", () => {
    const cfg = BetterStackConfig.fromEnv(
      {},
      {
        BETTERSTACK_INGESTING_HOST: env("BETTERSTACK_INGESTING_HOST"),
        BETTERSTACK_SOURCE_TOKEN: "x-token",
      },
    );
    assert.equal(cfg.url, `https://${env("BETTERSTACK_INGESTING_HOST")}`);
  });
});

describe("client surface", () => {
  test("health via the query host /ping", async () => {
    const h = await client().health();
    assert.equal(h.healthy, true, JSON.stringify(h));
    assert.equal(h.details.endpoint, "query");
  });

  test("health of an ingest-only client probes the ingest host", async () => {
    const h = await ingestOnly().health();
    assert.equal(h.healthy, true, JSON.stringify(h));
    assert.equal(h.details.endpoint, "ingest");
  });

  test("ping with SQL credentials and with the source token only", async () => {
    assert.ok((await client().ping()) >= 0);
    assert.ok((await ingestOnly().ping()) >= 0);
  });

  test("ping with a wrong source token → AUTHENTICATION without leaking it", async () => {
    const wrong = "wrong-source-token-integration";
    await assert.rejects(ingestOnly({ sourceToken: wrong }).ping(), (err: unknown) => {
      expectCode("AUTHENTICATION", false)(err);
      assert.ok(!(err as Error).message.includes(wrong));
      return true;
    });
  });

  test("ping with a wrong SQL password → AUTHENTICATION without leaking it", async () => {
    const wrong = "wrong-query-password-integration";
    await assert.rejects(client({ queryPassword: wrong }).ping(), (err: unknown) => {
      expectCode("AUTHENTICATION", false)(err);
      assert.ok(!(err as Error).message.includes(wrong));
      return true;
    });
  });

  test("serverVersion is unsupported for the SaaS", async () => {
    await assert.rejects(client().serverVersion(), expectCode("UNSUPPORTED"));
  });

  test("close is idempotent and blocks new log clients", async () => {
    const c = client();
    await c.close();
    await c.close();
    assert.throws(() => c.logClient(), expectCode("CANCELLED"));
    await assert.rejects(c.ping(), expectCode("CANCELLED"));
  });
});

describe("ingest exporter → SQL read-back", () => {
  for (const encoding of ["json", "ndjson", "msgpack"] as const) {
    for (const gzip of [true, false]) {
      test(`sendEvents ${encoding} gzip=${gzip}`, async () => {
        const c = client();
        const marker = `bs-${run}-${encoding}-${gzip}`;
        const dt = new Date("2026-01-02T03:04:05.678Z");
        const exporter = c.newExporter({ encoding });
        assert.equal(exporter.encoding, encoding);
        await exporter.sendEvents(
          [
            { message: `${marker} a`, dt, n: 7, nested: { ok: true, text: "Grüße ✓" } },
            { message: `${marker} b`, dt: Date.now() },
          ],
          { gzip },
        );
        const rows = await c
          .query()
          .query(
            `SELECT dt, raw, JSONExtractInt(raw, 'n') AS n FROM remote(${table}_logs) WHERE position(raw, {needle:String}) > 0 ORDER BY dt ASC`,
            { needle: marker },
          );
        assert.equal(rows.length, 2);
        assert.equal(rows[0]?.dt, "2026-01-02 03:04:05.678000");
        assert.equal(String(rows[0]?.n), "7");
        const first = logRecordFromRow(rows[0] ?? {});
        assert.equal(first.body, `${marker} a`);
        assert.deepEqual(first.attributes.nested, { ok: true, text: "Grüße ✓" });
      });
    }
  }

  test("exporter.export maps LogRecords (severity, trace ids, resource, attributes)", async () => {
    const c = client();
    const marker = `bs-${run}-export`;
    const result = await c.exporter.export(
      [
        rec(`${marker} one`, {
          severity: Severity.Error,
          severityText: "ERROR",
          traceId: TRACE,
          spanId: SPAN,
          traceFlags: 1,
          attributes: { user: { id: 42 }, tags: ["a", "b"] },
          resource: { "service.name": "sdk-ts-it" },
        }),
        rec(`${marker} two`),
      ],
      { gzip: true },
    );
    assert.deepEqual(result, { accepted: 2, rejected: 0 });
    const found = await c.query().searchLogs({ contains: marker, order: "asc" });
    assert.equal(found.records.length, 2);
    const one = found.records.find((r) => r.body.endsWith("one"));
    assert.ok(one);
    assert.equal(one.severity, Severity.Error);
    assert.equal(one.severityText, "ERROR");
    assert.equal(one.traceId, TRACE);
    assert.equal(one.spanId, SPAN);
    assert.equal(one.traceFlags, 1);
    assert.deepEqual(one.resource, { "service.name": "sdk-ts-it" });
    assert.deepEqual(one.attributes, { user: { id: 42 }, tags: ["a", "b"] });
  });

  for (const protocol of ["protobuf", "json"] as const) {
    test(`OTLP /v1/logs exporter (${protocol})`, async () => {
      const c = client();
      const marker = `bs-${run}-otlp-${protocol}`;
      const exporter =
        protocol === "protobuf" ? c.otlpExporter : c.newOtlpExporter({ protocol: "json" });
      const result = await exporter.export(
        [
          rec(`${marker} x`, {
            severity: Severity.Warn,
            severityText: "WARN",
            traceId: TRACE,
            spanId: SPAN,
            attributes: { n: 3, ratio: 0.5, ok: true, list: [1, "two"], map: { k: "v" } },
            resource: { "service.name": "sdk-ts-otlp" },
          }),
        ],
        { gzip: protocol === "protobuf" },
      );
      assert.deepEqual(result, { accepted: 1, rejected: 0 });
      const r = await c.query().waitForLog(marker, { timeoutMs: 10_000, intervalMs: 200 });
      assert.equal(r.body, `${marker} x`);
      assert.equal(r.severity, Severity.Warn);
      assert.equal(r.traceId, TRACE);
      assert.equal(r.spanId, SPAN);
      assert.equal(r.resource["service.name"], "sdk-ts-otlp");
      assert.deepEqual(r.attributes.list, [1, "two"]);
      assert.deepEqual(r.attributes.map, { k: "v" });
    });
  }

  test("402 quota → RATE_LIMITED, not retryable", async () => {
    const c = client();
    await fault({ status: 402, count: 1, target: "ingest", body: "Quota exceeded" });
    await assert.rejects(
      c.exporter.sendEvents([{ message: `bs-${run}-quota` }]),
      expectCode("RATE_LIMITED", false),
    );
  });

  test("429 with Retry-After is retried and then succeeds", async () => {
    const c = client();
    const marker = `bs-${run}-retry`;
    await fault({ status: 429, count: 1, target: "ingest", body: "slow down", retryAfter: 1 });
    await c.exporter.sendEvents([{ message: marker }]);
    const r = await c.query().waitForLog(marker, { timeoutMs: 10_000, intervalMs: 200 });
    assert.equal(r.body, marker);
  });

  test("an unparseable body → VALIDATION (406)", async () => {
    const c = client();
    await assert.rejects(
      c.exporter.sendEvents([1 as unknown as Record<string, unknown>], { encoding: "ndjson" }),
      expectCode("VALIDATION"),
    );
  });
});

describe("log client + logger", () => {
  for (const protocol of ["http", "otlp"] as const) {
    test(`logClient(${protocol}) batches, logger correlates, waitForLog finds`, async () => {
      const c = client();
      const logs = c.logClient({
        protocol,
        batch: { flushIntervalMs: 100 },
        resource: { "service.name": `sdk-ts-${protocol}` },
      });
      assert.ok(logs instanceof BetterStackLogClient);
      const marker = `bs-${run}-logger-${protocol}`;
      const log = logs.logger("it").child({ run });
      log.warn(`${marker} warn`, { password: "hunter2-secret" });
      logs.send({ message: `${marker} send`, traceId: TRACE, spanId: SPAN });
      logs.sendBatch([{ message: `${marker} batch 0` }, { message: `${marker} batch 1` }]);
      await logs.flush();
      assert.equal(logs.stats().sent, 4);
      const q = c.query();
      const warn = await q.waitForLog(`${marker} warn`, { timeoutMs: 10_000, intervalMs: 200 });
      assert.equal(warn.severity, Severity.Warn);
      assert.ok(!JSON.stringify(warn).includes("hunter2-secret"));
      const all = await q.search(marker, { limit: 10 });
      assert.equal(all.records.length, 4);
      await logs.close();
    });
  }
});

describe("SQL query API", () => {
  test("query binds parameters of several types; FORMAT JSON; queryRaw", async () => {
    const c = client();
    const marker = `bs-${run}-params`;
    await c.exporter.sendEvents([{ message: marker, n: 5, dt: new Date("2025-05-05T05:05:05Z") }]);
    const q = c.query();
    const rows = await q.query(
      `SELECT count() AS c FROM remote(${table}_logs) WHERE position(raw, {needle:String}) > 0 AND JSONExtractInt(raw, 'n') = {n:Int64} AND dt >= {since:DateTime64(6)}`,
      { needle: marker, n: 5, since: new Date("2025-01-01T00:00:00Z") },
    );
    assert.deepEqual(rows, [{ c: "1" }]);
    const json = await q.query(
      `SELECT count() AS c FROM remote(${table}_logs) WHERE position(raw, ${sqlString(marker)}) > 0 FORMAT JSON`,
    );
    assert.deepEqual(json, [{ c: "1" }]);
    const raw = await q.queryRaw(
      "SELECT 1 AS one FORMAT JSONEachRow",
      {},
      { settings: { output_format_pretty_row_numbers: 0 } },
    );
    assert.match(raw.trim(), /^\{"one":"?1"?\}$/);
  });

  test("query binds a String with a backslash and a quote (escaped TSV parameter format)", async () => {
    const c = client();
    const value = `C:\\tmp\\it's "${run}"`;
    const rows = await c.query().query("SELECT {v:String} AS v", { v: value });
    assert.deepEqual(rows, [{ v: value }]);
  });

  test("errors map by ClickHouse code", async () => {
    const q = client().query();
    await assert.rejects(q.query("SELECT dt FROM remote(t_missing_logs)"), expectCode("NOT_FOUND"));
    await assert.rejects(q.query("SELEKT nonsense"), expectCode("VALIDATION"));
    // UNKNOWN_IDENTIFIER (47) is a ValidationError even though ClickHouse answers 404.
    await assert.rejects(
      q.query(`SELECT nope FROM remote(${table}_logs)`),
      expectCode("VALIDATION"),
    );
  });

  test("503 on the query host is retried", async () => {
    const q = client().query();
    await fault({ status: 503, count: 1, target: "query", body: "busy" });
    const rows = await q.query("SELECT 1 AS one");
    assert.equal(String(rows[0]?.one), "1");
  });

  test("searchLogs: window, order, limit + cursor, quotes, source isolation", async () => {
    const c = client();
    const marker = `bs-${run}-search it's \\ "quoted"`;
    const base = Date.parse("2024-03-01T00:00:00Z");
    await c.exporter.export(
      [0, 1, 2, 3, 4].map((i) => rec(`${marker} ${i}`, { timestamp: new Date(base + i * 1000) })),
      { gzip: true },
    );
    const q = c.query();
    const window = { start: new Date(base), end: new Date(base + 10_000) };
    const desc = await q.searchLogs({ contains: marker, ...window, limit: 2 });
    assert.deepEqual(
      desc.records.map((r) => r.body),
      [`${marker} 4`, `${marker} 3`],
    );
    assert.ok(desc.nextCursor);
    const page2 = await q.searchLogs({
      contains: marker,
      ...window,
      limit: 2,
      cursor: desc.nextCursor,
    });
    assert.deepEqual(
      page2.records.map((r) => r.body),
      [`${marker} 2`, `${marker} 1`],
    );
    const asc = await q.searchLogs({ contains: marker, ...window, order: "asc", limit: 100 });
    assert.equal(asc.records.length, 5);
    assert.equal(asc.records[0]?.body, `${marker} 0`);
    assert.equal(asc.nextCursor, undefined);
    assert.equal(asc.records[0]?.timestamp.toISOString(), "2024-03-01T00:00:00.000Z");
    const narrow = await q.searchLogs({
      contains: marker,
      start: new Date(base + 1000),
      end: new Date(base + 1000),
    });
    assert.deepEqual(
      narrow.records.map((r) => r.body),
      [`${marker} 1`],
    );
    const other = await q.searchLogs({
      source: env("BETTERSTACK_SOURCE_TABLE_B"),
      contains: marker,
      ...window,
    });
    assert.equal(other.records.length, 0);
  });

  test("a second source token writes only to its own collection", async () => {
    const tableB = env("BETTERSTACK_SOURCE_TABLE_B");
    const b = client({ sourceToken: env("BETTERSTACK_SOURCE_TOKEN_B"), sourceTable: tableB });
    const marker = `bs-${run}-source-b`;
    await b.exporter.sendEvents([{ message: marker }]);
    assert.equal((await b.query().search(marker)).records.length, 1);
    assert.equal((await client().query().search(marker)).records.length, 0);
    assert.deepEqual(await b.query().listStreams(), [tableB]);
  });

  test("waitForLog times out, listLabels is unsupported, tail polls", async () => {
    const c = client();
    const q = c.query();
    await assert.rejects(
      q.waitForLog(`bs-${run}-never`, { timeoutMs: 300, intervalMs: 100 }),
      expectCode("TIMEOUT"),
    );
    await assert.rejects(q.listLabels(), expectCode("UNSUPPORTED"));
    const marker = `bs-${run}-tail`;
    const seen: string[] = [];
    const handle = q.tail(marker, (r) => seen.push(r.body), {
      start: new Date(Date.now() - 1000),
      intervalMs: 100,
    });
    try {
      await c.exporter.sendEvents([{ message: `${marker} 1` }]);
      const deadline = Date.now() + 10_000;
      while (seen.length === 0 && Date.now() < deadline) {
        await new Promise((r) => setTimeout(r, 100));
      }
    } finally {
      handle.stop();
    }
    assert.deepEqual(seen, [`${marker} 1`]);
  });
});
