// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! Blocking wrapper (feature `blocking`). Each client owns a small tokio runtime (one worker
//! thread, so batching log clients export in the background).
//!
//! Do not use it from inside an async runtime (calls would panic); use the async client there.

use std::future::Future;
use std::time::Duration;

use crate::core::{Error, HealthStatus, Result};
use crate::logging::{
    Attributes, BatchStats, LogClient as _, LogEntry, LogQueryClient as _, LogRecord,
    LogSearchResult, Logger, SearchOptions, WaitOptions,
};
use crate::{
    BetterStackClient as AsyncClient, BetterStackConfig, BetterStackLogClient, LogClientOptions,
    QueryOptions, QueryParams, QueryRow, SearchLogsOptions,
};

/// Blocking client for Better Stack Logs with the same methods as the async client.
#[derive(Debug)]
pub struct BetterStackClient {
    inner: AsyncClient,
    runtime: tokio::runtime::Runtime,
}

impl BetterStackClient {
    /// Creates a client from a configuration.
    pub fn new(config: BetterStackConfig) -> Result<Self> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .enable_all()
            .build()
            .map_err(|e| Error::config("cannot start the tokio runtime").with_source(e))?;
        let inner = {
            let _guard = runtime.enter();
            AsyncClient::new(config)?
        };
        Ok(BetterStackClient { inner, runtime })
    }

    /// Creates a client from the process environment.
    pub fn from_env() -> Result<Self> {
        Self::new(BetterStackConfig::from_env()?)
    }

    /// The wrapped async client.
    pub fn async_client(&self) -> &AsyncClient {
        &self.inner
    }

    /// The configuration in use.
    pub fn config(&self) -> &BetterStackConfig {
        self.inner.config()
    }

    /// Runs a future of the async API to completion on this client's runtime.
    pub fn block_on<F: Future>(&self, future: F) -> F::Output {
        self.runtime.block_on(future)
    }

    /// See [`AsyncClient::health`].
    pub fn health(&self) -> Result<HealthStatus> {
        self.block_on(self.inner.health())
    }

    /// See [`AsyncClient::ping`].
    pub fn ping(&self) -> Result<Duration> {
        self.block_on(self.inner.ping())
    }

    /// See [`AsyncClient::server_version`].
    pub fn server_version(&self) -> Result<String> {
        self.block_on(self.inner.server_version())
    }

    /// See [`crate::BetterStackQuery::query`].
    pub fn query(&self, sql: &str, params: &QueryParams) -> Result<Vec<QueryRow>> {
        let q = self.inner.query()?;
        self.block_on(q.query(sql, params, &QueryOptions::default()))
    }

    /// See [`crate::BetterStackQuery::query_raw`].
    pub fn query_raw(
        &self,
        sql: &str,
        params: &QueryParams,
        opts: &QueryOptions,
    ) -> Result<String> {
        let q = self.inner.query()?;
        self.block_on(q.query_raw(sql, params, opts))
    }

    /// See [`crate::BetterStackQuery::search_logs`].
    pub fn search_logs(&self, opts: SearchLogsOptions) -> Result<LogSearchResult> {
        let q = self.inner.query()?;
        self.block_on(q.search_logs(opts))
    }

    /// See [`crate::logging::LogQueryClient::search`].
    pub fn search(&self, query: &str, opts: SearchOptions) -> Result<LogSearchResult> {
        let q = self.inner.query()?;
        self.block_on(q.search(query, opts))
    }

    /// See [`crate::logging::LogQueryClient::wait_for_log`].
    pub fn wait_for_log(&self, query: &str, opts: WaitOptions) -> Result<LogRecord> {
        let q = self.inner.query()?;
        self.block_on(q.wait_for_log(query, opts))
    }

    /// See [`crate::logging::LogQueryClient::list_streams`].
    pub fn list_streams(&self) -> Result<Vec<String>> {
        let q = self.inner.query()?;
        self.block_on(q.list_streams())
    }

    /// See [`AsyncClient::log_client`]; the returned client exports in the background.
    pub fn log_client(&self, opts: LogClientOptions) -> Result<LogClient> {
        let _guard = self.runtime.enter();
        Ok(LogClient {
            inner: self.inner.log_client(opts)?,
            handle: self.runtime.handle().clone(),
        })
    }

    /// See [`AsyncClient::close`]. Idempotent.
    pub fn close(&self) {
        self.block_on(self.inner.close());
    }
}

impl Drop for BetterStackClient {
    fn drop(&mut self) {
        if let Some(t) = self.inner.ingest_transport() {
            t.close();
        }
        if let Some(t) = self.inner.query_transport() {
            t.close();
        }
    }
}

/// Blocking handle on a batching log client (see [`BetterStackClient::log_client`]).
#[derive(Debug, Clone)]
pub struct LogClient {
    inner: BetterStackLogClient,
    handle: tokio::runtime::Handle,
}

impl LogClient {
    /// The async log client.
    pub fn async_client(&self) -> &BetterStackLogClient {
        &self.inner
    }

    /// Enqueues one record (never blocks unless the drop policy is `Block`).
    pub fn send(&self, entry: impl Into<LogEntry>) {
        self.inner.send(entry.into());
    }

    /// Enqueues several records.
    pub fn send_batch(&self, entries: Vec<LogEntry>) {
        self.inner.send_batch(entries);
    }

    /// Exports everything queued now.
    pub fn flush(&self, timeout: Option<Duration>) -> Result<()> {
        self.handle.block_on(self.inner.flush(timeout))
    }

    /// Flushes and shuts down; idempotent.
    pub fn close(&self) -> Result<()> {
        self.handle.block_on(self.inner.close())
    }

    /// Batch counters.
    pub fn stats(&self) -> BatchStats {
        self.inner.stats()
    }

    /// A [`Logger`] facade writing to this client.
    pub fn logger(&self, name: Option<&str>, attributes: Attributes) -> Logger {
        self.inner.logger(name, attributes)
    }
}
