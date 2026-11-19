import { afterEach, describe, expect, it } from "vitest";
import {
  AuthenticationError,
  BetterStackClient,
  BetterStackLogClient,
  BetterStackLogExporter,
  BetterStackOtlpLogExporter,
  BetterStackQuery,
  ConfigError,
  PACKAGE_KEY,
  SDK_VERSION,
  UnsupportedError,
} from "../src/index.js";
import { closedPort } from "./helpers/server.js";
import { type RawServer, rawServer, replyFrom } from "./support/fixtures.js";

const servers: RawServer[] = [];
const clients: BetterStackClient[] = [];
afterEach(async () => {
  await Promise.all(clients.splice(0).map((c) => c.close()));
  await Promise.all(servers.splice(0).map((s) => s.close()));
});

const TOKEN = "src-token-value";
const PASSWORD = "query-password-value";
const fast = { retry: { maxRetries: 1, initialBackoffMs: 1 } };

async function server(...args: Parameters<typeof rawServer>): Promise<RawServer> {
  const s = await rawServer(...args);
  servers.push(s);
  return s;
}

function client(options: ConstructorParameters<typeof BetterStackClient>[0]): BetterStackClient {
  const c = new BetterStackClient({ ...fast, ...(options as object) });
  clients.push(c);
  return c;
}

const ingestOpts = (s: RawServer) => ({ url: s.url, sourceToken: TOKEN });
const queryOpts = (s: RawServer) => ({
  queryUrl: s.url,
  queryUsername: "user-a",
  queryPassword: PASSWORD,
});

describe("BetterStackClient.health", () => {
  it("uses GET /ping on the query host without credentials", async () => {
    const q = await server([replyFrom("health.query_ping")]);
    const h = await client(queryOpts(q)).health();
    expect(h).toEqual({ healthy: true, status: "ok", details: { endpoint: "query" } });
    expect(q.requests[0]?.path).toBe("/ping");
    expect(q.requests[0]?.headers.authorization).toBeUndefined();
  });

  it("treats an empty /ping body as ok", async () => {
    const q = await server([{ status: 200, body: "" }]);
    expect((await client(queryOpts(q)).health()).status).toBe("ok");
  });

  it("probes the ingest host when only ingest is configured (no data, no token)", async () => {
    const i = await server([replyFrom("health.ingest_get_root")]);
    const h = await client(ingestOpts(i)).health();
    expect(h).toEqual({
      healthy: true,
      status: "ok",
      details: { endpoint: "ingest", httpStatus: "405" },
    });
    expect(i.requests[0]?.method).toBe("GET");
    expect(i.requests[0]?.headers.authorization).toBeUndefined();
  });

  it("reports 5xx from the ingest host as unhealthy", async () => {
    const i = await server([{ status: 503, body: "down" }]);
    expect(await client(ingestOpts(i)).health()).toEqual({
      healthy: false,
      status: "unhealthy",
      details: { endpoint: "ingest", httpStatus: "503" },
    });
  });

  it("reports a failing /ping as unhealthy", async () => {
    const q = await server([{ status: 500, body: "boom" }]);
    expect(await client(queryOpts(q)).health()).toMatchObject({
      healthy: false,
      status: "unhealthy",
    });
  });

  it("core.health.unreachable: closed port → healthy=false, status=unreachable", async () => {
    const port = await closedPort();
    const h = await client({ url: `http://127.0.0.1:${port}`, sourceToken: TOKEN }).health();
    expect(h).toEqual({ healthy: false, status: "unreachable", details: { error: "UNAVAILABLE" } });
  });

  it("rethrows cancellation", async () => {
    const q = await server([replyFrom("health.query_ping")]);
    await expect(
      client(queryOpts(q)).health({ signal: AbortSignal.abort() }),
    ).rejects.toMatchObject({ code: "CANCELLED" });
  });
});

describe("BetterStackClient.ping", () => {
  it("runs SELECT count() … WHERE 1 = 0 on the configured collection with Basic auth", async () => {
    const q = await server([replyFrom("ping.fallback_count")]);
    const c = client({ ...queryOpts(q), sourceTable: "t1_grow_sdk" });
    expect(await c.ping()).toBeGreaterThanOrEqual(0);
    const req = q.requests[0];
    expect(req?.text).toBe(
      "SELECT count() AS c FROM remote(t1_grow_sdk_logs) WHERE 1 = 0 FORMAT JSONEachRow",
    );
    expect(req?.headers.authorization).toBe(
      `Basic ${Buffer.from(`user-a:${PASSWORD}`).toString("base64")}`,
    );
  });

  it("runs SELECT 1 without a collection", async () => {
    const q = await server([{ status: 200, body: '{"ok":1}\n' }]);
    await client(queryOpts(q)).ping({ signal: new AbortController().signal });
    expect(q.requests[0]?.text).toBe("SELECT 1 AS ok FORMAT JSONEachRow");
  });

  it("maps a bad SQL password to AuthenticationError without leaking it", async () => {
    const q = await server([replyFrom("query.401_bad_password")]);
    const err = await client(queryOpts(q))
      .ping()
      .catch((e: unknown) => e);
    expect(err).toBeInstanceOf(AuthenticationError);
    expect((err as Error).message).not.toContain(PASSWORD);
  });

  it("posts an empty batch with the source token when only ingest is configured", async () => {
    const i = await server([{ status: 202 }]);
    expect(await client(ingestOpts(i)).ping()).toBeGreaterThanOrEqual(0);
    const req = i.requests[0];
    expect(req?.method).toBe("POST");
    expect(req?.text).toBe("[]");
    expect(req?.headers.authorization).toBe(`Bearer ${TOKEN}`);
    expect(req?.headers["content-type"]).toBe("application/json");
  });

  it("maps ingest 403 to AuthenticationError (not PermissionDenied)", async () => {
    const i = await server([replyFrom("send.403_bad_token")]);
    const err = await client(ingestOpts(i))
      .ping({ signal: new AbortController().signal })
      .catch((e: unknown) => e);
    expect(err).toBeInstanceOf(AuthenticationError);
    expect((err as AuthenticationError).httpStatus).toBe(403);
    expect((err as Error).message).not.toContain(TOKEN);
  });
});

describe("BetterStackClient surface", () => {
  it("serverVersion is unsupported", async () => {
    const i = await server([{ status: 202 }]);
    await expect(client(ingestOpts(i)).serverVersion()).rejects.toBeInstanceOf(UnsupportedError);
  });

  it("never sends one side's credentials to the other host", async () => {
    const i = await server([{ status: 202 }]);
    const q = await server([{ status: 200, body: "" }]);
    const c = client({ ...ingestOpts(i), ...queryOpts(q), sourceTable: "t1_x" });
    await c.exporter.sendEvents([{ message: "x" }]);
    await c.query().query("SELECT 1");
    expect(i.requests[0]?.headers.authorization).toBe(`Bearer ${TOKEN}`);
    expect(q.requests[0]?.headers.authorization).toMatch(/^Basic /);
    expect(JSON.stringify(q.requests[0]?.headers)).not.toContain(TOKEN);
  });

  it("core.useragent: grow-codes-<key>/<version> (typescript)", async () => {
    const i = await server([{ status: 202 }]);
    await client({ ...ingestOpts(i), userAgentSuffix: "app/2" }).ping();
    expect(i.requests[0]?.headers["user-agent"]).toBe(
      `grow-codes-${PACKAGE_KEY}/${SDK_VERSION} (typescript) app/2`,
    );
  });

  it("exporter, otlpExporter and query() are cached accessors", async () => {
    const i = await server([{ status: 202 }]);
    const q = await server([{ status: 200 }]);
    const c = client({ ...ingestOpts(i), ...queryOpts(q) });
    expect(c.exporter).toBeInstanceOf(BetterStackLogExporter);
    expect(c.exporter).toBe(c.exporter);
    expect(c.otlpExporter).toBeInstanceOf(BetterStackOtlpLogExporter);
    expect(c.otlpExporter).toBe(c.otlpExporter);
    expect(c.query()).toBeInstanceOf(BetterStackQuery);
    expect(c.query()).toBe(c.query());
  });

  it("the missing side raises ConfigError", async () => {
    const i = await server([{ status: 202 }]);
    const q = await server([{ status: 200 }]);
    const ingestOnly = client(ingestOpts(i));
    expect(() => ingestOnly.query()).toThrow(ConfigError);
    const queryOnly = client(queryOpts(q));
    expect(() => queryOnly.exporter).toThrow(ConfigError);
    expect(() => queryOnly.otlpExporter).toThrow(ConfigError);
    expect(() => queryOnly.logClient()).toThrow(/ingesting host/);
  });

  it("logClient builds HTTP and OTLP clients and close() flushes them", async () => {
    const i = await server([{ status: 202 }]);
    const c = new BetterStackClient({ ...ingestOpts(i), ...fast });
    const http = c.logClient({ batch: { flushIntervalMs: 60_000 } });
    expect(http).toBeInstanceOf(BetterStackLogClient);
    expect(http.exporter).toBe(c.exporter);
    const ndjson = c.logClient({ exporter: { encoding: "ndjson" } });
    expect(ndjson.exporter).toMatchObject({ encoding: "ndjson" });
    const otlp = c.logClient({ protocol: "otlp" });
    expect(otlp.exporter).toBe(c.otlpExporter);
    const otlpJson = c.logClient({ protocol: "otlp", otlp: { protocol: "json" } });
    expect(otlpJson.exporter).toMatchObject({ protocol: "json" });
    expect(() => c.logClient({ protocol: "grpc" as never })).toThrow(ConfigError);
    http.logger().info("flushed on close", { n: 1 });
    await c.close();
    await c.close();
    expect(i.requests).toHaveLength(1);
    expect(JSON.parse(i.requests[0]?.text ?? "[]")[0].message).toBe("flushed on close");
    expect(() => c.logClient()).toThrow(/closed/);
  });

  it("core.close.idempotent: close twice is safe and later calls fail with CONFIG", async () => {
    const q = await server([replyFrom("health.query_ping")]);
    const c = new BetterStackClient(queryOpts(q));
    await c.health();
    await c.close();
    await c.close();
    await expect(c.query().query("SELECT 1")).rejects.toMatchObject({ code: "CONFIG" });
  });

  it("an unreadable TLS file raises ConfigError", () => {
    expect(
      () =>
        new BetterStackClient({
          url: "https://i.test",
          sourceToken: TOKEN,
          tls: { caCertPath: "/nonexistent/ca.pem" },
        }),
    ).toThrow(/TLS file/);
  });

  it("fromEnv builds from GROW_* and vendor variables", async () => {
    const i = await server([{ status: 202 }]);
    const vars = {
      GROW_BETTER_STACK_LOGS_URL: i.url,
      BETTERSTACK_SOURCE_TOKEN: TOKEN,
    };
    Object.assign(process.env, vars);
    try {
      const c = BetterStackClient.fromEnv({ retry: { maxRetries: 0 } });
      clients.push(c);
      expect(c.config.url).toBe(i.url);
      expect(await c.ping()).toBeGreaterThanOrEqual(0);
    } finally {
      for (const k of Object.keys(vars)) Reflect.deleteProperty(process.env, k);
    }
  });
});
