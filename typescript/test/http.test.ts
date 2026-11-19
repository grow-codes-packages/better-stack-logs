import { describe, expect, it } from "vitest";
import {
  AuthenticationError,
  NotFoundError,
  PermissionDeniedError,
  RateLimitError,
  UnavailableError,
  clickHouseCode,
  errorForClickHouseCode,
  joinUrl,
  mapIngestError,
  mapQueryError,
} from "../src/index.js";

describe("http helpers", () => {
  it("joinUrl strips trailing slashes", () => {
    expect(joinUrl("http://h:1//", "/v1/logs")).toBe("http://h:1/v1/logs");
  });

  it("mapIngestError: 403 → Authentication, 402 → non-retryable RateLimit, others unchanged", () => {
    const denied = new PermissionDeniedError("logs.send: Unauthorized (HTTP 403)", {
      httpStatus: 403,
      requestId: "r1",
    });
    const auth = mapIngestError(denied);
    expect(auth).toBeInstanceOf(AuthenticationError);
    expect(auth).toMatchObject({ httpStatus: 403, requestId: "r1", retryable: false });
    const quota = mapIngestError(
      new UnavailableError("logs.send: Quota exceeded (HTTP 402)", {
        httpStatus: 402,
        retryAfterMs: 5,
      }),
    );
    expect(quota).toBeInstanceOf(RateLimitError);
    expect(quota).toMatchObject({ retryable: false, retryAfterMs: 5 });
    const other = new NotFoundError("x", { httpStatus: 404 });
    expect(mapIngestError(other)).toBe(other);
    const plain = new Error("plain");
    expect(mapIngestError(plain)).toBe(plain);
  });

  it("mapQueryError and clickHouseCode", () => {
    expect(clickHouseCode("Code: 60. DB::Exception: Table x does not exist")).toBe(60);
    expect(clickHouseCode("no code here")).toBeUndefined();
    const e = new UnavailableError("query: Code: 516. DB::Exception: auth (HTTP 500)", {
      httpStatus: 500,
    });
    expect(mapQueryError(e)).toBeInstanceOf(AuthenticationError);
    const unmapped = new UnavailableError("query: Code: 9999. DB::Exception: ?", {
      httpStatus: 500,
    });
    expect(mapQueryError(unmapped)).toBe(unmapped);
    const noCode = new UnavailableError("query: boom", { httpStatus: 502 });
    expect(mapQueryError(noCode)).toBe(noCode);
    const plain = new Error("plain");
    expect(mapQueryError(plain)).toBe(plain);
    expect(errorForClickHouseCode(202, "busy")).toMatchObject({
      code: "RATE_LIMITED",
      retryable: true,
    });
    expect(errorForClickHouseCode(1, "x")).toBeUndefined();
  });
});
