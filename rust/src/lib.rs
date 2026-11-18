// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! # grow-better-stack-logs
//!
//! Rust SDK for [Better Stack Logs](https://grow-codes-packages.github.io/better-stack-logs/) by Grow Codes.
//!
//! ```no_run
//! # async fn demo() -> grow_better_stack_logs::Result<()> {
//! use grow_better_stack_logs::{BetterStackClient, BetterStackConfig};
//!
//! let client = BetterStackClient::new(BetterStackConfig::from_env()?)?;
//! let health = client.health().await?;
//! println!("healthy: {}", health.healthy);
//! client.close().await;
//! # Ok(())
//! # }
//! ```
//!
//! Features: `blocking` (a blocking client), `testing` (in-memory fakes and the conformance
//! runner)
//! , `tracing-layer` and `log-bridge` (logging adapters, on by default)
//! .
#![cfg_attr(docsrs, feature(doc_cfg))]

pub mod core;

mod client;
mod config;

#[cfg(feature = "blocking")]
#[cfg_attr(docsrs, doc(cfg(feature = "blocking")))]
pub mod blocking;

#[cfg(feature = "testing")]
#[cfg_attr(docsrs, doc(cfg(feature = "testing")))]
pub mod testing;

pub mod logging;

pub use crate::core::{Error, ErrorCode, HealthStatus, Result, RetryPolicy, Secret};

pub use crate::config::DEFAULT_URL;

pub use crate::client::BetterStackClient;

pub use crate::config::BetterStackConfig;

pub use crate::config::BetterStackConfigBuilder;

/// Service-prefixed alias of [`Error`].
pub type BetterStackError = Error;
