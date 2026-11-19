# Log client and search

The logging category interface ([Logging](../logging.md)) on top of Better Stack: a batching
`LogClient` that ships to the ingesting host and a `LogQueryClient` (the `BetterStackQuery`
client) that searches through the SQL API.

| Function | Result |
| - | - |
| `logClient({protocol, exporter, otlp, batch, resource, redaction})` | a batching `BetterStackLogClient` over the HTTP ingest API (`protocol` `http`, default) or OTLP (`otlp`); `exporter` / `otlp` take exporter options (encoding, limits, OTLP protocol); `close()` of the client flushes and closes it. Go: `NewLogClient(opts)`, `NewLogClientWith(exporter, opts)`; Rust: `log_client(opts)`, `log_client_with(exporter, opts)`; Java also `logClient(exporter, options)`; Python `log_client(exporter=…)` |
| `send`, `sendBatch`, `flush`, `close`, `stats`, `logger` | see [Logging](../logging.md#send-logs); never blocks the caller by default |
| `query().search(query, {start, end, limit, order, cursor})` | `searchLogs({contains: query, …})` on the configured `sourceTable` |
| `query().tail(query, onRecord, {start, interval})` | polls `search` every `interval` (default 1 s) from `start` (default now) and calls `onRecord` once per new record (deduplicated at the page boundary); `stop()` ends it; `close()` of the client stops every tail |
| `query().listStreams()` | `[sourceTable]`, or `[]` without one (Better Stack has no listing API on the data plane) |
| `query().listLabels()` | always `UNSUPPORTED`: Better Stack events have no labels |
| `query().waitForLog(query, {timeout, interval, predicate})` | polls `search` until a record matches; `TIMEOUT` (default 30 s) otherwise |

Better Stack has no public streaming API (Live tail is dashboard-only), so `tail` is a polling
implementation. Keep the interval at 1 s or more: the SQL API allows 4 concurrent queries per
account.

## Ship and read back


=== "TypeScript"

    ```ts
    import { BetterStackClient, Severity } from "@grow-codes/better-stack-logs";

    const client = BetterStackClient.fromEnv();
    const logs = client.logClient({
      batch: { flushIntervalMs: 1_000, maxBatchSize: 500 },
      resource: { "service.name": "checkout", "deployment.environment": "prod" },
    });
    const log = logs.logger("orders").child({ region: "eu" });
    log.info("order placed", { orderId: 42, password: "never leaves the process" }); // redacted
    logs.send({ message: "payment accepted", severity: Severity.Info, attributes: { orderId: 42 } });
    await logs.flush();
    console.log(logs.stats()); // { queued, sent, dropped, failed, retries }

    const q = client.query();
    const record = await q.waitForLog("payment accepted", { timeoutMs: 60_000, intervalMs: 2_000 });
    const recent = await q.search("order placed", { limit: 10, order: "asc" });
    console.log(record.body, recent.records.length, await q.listStreams());
    await client.close();
    ```

=== "Rust"

    ```rust
    use std::time::Duration;
    use grow_better_stack_logs::logging::{attrs, LogClient, LogQueryClient, SearchOptions, WaitOptions};
    use grow_better_stack_logs::{BetterStackClient, LogClientOptions};

    let client = BetterStackClient::from_env()?;
    let logs = client.log_client(
        LogClientOptions::default()
            .resource_attribute("service.name", "checkout")
            .resource_attribute("deployment.environment", "prod"),
    )?;
    let log = logs.logger(Some("orders"), attrs! { "region" => "eu" });
    log.info("order placed", attrs! { "order_id" => 42, "password" => "never leaves the process" });
    logs.flush(Some(Duration::from_secs(10))).await?;
    println!("{:?}", logs.stats());

    let q = client.query()?;
    let record = q
        .wait_for_log("order placed", WaitOptions::default().timeout(Duration::from_secs(60)).interval(Duration::from_secs(2)))
        .await?;
    let recent = q.search("order placed", SearchOptions::default()).await?;
    println!("{} {} {:?}", record.body, recent.records.len(), q.list_streams().await?);
    client.close().await;
    ```

=== "Go"

    ```go
    client, err := betterstack.NewClientFromEnv()
    if err != nil {
    	return err
    }
    defer client.Close()

    logs, err := client.NewLogClient(betterstack.LogClientOptions{
    	Resource: map[string]betterstack.AttrValue{"service.name": "checkout", "deployment.environment": "prod"},
    })
    if err != nil {
    	return err
    }
    log := logs.Logger("orders", nil)
    log.Info(ctx, "order placed", "order_id", 42, "password", "never leaves the process")
    slog.New(betterstack.NewSlogHandler(logs, nil)).Warn("payment slow", "ms", 950)
    if err := logs.Flush(ctx); err != nil {
    	return err
    }
    fmt.Printf("%+v\n", logs.Stats())

    q := client.Query()
    rec, err := q.WaitForLog(ctx, "order placed", betterstack.WaitForLogOptions{Timeout: time.Minute, Interval: 2 * time.Second})
    recent, err := q.Search(ctx, "order placed", betterstack.SearchOptions{Limit: 10})
    streams, err := q.ListStreams(ctx)
    fmt.Println(rec.Body, len(recent.Records), streams)
    ```

=== "Python"

    ```python
    import logging
    from grow_better_stack_logs import BatchOptions, BetterStackClient, LogHandler

    with BetterStackClient.from_env() as client:
        logs = client.log_client(
            options=BatchOptions(flush_interval_s=1, max_batch_size=500),
            resource={"service.name": "checkout", "deployment.environment": "prod"},
        )
        log = logs.logger("orders").child({"region": "eu"})
        log.info("order placed", {"order_id": 42, "password": "never leaves the process"})
        logging.getLogger("payments").addHandler(LogHandler(logs))  # stdlib logging
        logs.flush(10)
        print(logs.stats())

        q = client.query()
        record = q.wait_for_log("order placed", timeout_s=60, interval_s=2)
        recent = q.search("order placed", limit=10, order="asc")
        print(record.body, len(recent.records), q.list_streams())
    ```

=== "Java"

    ```java
    try (BetterStackClient client = BetterStackClient.fromEnv()) {
      BatchingLogClient logs = client.logClient(BetterStackLogClientOptions.builder()
          .resource(Map.of("service.name", "checkout", "deployment.environment", "prod"))
          .build());
      logs.logger("orders").child(Map.of("region", "eu"))
          .info("order placed", Map.of("orderId", 42, "password", "never leaves the process"));
      logs.flush();
      System.out.println(logs.stats());

      BetterStackQuery q = client.query();
      LogRecord record = q.waitForLog("order placed",
          WaitForLogOptions.of(Duration.ofSeconds(60), Duration.ofSeconds(2)));
      LogSearchResult recent = q.search("order placed", SearchOptions.builder().limit(10).build());
      System.out.println(record.body() + " " + recent.records().size() + " " + q.listStreams());
    }
    ```


## Tail


=== "TypeScript"

    ```ts
    const handle = client.query().tail("payment", (r) => console.log(r.timestamp, r.body), {
      intervalMs: 2_000,
      onError: (err) => console.warn("tail query failed", err),
    });
    // …
    handle.stop();
    ```

=== "Rust"

    ```rust
    use std::sync::Arc;
    use grow_better_stack_logs::logging::{LogQueryClient, TailOptions};

    let handle = client
        .query()?
        .tail(
            "payment",
            Arc::new(|r| println!("{:?} {}", r.timestamp, r.body)),
            TailOptions { interval: Duration::from_secs(2), start: None },
        )
        .await?;
    // …
    handle.stop();
    ```

=== "Go"

    ```go
    h, err := client.Query().Tail(ctx, "payment", func(r betterstack.LogRecord) {
    	fmt.Println(r.Timestamp, r.Body)
    }, betterstack.TailOptions{Interval: 2 * time.Second})
    if err != nil {
    	return err
    }
    // …
    h.Stop()
    ```

=== "Python"

    ```python
    tail = client.query().tail("payment", lambda r: print(r.timestamp, r.body), interval_s=2)
    # …
    tail.stop()
    ```

=== "Java"

    ```java
    TailHandle handle = client.query().tail("payment",
        r -> System.out.println(r.timestamp() + " " + r.body()),
        TailOptions.defaults().withOnError(e -> System.err.println("tail query failed: " + e)));
    // …
    handle.stop();
    ```


## Framework adapters

| Language | Adapters |
| - | - |
| TypeScript | `createPinoTransport(logs)` (pino), `WinstonTransport` (winston) |
| Rust | `LogLayer` (`tracing-subscriber`), `LogBridge` (`log::Log`, feature `log`) |
| Go | `NewSlogHandler(logs, opts)` (`log/slog`), `zapadapter.NewCore` / `NewLogger` (zap) |
| Python | `LogHandler(logs)` (`logging`), `structlog_processor(logs)` (structlog, extra `[structlog]`) |
| Java | `GrowLogbackAppender`, `GrowLog4j2Appender`, `GrowJulHandler` (`java.util.logging`) |

Details and configuration snippets are on the [Logging](../logging.md#framework-adapters) page.
