import { inspect } from "node:util";
import { describe, expect, it } from "vitest";
import {
  BetterStackConfig,
  ConfigError,
  REDACTED,
  RetryPolicy,
  Secret,
  VENDOR_ENV,
  normalizeBaseUrl,
} from "../src/index.js";

const INGEST = { url: "http://ingest.test:8080", sourceToken: "src-token-value" };
const QUERY = {
  queryUrl: "http://query.test:8123",
  queryUsername: "user-a",
  queryPassword: "query-password-value",
};

describe("normalizeBaseUrl", () => {
  it("adds https to bare hosts, keeps schemes, strips trailing slashes", () => {
    expect(normalizeBaseUrl("s1.eu-nbg-2.betterstackdata.com")).toBe(
      "https://s1.eu-nbg-2.betterstackdata.com",
    );
    expect(normalizeBaseUrl("host:8443/")).toBe("https://host:8443");
    expect(normalizeBaseUrl("http://better-stack-logs:8080//")).toBe(
      "http://better-stack-logs:8080",
    );
    expect(normalizeBaseUrl("  ")).toBeUndefined();
    expect(normalizeBaseUrl(undefined)).toBeUndefined();
  });
});

describe("BetterStackConfig", () => {
  it("accepts both sides, applies defaults and freezes", () => {
    const cfg = new BetterStackConfig({ ...INGEST, ...QUERY, sourceTable: "t1_app" });
    expect(cfg.url).toBe("http://ingest.test:8080");
    expect(cfg.queryUrl).toBe("http://query.test:8123");
    expect(cfg.hasIngest).toBe(true);
    expect(cfg.hasQuery).toBe(true);
    expect(cfg.connectTimeoutMs).toBe(10_000);
    expect(cfg.requestTimeoutMs).toBe(30_000);
    expect(cfg.totalTimeoutMs).toBe(120_000);
    expect(cfg.retry).toBeInstanceOf(RetryPolicy);
    expect(cfg.sourceToken).toBeInstanceOf(Secret);
    expect(Object.isFrozen(cfg)).toBe(true);
    expect(BetterStackConfig.from(cfg)).toBe(cfg);
    expect(BetterStackConfig.from(INGEST)).toBeInstanceOf(BetterStackConfig);
  });

  it("keeps optional common settings", () => {
    const hooks = {};
    const logger = { debug() {}, info() {}, warn() {}, error() {} };
    const cfg = new BetterStackConfig({
      ...INGEST,
      proxy: "http://proxy.test:3128",
      userAgentSuffix: "app/1",
      hooks,
      logger,
      headers: { "x-extra": "1" },
    });
    expect(cfg.proxy).toBe("http://proxy.test:3128");
    expect(cfg.userAgentSuffix).toBe("app/1");
    expect(cfg.hooks).toBe(hooks);
    expect(cfg.logger).toBe(logger);
    const t = cfg.transportConfig("ingest");
    expect(t).toMatchObject({
      url: INGEST.url,
      proxy: "http://proxy.test:3128",
      userAgentSuffix: "app/1",
    });
    expect(t.hooks).toBe(hooks);
    expect(t.logger).toBe(logger);
    expect(cfg.toJSON().proxy).toBe("http://proxy.test:3128/");
    expect(cfg.toJSON().userAgentSuffix).toBe("app/1");
  });

  it("a query-only or ingest-only config is valid; transportConfig of the missing side throws", () => {
    const q = new BetterStackConfig(QUERY);
    expect(q.hasIngest).toBe(false);
    expect(q.transportConfig("query").url).toBe(QUERY.queryUrl);
    expect(() => q.transportConfig("ingest")).toThrow(/ingesting host/);
    const i = new BetterStackConfig(INGEST);
    expect(i.hasQuery).toBe(false);
    expect(() => i.transportConfig("query")).toThrow(/queryUrl/);
    expect(i.basicCredentials()).toBeUndefined();
  });

  it.each([
    [{}, /set url \+ sourceToken/],
    [{ sourceToken: "x-token" }, /url \(the source's ingesting host\) is required/],
    [{ url: "http://i.test" }, /sourceToken is required/],
    [{ queryUrl: "http://q.test", queryUsername: "u" }, /must be given together/],
    [{ ...INGEST, url: "ftp://i.test" }, /url must be an http or https URL/],
    [{ ...QUERY, queryUrl: "ftp://q.test" }, /queryUrl must be an http or https URL/],
    [{ ...INGEST, sourceToken: "   " }, /sourceToken must not be blank/],
    [{ ...QUERY, queryPassword: new Secret("") }, /queryPassword must not be blank/],
    [{ ...INGEST, sourceTable: "bad-name" }, /sourceTable must match/],
    [{ ...INGEST, requestTimeoutMs: 0 }, /requestTimeoutMs must be a positive number/],
    [{ ...INGEST, proxy: "nope" }, /proxy must be an http or https URL/],
  ])("validate() rejects %j", (options, message) => {
    expect(() => new BetterStackConfig(options as never)).toThrow(ConfigError);
    expect(() => new BetterStackConfig(options as never)).toThrow(message);
  });

  it("core.redaction.config: toString/JSON/inspect hide credentials", () => {
    const cfg = new BetterStackConfig({ ...INGEST, ...QUERY, sourceTable: "t1_app" });
    const outputs = [cfg.toString(), JSON.stringify(cfg), inspect(cfg)];
    for (const text of outputs) {
      expect(text).not.toContain(INGEST.sourceToken);
      expect(text).not.toContain(QUERY.queryPassword);
      expect(text).toContain(REDACTED);
    }
    expect(cfg.toJSON()).toMatchObject({
      url: `${INGEST.url}/`,
      sourceToken: REDACTED,
      queryUrl: `${QUERY.queryUrl}/`,
      queryUsername: "user-a",
      queryPassword: REDACTED,
      sourceTable: "t1_app",
    });
    const empty = new BetterStackConfig(QUERY).toJSON();
    expect(empty).toMatchObject({ url: null, sourceToken: null, sourceTable: null });
    const noQuery = new BetterStackConfig(INGEST).toJSON();
    expect(noQuery).toMatchObject({ queryUrl: null, queryUsername: null, queryPassword: null });
    const basic = Buffer.from("user-a:query-password-value").toString("base64");
    expect(cfg.knownSecrets()).toEqual([INGEST.sourceToken, QUERY.queryPassword, basic]);
  });

  it("core.config.env-precedence: explicit > GROW_* > vendor > default", () => {
    const env = {
      GROW_BETTER_STACK_LOGS_URL: "http://grow-ingest.test",
      BETTERSTACK_INGESTING_URL: "http://vendor-ingest.test",
      BETTERSTACK_INGESTING_HOST: "vendor-host.test:443",
      BETTERSTACK_SOURCE_TOKEN: "vendor-token",
      GROW_BETTER_STACK_LOGS_SOURCE_TOKEN: "grow-token",
      BETTERSTACK_QUERY_HOST: "eu-nbg-2-connect.betterstackdata.com",
      BETTERSTACK_QUERY_USERNAME: "vendor-user",
      GROW_BETTER_STACK_LOGS_QUERY_PASSWORD: "grow-password",
      BETTERSTACK_QUERY_PASSWORD: "vendor-password",
      BETTERSTACK_SOURCE_TABLE: "t9_vendor",
      GROW_BETTER_STACK_LOGS_REQUEST_TIMEOUT: "5s",
    };
    const cfg = BetterStackConfig.fromEnv({}, env);
    expect(cfg.url).toBe("http://grow-ingest.test");
    expect(cfg.sourceToken?.reveal()).toBe("grow-token");
    expect(cfg.queryUrl).toBe("https://eu-nbg-2-connect.betterstackdata.com");
    expect(cfg.queryUsername).toBe("vendor-user");
    expect(cfg.queryPassword?.reveal()).toBe("grow-password");
    expect(cfg.sourceTable).toBe("t9_vendor");
    expect(cfg.requestTimeoutMs).toBe(5000);

    const explicit = BetterStackConfig.fromEnv(
      { url: "http://explicit.test", sourceTable: "t1_explicit", queryUsername: "me" },
      env,
    );
    expect(explicit.url).toBe("http://explicit.test");
    expect(explicit.sourceTable).toBe("t1_explicit");
    expect(explicit.queryUsername).toBe("me");

    const vendorOnly = BetterStackConfig.fromEnv(
      {},
      {
        BETTERSTACK_INGESTING_HOST: "s1.eu-nbg-2.betterstackdata.com",
        BETTERSTACK_SOURCE_TOKEN: "vendor-token",
        BETTERSTACK_QUERY_URL: "http://q.test:8123",
        GROW_BETTER_STACK_LOGS_QUERY_USERNAME: "grow-user",
        BETTERSTACK_QUERY_PASSWORD: "vendor-password",
        GROW_BETTER_STACK_LOGS_SOURCE_TABLE: "t2_grow",
      },
    );
    expect(vendorOnly.url).toBe("https://s1.eu-nbg-2.betterstackdata.com");
    expect(vendorOnly.queryUrl).toBe("http://q.test:8123");
    expect(vendorOnly.queryUsername).toBe("grow-user");
    expect(vendorOnly.sourceTable).toBe("t2_grow");

    expect(() =>
      BetterStackConfig.fromEnv({}, { ...env, GROW_BETTER_STACK_LOGS_MAX_RETRIES: "lots" }),
    ).toThrow(/GROW_BETTER_STACK_LOGS_MAX_RETRIES/);
    expect(() => BetterStackConfig.fromEnv({}, {})).toThrow(ConfigError);
  });

  it("fromEnv defaults to process.env", () => {
    const key = "GROW_BETTER_STACK_LOGS_URL";
    const tok = "GROW_BETTER_STACK_LOGS_SOURCE_TOKEN";
    process.env[key] = "http://from-process.test";
    process.env[tok] = "process-token";
    try {
      expect(BetterStackConfig.fromEnv().url).toBe("http://from-process.test");
    } finally {
      delete process.env[key];
      delete process.env[tok];
    }
  });

  it("documents the vendor variables in order", () => {
    expect(VENDOR_ENV.url).toEqual(["BETTERSTACK_INGESTING_URL", "BETTERSTACK_INGESTING_HOST"]);
    expect(VENDOR_ENV.queryUrl).toEqual(["BETTERSTACK_QUERY_URL", "BETTERSTACK_QUERY_HOST"]);
  });
});
