//! Service test helpers: recorded stub fixtures and mock servers for both hosts.
#![allow(dead_code)]

use std::time::Duration;

use grow_better_stack_logs as sdk;
use sdk::core::{RandomSource, RetryPolicy};
use sdk::{BetterStackClient, BetterStackConfig, BetterStackConfigBuilder};
use serde_json::Value;
use wiremock::{MockServer, Request, ResponseTemplate};

/// Source token used by the mocks.
pub const TOKEN: &str = "src-token-1234";
/// Query password used by the mocks.
pub const PASSWORD: &str = "query-pass-1234";
/// `Basic base64(user:query-pass-1234)`.
pub fn basic() -> String {
    "Basic dXNlcjpxdWVyeS1wYXNzLTEyMzQ=".to_owned()
}

/// Fast deterministic retries.
pub fn fast_retry(max: u32) -> RetryPolicy {
    RetryPolicy {
        max_retries: max,
        initial_backoff: Duration::from_millis(5),
        max_backoff: Duration::from_millis(20),
        ..RetryPolicy::default()
    }
    .with_random(RandomSource::fixed(0.0))
}

/// Loads a recorded fixture (`tests/fixtures/betterstack/<name>.json`).
pub fn fixture(name: &str) -> Value {
    let path = format!(
        "{}/tests/fixtures/betterstack/{name}.json",
        env!("CARGO_MANIFEST_DIR")
    );
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

/// The fixture's response as a mock response (status, ClickHouse headers, body).
pub fn fixture_response(name: &str) -> ResponseTemplate {
    let f = fixture(name);
    let r = &f["response"];
    let status = r["status"].as_u64().unwrap() as u16;
    let body = match &r["body"] {
        Value::Null => String::new(),
        Value::String(s) => s.clone(),
        other => format!("{other}\n"),
    };
    let mut t = ResponseTemplate::new(status).set_body_string(body);
    if let Some(Value::Object(h)) = r.get("headers") {
        for (k, v) in h {
            if k.starts_with("x-clickhouse") || k == "content-type" {
                t = t.insert_header(k.as_str(), v.as_str().unwrap());
            }
        }
    }
    t
}

/// Builder for a client with both sides pointing at the given mocks.
pub fn builder(
    ingest: Option<&MockServer>,
    query: Option<&MockServer>,
) -> BetterStackConfigBuilder {
    let mut b = BetterStackConfig::builder().retry(fast_retry(0));
    if let Some(s) = ingest {
        b = b.url(s.uri()).source_token(TOKEN);
    }
    if let Some(s) = query {
        b = b
            .query_url(s.uri())
            .query_username("user")
            .query_password(PASSWORD)
            .source_table("t1_app");
    }
    b
}

/// Client with both sides pointing at the given mocks.
pub fn client(ingest: Option<&MockServer>, query: Option<&MockServer>) -> BetterStackClient {
    BetterStackClient::new(builder(ingest, query).build().unwrap()).unwrap()
}

/// Header value of a recorded request.
pub fn header(req: &Request, name: &str) -> Option<String> {
    req.headers
        .get(name)
        .and_then(|v| v.to_str().ok())
        .map(str::to_owned)
}

/// Body of a recorded request, gunzipped when needed.
pub fn body(req: &Request) -> Vec<u8> {
    if header(req, "content-encoding").as_deref() == Some("gzip") {
        use std::io::Read;
        let mut out = Vec::new();
        flate2::read::GzDecoder::new(&req.body[..])
            .read_to_end(&mut out)
            .unwrap();
        out
    } else {
        req.body.clone()
    }
}

/// A URL parameter of a recorded request.
pub fn param(req: &Request, name: &str) -> Option<String> {
    req.url
        .query_pairs()
        .find(|(k, _)| k == name)
        .map(|(_, v)| v.into_owned())
}
