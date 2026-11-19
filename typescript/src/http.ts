// Service-owned: Better Stack specific HTTP plumbing on top of the generated transport.
// Ingest: 403 → AuthenticationError (DECISIONS 3), 402 → RateLimitError{retryable=false}
// (DECISIONS 2). SQL: ClickHouse exception codes win over the HTTP status (API.md §5.1).

import {
  AuthenticationError,
  BetterStackError,
  CancelledError,
  ErrorCode,
  type ErrorDetails,
  NotFoundError,
  PermissionDeniedError,
  RateLimitError,
  type RequestOptions,
  TimeoutError,
  type TransportResponse,
  type HttpTransport,
  UnsupportedError,
  ValidationError,
} from "./core/index.js";

/** Joins a base URL and an absolute path (`/v1/logs`). */
export function joinUrl(base: string, path: string): string {
  return `${base.replace(/\/+$/, "")}${path}`;
}

function detailsOf(err: BetterStackError, extra: ErrorDetails = {}): ErrorDetails {
  return {
    ...(err.httpStatus === undefined ? {} : { httpStatus: err.httpStatus }),
    ...(err.requestId === undefined ? {} : { requestId: err.requestId }),
    ...(err.retryAfterMs === undefined ? {} : { retryAfterMs: err.retryAfterMs }),
    cause: err.cause ?? undefined,
    ...extra,
  };
}

/**
 * Maps ingest-host errors: `403` (invalid or missing source token) → {@link AuthenticationError},
 * `402` (quota or spending limit) → non-retryable {@link RateLimitError}. Other errors pass through
 * (406/413 are already `ValidationError`, 429/5xx follow SPEC §2.1).
 */
export function mapIngestError(err: unknown): unknown {
  if (!(err instanceof BetterStackError)) return err;
  if (err.httpStatus === 403 && err.code === ErrorCode.PermissionDenied) {
    return new AuthenticationError(err.message, detailsOf(err));
  }
  if (err.httpStatus === 402) {
    return new RateLimitError(err.message, detailsOf(err, { retryable: false }));
  }
  return err;
}

const CODE_PATTERN = /Code:\s*(\d+)\.\s*DB::Exception/;

/** Extracts the ClickHouse exception code from an error text (`Code: 60. DB::Exception: …`). */
export function clickHouseCode(text: string): number | undefined {
  const m = CODE_PATTERN.exec(text);
  return m === null ? undefined : Number(m[1]);
}

const AUTH_CODES = new Set([516, 192, 193, 194]);
const NOT_FOUND_CODES = new Set([60, 81]);
const VALIDATION_CODES = new Set([62, 47, 46, 36, 53, 42, 73, 456, 457]);
const TIMEOUT_CODES = new Set([159, 160]);

/**
 * Builds the SDK error for a ClickHouse exception code (API.md §5.1), or `undefined` when the code
 * has no special mapping (the HTTP status mapping applies then).
 */
export function errorForClickHouseCode(
  code: number,
  message: string,
  details: ErrorDetails = {},
): BetterStackError | undefined {
  if (AUTH_CODES.has(code)) return new AuthenticationError(message, details);
  if (code === 497) return new PermissionDeniedError(message, details);
  if (NOT_FOUND_CODES.has(code)) return new NotFoundError(message, details);
  if (VALIDATION_CODES.has(code)) return new ValidationError(message, details);
  if (code === 241) {
    return new ValidationError(
      `${message} (hint: narrow the time range or sample the cold table)`,
      details,
    );
  }
  if (code === 48) return new UnsupportedError(message, details);
  if (TIMEOUT_CODES.has(code)) return new TimeoutError(message, details);
  if (code === 202) return new RateLimitError(message, { ...details, retryable: true });
  return undefined;
}

/** Maps SQL-host errors by their ClickHouse code (found in the message), else keeps the error. */
export function mapQueryError(err: unknown): unknown {
  if (!(err instanceof BetterStackError)) return err;
  const code = clickHouseCode(err.message);
  if (code === undefined) return err;
  const mapped = errorForClickHouseCode(code, err.message, detailsOf(err));
  return mapped ?? err;
}

/** `CancelledError` for a call on a closed client (the same code in all five languages). */
export function closedError(operation: string): CancelledError {
  return new CancelledError(`${operation}: client is closed`);
}

/**
 * Sends a request through the transport and applies `map` to failures. A closed transport raises
 * `CancelledError` (the generated transport would raise `ConfigError`).
 */
export async function mappedRequest<T>(
  transport: HttpTransport,
  options: RequestOptions,
  map: (err: unknown) => unknown,
): Promise<TransportResponse<T>> {
  if (transport.closed) throw closedError(options.operation ?? "request");
  try {
    return await transport.request<T>(options);
  } catch (err) {
    throw map(err);
  }
}
