// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! # grow-better-stack-logs
//!
//! Rust SDK for [Better Stack Logs](https://grow-codes-packages.github.io/better-stack-logs/) by
//! Grow Codes. Not affiliated with Better Stack, Inc.
//!
//! Better Stack has two hosts with separate credentials: the source's **ingesting host**
//! (source token, `POST /` with JSON / NDJSON / MessagePack, or OTLP `/v1/logs`) and the
//! **SQL query host** (ClickHouse HTTP interface with a connection's username and password).
//!
//! ```no_run
//! # async fn demo() -> grow_better_stack_logs::Result<()> {
//! use grow_better_stack_logs::logging::{attrs, LogClient};
//! use grow_better_stack_logs::{BetterStackClient, LogClientOptions, SearchLogsOptions};
//!
//! let client = BetterStackClient::from_env()?;
//! let logs = client.log_client(LogClientOptions::default())?;
//! logs.logger(None, attrs! {}).info("order placed", attrs! { "order_id" => 42 });
//! logs.flush(None).await?;
//!
//! let found = client
//!     .query()?
//!     .search_logs(SearchLogsOptions::default().contains("order placed"))
//!     .await?;
//! println!("{} records", found.records.len());
//! client.close().await;
//! # Ok(())
//! # }
//! ```
//!
//! Features: `blocking` (a blocking client), `testing` (in-memory fakes and the conformance
//! runner), `tracing-layer` and `log-bridge` (logging adapters, on by default).
#![cfg_attr(docsrs, feature(doc_cfg))]

pub mod core;

mod client;
mod config;
mod encoding;
mod exporter;
mod http;
mod otlp;
mod query;

#[cfg(feature = "blocking")]
#[cfg_attr(docsrs, doc(cfg(feature = "blocking")))]
pub mod blocking;

#[cfg(feature = "testing")]
#[cfg_attr(docsrs, doc(cfg(feature = "testing")))]
pub mod testing;

pub mod logging;

pub use crate::core::{Error, ErrorCode, HealthStatus, Result, RetryPolicy, Secret};

pub use crate::client::{BetterStackClient, BetterStackLogClient, LogClientOptions, LogProtocol};
pub use crate::config::{
    is_valid_identifier, normalize_base_url, BetterStackConfig, BetterStackConfigBuilder, Encoding,
    DEFAULT_URL, VENDOR_QUERY_PASSWORD_VARS, VENDOR_QUERY_URL_VARS, VENDOR_QUERY_USERNAME_VARS,
    VENDOR_SOURCE_TABLE_VARS, VENDOR_SOURCE_TOKEN_VARS, VENDOR_URL_VARS,
};
pub use crate::encoding::{
    encode_event, encode_msgpack, encode_record, format_event_time, frame, to_better_stack_event,
};
pub use crate::exporter::{
    BetterStackLogExporter, IngestExporterOptions, SendEventsOptions, MAX_RECORD_BYTES,
    MAX_REQUEST_BYTES,
};
pub use crate::http::{
    clickhouse_code, code_for_clickhouse, error_for_clickhouse, map_ingest_error, map_query_error,
};
pub use crate::otlp::{
    encode_otlp_protobuf, otlp_json_request, parse_partial_success, BetterStackOtlpLogExporter,
    OtlpExporterOptions, OtlpPartialSuccess, OtlpProtocol, DEFAULT_SCOPE_NAME, OTLP_LOGS_PATH,
};
pub use crate::query::{
    escape_param_value, format_datetime64, json_escape, log_record_from_row, parse_datetime,
    parse_rows, sql_string, BetterStackQuery, QueryOptions, QueryParam, QueryParams, QueryRow,
    SearchLogsOptions, DEFAULT_SEARCH_LIMIT, MAX_SEARCH_LIMIT,
};

/// Service-prefixed alias of [`Error`].
pub type BetterStackError = Error;
