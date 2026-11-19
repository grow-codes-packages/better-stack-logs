// Service-owned: generated once by sdk-kit, then maintained in this repository.
// Better Stack Logs has two hosts with separate credentials (API.md §1/§2, DECISIONS 1 and 4):
//   ingest (source's ingesting host, Bearer source token) and SQL query (ClickHouse HTTP, Basic).

import {
  type CommonConfig,
  type CommonConfigOptions,
  type CommonVendorEnv,
  ConfigError,
  type EnvMap,
  EnvReader,
  type Hooks,
  type ClientLogger,
  REDACTED,
  type RetryPolicy,
  Secret,
  type TlsOptions,
  describeCommonConfig,
  mergeOptions,
  readCommonEnv,
  redactUrl,
  resolveCommonConfig,
  secretValues,
  validateCommonConfig,
} from "./core/index.js";
import type { IngestEncoding } from "./encoding.js";

const INSPECT = Symbol.for("nodejs.util.inspect.custom");

/** Ingest body encodings, in documentation order (`json` is the default). */
export const INGEST_ENCODINGS: readonly IngestEncoding[] = Object.freeze([
  "json",
  "ndjson",
  "msgpack",
]);

/** Collection (table) names are identifiers: they cannot be bound as SQL parameters. */
export const IDENTIFIER_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** Options accepted by {@link BetterStackConfig} (all optional, but one side must be complete). */
export interface BetterStackConfigOptions extends CommonConfigOptions {
  /**
   * Ingest base URL: the source's ingesting host (`s1234.eu-nbg-2.betterstackdata.com`). A value
   * without `://` gets `https://`. Required for sending logs; there is no default host. Env
   * `GROW_BETTER_STACK_LOGS_URL`, `BETTERSTACK_INGESTING_URL`, `BETTERSTACK_INGESTING_HOST`.
   */
  url?: string;
  /**
   * Source token sent as `Authorization: Bearer <token>` to the ingest host only. Env
   * `GROW_BETTER_STACK_LOGS_SOURCE_TOKEN`, `BETTERSTACK_SOURCE_TOKEN`.
   */
  sourceToken?: string | Secret;
  /**
   * SQL query base URL (ClickHouse HTTP interface, e.g. `eu-nbg-2-connect.betterstackdata.com`).
   * Env `GROW_BETTER_STACK_LOGS_QUERY_URL`, `BETTERSTACK_QUERY_URL`, `BETTERSTACK_QUERY_HOST`.
   */
  queryUrl?: string;
  /** Username of the SQL connection. Env `…_QUERY_USERNAME`, `BETTERSTACK_QUERY_USERNAME`. */
  queryUsername?: string;
  /** Password of the SQL connection (HTTP Basic). Env `…_QUERY_PASSWORD`, `BETTERSTACK_QUERY_PASSWORD`. */
  queryPassword?: string | Secret;
  /**
   * Default collection (`t1234_myapp`) used by `search`, `searchLogs` and `ping`. Env
   * `GROW_BETTER_STACK_LOGS_SOURCE_TABLE`, `BETTERSTACK_SOURCE_TABLE`.
   */
  sourceTable?: string;
  /**
   * Default ingest body encoding of the exporters: `"json"` (default), `"ndjson"` or `"msgpack"`;
   * each exporter can override it. Env `GROW_BETTER_STACK_LOGS_ENCODING`.
   */
  encoding?: IngestEncoding;
}

/** Vendor environment variables read after `GROW_BETTER_STACK_LOGS_*`, in order (API.md §2). */
export const VENDOR_ENV: CommonVendorEnv & {
  sourceToken: readonly string[];
  queryUrl: readonly string[];
  queryUsername: readonly string[];
  queryPassword: readonly string[];
  sourceTable: readonly string[];
} = {
  url: ["BETTERSTACK_INGESTING_URL", "BETTERSTACK_INGESTING_HOST"],
  sourceToken: ["BETTERSTACK_SOURCE_TOKEN"],
  queryUrl: ["BETTERSTACK_QUERY_URL", "BETTERSTACK_QUERY_HOST"],
  queryUsername: ["BETTERSTACK_QUERY_USERNAME"],
  queryPassword: ["BETTERSTACK_QUERY_PASSWORD"],
  sourceTable: ["BETTERSTACK_SOURCE_TABLE"],
};

/**
 * Normalizes a host or URL: a value containing `://` is used as given, a bare `host[:port]` becomes
 * `https://host[:port]`; trailing slashes are removed. Blank input gives `undefined`.
 */
export function normalizeBaseUrl(value: string | undefined): string | undefined {
  if (value === undefined) return undefined;
  const trimmed = value.trim();
  if (trimmed === "") return undefined;
  const withScheme = trimmed.includes("://") ? trimmed : `https://${trimmed}`;
  return withScheme.replace(/\/+$/, "");
}

function isHttpUrl(value: string): boolean {
  try {
    const u = new URL(value);
    return u.protocol === "http:" || u.protocol === "https:";
  } catch {
    return false;
  }
}

/** Immutable configuration of {@link BetterStackClient}. */
export class BetterStackConfig {
  /** Prefix of every environment variable. */
  static readonly ENV_PREFIX = "GROW_BETTER_STACK_LOGS_";

  /** Ingest base URL (normalized), or `undefined` for a query-only client. */
  readonly url?: string;
  readonly connectTimeoutMs: number;
  readonly requestTimeoutMs: number;
  readonly totalTimeoutMs: number;
  readonly retry: RetryPolicy;
  readonly tls: Readonly<TlsOptions>;
  readonly proxy?: string;
  readonly headers: Readonly<Record<string, string>>;
  readonly userAgentSuffix?: string;
  readonly hooks?: Hooks;
  readonly logger?: ClientLogger;
  /** Source token for the ingest host. */
  readonly sourceToken?: Secret;
  /** SQL query base URL (normalized). */
  readonly queryUrl?: string;
  /** SQL connection username. */
  readonly queryUsername?: string;
  /** SQL connection password. */
  readonly queryPassword?: Secret;
  /** Default collection for queries. */
  readonly sourceTable?: string;
  /** Default ingest body encoding (`"json"` unless configured). */
  readonly encoding: IngestEncoding;

  /** Applies defaults, validates (throws {@link ConfigError}) and freezes. */
  constructor(options: BetterStackConfigOptions = {}) {
    const url = normalizeBaseUrl(options.url);
    const queryUrl = normalizeBaseUrl(options.queryUrl);
    const common = resolveCommonConfig(options, url ?? queryUrl ?? "");
    if (url !== undefined) this.url = url;
    this.connectTimeoutMs = common.connectTimeoutMs;
    this.requestTimeoutMs = common.requestTimeoutMs;
    this.totalTimeoutMs = common.totalTimeoutMs;
    this.retry = common.retry;
    this.tls = common.tls;
    this.headers = common.headers;
    if (common.proxy !== undefined) this.proxy = common.proxy;
    if (common.userAgentSuffix !== undefined) this.userAgentSuffix = common.userAgentSuffix;
    if (common.hooks !== undefined) this.hooks = common.hooks;
    if (common.logger !== undefined) this.logger = common.logger;
    const sourceToken = Secret.from(options.sourceToken);
    if (sourceToken !== undefined) this.sourceToken = sourceToken;
    if (queryUrl !== undefined) this.queryUrl = queryUrl;
    const username = options.queryUsername;
    if (username !== undefined && username !== "") this.queryUsername = username;
    const password = Secret.from(options.queryPassword);
    if (password !== undefined) this.queryPassword = password;
    if (options.sourceTable !== undefined && options.sourceTable !== "")
      this.sourceTable = options.sourceTable;
    const encoding = (options.encoding as string | undefined)?.trim().toLowerCase();
    this.encoding = (
      encoding === undefined || encoding === "" ? "json" : encoding
    ) as IngestEncoding;
    this.validate();
    Object.freeze(this);
  }

  /** Returns `options` when it already is a config, otherwise builds one. */
  static from(options: BetterStackConfigOptions = {}): BetterStackConfig {
    if (options instanceof BetterStackConfig) return options;
    return new BetterStackConfig(options);
  }

  /**
   * Builds a config from the environment. Precedence: explicit `overrides` >
   * `GROW_BETTER_STACK_LOGS_<NAME>` > vendor variables ({@link VENDOR_ENV}) > defaults.
   */
  static fromEnv(
    overrides: BetterStackConfigOptions = {},
    env: EnvMap = process.env,
  ): BetterStackConfig {
    const reader = new EnvReader(BetterStackConfig.ENV_PREFIX, env);
    const fromEnv: BetterStackConfigOptions = readCommonEnv(reader, VENDOR_ENV);
    const sourceToken = reader.secret("SOURCE_TOKEN", VENDOR_ENV.sourceToken);
    if (sourceToken !== undefined) fromEnv.sourceToken = sourceToken;
    const queryUrl = reader.string("QUERY_URL", VENDOR_ENV.queryUrl);
    if (queryUrl !== undefined) fromEnv.queryUrl = queryUrl;
    const queryUsername = reader.string("QUERY_USERNAME", VENDOR_ENV.queryUsername);
    if (queryUsername !== undefined) fromEnv.queryUsername = queryUsername;
    const queryPassword = reader.secret("QUERY_PASSWORD", VENDOR_ENV.queryPassword);
    if (queryPassword !== undefined) fromEnv.queryPassword = queryPassword;
    const sourceTable = reader.string("SOURCE_TABLE", VENDOR_ENV.sourceTable);
    if (sourceTable !== undefined) fromEnv.sourceTable = sourceTable;
    const encoding = reader.string("ENCODING");
    if (encoding !== undefined) fromEnv.encoding = encoding as IngestEncoding;
    const merged = mergeOptions<BetterStackConfigOptions>(fromEnv, overrides);
    return new BetterStackConfig(merged);
  }

  /** Whether the ingest side (url + sourceToken) is configured. */
  get hasIngest(): boolean {
    return this.url !== undefined && this.sourceToken !== undefined;
  }

  /** Whether the SQL query side (queryUrl + queryUsername + queryPassword) is configured. */
  get hasQuery(): boolean {
    return (
      this.queryUrl !== undefined &&
      this.queryUsername !== undefined &&
      this.queryPassword !== undefined
    );
  }

  /**
   * The common settings for one side's transport (`url` = that side's base URL). Throws
   * {@link ConfigError} when the side is not configured.
   */
  transportConfig(side: "ingest" | "query"): CommonConfig {
    const url = side === "ingest" ? this.url : this.queryUrl;
    if (url === undefined || !(side === "ingest" ? this.hasIngest : this.hasQuery)) {
      throw new ConfigError(
        side === "ingest"
          ? "invalid configuration: url (the source's ingesting host) and sourceToken are required for ingestion"
          : "invalid configuration: queryUrl, queryUsername and queryPassword are required for SQL queries",
      );
    }
    const out: { -readonly [K in keyof CommonConfig]: CommonConfig[K] } = {
      url,
      connectTimeoutMs: this.connectTimeoutMs,
      requestTimeoutMs: this.requestTimeoutMs,
      totalTimeoutMs: this.totalTimeoutMs,
      retry: this.retry,
      tls: this.tls,
      headers: this.headers,
    };
    if (this.proxy !== undefined) out.proxy = this.proxy;
    if (this.userAgentSuffix !== undefined) out.userAgentSuffix = this.userAgentSuffix;
    if (this.hooks !== undefined) out.hooks = this.hooks;
    if (this.logger !== undefined) out.logger = this.logger;
    return out;
  }

  /** Throws {@link ConfigError} when a setting is invalid (SPEC §2.2 plus API.md §2 rules). */
  validate(): void {
    const problems: string[] = [];
    const ingestParts = [this.url, this.sourceToken];
    const queryParts = [this.queryUrl, this.queryUsername, this.queryPassword];
    const anyIngest = ingestParts.some((v) => v !== undefined);
    const anyQuery = queryParts.some((v) => v !== undefined);
    if (!anyIngest && !anyQuery) {
      throw new ConfigError(
        "invalid configuration: set url + sourceToken (ingest) and/or queryUrl + queryUsername + queryPassword (SQL query)",
      );
    }
    if (anyIngest && this.url === undefined)
      problems.push("url (the source's ingesting host) is required with sourceToken");
    if (anyIngest && this.sourceToken === undefined)
      problems.push("sourceToken is required with url");
    if (anyQuery && queryParts.some((v) => v === undefined))
      problems.push("queryUrl, queryUsername and queryPassword must be given together");
    for (const [name, value] of [
      ["url", this.url],
      ["queryUrl", this.queryUrl],
    ] as const) {
      if (value !== undefined && !isHttpUrl(value))
        problems.push(`${name} must be an http or https URL`);
    }
    if (this.sourceToken?.reveal().trim() === "") problems.push("sourceToken must not be blank");
    if (this.queryPassword?.reveal() === "") problems.push("queryPassword must not be blank");
    if (this.sourceTable !== undefined && !IDENTIFIER_PATTERN.test(this.sourceTable))
      problems.push("sourceTable must match ^[A-Za-z_][A-Za-z0-9_]*$");
    if (!INGEST_ENCODINGS.includes(this.encoding))
      problems.push("encoding must be json, ndjson or msgpack");
    if (problems.length > 0) throw new ConfigError(`invalid configuration: ${problems.join("; ")}`);
    // Timeouts, retry, TLS and proxy are shared by both sides.
    validateCommonConfig({
      url: this.url ?? this.queryUrl ?? "",
      connectTimeoutMs: this.connectTimeoutMs,
      requestTimeoutMs: this.requestTimeoutMs,
      totalTimeoutMs: this.totalTimeoutMs,
      retry: this.retry,
      tls: this.tls,
      headers: this.headers,
      ...(this.proxy === undefined ? {} : { proxy: this.proxy }),
    });
  }

  /** Credential values to redact from error messages and logs. */
  knownSecrets(): string[] {
    const out = secretValues(this.sourceToken, this.queryPassword);
    const basic = this.basicCredentials();
    if (basic !== undefined) out.push(basic);
    return out;
  }

  /** base64(`username:password`) for HTTP Basic, or `undefined` without query credentials. */
  basicCredentials(): string | undefined {
    if (this.queryUsername === undefined || this.queryPassword === undefined) return undefined;
    return Buffer.from(`${this.queryUsername}:${this.queryPassword.reveal()}`, "utf8").toString(
      "base64",
    );
  }

  /** Debug form with every credential redacted. */
  toJSON(): Record<string, unknown> {
    const common = describeCommonConfig({
      url: this.url ?? "",
      connectTimeoutMs: this.connectTimeoutMs,
      requestTimeoutMs: this.requestTimeoutMs,
      totalTimeoutMs: this.totalTimeoutMs,
      retry: this.retry,
      tls: this.tls,
      headers: this.headers,
      ...(this.proxy === undefined ? {} : { proxy: this.proxy }),
      ...(this.userAgentSuffix === undefined ? {} : { userAgentSuffix: this.userAgentSuffix }),
    });
    return {
      ...common,
      url: this.url === undefined ? null : redactUrl(this.url),
      sourceToken: this.sourceToken === undefined ? null : REDACTED,
      queryUrl: this.queryUrl === undefined ? null : redactUrl(this.queryUrl),
      queryUsername: this.queryUsername ?? null,
      queryPassword: this.queryPassword === undefined ? null : REDACTED,
      sourceTable: this.sourceTable ?? null,
      encoding: this.encoding,
    };
  }

  toString(): string {
    return `BetterStackConfig(${JSON.stringify(this.toJSON())})`;
  }

  [INSPECT](): string {
    return this.toString();
  }
}
