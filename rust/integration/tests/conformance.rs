// Created by sdk-kit 0.1.0; owned by this repository.
//! Conformance scenarios for `core` and `logging` against the Better Stack stub (Docker).
//!
//! Capabilities: `query` (records are read back through the SQL query host). Not declared:
//! `version` (Better Stack is a SaaS; `server_version` is UNSUPPORTED, asserted in
//! `betterstack.rs`) and the secrets capabilities (`write`, `delete`, `versions`).

use std::sync::Arc;

use grow_better_stack_logs as sdk;
use grow_better_stack_logs_integration as support;
use sdk::testing::logging::{LoggingHandler, LoggingTarget};
use sdk::testing::{ConformanceRunner, CoreHandler};
use sdk::{BetterStackClient as Client, LogClientOptions};
use support::{conformance_dir, load_bootstrap_env};

#[tokio::test]
async fn conformance() {
    load_bootstrap_env();
    if std::env::var("GROW_BETTER_STACK_LOGS_URL").is_err() {
        eprintln!("GROW_BETTER_STACK_LOGS_URL is not set; run through docker/compose.yml");
        return;
    }
    let logging = LoggingHandler::new(|batch| async move {
        let client = Client::from_env()?;
        let logs = client.log_client(
            LogClientOptions::default()
                .batch(batch)
                .resource_attribute("service.name", "grow-conformance-rust")
                .resource_attribute("deployment.environment", "conformance"),
        )?;
        Ok(LoggingTarget {
            client: Arc::new(logs),
            query: Some(Arc::new(client.query()?)),
        })
    });
    let report = ConformanceRunner::new(conformance_dir())
        .capabilities(["query"])
        .handler(CoreHandler::new(Client::from_env))
        .handler(logging)
        .run()
        .await
        .expect("scenarios load");
    println!("{report}");
    report.assert_success();
    assert!(report.passed() >= 4, "core + 3 logging scenarios must run");
}
