// Service-owned: the Better Stack ingest exporter, `POST {ingesting host}/` (API.md §3).

import { gzipSync } from "node:zlib";
import {
  BetterStackError,
  type HttpTransport,
  UnavailableError,
  ValidationError,
} from "./core/index.js";
import type { BetterStackConfig } from "./config.js";
import {
  CONTENT_TYPES,
  type BetterStackEvent,
  type IngestEncoding,
  encodeEvents,
  encodedEventSize,
  toBetterStackEvent,
} from "./encoding.js";
import { mapIngestError, mappedRequest, joinUrl } from "./http.js";
import type { ExportOptions, ExportResult, LogExporter, LogRecord } from "./logging/index.js";

/** Better Stack limit: one request body (compressed) and one record (uncompressed), 10 MiB. */
export const MAX_REQUEST_BYTES = 10 * 1024 * 1024;
/** Better Stack limit for one record, uncompressed. */
export const MAX_RECORD_BYTES = 10 * 1024 * 1024;

/** Options of {@link BetterStackLogExporter}. */
export interface BetterStackLogExporterOptions {
  /** Body encoding (default: the config's `encoding`, `"json"` unless configured). */
  encoding?: IngestEncoding;
  /** Uncompressed bytes per request, at most 10 MiB (default 10 MiB). */
  maxRequestBytes?: number;
  /** Encoded bytes per record, at most 10 MiB (default 10 MiB); larger records are rejected. */
  maxRecordBytes?: number;
}

/** Options of {@link BetterStackLogExporter.sendEvents}. */
export interface SendEventsOptions {
  /** Body encoding (default: the exporter's encoding). */
  encoding?: IngestEncoding;
  /** Gzip the body (`Content-Encoding: gzip`, default true). */
  gzip?: boolean;
  /** Cancels the call. */
  signal?: AbortSignal;
}

/** One request body before compression. */
export interface IngestPayload {
  /** Encoded events. */
  events: BetterStackEvent[];
  /** Uncompressed body bytes (estimate including separators). */
  bytes: number;
}

/** Result of {@link BetterStackLogExporter.split}. */
export interface SplitResult {
  /** Request bodies in order. */
  payloads: IngestPayload[];
  /** Records larger than `maxRecordBytes` (never sent). */
  oversized: number;
  /** Records that cannot be encoded (e.g. an invalid `Date` in MessagePack; never sent). */
  invalid: number;
}

function cap(value: number | undefined, max: number, name: string): number {
  if (value === undefined) return max;
  if (!Number.isInteger(value) || value < 1) {
    throw new ValidationError(`logs.exporter: ${name} must be a positive integer`);
  }
  return Math.min(value, max);
}

/**
 * {@link LogExporter} for Better Stack's HTTP ingest API: maps records to events
 * ({@link toBetterStackEvent}), splits batches so no request exceeds 10 MiB, encodes them as
 * JSON, NDJSON or MessagePack, gzips when asked and posts with `Authorization: Bearer <source
 * token>`. Exports are retried like idempotent calls (429, 5xx, network errors); Better Stack does
 * not deduplicate, so an ambiguous failure may produce duplicates.
 */
export class BetterStackLogExporter implements LogExporter {
  readonly #transport: HttpTransport;
  readonly #config: BetterStackConfig;
  /** Default body encoding. */
  readonly encoding: IngestEncoding;
  readonly #maxRequest: number;
  readonly #maxRecord: number;

  /**
   * @param transport the client's ingest transport.
   * @param config client configuration (ingest URL and source token).
   * @param options encoding and request limits.
   */
  constructor(
    transport: HttpTransport,
    config: BetterStackConfig,
    options: BetterStackLogExporterOptions = {},
  ) {
    this.#transport = transport;
    this.#config = config;
    const encoding = options.encoding ?? config.encoding;
    if (!(encoding in CONTENT_TYPES)) {
      throw new ValidationError(
        `logs.exporter: encoding must be json, ndjson or msgpack, got ${JSON.stringify(encoding)}`,
      );
    }
    this.encoding = encoding;
    this.#maxRequest = cap(options.maxRequestBytes, MAX_REQUEST_BYTES, "maxRequestBytes");
    this.#maxRecord = cap(options.maxRecordBytes, MAX_RECORD_BYTES, "maxRecordBytes");
  }

  /** Maps records to events and splits them into request bodies (no I/O). */
  split(records: readonly LogRecord[]): SplitResult {
    const payloads: IngestPayload[] = [];
    let oversized = 0;
    let invalid = 0;
    let current: BetterStackEvent[] = [];
    let bytes = 8;
    for (const record of records) {
      const event = toBetterStackEvent(record);
      let size: number;
      try {
        size = encodedEventSize(event, this.encoding);
      } catch {
        invalid++;
        continue;
      }
      if (size > this.#maxRecord || size + 8 > this.#maxRequest) {
        oversized++;
        continue;
      }
      if (current.length > 0 && bytes + size > this.#maxRequest) {
        payloads.push({ events: current, bytes });
        current = [];
        bytes = 8;
      }
      current.push(event);
      bytes += size;
    }
    if (current.length > 0) payloads.push({ events: current, bytes });
    return { payloads, oversized, invalid };
  }

  /**
   * Sends the records. Returns `{accepted, rejected, error?}`; a failing request stops the export
   * (later requests count as rejected). Never throws for service errors.
   */
  async export(records: LogRecord[], opts: ExportOptions): Promise<ExportResult> {
    const { payloads, oversized, invalid } = this.split(records);
    let accepted = 0;
    for (const payload of payloads) {
      try {
        await this.sendEvents(payload.events, {
          gzip: opts.gzip,
          ...(opts.signal === undefined ? {} : { signal: opts.signal }),
        });
      } catch (err) {
        return {
          accepted,
          rejected: records.length - accepted,
          error:
            err instanceof BetterStackError
              ? err
              : new UnavailableError("logs.send: unexpected failure", { cause: err }),
        };
      }
      accepted += payload.events.length;
    }
    if (oversized + invalid > 0) {
      const reasons = [
        oversized > 0 ? `${oversized} larger than ${this.#maxRecord} bytes` : "",
        invalid > 0 ? `${invalid} not encodable as ${this.encoding}` : "",
      ].filter((r) => r !== "");
      return {
        accepted,
        rejected: oversized + invalid,
        error: new ValidationError(`logs.send: record(s) dropped: ${reasons.join(", ")}`),
      };
    }
    return { accepted, rejected: 0 };
  }

  /**
   * Posts raw events (any JSON objects; `dt` may be a `Date`, an RFC 3339 string or UNIX
   * seconds/ms/ns) as one request. Throws the mapped SDK error: 403 → `AuthenticationError`,
   * 402 → `RateLimitError` (not retryable), 406/413 → `ValidationError`.
   */
  async sendEvents(
    events: readonly BetterStackEvent[],
    options: SendEventsOptions = {},
  ): Promise<void> {
    const encoding = options.encoding ?? this.encoding;
    const body = encodeEvents(events, encoding);
    if (body.length > this.#maxRequest) {
      throw new ValidationError(
        `logs.send: request body of ${body.length} bytes exceeds ${this.#maxRequest} bytes`,
      );
    }
    await this.#post(body, CONTENT_TYPES[encoding], options);
  }

  async #post(body: Uint8Array, contentType: string, options: SendEventsOptions): Promise<void> {
    const gzip = options.gzip ?? true;
    const headers: Record<string, string> = {
      authorization: `Bearer ${this.#config.sourceToken?.reveal() ?? ""}`,
      "content-type": contentType,
    };
    if (gzip) headers["content-encoding"] = "gzip";
    await mappedRequest(
      this.#transport,
      {
        method: "POST",
        path: joinUrl(this.#config.url ?? "", "/"),
        headers,
        body: gzip ? new Uint8Array(gzipSync(body)) : body,
        idempotent: true,
        operation: "logs.send",
        responseType: "none",
        ...(options.signal === undefined ? {} : { signal: options.signal }),
      },
      mapIngestError,
    );
  }

  /** Nothing to release; the client owns the transport. */
  shutdown(): Promise<void> {
    return Promise.resolve();
  }
}
