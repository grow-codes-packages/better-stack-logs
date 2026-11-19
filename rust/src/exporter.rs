//! The Better Stack ingest exporter: `POST {ingesting host}/` (API.md §3).

use std::io::Write;
use std::sync::Arc;

use async_trait::async_trait;
use flate2::write::GzEncoder;
use flate2::Compression;
use serde_json::Value;

use crate::client::Inner;
use crate::config::Encoding;
use crate::core::{Error, Result};
use crate::encoding::{encode_event, encode_record, frame, FRAME_OVERHEAD};
use crate::http::map_ingest_error;
use crate::logging::{ExportOptions, ExportResult, LogExporter, LogRecord};

/// Better Stack limit for one request body (10 MiB, measured compressed). The exporter never
/// builds a larger uncompressed body, so the limit always holds.
pub const MAX_REQUEST_BYTES: usize = 10 * 1024 * 1024;
/// Better Stack limit for one record (10 MiB uncompressed).
pub const MAX_RECORD_BYTES: usize = 10 * 1024 * 1024;

/// Options of [`BetterStackClient::new_ingest_exporter`](crate::BetterStackClient::new_ingest_exporter).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct IngestExporterOptions {
    /// Body encoding (default: the config's encoding).
    pub encoding: Option<Encoding>,
    /// Uncompressed bytes per request, 1..=10 MiB (default 10 MiB).
    pub max_request_bytes: Option<usize>,
    /// Encoded bytes per record, 1..=10 MiB (default 10 MiB); larger records are rejected.
    pub max_record_bytes: Option<usize>,
}

impl IngestExporterOptions {
    /// Sets the encoding.
    pub fn encoding(mut self, encoding: Encoding) -> Self {
        self.encoding = Some(encoding);
        self
    }
    /// Sets the request size limit.
    pub fn max_request_bytes(mut self, n: usize) -> Self {
        self.max_request_bytes = Some(n);
        self
    }
    /// Sets the record size limit.
    pub fn max_record_bytes(mut self, n: usize) -> Self {
        self.max_record_bytes = Some(n);
        self
    }
}

/// Options of [`BetterStackLogExporter::send_events`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SendEventsOptions {
    /// Body encoding (default: the exporter's encoding).
    pub encoding: Option<Encoding>,
    /// Gzip the body (`Content-Encoding: gzip`, default `true`).
    pub gzip: bool,
}

impl Default for SendEventsOptions {
    fn default() -> Self {
        SendEventsOptions {
            encoding: None,
            gzip: true,
        }
    }
}

/// [`LogExporter`] for Better Stack's HTTP ingest API: maps records to events
/// ([`to_better_stack_event`](crate::to_better_stack_event)), splits batches so no request
/// exceeds 10 MiB, encodes them as JSON, NDJSON or MessagePack, gzips when asked and posts with
/// `Authorization: Bearer <source token>`. Exports are retried like idempotent calls (429, 5xx,
/// network errors); Better Stack does not deduplicate, so an ambiguous failure may store a
/// record twice.
#[derive(Clone)]
pub struct BetterStackLogExporter {
    inner: Arc<Inner>,
    encoding: Encoding,
    max_request: usize,
    max_record: usize,
}

impl std::fmt::Debug for BetterStackLogExporter {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("BetterStackLogExporter")
            .field("encoding", &self.encoding)
            .field("max_request_bytes", &self.max_request)
            .field("max_record_bytes", &self.max_record)
            .finish()
    }
}

fn limit(value: Option<usize>, name: &str) -> Result<usize> {
    match value {
        None => Ok(MAX_REQUEST_BYTES),
        Some(n) if (1..=MAX_REQUEST_BYTES).contains(&n) => Ok(n),
        Some(_) => Err(Error::validation(format!(
            "logs.exporter: {name} must be between 1 and 10 MiB"
        ))),
    }
}

pub(crate) fn gzip(body: &[u8]) -> Vec<u8> {
    let mut enc = GzEncoder::new(Vec::with_capacity(body.len() / 4 + 64), Compression::fast());
    // Writing into a Vec cannot fail.
    let _ = enc.write_all(body);
    enc.finish().unwrap_or_default()
}

impl BetterStackLogExporter {
    pub(crate) fn new(inner: Arc<Inner>, opts: IngestExporterOptions) -> Result<Self> {
        let encoding = opts.encoding.unwrap_or(inner.config.encoding());
        Ok(BetterStackLogExporter {
            inner,
            encoding,
            max_request: limit(opts.max_request_bytes, "max_request_bytes")?,
            max_record: limit(opts.max_record_bytes, "max_record_bytes")?,
        })
    }

    /// Body encoding of [`export`](LogExporter::export).
    pub fn encoding(&self) -> Encoding {
        self.encoding
    }

    /// Uncompressed size limit of one request.
    pub fn max_request_bytes(&self) -> usize {
        self.max_request
    }

    /// Encoded size limit of one record.
    pub fn max_record_bytes(&self) -> usize {
        self.max_record
    }

    /// Splits encoded records into request chunks that stay within the request limit.
    fn split(&self, encoded: Vec<Vec<u8>>) -> Vec<Vec<Vec<u8>>> {
        let mut out = Vec::new();
        let mut cur: Vec<Vec<u8>> = Vec::new();
        let mut size = FRAME_OVERHEAD;
        for e in encoded {
            let n = e.len() + 1;
            if !cur.is_empty() && size + n > self.max_request {
                out.push(std::mem::take(&mut cur));
                size = FRAME_OVERHEAD;
            }
            size += n;
            cur.push(e);
        }
        if !cur.is_empty() {
            out.push(cur);
        }
        out
    }

    /// Posts raw events (JSON objects; `dt` may be an RFC 3339 string or UNIX seconds/ms/ns) as
    /// one request, without splitting. Errors: 403 → `AUTHENTICATION`, 402 → `RATE_LIMITED` (not
    /// retryable), 406/413 → `VALIDATION`; a body over the request limit is a `VALIDATION`
    /// error before anything is sent.
    pub async fn send_events(&self, events: &[Value], opts: SendEventsOptions) -> Result<()> {
        const OP: &str = "logs.sendEvents";
        let encoding = opts.encoding.unwrap_or(self.encoding);
        let encoded = events
            .iter()
            .map(|e| encode_event(e, encoding))
            .collect::<Result<Vec<_>>>()?;
        let body = frame(encoding, &encoded);
        if body.len() > self.max_request {
            return Err(Error::validation(format!(
                "{OP}: request body of {} bytes exceeds {} bytes",
                body.len(),
                self.max_request
            )));
        }
        self.inner
            .post_ingest(OP, "/", body, encoding.content_type(), opts.gzip)
            .await
    }
}

#[async_trait]
impl LogExporter for BetterStackLogExporter {
    /// Encodes and posts records. Records over the record limit are rejected with a
    /// `VALIDATION` error before anything is sent; a failed request fails its records and the
    /// rest of the batch. Returns `Err` when nothing was accepted.
    async fn export(&self, records: &[LogRecord], opts: ExportOptions) -> Result<ExportResult> {
        const OP: &str = "logs.export";
        self.inner.ingest_transport(OP)?;
        let mut rejected = 0usize;
        let mut encoded = Vec::with_capacity(records.len());
        for r in records {
            let e = encode_record(r, self.encoding);
            if e.len() > self.max_record || e.len() + FRAME_OVERHEAD > self.max_request {
                rejected += 1;
            } else {
                encoded.push(e);
            }
        }
        let mut error = (rejected > 0).then(|| {
            Error::validation(format!(
                "{OP}: {rejected} record(s) larger than {} bytes dropped",
                self.max_record.min(self.max_request - FRAME_OVERHEAD)
            ))
        });
        let mut accepted = 0usize;
        for chunk in self.split(encoded) {
            let body = frame(self.encoding, &chunk);
            if let Err(err) = self
                .inner
                .post_ingest(OP, "/", body, self.encoding.content_type(), opts.gzip)
                .await
            {
                if accepted == 0 {
                    return Err(err);
                }
                return Ok(ExportResult::partial(
                    accepted,
                    records.len() - accepted,
                    Some(err),
                ));
            }
            accepted += chunk.len();
        }
        if accepted == 0 {
            if let Some(err) = error.take() {
                return Err(err);
            }
        }
        Ok(ExportResult::partial(accepted, rejected, error))
    }
}

impl Inner {
    /// Sends one ingest request body (optionally gzipped) with the source token.
    pub(crate) async fn post_ingest(
        &self,
        operation: &str,
        path: &str,
        body: Vec<u8>,
        content_type: &str,
        gz: bool,
    ) -> Result<()> {
        let transport = self.ingest_transport(operation)?;
        let token = self
            .config
            .source_token()
            .ok_or_else(|| Error::config(format!("{operation}: source_token is required")))?;
        let mut req = transport
            .post(path)
            .operation(operation)
            .bearer(token)
            .idempotent(true);
        if gz {
            req = req
                .header("content-encoding", "gzip")
                .body(gzip(&body), content_type);
        } else {
            req = req.body(body, content_type);
        }
        req.send().await.map(|_| ()).map_err(map_ingest_error)
    }
}
