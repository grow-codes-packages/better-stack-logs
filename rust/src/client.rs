// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! `BetterStackClient`: entry point of the SDK (SPEC §2.4, API.md §6, DECISIONS 4 and 5).

use std::sync::{Arc, Once};
use std::time::{Duration, Instant};

use futures::future::BoxFuture;

use crate::config::BetterStackConfig;
use crate::core::lifecycle::CloseGroup;
use crate::core::{Error, HealthStatus, HttpTransport, Result};
use crate::exporter::{BetterStackLogExporter, IngestExporterOptions, MAX_REQUEST_BYTES};
use crate::logging::{
    Attributes, BatchOptions, BatchingLogClient, LogClient, LogExporter, RedactionProcessor,
    Severity,
};
use crate::otlp::{BetterStackOtlpLogExporter, OtlpExporterOptions};
use crate::query::BetterStackQuery;

/// The batching [`LogClient`] returned by [`BetterStackClient::log_client`].
pub type BetterStackLogClient = BatchingLogClient;

static PLAIN_HTTP_WARNING: Once = Once::new();

/// Wire protocol of [`BetterStackClient::log_client`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum LogProtocol {
    /// Better Stack HTTP ingest API (`POST /`, JSON / NDJSON / MessagePack). Default.
    #[default]
    Http,
    /// OTLP/HTTP (`POST /v1/logs`).
    Otlp,
}

/// Options of [`BetterStackClient::log_client`].
#[derive(Clone)]
pub struct LogClientOptions {
    /// Batch options; `max_batch_bytes` is capped at the 10 MiB request limit.
    pub batch: BatchOptions,
    /// Resource attributes added to every record (`service.name`, …).
    pub resource: Attributes,
    /// Redaction applied to every record (default [`RedactionProcessor::default`]); `None`
    /// disables it.
    pub redaction: Option<RedactionProcessor>,
    /// Minimum severity of loggers made by [`LogClient::logger`] (default `Info`).
    pub min_severity: Severity,
    /// HTTP ingest (default) or OTLP.
    pub protocol: LogProtocol,
    /// Encoding and limits of the HTTP exporter (protocol `Http`); default: the client's
    /// shared exporter.
    pub exporter: Option<IngestExporterOptions>,
    /// Options of the OTLP exporter (protocol `Otlp`); default: the client's shared exporter.
    pub otlp: Option<OtlpExporterOptions>,
}

impl Default for LogClientOptions {
    fn default() -> Self {
        LogClientOptions {
            batch: BatchOptions::default(),
            resource: Attributes::new(),
            redaction: Some(RedactionProcessor::default()),
            min_severity: Severity::Info,
            protocol: LogProtocol::Http,
            exporter: None,
            otlp: None,
        }
    }
}

impl std::fmt::Debug for LogClientOptions {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LogClientOptions")
            .field("batch", &self.batch)
            .field("resource", &self.resource)
            .field("redaction", &self.redaction.is_some())
            .field("min_severity", &self.min_severity)
            .field("protocol", &self.protocol)
            .field("exporter", &self.exporter)
            .field("otlp", &self.otlp)
            .finish()
    }
}

impl LogClientOptions {
    /// Sets the batch options.
    pub fn batch(mut self, batch: BatchOptions) -> Self {
        self.batch = batch;
        self
    }
    /// Adds one resource attribute.
    pub fn resource_attribute(
        mut self,
        key: impl Into<String>,
        value: impl Into<crate::logging::AttrValue>,
    ) -> Self {
        self.resource.insert(key.into(), value.into());
        self
    }
    /// Sets the protocol.
    pub fn protocol(mut self, protocol: LogProtocol) -> Self {
        self.protocol = protocol;
        self
    }
    /// Sets the HTTP exporter options.
    pub fn exporter(mut self, opts: IngestExporterOptions) -> Self {
        self.exporter = Some(opts);
        self
    }
    /// Sets the OTLP exporter options (and selects OTLP).
    pub fn otlp(mut self, opts: OtlpExporterOptions) -> Self {
        self.protocol = LogProtocol::Otlp;
        self.otlp = Some(opts);
        self
    }
}

/// Async client for Better Stack Logs. Cheap to clone (clones share the connections).
///
/// It talks to two hosts with separate credentials and transports: the source's ingesting host
/// ([`log_client`](Self::log_client), [`exporter`](Self::exporter),
/// [`otlp_exporter`](Self::otlp_exporter)) and the SQL query host ([`query`](Self::query)). A
/// source token is never sent to the query host and SQL credentials never to the ingest host.
///
/// ```no_run
/// # async fn demo() -> grow_better_stack_logs::Result<()> {
/// use grow_better_stack_logs::logging::{attrs, LogClient};
/// use grow_better_stack_logs::{BetterStackClient, LogClientOptions};
///
/// let client = BetterStackClient::from_env()?;
/// let logs = client.log_client(LogClientOptions::default().resource_attribute("service.name", "checkout"))?;
/// logs.logger(None, attrs! {}).info("order placed", attrs! { "order_id" => 42 });
/// client.close().await;
/// # Ok(()) }
/// ```
#[derive(Debug, Clone)]
pub struct BetterStackClient {
    inner: Arc<Inner>,
}

#[derive(Debug)]
pub(crate) struct Inner {
    pub(crate) config: BetterStackConfig,
    pub(crate) ingest: Option<HttpTransport>,
    pub(crate) query: Option<HttpTransport>,
    pub(crate) on_close: CloseGroup,
}

impl Inner {
    pub(crate) fn ingest_transport(&self, operation: &str) -> Result<&HttpTransport> {
        self.ingest.as_ref().ok_or_else(|| {
            Error::config(format!(
                "{operation}: url (the source's ingesting host) and source_token are required for ingestion"
            ))
        })
    }

    pub(crate) fn query_transport(&self, operation: &str) -> Result<&HttpTransport> {
        self.query.as_ref().ok_or_else(|| {
            Error::config(format!(
                "{operation}: query_url, query_username and query_password are required for SQL queries"
            ))
        })
    }
}

impl BetterStackClient {
    /// Creates a client from a configuration (`CONFIG` when invalid).
    pub fn new(config: BetterStackConfig) -> Result<Self> {
        config.validate()?;
        let secrets = config.known_secrets();
        let make = |common: Option<&crate::core::CommonConfig>| -> Result<Option<HttpTransport>> {
            let Some(common) = common else {
                return Ok(None);
            };
            let t = HttpTransport::new(common)?;
            for s in &secrets {
                t.add_known_secret(s);
            }
            Ok(Some(t))
        };
        let ingest = if config.has_ingest() {
            make(config.ingest_common())?
        } else {
            None
        };
        let query = if config.has_query() {
            make(config.query_common())?
        } else {
            None
        };
        if [config.url(), config.query_url()]
            .iter()
            .flatten()
            .any(|u| u.scheme() == "http")
        {
            PLAIN_HTTP_WARNING.call_once(|| {
                tracing::warn!(
                    target: "grow_better_stack_logs",
                    "a Better Stack endpoint uses plain http; credentials travel unencrypted (use only for local test services)"
                );
            });
        }
        Ok(BetterStackClient {
            inner: Arc::new(Inner {
                config,
                ingest,
                query,
                on_close: CloseGroup::new(),
            }),
        })
    }

    /// Creates a client from `GROW_BETTER_STACK_LOGS_*`, `BETTERSTACK_SOURCE_TOKEN`,
    /// `BETTERSTACK_INGESTING_HOST`, `BETTERSTACK_QUERY_*` … environment variables.
    pub fn from_env() -> Result<Self> {
        Self::new(BetterStackConfig::from_env()?)
    }

    /// The configuration in use.
    pub fn config(&self) -> &BetterStackConfig {
        &self.inner.config
    }

    /// The ingest transport (`None` without ingest credentials).
    pub fn ingest_transport(&self) -> Option<&HttpTransport> {
        self.inner.ingest.as_ref()
    }

    /// The SQL query transport (`None` without query credentials).
    pub fn query_transport(&self) -> Option<&HttpTransport> {
        self.inner.query.as_ref()
    }

    /// Registers work that [`BetterStackClient::close`] runs (flush, stop tails, …).
    pub fn on_close<F>(&self, f: F)
    where
        F: FnOnce() -> BoxFuture<'static, ()> + Send + 'static,
    {
        self.inner.on_close.register(f);
    }

    /// Unauthenticated liveness, without sending data. With a query host:
    /// `GET {query_url}/ping` (`Ok.` → `ok`). Ingest-only: `GET {url}/` without credentials;
    /// any answer below 500 proves the host is reachable. `details.endpoint` is `query` or
    /// `ingest`. Never fails except for `CANCELLED` and `CONFIG`.
    pub async fn health(&self) -> Result<HealthStatus> {
        let (transport, path, endpoint) = match (&self.inner.query, &self.inner.ingest) {
            (Some(q), _) => (q, "/ping", "query"),
            (None, Some(i)) => (i, "/", "ingest"),
            (None, None) => return Err(Error::config("health: no endpoint configured")),
        };
        let req = (400..600).fold(
            transport.get(path).operation("health").idempotent(false),
            |r, s| r.ok_status(s),
        );
        let resp = match req.send().await {
            Ok(resp) => resp,
            Err(err) => {
                return HealthStatus::from_error(err).map(|h| h.with_detail("endpoint", endpoint))
            }
        };
        let status = resp.status();
        let hs = if endpoint == "query" && status < 300 {
            let word = resp.text().unwrap_or_default();
            let word = word.trim().trim_end_matches('.').to_ascii_lowercase();
            HealthStatus::healthy(if word.is_empty() {
                "ok".to_owned()
            } else {
                word
            })
        } else if (endpoint == "ingest" && status < 500) || status < 300 {
            HealthStatus::healthy("ok")
        } else {
            HealthStatus::unhealthy("unhealthy")
        };
        Ok(hs
            .with_detail("endpoint", endpoint)
            .with_detail("httpStatus", status.to_string()))
    }

    /// Authenticated round trip; returns the latency. With query credentials:
    /// `SELECT count() AS c FROM remote(<source_table>_logs) WHERE 1 = 0` (or `SELECT 1`
    /// without a `source_table`). Ingest-only: posts an empty batch `[]` (no event is stored).
    /// Bad credentials → `AUTHENTICATION`.
    pub async fn ping(&self) -> Result<Duration> {
        let started = Instant::now();
        if self.inner.query.is_some() {
            let sql = match self.config().source_table() {
                Some(t) => format!("SELECT count() AS c FROM remote({t}_logs) WHERE 1 = 0"),
                None => "SELECT 1 AS ok".to_owned(),
            };
            self.query()?
                .query(&sql, &Default::default(), &Default::default())
                .await?;
        } else {
            self.inner
                .post_ingest("ping", "/", b"[]".to_vec(), "application/json", false)
                .await?;
        }
        Ok(started.elapsed().max(Duration::from_nanos(1)))
    }

    /// Always `UNSUPPORTED`: Better Stack is a SaaS and exposes no service version (the query
    /// host's ClickHouse version is not one).
    pub async fn server_version(&self) -> Result<String> {
        Err(Error::unsupported(
            "serverVersion: Better Stack does not report a server version",
        ))
    }

    /// The HTTP ingest exporter with the configured encoding and default limits. `CONFIG`
    /// without ingest credentials. Most users call [`log_client`](Self::log_client) instead.
    pub fn exporter(&self) -> Result<BetterStackLogExporter> {
        self.new_exporter(IngestExporterOptions::default())
    }

    /// An HTTP ingest exporter with its own encoding and limits (use it with
    /// [`log_client_with`](Self::log_client_with)). `CONFIG` without ingest credentials,
    /// `VALIDATION` for limits outside 1..=10 MiB.
    pub fn new_exporter(&self, opts: IngestExporterOptions) -> Result<BetterStackLogExporter> {
        self.inner.ingest_transport("logs.exporter")?;
        BetterStackLogExporter::new(Arc::clone(&self.inner), opts)
    }

    /// The OTLP/HTTP logs exporter (protobuf). `CONFIG` without ingest credentials.
    pub fn otlp_exporter(&self) -> Result<BetterStackOtlpLogExporter> {
        self.new_otlp_exporter(OtlpExporterOptions::default())
    }

    /// An OTLP/HTTP logs exporter with its own protocol (protobuf or JSON), scope and limit.
    pub fn new_otlp_exporter(
        &self,
        opts: OtlpExporterOptions,
    ) -> Result<BetterStackOtlpLogExporter> {
        self.inner.ingest_transport("logs.otlpExporter")?;
        BetterStackOtlpLogExporter::new(Arc::clone(&self.inner), opts)
    }

    /// A batching [`BetterStackLogClient`] over the HTTP ingest API (default) or OTLP;
    /// [`close`](Self::close) flushes and closes it. Must be called inside a tokio runtime.
    /// `CONFIG` without ingest credentials, `CANCELLED` after `close`.
    pub fn log_client(&self, opts: LogClientOptions) -> Result<BetterStackLogClient> {
        let exporter: Arc<dyn LogExporter> = match opts.protocol {
            LogProtocol::Http => Arc::new(self.new_exporter(opts.exporter.unwrap_or_default())?),
            LogProtocol::Otlp => {
                Arc::new(self.new_otlp_exporter(opts.otlp.clone().unwrap_or_default())?)
            }
        };
        self.log_client_with(exporter, opts)
    }

    /// A batching [`BetterStackLogClient`] over any exporter (for example
    /// [`otlp_exporter`](Self::otlp_exporter)); [`close`](Self::close) flushes and closes it.
    pub fn log_client_with(
        &self,
        exporter: Arc<dyn LogExporter>,
        opts: LogClientOptions,
    ) -> Result<BetterStackLogClient> {
        if self.inner.on_close.is_closed() {
            return Err(Error::cancelled("logClient: client is closed"));
        }
        let mut batch = opts.batch;
        batch.max_batch_bytes = batch.max_batch_bytes.min(MAX_REQUEST_BYTES);
        let client = BatchingLogClient::builder(exporter)
            .batch_options(batch)
            .resource(opts.resource)
            .redaction(opts.redaction)
            .min_severity(opts.min_severity)
            .build()?;
        let c = client.clone();
        self.inner.on_close.register(move || {
            Box::pin(async move {
                let _ = c.close().await;
            })
        });
        Ok(client)
    }

    /// The SQL query client. `CONFIG` without query credentials.
    pub fn query(&self) -> Result<BetterStackQuery> {
        self.inner.query_transport("query")?;
        Ok(BetterStackQuery::new(Arc::clone(&self.inner)))
    }

    /// Flushes and closes every log client, stops running tails, then releases both
    /// transports. Idempotent.
    pub async fn close(&self) {
        self.inner.on_close.close().await;
        if let Some(t) = &self.inner.ingest {
            t.close();
        }
        if let Some(t) = &self.inner.query {
            t.close();
        }
    }

    /// Whether [`BetterStackClient::close`] was called.
    pub fn is_closed(&self) -> bool {
        self.inner.on_close.is_closed()
    }
}
