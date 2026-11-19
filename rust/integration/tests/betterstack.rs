//! Integration suite against the Better Stack stub (docker/compose.yml): every public function
//! is called at least once with a write → read-back assertion where the service allows it.

use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};

use grow_better_stack_logs as sdk;
use grow_better_stack_logs_integration as support;
use sdk::logging::{
    attrs, BatchOptions, ExportOptions, LogBridge, LogClient, LogEntry, LogExporter, LogInput,
    LogLayer, LogQueryClient, LogRecord, SearchOptions, Severity, SortOrder, TailOptions,
    WaitOptions,
};
use sdk::{
    query_params, BetterStackClient as Client, BetterStackConfig as Config, Encoding, ErrorCode,
    IngestExporterOptions, LogClientOptions, OtlpExporterOptions, OtlpProtocol, QueryOptions,
    QueryParams, SearchLogsOptions, SendEventsOptions,
};
use serde_json::json;
use support::{inject_fault, load_bootstrap_env, run_id};
use tracing_subscriber::prelude::*;

fn ready() -> bool {
    load_bootstrap_env();
    let ok = std::env::var("GROW_BETTER_STACK_LOGS_URL").is_ok();
    if !ok {
        eprintln!("GROW_BETTER_STACK_LOGS_URL is not set; run through docker/compose.yml");
    }
    ok
}

fn client_with(
    f: impl FnOnce(sdk::BetterStackConfigBuilder) -> sdk::BetterStackConfigBuilder,
) -> Client {
    Client::new(f(Config::builder()).build_from_env().expect("config")).expect("client")
}

fn client() -> Client {
    client_with(|b| b)
}

/// Polls `search_logs` until at least `n` rows contain `needle`.
async fn wait_rows(c: &Client, source: Option<&str>, needle: &str, n: usize) -> Vec<LogRecord> {
    let q = c.query().unwrap();
    let deadline = std::time::Instant::now() + Duration::from_secs(30);
    loop {
        let mut opts = SearchLogsOptions::default()
            .contains(needle)
            .order(SortOrder::Asc)
            .limit(1000);
        opts.source = source.map(str::to_owned);
        let res = q.search_logs(opts).await.unwrap();
        if res.records.len() >= n {
            return res.records;
        }
        assert!(
            std::time::Instant::now() < deadline,
            "found {} of {n} rows for {needle:?}",
            res.records.len()
        );
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
}

#[tokio::test]
async fn core_surface() {
    if !ready() {
        return;
    }
    let c = client();
    let cfg = c.config();
    assert!(cfg.has_ingest() && cfg.has_query() && cfg.source_table().is_some());
    let dbg = format!("{cfg:?}");
    assert!(!dbg.contains(cfg.source_token().unwrap().expose_secret()));
    assert!(!dbg.contains(cfg.query_password().unwrap().expose_secret()));
    let h = c.health().await.unwrap();
    assert!(h.healthy && h.status == "ok", "{h:?}");
    assert_eq!(h.details["endpoint"], "query");
    assert!(c.ping().await.unwrap() > Duration::ZERO);
    assert_eq!(
        c.server_version().await.unwrap_err().code(),
        ErrorCode::Unsupported
    );

    // ingest-only client: health = reachability, ping = empty batch (nothing stored)
    let ingest_only = Client::new(
        Config::builder()
            .url(cfg.url().unwrap().as_str())
            .source_token(cfg.source_token().unwrap().clone())
            .build()
            .unwrap(),
    )
    .unwrap();
    let h = ingest_only.health().await.unwrap();
    assert!(h.healthy && h.details["endpoint"] == "ingest", "{h:?}");
    ingest_only.ping().await.unwrap();
    ingest_only.close().await;
    if let Ok(host) = std::env::var("BETTERSTACK_INGESTING_HOST") {
        assert_eq!(
            sdk::normalize_base_url(&host),
            Some(format!("https://{host}"))
        );
    }

    c.close().await;
    c.close().await;
    assert!(c.is_closed());
    assert_eq!(c.ping().await.unwrap_err().code(), ErrorCode::Cancelled);
}

#[tokio::test]
async fn auth_errors() {
    if !ready() {
        return;
    }
    let bad = client_with(|b| {
        b.source_token("wrong-source-token")
            .query_password("wrong-query-password")
    });
    let err = bad.ping().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Authentication, "{err}");
    assert!(!err.to_string().contains("wrong-query-password"));
    let err = bad
        .exporter()
        .unwrap()
        .export(
            &[LogRecord::new(Severity::Info, "x")],
            ExportOptions::new(false),
        )
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Authentication, "{err}");
    assert!(!err.to_string().contains("wrong-source-token"));
    bad.close().await;
}

#[tokio::test]
async fn ingest_encodings_round_trip() {
    if !ready() {
        return;
    }
    let run = run_id();
    for enc in Encoding::ALL {
        for gz in [false, true] {
            let name = format!("{enc}{}", if gz { "-gzip" } else { "" });
            let c = client_with(|b| b.encoding(enc));
            let lc = c
                .log_client(
                    LogClientOptions::default()
                        .batch(BatchOptions::default().gzip(gz).max_batch_size(2))
                        .resource_attribute("service.name", "grow-it-rust"),
                )
                .unwrap();
            let needle = format!("{run}-{name}");
            let ts = SystemTime::UNIX_EPOCH + Duration::new(1_767_323_045, 678_901_000);
            lc.send(LogEntry::Input(
                LogInput::new(format!("{needle} first"))
                    .severity(Severity::Warn)
                    .timestamp(ts)
                    .attribute("n", 1)
                    .attribute("unicode", "Grüße ✓")
                    .attribute("token", "secret-value")
                    .trace("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7"),
            ));
            lc.send_batch(vec![
                LogEntry::Input(
                    LogInput::new(format!("{needle} second"))
                        .timestamp(ts + Duration::from_secs(1)),
                ),
                LogEntry::Input(
                    LogInput::new(format!("{needle} third")).timestamp(ts + Duration::from_secs(2)),
                ),
            ]);
            lc.flush(None).await.unwrap();
            let st = lc.stats();
            assert_eq!((st.sent, st.failed), (3, 0), "{name}: {st:?}");
            let res = c
                .query()
                .unwrap()
                .search_logs(
                    SearchLogsOptions::default()
                        .contains(&needle)
                        .range(
                            Some(ts - Duration::from_secs(60)),
                            Some(ts + Duration::from_secs(60)),
                        )
                        .order(SortOrder::Asc),
                )
                .await
                .unwrap();
            assert_eq!(res.records.len(), 3, "{name}");
            let r = &res.records[0];
            assert_eq!(r.body, format!("{needle} first"));
            assert_eq!(r.severity, Severity::Warn);
            assert_eq!(
                r.trace_id.as_deref(),
                Some("4bf92f3577b34da6a3ce929d0e0e4736")
            );
            assert_eq!(r.span_id.as_deref(), Some("00f067aa0ba902b7"));
            assert_eq!(r.attributes["unicode"], "Grüße ✓");
            assert_ne!(r.attributes["token"], "secret-value");
            assert_eq!(r.resource["service.name"], "grow-it-rust");
            assert_eq!(r.timestamp, ts, "{name}");
            c.close().await;
        }
    }
}

#[tokio::test]
async fn exporters_and_otlp() {
    if !ready() {
        return;
    }
    let run = run_id();
    let c = client();
    let mut rec = LogRecord::new(Severity::Info, format!("{run} direct"));
    rec.attributes.insert("k".into(), "v".into());
    let res = c
        .exporter()
        .unwrap()
        .export(&[rec], ExportOptions::new(true))
        .await
        .unwrap();
    assert_eq!(res.accepted, 1);
    wait_rows(&c, None, &format!("{run} direct"), 1).await;

    // dt as UNIX milliseconds (search windows default to the last 24 h)
    let now_ms = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64;
    c.exporter()
        .unwrap()
        .send_events(
            &[json!({"message": format!("{run} raw event"), "dt": now_ms, "custom": {"k": "v"}})],
            SendEventsOptions::default(),
        )
        .await
        .unwrap();
    let raw = wait_rows(&c, None, &format!("{run} raw event"), 1).await;
    assert_eq!(raw[0].attributes["custom"]["k"], "v");

    let mp = c
        .new_ingest_exporter(
            IngestExporterOptions::default()
                .encoding(Encoding::Msgpack)
                .max_request_bytes(1 << 20),
        )
        .unwrap();
    let mlc = c
        .log_client_with(Arc::new(mp), LogClientOptions::default())
        .unwrap();
    mlc.send(format!("{run} custom exporter").into());
    mlc.flush(None).await.unwrap();
    wait_rows(&c, None, &format!("{run} custom exporter"), 1).await;

    let otlp = c
        .log_client(
            LogClientOptions::default()
                .resource_attribute("service.name", "grow-it-rust-otlp")
                .otlp(OtlpExporterOptions::default()),
        )
        .unwrap();
    otlp.logger(Some("otlp"), attrs! {})
        .error(format!("{run} via otlp"), attrs! { "k" => "v" });
    otlp.flush(None).await.unwrap();
    assert_eq!(otlp.stats().sent, 1);
    let rows = wait_rows(&c, None, &format!("{run} via otlp"), 1).await;
    assert_eq!(rows[0].body, format!("{run} via otlp"));
    assert_eq!(rows[0].severity, Severity::Error);

    let otlp_json = c
        .new_otlp_exporter(OtlpExporterOptions {
            protocol: OtlpProtocol::Json,
            ..Default::default()
        })
        .unwrap();
    let res = otlp_json
        .export(
            &[LogRecord::new(Severity::Warn, format!("{run} otlp json"))],
            ExportOptions::new(false),
        )
        .await
        .unwrap();
    assert_eq!(res.accepted, 1);
    let rows = wait_rows(&c, None, &format!("{run} otlp json"), 1).await;
    assert_eq!(rows[0].severity, Severity::Warn);
    let res = c
        .otlp_exporter()
        .unwrap()
        .export(
            &[LogRecord::new(Severity::Info, format!("{run} otlp direct"))],
            ExportOptions::default(),
        )
        .await
        .unwrap();
    assert_eq!(res.accepted, 1);
    wait_rows(&c, None, &format!("{run} otlp direct"), 1).await;
    c.close().await;
}

#[tokio::test]
async fn query_and_search() {
    if !ready() {
        return;
    }
    let run = run_id();
    let c = client();
    let q = c.query().unwrap();
    let table = c.config().source_table().unwrap().to_owned();
    let lc = c.log_client(LogClientOptions::default()).unwrap();
    let base = SystemTime::now() - Duration::from_secs(60);
    for (i, ch) in "abcde".chars().enumerate() {
        lc.send(LogEntry::Input(
            LogInput::new(format!("{run} it's \\ #{ch}"))
                .timestamp(base + Duration::from_secs(i as u64)),
        ));
    }
    lc.flush(None).await.unwrap();

    // query(sql, params): placeholders are bound server side
    let rows = q
        .query(
            "SELECT count() AS c FROM remote({table:Identifier}) WHERE position(raw, {needle:String}) > 0 AND dt >= {since:DateTime64(6)}",
            &query_params! {
                "table" => format!("{table}_logs"),
                "needle" => format!("{run} it's \\\\"),
                "since" => base - Duration::from_millis(1),
            },
            &QueryOptions::default(),
        )
        .await
        .unwrap();
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0]["c"], "5", "{rows:?}");
    let rows = q
        .query(
            &format!("SELECT JSONExtractString(raw, 'message') AS m FROM remote({table}_logs) WHERE position(raw, '{run}') > 0 ORDER BY dt ASC LIMIT 1 FORMAT JSON"),
            &QueryParams::new(),
            &QueryOptions::default(),
        )
        .await
        .unwrap();
    assert_eq!(rows[0]["m"], format!("{run} it's \\ #a"));
    let raw = q
        .query_raw(
            "SELECT count() AS c FROM remote({table:Identifier}) WHERE position(raw, {needle:String}) > 0 FORMAT JSON",
            &query_params! { "table" => format!("{table}_logs"), "needle" => run.clone() },
            &QueryOptions::default().setting("output_format_pretty_row_numbers", 0),
        )
        .await
        .unwrap();
    let doc: serde_json::Value = serde_json::from_str(&raw).unwrap();
    assert_eq!(doc["data"][0]["c"], "5");

    // search_logs: window, order, limit, cursor
    let page = q
        .search_logs(
            SearchLogsOptions::default()
                .contains(format!("{run} it's \\"))
                .range(Some(base), Some(base + Duration::from_secs(10)))
                .limit(2)
                .order(SortOrder::Asc),
        )
        .await
        .unwrap();
    assert_eq!(page.records.len(), 2);
    assert!(page.records[1].body.ends_with("#b"));
    let page2 = q
        .search_logs(
            SearchLogsOptions::default()
                .contains(&run)
                .range(Some(base), Some(base + Duration::from_secs(10)))
                .limit(2)
                .order(SortOrder::Asc)
                .cursor(page.next_cursor.clone().unwrap()),
        )
        .await
        .unwrap();
    assert!(page2.records[0].body.ends_with("#c"), "{:?}", page2.records);
    let desc = q
        .search_logs(
            SearchLogsOptions::default()
                .contains(&run)
                .range(Some(base - Duration::from_secs(1)), None),
        )
        .await
        .unwrap();
    assert_eq!(desc.records.len(), 5);
    assert!(desc.next_cursor.is_none());
    assert!(desc.records[0].body.ends_with("#e"));
    let with = q
        .search_logs_with(
            SearchLogsOptions::default().contains(&run).limit(5),
            &QueryOptions::default().setting("max_execution_time", 30),
        )
        .await
        .unwrap();
    assert_eq!(with.records.len(), 5);
    let sql = q
        .render_search_sql(&SearchLogsOptions::default().contains(&run))
        .unwrap();
    assert!(sql.contains("s3Cluster(primary, "));

    // LogQueryClient: search + cursor, wait_for_log, list_streams, list_labels
    let res = q
        .search(&run, SearchOptions::default().limit(2))
        .await
        .unwrap();
    assert_eq!(res.records.len(), 2);
    let next = q
        .search(
            &run,
            SearchOptions::default()
                .limit(2)
                .cursor(res.next_cursor.clone().unwrap()),
        )
        .await
        .unwrap();
    assert_eq!(next.records.len(), 2);
    let rec = q
        .wait_for_log(
            &run,
            WaitOptions::default()
                .timeout(Duration::from_secs(30))
                .predicate(|r| r.body.ends_with("#c")),
        )
        .await
        .unwrap();
    assert!(rec.body.ends_with("#c"));
    let err = q
        .wait_for_log(
            &format!("{run} never"),
            WaitOptions::default().timeout(Duration::from_millis(1500)),
        )
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Timeout);
    assert_eq!(q.list_streams().await.unwrap(), vec![table.clone()]);
    assert_eq!(
        q.list_labels().await.unwrap_err().code(),
        ErrorCode::Unsupported
    );

    // error mapping by ClickHouse code
    let err = q
        .query(
            "SELECT dt FROM remote(t1_does_not_exist_logs)",
            &QueryParams::new(),
            &QueryOptions::default(),
        )
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::NotFound, "{err}");
    let err = q
        .query(
            "SELEC nonsense",
            &QueryParams::new(),
            &QueryOptions::default(),
        )
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation, "{err}");
    c.close().await;
}

#[tokio::test]
async fn source_isolation() {
    if !ready() {
        return;
    }
    let (Ok(token_b), Ok(table_b)) = (
        std::env::var("BETTERSTACK_SOURCE_TOKEN_B"),
        std::env::var("BETTERSTACK_SOURCE_TABLE_B"),
    ) else {
        eprintln!("no second source");
        return;
    };
    let run = run_id();
    let b = client_with(|cb| {
        cb.source_token(token_b.as_str())
            .source_table(table_b.as_str())
    });
    let lc = b.log_client(LogClientOptions::default()).unwrap();
    lc.send(format!("{run} source b").into());
    lc.flush(None).await.unwrap();
    wait_rows(&b, Some(&table_b), &run, 1).await;
    let a = client();
    let res = a
        .query()
        .unwrap()
        .search_logs(SearchLogsOptions::default().contains(&run))
        .await
        .unwrap();
    assert!(res.records.is_empty(), "source a sees source b");
    a.close().await;
    b.close().await;
}

#[tokio::test]
async fn tail_polls_search() {
    if !ready() {
        return;
    }
    let run = run_id();
    let c = client();
    let got = Arc::new(Mutex::new(Vec::<String>::new()));
    let sink = Arc::clone(&got);
    let handle = c
        .query()
        .unwrap()
        .tail(
            &run,
            Arc::new(move |r| sink.lock().unwrap().push(r.body)),
            TailOptions {
                interval: Duration::from_millis(300),
                start: Some(SystemTime::now() - Duration::from_secs(1)),
            },
        )
        .await
        .unwrap();
    let lc = c.log_client(LogClientOptions::default()).unwrap();
    for m in ["one", "two"] {
        lc.send(format!("{run} tail {m}").into());
    }
    lc.flush(None).await.unwrap();
    let deadline = std::time::Instant::now() + Duration::from_secs(20);
    while got.lock().unwrap().len() < 2 {
        assert!(
            std::time::Instant::now() < deadline,
            "tail got {:?}",
            got.lock().unwrap()
        );
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    tokio::time::sleep(Duration::from_millis(700)).await;
    handle.stop();
    assert_eq!(got.lock().unwrap().len(), 2, "tail delivered duplicates");
    c.close().await;
}

#[tokio::test]
async fn adapters_and_logger() {
    if !ready() {
        return;
    }
    let run = run_id();
    let c = client();
    let lc = Arc::new(c.log_client(LogClientOptions::default()).unwrap());
    let subscriber = tracing_subscriber::registry().with(LogLayer::new(lc.clone()));
    tracing::subscriber::with_default(subscriber, || {
        tracing::warn!(
            run = run.as_str(),
            user = "ada",
            password = "hunter2",
            "{} from tracing",
            run
        );
    });
    LogBridge::new(lc.clone())
        .init()
        .expect("logger installed once");
    log::error!("{run} from log");
    lc.logger(Some("facade"), attrs! {})
        .child(attrs! { "component" => "child" })
        .error(format!("{run} from facade"), attrs! {});
    lc.flush(None).await.unwrap();
    let rows = wait_rows(&c, None, &run, 3).await;
    for r in &rows {
        assert!(!format!("{:?}", r.attributes).contains("hunter2"), "{r:?}");
    }
    let facade = rows
        .iter()
        .find(|r| r.body.ends_with("from facade"))
        .unwrap();
    assert_eq!(facade.attributes["component"], "child");
    c.close().await;
}

#[tokio::test]
async fn stub_faults() {
    if !ready() {
        return;
    }
    let c = client_with(|b| b.max_retries(0));
    if !inject_fault("ingest", 402, "Quota exceeded").await {
        eprintln!("no stub control URL");
        return;
    }
    let err = c
        .exporter()
        .unwrap()
        .export(
            &[LogRecord::new(Severity::Info, "quota")],
            ExportOptions::default(),
        )
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::RateLimited);
    assert!(!err.is_retryable());
    assert_eq!(err.http_status(), Some(402));
    inject_fault("query", 503, "Service Unavailable").await;
    let err = c
        .query()
        .unwrap()
        .query("SELECT 1", &QueryParams::new(), &QueryOptions::default())
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unavailable);
    c.close().await;
}

#[test]
fn blocking_round_trip() {
    if !ready() {
        return;
    }
    let run = run_id();
    let c = sdk::blocking::BetterStackClient::from_env().expect("client");
    assert!(c.health().unwrap().healthy);
    c.ping().unwrap();
    assert_eq!(
        c.server_version().unwrap_err().code(),
        ErrorCode::Unsupported
    );
    let lc = c.log_client(LogClientOptions::default()).unwrap();
    lc.send(format!("{run} blocking"));
    lc.flush(None).unwrap();
    let rec = c
        .wait_for_log(
            &format!("{run} blocking"),
            WaitOptions::default().timeout(Duration::from_secs(30)),
        )
        .unwrap();
    assert_eq!(rec.body, format!("{run} blocking"));
    let rows = c
        .query(
            "SELECT count() AS c FROM remote({t:Identifier}) WHERE position(raw, {n:String}) > 0",
            &query_params! { "t" => format!("{}_logs", c.config().source_table().unwrap()), "n" => run.clone() },
        )
        .unwrap();
    assert_eq!(rows[0]["c"], "1");
    assert!(c
        .query_raw(
            "SELECT 1 AS one FORMAT JSONEachRow",
            &QueryParams::new(),
            &QueryOptions::default()
        )
        .unwrap()
        .contains("one"));
    assert_eq!(
        c.search_logs(SearchLogsOptions::default().contains(&run))
            .unwrap()
            .records
            .len(),
        1
    );
    assert_eq!(
        c.search(&run, SearchOptions::default())
            .unwrap()
            .records
            .len(),
        1
    );
    assert_eq!(c.list_streams().unwrap().len(), 1);
    lc.close().unwrap();
    c.close();
}
