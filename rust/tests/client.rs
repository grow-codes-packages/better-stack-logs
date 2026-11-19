// Taken over from sdk-kit 0.1.0: service client, config and ingest tests (GRO-1377).
//! Config, client surface (health/ping/serverVersion/close) and ingest exporters against mocks.

mod support;

use std::sync::Arc;
use std::time::{Duration, UNIX_EPOCH};

use grow_better_stack_logs as sdk;
use sdk::core::{EnvReader, ErrorCode};
use sdk::logging::{
    attrs, BatchOptions, ExportOptions, LogClient, LogEntry, LogExporter, LogInput, LogRecord,
    Severity,
};
use sdk::{
    BetterStackClient, BetterStackConfig, Encoding, IngestExporterOptions, LogClientOptions,
    LogProtocol, OtlpExporterOptions, OtlpProtocol, SendEventsOptions,
};
use serde_json::{json, Value};
use support::*;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

fn env(pairs: &[(&str, &str)]) -> EnvReader {
    EnvReader::from_map(pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())))
}

fn record(body: &str) -> LogRecord {
    let mut r = LogRecord::new(Severity::Warn, body)
        .with_attribute("n", 1)
        .with_trace("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7");
    r.timestamp = UNIX_EPOCH + Duration::new(1_767_323_045, 678_901_000);
    r.observed_timestamp = Some(UNIX_EPOCH + Duration::from_secs(1_767_323_046));
    r.trace_flags = Some(1);
    r.resource.insert("service.name".into(), "svc".into());
    r
}

async fn accept_all() -> MockServer {
    let s = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(202))
        .mount(&s)
        .await;
    s
}

#[test]
fn config_env_precedence_and_validation() {
    let e = env(&[
        ("GROW_BETTER_STACK_LOGS_URL", "http://grow:8080/"),
        ("BETTERSTACK_INGESTING_HOST", "s1.example:443"),
        ("BETTERSTACK_SOURCE_TOKEN", "vendor-token"),
        ("BETTERSTACK_QUERY_HOST", "eu-connect.example"),
        ("BETTERSTACK_QUERY_USERNAME", " user "),
        ("BETTERSTACK_QUERY_PASSWORD", "pw-123456"),
        ("BETTERSTACK_SOURCE_TABLE", "t1_app"),
        ("GROW_BETTER_STACK_LOGS_ENCODING", "NDJSON"),
    ]);
    let c = BetterStackConfig::builder().build_with_env(&e).unwrap();
    assert_eq!(c.url().unwrap().as_str(), "http://grow:8080/");
    assert_eq!(
        c.query_url().unwrap().as_str(),
        "https://eu-connect.example/"
    );
    assert_eq!(c.source_token().unwrap().expose_secret(), "vendor-token");
    assert_eq!(c.query_username(), Some("user"));
    assert_eq!(c.query_password().unwrap().expose_secret(), "pw-123456");
    assert_eq!(c.source_table(), Some("t1_app"));
    assert_eq!(c.encoding(), Encoding::Ndjson);
    assert!(c.has_ingest() && c.has_query());
    assert_eq!(c.common().url(), c.url().unwrap());
    assert!(c.ingest_common().is_some() && c.query_common().is_some());
    assert_eq!(
        c.basic_credentials().unwrap().expose_secret(),
        "dXNlcjpwdy0xMjM0NTY="
    );
    assert_eq!(c.known_secrets().len(), 3);
    let dbg = format!("{c:?}");
    assert!(
        !dbg.contains("vendor-token") && !dbg.contains("pw-123456"),
        "{dbg}"
    );

    // vendor host only: bare host:port → https
    let c = BetterStackConfig::builder()
        .build_with_env(&env(&[
            ("BETTERSTACK_INGESTING_HOST", "s1.example:8443"),
            ("BETTERSTACK_SOURCE_TOKEN", "t-1234"),
        ]))
        .unwrap();
    assert_eq!(c.url().unwrap().as_str(), "https://s1.example:8443/");
    assert!(!c.has_query() && c.query_url().is_none() && c.basic_credentials().is_none());
    assert_eq!(c.encoding(), Encoding::Json);

    // explicit wins over env
    let c = BetterStackConfig::builder()
        .encoding(Encoding::Msgpack)
        .source_token("explicit-token")
        .build_with_env(&e)
        .unwrap();
    assert_eq!(c.encoding(), Encoding::Msgpack);
    assert_eq!(c.source_token().unwrap().expose_secret(), "explicit-token");

    // query-only client
    let c = BetterStackConfig::builder()
        .query_url("http://q:8123")
        .query_username("u")
        .query_password("p-1234")
        .build()
        .unwrap();
    assert!(c.url().is_none() && c.has_query() && !c.has_ingest());
    assert_eq!(c.common().url().as_str(), "http://q:8123/");

    let bad_env = env(&[
        ("GROW_BETTER_STACK_LOGS_URL", "http://h"),
        ("GROW_BETTER_STACK_LOGS_SOURCE_TOKEN", "t-1234"),
        ("GROW_BETTER_STACK_LOGS_ENCODING", "xml"),
    ]);
    let err = BetterStackConfig::builder()
        .build_with_env(&bad_env)
        .unwrap_err();
    assert!(err.message().contains("GROW_BETTER_STACK_LOGS_ENCODING"));

    let cases: Vec<(&str, sdk::BetterStackConfigBuilder)> = vec![
        ("nothing", BetterStackConfig::builder()),
        ("url only", BetterStackConfig::builder().url("http://h")),
        ("token only", BetterStackConfig::builder().source_token("t")),
        (
            "blank token",
            BetterStackConfig::builder()
                .url("http://h")
                .source_token("  "),
        ),
        (
            "partial query",
            BetterStackConfig::builder()
                .query_url("http://q")
                .query_username("u"),
        ),
        (
            "blank password",
            BetterStackConfig::builder()
                .query_url("http://q")
                .query_username("u")
                .query_password(""),
        ),
        (
            "bad table",
            BetterStackConfig::builder()
                .url("http://h")
                .source_token("t-1234")
                .source_table("t1-app; DROP"),
        ),
        (
            "bad scheme",
            BetterStackConfig::builder()
                .url("ftp://h")
                .source_token("t-1234"),
        ),
        (
            "bad query url",
            BetterStackConfig::builder()
                .query_url("ftp://q")
                .query_username("u")
                .query_password("p"),
        ),
    ];
    for (name, b) in cases {
        let err = b.build().unwrap_err();
        assert_eq!(err.code(), ErrorCode::Config, "{name}: {err}");
    }
    let err = BetterStackConfig::builder()
        .query_url("ftp://q")
        .query_username("u")
        .query_password("p")
        .build()
        .unwrap_err();
    assert!(err.message().contains("query_url"), "{err}");
}

#[test]
fn helpers_and_encodings() {
    assert_eq!(
        sdk::normalize_base_url(" h:1// "),
        Some("https://h:1".into())
    );
    assert_eq!(
        sdk::normalize_base_url("http://h/"),
        Some("http://h".into())
    );
    assert_eq!(sdk::normalize_base_url("  "), None);
    assert!(sdk::is_valid_identifier("_t1_app"));
    assert!(
        !sdk::is_valid_identifier("1t")
            && !sdk::is_valid_identifier("")
            && !sdk::is_valid_identifier("a-b")
    );
    for e in Encoding::ALL {
        assert_eq!(e.as_str().parse::<Encoding>().unwrap(), e);
        assert_eq!(e.to_string(), e.as_str());
        assert!(e.content_type().starts_with("application/"));
    }
    assert_eq!(
        "MessagePack".parse::<Encoding>().unwrap(),
        Encoding::Msgpack
    );
    assert!("xml".parse::<Encoding>().is_err());
    assert!(sdk::DEFAULT_URL.is_empty());
    assert_eq!(sdk::VENDOR_URL_VARS.len(), 2);
    assert_eq!(sdk::VENDOR_QUERY_URL_VARS.len(), 2);
    assert_eq!(
        [
            sdk::VENDOR_SOURCE_TOKEN_VARS,
            sdk::VENDOR_QUERY_USERNAME_VARS,
            sdk::VENDOR_QUERY_PASSWORD_VARS,
            sdk::VENDOR_SOURCE_TABLE_VARS
        ]
        .concat()
        .len(),
        4
    );
}

#[test]
fn event_mapping() {
    let r = record("hello");
    let ev = sdk::to_better_stack_event(&r);
    assert_eq!(ev["dt"], "2026-01-02T03:04:05.678901000Z");
    assert_eq!(ev["message"], "hello");
    assert_eq!(ev["level"], "warn");
    assert_eq!(ev["severity_number"], 13);
    assert_eq!(ev["span"]["trace_id"], "4bf92f3577b34da6a3ce929d0e0e4736");
    assert_eq!(ev["span"]["span_id"], "00f067aa0ba902b7");
    assert_eq!(ev["trace_flags"], 1);
    assert_eq!(ev["resource"]["service.name"], "svc");
    assert_eq!(ev["attributes"]["n"], 1);
    assert_eq!(ev["observed_dt"], "2026-01-02T03:04:06.000000000Z");
    let mut plain = LogRecord::new(Severity::Info, "x");
    plain.severity_text.clear();
    plain.span_id = Some("00f067aa0ba902b7".into());
    let ev = sdk::to_better_stack_event(&plain);
    assert_eq!(ev["level"], "info");
    assert!(ev.get("resource").is_none() && ev.get("attributes").is_none());
    assert!(ev["span"].get("trace_id").is_none());
    assert_eq!(
        sdk::format_event_time(UNIX_EPOCH - Duration::from_secs(1)),
        "1970-01-01T00:00:00.000000000Z"
    );
    // MessagePack: map with dt / observed_dt as timestamp extensions
    let mp = sdk::encode_record(&r, Encoding::Msgpack);
    assert_eq!(mp[0] & 0xf0, 0x80);
    assert!(mp.windows(2).filter(|w| w == &[0xd7, 0xff]).count() == 1);
    assert!(mp.windows(2).any(|w| w == [0xd6, 0xff]));
    let json = sdk::encode_record(&r, Encoding::Json);
    assert!(serde_json::from_slice::<Value>(&json).unwrap().is_object());
    assert_eq!(
        sdk::frame(Encoding::Json, &[b"{}".to_vec(), b"{}".to_vec()]),
        b"[{},{}]"
    );
    assert_eq!(sdk::frame(Encoding::Ndjson, &[b"{}".to_vec()]), b"{}\n");
    assert_eq!(sdk::frame(Encoding::Msgpack, &[vec![0x80]]), [0x91, 0x80]);
    assert_eq!(
        sdk::encode_event(&json!(3), Encoding::Json)
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
    assert_eq!(
        sdk::encode_event(&json!({"a": 1}), Encoding::Msgpack).unwrap(),
        [0x81, 0xa1, b'a', 1]
    );
    assert_eq!(sdk::encode_msgpack(&json!([true])), [0x91, 0xc3]);
}

#[tokio::test]
async fn health_ping_version_close_with_both_hosts() {
    let ingest = MockServer::start().await;
    let query = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/ping"))
        .respond_with(fixture_response("health.query_ping"))
        .mount(&query)
        .await;
    Mock::given(method("POST"))
        .and(path("/"))
        .respond_with(fixture_response("ping.fallback_count"))
        .mount(&query)
        .await;
    let c = client(Some(&ingest), Some(&query));
    let h = c.health().await.unwrap();
    assert!(h.healthy, "{h:?}");
    assert_eq!(h.status, "ok");
    assert_eq!(h.details["endpoint"], "query");
    assert!(c.ping().await.unwrap() > Duration::ZERO);
    let reqs = query.received_requests().await.unwrap();
    let ping = reqs.iter().find(|r| r.url.path() == "/").unwrap();
    assert_eq!(header(ping, "authorization"), Some(basic()));
    let sql = String::from_utf8(ping.body.clone()).unwrap();
    assert!(
        sql.contains("remote(t1_app_logs) WHERE 1 = 0 FORMAT JSONEachRow"),
        "{sql}"
    );
    // credentials never cross hosts
    assert!(reqs
        .iter()
        .all(|r| !format!("{:?}", r.headers).contains(TOKEN)));
    assert!(ingest.received_requests().await.unwrap().is_empty());
    assert_eq!(
        c.server_version().await.unwrap_err().code(),
        ErrorCode::Unsupported
    );
    assert!(c.ingest_transport().is_some() && c.query_transport().is_some());
    assert!(c.config().has_query());
    c.on_close(|| Box::pin(async {}));
    assert!(!c.is_closed());
    c.close().await;
    c.close().await;
    assert!(c.is_closed());
    assert_eq!(c.ping().await.unwrap_err().code(), ErrorCode::Cancelled);
    assert_eq!(
        c.log_client(LogClientOptions::default())
            .unwrap_err()
            .code(),
        ErrorCode::Config
    );
}

#[tokio::test]
async fn ping_without_table_and_auth_errors() {
    let query = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(fixture_response("query.401_bad_password"))
        .mount(&query)
        .await;
    let c = BetterStackClient::new(
        BetterStackConfig::builder()
            .query_url(query.uri())
            .query_username("user")
            .query_password(PASSWORD)
            .retry(fast_retry(0))
            .build()
            .unwrap(),
    )
    .unwrap();
    let err = c.ping().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Authentication);
    assert!(!err.to_string().contains(PASSWORD));
    let reqs = query.received_requests().await.unwrap();
    assert!(String::from_utf8_lossy(&reqs[0].body).starts_with("SELECT 1 AS ok"));
    // the ingest side is missing
    assert_eq!(c.exporter().unwrap_err().code(), ErrorCode::Config);
    assert_eq!(c.otlp_exporter().unwrap_err().code(), ErrorCode::Config);
    assert!(c.ingest_transport().is_none());
}

#[tokio::test]
async fn ingest_only_health_and_ping() {
    let ingest = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(fixture_response("health.ingest_get_root"))
        .mount(&ingest)
        .await;
    Mock::given(method("POST"))
        .respond_with(fixture_response("send.json_single"))
        .mount(&ingest)
        .await;
    let c = client(Some(&ingest), None);
    let h = c.health().await.unwrap();
    assert!(h.healthy);
    assert_eq!(h.details["endpoint"], "ingest");
    assert_eq!(h.details["httpStatus"], "405");
    c.ping().await.unwrap();
    let reqs = ingest.received_requests().await.unwrap();
    let post = reqs.iter().find(|r| r.method.as_str() == "POST").unwrap();
    assert_eq!(post.body, b"[]");
    assert_eq!(
        header(post, "authorization"),
        Some(format!("Bearer {TOKEN}"))
    );
    assert!(header(&reqs[0], "authorization").is_none());
    assert_eq!(c.query().unwrap_err().code(), ErrorCode::Config);
    assert!(c.query_transport().is_none());
}

#[tokio::test]
async fn health_unhealthy_and_unreachable() {
    let ingest = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(503))
        .mount(&ingest)
        .await;
    let h = client(Some(&ingest), None).health().await.unwrap();
    assert!(!h.healthy);
    assert_eq!(h.status, "unhealthy");
    let query = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(500))
        .mount(&query)
        .await;
    assert!(!client(None, Some(&query)).health().await.unwrap().healthy);
    let query2 = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&query2)
        .await;
    assert_eq!(
        client(None, Some(&query2)).health().await.unwrap().status,
        "ok"
    );
    let c = BetterStackClient::new(
        BetterStackConfig::builder()
            .url("http://127.0.0.1:9")
            .source_token("t-1234")
            .retry(fast_retry(0))
            .connect_timeout(Duration::from_millis(200))
            .build()
            .unwrap(),
    )
    .unwrap();
    let h = c.health().await.unwrap();
    assert_eq!(h.status, "unreachable");
    assert_eq!(h.details["endpoint"], "ingest");
}

#[tokio::test]
async fn ingest_errors_are_mapped() {
    for (fixture, code, retryable) in [
        ("send.403_bad_token", ErrorCode::Authentication, false),
        ("send.402_fault", ErrorCode::RateLimited, false),
        ("send.406_bad_json", ErrorCode::Validation, false),
        ("send.413_too_large", ErrorCode::Validation, false),
        ("send.429_fault", ErrorCode::RateLimited, true),
        ("send.503_fault", ErrorCode::Unavailable, true),
    ] {
        let s = MockServer::start().await;
        Mock::given(method("POST"))
            .respond_with(fixture_response(fixture).insert_header("x-request-id", "req-1"))
            .mount(&s)
            .await;
        let c = client(Some(&s), None);
        let err = c
            .exporter()
            .unwrap()
            .export(&[record("x")], ExportOptions::new(false))
            .await
            .unwrap_err();
        assert_eq!(err.code(), code, "{fixture}: {err}");
        assert_eq!(err.is_retryable(), retryable, "{fixture}");
        assert!(!err.to_string().contains(TOKEN));
        if fixture == "send.403_bad_token" {
            assert_eq!(err.http_status(), Some(403));
            assert_eq!(err.request_id(), Some("req-1"));
        }
    }
}

#[tokio::test]
async fn export_encodings_gzip_and_splitting() {
    for enc in Encoding::ALL {
        for gz in [false, true] {
            let s = accept_all().await;
            let c = client(Some(&s), None);
            let exp = c
                .new_ingest_exporter(IngestExporterOptions::default().encoding(enc))
                .unwrap();
            assert_eq!(exp.encoding(), enc);
            let res = exp
                .export(&[record("a"), record("b")], ExportOptions::new(gz))
                .await
                .unwrap();
            assert_eq!((res.accepted, res.rejected), (2, 0));
            let reqs = s.received_requests().await.unwrap();
            assert_eq!(reqs.len(), 1);
            let r = &reqs[0];
            assert_eq!(
                header(r, "content-type").as_deref(),
                Some(enc.content_type())
            );
            assert_eq!(header(r, "content-encoding").is_some(), gz);
            let b = body(r);
            match enc {
                Encoding::Json => {
                    let v: Value = serde_json::from_slice(&b).unwrap();
                    assert_eq!(v[1]["message"], "b");
                }
                Encoding::Ndjson => assert_eq!(b.iter().filter(|c| **c == b'\n').count(), 2),
                Encoding::Msgpack => assert_eq!(b[0], 0x92),
            }
        }
    }
    // config encoding is the default
    let s = accept_all().await;
    let c = BetterStackClient::new(
        builder(Some(&s), None)
            .encoding(Encoding::Ndjson)
            .build()
            .unwrap(),
    )
    .unwrap();
    assert_eq!(c.exporter().unwrap().encoding(), Encoding::Ndjson);

    // small request limit: one request per record, oversized records rejected
    let one = sdk::encode_record(&record("1"), Encoding::Ndjson).len();
    let exp = c
        .new_ingest_exporter(
            IngestExporterOptions::default()
                .max_request_bytes(one + 10)
                .max_record_bytes(one + 5),
        )
        .unwrap();
    assert_eq!(
        (exp.max_request_bytes(), exp.max_record_bytes()),
        (one + 10, one + 5)
    );
    let big = record(&"x".repeat(800));
    let res = exp
        .export(
            &[record("1"), record("2"), big.clone()],
            ExportOptions::new(false),
        )
        .await
        .unwrap();
    assert_eq!((res.accepted, res.rejected), (2, 1));
    assert_eq!(res.error.unwrap().code(), ErrorCode::Validation);
    assert_eq!(s.received_requests().await.unwrap().len(), 2);
    let err = exp
        .export(&[big], ExportOptions::new(false))
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    assert!(format!("{exp:?}").contains("max_request_bytes"));
    for bad in [0, sdk::MAX_REQUEST_BYTES + 1] {
        assert_eq!(
            c.new_ingest_exporter(IngestExporterOptions::default().max_request_bytes(bad))
                .unwrap_err()
                .code(),
            ErrorCode::Validation
        );
    }
    assert_eq!(sdk::MAX_RECORD_BYTES, 10 << 20);
}

#[tokio::test]
async fn export_partial_failure_and_send_events() {
    let s = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(202))
        .up_to_n_times(1)
        .mount(&s)
        .await;
    Mock::given(method("POST"))
        .respond_with(fixture_response("send.402_fault"))
        .mount(&s)
        .await;
    let c = client(Some(&s), None);
    let one = sdk::encode_record(&record("1"), Encoding::Json).len();
    let exp = c
        .new_ingest_exporter(IngestExporterOptions::default().max_request_bytes(one + 10))
        .unwrap();
    let res = exp
        .export(
            &[record("1"), record("2"), record("3")],
            ExportOptions::new(true),
        )
        .await
        .unwrap();
    assert_eq!((res.accepted, res.rejected), (1, 2));
    assert_eq!(res.error.unwrap().code(), ErrorCode::RateLimited);

    let s = accept_all().await;
    let c = client(Some(&s), None);
    let exp = c.exporter().unwrap();
    exp.send_events(
        &[json!({"message": "raw", "dt": 1_700_000_000, "custom": {"k": "v"}})],
        SendEventsOptions::default(),
    )
    .await
    .unwrap();
    exp.send_events(
        &[json!({"message": "mp"})],
        SendEventsOptions {
            encoding: Some(Encoding::Msgpack),
            gzip: false,
        },
    )
    .await
    .unwrap();
    let reqs = s.received_requests().await.unwrap();
    let v: Value = serde_json::from_slice(&body(&reqs[0])).unwrap();
    assert_eq!(v[0]["custom"]["k"], "v");
    assert_eq!(
        header(&reqs[1], "content-type").as_deref(),
        Some("application/msgpack")
    );
    assert_eq!(
        exp.send_events(&[json!("scalar")], SendEventsOptions::default())
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
    let small = c
        .new_ingest_exporter(IngestExporterOptions::default().max_request_bytes(10))
        .unwrap();
    assert_eq!(
        small
            .send_events(
                &[json!({"message": "too long for ten bytes"})],
                SendEventsOptions::default()
            )
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
}

fn varints(data: &[u8]) -> bool {
    // the request body is a protobuf message whose first field is resource_logs (1, LEN)
    data.first() == Some(&0x0a)
}

#[tokio::test]
async fn otlp_exporter_protobuf_json_and_partial_success() {
    let s = MockServer::start().await;
    // ExportLogsServiceResponse{partial_success{rejected_log_records: 1, error_message: "bad"}}
    let partial = vec![0x0a, 0x07, 0x08, 0x01, 0x12, 0x03, b'b', b'a', b'd'];
    Mock::given(method("POST"))
        .and(path("/v1/logs"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(partial))
        .up_to_n_times(1)
        .mount(&s)
        .await;
    Mock::given(method("POST"))
        .and(path("/v1/logs"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&s)
        .await;
    let c = client(Some(&s), None);
    let exp = c.otlp_exporter().unwrap();
    assert_eq!(exp.protocol(), OtlpProtocol::Protobuf);
    let mut other = record("second resource");
    other.resource.insert("service.name".into(), "other".into());
    let res = exp
        .export(&[record("one"), other.clone()], ExportOptions::new(true))
        .await
        .unwrap();
    assert_eq!((res.accepted, res.rejected), (1, 1));
    assert!(res.error.unwrap().message().contains("bad"));
    let res = exp
        .export(&[record("two")], ExportOptions::new(false))
        .await
        .unwrap();
    assert_eq!((res.accepted, res.rejected), (1, 0));
    let reqs = s.received_requests().await.unwrap();
    assert_eq!(
        header(&reqs[0], "content-type").as_deref(),
        Some("application/x-protobuf")
    );
    assert_eq!(
        header(&reqs[0], "authorization"),
        Some(format!("Bearer {TOKEN}"))
    );
    let pb = body(&reqs[0]);
    assert!(varints(&pb));
    assert!(pb.windows(3).any(|w| w == b"one"));
    assert!(pb.windows(5).any(|w| w == b"other"));
    assert!(format!("{exp:?}").contains("Protobuf"));

    let json_exp = c
        .new_otlp_exporter(OtlpExporterOptions {
            protocol: OtlpProtocol::Json,
            scope_name: Some("my-scope".into()),
            max_request_bytes: None,
        })
        .unwrap();
    let mut rich = record("json");
    rich.attributes
        .insert("list".into(), json!([1, 2.5, true, null, {"k": "v"}]));
    json_exp
        .export(&[rich.clone()], ExportOptions::new(false))
        .await
        .unwrap();
    let reqs = s.received_requests().await.unwrap();
    let v: Value = serde_json::from_slice(&body(&reqs[2])).unwrap();
    let lr = &v["resourceLogs"][0]["scopeLogs"][0]["logRecords"][0];
    assert_eq!(lr["body"]["stringValue"], "json");
    assert_eq!(lr["traceId"], "4bf92f3577b34da6a3ce929d0e0e4736");
    assert_eq!(
        v["resourceLogs"][0]["scopeLogs"][0]["scope"]["name"],
        "my-scope"
    );
    let pb = sdk::encode_otlp_protobuf(&[rich], "s");
    assert!(pb.len() > 50);

    // oversized and errors
    let tiny = c
        .new_otlp_exporter(OtlpExporterOptions {
            max_request_bytes: Some(10),
            ..Default::default()
        })
        .unwrap();
    assert_eq!(
        tiny.export(&[record("x")], ExportOptions::new(false))
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
    assert_eq!(
        c.new_otlp_exporter(OtlpExporterOptions {
            max_request_bytes: Some(0),
            ..Default::default()
        })
        .unwrap_err()
        .code(),
        ErrorCode::Validation
    );
    let s404 = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(fixture_response("otlpExport.v1_logs"))
        .mount(&s404)
        .await;
    let c404 = client(Some(&s404), None);
    let err = c404
        .otlp_exporter()
        .unwrap()
        .export(&[record("x")], ExportOptions::default())
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::NotFound);
}

#[tokio::test]
async fn otlp_partial_failure_after_first_chunk() {
    let s = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(200))
        .up_to_n_times(1)
        .mount(&s)
        .await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(403))
        .mount(&s)
        .await;
    let c = client(Some(&s), None);
    let one = c.otlp_exporter().unwrap().encode(&[record("1")]).len();
    let exp = c
        .new_otlp_exporter(OtlpExporterOptions {
            max_request_bytes: Some(one + 5),
            ..Default::default()
        })
        .unwrap();
    let res = exp
        .export(
            &[record("1"), record("2"), record("3")],
            ExportOptions::new(false),
        )
        .await
        .unwrap();
    assert_eq!(res.accepted, 1);
    assert_eq!(res.error.unwrap().code(), ErrorCode::Authentication);
}

#[test]
fn partial_success_parsing() {
    assert_eq!(
        sdk::parse_partial_success(
            br#"{"partialSuccess":{"rejectedLogRecords":"2","errorMessage":"m"}}"#,
            true
        ),
        Some(sdk::OtlpPartialSuccess {
            rejected: 2,
            message: Some("m".into())
        })
    );
    assert_eq!(
        sdk::parse_partial_success(br#"{"partialSuccess":{"rejectedLogRecords":3}}"#, true)
            .unwrap()
            .rejected,
        3
    );
    assert!(
        sdk::parse_partial_success(br#"{"partialSuccess":{"rejectedLogRecords":0}}"#, true)
            .is_none()
    );
    assert!(sdk::parse_partial_success(b"{}", true).is_none());
    assert!(sdk::parse_partial_success(b"", false).is_none());
    assert!(sdk::parse_partial_success(&[0x0a, 0x02, 0x08, 0x00], false).is_none());
    // unknown fields of every wire type are skipped; truncation is rejected
    let mut msg = vec![0x10, 0x05, 0x19];
    msg.extend_from_slice(&[0; 8]);
    msg.push(0x25);
    msg.extend_from_slice(&[0; 4]);
    msg.extend_from_slice(&[0x0a, 0x02, 0x08, 0x04]);
    assert_eq!(sdk::parse_partial_success(&msg, false).unwrap().rejected, 4);
    assert!(sdk::parse_partial_success(&[0x0a, 0x09, 0x08], false).is_none());
    assert!(sdk::parse_partial_success(&[0x0b], false).is_none());
    assert!(sdk::parse_partial_success(&[0x08, 0xff], false).is_none());
    assert_eq!(sdk::OTLP_LOGS_PATH, "/v1/logs");
    assert!(!sdk::DEFAULT_SCOPE_NAME.is_empty());
}

#[tokio::test]
async fn log_client_end_to_end() {
    let s = accept_all().await;
    let c = client(Some(&s), None);
    let lc = c
        .log_client(
            LogClientOptions::default()
                .resource_attribute("service.name", "checkout")
                .batch(
                    BatchOptions::default()
                        .max_batch_bytes(usize::MAX)
                        .gzip(false),
                )
                .exporter(IngestExporterOptions::default().encoding(Encoding::Json)),
        )
        .unwrap();
    assert!(format!("{:?}", LogClientOptions::default()).contains("Http"));
    lc.send(LogEntry::Input(
        LogInput::new("hello")
            .attribute("password", "hunter2")
            .severity(Severity::Error),
    ));
    lc.logger(Some("http"), attrs! {})
        .info("via logger", attrs! { "k" => "v" });
    lc.flush(None).await.unwrap();
    assert_eq!(lc.stats().sent, 2);
    let reqs = s.received_requests().await.unwrap();
    let text = String::from_utf8(body(&reqs[0])).unwrap();
    assert!(text.contains("checkout") && text.contains("via logger"));
    assert!(!text.contains("hunter2"), "{text}");

    // OTLP log client and a custom exporter; close flushes them
    let otlp = c
        .log_client(LogClientOptions::default().otlp(OtlpExporterOptions::default()))
        .unwrap();
    otlp.send("queued until close".into());
    let custom = c
        .log_client_with(
            Arc::new(c.exporter().unwrap()),
            LogClientOptions::default().protocol(LogProtocol::Http),
        )
        .unwrap();
    custom.send("custom".into());
    let otlp_default = c
        .log_client(LogClientOptions::default().protocol(LogProtocol::Otlp))
        .unwrap();
    otlp_default.send("otlp default".into());
    c.close().await;
    let reqs = s.received_requests().await.unwrap();
    assert!(reqs.iter().any(|r| r.url.path() == "/v1/logs"));
    assert_eq!(otlp.stats().sent, 1);
    assert_eq!(custom.stats().sent, 1);
}

#[tokio::test]
async fn query_only_client_cannot_log() {
    let q = MockServer::start().await;
    let c = client(None, Some(&q));
    assert_eq!(
        c.log_client(LogClientOptions::default())
            .unwrap_err()
            .code(),
        ErrorCode::Config
    );
    assert_eq!(
        c.new_ingest_exporter(IngestExporterOptions::default())
            .unwrap_err()
            .code(),
        ErrorCode::Config
    );
}
