// Created by sdk-kit 0.1.0; owned by this repository.
//! Helpers for the integration tests: bootstrap credentials and the conformance directory.

use std::path::PathBuf;

use grow_better_stack_logs as sdk;

/// Loads `KEY=value` lines from `$GROW_BOOTSTRAP_ENV` (default `/bootstrap/env`) into the
/// process environment; variables that are already set win. A missing file is not an error.
pub fn load_bootstrap_env() {
    let path = std::env::var("GROW_BOOTSTRAP_ENV").unwrap_or_else(|_| "/bootstrap/env".to_owned());
    let Ok(text) = std::fs::read_to_string(path) else {
        return;
    };
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some((key, value)) = line.split_once('=') {
            let key = key.trim().trim_start_matches("export ").trim();
            let value = value.trim().trim_matches('"');
            if !key.is_empty() && std::env::var_os(key).is_none() {
                std::env::set_var(key, value);
            }
        }
    }
}

/// `$GROW_CONFORMANCE_DIR`, else `<repo>/conformance/scenarios`.
pub fn conformance_dir() -> PathBuf {
    sdk::testing::scenarios_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/.."))
}

/// A run id unique per test call (`rs-<pid>-<nanos>-<n>`).
pub fn run_id() -> String {
    use std::sync::atomic::{AtomicU32, Ordering};
    static N: AtomicU32 = AtomicU32::new(0);
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(0);
    format!(
        "rs-{}-{nanos:x}-{}",
        std::process::id(),
        N.fetch_add(1, Ordering::SeqCst)
    )
}

/// Arms a stub fault (stub-only `/__stub/fault` control, not part of the real API). Returns
/// `false` when no control URL is configured.
pub async fn inject_fault(target: &str, status: u16, body: &str) -> bool {
    let var = if target == "query" {
        "BETTERSTACK_STUB_QUERY_CONTROL_URL"
    } else {
        "BETTERSTACK_STUB_INGEST_CONTROL_URL"
    };
    let Ok(url) = std::env::var(var) else {
        return false;
    };
    let config = sdk::core::CommonConfig::builder()
        .url(url.clone())
        .build(&url)
        .expect("control url");
    let transport = sdk::core::HttpTransport::new(&config).expect("transport");
    transport
        .post("fault")
        .json(&serde_json::json!({"status": status, "count": 1, "target": target, "body": body}))
        .send()
        .await
        .expect("fault control");
    true
}
