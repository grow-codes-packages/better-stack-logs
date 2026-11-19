//! SQL query API, search, normalization and LogQueryClient against recorded stub fixtures.

mod support;

use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use grow_better_stack_logs as sdk;
use sdk::core::{CancellationToken, ErrorCode};
use sdk::logging::{LogQueryClient, SearchOptions, Severity, SortOrder, TailOptions, WaitOptions};
use sdk::{query_params, QueryOptions, QueryParam, QueryParams, QueryRow, SearchLogsOptions};
use serde_json::{json, Value};
use support::*;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

async fn query_server(fixture: &str) -> MockServer {
    let s = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/"))
        .respond_with(fixture_response(fixture))
        .mount(&s)
        .await;
    s
}

async fn body_server(status: u16, body: &str) -> MockServer {
    let s = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(status).set_body_string(body.to_owned()))
        .mount(&s)
        .await;
    s
}

fn row(v: Value) -> QueryRow {
    v.as_object().unwrap().clone()
}

fn t(secs: u64) -> SystemTime {
    UNIX_EPOCH + Duration::from_secs(secs)
}

#[tokio::test]
async fn query_binds_params_server_side() {
    let s = query_server("query.get_query_param").await;
    let q = client(None, Some(&s)).query().unwrap();
    let params = query_params! {
        "needle" => "it's \\ \"x\"",
        "n" => 3i32,
        "u" => 7u64,
        "f" => 1.5f64,
        "b" => true,
        "since" => t(1_700_000_000) + Duration::from_micros(123_456),
        "none" => Option::<String>::None,
    };
    let rows = q
        .query(
            "SELECT count() AS c FROM remote(t1_app_logs) WHERE position(raw, {needle:String}) > 0;",
            &params,
            &QueryOptions::default().setting("max_execution_time", 30),
        )
        .await
        .unwrap();
    assert_eq!(rows[0]["c"], "11");
    let reqs = s.received_requests().await.unwrap();
    let r = &reqs[0];
    assert_eq!(header(r, "authorization"), Some(basic()));
    assert_eq!(
        header(r, "content-type").as_deref(),
        Some("text/plain; charset=utf-8")
    );
    // String values use ClickHouse's escaped (TSV) text format: backslash doubled.
    assert_eq!(param(r, "param_needle").as_deref(), Some("it's \\\\ \"x\""));
    assert_eq!(param(r, "param_n").as_deref(), Some("3"));
    assert_eq!(param(r, "param_u").as_deref(), Some("7"));
    assert_eq!(param(r, "param_f").as_deref(), Some("1.5"));
    assert_eq!(param(r, "param_b").as_deref(), Some("true"));
    assert_eq!(
        param(r, "param_since").as_deref(),
        Some("2023-11-14 22:13:20.123456")
    );
    assert_eq!(param(r, "param_none").as_deref(), Some("\\N"));
    assert_eq!(param(r, "max_execution_time").as_deref(), Some("30"));
    let sql = String::from_utf8(r.body.clone()).unwrap();
    assert!(sql.ends_with("> 0 FORMAT JSONEachRow"), "{sql}");
    assert!(!format!("{:?}", r.headers).contains(TOKEN));

    // FORMAT JSON is kept, other formats are rejected, empty SQL and bad params are invalid
    let raw = q
        .query_raw(
            "SELECT 1 FORMAT JSON",
            &QueryParams::new(),
            &QueryOptions::default(),
        )
        .await
        .unwrap();
    assert!(raw.contains("\"c\""));
    for (sql, params) in [
        ("SELECT 1 FORMAT CSV", QueryParams::new()),
        ("   ", QueryParams::new()),
        ("SELECT {x:Float64}", query_params! { "x" => f64::NAN }),
        (
            "SELECT {bad-name:String}",
            query_params! { "bad-name" => "x" },
        ),
    ] {
        let err = q
            .query(sql, &params, &QueryOptions::default())
            .await
            .unwrap_err();
        assert_eq!(err.code(), ErrorCode::Validation, "{sql}");
    }
    assert!(format!("{q:?}").contains("t1_app"));
}

#[test]
fn escape_param_value_uses_the_tsv_escaped_format() {
    assert_eq!(
        sdk::escape_param_value("plain 'quoted' \"text\""),
        "plain 'quoted' \"text\""
    );
    assert_eq!(
        sdk::escape_param_value("a\\b\tc\nd\re"),
        "a\\\\b\\tc\\nd\\re"
    );
    assert_eq!(sdk::escape_param_value("\\N"), "\\\\N");
    assert_eq!(
        QueryParam::from("C:\\tmp\n").to_param_text().unwrap(),
        "C:\\\\tmp\\n"
    );
}

#[test]
fn query_param_conversions() {
    assert_eq!(QueryParam::from(1i8), QueryParam::Int(1));
    assert_eq!(QueryParam::from(1i16), QueryParam::Int(1));
    assert_eq!(QueryParam::from(1i64), QueryParam::Int(1));
    assert_eq!(QueryParam::from(1u8), QueryParam::UInt(1));
    assert_eq!(QueryParam::from(1u16), QueryParam::UInt(1));
    assert_eq!(QueryParam::from(1u32), QueryParam::UInt(1));
    assert_eq!(QueryParam::from(1usize), QueryParam::UInt(1));
    assert_eq!(QueryParam::from(0.5f32), QueryParam::Float(0.5));
    assert_eq!(
        QueryParam::from(String::from("s")),
        QueryParam::String("s".into())
    );
    assert_eq!(QueryParam::from(Some("s")), QueryParam::String("s".into()));
    assert_eq!(QueryParam::from(false).to_param_text().unwrap(), "false");
    assert_eq!(QueryParam::Int(-2).to_param_text().unwrap(), "-2");
    assert_eq!(
        QueryParam::Float(f64::INFINITY)
            .to_param_text()
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
    assert!(query_params! {}.is_empty());
}

#[tokio::test]
async fn query_formats_are_parsed() {
    for (fixture, key, value) in [
        ("query.json_format", "c", json!("11")),
        ("query.json_extract", "n", json!("7")),
        (
            "query.array_of_rows",
            "dt",
            json!("2023-11-14 22:15:00.123000"),
        ),
    ] {
        let s = query_server(fixture).await;
        let rows = client(None, Some(&s))
            .query()
            .unwrap()
            .query(
                "SELECT x FORMAT JSONEachRow",
                &QueryParams::new(),
                &QueryOptions::default(),
            )
            .await
            .unwrap();
        assert_eq!(rows[0][key], value, "{fixture}");
    }
    let s = query_server("searchLogs.empty").await;
    let rows = client(None, Some(&s))
        .query()
        .unwrap()
        .query("SELECT 1", &QueryParams::new(), &QueryOptions::default())
        .await
        .unwrap();
    assert!(rows.is_empty());
}

#[test]
fn parse_rows_edge_cases() {
    let multi = "{\n  \"meta\": [],\n  \"data\": [{\"c\": \"1\"}],\n  \"rows\": 1\n}\n";
    assert_eq!(sdk::parse_rows(multi).unwrap()[0]["c"], "1");
    let err = sdk::parse_rows("{\"a\":1}\nCode: 241. DB::Exception: Memory limit (total) exceeded")
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    assert!(err.message().contains("hint"));
    let err = sdk::parse_rows("Code: 999. DB::Exception: odd").unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unavailable);
    assert_eq!(err.http_status(), Some(200));
    assert!(!err.is_retryable());
    let err = sdk::parse_rows("[1, 2").unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unavailable);
    let err = sdk::parse_rows("[{\"a\":1}]\nCode: 202. DB::Exception: too many").unwrap_err();
    assert_eq!(err.code(), ErrorCode::RateLimited);
    assert!(err.is_retryable());
    assert_eq!(
        sdk::parse_rows("[1]").unwrap_err().code(),
        ErrorCode::Unavailable
    );
    assert_eq!(
        sdk::parse_rows("not json").unwrap_err().code(),
        ErrorCode::Unavailable
    );
    assert_eq!(
        sdk::parse_rows("{\"meta\":[],\"data\":[{\"c\":1}]}").unwrap()[0]["c"],
        1
    );
    assert_eq!(sdk::parse_rows("{\"a\":1}\n\n{\"a\":2}").unwrap().len(), 2);
}

#[tokio::test]
async fn query_errors_map_by_clickhouse_code() {
    for (fixture, code) in [
        ("query.400_syntax", ErrorCode::Validation),
        ("query.401_bad_password", ErrorCode::Authentication),
        ("query.404_unknown_table", ErrorCode::NotFound),
        ("query.404_unknown_column", ErrorCode::Validation),
        ("query.params_binding", ErrorCode::Validation),
        ("query.503_fault", ErrorCode::Unavailable),
    ] {
        let s = query_server(fixture).await;
        let err = client(None, Some(&s))
            .query()
            .unwrap()
            .query("SELECT 1", &QueryParams::new(), &QueryOptions::default())
            .await
            .unwrap_err();
        assert_eq!(err.code(), code, "{fixture}: {err}");
        assert!(!err.to_string().contains(PASSWORD));
    }
    for (status, body, code) in [
        (
            500,
            "Code: 497. DB::Exception: denied",
            ErrorCode::PermissionDenied,
        ),
        (500, "Code: 159. DB::Exception: timeout", ErrorCode::Timeout),
        (
            501,
            "Code: 48. DB::Exception: not implemented",
            ErrorCode::Unsupported,
        ),
        (
            500,
            "Code: 241. DB::Exception: memory",
            ErrorCode::Validation,
        ),
        (
            400,
            "Code: 241. DB::Exception: memory",
            ErrorCode::Validation,
        ),
        (500, "Code: 1. DB::Exception: other", ErrorCode::Unavailable),
    ] {
        let s = body_server(status, body).await;
        let err = client(None, Some(&s))
            .query()
            .unwrap()
            .query_raw("SELECT 1", &QueryParams::new(), &QueryOptions::default())
            .await
            .unwrap_err();
        assert_eq!(err.code(), code, "{body}");
    }
    // cancellation
    let s = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(200).set_delay(Duration::from_secs(5)))
        .mount(&s)
        .await;
    let token = CancellationToken::new();
    token.cancel();
    let err = client(None, Some(&s))
        .query()
        .unwrap()
        .query_raw(
            "SELECT 1",
            &QueryParams::new(),
            &QueryOptions::default().cancel(token),
        )
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Cancelled);
}

#[tokio::test]
async fn search_logs_normalizes_fixture_rows() {
    let s = query_server("query.json_each_row").await;
    let q = client(None, Some(&s)).query().unwrap();
    let res = q
        .search_logs(
            SearchLogsOptions::default()
                .contains("needle-prep")
                .order(SortOrder::Asc),
        )
        .await
        .unwrap();
    assert_eq!(res.records.len(), 11);
    assert!(res.next_cursor.is_none());
    let first = &res.records[0];
    assert_eq!(first.body, "needle-prep-208cdca json-single");
    assert_eq!(first.severity, Severity::Info);
    assert_eq!(first.severity_text, "INFO");
    assert_eq!(
        first.trace_id.as_deref(),
        Some("0af7651916cd43dd8448eb211c80319c")
    );
    assert_eq!(first.span_id.as_deref(), Some("b7ad6b7169203331"));
    assert_eq!(first.attributes["unicode"], "Grüße ✓");
    assert_eq!(first.attributes["service"]["name"], "sdk-verify");
    assert_eq!(first.timestamp, t(1_691_564_610));
    let ns = res
        .records
        .iter()
        .find(|r| r.body.ends_with("msgpack ns"))
        .unwrap();
    assert_eq!(
        ns.timestamp,
        t(1_700_000_200) + Duration::from_micros(123_457)
    );
    let sql = String::from_utf8(s.received_requests().await.unwrap()[0].body.clone()).unwrap();
    assert!(
        sql.contains("ORDER BY dt ASC LIMIT 101 FORMAT JSONEachRow"),
        "{sql}"
    );
    assert!(sql.contains("dt >= toDateTime64('"), "{sql}");
    assert!(sql.contains(", 6, 'UTC')"), "{sql}");
}

#[tokio::test]
async fn search_logs_pages_and_dedups() {
    let rows = [
        json!({"dt": "2026-01-01 00:00:03.000000", "raw": "{\"message\":\"c\"}"}),
        json!({"dt": "2026-01-01 00:00:03.000000", "raw": "{\"message\":\"c\"}"}),
        json!({"dt": "2026-01-01 00:00:01.000000", "raw": "{\"message\":\"a\"}"}),
    ];
    let body: String = rows.iter().map(|r| format!("{r}\n")).collect();
    let s = body_server(200, &body).await;
    let q = client(None, Some(&s)).query().unwrap();
    let page = q
        .search_logs(
            SearchLogsOptions::default()
                .limit(2)
                .range(Some(t(1)), Some(t(2_000_000_000))),
        )
        .await
        .unwrap();
    assert_eq!(page.records.len(), 1);
    let cursor = page.next_cursor.clone().unwrap();
    let page2 = q
        .search_logs_with(
            SearchLogsOptions::default().limit(2).cursor(cursor.clone()),
            &QueryOptions::default(),
        )
        .await
        .unwrap();
    assert_eq!(
        page2.next_cursor.as_deref().map(|c| c != cursor),
        Some(true)
    );
    let reqs = s.received_requests().await.unwrap();
    let sql = String::from_utf8(reqs[1].body.clone()).unwrap();
    assert!(sql.contains("LIMIT 3 OFFSET 2"), "{sql}");
    let sql0 = String::from_utf8(reqs[0].body.clone()).unwrap();
    assert!(
        sql0.contains("dt BETWEEN toDateTime64('1970-01-01 00:00:01.000000', 6, 'UTC') AND"),
        "{sql0}"
    );
}

#[tokio::test]
async fn render_search_sql_rules() {
    let s = MockServer::start().await;
    let q = client(None, Some(&s)).query().unwrap();
    let sql = q
        .render_search_sql(
            &SearchLogsOptions::default()
                .contains("it's \\ \"q\"\n")
                .source("t2_other"),
        )
        .unwrap();
    assert!(sql.contains("remote(t2_other_logs)"), "{sql}");
    assert!(sql.contains("s3Cluster(primary, t2_other_s3) WHERE _row_type = 1"));
    assert!(
        sql.contains(r#"position(raw, 'it\'s \\\\ \\"q\\"\\n') > 0"#),
        "{sql}"
    );
    assert!(sql.contains("ORDER BY dt DESC LIMIT 101"));
    let only_end = q
        .render_search_sql(&SearchLogsOptions::default().range(None, Some(t(100_000))))
        .unwrap();
    assert!(
        only_end.contains("'1970-01-01 03:46:40.000000', 6, 'UTC')"),
        "{only_end}"
    );
    assert!(q
        .render_search_sql(&SearchLogsOptions::default().contains(""))
        .unwrap()
        .find("position")
        .is_none());
    for (opts, code) in [
        (
            SearchLogsOptions::default().source("bad-name"),
            ErrorCode::Validation,
        ),
        (SearchLogsOptions::default().limit(0), ErrorCode::Validation),
        (
            SearchLogsOptions::default().limit(10_001),
            ErrorCode::Validation,
        ),
        (
            SearchLogsOptions::default().range(Some(t(10)), Some(t(5))),
            ErrorCode::Validation,
        ),
        (
            SearchLogsOptions::default().cursor("%%%"),
            ErrorCode::Validation,
        ),
        (
            SearchLogsOptions::default().cursor("e30"),
            ErrorCode::Validation,
        ),
    ] {
        assert_eq!(
            q.render_search_sql(&opts).unwrap_err().code(),
            code,
            "{opts:?}"
        );
    }
    assert_eq!(
        q.search_logs(SearchLogsOptions::default().cursor("%%%"))
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
    // no source table configured
    let c = sdk::BetterStackClient::new(
        sdk::BetterStackConfig::builder()
            .query_url(s.uri())
            .query_username("u")
            .query_password("p-1234")
            .build()
            .unwrap(),
    )
    .unwrap();
    let q2 = c.query().unwrap();
    assert_eq!(
        q2.render_search_sql(&SearchLogsOptions::default())
            .unwrap_err()
            .code(),
        ErrorCode::Config
    );
    assert!(q2.list_streams().await.unwrap().is_empty());
    let noop: sdk::logging::RecordCallback = Arc::new(|_| {});
    assert_eq!(
        q2.tail("x", noop, TailOptions::default())
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Config
    );
    assert_eq!(sdk::DEFAULT_SEARCH_LIMIT, 100);
    assert_eq!(sdk::MAX_SEARCH_LIMIT, 10_000);
}

#[tokio::test]
async fn log_query_client_surface() {
    let s = query_server("waitForLog.hit").await;
    let c = client(None, Some(&s));
    let q = c.query().unwrap();
    let res = q
        .search("wait-for-log", SearchOptions::default().limit(5))
        .await
        .unwrap();
    assert_eq!(res.records[0].body, "needle-prep-208cdca wait-for-log");
    let sql = String::from_utf8(s.received_requests().await.unwrap()[0].body.clone()).unwrap();
    assert!(
        sql.contains("position(raw, 'wait-for-log')") && sql.contains("LIMIT 6"),
        "{sql}"
    );
    let rec = q
        .wait_for_log(
            "wait-for-log",
            WaitOptions::default().interval(Duration::from_millis(10)),
        )
        .await
        .unwrap();
    assert!(rec.body.ends_with("wait-for-log"));
    assert_eq!(q.list_streams().await.unwrap(), vec!["t1_app".to_owned()]);
    assert_eq!(
        q.list_labels().await.unwrap_err().code(),
        ErrorCode::Unsupported
    );

    let empty = query_server("searchLogs.empty").await;
    let q = client(None, Some(&empty)).query().unwrap();
    let err = q
        .wait_for_log(
            "never",
            WaitOptions::default()
                .timeout(Duration::from_millis(50))
                .interval(Duration::from_millis(10)),
        )
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Timeout);
}

#[tokio::test]
async fn tail_polls_and_stops_on_close() {
    let s = MockServer::start().await;
    let now = SystemTime::now();
    let dt = sdk::format_datetime64(now + Duration::from_secs(1));
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(200).set_body_string(format!(
            "{}\n",
            json!({"dt": dt, "raw": "{\"message\":\"tailed\"}"})
        )))
        .mount(&s)
        .await;
    let c = client(None, Some(&s));
    let got = Arc::new(Mutex::new(Vec::new()));
    let sink = Arc::clone(&got);
    let handle = c
        .query()
        .unwrap()
        .tail(
            "tailed",
            Arc::new(move |r| sink.lock().unwrap().push(r.body)),
            TailOptions {
                interval: Duration::from_millis(20),
                start: Some(now),
            },
        )
        .await
        .unwrap();
    for _ in 0..100 {
        if !got.lock().unwrap().is_empty() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert_eq!(
        got.lock().unwrap().first().map(String::as_str),
        Some("tailed")
    );
    c.close().await;
    assert!(handle.is_stopped());
    tokio::time::sleep(Duration::from_millis(60)).await;
    assert_eq!(
        got.lock().unwrap().len(),
        1,
        "each record is delivered once"
    );
}

#[test]
fn row_normalization_edge_cases() {
    let r = sdk::log_record_from_row(&row(json!({
        "dt": "2026-01-02 03:04:05.5",
        "raw": "{\"msg\":\"m\",\"severity\":\"verbose\",\"trace_id\":\"4BF92F3577B34DA6A3CE929D0E0E4736\",\"span_id\":\"00f067aa0ba902b7\",\"trace_flags\":1,\"observed_dt\":\"2026-01-02T05:04:05+02:00\",\"resource\":{\"service.name\":\"s\"},\"attributes\":{\"a\":1},\"extra\":true}"
    })));
    assert_eq!(r.body, "m");
    assert_eq!(r.severity, Severity::Info);
    assert_eq!(r.severity_text, "verbose");
    assert_eq!(
        r.trace_id.as_deref(),
        Some("4bf92f3577b34da6a3ce929d0e0e4736")
    );
    assert_eq!(r.span_id.as_deref(), Some("00f067aa0ba902b7"));
    assert_eq!(r.trace_flags, Some(1));
    assert_eq!(r.observed_timestamp, Some(t(1_767_323_045)));
    assert_eq!(r.resource["service.name"], "s");
    assert_eq!(r.attributes["a"], 1);
    assert_eq!(r.attributes["extra"], true);
    assert_eq!(r.timestamp, t(1_767_323_045) + Duration::from_millis(500));

    let r = sdk::log_record_from_row(&row(json!({
        "raw": "{\"message\":{\"nested\":1},\"severity_number\":\"17\",\"dt\":1700000000123,\"trace_id\":\"00000000000000000000000000000000\"}"
    })));
    assert_eq!(r.body, "{\"nested\":1}");
    assert_eq!(r.severity, Severity::Error);
    assert_eq!(r.severity_text, "ERROR");
    assert!(r.trace_id.is_none() && r.span_id.is_none());
    assert_eq!(r.timestamp, t(1_700_000_000) + Duration::from_millis(123));

    let r = sdk::log_record_from_row(&row(json!({"dt": 1_700_000_000, "raw": "plain text"})));
    assert_eq!(r.body, "plain text");
    assert_eq!(r.timestamp, t(1_700_000_000));
    let r = sdk::log_record_from_row(&row(
        json!({"raw": {"message": "obj", "level": "WARNING", "severity_number": 21}}),
    ));
    assert_eq!(r.body, "obj");
    assert_eq!(r.severity, Severity::Fatal);
    assert_eq!(r.severity_text, "WARN");
    let r = sdk::log_record_from_row(&row(json!({})));
    assert_eq!(r.body, "null");
    assert_eq!(r.timestamp, UNIX_EPOCH);
}

#[test]
fn time_and_string_helpers() {
    let p = |s: &str| sdk::parse_datetime(&json!(s));
    assert_eq!(p("2026-01-02T03:04:05Z"), Some(t(1_767_323_045)));
    assert_eq!(p("2026-01-02T01:04:05-02:00"), Some(t(1_767_323_045)));
    assert_eq!(
        p("2026-01-02 03:04:05.123456789"),
        Some(t(1_767_323_045) + Duration::from_nanos(123_456_789))
    );
    assert_eq!(p("2026-01-02T05:04:05+0200"), None);
    assert_eq!(p("2026-01-02T05:04:05+02-00"), None);
    assert_eq!(p("2026-01-02"), None);
    assert_eq!(p("2026-01-02X03:04:05"), None);
    assert_eq!(p("not a time at all!!!"), None);
    assert_eq!(sdk::parse_datetime(&json!(-1)), None);
    assert_eq!(sdk::parse_datetime(&json!(-1.5)), None);
    assert_eq!(
        sdk::parse_datetime(&json!(1.5)),
        Some(t(1) + Duration::from_millis(500))
    );
    assert_eq!(sdk::parse_datetime(&json!(1.7e12)), Some(t(1_700_000_000)));
    assert_eq!(p("2026-01-02 03:04:05.ü"), None);
    assert_eq!(sdk::parse_datetime(&json!(true)), None);
    assert_eq!(
        sdk::format_datetime64(t(1_700_000_000)),
        "2023-11-14 22:13:20.000000"
    );
    assert_eq!(
        sdk::format_datetime64(UNIX_EPOCH - Duration::from_secs(1)),
        "1970-01-01 00:00:00.000000"
    );
    assert_eq!(sdk::sql_string("a'b\\c\n\r\t\0"), r"'a\'b\\c\n\r\t\0'");
    assert_eq!(sdk::json_escape("q\"\\\n"), r#"q\"\\\n"#);
}

#[test]
fn error_helpers() {
    let e = sdk::map_ingest_error(sdk::Error::from_status(403, "x").with_request_id("r"));
    assert_eq!(e.code(), ErrorCode::Authentication);
    assert_eq!(e.request_id(), Some("r"));
    let e = sdk::map_ingest_error(
        sdk::Error::from_status(429, "x").with_retry_after(Duration::from_secs(2)),
    );
    assert_eq!(e.retry_after(), Some(Duration::from_secs(2)));
    let e = sdk::map_query_error(sdk::Error::from_status(
        401,
        "Code: 516. DB::Exception: auth",
    ));
    assert_eq!(e.code(), ErrorCode::Authentication);
    let e = sdk::map_query_error(sdk::Error::unavailable("no code"));
    assert_eq!(e.code(), ErrorCode::Unavailable);
    assert_eq!(sdk::code_for_clickhouse(60), Some(ErrorCode::NotFound));
    assert_eq!(sdk::clickhouse_code("Code: 81. DB::Exception: x"), Some(81));
    assert!(sdk::error_for_clickhouse(193, "m").is_some());
}
