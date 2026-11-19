//! Better Stack specific HTTP plumbing on top of the generated transport.
//!
//! Ingest: `403` → `AUTHENTICATION` (DECISIONS 3), `402` → non-retryable `RATE_LIMITED`
//! (DECISIONS 2). SQL: the ClickHouse exception code wins over the HTTP status (API.md §5.1).

use crate::core::{Error, ErrorCode};

const STD_ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
const URL_ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/// Base64 encoding: standard alphabet with padding, or (`url_safe`) the URL-safe alphabet
/// without padding.
pub(crate) fn base64_encode(data: &[u8], url_safe: bool) -> String {
    let alphabet = if url_safe { URL_ALPHABET } else { STD_ALPHABET };
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for chunk in data.chunks(3) {
        let b = [
            chunk[0],
            chunk.get(1).copied().unwrap_or(0),
            chunk.get(2).copied().unwrap_or(0),
        ];
        let n = (u32::from(b[0]) << 16) | (u32::from(b[1]) << 8) | u32::from(b[2]);
        for i in 0..4 {
            if i <= chunk.len() {
                out.push(alphabet[((n >> (18 - 6 * i)) & 63) as usize] as char);
            } else if !url_safe {
                out.push('=');
            }
        }
    }
    out
}

/// Decodes URL-safe base64 (padding optional). `None` when the input is not valid.
pub(crate) fn base64url_decode(text: &str) -> Option<Vec<u8>> {
    let text = text.trim_end_matches('=');
    let mut out = Vec::with_capacity(text.len() * 3 / 4);
    let mut acc: u32 = 0;
    let mut bits = 0;
    for c in text.bytes() {
        let v = URL_ALPHABET.iter().position(|&a| a == c)? as u32;
        acc = (acc << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    (bits < 6).then_some(out)
}

/// Maps ingest-host errors: `403` (invalid or missing source token) → `AUTHENTICATION`,
/// `402` (quota or spending limit) → `RATE_LIMITED` with `retryable = false`. Other errors pass
/// through (406/413 are already `VALIDATION`, 429/5xx follow SPEC §2.1).
pub fn map_ingest_error(err: Error) -> Error {
    match err.http_status() {
        Some(403) => remap(err, ErrorCode::Authentication).with_retryable(false),
        Some(402) => remap(err, ErrorCode::RateLimited).with_retryable(false),
        _ => err,
    }
}

/// Rebuilds `err` with another code, keeping message, status, request id and retry-after.
fn remap(err: Error, code: ErrorCode) -> Error {
    let mut out = Error::new(code, err.message());
    if let Some(s) = err.http_status() {
        out = out.with_status(s);
    }
    if let Some(id) = err.request_id() {
        out = out.with_request_id(id);
    }
    if let Some(ra) = err.retry_after() {
        out = out.with_retry_after(ra);
    }
    out.with_source(err)
}

/// Extracts the ClickHouse exception code from an error text (`Code: 60. DB::Exception: …`).
pub fn clickhouse_code(text: &str) -> Option<u32> {
    let mut rest = text;
    while let Some(i) = rest.find("Code:") {
        let after = rest[i + 5..].trim_start();
        let digits: String = after.chars().take_while(char::is_ascii_digit).collect();
        if !digits.is_empty() {
            let tail = after[digits.len()..].strip_prefix('.').map(str::trim_start);
            if tail.is_some_and(|t| t.starts_with("DB::Exception")) {
                return digits.parse().ok();
            }
        }
        rest = &rest[i + 5..];
    }
    None
}

/// The SDK error code for a ClickHouse exception code (API.md §5.1), or `None` when the code
/// has no special mapping (the HTTP status mapping applies then).
pub fn code_for_clickhouse(code: u32) -> Option<ErrorCode> {
    Some(match code {
        516 | 192 | 193 | 194 => ErrorCode::Authentication,
        497 => ErrorCode::PermissionDenied,
        60 | 81 => ErrorCode::NotFound,
        62 | 47 | 46 | 36 | 53 | 42 | 73 | 456 | 457 | 241 => ErrorCode::Validation,
        48 => ErrorCode::Unsupported,
        159 | 160 => ErrorCode::Timeout,
        202 => ErrorCode::RateLimited,
        _ => return None,
    })
}

/// Builds the SDK error for a ClickHouse exception code, or `None` without a special mapping.
/// `241` (memory limit) gets a hint; `202` (too many simultaneous queries) is retryable.
pub fn error_for_clickhouse(code: u32, message: &str) -> Option<Error> {
    let kind = code_for_clickhouse(code)?;
    let message = if code == 241 {
        format!("{message} (hint: narrow the time range or sample the cold table)")
    } else {
        message.to_owned()
    };
    let err = Error::new(kind, message);
    Some(if code == 202 {
        err.with_retryable(true)
    } else {
        err
    })
}

/// Maps SQL-host errors by their ClickHouse code (found in the message), else keeps the error.
pub fn map_query_error(err: Error) -> Error {
    let Some(code) = clickhouse_code(err.message()) else {
        return err;
    };
    let Some(mapped) = error_for_clickhouse(code, err.message()) else {
        return err;
    };
    if mapped.code() == err.code() && code != 241 {
        return err;
    }
    let retryable = mapped.is_retryable();
    remap(err, mapped.code())
        .with_message(mapped.message())
        .with_retryable(retryable)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base64_round_trip() {
        assert_eq!(base64_encode(b"user:pass", false), "dXNlcjpwYXNz");
        assert_eq!(base64_encode(b"ab", false), "YWI=");
        assert_eq!(base64_encode(b"a", false), "YQ==");
        assert_eq!(base64_encode(b"a", true), "YQ");
        for s in [&b""[..], b"a", b"ab", b"abc", b"\xff\xfe\xfd\x00"] {
            assert_eq!(base64url_decode(&base64_encode(s, true)).unwrap(), s);
        }
        assert!(base64url_decode("!!").is_none());
        assert!(base64url_decode("A").is_none());
    }

    #[test]
    fn clickhouse_codes() {
        assert_eq!(clickhouse_code("x: Code: 60. DB::Exception: no"), Some(60));
        assert_eq!(clickhouse_code("Code: x Code: 62. DB::Exception"), Some(62));
        assert_eq!(clickhouse_code("Code: 62 nope"), None);
        assert_eq!(clickhouse_code("nothing"), None);
        assert_eq!(code_for_clickhouse(1), None);
        assert!(error_for_clickhouse(241, "m")
            .unwrap()
            .message()
            .contains("hint"));
        assert!(error_for_clickhouse(202, "m").unwrap().is_retryable());
        assert!(error_for_clickhouse(999, "m").is_none());
    }
}
