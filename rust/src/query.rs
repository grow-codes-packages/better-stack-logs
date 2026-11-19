//! SQL query API (ClickHouse HTTP interface on the query host), API.md §5.

use std::collections::{BTreeMap, HashSet};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use async_trait::async_trait;
use serde_json::{Map, Value};

use crate::client::Inner;
use crate::config::is_valid_identifier;
use crate::core::{CancellationToken, Error, Result, Secret};
use crate::http::map_query_error;
use crate::http::{base64_encode, base64url_decode, clickhouse_code, error_for_clickhouse};
use crate::logging::{
    poll_tail, Attributes, LogQueryClient, LogRecord, LogSearchResult, RecordCallback,
    SearchOptions, Severity, SortOrder, TailHandle, TailOptions,
};

/// Largest `limit` of one search page.
pub const MAX_SEARCH_LIMIT: usize = 10_000;
/// Default `limit` of one search page.
pub const DEFAULT_SEARCH_LIMIT: usize = 100;
const DAY: Duration = Duration::from_secs(24 * 60 * 60);

/// Value of a bound query parameter (`{name:Type}` placeholder + `param_<name>` URL
/// parameter). [`QueryParam::Null`] is sent as `\N` (use a `Nullable(…)` type).
#[derive(Debug, Clone, PartialEq)]
pub enum QueryParam {
    /// SQL `NULL`.
    Null,
    /// `true` / `false`.
    Bool(bool),
    /// Signed integer.
    Int(i64),
    /// Unsigned integer.
    UInt(u64),
    /// Float (must be finite).
    Float(f64),
    /// Text (also identifiers for `{name:Identifier}`).
    String(String),
    /// Time, sent as UTC `YYYY-MM-DD HH:MM:SS.ffffff` (for `DateTime64(6)` / `String`).
    Time(SystemTime),
}

macro_rules! param_from {
    ($($t:ty => $v:ident as $c:ty),* $(,)?) => {$(
        impl From<$t> for QueryParam {
            fn from(v: $t) -> Self {
                QueryParam::$v(v as $c)
            }
        }
    )*};
}
param_from!(i8 => Int as i64, i16 => Int as i64, i32 => Int as i64, i64 => Int as i64,
    u8 => UInt as u64, u16 => UInt as u64, u32 => UInt as u64, u64 => UInt as u64,
    usize => UInt as u64, f32 => Float as f64, f64 => Float as f64);

impl From<bool> for QueryParam {
    fn from(v: bool) -> Self {
        QueryParam::Bool(v)
    }
}
impl From<&str> for QueryParam {
    fn from(v: &str) -> Self {
        QueryParam::String(v.to_owned())
    }
}
impl From<String> for QueryParam {
    fn from(v: String) -> Self {
        QueryParam::String(v)
    }
}
impl From<SystemTime> for QueryParam {
    fn from(v: SystemTime) -> Self {
        QueryParam::Time(v)
    }
}
impl<T: Into<QueryParam>> From<Option<T>> for QueryParam {
    fn from(v: Option<T>) -> Self {
        v.map_or(QueryParam::Null, Into::into)
    }
}

impl QueryParam {
    /// The text sent as `param_<name>` (ClickHouse's escaped text form).
    pub fn to_param_text(&self) -> Result<String> {
        Ok(match self {
            QueryParam::Null => "\\N".to_owned(),
            QueryParam::Bool(b) => b.to_string(),
            QueryParam::Int(i) => i.to_string(),
            QueryParam::UInt(u) => u.to_string(),
            QueryParam::Float(f) if f.is_finite() => f.to_string(),
            QueryParam::Float(_) => {
                return Err(Error::validation("query: a float parameter must be finite"))
            }
            QueryParam::String(s) => s.clone(),
            QueryParam::Time(t) => format_datetime64(*t),
        })
    }
}

/// Bound parameters of [`BetterStackQuery::query`]: name → value.
pub type QueryParams = BTreeMap<String, QueryParam>;

/// One result row (JSON object; ClickHouse quotes 64-bit integers as strings).
pub type QueryRow = Map<String, Value>;

/// Builds [`QueryParams`]: `query_params! { "needle" => "x", "n" => 3 }`.
#[macro_export]
macro_rules! query_params {
    () => { $crate::QueryParams::new() };
    ($($k:expr => $v:expr),+ $(,)?) => {{
        let mut m = $crate::QueryParams::new();
        $( m.insert(::std::string::String::from($k), $crate::QueryParam::from($v)); )+
        m
    }};
}

/// Options of [`BetterStackQuery::query`] and [`BetterStackQuery::query_raw`].
#[derive(Debug, Clone, Default)]
pub struct QueryOptions {
    /// ClickHouse settings sent as URL parameters (e.g. `max_execution_time` → `30`).
    pub settings: BTreeMap<String, String>,
    /// Cancels the call (`CANCELLED`).
    pub cancel: Option<CancellationToken>,
}

impl QueryOptions {
    /// Adds one ClickHouse setting.
    pub fn setting(mut self, name: impl Into<String>, value: impl ToString) -> Self {
        self.settings.insert(name.into(), value.to_string());
        self
    }
    /// Sets the cancellation token.
    pub fn cancel(mut self, token: CancellationToken) -> Self {
        self.cancel = Some(token);
        self
    }
}

/// Options of [`BetterStackQuery::search_logs`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchLogsOptions {
    /// Collection (`t1234_myapp`); default: the configured `source_table`.
    pub source: Option<String>,
    /// Case-sensitive literal text searched in the raw event JSON; it is JSON-escaped first
    /// (`"`, `\` and control characters), so it matches text inside string values. Empty or
    /// unset matches everything.
    pub contains: Option<String>,
    /// Window start (inclusive); default 24 h before `end` (or before now).
    pub start: Option<SystemTime>,
    /// Window end (inclusive); default: no upper bound (robust to clock skew and ingest delay).
    pub end: Option<SystemTime>,
    /// Records per page, 1..=10000 (default 100).
    pub limit: usize,
    /// Newest first (default) or oldest first.
    pub order: SortOrder,
    /// Opaque cursor from [`LogSearchResult::next_cursor`].
    pub cursor: Option<String>,
}

impl Default for SearchLogsOptions {
    fn default() -> Self {
        SearchLogsOptions {
            source: None,
            contains: None,
            start: None,
            end: None,
            limit: DEFAULT_SEARCH_LIMIT,
            order: SortOrder::Desc,
            cursor: None,
        }
    }
}

impl SearchLogsOptions {
    /// Sets the collection.
    pub fn source(mut self, source: impl Into<String>) -> Self {
        self.source = Some(source.into());
        self
    }
    /// Sets the text to search.
    pub fn contains(mut self, text: impl Into<String>) -> Self {
        self.contains = Some(text.into());
        self
    }
    /// Sets the window (both ends inclusive).
    pub fn range(mut self, start: Option<SystemTime>, end: Option<SystemTime>) -> Self {
        self.start = start;
        self.end = end;
        self
    }
    /// Sets the page size.
    pub fn limit(mut self, limit: usize) -> Self {
        self.limit = limit;
        self
    }
    /// Sets the order.
    pub fn order(mut self, order: SortOrder) -> Self {
        self.order = order;
        self
    }
    /// Sets the cursor.
    pub fn cursor(mut self, cursor: impl Into<String>) -> Self {
        self.cursor = Some(cursor.into());
        self
    }
}

/// Escapes a value as a ClickHouse single-quoted string literal (`\`, `'` and control
/// characters escaped with a backslash).
pub fn sql_string(value: &str) -> String {
    let mut out = String::with_capacity(value.len() + 2);
    out.push('\'');
    for c in value.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '\'' => out.push_str("\\'"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\0' => out.push_str("\\0"),
            c => out.push(c),
        }
    }
    out.push('\'');
    out
}

/// JSON string escaping without the surrounding quotes, as in the raw event JSON.
pub fn json_escape(text: &str) -> String {
    let s = Value::String(text.to_owned()).to_string();
    s[1..s.len() - 1].to_owned()
}

/// `YYYY-MM-DD HH:MM:SS.ffffff` (UTC), the `DateTime64(6)` text form. Times before the epoch
/// are clamped to it.
pub fn format_datetime64(t: SystemTime) -> String {
    let t = if t < UNIX_EPOCH { UNIX_EPOCH } else { t };
    let s = humantime::format_rfc3339_micros(t).to_string(); // 2026-11-18T21:00:00.123456Z
    format!("{} {}", &s[..10], &s[11..26])
}

/// Parses a ClickHouse `DateTime64` text (`2026-11-18 21:00:00.123456`, UTC, 0–9 fraction
/// digits), an RFC 3339 string (`Z` or numeric offset) or UNIX seconds / milliseconds.
pub fn parse_datetime(value: &Value) -> Option<SystemTime> {
    match value {
        Value::Number(n) => {
            if let Some(i) = n.as_u64() {
                return Some(if i > 1_000_000_000_000 {
                    UNIX_EPOCH + Duration::from_millis(i)
                } else {
                    UNIX_EPOCH + Duration::from_secs(i)
                });
            }
            let f = n.as_f64()?;
            if !f.is_finite() || f < 0.0 {
                return None;
            }
            let secs = if f > 1e12 { f / 1000.0 } else { f };
            Some(UNIX_EPOCH + Duration::from_secs_f64(secs))
        }
        Value::String(s) => parse_datetime_text(s),
        _ => None,
    }
}

fn parse_datetime_text(text: &str) -> Option<SystemTime> {
    let s = text.trim();
    if !s.is_ascii() || s.len() < 19 || !matches!(s.as_bytes()[10], b' ' | b'T') {
        return None;
    }
    let (base, offset): (&str, i64) = if let Some(b) = s.strip_suffix('Z') {
        (b, 0)
    } else if s.len() >= 25 && matches!(s.as_bytes()[s.len() - 6], b'+' | b'-') {
        let tz = &s[s.len() - 6..];
        if tz.as_bytes()[3] != b':' {
            return None;
        }
        let h: i64 = tz[1..3].parse().ok()?;
        let m: i64 = tz[4..].parse().ok()?;
        let secs = h * 3600 + m * 60;
        (
            &s[..s.len() - 6],
            if tz.starts_with('+') { secs } else { -secs },
        )
    } else {
        (s, 0)
    };
    let t = humantime::parse_rfc3339_weak(base).ok()?;
    if offset >= 0 {
        t.checked_sub(Duration::from_secs(offset as u64))
    } else {
        t.checked_add(Duration::from_secs(offset.unsigned_abs()))
    }
}

const TOP_LEVEL_KEYS: &[&str] = &[
    "dt",
    "message",
    "msg",
    "level",
    "severity",
    "severity_number",
    "span",
    "trace_id",
    "span_id",
    "trace_flags",
    "resource",
    "attributes",
    "observed_dt",
];

fn hex_id(v: Option<&Value>, len: usize) -> Option<String> {
    let s = v?.as_str()?.trim().to_ascii_lowercase();
    (s.len() == len && s.bytes().all(|b| b.is_ascii_hexdigit()) && s.bytes().any(|b| b != b'0'))
        .then_some(s)
}

fn severity_from_value(v: &Value) -> Option<Severity> {
    match v {
        Value::Number(n) => n.as_u64().map(|n| Severity::from_number(n.min(255) as u8)),
        Value::String(s) => s.parse().ok(),
        _ => None,
    }
}

fn object_attributes(v: Option<&Value>) -> Attributes {
    match v {
        Some(Value::Object(m)) => m.iter().map(|(k, v)| (k.clone(), v.clone())).collect(),
        _ => Attributes::new(),
    }
}

/// Normalizes a `{dt, raw}` row into a [`LogRecord`] (API.md §5.3): `body` = `message` (else
/// `msg`, else the raw text), severity from `severity_number` / `level` / `severity`, trace ids
/// from `span.trace_id` / `span.span_id` (or top-level `trace_id` / `span_id`), `resource` from
/// `resource`, and every other field plus `attributes` into `attributes`.
pub fn log_record_from_row(row: &QueryRow) -> LogRecord {
    let raw_text = match row.get("raw") {
        Some(Value::String(s)) => s.clone(),
        Some(v) => v.to_string(),
        None => "null".to_owned(),
    };
    let parsed: Option<Value> = serde_json::from_str(&raw_text).ok();
    let empty = Map::new();
    let event = match &parsed {
        Some(Value::Object(m)) => m,
        _ => &empty,
    };
    let timestamp = row
        .get("dt")
        .and_then(parse_datetime)
        .or_else(|| event.get("dt").and_then(parse_datetime))
        .unwrap_or(UNIX_EPOCH);
    let body = match event.get("message").or_else(|| event.get("msg")) {
        Some(Value::String(s)) => s.clone(),
        Some(v) => v.to_string(),
        None => raw_text.clone(),
    };
    let level_text = ["level", "severity"].iter().find_map(|k| {
        event
            .get(*k)
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
    });
    let from_number = event.get("severity_number").and_then(severity_from_value);
    let from_text = level_text.and_then(|t| t.parse::<Severity>().ok());
    let severity = from_number.or(from_text).unwrap_or(Severity::Info);
    let severity_text = match (from_text, level_text) {
        (Some(s), _) => s.text().to_owned(),
        (None, Some(t)) => t.to_owned(),
        (None, None) => severity.text().to_owned(),
    };
    let mut attributes: Attributes = event
        .iter()
        .filter(|(k, _)| !TOP_LEVEL_KEYS.contains(&k.as_str()))
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    attributes.extend(object_attributes(event.get("attributes")));
    let span = event.get("span").and_then(Value::as_object);
    let trace_id = hex_id(
        span.and_then(|s| s.get("trace_id"))
            .or_else(|| event.get("trace_id")),
        32,
    );
    let (span_id, trace_flags) = if trace_id.is_some() {
        (
            hex_id(
                span.and_then(|s| s.get("span_id"))
                    .or_else(|| event.get("span_id")),
                16,
            ),
            event
                .get("trace_flags")
                .and_then(Value::as_u64)
                .map(|f| f.min(255) as u8),
        )
    } else {
        (None, None)
    };
    LogRecord {
        timestamp,
        observed_timestamp: event.get("observed_dt").and_then(parse_datetime),
        severity,
        severity_text,
        body,
        attributes,
        resource: object_attributes(event.get("resource")),
        trace_id,
        span_id,
        trace_flags,
    }
}

/// Raises the mapped error when a 200 body carries a ClickHouse exception (error after 200).
fn exception_in(text: &str) -> Error {
    if let Some(code) = clickhouse_code(text) {
        let line = text
            .lines()
            .find(|l| clickhouse_code(l).is_some())
            .unwrap_or(text)
            .trim();
        let line: String = line.chars().take(300).collect();
        let message = format!("query: {line}");
        return error_for_clickhouse(code, &message).unwrap_or_else(|| {
            Error::unavailable(message)
                .with_status(200)
                .with_retryable(false)
        });
    }
    Error::unavailable("query: invalid JSON in response").with_retryable(false)
}

fn rows_from_array(v: Vec<Value>) -> Result<Vec<QueryRow>> {
    v.into_iter()
        .map(|item| match item {
            Value::Object(m) => Ok(m),
            _ => Err(
                Error::unavailable("query: a result row is not a JSON object")
                    .with_retryable(false),
            ),
        })
        .collect()
}

/// Parses a JSON-family response body (`JSONEachRow`, `JSON`, `JSONEachRow` with
/// `output_format_json_array_of_rows`) into rows. A ClickHouse exception appended to the
/// body is returned as the mapped error.
pub fn parse_rows(text: &str) -> Result<Vec<QueryRow>> {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return Ok(Vec::new());
    }
    if trimmed.starts_with('[') {
        return match serde_json::from_str::<Vec<Value>>(trimmed) {
            Ok(v) => rows_from_array(v),
            Err(_) => Err(exception_in(trimmed)),
        };
    }
    let mut rows = Vec::new();
    for line in trimmed.lines().map(str::trim).filter(|l| !l.is_empty()) {
        match serde_json::from_str::<Value>(line) {
            Ok(Value::Object(m)) => rows.push(m),
            _ => {
                if rows.is_empty() {
                    if let Ok(Value::Object(mut doc)) = serde_json::from_str::<Value>(trimmed) {
                        if let Some(Value::Array(data)) = doc.remove("data") {
                            return rows_from_array(data);
                        }
                    }
                }
                return Err(exception_in(line));
            }
        }
    }
    if rows.len() == 1 && rows[0].contains_key("meta") {
        if let Some(Value::Array(data)) = rows[0].get("data").cloned() {
            return rows_from_array(data);
        }
    }
    Ok(rows)
}

const JSON_FORMATS: &[&str] = &[
    "jsoneachrow",
    "json",
    "jsonstringseachrow",
    "ndjson",
    "jsonlines",
];

/// The `FORMAT <name>` at the end of a statement, if any.
fn format_clause(sql: &str) -> Option<&str> {
    let words: Vec<&str> = sql.split_whitespace().collect();
    match words.as_slice() {
        [.., kw, name] if kw.eq_ignore_ascii_case("FORMAT") => Some(name.trim_end_matches(';')),
        _ => None,
    }
}

fn encode_cursor(offset: usize) -> String {
    base64_encode(format!("{{\"o\":{offset}}}").as_bytes(), true)
}

fn decode_cursor(cursor: &str) -> Result<usize> {
    base64url_decode(cursor)
        .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
        .and_then(|v| v.get("o").and_then(Value::as_u64))
        .map(|o| o as usize)
        .ok_or_else(|| Error::validation("logs.search: invalid cursor"))
}

/// SQL query client for Better Stack Logs (ClickHouse HTTP interface with the connection's own
/// Basic credentials). Implements [`LogQueryClient`]: `search` runs
/// [`search_logs`](Self::search_logs) on the configured `source_table`, `tail` polls `search`,
/// `list_streams` returns the configured collection and `list_labels` is `UNSUPPORTED`
/// (Better Stack has no labels). Cheap to clone.
#[derive(Clone)]
pub struct BetterStackQuery {
    inner: Arc<Inner>,
}

impl std::fmt::Debug for BetterStackQuery {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("BetterStackQuery")
            .field("source_table", &self.inner.config.source_table())
            .finish()
    }
}

impl BetterStackQuery {
    pub(crate) fn new(inner: Arc<Inner>) -> Self {
        BetterStackQuery { inner }
    }

    /// Runs SQL and returns the response text unchanged. `params` are bound server side
    /// (`{name:Type}` placeholders + `param_<name>` URL parameters).
    pub async fn query_raw(
        &self,
        sql: &str,
        params: &QueryParams,
        opts: &QueryOptions,
    ) -> Result<String> {
        const OP: &str = "query";
        if sql.trim().is_empty() {
            return Err(Error::validation("query: sql must not be empty"));
        }
        let transport = self.inner.query_transport(OP)?;
        let basic = self
            .inner
            .config
            .basic_credentials()
            .ok_or_else(|| Error::config("query: query credentials are required"))?;
        let mut req = transport
            .post("/")
            .operation(OP)
            .secret_header(
                "authorization",
                &Secret::new(format!("Basic {}", basic.expose_secret())),
            )
            .idempotent(true);
        for (k, v) in &opts.settings {
            req = req.query(k.clone(), v.clone());
        }
        for (name, value) in params {
            if !is_valid_identifier(name) {
                return Err(Error::validation(format!(
                    "query: invalid parameter name {name:?}"
                )));
            }
            req = req.query(format!("param_{name}"), value.to_param_text()?);
        }
        if let Some(token) = &opts.cancel {
            req = req.cancel_token(token.clone());
        }
        let resp = req
            .body(sql.as_bytes().to_vec(), "text/plain; charset=utf-8")
            .send()
            .await
            .map_err(map_query_error)?;
        resp.text()
    }

    /// Runs SQL and returns the rows. Without a `FORMAT` clause, `FORMAT JSONEachRow` is
    /// appended; `FORMAT JSON` / `JSONEachRow` (also with `output_format_json_array_of_rows`)
    /// are parsed, other formats are a `VALIDATION` error (use [`query_raw`](Self::query_raw)).
    /// A ClickHouse exception appended to a 200 response is returned as the mapped error.
    ///
    /// ```no_run
    /// # async fn demo(q: grow_better_stack_logs::BetterStackQuery) -> grow_better_stack_logs::Result<()> {
    /// use grow_better_stack_logs::{query_params, QueryOptions};
    /// let rows = q
    ///     .query(
    ///         "SELECT count() AS c FROM remote(t1_app_logs) WHERE position(raw, {needle:String}) > 0",
    ///         &query_params! { "needle" => "checkout" },
    ///         &QueryOptions::default(),
    ///     )
    ///     .await?;
    /// # Ok(()) }
    /// ```
    pub async fn query(
        &self,
        sql: &str,
        params: &QueryParams,
        opts: &QueryOptions,
    ) -> Result<Vec<QueryRow>> {
        let trimmed = sql.trim().trim_end_matches(';').trim_end();
        if trimmed.is_empty() {
            return Err(Error::validation("query: sql must not be empty"));
        }
        let statement = match format_clause(trimmed) {
            Some(name) if JSON_FORMATS.contains(&name.to_ascii_lowercase().as_str()) => {
                trimmed.to_owned()
            }
            Some(name) => {
                return Err(Error::validation(format!(
                    "query: FORMAT {name} cannot be parsed into rows; use query_raw or FORMAT JSONEachRow"
                )))
            }
            None => format!("{trimmed} FORMAT JSONEachRow"),
        };
        let text = self.query_raw(&statement, params, opts).await?;
        parse_rows(&text)
    }

    /// Renders the [`search_logs`](Self::search_logs) SQL: hot `remote(<source>_logs)` UNION ALL
    /// cold `s3Cluster(primary, <source>_s3)`, values as escaped literals,
    /// `toDateTime64(…, 6, 'UTC')` window, `position(raw, …)` substring match, `LIMIT n+1`.
    pub fn render_search_sql(&self, opts: &SearchLogsOptions) -> Result<String> {
        let source = opts
            .source
            .as_deref()
            .or(self.inner.config.source_table())
            .ok_or_else(|| {
                Error::config(
                    "invalid configuration: search_logs needs a source or a configured source_table (GROW_BETTER_STACK_LOGS_SOURCE_TABLE)",
                )
            })?;
        if !is_valid_identifier(source) {
            return Err(Error::validation(
                "logs.search: source must match ^[A-Za-z_][A-Za-z0-9_]*$",
            ));
        }
        if !(1..=MAX_SEARCH_LIMIT).contains(&opts.limit) {
            return Err(Error::validation(format!(
                "logs.search: limit must be between 1 and {MAX_SEARCH_LIMIT}"
            )));
        }
        let start = match (opts.start, opts.end) {
            (Some(s), _) => s,
            (None, Some(e)) => e.checked_sub(DAY).unwrap_or(UNIX_EPOCH),
            (None, None) => SystemTime::now() - DAY,
        };
        if opts.end.is_some_and(|e| start > e) {
            return Err(Error::validation(
                "logs.search: start must not be after end",
            ));
        }
        let offset = match &opts.cursor {
            Some(c) => decode_cursor(c)?,
            None => 0,
        };
        let ts = |t: SystemTime| {
            format!(
                "toDateTime64({}, 6, 'UTC')",
                sql_string(&format_datetime64(t))
            )
        };
        let mut wheres = vec![match opts.end {
            None => format!("dt >= {}", ts(start)),
            Some(e) => format!("dt BETWEEN {} AND {}", ts(start), ts(e)),
        }];
        if let Some(c) = opts.contains.as_deref().filter(|c| !c.is_empty()) {
            wheres.push(format!(
                "position(raw, {}) > 0",
                sql_string(&json_escape(c))
            ));
        }
        let order = match opts.order {
            SortOrder::Desc => "DESC",
            SortOrder::Asc => "ASC",
        };
        let offset_sql = if offset > 0 {
            format!(" OFFSET {offset}")
        } else {
            String::new()
        };
        Ok(format!(
            "SELECT dt, raw FROM (SELECT dt, raw FROM remote({source}_logs) UNION ALL \
             SELECT dt, raw FROM s3Cluster(primary, {source}_s3) WHERE _row_type = 1) \
             WHERE {} ORDER BY dt {order} LIMIT {}{offset_sql} FORMAT JSONEachRow",
            wheres.join(" AND "),
            opts.limit + 1
        ))
    }

    /// Searches hot and cold logs of a collection: inclusive time window, literal substring
    /// match, newest first by default. Rows are normalized ([`log_record_from_row`]) and
    /// deduplicated on `(dt, raw)`; `next_cursor` is set when more rows exist.
    pub async fn search_logs(&self, opts: SearchLogsOptions) -> Result<LogSearchResult> {
        self.search_logs_with(opts, &QueryOptions::default()).await
    }

    /// [`search_logs`](Self::search_logs) with query options (settings, cancellation).
    pub async fn search_logs_with(
        &self,
        opts: SearchLogsOptions,
        query_opts: &QueryOptions,
    ) -> Result<LogSearchResult> {
        let sql = self.render_search_sql(&opts)?;
        let offset = match &opts.cursor {
            Some(c) => decode_cursor(c)?,
            None => 0,
        };
        let rows = self.query(&sql, &QueryParams::new(), query_opts).await?;
        let more = rows.len() > opts.limit;
        let mut seen = HashSet::new();
        let records = rows
            .iter()
            .take(opts.limit)
            .filter(|row| {
                let key = (
                    row.get("dt").map(Value::to_string),
                    row.get("raw").map(Value::to_string),
                );
                seen.insert(key)
            })
            .map(log_record_from_row)
            .collect();
        Ok(LogSearchResult {
            records,
            next_cursor: more.then(|| encode_cursor(offset + opts.limit)),
            total: None,
        })
    }
}

#[async_trait]
impl LogQueryClient for BetterStackQuery {
    /// `query` is the `contains` text, searched in the configured `source_table`.
    async fn search(&self, query: &str, opts: SearchOptions) -> Result<LogSearchResult> {
        self.search_logs(SearchLogsOptions {
            source: None,
            contains: Some(query.to_owned()),
            start: opts.start,
            end: opts.end,
            limit: opts.limit,
            order: opts.order,
            cursor: opts.cursor,
        })
        .await
    }

    /// Polls [`search`](LogQueryClient::search) oldest-first (Better Stack has no public
    /// streaming endpoint); stopped by the handle or by the client's `close`.
    async fn tail(
        &self,
        query: &str,
        on_record: RecordCallback,
        opts: TailOptions,
    ) -> Result<TailHandle> {
        self.inner.query_transport("logs.tail")?;
        if self.inner.config.source_table().is_none() {
            return Err(Error::config(
                "logs.tail: a configured source_table is required",
            ));
        }
        let handle = poll_tail(Arc::new(self.clone()), query, on_record, opts);
        let h = handle.clone();
        self.inner.on_close.register(move || {
            Box::pin(async move {
                h.stop();
            })
        });
        Ok(handle)
    }

    /// The configured collections (`[source_table]`, or `[]`); Better Stack has no data-plane
    /// listing.
    async fn list_streams(&self) -> Result<Vec<String>> {
        Ok(self
            .inner
            .config
            .source_table()
            .map(|t| vec![t.to_owned()])
            .unwrap_or_default())
    }

    /// Always `UNSUPPORTED`: Better Stack has no label concept.
    async fn list_labels(&self) -> Result<Vec<String>> {
        Err(Error::unsupported(
            "logs.listLabels: Better Stack has no labels",
        ))
    }
}
