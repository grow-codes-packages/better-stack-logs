// Service-owned: SQL query API (ClickHouse HTTP interface on the query host), API.md §5.

import {
  type HttpTransport,
  UnavailableError,
  ValidationError,
  ConfigError,
} from "./core/index.js";
import { IDENTIFIER_PATTERN, type BetterStackConfig } from "./config.js";
import {
  clickHouseCode,
  errorForClickHouseCode,
  joinUrl,
  mapQueryError,
  mappedRequest,
} from "./http.js";
import {
  BaseLogQueryClient,
  Severity,
  parseSeverity,
  severityText,
  toAttributes,
  type Attributes,
  type LogRecord,
  type LogSearchOptions,
  type LogSearchResult,
} from "./logging/index.js";

/** Value of a bound query parameter (`{name:Type}` + `param_<name>`). `null` is sent as `\N`. */
export type QueryParamValue = string | number | bigint | boolean | Date | null;

/** One result row (JSON object; ClickHouse quotes 64-bit integers as strings). */
export type QueryRow = Record<string, unknown>;

/** Options of {@link BetterStackQuery.query} and {@link BetterStackQuery.queryRaw}. */
export interface QueryOptions {
  /** ClickHouse settings sent as URL parameters (e.g. `{ max_execution_time: 30 }`). */
  settings?: Readonly<Record<string, string | number | boolean>>;
  /** Cancels the call. */
  signal?: AbortSignal;
}

/** Options of {@link BetterStackQuery.searchLogs}. */
export interface SearchLogsOptions {
  /** Collection (`t1234_myapp`); default: the configured `sourceTable`. */
  source?: string;
  /**
   * Case-sensitive literal text searched in the raw event JSON; it is JSON-escaped first (`"` and
   * `\\` and control characters), so it matches text inside string values. Empty or unset matches
   * everything.
   */
  contains?: string;
  /** Window start (inclusive); default 24 h before `end` (or before now). */
  start?: Date;
  /** Window end (inclusive); default: no upper bound (robust to clock skew and ingest delay). */
  end?: Date;
  /** Records per page, 1..10000 (default 100). */
  limit?: number;
  /** `"desc"` (default, newest first) or `"asc"`. */
  order?: "desc" | "asc";
  /** Opaque cursor from {@link LogSearchResult.nextCursor}. */
  cursor?: string;
  /** Cancels the call. */
  signal?: AbortSignal;
}

/** Formats {@link BetterStackQuery.query} can parse into rows. */
const JSON_FORMATS = new Set(["jsoneachrow", "json", "jsonstringseachrow", "ndjson", "jsonlines"]);
const FORMAT_CLAUSE = /\bFORMAT\s+([A-Za-z][A-Za-z0-9_]*)\s*;?\s*$/i;
const DAY_MS = 24 * 60 * 60 * 1000;
const MAX_LIMIT = 10_000;

/** Escapes a value as a ClickHouse single-quoted string literal (`\` and `'` and controls). */
export function sqlString(value: string): string {
  const escaped = value.replace(/[\\'\n\r\t\0]/g, (ch) => {
    switch (ch) {
      case "\n":
        return "\\n";
      case "\r":
        return "\\r";
      case "\t":
        return "\\t";
      case "\0":
        return "\\0";
      default:
        return `\\${ch}`;
    }
  });
  return `'${escaped}'`;
}

/** JSON string escaping without the surrounding quotes (quote, backslash, control characters), as in raw event JSON. */
export function jsonEscape(text: string): string {
  return JSON.stringify(text).slice(1, -1);
}

/** `YYYY-MM-DD HH:MM:SS.ffffff` (UTC), the DateTime64(6) text form. */
export function formatDateTime64(date: Date): string {
  if (!Number.isFinite(date.getTime())) throw new ValidationError("query: invalid Date");
  const iso = date.toISOString(); // 2026-11-18T21:00:00.123Z
  return `${iso.slice(0, 10)} ${iso.slice(11, 23)}000`;
}

/**
 * Parses a ClickHouse `DateTime64` text (`2026-11-18 21:00:00.123456`, UTC, 0–9 fraction digits),
 * an RFC 3339 string or UNIX seconds/ms. Returns `undefined` when unparseable.
 */
export function parseDateTime(value: unknown): Date | undefined {
  if (value instanceof Date) return value;
  if (typeof value === "number" && Number.isFinite(value)) {
    return new Date(value > 1e12 ? value : value * 1000);
  }
  if (typeof value !== "string") return undefined;
  const m = /^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?$/.exec(
    value.trim(),
  );
  if (m === null) return undefined;
  const frac = (m[3] ?? "").slice(0, 3).padEnd(3, "0");
  const zone = m[4] ?? "Z";
  const d = new Date(`${m[1]}T${m[2]}.${frac}${zone}`);
  return Number.isFinite(d.getTime()) ? d : undefined;
}

function paramText(name: string, value: QueryParamValue): string {
  if (value === null) return "\\N";
  if (value instanceof Date) return formatDateTime64(value);
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "string") return value;
  if (typeof value === "number" && !Number.isFinite(value)) {
    throw new ValidationError(`query: parameter ${name} must be a finite number`);
  }
  return String(value);
}

const TOP_LEVEL_KEYS = new Set([
  "dt",
  "message",
  "msg",
  "level",
  "severity",
  "severity_number",
  "span",
  "trace_id",
  "span_id",
  "trace_flags",
  "resource",
  "attributes",
  "observed_dt",
]);

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function hexId(v: unknown, length: number): string | undefined {
  if (typeof v !== "string") return undefined;
  const s = v.trim().toLowerCase();
  return new RegExp(`^[0-9a-f]{${length}}$`).test(s) && !/^0+$/.test(s) ? s : undefined;
}

/**
 * Normalizes a `{dt, raw}` row into a {@link LogRecord} (API.md §5.3): `body` = `message` (else
 * `msg`, else the raw text), severity from `severity_number` / `level` / `severity`, trace ids from
 * `span.trace_id`/`span.span_id` (or top-level `trace_id`/`span_id`), `resource` from `resource`,
 * and every other field plus `attributes` into `attributes`.
 */
export function logRecordFromRow(row: QueryRow): LogRecord {
  const rawText = typeof row.raw === "string" ? row.raw : JSON.stringify(row.raw ?? null);
  let raw: unknown;
  try {
    raw = JSON.parse(rawText) as unknown;
  } catch {
    raw = undefined;
  }
  const event: Record<string, unknown> = isObject(raw) ? raw : {};
  const timestamp = parseDateTime(row.dt) ?? parseDateTime(event.dt) ?? new Date(0);
  const message = event.message ?? event.msg;
  const body =
    typeof message === "string"
      ? message
      : message !== undefined
        ? JSON.stringify(message)
        : rawText;
  const levelText =
    typeof event.level === "string" && event.level !== ""
      ? event.level
      : typeof event.severity === "string" && event.severity !== ""
        ? event.severity
        : undefined;
  const fromNumber =
    typeof event.severity_number === "number" || typeof event.severity_number === "string"
      ? parseSeverity(event.severity_number)
      : null;
  const fromText = levelText === undefined ? null : parseSeverity(levelText);
  const severity: Severity = fromNumber ?? fromText ?? Severity.Info;
  const text =
    fromText !== null
      ? severityText(fromText)
      : levelText !== undefined
        ? levelText
        : severityText(severity);
  const attributes: Attributes = {};
  for (const [k, v] of Object.entries(event)) {
    if (!TOP_LEVEL_KEYS.has(k)) Object.assign(attributes, toAttributes({ [k]: v }));
  }
  if (isObject(event.attributes)) Object.assign(attributes, toAttributes(event.attributes));
  const record: LogRecord = {
    timestamp,
    severity,
    severityText: text,
    body,
    attributes,
    resource: isObject(event.resource) ? toAttributes(event.resource) : {},
  };
  const observed = parseDateTime(event.observed_dt);
  if (observed !== undefined) record.observedTimestamp = observed;
  const span = isObject(event.span) ? event.span : {};
  const traceId = hexId(span.trace_id ?? event.trace_id, 32);
  if (traceId !== undefined) {
    record.traceId = traceId;
    const spanId = hexId(span.span_id ?? event.span_id, 16);
    if (spanId !== undefined) record.spanId = spanId;
    if (typeof event.trace_flags === "number") record.traceFlags = event.trace_flags;
  }
  return record;
}

/** Throws the mapped error when a 200 body carries a ClickHouse exception (error after 200). */
function throwIfException(text: string): void {
  const code = clickHouseCode(text);
  if (code === undefined) return;
  const line = text
    .split("\n")
    .find((l) => clickHouseCode(l) !== undefined)
    ?.trim()
    .slice(0, 300);
  const message = `query: ${line ?? `Code: ${code}`}`;
  throw (
    errorForClickHouseCode(code, message) ??
    new UnavailableError(message, { retryable: false, httpStatus: 200 })
  );
}

/** Parses a JSON-family response body into rows. */
export function parseRows(text: string): QueryRow[] {
  const trimmed = text.trim();
  if (trimmed === "") return [];
  if (trimmed.startsWith("[")) {
    try {
      return JSON.parse(trimmed) as QueryRow[];
    } catch {
      throwIfException(trimmed);
      throw new UnavailableError("query: invalid JSON array in response", { retryable: false });
    }
  }
  const rows: QueryRow[] = [];
  for (const line of trimmed.split("\n")) {
    const l = line.trim();
    if (l === "") continue;
    let parsed: unknown;
    try {
      parsed = JSON.parse(l) as unknown;
    } catch {
      parsed = undefined;
    }
    if (!isObject(parsed)) {
      // FORMAT JSON spans several lines: parse the whole document instead.
      if (rows.length === 0) {
        try {
          const doc = JSON.parse(trimmed) as unknown;
          if (isObject(doc) && Array.isArray(doc.data)) return doc.data as QueryRow[];
        } catch {
          // fall through to the exception / invalid-line handling
        }
      }
      throwIfException(l);
      throw new UnavailableError("query: invalid JSON line in response", { retryable: false });
    }
    rows.push(parsed);
  }
  if (rows.length === 1 && Array.isArray(rows[0]?.data) && "meta" in (rows[0] as object)) {
    return rows[0].data as QueryRow[];
  }
  return rows;
}

interface Cursor {
  o: number;
}

function encodeCursor(c: Cursor): string {
  return Buffer.from(JSON.stringify(c), "utf8").toString("base64url");
}

function decodeCursor(cursor: string): Cursor {
  try {
    const c = JSON.parse(Buffer.from(cursor, "base64url").toString("utf8")) as unknown;
    if (isObject(c) && Number.isSafeInteger(c.o) && (c.o as number) >= 0)
      return { o: c.o as number };
  } catch {
    // invalid below
  }
  throw new ValidationError("logs.search: invalid cursor");
}

/**
 * SQL query client for Better Stack Logs (ClickHouse HTTP interface with the connection's own
 * Basic credentials). Implements {@link LogQueryClient}: `search` runs {@link searchLogs} on the
 * configured `sourceTable`, `tail` polls `search`, `listStreams` returns the configured
 * collection and `listLabels` is unsupported (Better Stack has no labels).
 */
export class BetterStackQuery extends BaseLogQueryClient {
  readonly #transport: HttpTransport;
  readonly #config: BetterStackConfig;
  readonly #now: () => Date;

  /**
   * @param transport the client's query transport.
   * @param config client configuration (query URL and credentials).
   * @param now clock (tests).
   */
  constructor(
    transport: HttpTransport,
    config: BetterStackConfig,
    now: () => Date = () => new Date(),
  ) {
    super();
    this.#transport = transport;
    this.#config = config;
    this.#now = now;
  }

  /**
   * Runs SQL and returns the response text unchanged. `params` are bound server side
   * (`{name:Type}` placeholders + `param_<name>` URL parameters).
   */
  async queryRaw(
    sql: string,
    params: Readonly<Record<string, QueryParamValue>> = {},
    options: QueryOptions = {},
  ): Promise<string> {
    if (sql.trim() === "") throw new ValidationError("query: sql must not be empty");
    const query: Record<string, string> = {};
    for (const [k, v] of Object.entries(options.settings ?? {})) query[k] = String(v);
    for (const [name, value] of Object.entries(params)) {
      if (!IDENTIFIER_PATTERN.test(name)) {
        throw new ValidationError(`query: invalid parameter name ${JSON.stringify(name)}`);
      }
      query[`param_${name}`] = paramText(name, value);
    }
    const res = await mappedRequest<string>(
      this.#transport,
      {
        method: "POST",
        path: joinUrl(this.#config.queryUrl ?? "", "/"),
        query,
        headers: {
          authorization: `Basic ${this.#config.basicCredentials() ?? ""}`,
          "content-type": "text/plain; charset=utf-8",
        },
        body: sql,
        idempotent: true,
        operation: "query",
        responseType: "text",
        ...(options.signal === undefined ? {} : { signal: options.signal }),
      },
      mapQueryError,
    );
    return res.data;
  }

  /**
   * Runs SQL and returns the rows. Without a `FORMAT` clause, `FORMAT JSONEachRow` is appended;
   * `FORMAT JSON`/`JSONEachRow` (also with `output_format_json_array_of_rows`) are parsed, other
   * formats throw `ValidationError` (use {@link queryRaw}). A ClickHouse exception appended to a
   * 200 response is raised as the mapped error.
   *
   * @example
   * const rows = await q.query(
   *   "SELECT count() AS c FROM remote(t1_app_logs) WHERE position(raw, {needle:String}) > 0",
   *   { needle: "checkout" },
   * );
   */
  async query(
    sql: string,
    params: Readonly<Record<string, QueryParamValue>> = {},
    options: QueryOptions = {},
  ): Promise<QueryRow[]> {
    const trimmed = sql.trim().replace(/;\s*$/, "");
    const m = FORMAT_CLAUSE.exec(trimmed);
    if (m !== null && !JSON_FORMATS.has((m[1] as string).toLowerCase())) {
      throw new ValidationError(
        `query: FORMAT ${m[1]} cannot be parsed into rows; use queryRaw or FORMAT JSONEachRow`,
      );
    }
    const text = await this.queryRaw(
      m === null ? `${trimmed} FORMAT JSONEachRow` : trimmed,
      params,
      options,
    );
    return parseRows(text);
  }

  /** Renders the {@link searchLogs} SQL (values escaped client side, DECISIONS 6). */
  renderSearchSql(options: SearchLogsOptions = {}): string {
    const source = options.source ?? this.#config.sourceTable;
    if (source === undefined) {
      throw new ConfigError(
        "invalid configuration: searchLogs needs a source or a configured sourceTable (GROW_BETTER_STACK_LOGS_SOURCE_TABLE)",
      );
    }
    if (!IDENTIFIER_PATTERN.test(source)) {
      throw new ValidationError("logs.search: source must match ^[A-Za-z_][A-Za-z0-9_]*$");
    }
    const limit = options.limit ?? 100;
    if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) {
      throw new ValidationError(`logs.search: limit must be an integer between 1 and ${MAX_LIMIT}`);
    }
    const order: string = options.order ?? "desc";
    if (order !== "asc" && order !== "desc") {
      throw new ValidationError('logs.search: order must be "asc" or "desc"');
    }
    const end = options.end;
    const start = options.start ?? new Date((end ?? this.#now()).getTime() - DAY_MS);
    if (end !== undefined && start.getTime() > end.getTime()) {
      throw new ValidationError("logs.search: start must not be after end");
    }
    const offset = options.cursor === undefined ? 0 : decodeCursor(options.cursor).o;
    const from = `toDateTime64(${sqlString(formatDateTime64(start))}, 6)`;
    const where = [
      end === undefined
        ? `dt >= ${from}`
        : `dt BETWEEN ${from} AND toDateTime64(${sqlString(formatDateTime64(end))}, 6)`,
    ];
    if (options.contains !== undefined && options.contains !== "") {
      where.push(`position(raw, ${sqlString(jsonEscape(options.contains))}) > 0`);
    }
    return (
      `SELECT dt, raw FROM (SELECT dt, raw FROM remote(${source}_logs) UNION ALL ` +
      `SELECT dt, raw FROM s3Cluster(primary, ${source}_s3) WHERE _row_type = 1) ` +
      `WHERE ${where.join(" AND ")} ORDER BY dt ${order.toUpperCase()} ` +
      `LIMIT ${limit + 1}${offset > 0 ? ` OFFSET ${offset}` : ""} FORMAT JSONEachRow`
    );
  }

  /**
   * Searches hot and cold logs of a collection (`remote(<source>_logs)` UNION ALL
   * `s3Cluster(primary, <source>_s3)`), inclusive time window, literal substring match, newest
   * first by default. Rows are normalized ({@link logRecordFromRow}) and deduplicated on
   * `(dt, raw)`; `nextCursor` is set when more rows exist.
   */
  async searchLogs(options: SearchLogsOptions = {}): Promise<LogSearchResult> {
    const sql = this.renderSearchSql(options);
    const limit = options.limit ?? 100;
    const offset = options.cursor === undefined ? 0 : decodeCursor(options.cursor).o;
    const rows = await this.query(
      sql,
      {},
      options.signal === undefined ? {} : { signal: options.signal },
    );
    const page = rows.slice(0, limit);
    const seen = new Set<string>();
    const records: LogRecord[] = [];
    for (const row of page) {
      const key = `${String(row.dt)}\u0000${String(row.raw)}`;
      if (seen.has(key)) continue;
      seen.add(key);
      records.push(logRecordFromRow(row));
    }
    const result: LogSearchResult = { records };
    if (rows.length > limit) result.nextCursor = encodeCursor({ o: offset + limit });
    return result;
  }

  /**
   * {@link LogQueryClient.search}: `query` is the `contains` text, searched in the configured
   * `sourceTable`.
   */
  search(query: string, opts: LogSearchOptions = {}): Promise<LogSearchResult> {
    const options: SearchLogsOptions = { contains: query };
    if (opts.start !== undefined) options.start = opts.start;
    if (opts.end !== undefined) options.end = opts.end;
    if (opts.limit !== undefined) options.limit = opts.limit;
    if (opts.order !== undefined) options.order = opts.order;
    if (opts.cursor !== undefined) options.cursor = opts.cursor;
    if (opts.signal !== undefined) options.signal = opts.signal;
    return this.searchLogs(options);
  }

  /** The configured collections (`[sourceTable]`, or `[]`); Better Stack has no listing API here. */
  override listStreams(): Promise<string[]> {
    return Promise.resolve(
      this.#config.sourceTable === undefined ? [] : [this.#config.sourceTable],
    );
  }
}
