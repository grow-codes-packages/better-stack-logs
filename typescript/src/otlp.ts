// Service-owned: OTLP/HTTP logs exporter for the ingesting host, `POST {url}/v1/logs`
// (API.md §4). Protobuf (default) or OTLP JSON, same Bearer source token, optional gzip.

import { gzipSync } from "node:zlib";
import {
  BetterStackError,
  type HttpTransport,
  UnavailableError,
  ValidationError,
} from "./core/index.js";
import type { BetterStackConfig } from "./config.js";
import { MAX_REQUEST_BYTES } from "./exporter.js";
import { joinUrl, mapIngestError, mappedRequest } from "./http.js";
import type {
  AttrValue,
  Attributes,
  ExportOptions,
  ExportResult,
  LogExporter,
  LogRecord,
} from "./logging/index.js";
import { SDK_VERSION } from "./core/index.js";

/** OTLP logs path on the ingesting host. */
export const OTLP_LOGS_PATH = "/v1/logs";

/** OTLP/HTTP payload format. */
export type OtlpProtocol = "protobuf" | "json";

/** Options of {@link BetterStackOtlpLogExporter}. */
export interface BetterStackOtlpLogExporterOptions {
  /** `"protobuf"` (default, `application/x-protobuf`) or `"json"` (`application/json`). */
  protocol?: OtlpProtocol;
  /** Instrumentation scope name (default `@grow-codes/better-stack-logs`). */
  scopeName?: string;
  /** Uncompressed bytes per request, at most 10 MiB (default 10 MiB). */
  maxRequestBytes?: number;
}

const DEFAULT_SCOPE = "@grow-codes/better-stack-logs";

// --- protobuf writer -----------------------------------------------------------------------------

class Pb {
  readonly parts: Uint8Array[] = [];
  length = 0;

  #push(bytes: Uint8Array): void {
    this.parts.push(bytes);
    this.length += bytes.length;
  }

  varint(v: bigint): void {
    const out: number[] = [];
    let n = BigInt.asUintN(64, v);
    while (n >= 0x80n) {
      out.push(Number(n & 0x7fn) | 0x80);
      n >>= 7n;
    }
    out.push(Number(n));
    this.#push(Uint8Array.from(out));
  }

  tag(field: number, wire: number): void {
    this.varint(BigInt((field << 3) | wire));
  }

  uint(field: number, v: number | bigint): void {
    this.tag(field, 0);
    this.varint(BigInt(v));
  }

  fixed64(field: number, v: bigint): void {
    this.tag(field, 1);
    const b = new Uint8Array(8);
    new DataView(b.buffer).setBigUint64(0, BigInt.asUintN(64, v), true);
    this.#push(b);
  }

  fixed32(field: number, v: number): void {
    this.tag(field, 5);
    const b = new Uint8Array(4);
    new DataView(b.buffer).setUint32(0, v >>> 0, true);
    this.#push(b);
  }

  double(field: number, v: number): void {
    this.tag(field, 1);
    const b = new Uint8Array(8);
    new DataView(b.buffer).setFloat64(0, v, true);
    this.#push(b);
  }

  bytes(field: number, b: Uint8Array): void {
    this.tag(field, 2);
    this.varint(BigInt(b.length));
    this.#push(b);
  }

  string(field: number, s: string): void {
    this.bytes(field, new TextEncoder().encode(s));
  }

  message(field: number, m: Pb): void {
    this.bytes(field, m.finish());
  }

  finish(): Uint8Array {
    const out = new Uint8Array(this.length);
    let offset = 0;
    for (const p of this.parts) {
      out.set(p, offset);
      offset += p.length;
    }
    return out;
  }
}

function pbAnyValue(value: AttrValue): Pb {
  const m = new Pb();
  if (typeof value === "string") m.string(1, value);
  else if (typeof value === "boolean") m.uint(2, value ? 1 : 0);
  else if (typeof value === "number") {
    if (Number.isSafeInteger(value)) m.uint(3, BigInt(value));
    else m.double(4, value);
  } else if (Array.isArray(value)) {
    const arr = new Pb();
    for (const item of value) arr.message(1, pbAnyValue(item));
    m.message(5, arr);
  } else {
    m.message(6, pbKeyValues(value));
  }
  return m;
}

function pbKeyValues(attrs: Attributes): Pb {
  const list = new Pb();
  for (const [k, v] of Object.entries(attrs)) list.message(1, pbKeyValue(k, v));
  return list;
}

function pbKeyValue(key: string, value: AttrValue): Pb {
  const kv = new Pb();
  kv.string(1, key);
  kv.message(2, pbAnyValue(value));
  return kv;
}

function hexBytes(hex: string): Uint8Array {
  return Uint8Array.from(Buffer.from(hex, "hex"));
}

function nanos(date: Date): bigint {
  return BigInt(date.getTime()) * 1_000_000n;
}

/** Encodes one OTLP `LogRecord` message (logs/v1). */
export function encodeOtlpLogRecord(record: LogRecord): Uint8Array {
  const m = new Pb();
  m.fixed64(1, nanos(record.timestamp));
  m.uint(2, record.severity);
  m.string(3, record.severityText);
  m.message(5, pbAnyValue(record.body));
  for (const [k, v] of Object.entries(record.attributes)) m.message(6, pbKeyValue(k, v));
  if (record.traceFlags !== undefined) m.fixed32(8, record.traceFlags);
  if (record.traceId !== undefined) m.bytes(9, hexBytes(record.traceId));
  if (record.spanId !== undefined) m.bytes(10, hexBytes(record.spanId));
  if (record.observedTimestamp !== undefined) m.fixed64(11, nanos(record.observedTimestamp));
  return m.finish();
}

function groupByResource(records: readonly LogRecord[]): [Attributes, LogRecord[]][] {
  const groups = new Map<string, [Attributes, LogRecord[]]>();
  for (const r of records) {
    const key = JSON.stringify(r.resource);
    const g = groups.get(key);
    if (g === undefined) groups.set(key, [r.resource, [r]]);
    else g[1].push(r);
  }
  return [...groups.values()];
}

/** Encodes an `ExportLogsServiceRequest` (collector/logs/v1) as protobuf. */
export function encodeOtlpProtobuf(
  records: readonly LogRecord[],
  scopeName: string = DEFAULT_SCOPE,
): Uint8Array {
  const req = new Pb();
  for (const [resource, group] of groupByResource(records)) {
    const rl = new Pb();
    const res = new Pb();
    for (const [k, v] of Object.entries(resource)) res.message(1, pbKeyValue(k, v));
    rl.message(1, res);
    const sl = new Pb();
    const scope = new Pb();
    scope.string(1, scopeName);
    scope.string(2, SDK_VERSION);
    sl.message(1, scope);
    for (const r of group) sl.bytes(2, encodeOtlpLogRecord(r));
    rl.message(2, sl);
    req.message(1, rl);
  }
  return req.finish();
}

// --- OTLP JSON -----------------------------------------------------------------------------------

type JsonAny = Record<string, unknown>;

function jsonAnyValue(value: AttrValue): JsonAny {
  if (typeof value === "string") return { stringValue: value };
  if (typeof value === "boolean") return { boolValue: value };
  if (typeof value === "number") {
    return Number.isSafeInteger(value) ? { intValue: String(value) } : { doubleValue: value };
  }
  if (Array.isArray(value)) return { arrayValue: { values: value.map(jsonAnyValue) } };
  return { kvlistValue: { values: jsonKeyValues(value) } };
}

function jsonKeyValues(attrs: Attributes): JsonAny[] {
  return Object.entries(attrs).map(([key, v]) => ({ key, value: jsonAnyValue(v) }));
}

/** Builds the OTLP JSON `ExportLogsServiceRequest` object. */
export function otlpJsonRequest(
  records: readonly LogRecord[],
  scopeName: string = DEFAULT_SCOPE,
): Record<string, unknown> {
  return {
    resourceLogs: groupByResource(records).map(([resource, group]) => ({
      resource: { attributes: jsonKeyValues(resource) },
      scopeLogs: [
        {
          scope: { name: scopeName, version: SDK_VERSION },
          logRecords: group.map((r) => {
            const out: JsonAny = {
              timeUnixNano: nanos(r.timestamp).toString(),
              severityNumber: r.severity,
              severityText: r.severityText,
              body: jsonAnyValue(r.body),
              attributes: jsonKeyValues(r.attributes),
            };
            if (r.observedTimestamp !== undefined)
              out.observedTimeUnixNano = nanos(r.observedTimestamp).toString();
            if (r.traceFlags !== undefined) out.flags = r.traceFlags;
            if (r.traceId !== undefined) out.traceId = r.traceId;
            if (r.spanId !== undefined) out.spanId = r.spanId;
            return out;
          }),
        },
      ],
    })),
  };
}

// --- partial success -----------------------------------------------------------------------------

function readVarint(data: Uint8Array, pos: number): [bigint, number] {
  let result = 0n;
  let shift = 0n;
  for (;;) {
    if (pos >= data.length) throw new Error("truncated varint");
    const byte = data[pos++] as number;
    result |= BigInt(byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) return [result, pos];
    shift += 7n;
    if (shift > 63n) throw new Error("varint too long");
  }
}

/** Fields of a protobuf message: `[field, wireType, varint | bytes]`. */
function pbFields(data: Uint8Array): [number, number, bigint | Uint8Array][] {
  const out: [number, number, bigint | Uint8Array][] = [];
  let pos = 0;
  while (pos < data.length) {
    const [key, p1] = readVarint(data, pos);
    pos = p1;
    const field = Number(key >> 3n);
    const wire = Number(key & 7n);
    if (wire === 0) {
      const [v, p2] = readVarint(data, pos);
      out.push([field, wire, v]);
      pos = p2;
    } else if (wire === 2) {
      const [len, p2] = readVarint(data, pos);
      const end = p2 + Number(len);
      if (end > data.length) throw new Error("truncated bytes");
      out.push([field, wire, data.subarray(p2, end)]);
      pos = end;
    } else if (wire === 1) {
      pos += 8;
    } else if (wire === 5) {
      pos += 4;
    } else {
      throw new Error(`unsupported wire type ${wire}`);
    }
  }
  return out;
}

/** Partial success reported by an OTLP response. */
export interface OtlpPartialSuccess {
  rejected: number;
  message?: string;
}

/**
 * Reads `partial_success` from an `ExportLogsServiceResponse` (protobuf bytes or JSON object).
 * Returns `undefined` when nothing was rejected or the body cannot be read.
 */
export function parsePartialSuccess(body: unknown): OtlpPartialSuccess | undefined {
  try {
    if (body instanceof Uint8Array) {
      for (const [f, w, v] of pbFields(body)) {
        if (f !== 1 || w !== 2) continue;
        let rejected = 0;
        let message: string | undefined;
        for (const [pf, pw, pv] of pbFields(v as Uint8Array)) {
          if (pf === 1 && pw === 0) rejected = Number(pv);
          if (pf === 2 && pv instanceof Uint8Array) message = new TextDecoder().decode(pv);
        }
        if (rejected > 0)
          return message === undefined || message === "" ? { rejected } : { rejected, message };
      }
      return undefined;
    }
    const ps = (
      body as { partialSuccess?: { rejectedLogRecords?: unknown; errorMessage?: unknown } }
    ).partialSuccess;
    const rejected = Number(ps?.rejectedLogRecords ?? 0);
    if (!Number.isFinite(rejected) || rejected <= 0) return undefined;
    const message = typeof ps?.errorMessage === "string" ? ps.errorMessage : "";
    return message === "" ? { rejected } : { rejected, message };
  } catch {
    return undefined;
  }
}

/**
 * {@link LogExporter} that sends OTLP/HTTP logs to the source's ingesting host
 * (`POST {url}/v1/logs`) with the source token. Records are grouped by resource; batches are
 * split so no request exceeds `maxRequestBytes`. `partial_success` in the response counts as
 * rejected records. Retried like idempotent calls (duplicates possible after an ambiguous failure).
 */
export class BetterStackOtlpLogExporter implements LogExporter {
  readonly #transport: HttpTransport;
  readonly #config: BetterStackConfig;
  /** Payload format. */
  readonly protocol: OtlpProtocol;
  readonly #scope: string;
  readonly #maxRequest: number;

  /**
   * @param transport the client's ingest transport.
   * @param config client configuration (ingest URL and source token).
   * @param options protocol, scope name and request limit.
   */
  constructor(
    transport: HttpTransport,
    config: BetterStackConfig,
    options: BetterStackOtlpLogExporterOptions = {},
  ) {
    this.#transport = transport;
    this.#config = config;
    const protocol: string = options.protocol ?? "protobuf";
    if (protocol !== "protobuf" && protocol !== "json") {
      throw new ValidationError(
        `logs.otlp: protocol must be protobuf or json, got ${JSON.stringify(protocol)}`,
      );
    }
    this.protocol = protocol;
    this.#scope = options.scopeName ?? DEFAULT_SCOPE;
    const max = options.maxRequestBytes;
    if (max !== undefined && (!Number.isInteger(max) || max < 1)) {
      throw new ValidationError("logs.otlp: maxRequestBytes must be a positive integer");
    }
    this.#maxRequest = Math.min(max ?? MAX_REQUEST_BYTES, MAX_REQUEST_BYTES);
  }

  /** Encodes records as one request body (protobuf bytes or OTLP JSON text). */
  encode(records: readonly LogRecord[]): Uint8Array {
    return this.protocol === "protobuf"
      ? encodeOtlpProtobuf(records, this.#scope)
      : new TextEncoder().encode(JSON.stringify(otlpJsonRequest(records, this.#scope)));
  }

  /** Splits records into chunks whose encoded size stays under the request limit. */
  chunks(records: readonly LogRecord[]): { chunks: LogRecord[][]; oversized: number } {
    const chunks: LogRecord[][] = [];
    let oversized = 0;
    let current: LogRecord[] = [];
    let bytes = 0;
    for (const r of records) {
      // A one-record request over-estimates the record's share (wrappers counted every time).
      let size: number;
      try {
        size = this.encode([r]).length;
      } catch {
        size = Number.POSITIVE_INFINITY; // not encodable (e.g. an invalid Date)
      }
      if (size > this.#maxRequest) {
        oversized++;
        continue;
      }
      if (current.length > 0 && bytes + size > this.#maxRequest) {
        chunks.push(current);
        current = [];
        bytes = 0;
      }
      current.push(r);
      bytes += size;
    }
    if (current.length > 0) chunks.push(current);
    return { chunks, oversized };
  }

  /** Sends the records; never throws for service errors (see {@link LogExporter.export}). */
  async export(records: LogRecord[], opts: ExportOptions): Promise<ExportResult> {
    const { chunks, oversized } = this.chunks(records);
    let accepted = 0;
    let rejected = oversized;
    let error: BetterStackError | undefined =
      oversized > 0
        ? new ValidationError(
            `logs.otlp: ${oversized} record(s) dropped: not encodable or larger than ${this.#maxRequest} bytes`,
          )
        : undefined;
    for (const chunk of chunks) {
      try {
        const partial = await this.send(chunk, {
          gzip: opts.gzip,
          ...(opts.signal === undefined ? {} : { signal: opts.signal }),
        });
        const bad = Math.min(partial?.rejected ?? 0, chunk.length);
        accepted += chunk.length - bad;
        if (bad > 0) {
          rejected += bad;
          error = new ValidationError(
            `logs.otlp: the service rejected ${bad} record(s)${
              partial?.message === undefined ? "" : `: ${partial.message}`
            }`,
          );
        }
      } catch (err) {
        return {
          accepted,
          rejected: records.length - accepted,
          error:
            err instanceof BetterStackError
              ? err
              : new UnavailableError("logs.otlp: unexpected failure", { cause: err }),
        };
      }
    }
    return error === undefined ? { accepted, rejected } : { accepted, rejected, error };
  }

  /**
   * Posts one OTLP request for `records`. Returns the partial success (rejected count) when the
   * service reports one. Throws the mapped SDK error.
   */
  async send(
    records: readonly LogRecord[],
    options: { gzip?: boolean; signal?: AbortSignal } = {},
  ): Promise<OtlpPartialSuccess | undefined> {
    const body = this.encode(records);
    const gzip = options.gzip ?? true;
    const headers: Record<string, string> = {
      authorization: `Bearer ${this.#config.sourceToken?.reveal() ?? ""}`,
      "content-type": this.protocol === "protobuf" ? "application/x-protobuf" : "application/json",
      accept: this.protocol === "protobuf" ? "application/x-protobuf" : "application/json",
    };
    if (gzip) headers["content-encoding"] = "gzip";
    const res = await mappedRequest<unknown>(
      this.#transport,
      {
        method: "POST",
        path: joinUrl(this.#config.url ?? "", OTLP_LOGS_PATH),
        headers,
        body: gzip ? new Uint8Array(gzipSync(body)) : body,
        idempotent: true,
        operation: "logs.otlp",
        responseType: "bytes",
        ...(options.signal === undefined ? {} : { signal: options.signal }),
      },
      mapIngestError,
    );
    const data = res.data as Uint8Array;
    if (this.protocol === "protobuf") return parsePartialSuccess(data);
    const text = new TextDecoder().decode(data).trim();
    if (text === "") return undefined;
    try {
      return parsePartialSuccess(JSON.parse(text) as unknown);
    } catch {
      return undefined;
    }
  }

  /** Nothing to release; the client owns the transport. */
  shutdown(): Promise<void> {
    return Promise.resolve();
  }
}
