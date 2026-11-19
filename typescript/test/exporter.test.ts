import { afterEach, describe, expect, it } from "vitest";
import {
  AuthenticationError,
  BetterStackClient,
  BetterStackLogExporter,
  HttpTransport,
  BetterStackConfig,
  MAX_REQUEST_BYTES,
  RateLimitError,
  Severity,
  UnavailableError,
  ValidationError,
  type LogRecord,
} from "../src/index.js";
import { decodeMsgpack, type RawServer, rawServer, replyFrom } from "./support/fixtures.js";

const servers: RawServer[] = [];
const clients: BetterStackClient[] = [];
afterEach(async () => {
  await Promise.all(clients.splice(0).map((c) => c.close()));
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

const TOKEN = "src-token-value";

async function setup(replies: Parameters<typeof rawServer>[0] = [{ status: 202 }]) {
  const s = await rawServer(replies);
  servers.push(s);
  const c = new BetterStackClient({
    url: s.url,
    sourceToken: TOKEN,
    retry: { maxRetries: 2, initialBackoffMs: 1 },
  });
  clients.push(c);
  return { s, c };
}

function rec(body: string, extra: Partial<LogRecord> = {}): LogRecord {
  return {
    timestamp: new Date("2026-11-18T21:00:00.000Z"),
    severity: Severity.Info,
    severityText: "INFO",
    body,
    attributes: {},
    resource: {},
    ...extra,
  };
}

describe("BetterStackLogExporter.export", () => {
  it("posts a gzipped JSON array with the Bearer token", async () => {
    const { s, c } = await setup([replyFrom("sendBatch.gzip_json")]);
    const result = await c.exporter.export([rec("a"), rec("b")], { gzip: true });
    expect(result).toEqual({ accepted: 2, rejected: 0 });
    const req = s.requests[0];
    expect(req?.path).toBe("/");
    expect(req?.headers["content-encoding"]).toBe("gzip");
    expect(req?.headers["content-type"]).toBe("application/json");
    expect(req?.headers.authorization).toBe(`Bearer ${TOKEN}`);
    expect(JSON.parse(req?.text ?? "")).toEqual([
      { dt: "2026-11-18T21:00:00.000Z", message: "a", level: "info", severity_number: 9 },
      { dt: "2026-11-18T21:00:00.000Z", message: "b", level: "info", severity_number: 9 },
    ]);
  });

  it("uses the config encoding as the default; an exporter option overrides it", async () => {
    const { s } = await setup();
    const cfg = new BetterStackConfig({ url: s.url, sourceToken: TOKEN, encoding: "ndjson" });
    const transport = new HttpTransport({ config: cfg.transportConfig("ingest") });
    const nd = new BetterStackLogExporter(transport, cfg);
    expect(nd.encoding).toBe("ndjson");
    await nd.export([rec("a")], { gzip: false });
    expect(s.requests[0]?.headers["content-type"]).toBe("application/x-ndjson");
    expect(new BetterStackLogExporter(transport, cfg, { encoding: "json" }).encoding).toBe("json");
    await transport.close();
  });

  it("sends NDJSON and MessagePack without gzip", async () => {
    const { s } = await setup();
    const t = { url: s.url, sourceToken: TOKEN };
    const cfg = new BetterStackConfig(t);
    const transport = new HttpTransport({ config: cfg.transportConfig("ingest") });
    const nd = new BetterStackLogExporter(transport, cfg, { encoding: "ndjson" });
    expect(await nd.export([rec("a")], { gzip: false })).toEqual({ accepted: 1, rejected: 0 });
    expect(s.requests[0]?.headers["content-type"]).toBe("application/x-ndjson");
    expect(s.requests[0]?.headers["content-encoding"]).toBeUndefined();
    expect(s.requests[0]?.text.endsWith("\n")).toBe(true);
    const mp = new BetterStackLogExporter(transport, cfg, { encoding: "msgpack" });
    await mp.export([rec("m")], { gzip: false });
    expect(s.requests[1]?.headers["content-type"]).toBe("application/msgpack");
    expect(decodeMsgpack(s.requests[1]?.body ?? new Uint8Array())).toEqual([
      { dt: new Date("2026-11-18T21:00:00.000Z"), message: "m", level: "info", severity_number: 9 },
    ]);
    await transport.close();
    await nd.shutdown();
  });

  it("splits batches by maxRequestBytes and drops oversized records", async () => {
    const { s, c } = await setup();
    const cfg = c.config;
    const transport = new HttpTransport({ config: cfg.transportConfig("ingest") });
    const exp = new BetterStackLogExporter(transport, cfg, {
      maxRequestBytes: 300,
      maxRecordBytes: 200,
    });
    const records = [rec("x".repeat(100)), rec("y".repeat(100)), rec("z".repeat(500))];
    const split = exp.split(records);
    expect(split.oversized).toBe(1);
    expect(split.invalid).toBe(0);
    expect(split.payloads.map((p) => p.events.length)).toEqual([1, 1]);
    const result = await exp.export(records, { gzip: false });
    expect(result.accepted).toBe(2);
    expect(result.rejected).toBe(1);
    expect(result.error).toBeInstanceOf(ValidationError);
    expect(s.requests).toHaveLength(2);
    await transport.close();
  });

  it("stops at the first failing request and reports the mapped error", async () => {
    const { s, c } = await setup([{ status: 202 }, replyFrom("send.402_fault")]);
    const cfg = c.config;
    const transport = new HttpTransport({ config: cfg.transportConfig("ingest") });
    const exp = new BetterStackLogExporter(transport, cfg, { maxRequestBytes: 200 });
    const result = await exp.export([rec("a".repeat(80)), rec("b".repeat(80)), rec("c")], {
      gzip: false,
    });
    expect(result.accepted).toBe(1);
    expect(result.rejected).toBe(2);
    expect(result.error).toBeInstanceOf(RateLimitError);
    expect(result.error?.retryable).toBe(false);
    expect(result.error?.httpStatus).toBe(402);
    expect(s.requests).toHaveLength(2);
    await transport.close();
  });

  it("drops records that cannot be encoded", async () => {
    const { c } = await setup();
    const transport = new HttpTransport({ config: c.config.transportConfig("ingest") });
    const exp = new BetterStackLogExporter(transport, c.config, { encoding: "msgpack" });
    const result = await exp.export([rec("ok"), rec("x", { timestamp: new Date(Number.NaN) })], {
      gzip: false,
    });
    expect(result).toMatchObject({ accepted: 1, rejected: 1 });
    expect(result.error?.message).toMatch(/1 not encodable as msgpack/);
    await transport.close();
  });

  it("wraps unexpected failures as UnavailableError", async () => {
    const { c } = await setup();
    const broken = {
      request: () => Promise.reject(new Error("boom")),
    } as unknown as HttpTransport;
    const exp = new BetterStackLogExporter(broken, c.config);
    const result = await exp.export([rec("a")], { gzip: false });
    expect(result.accepted).toBe(0);
    expect(result.error).toBeInstanceOf(UnavailableError);
  });

  it("passes the abort signal", async () => {
    const { c } = await setup([{ status: 202, delayMs: 200 }]);
    const ac = new AbortController();
    const p = c.exporter.export([rec("a")], { gzip: true, signal: ac.signal });
    ac.abort();
    const result = await p;
    expect(result.error?.code).toBe("CANCELLED");
  });
});

describe("BetterStackLogExporter.sendEvents", () => {
  it.each([
    ["send.403_bad_token", AuthenticationError, 403],
    ["send.406_bad_json", ValidationError, 406],
    ["send.413_too_large", ValidationError, 413],
    ["send.402_fault", RateLimitError, 402],
  ] as const)("maps %s", async (name, cls, status) => {
    const { c } = await setup([replyFrom(name)]);
    const err = await c.exporter.sendEvents([{ message: "x" }]).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(cls);
    expect((err as { httpStatus?: number }).httpStatus).toBe(status);
    expect((err as Error).message).not.toContain(TOKEN);
  });

  it("retries 429 and 503 (exports are marked idempotent)", async () => {
    const { s, c } = await setup([
      { ...replyFrom("send.429_fault"), headers: { "retry-after": "0" } },
      replyFrom("send.503_fault"),
      { status: 202 },
    ]);
    await c.exporter.sendEvents([{ message: "x" }], { encoding: "msgpack", gzip: false });
    expect(s.requests).toHaveLength(3);
  });

  it("refuses bodies over the request limit before sending", async () => {
    const { s, c } = await setup();
    await expect(
      c.exporter.sendEvents([{ message: "x".repeat(MAX_REQUEST_BYTES) }]),
    ).rejects.toThrow(/exceeds/);
    expect(s.requests).toHaveLength(0);
  });

  it("validates exporter options", async () => {
    const { c } = await setup();
    const transport = new HttpTransport({ config: c.config.transportConfig("ingest") });
    expect(
      () => new BetterStackLogExporter(transport, c.config, { encoding: "xml" as never }),
    ).toThrow(/encoding/);
    expect(() => new BetterStackLogExporter(transport, c.config, { maxRequestBytes: 0 })).toThrow(
      /maxRequestBytes/,
    );
    expect(
      new BetterStackLogExporter(transport, c.config, { maxRecordBytes: MAX_REQUEST_BYTES * 2 }),
    ).toBeInstanceOf(BetterStackLogExporter);
    await transport.close();
  });
});
