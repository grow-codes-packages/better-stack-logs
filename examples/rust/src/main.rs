// Created by sdk-kit 0.1.0; owned by this repository.
//! Basics of the Better Stack Logs Rust SDK: configuration from the environment, health, an
//! authenticated ping, hooks and error handling.
//!
//! ```sh
//! GROW_BETTER_STACK_LOGS_URL=http://127.0.0.1:8080 GROW_BETTER_STACK_LOGS_TOKEN=... cargo run
//! ```

use std::sync::Arc;

use grow_better_stack_logs as sdk;
use sdk::core::{ErrorInfo, Hooks};
use sdk::ErrorCode;

use sdk::BetterStackClient as Client;
use sdk::BetterStackConfig as Config;

/// Prints every retry.
struct RetryLogger;

impl Hooks for RetryLogger {
    fn on_error(&self, info: &ErrorInfo) {
        if info.will_retry {
            eprintln!(
                "retrying {} {} (attempt {})",
                info.method, info.url, info.attempt
            );
        }
    }
}

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        eprintln!("error [{}]: {err}", err.code());
        std::process::exit(1);
    }
}

async fn run() -> sdk::Result<()> {
    // explicit > GROW_BETTER_STACK_LOGS_* > vendor variables > defaults; invalid values name the
    // variable, never its value.
    let config = Config::builder()
        .hooks(Arc::new(RetryLogger))
        .build_from_env()?;
    println!("config: {config:?}"); // credentials print as [REDACTED]

    let client = Client::new(config)?;
    let health = client.health().await?; // never fails for an unreachable service
    println!("health: {} ({})", health.healthy, health.status);

    match client.ping().await {
        Ok(latency) => println!("ping: {} ms", latency.as_millis()),
        Err(e) if e.code() == ErrorCode::Authentication => {
            println!("ping: check GROW_BETTER_STACK_LOGS_TOKEN ({e})")
        }
        Err(e) => return Err(e),
    }
    match client.server_version().await {
        Ok(version) => println!("server version: {version}"),
        Err(e) if e.code() == ErrorCode::Unsupported => println!("server version: not exposed"),
        Err(e) => return Err(e),
    }
    client.close().await;
    Ok(())
}
