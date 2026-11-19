import { describe, expect, it } from "vitest";
import {
  CONTENT_TYPES,
  Severity,
  ValidationError,
  encodeEvents,
  encodeMsgpack,
  encodedEventSize,
  eventJson,
  toBetterStackEvent,
  type LogRecord,
} from "../src/index.js";
import { decodeMsgpack, fixture } from "./support/fixtures.js";

const T = new Date("2026-11-18T21:00:00.123Z");

function rec(extra: Partial<LogRecord> = {}): LogRecord {
  return {
    timestamp: T,
    severity: Severity.Info,
    severityText: "INFO",
    body: "hello",
    attributes: {},
    resource: {},
    ...extra,
  };
}

describe("toBetterStackEvent", () => {
  it("maps a minimal record", () => {
    expect(toBetterStackEvent(rec())).toEqual({
      dt: T,
      message: "hello",
      level: "info",
      severity_number: 9,
    });
  });

  it("maps trace correlation, resource, attributes and observed time", () => {
    const observed = new Date("2026-11-18T21:00:01Z");
    const event = toBetterStackEvent(
      rec({
        severity: Severity.Error,
        severityText: "ERROR",
        traceId: "0af7651916cd43dd8448eb211c80319c",
        spanId: "b7ad6b7169203331",
        traceFlags: 1,
        attributes: { user: { id: 7 } },
        resource: { "service.name": "svc" },
        observedTimestamp: observed,
      }),
    );
    expect(event).toEqual({
      dt: T,
      message: "hello",
      level: "error",
      severity_number: 17,
      span: { trace_id: "0af7651916cd43dd8448eb211c80319c", span_id: "b7ad6b7169203331" },
      trace_flags: 1,
      resource: { "service.name": "svc" },
      attributes: { user: { id: 7 } },
      observed_dt: observed,
    });
    // the recorded fixture uses the same correlation field names
    const sent = fixture("send.json_single").request.body as Record<string, unknown>;
    expect(Object.keys(sent.span as object)).toEqual(["trace_id", "span_id"]);
  });

  it("omits span_id and trace_flags when absent", () => {
    expect(toBetterStackEvent(rec({ traceId: "0af7651916cd43dd8448eb211c80319c" })).span).toEqual({
      trace_id: "0af7651916cd43dd8448eb211c80319c",
    });
  });
});

describe("encodeEvents", () => {
  const events = [{ message: "a", dt: T }, { message: "b" }];

  it("json: one array, Date → RFC 3339", () => {
    const text = new TextDecoder().decode(encodeEvents(events, "json"));
    expect(text).toBe('[{"message":"a","dt":"2026-11-18T21:00:00.123Z"},{"message":"b"}]');
    expect(eventJson(events[1] ?? {})).toBe('{"message":"b"}');
  });

  it("ndjson: one object per line", () => {
    const text = new TextDecoder().decode(encodeEvents(events, "ndjson"));
    expect(text).toBe('{"message":"a","dt":"2026-11-18T21:00:00.123Z"}\n{"message":"b"}\n');
  });

  it("msgpack: array with the timestamp extension", () => {
    expect(decodeMsgpack(encodeEvents(events, "msgpack"))).toEqual([
      { message: "a", dt: T },
      { message: "b" },
    ]);
  });

  it("rejects unknown encodings and reports content types", () => {
    expect(() => encodeEvents(events, "xml" as never)).toThrow(ValidationError);
    expect(CONTENT_TYPES).toEqual({
      json: "application/json",
      ndjson: "application/x-ndjson",
      msgpack: "application/msgpack",
    });
  });

  it("encodedEventSize counts separators", () => {
    expect(encodedEventSize({ message: "b" }, "json")).toBe('{"message":"b"}'.length + 1);
    expect(encodedEventSize({ message: "b" }, "msgpack")).toBe(
      encodeMsgpack({ message: "b" }).length,
    );
  });
});

describe("encodeMsgpack", () => {
  it.each([
    [null, [0xc0]],
    [undefined, [0xc0]],
    [true, [0xc3]],
    [false, [0xc2]],
    [0, [0x00]],
    [127, [0x7f]],
    [128, [0xcc, 0x80]],
    [256, [0xcd, 0x01, 0x00]],
    [65536, [0xce, 0x00, 0x01, 0x00, 0x00]],
    [2 ** 32, [0xcf, 0, 0, 0, 1, 0, 0, 0, 0]],
    [-1, [0xff]],
    [-32, [0xe0]],
    [-33, [0xd0, 0xdf]],
    [-129, [0xd1, 0xff, 0x7f]],
    [-32769, [0xd2, 0xff, 0xff, 0x7f, 0xff]],
    [-(2 ** 31) - 1, [0xd3, 0xff, 0xff, 0xff, 0xff, 0x7f, 0xff, 0xff, 0xff]],
    [1.5, [0xcb, 0x3f, 0xf8, 0, 0, 0, 0, 0, 0]],
    [2n ** 63n, [0xcf, 0x80, 0, 0, 0, 0, 0, 0, 0]],
    ["a", [0xa1, 0x61]],
    [new Uint8Array([1, 2]), [0xc4, 0x02, 0x01, 0x02]],
    [
      [1, 2],
      [0x92, 0x01, 0x02],
    ],
    [{ a: 1, skip: undefined }, [0x81, 0xa1, 0x61, 0x01]],
    [new Date(1000), [0xd6, 0xff, 0, 0, 0, 1]],
  ])("encodes %p", (value, bytes) => {
    expect([...encodeMsgpack(value)]).toEqual(bytes);
  });

  it("uses 8- and 16/32-bit headers for long strings, arrays, maps and bins", () => {
    const s32 = "x".repeat(40);
    expect([...encodeMsgpack(s32).subarray(0, 2)]).toEqual([0xd9, 40]);
    expect([...encodeMsgpack("x".repeat(300)).subarray(0, 3)]).toEqual([0xda, 0x01, 0x2c]);
    expect([...encodeMsgpack("x".repeat(70_000)).subarray(0, 5)]).toEqual([
      0xdb, 0x00, 0x01, 0x11, 0x70,
    ]);
    const arr = Array.from({ length: 16 }, () => 0);
    expect([...encodeMsgpack(arr).subarray(0, 3)]).toEqual([0xdc, 0x00, 0x10]);
    expect(decodeMsgpack(encodeMsgpack(Array.from({ length: 70_000 }, () => 1)))).toHaveLength(
      70_000,
    );
    const map = Object.fromEntries(Array.from({ length: 16 }, (_, i) => [`k${i}`, i]));
    expect([...encodeMsgpack(map).subarray(0, 3)]).toEqual([0xde, 0x00, 0x10]);
    const big = Object.fromEntries(Array.from({ length: 70_000 }, (_, i) => [`k${i}`, 1]));
    expect(Object.keys(decodeMsgpack(encodeMsgpack(big)) as object)).toHaveLength(70_000);
    expect([...encodeMsgpack(new Uint8Array(300)).subarray(0, 3)]).toEqual([0xc5, 0x01, 0x2c]);
    expect([...encodeMsgpack(new Uint8Array(70_000)).subarray(0, 5)]).toEqual([
      0xc6, 0x00, 0x01, 0x11, 0x70,
    ]);
  });

  it("encodes timestamps as 32-, 64- and 96-bit extensions", () => {
    const ms = new Date("2026-11-18T21:00:00.123Z");
    expect([...encodeMsgpack(ms).subarray(0, 2)]).toEqual([0xd7, 0xff]);
    expect(decodeMsgpack(encodeMsgpack(ms))).toEqual(ms);
    const before1970 = new Date(-1500);
    expect([...encodeMsgpack(before1970).subarray(0, 3)]).toEqual([0xc7, 12, 0xff]);
    expect(decodeMsgpack(encodeMsgpack(before1970))).toEqual(before1970);
    expect(() => encodeMsgpack(new Date(Number.NaN))).toThrow(ValidationError);
  });

  it("drops functions/symbols and rejects out-of-range or too deep values", () => {
    expect([...encodeMsgpack({ f: () => 1, s: Symbol("x") })]).toEqual([0x80]);
    expect([...encodeMsgpack([() => 1])]).toEqual([0x91, 0xc0]);
    expect(() => encodeMsgpack(2n ** 64n)).toThrow(ValidationError);
    expect(() => encodeMsgpack(-(2n ** 63n) - 1n)).toThrow(ValidationError);
    let deep: unknown = 1;
    for (let i = 0; i < 120; i++) deep = [deep];
    expect(() => encodeMsgpack(deep)).toThrow(/nested too deeply/);
  });
});
