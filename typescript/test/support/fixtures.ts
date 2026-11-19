// Recorded Better Stack stub fixtures (test/fixtures/*.json) and a raw-body mock server.

import { readFileSync } from "node:fs";
import { createServer, type IncomingMessage, type Server } from "node:http";
import type { AddressInfo, Socket } from "node:net";
import { gunzipSync } from "node:zlib";

/** One recorded exchange. */
export interface Fixture {
  fixture: string;
  request: {
    method: string;
    host: string;
    path: string;
    headers: Record<string, string>;
    body: unknown;
  };
  response: { status: number; headers: Record<string, string>; body: unknown };
}

/** Loads `test/fixtures/<name>.json`. */
export function fixture(name: string): Fixture {
  return JSON.parse(
    readFileSync(new URL(`../fixtures/${name}.json`, import.meta.url), "utf8"),
  ) as Fixture;
}

/** A canned response. */
export interface Reply {
  status?: number;
  headers?: Record<string, string>;
  body?: string | Uint8Array;
  delayMs?: number;
}

/** Turns a fixture's recorded response into a {@link Reply}. */
export function replyFrom(name: string): Reply {
  const f = fixture(name);
  const body = f.response.body;
  const headers = { ...f.response.headers };
  delete headers["content-length"];
  return {
    status: f.response.status,
    headers,
    body:
      body === null || body === undefined
        ? ""
        : typeof body === "string"
          ? body
          : JSON.stringify(body),
  };
}

/** One request seen by the server (body as raw bytes, gunzipped when gzip). */
export interface SeenRequest {
  method: string;
  url: string;
  path: string;
  query: URLSearchParams;
  headers: IncomingMessage["headers"];
  raw: Buffer;
  body: Buffer;
  text: string;
}

/** A local HTTP server answering with `handler`. */
export interface RawServer {
  url: string;
  requests: SeenRequest[];
  close(): Promise<void>;
}

/** Starts a local server; the handler may be a list of replies (the last one repeats). */
export async function rawServer(
  handler: ((req: SeenRequest, index: number) => Reply | Promise<Reply>) | Reply[],
): Promise<RawServer> {
  const requests: SeenRequest[] = [];
  const sockets = new Set<Socket>();
  const pick = Array.isArray(handler)
    ? (_r: SeenRequest, i: number): Reply => handler[Math.min(i, handler.length - 1)] ?? {}
    : handler;
  const server: Server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => chunks.push(c));
    req.on("end", () => {
      const raw = Buffer.concat(chunks);
      const body = req.headers["content-encoding"] === "gzip" ? gunzipSync(raw) : raw;
      const u = new URL(req.url ?? "/", "http://x");
      const seen: SeenRequest = {
        method: req.method ?? "",
        url: req.url ?? "",
        path: u.pathname,
        query: u.searchParams,
        headers: req.headers,
        raw,
        body,
        text: body.toString("utf8"),
      };
      const index = requests.length;
      requests.push(seen);
      void Promise.resolve(pick(seen, index)).then((r) => {
        const finish = (): void => {
          res.writeHead(r.status ?? 200, r.headers ?? {});
          res.end(r.body ?? "");
        };
        if (r.delayMs !== undefined) setTimeout(finish, r.delayMs);
        else finish();
      });
    });
  });
  server.on("connection", (s) => {
    sockets.add(s);
    s.on("close", () => sockets.delete(s));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as AddressInfo).port;
  return {
    url: `http://127.0.0.1:${port}`,
    requests,
    close: () =>
      new Promise<void>((resolve) => {
        for (const s of sockets) s.destroy();
        server.close(() => resolve());
      }),
  };
}

/** Minimal MessagePack decoder for assertions (timestamp ext → Date). */
export function decodeMsgpack(buf: Uint8Array): unknown {
  const view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  let pos = 0;
  const str = (n: number): string => {
    const s = new TextDecoder().decode(buf.subarray(pos, pos + n));
    pos += n;
    return s;
  };
  const ext = (type: number, n: number): unknown => {
    const start = pos;
    pos += n;
    if (type !== 0xff) return { type, data: buf.subarray(start, start + n) };
    if (n === 4) return new Date(view.getUint32(start) * 1000);
    if (n === 8) {
      const v = view.getBigUint64(start);
      const nsec = Number(v >> 34n);
      const sec = Number(v & 0x3ffffffffn);
      return new Date(sec * 1000 + nsec / 1e6);
    }
    const nsec = view.getUint32(start);
    const sec = Number(view.getBigInt64(start + 4));
    return new Date(sec * 1000 + nsec / 1e6);
  };
  const arr = (n: number): unknown[] => Array.from({ length: n }, () => read());
  const map = (n: number): Record<string, unknown> => {
    const out: Record<string, unknown> = {};
    for (let i = 0; i < n; i++) {
      const k = read() as string;
      out[k] = read();
    }
    return out;
  };
  function read(): unknown {
    const b = buf[pos++] as number;
    if (b < 0x80) return b;
    if (b >= 0xe0) return b - 0x100;
    if ((b & 0xf0) === 0x80) return map(b & 0x0f);
    if ((b & 0xf0) === 0x90) return arr(b & 0x0f);
    if ((b & 0xe0) === 0xa0) return str(b & 0x1f);
    const at = pos;
    switch (b) {
      case 0xc0:
        return null;
      case 0xc2:
        return false;
      case 0xc3:
        return true;
      case 0xc4:
        pos += 1;
        return buf.slice(pos, (pos += view.getUint8(at)));
      case 0xc5:
        pos += 2;
        return buf.slice(pos, (pos += view.getUint16(at)));
      case 0xc6:
        pos += 4;
        return buf.slice(pos, (pos += view.getUint32(at)));
      case 0xc7: {
        const n = view.getUint8(at);
        const t = view.getUint8(at + 1);
        pos += 2;
        return ext(t, n);
      }
      case 0xcb:
        pos += 8;
        return view.getFloat64(at);
      case 0xcc:
        pos += 1;
        return view.getUint8(at);
      case 0xcd:
        pos += 2;
        return view.getUint16(at);
      case 0xce:
        pos += 4;
        return view.getUint32(at);
      case 0xcf:
        pos += 8;
        return view.getBigUint64(at);
      case 0xd0:
        pos += 1;
        return view.getInt8(at);
      case 0xd1:
        pos += 2;
        return view.getInt16(at);
      case 0xd2:
        pos += 4;
        return view.getInt32(at);
      case 0xd3:
        pos += 8;
        return view.getBigInt64(at);
      case 0xd6:
        pos += 1;
        return ext(view.getUint8(at), 4);
      case 0xd7:
        pos += 1;
        return ext(view.getUint8(at), 8);
      case 0xd9:
        pos += 1;
        return str(view.getUint8(at));
      case 0xda:
        pos += 2;
        return str(view.getUint16(at));
      case 0xdb:
        pos += 4;
        return str(view.getUint32(at));
      case 0xdc:
        pos += 2;
        return arr(view.getUint16(at));
      case 0xdd:
        pos += 4;
        return arr(view.getUint32(at));
      case 0xde:
        pos += 2;
        return map(view.getUint16(at));
      case 0xdf:
        pos += 4;
        return map(view.getUint32(at));
      default:
        throw new Error(`unsupported msgpack byte 0x${b.toString(16)}`);
    }
  }
  return read();
}
