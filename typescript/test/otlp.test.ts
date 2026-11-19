import { afterEach, describe, expect, it } from "vitest";
import {
  AuthenticationError,
  BetterStackClient,
  BetterStackOtlpLogExporter,
  HttpTransport,
  OTLP_LOGS_PATH,
  SDK_VERSION,
  Severity,
  UnavailableError,
  ValidationError,
  encodeOtlpLogRecord,
  encodeOtlpProtobuf,
  otlpJsonRequest,
  parsePartialSuccess,
  type LogRecord,
} from "../src/index.js";
import { type RawServer, rawServer, replyFrom } from "./support/fixtures.js";

const servers: RawServer[] = [];
const clients: BetterStackClient[] = [];
afterEach(async () => {
  await Promise.all(clients.splice(0).map((c) => c.close()));
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

const TOKEN = "src-token-value";
const TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
const SPAN = "00f067aa0ba902b7";
const T = new Date("2026-11-18T21:00:00.123Z");

function rec(body: string, extra: Partial<LogRecord> = {}): LogRecord {
  return {
    timestamp: T,
    severity: Severity.Warn,
    severityText: "WARN",
    body,
    attributes: {},
    resource: { "service.name": "svc" },
    ...extra,
  };
}

async function setup(replies: Parameters<typeof rawServer>[0] = [{ status: 200 }]) {
  const s = await rawServer(replies);
  servers.push(s);
  const c = new BetterStackClient({
    url: s.url,
    sourceToken: TOKEN,
    retry: { maxRetries: 1, initialBackoffMs: 1 },
  });
  clients.push(c);
  return { s, c };
}

// --- tiny protobuf reader for assertions ---
type Field = [number, number, bigint | Uint8Array];
function fields(data: Uint8Array): Field[] {
  const out: Field[] = [];
  let pos = 0;
  const varint = (): bigint => {
    let r = 0n;
    let shift = 0n;
    for (;;) {
      const b = data[pos++] as number;
      r |= BigInt(b & 0x7f) << shift;
      if ((b & 0x80) === 0) return r;
      shift += 7n;
    }
  };
  while (pos < data.length) {
    const key = varint();
    const f = Number(key >> 3n);
    const w = Number(key & 7n);
    if (w === 0) out.push([f, w, varint()]);
    else if (w === 1) {
      out.push([f, w, data.slice(pos, pos + 8)]);
      pos += 8;
    } else if (w === 5) {
      out.push([f, w, data.slice(pos, pos + 4)]);
      pos += 4;
    } else {
      const len = Number(varint());
      out.push([f, w, data.slice(pos, pos + len)]);
      pos += len;
    }
  }
  return out;
}
const sub = (fs: Field[], n: number): Uint8Array[] =>
  fs.filter(([f]) => f === n).map(([, , v]) => v as Uint8Array);
const text = (b: Uint8Array | undefined): string => new TextDecoder().decode(b);

describe("OTLP protobuf encoding", () => {
  it("encodes resource, scope and log record fields", () => {
    const bytes = encodeOtlpProtobuf([
      rec("hello", {
        traceId: TRACE,
        spanId: SPAN,
        traceFlags: 1,
        observedTimestamp: T,
        attributes: { s: "x", n: 3, f: 0.5, b: true, list: [1, "two"], map: { k: "v" } },
      }),
    ]);
    const [rl] = sub(fields(bytes), 1);
    const rlFields = fields(rl as Uint8Array);
    const [resource] = sub(rlFields, 1);
    const [kv] = sub(fields(resource as Uint8Array), 1);
    expect(text(sub(fields(kv as Uint8Array), 1)[0])).toBe("service.name");
    const [sl] = sub(rlFields, 2);
    const slFields = fields(sl as Uint8Array);
    const [scope] = sub(slFields, 1);
    expect(text(sub(fields(scope as Uint8Array), 1)[0])).toBe("@grow-codes/better-stack-logs");
    expect(text(sub(fields(scope as Uint8Array), 2)[0])).toBe(SDK_VERSION);
    const [log] = sub(slFields, 2);
    const lf = fields(log as Uint8Array);
    const time = lf.find(([f]) => f === 1)?.[2] as Uint8Array;
    expect(new DataView(time.buffer).getBigUint64(0, true)).toBe(1_795_035_600_123_000_000n);
    expect(lf.find(([f]) => f === 2)?.[2]).toBe(13n);
    expect(text(sub(lf, 3)[0])).toBe("WARN");
    expect(text(sub(fields(sub(lf, 5)[0] as Uint8Array), 1)[0])).toBe("hello");
    expect(sub(lf, 6)).toHaveLength(6);
    expect(Buffer.from(sub(lf, 9)[0] as Uint8Array).toString("hex")).toBe(TRACE);
    expect(Buffer.from(sub(lf, 10)[0] as Uint8Array).toString("hex")).toBe(SPAN);
    expect(lf.some(([f]) => f === 8)).toBe(true);
    expect(lf.some(([f]) => f === 11)).toBe(true);
  });

  it("groups records by resource", () => {
    const bytes = encodeOtlpProtobuf([
      rec("a"),
      rec("b", { resource: { "service.name": "other" } }),
      rec("c"),
    ]);
    const groups = sub(fields(bytes), 1);
    expect(groups).toHaveLength(2);
    expect(encodeOtlpLogRecord(rec("x")).length).toBeGreaterThan(0);
  });

  it("builds the OTLP JSON request", () => {
    const req = otlpJsonRequest(
      [
        rec("hello", {
          traceId: TRACE,
          spanId: SPAN,
          traceFlags: 1,
          observedTimestamp: T,
          attributes: { n: 3, f: 0.5, b: true, list: ["a"], map: { k: "v" } },
        }),
      ],
      "scope-x",
    );
    expect(req).toEqual({
      resourceLogs: [
        {
          resource: { attributes: [{ key: "service.name", value: { stringValue: "svc" } }] },
          scopeLogs: [
            {
              scope: { name: "scope-x", version: SDK_VERSION },
              logRecords: [
                {
                  timeUnixNano: "1795035600123000000",
                  observedTimeUnixNano: "1795035600123000000",
                  severityNumber: 13,
                  severityText: "WARN",
                  body: { stringValue: "hello" },
                  attributes: [
                    { key: "n", value: { intValue: "3" } },
                    { key: "f", value: { doubleValue: 0.5 } },
                    { key: "b", value: { boolValue: true } },
                    { key: "list", value: { arrayValue: { values: [{ stringValue: "a" }] } } },
                    {
                      key: "map",
                      value: {
                        kvlistValue: { values: [{ key: "k", value: { stringValue: "v" } }] },
                      },
                    },
                  ],
                  flags: 1,
                  traceId: TRACE,
                  spanId: SPAN,
                },
              ],
            },
          ],
        },
      ],
    });
  });
});

describe("parsePartialSuccess", () => {
  it("reads protobuf and JSON partial success", () => {
    // ExportLogsServiceResponse{partial_success{rejected_log_records=2, error_message="bad"}}
    const inner = [0x08, 0x02, 0x12, 0x03, 0x62, 0x61, 0x64];
    expect(parsePartialSuccess(Uint8Array.from([0x0a, inner.length, ...inner]))).toEqual({
      rejected: 2,
      message: "bad",
    });
    expect(parsePartialSuccess(Uint8Array.from([0x0a, 0x02, 0x08, 0x01]))).toEqual({ rejected: 1 });
    expect(parsePartialSuccess(Uint8Array.from([0x0a, 0x00]))).toBeUndefined();
    expect(parsePartialSuccess(new Uint8Array())).toBeUndefined();
    // unknown fields of every wire type are skipped
    expect(
      parsePartialSuccess(
        Uint8Array.from([0x10, 0x01, 0x19, 1, 2, 3, 4, 5, 6, 7, 8, 0x25, 1, 2, 3, 4]),
      ),
    ).toBeUndefined();
    expect(parsePartialSuccess(Uint8Array.from([0x0a, 0x05, 0x08]))).toBeUndefined();
    expect(parsePartialSuccess(Uint8Array.from([0x0b]))).toBeUndefined();
    expect(parsePartialSuccess(Uint8Array.from([0x08, 0xff]))).toBeUndefined();
    expect(
      parsePartialSuccess(Uint8Array.from([0x08, ...Array.from({ length: 11 }, () => 0xff)])),
    ).toBeUndefined();
    expect(
      parsePartialSuccess({ partialSuccess: { rejectedLogRecords: "3", errorMessage: "why" } }),
    ).toEqual({ rejected: 3, message: "why" });
    expect(parsePartialSuccess({ partialSuccess: { rejectedLogRecords: 1 } })).toEqual({
      rejected: 1,
    });
    expect(parsePartialSuccess({ partialSuccess: {} })).toBeUndefined();
    expect(parsePartialSuccess({})).toBeUndefined();
    expect(parsePartialSuccess(null)).toBeUndefined();
  });
});

describe("BetterStackOtlpLogExporter", () => {
  it("posts protobuf to /v1/logs with gzip and the Bearer token", async () => {
    const { s, c } = await setup();
    const result = await c.otlpExporter.export([rec("a"), rec("b")], { gzip: true });
    expect(result).toEqual({ accepted: 2, rejected: 0 });
    const req = s.requests[0];
    expect(req?.path).toBe(OTLP_LOGS_PATH);
    expect(req?.headers["content-type"]).toBe("application/x-protobuf");
    expect(req?.headers["content-encoding"]).toBe("gzip");
    expect(req?.headers.authorization).toBe(`Bearer ${TOKEN}`);
    expect(sub(fields(req?.body ?? new Uint8Array()), 1)).toHaveLength(1);
  });

  it("posts OTLP JSON and honours a JSON partial success", async () => {
    const { s, c } = await setup([
      {
        status: 200,
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ partialSuccess: { rejectedLogRecords: "1", errorMessage: "dup" } }),
      },
      { status: 200, body: "{}" },
      { status: 200, body: "" },
      { status: 200, body: "not json" },
    ]);
    const transport = new HttpTransport({ config: c.config.transportConfig("ingest") });
    const exp = new BetterStackOtlpLogExporter(transport, c.config, {
      protocol: "json",
      scopeName: "s",
    });
    const r1 = await exp.export([rec("a"), rec("b")], { gzip: false });
    expect(r1.accepted).toBe(1);
    expect(r1.rejected).toBe(1);
    expect(r1.error).toBeInstanceOf(ValidationError);
    expect(r1.error?.message).toContain("dup");
    expect(s.requests[0]?.headers["content-type"]).toBe("application/json");
    expect(JSON.parse(s.requests[0]?.text ?? "{}").resourceLogs).toHaveLength(1);
    expect(await exp.export([rec("c")], { gzip: false })).toEqual({ accepted: 1, rejected: 0 });
    expect(await exp.send([rec("d")])).toBeUndefined();
    expect(await exp.send([rec("e")], { gzip: false })).toBeUndefined();
    await exp.shutdown();
    await transport.close();
  });

  it("honours a protobuf partial success without a message", async () => {
    const { c } = await setup([{ status: 200, body: Uint8Array.from([0x0a, 0x02, 0x08, 0x01]) }]);
    const r = await c.otlpExporter.export([rec("a")], { gzip: false });
    expect(r.rejected).toBe(1);
    expect(r.error?.message).toBe("logs.otlp: the service rejected 1 record(s)");
  });

  it("maps 403 to AuthenticationError and stops", async () => {
    const { c } = await setup([replyFrom("send.403_bad_token")]);
    const r = await c.otlpExporter.export([rec("a")], { gzip: false });
    expect(r).toMatchObject({ accepted: 0, rejected: 1 });
    expect(r.error).toBeInstanceOf(AuthenticationError);
  });

  it("splits by maxRequestBytes and drops oversized or invalid records", async () => {
    const { s, c } = await setup();
    const transport = new HttpTransport({ config: c.config.transportConfig("ingest") });
    const exp = new BetterStackOtlpLogExporter(transport, c.config, { maxRequestBytes: 200 });
    const r = await exp.export(
      [
        rec("a".repeat(60)),
        rec("b".repeat(60)),
        rec("c".repeat(500)),
        rec("d", { timestamp: new Date(Number.NaN) }),
      ],
      { gzip: false, signal: new AbortController().signal },
    );
    expect(r.accepted).toBe(2);
    expect(r.rejected).toBe(2);
    expect(r.error).toBeInstanceOf(ValidationError);
    expect(s.requests).toHaveLength(2);
    await transport.close();
  });

  it("wraps unexpected failures and validates options", async () => {
    const { c } = await setup();
    const broken = { request: () => Promise.reject(new Error("boom")) } as unknown as HttpTransport;
    const r = await new BetterStackOtlpLogExporter(broken, c.config).export([rec("a")], {
      gzip: false,
    });
    expect(r.error).toBeInstanceOf(UnavailableError);
    const t = broken;
    expect(
      () => new BetterStackOtlpLogExporter(t, c.config, { protocol: "grpc" as never }),
    ).toThrow(/protocol/);
    expect(() => new BetterStackOtlpLogExporter(t, c.config, { maxRequestBytes: -1 })).toThrow(
      /maxRequestBytes/,
    );
  });
});
