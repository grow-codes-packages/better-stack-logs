// Taken over from sdk-kit 0.1.0: blocking client against Better Stack mocks (GRO-1377).
//! Blocking client (feature `blocking`).
#![cfg(feature = "blocking")]

mod support;

use std::time::Duration;

use grow_better_stack_logs as sdk;
use sdk::blocking::BetterStackClient as Client;
use sdk::core::ErrorCode;
use sdk::logging::{attrs, LogEntry, SearchOptions, WaitOptions};
use sdk::{
    BetterStackConfig as Config, LogClientOptions, QueryOptions, QueryParams, SearchLogsOptions,
};
use support::*;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer};

#[test]
fn blocking_client() {
    let rt = tokio::runtime::Runtime::new().unwrap();
    let (ingest, query) = rt.block_on(async {
        let ingest = MockServer::start().await;
        Mock::given(method("POST"))
            .respond_with(fixture_response("send.json_single"))
            .mount(&ingest)
            .await;
        let query = MockServer::start().await;
        Mock::given(path("/ping"))
            .respond_with(fixture_response("health.query_ping"))
            .mount(&query)
            .await;
        Mock::given(method("POST"))
            .respond_with(fixture_response("waitForLog.hit"))
            .mount(&query)
            .await;
        (ingest, query)
    });
    let c = Client::new(builder(Some(&ingest), Some(&query)).build().unwrap()).unwrap();
    assert!(c.health().unwrap().healthy);
    c.ping().unwrap();
    assert_eq!(
        c.server_version().unwrap_err().code(),
        ErrorCode::Unsupported
    );
    assert!(c.config().has_ingest());
    let rows = c
        .query(
            "SELECT dt, raw FROM remote(t1_app_logs)",
            &QueryParams::new(),
        )
        .unwrap();
    assert_eq!(rows.len(), 1);
    assert!(c
        .query_raw("SELECT 1", &QueryParams::new(), &QueryOptions::default())
        .unwrap()
        .contains("wait-for-log"));
    assert_eq!(
        c.search_logs(SearchLogsOptions::default())
            .unwrap()
            .records
            .len(),
        1
    );
    assert_eq!(
        c.search("wait", SearchOptions::default())
            .unwrap()
            .records
            .len(),
        1
    );
    c.wait_for_log(
        "wait",
        WaitOptions::default().timeout(Duration::from_secs(2)),
    )
    .unwrap();
    assert_eq!(c.list_streams().unwrap(), vec!["t1_app".to_owned()]);

    let lc = c.log_client(LogClientOptions::default()).unwrap();
    lc.send("one");
    lc.send_batch(vec![LogEntry::from("two")]);
    lc.logger(None, attrs! {}).warn("three", attrs! {});
    lc.flush(Some(Duration::from_secs(5))).unwrap();
    assert_eq!(lc.stats().sent, 3);
    assert_eq!(sdk::logging::LogClient::stats(lc.async_client()).sent, 3);
    lc.close().unwrap();
    assert!(!c.async_client().is_closed());
    assert_eq!(c.block_on(async { 7 }), 7);
    c.close();
    c.close();
    assert_eq!(c.ping().unwrap_err().code(), ErrorCode::Cancelled);
    let _ = Client::from_env();
    let bad = Config::builder()
        .url("http://h")
        .source_token("t-1234")
        .ca_cert_path("/nonexistent")
        .build()
        .unwrap();
    assert_eq!(Client::new(bad).unwrap_err().code(), ErrorCode::Config);
    // query-side methods without query credentials
    let ingest_only = Client::new(builder(Some(&ingest), None).build().unwrap()).unwrap();
    assert_eq!(
        ingest_only.list_streams().unwrap_err().code(),
        ErrorCode::Config
    );
    drop(ingest_only);
    drop(rt);
}
