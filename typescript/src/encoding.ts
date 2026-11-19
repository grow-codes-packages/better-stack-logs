// Service-owned: LogRecord → Better Stack event mapping and the three ingest encodings
// (JSON, NDJSON, MessagePack with the timestamp extension), API.md §3.

import { ValidationError } from "./core/index.js";
import type { LogRecord } from "./logging/index.js";

/** Ingest body encodings accepted by `POST /` on the ingesting host. */
export type IngestEncoding = "json" | "ndjson" | "msgpack";

/** Content-Type sent for each {@link IngestEncoding}. */
export const CONTENT_TYPES: Readonly<Record<IngestEncoding, string>> = Object.freeze({
  json: "application/json",
  ndjson: "application/x-ndjson",
  msgpack: "application/msgpack",
});

/** One Better Stack event: a JSON object; `dt` is a `Date` until it is encoded. */
export type BetterStackEvent = Record<string, unknown>;

/**
 * Maps a {@link LogRecord} to the Better Stack event shape:
 * `{dt, message, level, severity_number, span: {trace_id, span_id}, trace_flags, resource,
 * attributes, observed_dt}`. `span.trace_id`/`span.span_id` are Better Stack's log↔trace
 * correlation fields. Empty `resource`/`attributes` are omitted.
 */
export function toBetterStackEvent(record: LogRecord): BetterStackEvent {
  const event: BetterStackEvent = {
    dt: record.timestamp,
    message: record.body,
    level: record.severityText.toLowerCase(),
    severity_number: record.severity,
  };
  if (record.traceId !== undefined) {
    const span: Record<string, string> = { trace_id: record.traceId };
    if (record.spanId !== undefined) span.span_id = record.spanId;
    event.span = span;
    if (record.traceFlags !== undefined) event.trace_flags = record.traceFlags;
  }
  if (Object.keys(record.resource).length > 0) event.resource = record.resource;
  if (Object.keys(record.attributes).length > 0) event.attributes = record.attributes;
  if (record.observedTimestamp !== undefined) event.observed_dt = record.observedTimestamp;
  return event;
}

const encoder = new TextEncoder();

/** JSON text of one event (`Date` → RFC 3339 UTC). */
export function eventJson(event: BetterStackEvent): string {
  return JSON.stringify(event);
}

/**
 * Encodes events as one request body. `json`: a JSON array (or the object itself when
 * `single` is true); `ndjson`: one object per line; `msgpack`: a MessagePack array with `Date`
 * values as timestamp extensions (type -1).
 */
export function encodeEvents(
  events: readonly BetterStackEvent[],
  encoding: IngestEncoding,
): Uint8Array {
  switch (encoding) {
    case "json":
      return encoder.encode(`[${events.map(eventJson).join(",")}]`);
    case "ndjson":
      return encoder.encode(events.map((e) => `${eventJson(e)}\n`).join(""));
    case "msgpack":
      return encodeMsgpack(events);
    default:
      throw new ValidationError(`logs.send: unknown encoding ${JSON.stringify(encoding)}`);
  }
}

/** Encoded size of one event inside a body of the given encoding (separators included). */
export function encodedEventSize(event: BetterStackEvent, encoding: IngestEncoding): number {
  if (encoding === "msgpack") return encodeMsgpack(event).length;
  return encoder.encode(eventJson(event)).length + 1;
}

// --- MessagePack -------------------------------------------------------------------------------

class Writer {
  #buf = new Uint8Array(256);
  #view = new DataView(this.#buf.buffer);
  #len = 0;

  #ensure(extra: number): void {
    if (this.#len + extra <= this.#buf.length) return;
    let size = this.#buf.length * 2;
    while (size < this.#len + extra) size *= 2;
    const next = new Uint8Array(size);
    next.set(this.#buf.subarray(0, this.#len));
    this.#buf = next;
    this.#view = new DataView(next.buffer);
  }

  u8(v: number): void {
    this.#ensure(1);
    this.#buf[this.#len++] = v;
  }

  u16(v: number): void {
    this.#ensure(2);
    this.#view.setUint16(this.#len, v);
    this.#len += 2;
  }

  u32(v: number): void {
    this.#ensure(4);
    this.#view.setUint32(this.#len, v);
    this.#len += 4;
  }

  i64(v: bigint): void {
    this.#ensure(8);
    this.#view.setBigInt64(this.#len, v);
    this.#len += 8;
  }

  u64(v: bigint): void {
    this.#ensure(8);
    this.#view.setBigUint64(this.#len, v);
    this.#len += 8;
  }

  f64(v: number): void {
    this.#ensure(8);
    this.#view.setFloat64(this.#len, v);
    this.#len += 8;
  }

  bytes(b: Uint8Array): void {
    this.#ensure(b.length);
    this.#buf.set(b, this.#len);
    this.#len += b.length;
  }

  result(): Uint8Array {
    return this.#buf.slice(0, this.#len);
  }
}

function writeInt(w: Writer, v: bigint): void {
  if (v >= 0n) {
    if (v < 128n) {
      w.u8(Number(v));
    } else if (v < 256n) {
      w.u8(0xcc);
      w.u8(Number(v));
    } else if (v < 65536n) {
      w.u8(0xcd);
      w.u16(Number(v));
    } else if (v < 4294967296n) {
      w.u8(0xce);
      w.u32(Number(v));
    } else if (v < 18446744073709551616n) {
      w.u8(0xcf);
      w.u64(v);
    } else {
      throw new ValidationError("msgpack: integer out of range");
    }
  } else if (v >= -32n) {
    w.u8(Number(v) & 0xff);
  } else if (v >= -128n) {
    w.u8(0xd0);
    w.u8(Number(v) & 0xff);
  } else if (v >= -32768n) {
    w.u8(0xd1);
    w.u16(Number(v) & 0xffff);
  } else if (v >= -2147483648n) {
    w.u8(0xd2);
    w.u32(Number(v) >>> 0);
  } else if (v >= -9223372036854775808n) {
    w.u8(0xd3);
    w.i64(v);
  } else {
    throw new ValidationError("msgpack: integer out of range");
  }
}

/** Writes a length header: fix form (`fix | n` up to `fixMax`), else the 8/16/32-bit codes. */
function writeLength(w: Writer, n: number, fix: number, fixMax: number, codes: number[]): void {
  const [c8, c16, c32] = codes as [number, number, number];
  if (n <= fixMax && fix >= 0) {
    w.u8(fix | n);
  } else if (n < 256 && c8 !== 0) {
    w.u8(c8);
    w.u8(n);
  } else if (n < 65536) {
    w.u8(c16);
    w.u16(n);
  } else {
    w.u8(c32);
    w.u32(n);
  }
}

/** MessagePack timestamp extension (type -1) for a `Date` (ms precision). */
function writeTimestamp(w: Writer, date: Date): void {
  const ms = date.getTime();
  if (!Number.isFinite(ms)) throw new ValidationError("msgpack: invalid Date");
  const sec = Math.floor(ms / 1000);
  const nsec = (ms - sec * 1000) * 1_000_000;
  if (sec >= 0 && sec < 2 ** 34) {
    if (nsec === 0 && sec < 2 ** 32) {
      w.u8(0xd6);
      w.u8(0xff);
      w.u32(sec);
    } else {
      w.u8(0xd7);
      w.u8(0xff);
      w.u64((BigInt(nsec) << 34n) | BigInt(sec));
    }
  } else {
    w.u8(0xc7);
    w.u8(12);
    w.u8(0xff);
    w.u32(nsec);
    w.i64(BigInt(sec));
  }
}

function writeValue(w: Writer, value: unknown, depth: number): void {
  if (depth > 100) throw new ValidationError("msgpack: value nested too deeply");
  if (value === null || value === undefined) {
    w.u8(0xc0);
  } else if (typeof value === "boolean") {
    w.u8(value ? 0xc3 : 0xc2);
  } else if (typeof value === "number") {
    if (Number.isSafeInteger(value)) {
      writeInt(w, BigInt(value));
    } else {
      w.u8(0xcb);
      w.f64(value);
    }
  } else if (typeof value === "bigint") {
    writeInt(w, value);
  } else if (typeof value === "string") {
    const bytes = encoder.encode(value);
    writeLength(w, bytes.length, 0xa0, 31, [0xd9, 0xda, 0xdb]);
    w.bytes(bytes);
  } else if (value instanceof Date) {
    writeTimestamp(w, value);
  } else if (value instanceof Uint8Array) {
    writeLength(w, value.length, -1, -1, [0xc4, 0xc5, 0xc6]);
    w.bytes(value);
  } else if (Array.isArray(value)) {
    writeLength(w, value.length, 0x90, 15, [0, 0xdc, 0xdd]);
    for (const item of value) writeValue(w, item, depth + 1);
  } else if (typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>).filter(
      ([, v]) => v !== undefined && typeof v !== "function" && typeof v !== "symbol",
    );
    writeLength(w, entries.length, 0x80, 15, [0, 0xde, 0xdf]);
    for (const [k, v] of entries) {
      writeValue(w, k, depth + 1);
      writeValue(w, v, depth + 1);
    }
  } else {
    // functions and symbols have no MessagePack form (JSON drops them too)
    w.u8(0xc0);
  }
}

/** Encodes a value as MessagePack (`Date` → timestamp extension, `Uint8Array` → bin). */
export function encodeMsgpack(value: unknown): Uint8Array {
  const w = new Writer();
  writeValue(w, value, 0);
  return w.result();
}
