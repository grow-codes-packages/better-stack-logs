import { afterEach, describe, expect, it } from "vitest";
import {
  AuthenticationError,
  BetterStackClient,
  BetterStackQuery,
  ConfigError,
  HttpTransport,
  NotFoundError,
  PermissionDeniedError,
  RateLimitError,
  Severity,
  TimeoutError,
  UnavailableError,
  UnsupportedError,
  ValidationError,
  formatDateTime64,
  escapeParamValue,
  jsonEscape,
  logRecordFromRow,
  parseDateTime,
  parseRows,
  sqlString,
} from "../src/index.js";
import { type RawServer, type Reply, rawServer, replyFrom } from "./support/fixtures.js";

const servers: RawServer[] = [];
const clients: BetterStackClient[] = [];
afterEach(async () => {
  await Promise.all(clients.splice(0).map((c) => c.close()));
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

const PASSWORD = "query-password-value";
const NOW = new Date("2026-11-18T21:00:00.000Z");

async function setup(
  replies: Parameters<typeof rawServer>[0] = [{ status: 200, body: "" }],
  extra: Record<string, unknown> = { sourceTable: "t1_grow_sdk" },
) {
  const s = await rawServer(replies);
  servers.push(s);
  const c = new BetterStackClient({
    queryUrl: s.url,
    queryUsername: "user-a",
    queryPassword: PASSWORD,
    retry: { maxRetries: 2, initialBackoffMs: 1 },
    ...extra,
  });
  clients.push(c);
  const transport = new HttpTransport({ config: c.config.transportConfig("query") });
  const q = new BetterStackQuery(transport, c.config, () => NOW);
  return { s, c, q, transport };
}

describe("helpers", () => {
  it("sqlString escapes backslashes, quotes and control characters", () => {
    expect(sqlString(`it's \\ a\n\r\t\0`)).toBe(`'it\\'s \\\\ a\\n\\r\\t\\0'`);
  });

  it("jsonEscape matches raw event JSON", () => {
    expect(jsonEscape('say "hi" \\ ✓')).toBe('say \\"hi\\" \\\\ ✓');
  });

  it("formatDateTime64 and parseDateTime", () => {
    expect(formatDateTime64(new Date("2026-11-18T21:00:00.123Z"))).toBe(
      "2026-11-18 21:00:00.123000",
    );
    expect(() => formatDateTime64(new Date(Number.NaN))).toThrow(ValidationError);
    expect(parseDateTime("2023-08-09 07:03:30.123456")?.toISOString()).toBe(
      "2023-08-09T07:03:30.123Z",
    );
    expect(parseDateTime("2023-08-09 07:03:30")?.toISOString()).toBe("2023-08-09T07:03:30.000Z");
    expect(parseDateTime("2023-08-09T07:03:30.1+02:00")?.toISOString()).toBe(
      "2023-08-09T05:03:30.100Z",
    );
    expect(parseDateTime(1_700_000_000)?.toISOString()).toBe("2023-11-14T22:13:20.000Z");
    expect(parseDateTime(1_700_000_000_123)?.toISOString()).toBe("2023-11-14T22:13:20.123Z");
    expect(parseDateTime(NOW)).toBe(NOW);
    expect(parseDateTime("nope")).toBeUndefined();
    expect(parseDateTime("2023-99-99 99:99:99")).toBeUndefined();
    expect(parseDateTime(null)).toBeUndefined();
  });

  it("parseRows handles JSONEachRow, arrays, FORMAT JSON and empty bodies", () => {
    expect(parseRows("")).toEqual([]);
    expect(parseRows('{"a":1}\n\n{"a":2}\n')).toEqual([{ a: 1 }, { a: 2 }]);
    expect(parseRows(String(replyFrom("query.array_of_rows").body)).length).toBeGreaterThan(0);
    expect(parseRows(String(replyFrom("query.json_format").body))).toEqual([{ c: "11" }]);
    expect(parseRows('{"meta":[],"data":[{"x":1}],"rows":1}')).toEqual([{ x: 1 }]);
  });

  it("parseRows raises errors appended after a 200", () => {
    expect(() =>
      parseRows(
        '{"a":1}\nCode: 241. DB::Exception: Memory limit exceeded. (MEMORY_LIMIT_EXCEEDED)\n',
      ),
    ).toThrow(ValidationError);
    expect(() =>
      parseRows('[{"a":1},\nCode: 202. DB::Exception: Too many simultaneous queries.'),
    ).toThrow(RateLimitError);
    expect(() => parseRows('{"a":1}\nCode: 999. DB::Exception: odd')).toThrow(UnavailableError);
    expect(() => parseRows("[not json")).toThrow(/invalid JSON array/);
    expect(() => parseRows('{"a":1}\nnot json')).toThrow(/invalid JSON line/);
    expect(() => parseRows("garbage")).toThrow(/invalid JSON line/);
  });

  it("logRecordFromRow normalizes events", () => {
    const r = logRecordFromRow({
      dt: "2023-08-09 07:03:30.000000",
      raw: JSON.stringify({
        message: "hello",
        level: "warning",
        span: { trace_id: "0AF7651916CD43DD8448EB211C80319C", span_id: "b7ad6b7169203331" },
        trace_flags: 1,
        resource: { "service.name": "svc" },
        attributes: { a: 1 },
        observed_dt: "2023-08-09T07:03:31Z",
        extra: { nested: true },
        nothing: null,
      }),
    });
    expect(r).toEqual({
      timestamp: new Date("2023-08-09T07:03:30Z"),
      observedTimestamp: new Date("2023-08-09T07:03:31Z"),
      severity: Severity.Warn,
      severityText: "WARN",
      body: "hello",
      attributes: { extra: { nested: true }, a: 1 },
      resource: { "service.name": "svc" },
      traceId: "0af7651916cd43dd8448eb211c80319c",
      spanId: "b7ad6b7169203331",
      traceFlags: 1,
    });
  });

  it("logRecordFromRow falls back for msg, severity numbers, OTLP fields and bad JSON", () => {
    const otlp = logRecordFromRow({
      dt: "2023-08-09 07:03:30.000000",
      raw: JSON.stringify({
        msg: { structured: 1 },
        severity_number: 17,
        level: "custom",
        trace_id: "0af7651916cd43dd8448eb211c80319c",
        span_id: "zz",
      }),
    });
    expect(otlp.body).toBe('{"structured":1}');
    expect(otlp.severity).toBe(Severity.Error);
    expect(otlp.severityText).toBe("custom");
    expect(otlp.traceId).toBe("0af7651916cd43dd8448eb211c80319c");
    expect(otlp.spanId).toBeUndefined();
    const plain = logRecordFromRow({ dt: "bad", raw: "not json at all" });
    expect(plain.body).toBe("not json at all");
    expect(plain.severity).toBe(Severity.Info);
    expect(plain.severityText).toBe("INFO");
    expect(plain.timestamp.getTime()).toBe(0);
    const sev = logRecordFromRow({
      dt: "x",
      raw: JSON.stringify({ dt: 1_700_000_000, severity: "error", level: "" }),
    });
    expect(sev.timestamp.toISOString()).toBe("2023-11-14T22:13:20.000Z");
    expect(sev.severity).toBe(Severity.Error);
    expect(sev.body).toBe(JSON.stringify({ dt: 1_700_000_000, severity: "error", level: "" }));
    const obj = logRecordFromRow({ dt: "x", raw: { message: "m", trace_id: "0".repeat(32) } });
    expect(obj.body).toBe("m");
    expect(obj.traceId).toBeUndefined();
    const emptyLevel = logRecordFromRow({
      dt: "x",
      raw: JSON.stringify({ message: "m", level: "" }),
    });
    expect(emptyLevel.severityText).toBe("INFO");
  });
});

describe("BetterStackQuery.query", () => {
  it("posts SQL with Basic auth, appends FORMAT JSONEachRow and binds parameters", async () => {
    const { s, q, transport } = await setup([replyFrom("query.json_each_row")]);
    const rows = await q.query(
      "SELECT dt, raw FROM remote(t1_grow_sdk_logs) WHERE position(raw, {needle:String}) > 0;",
      {
        needle: "x",
        n: 5,
        big: 2n ** 60n,
        ok: true,
        no: false,
        since: new Date("2026-01-01T00:00:00Z"),
        nothing: null,
      },
      { settings: { max_execution_time: 30, readonly: true } },
    );
    expect(rows.length).toBe(11);
    const req = s.requests[0];
    expect(req?.method).toBe("POST");
    expect(req?.text).toBe(
      "SELECT dt, raw FROM remote(t1_grow_sdk_logs) WHERE position(raw, {needle:String}) > 0 FORMAT JSONEachRow",
    );
    expect(req?.headers["content-type"]).toBe("text/plain; charset=utf-8");
    expect(req?.headers.authorization).toBe(
      `Basic ${Buffer.from(`user-a:${PASSWORD}`).toString("base64")}`,
    );
    expect(Object.fromEntries(req?.query ?? [])).toEqual({
      max_execution_time: "30",
      readonly: "true",
      param_needle: "x",
      param_n: "5",
      param_big: String(2n ** 60n),
      param_ok: "true",
      param_no: "false",
      param_since: "2026-01-01 00:00:00.000000",
      param_nothing: "\\N",
    });
    await transport.close();
  });

  it("escapes String parameter values in the ClickHouse escaped (TSV) format (DECISIONS 12)", async () => {
    expect(escapeParamValue("plain 'quoted' \"text\"")).toBe("plain 'quoted' \"text\"");
    expect(escapeParamValue("a\\b\tc\nd\re")).toBe("a\\\\b\\tc\\nd\\re");
    expect(escapeParamValue("\\N")).toBe("\\\\N");
    const { s, q } = await setup([{ status: 200, body: "" }]);
    await q.query("SELECT {v:String} AS v", { v: `C:\\tmp\\it's "x"\n` });
    expect(Object.fromEntries(s.requests[0]?.query ?? [])).toEqual({
      param_v: `C:\\\\tmp\\\\it's "x"\\n`,
    });
  });

  it("keeps a JSON FORMAT clause and rejects other formats", async () => {
    const { s, q } = await setup([replyFrom("query.json_format")]);
    expect(await q.query("SELECT count() AS c FROM remote(t1_grow_sdk_logs) FORMAT JSON")).toEqual([
      { c: "11" },
    ]);
    expect(s.requests[0]?.text).toBe(
      "SELECT count() AS c FROM remote(t1_grow_sdk_logs) FORMAT JSON",
    );
    await expect(q.query("SELECT 1 FORMAT CSV")).rejects.toThrow(/queryRaw/);
  });

  it("queryRaw returns the body text and validates input", async () => {
    const { q } = await setup([{ status: 200, body: "1\n" }]);
    expect(
      await q.queryRaw("SELECT 1 FORMAT TSV", {}, { signal: new AbortController().signal }),
    ).toBe("1\n");
    await expect(q.queryRaw("  ")).rejects.toThrow(ValidationError);
    await expect(q.queryRaw("SELECT {x:String}", { "bad-name": "x" })).rejects.toThrow(
      /invalid parameter name/,
    );
    await expect(q.queryRaw("SELECT {x:Float64}", { x: Number.NaN })).rejects.toThrow(/finite/);
  });

  it.each([
    ["query.401_bad_password", AuthenticationError],
    ["query.401_no_auth", AuthenticationError],
    ["query.404_unknown_table", NotFoundError],
    ["query.404_unknown_column", ValidationError],
    ["query.400_syntax", ValidationError],
  ] as const)("maps %s", async (name, cls) => {
    const { q } = await setup([replyFrom(name)]);
    const err = await q.query("SELECT 1").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(cls);
    expect((err as Error).message).toContain("DB::Exception");
    expect((err as Error).message).not.toContain(PASSWORD);
  });

  it.each([
    [497, PermissionDeniedError],
    [48, UnsupportedError],
    [159, TimeoutError],
    [241, ValidationError],
    [81, NotFoundError],
  ] as const)("maps ClickHouse code %i", async (code, cls) => {
    const { q } = await setup([
      { status: 400, body: `Code: ${code}. DB::Exception: synthetic. (X) (version 24)` },
    ]);
    await expect(q.query("SELECT 1")).rejects.toBeInstanceOf(cls);
  });

  it("retries 503 (queries are idempotent) and keeps unmapped codes", async () => {
    const { s, q } = await setup([
      replyFrom("query.503_fault"),
      { status: 200, body: '{"one":"1"}\n' },
    ]);
    expect(await q.query("SELECT 1 AS one")).toEqual([{ one: "1" }]);
    expect(s.requests).toHaveLength(2);
    const other = await setup([
      { status: 400, body: "Code: 1000. DB::Exception: something else. (X)" },
    ]);
    await expect(other.q.query("SELECT 1")).rejects.toBeInstanceOf(ValidationError);
  });
});

describe("BetterStackQuery.searchLogs", () => {
  const rowsBody = (
    n: number,
    raw: (i: number) => string = (i) => `{"message":"m ${i}"}`,
  ): Reply => ({
    status: 200,
    body: Array.from(
      { length: n },
      (_, i) => `${JSON.stringify({ dt: `2026-11-18 20:00:0${i}.000000`, raw: raw(i) })}\n`,
    ).join(""),
  });

  it("renders the UNION ALL SQL with escaped values", async () => {
    const { q } = await setup();
    expect(
      q.renderSearchSql({
        source: "t1_app",
        contains: `it's "x"`,
        start: new Date("2026-11-18T00:00:00Z"),
        end: new Date("2026-11-18T01:00:00Z"),
        limit: 2,
        order: "asc",
      }),
    ).toBe(
      "SELECT dt, raw FROM (SELECT dt, raw FROM remote(t1_app_logs) UNION ALL SELECT dt, raw FROM s3Cluster(primary, t1_app_s3) WHERE _row_type = 1) " +
        "WHERE dt BETWEEN toDateTime64('2026-11-18 00:00:00.000000', 6, 'UTC') AND toDateTime64('2026-11-18 01:00:00.000000', 6, 'UTC') " +
        `AND position(raw, 'it\\'s \\\\"x\\\\"') > 0 ORDER BY dt ASC LIMIT 3 FORMAT JSONEachRow`,
    );
    // defaults: configured table, last 24 h, no upper bound, newest first, 100 rows
    expect(q.renderSearchSql()).toBe(
      "SELECT dt, raw FROM (SELECT dt, raw FROM remote(t1_grow_sdk_logs) UNION ALL SELECT dt, raw FROM s3Cluster(primary, t1_grow_sdk_s3) WHERE _row_type = 1) " +
        "WHERE dt >= toDateTime64('2026-11-17 21:00:00.000000', 6, 'UTC') ORDER BY dt DESC LIMIT 101 FORMAT JSONEachRow",
    );
    expect(q.renderSearchSql({ end: NOW, contains: "" })).toContain(
      "BETWEEN toDateTime64('2026-11-17 21:00:00.000000', 6, 'UTC') AND toDateTime64('2026-11-18 21:00:00.000000', 6, 'UTC') ORDER BY",
    );
  });

  it.each([
    [{ source: "bad-name" }, ValidationError, /source must match/],
    [{ limit: 0 }, ValidationError, /limit/],
    [{ limit: 10_001 }, ValidationError, /limit/],
    [{ order: "up" }, ValidationError, /order/],
    [{ start: new Date(2), end: new Date(1) }, ValidationError, /start must not be after end/],
    [{ cursor: "%%%" }, ValidationError, /invalid cursor/],
    [{ cursor: Buffer.from('{"o":-1}').toString("base64url") }, ValidationError, /invalid cursor/],
  ] as const)("validates %j", async (opts, cls, message) => {
    const { q } = await setup();
    expect(() => q.renderSearchSql(opts as never)).toThrow(cls);
    expect(() => q.renderSearchSql(opts as never)).toThrow(message);
  });

  it("needs a source or a configured sourceTable", async () => {
    const { q } = await setup(undefined, {});
    expect(() => q.renderSearchSql()).toThrow(ConfigError);
    expect(await q.listStreams()).toEqual([]);
  });

  it("returns normalized records, deduplicates and pages with a cursor", async () => {
    const dup = `{"message":"dup"}`;
    const { s, q } = await setup([
      {
        status: 200,
        body:
          `${JSON.stringify({ dt: "2026-11-18 20:00:00.000000", raw: dup })}\n` +
          `${JSON.stringify({ dt: "2026-11-18 20:00:00.000000", raw: dup })}\n` +
          `${JSON.stringify({ dt: "2026-11-18 19:00:00.000000", raw: '{"message":"older"}' })}\n`,
      },
      rowsBody(1),
    ]);
    const page1 = await q.searchLogs({ contains: "x", limit: 2 });
    expect(page1.records.map((r) => r.body)).toEqual(["dup"]);
    expect(page1.nextCursor).toBeDefined();
    const page2 = await q.searchLogs({
      contains: "x",
      limit: 2,
      cursor: page1.nextCursor as string,
      signal: new AbortController().signal,
    });
    expect(page2.records.map((r) => r.body)).toEqual(["m 0"]);
    expect(page2.nextCursor).toBeUndefined();
    expect(s.requests[1]?.text).toContain("LIMIT 3 OFFSET 2");
  });

  it("search() maps LogQueryClient options onto searchLogs", async () => {
    const { s, q } = await setup([rowsBody(2)]);
    const res = await q.search("needle", {
      start: new Date("2026-11-18T00:00:00Z"),
      end: new Date("2026-11-18T23:00:00Z"),
      limit: 5,
      order: "asc",
      cursor: Buffer.from('{"o":5}').toString("base64url"),
      signal: new AbortController().signal,
    });
    expect(res.records).toHaveLength(2);
    expect(s.requests[0]?.text).toContain(
      "position(raw, 'needle') > 0 ORDER BY dt ASC LIMIT 6 OFFSET 5",
    );
    expect(replyFrom("searchLogs.contains").status).toBe(200);
  });

  it("waitForLog polls search; listStreams/listLabels; tail polls", async () => {
    const { q } = await setup([
      { status: 200, body: "" },
      rowsBody(1, () => '{"message":"found it"}'),
    ]);
    const r = await q.waitForLog("found", { timeoutMs: 5000, intervalMs: 10 });
    expect(r.body).toBe("found it");
    expect(await q.listStreams()).toEqual(["t1_grow_sdk"]);
    await expect(q.listLabels()).rejects.toBeInstanceOf(UnsupportedError);
    const seen: string[] = [];
    const handle = q.tail("found", (rec) => seen.push(rec.body), {
      start: new Date("2026-11-18T00:00:00Z"),
      intervalMs: 10,
    });
    await new Promise((resolve) => setTimeout(resolve, 200));
    handle.stop();
    expect(seen).toContain("found it");
  });

  it("uses the real clock by default", async () => {
    const { c } = await setup();
    expect(c.query().renderSearchSql()).toMatch(/dt >= toDateTime64\('\d{4}-/);
  });
});
