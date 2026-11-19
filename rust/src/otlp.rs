//! OTLP/HTTP logs exporter to the source's ingesting host (`POST {url}/v1/logs`, API.md §4).
//!
//! Protobuf (`application/x-protobuf`, default) or OTLP/JSON (`application/json`). The protobuf
//! encoder is written by hand (a few message types only) to keep the dependency tree small.

use std::collections::BTreeMap;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use async_trait::async_trait;
use serde_json::{json, Value};

use crate::client::Inner;
use crate::core::transport::SDK_VERSION;
use crate::core::{Error, Result};
use crate::exporter::{gzip, MAX_REQUEST_BYTES};
use crate::http::map_ingest_error;
use crate::logging::{AttrValue, Attributes, ExportOptions, ExportResult, LogExporter, LogRecord};

/// OTLP logs path on the ingesting host.
pub const OTLP_LOGS_PATH: &str = "/v1/logs";
/// Default instrumentation scope name.
pub const DEFAULT_SCOPE_NAME: &str = "grow-better-stack-logs";

/// OTLP/HTTP payload format.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum OtlpProtocol {
    /// `application/x-protobuf` (default).
    #[default]
    Protobuf,
    /// `application/json` (OTLP/JSON).
    Json,
}

/// Options of [`BetterStackClient::new_otlp_exporter`](crate::BetterStackClient::new_otlp_exporter).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct OtlpExporterOptions {
    /// Payload format (default protobuf).
    pub protocol: OtlpProtocol,
    /// Instrumentation scope name (default [`DEFAULT_SCOPE_NAME`]).
    pub scope_name: Option<String>,
    /// Uncompressed bytes per request, 1..=10 MiB (default 10 MiB).
    pub max_request_bytes: Option<usize>,
}

// --- protobuf writer -----------------------------------------------------------------------

#[derive(Default)]
struct Pb(Vec<u8>);

impl Pb {
    fn varint(&mut self, mut v: u64) {
        while v >= 0x80 {
            self.0.push((v as u8) | 0x80);
            v >>= 7;
        }
        self.0.push(v as u8);
    }
    fn tag(&mut self, field: u32, wire: u8) {
        self.varint(u64::from(field << 3 | u32::from(wire)));
    }
    fn uint(&mut self, field: u32, v: u64) {
        self.tag(field, 0);
        self.varint(v);
    }
    fn fixed64(&mut self, field: u32, v: u64) {
        self.tag(field, 1);
        self.0.extend_from_slice(&v.to_le_bytes());
    }
    fn fixed32(&mut self, field: u32, v: u32) {
        self.tag(field, 5);
        self.0.extend_from_slice(&v.to_le_bytes());
    }
    fn double(&mut self, field: u32, v: f64) {
        self.tag(field, 1);
        self.0.extend_from_slice(&v.to_le_bytes());
    }
    fn bytes(&mut self, field: u32, b: &[u8]) {
        self.tag(field, 2);
        self.varint(b.len() as u64);
        self.0.extend_from_slice(b);
    }
    fn string(&mut self, field: u32, s: &str) {
        self.bytes(field, s.as_bytes());
    }
    fn message(&mut self, field: u32, m: Pb) {
        self.bytes(field, &m.0);
    }
}

fn pb_any(value: &AttrValue) -> Pb {
    let mut m = Pb::default();
    match value {
        Value::Null => {}
        Value::String(s) => m.string(1, s),
        Value::Bool(b) => m.uint(2, u64::from(*b)),
        Value::Number(n) => match n.as_i64() {
            Some(i) => m.uint(3, i as u64),
            None => m.double(4, n.as_f64().unwrap_or(0.0)),
        },
        Value::Array(items) => {
            let mut arr = Pb::default();
            for item in items {
                arr.message(1, pb_any(item));
            }
            m.message(5, arr);
        }
        Value::Object(map) => {
            let mut list = Pb::default();
            for (k, v) in map {
                list.message(1, pb_kv(k, v));
            }
            m.message(6, list);
        }
    }
    m
}

fn pb_kv(key: &str, value: &AttrValue) -> Pb {
    let mut kv = Pb::default();
    kv.string(1, key);
    kv.message(2, pb_any(value));
    kv
}

fn nanos(t: SystemTime) -> u64 {
    t.duration_since(UNIX_EPOCH)
        .map(|d| u64::try_from(d.as_nanos()).unwrap_or(u64::MAX))
        .unwrap_or(0)
}

fn hex_bytes(hex: &str) -> Option<Vec<u8>> {
    if !hex.len().is_multiple_of(2) {
        return None;
    }
    (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(hex.get(i..i + 2)?, 16).ok())
        .collect()
}

fn encode_log_record(r: &LogRecord) -> Pb {
    let mut m = Pb::default();
    m.fixed64(1, nanos(r.timestamp));
    m.uint(2, u64::from(r.severity.number()));
    m.string(3, &r.severity_text);
    m.message(5, pb_any(&Value::String(r.body.clone())));
    for (k, v) in &r.attributes {
        m.message(6, pb_kv(k, v));
    }
    if let Some(f) = r.trace_flags {
        m.fixed32(8, u32::from(f));
    }
    if let Some(t) = r.trace_id.as_deref().and_then(hex_bytes) {
        m.bytes(9, &t);
    }
    if let Some(s) = r.span_id.as_deref().and_then(hex_bytes) {
        m.bytes(10, &s);
    }
    if let Some(o) = r.observed_timestamp {
        m.fixed64(11, nanos(o));
    }
    m
}

fn group_by_resource(records: &[LogRecord]) -> Vec<(&Attributes, Vec<&LogRecord>)> {
    let mut groups: BTreeMap<String, (&Attributes, Vec<&LogRecord>)> = BTreeMap::new();
    let mut order: Vec<String> = Vec::new();
    for r in records {
        let key = serde_json::to_string(&r.resource).unwrap_or_default();
        groups
            .entry(key.clone())
            .or_insert_with(|| {
                order.push(key);
                (&r.resource, Vec::new())
            })
            .1
            .push(r);
    }
    order
        .into_iter()
        .filter_map(|k| groups.remove(&k))
        .collect()
}

/// Encodes an OTLP `ExportLogsServiceRequest` (collector/logs/v1) as protobuf; records are
/// grouped by resource, the scope is `scope_name` with the SDK version.
pub fn encode_otlp_protobuf(records: &[LogRecord], scope_name: &str) -> Vec<u8> {
    let mut req = Pb::default();
    for (resource, group) in group_by_resource(records) {
        let mut res = Pb::default();
        for (k, v) in resource {
            res.message(1, pb_kv(k, v));
        }
        let mut scope = Pb::default();
        scope.string(1, scope_name);
        scope.string(2, SDK_VERSION);
        let mut sl = Pb::default();
        sl.message(1, scope);
        for r in group {
            sl.message(2, encode_log_record(r));
        }
        let mut rl = Pb::default();
        rl.message(1, res);
        rl.message(2, sl);
        req.message(1, rl);
    }
    req.0
}

fn json_any(value: &AttrValue) -> Value {
    match value {
        Value::Null => json!({}),
        Value::String(s) => json!({ "stringValue": s }),
        Value::Bool(b) => json!({ "boolValue": b }),
        Value::Number(n) => match n.as_i64() {
            Some(i) => json!({ "intValue": i.to_string() }),
            None => json!({ "doubleValue": n.as_f64() }),
        },
        Value::Array(items) => {
            json!({ "arrayValue": { "values": items.iter().map(json_any).collect::<Vec<_>>() } })
        }
        Value::Object(map) => json!({ "kvlistValue": { "values": json_kvs(map.iter()) } }),
    }
}

fn json_kvs<'a>(attrs: impl Iterator<Item = (&'a String, &'a Value)>) -> Vec<Value> {
    attrs
        .map(|(k, v)| json!({ "key": k, "value": json_any(v) }))
        .collect()
}

/// Builds the OTLP/JSON `ExportLogsServiceRequest` object.
pub fn otlp_json_request(records: &[LogRecord], scope_name: &str) -> Value {
    let resource_logs: Vec<Value> = group_by_resource(records)
        .into_iter()
        .map(|(resource, group)| {
            let logs: Vec<Value> = group
                .into_iter()
                .map(|r| {
                    let mut out = json!({
                        "timeUnixNano": nanos(r.timestamp).to_string(),
                        "severityNumber": r.severity.number(),
                        "severityText": r.severity_text,
                        "body": { "stringValue": r.body },
                        "attributes": json_kvs(r.attributes.iter()),
                    });
                    if let Some(o) = r.observed_timestamp {
                        out["observedTimeUnixNano"] = nanos(o).to_string().into();
                    }
                    if let Some(f) = r.trace_flags {
                        out["flags"] = f.into();
                    }
                    if let Some(t) = &r.trace_id {
                        out["traceId"] = t.clone().into();
                    }
                    if let Some(s) = &r.span_id {
                        out["spanId"] = s.clone().into();
                    }
                    out
                })
                .collect();
            json!({
                "resource": { "attributes": json_kvs(resource.iter()) },
                "scopeLogs": [{ "scope": { "name": scope_name, "version": SDK_VERSION }, "logRecords": logs }],
            })
        })
        .collect();
    json!({ "resourceLogs": resource_logs })
}

// --- response parsing ------------------------------------------------------------------------

fn read_varint(data: &[u8], pos: &mut usize) -> Option<u64> {
    let mut result = 0u64;
    for shift in (0..64).step_by(7) {
        let byte = *data.get(*pos)?;
        *pos += 1;
        result |= u64::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Some(result);
        }
    }
    None
}

enum Field<'a> {
    Varint(u64),
    Bytes(&'a [u8]),
}

fn pb_fields(data: &[u8]) -> Option<Vec<(u64, Field<'_>)>> {
    let mut out = Vec::new();
    let mut pos = 0;
    while pos < data.len() {
        let key = read_varint(data, &mut pos)?;
        let field = key >> 3;
        match key & 7 {
            0 => out.push((field, Field::Varint(read_varint(data, &mut pos)?))),
            2 => {
                let len = usize::try_from(read_varint(data, &mut pos)?).ok()?;
                let end = pos.checked_add(len).filter(|e| *e <= data.len())?;
                out.push((field, Field::Bytes(&data[pos..end])));
                pos = end;
            }
            1 => pos += 8,
            5 => pos += 4,
            _ => return None,
        }
    }
    (pos == data.len()).then_some(out)
}

/// Partial success reported by an OTLP response.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OtlpPartialSuccess {
    /// Rejected log records.
    pub rejected: u64,
    /// Server message, when any.
    pub message: Option<String>,
}

/// Reads `partial_success` from an `ExportLogsServiceResponse` (protobuf or OTLP/JSON bytes).
/// Returns `None` when nothing was rejected or the body cannot be read.
pub fn parse_partial_success(body: &[u8], json: bool) -> Option<OtlpPartialSuccess> {
    let (rejected, message) = if json {
        let v: Value = serde_json::from_slice(body).ok()?;
        let ps = v.get("partialSuccess")?;
        let rejected = match ps.get("rejectedLogRecords")? {
            Value::String(s) => s.parse().ok()?,
            other => other.as_u64()?,
        };
        let msg = ps
            .get("errorMessage")
            .and_then(Value::as_str)
            .map(str::to_owned);
        (rejected, msg)
    } else {
        let mut found = None;
        for (f, v) in pb_fields(body)? {
            if let (1, Field::Bytes(b)) = (f, v) {
                let mut rejected = 0;
                let mut message = None;
                for (pf, pv) in pb_fields(b)? {
                    match (pf, pv) {
                        (1, Field::Varint(n)) => rejected = n,
                        (2, Field::Bytes(s)) => {
                            message = Some(String::from_utf8_lossy(s).into_owned())
                        }
                        _ => {}
                    }
                }
                found = Some((rejected, message));
            }
        }
        found?
    };
    (rejected > 0).then(|| OtlpPartialSuccess {
        rejected,
        message: message.filter(|m| !m.is_empty()),
    })
}

/// [`LogExporter`] that sends OTLP/HTTP logs to the source's ingesting host
/// (`POST {url}/v1/logs`) with the source token. Records are grouped by resource; batches are
/// split so no request exceeds the request limit; `partial_success` in the response counts as
/// rejected records. Retried like idempotent calls (duplicates possible after an ambiguous
/// failure).
#[derive(Clone)]
pub struct BetterStackOtlpLogExporter {
    inner: Arc<Inner>,
    protocol: OtlpProtocol,
    scope: String,
    max_request: usize,
}

impl std::fmt::Debug for BetterStackOtlpLogExporter {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("BetterStackOtlpLogExporter")
            .field("protocol", &self.protocol)
            .field("scope", &self.scope)
            .field("max_request_bytes", &self.max_request)
            .finish()
    }
}

impl BetterStackOtlpLogExporter {
    pub(crate) fn new(inner: Arc<Inner>, opts: OtlpExporterOptions) -> Result<Self> {
        let max_request = match opts.max_request_bytes {
            None => MAX_REQUEST_BYTES,
            Some(n) if (1..=MAX_REQUEST_BYTES).contains(&n) => n,
            Some(_) => {
                return Err(Error::validation(
                    "logs.otlp: max_request_bytes must be between 1 and 10 MiB",
                ))
            }
        };
        Ok(BetterStackOtlpLogExporter {
            inner,
            protocol: opts.protocol,
            scope: opts
                .scope_name
                .unwrap_or_else(|| DEFAULT_SCOPE_NAME.to_owned()),
            max_request,
        })
    }

    /// Payload format.
    pub fn protocol(&self) -> OtlpProtocol {
        self.protocol
    }

    /// Encodes records as one request body (protobuf bytes or OTLP/JSON text).
    pub fn encode(&self, records: &[LogRecord]) -> Vec<u8> {
        match self.protocol {
            OtlpProtocol::Protobuf => encode_otlp_protobuf(records, &self.scope),
            OtlpProtocol::Json => otlp_json_request(records, &self.scope)
                .to_string()
                .into_bytes(),
        }
    }

    fn chunks(&self, records: &[LogRecord]) -> (Vec<Vec<LogRecord>>, usize) {
        let mut chunks = Vec::new();
        let mut cur: Vec<LogRecord> = Vec::new();
        let mut bytes = 0;
        let mut oversized = 0;
        for r in records {
            let size = self.encode(std::slice::from_ref(r)).len();
            if size > self.max_request {
                oversized += 1;
                continue;
            }
            if !cur.is_empty() && bytes + size > self.max_request {
                chunks.push(std::mem::take(&mut cur));
                bytes = 0;
            }
            bytes += size;
            cur.push(r.clone());
        }
        if !cur.is_empty() {
            chunks.push(cur);
        }
        (chunks, oversized)
    }

    async fn post(&self, body: Vec<u8>, gz: bool) -> Result<Option<OtlpPartialSuccess>> {
        const OP: &str = "logs.otlpExport";
        let transport = self.inner.ingest_transport(OP)?;
        let token = self
            .inner
            .config
            .source_token()
            .ok_or_else(|| Error::config(format!("{OP}: source_token is required")))?;
        let (ct, json) = match self.protocol {
            OtlpProtocol::Protobuf => ("application/x-protobuf", false),
            OtlpProtocol::Json => ("application/json", true),
        };
        let mut req = transport
            .post(OTLP_LOGS_PATH)
            .operation(OP)
            .bearer(token)
            .header("accept", ct)
            .idempotent(true);
        req = if gz {
            req.header("content-encoding", "gzip").body(gzip(&body), ct)
        } else {
            req.body(body, ct)
        };
        let resp = req.send().await.map_err(map_ingest_error)?;
        Ok(parse_partial_success(resp.bytes(), json))
    }
}

#[async_trait]
impl LogExporter for BetterStackOtlpLogExporter {
    /// Sends records in one or more OTLP requests. Returns `Err` when nothing was accepted.
    async fn export(&self, records: &[LogRecord], opts: ExportOptions) -> Result<ExportResult> {
        let (chunks, oversized) = self.chunks(records);
        let mut accepted = 0usize;
        let mut rejected = oversized;
        let mut error = (oversized > 0).then(|| {
            Error::validation(format!(
                "logs.otlpExport: {oversized} record(s) larger than {} bytes dropped",
                self.max_request
            ))
        });
        for chunk in chunks {
            match self.post(self.encode(&chunk), opts.gzip).await {
                Ok(partial) => {
                    let rej = partial
                        .as_ref()
                        .map_or(0, |p| (p.rejected as usize).min(chunk.len()));
                    if let Some(p) = partial {
                        error = Some(Error::validation(format!(
                            "logs.otlpExport: the ingesting host rejected {} log records{}",
                            p.rejected,
                            p.message.map(|m| format!(": {m}")).unwrap_or_default()
                        )));
                    }
                    accepted += chunk.len() - rej;
                    rejected += rej;
                }
                Err(err) if accepted == 0 => return Err(err),
                Err(err) => {
                    return Ok(ExportResult::partial(
                        accepted,
                        records.len() - accepted,
                        Some(err),
                    ))
                }
            }
        }
        if accepted == 0 {
            if let Some(err) = error.take() {
                return Err(err);
            }
        }
        Ok(ExportResult::partial(accepted, rejected, error))
    }
}
