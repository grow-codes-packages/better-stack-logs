// Created by sdk-kit 0.1.0; owned by this repository.
//! Better Stack Logs Rust SDK: configuration from the environment, health, ping, sending logs
//! through a batching log client, reading them back with the SQL query API, hooks and errors.
//!
//! ```sh
//! BETTERSTACK_INGESTING_HOST=s1234.eu-nbg-2.betterstackdata.com BETTERSTACK_SOURCE_TOKEN=... \
//! BETTERSTACK_QUERY_HOST=eu-nbg-2-connect.betterstackdata.com BETTERSTACK_QUERY_USERNAME=... \
//! BETTERSTACK_QUERY_PASSWORD=... BETTERSTACK_SOURCE_TABLE=t1234_myapp cargo run
//! ```

use std::sync::Arc;
use std::time::Duration;

use grow_better_stack_logs as sdk;
use sdk::core::{ErrorInfo, Hooks};
use sdk::logging::{attrs, LogClient, LogQueryClient, WaitOptions};
use sdk::{query_params, ErrorCode, LogClientOptions, QueryOptions, SearchLogsOptions};

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
            println!("ping: check the source token / SQL credentials ({e})")
        }
        Err(e) => return Err(e),
    }
    if let Err(e) = client.server_version().await {
        println!("server version: not exposed by Better Stack ({})", e.code());
    }

    if client.config().has_ingest() {
        let logs = client.log_client(
            LogClientOptions::default().resource_attribute("service.name", "rust-example"),
        )?;
        let log = logs.logger(Some("example"), attrs! { "region" => "eu" });
        log.info(
            "order placed",
            attrs! { "order_id" => 42, "password" => "redacted before it leaves" },
        );
        logs.flush(Some(Duration::from_secs(10))).await?;
        println!("sent: {:?}", logs.stats());
    }

    if client.config().has_query() && client.config().source_table().is_some() {
        let q = client.query()?;
        let record = q
            .wait_for_log(
                "order placed",
                WaitOptions::default().timeout(Duration::from_secs(60)),
            )
            .await?;
        println!("read back: {} {:?}", record.body, record.attributes);
        let page = q
            .search_logs(
                SearchLogsOptions::default()
                    .contains("order placed")
                    .limit(10),
            )
            .await?;
        println!("search: {} records", page.records.len());
        let table = format!(
            "{}_logs",
            client.config().source_table().unwrap_or_default()
        );
        let rows = q
            .query(
                "SELECT count() AS c FROM remote({table:Identifier}) WHERE position(raw, {needle:String}) > 0",
                &query_params! { "table" => table, "needle" => "order placed" },
                &QueryOptions::default(),
            )
            .await?;
        println!("count: {:?}", rows.first().and_then(|r| r.get("c")));
    }
    client.close().await; // flushes every log client
    Ok(())
}
