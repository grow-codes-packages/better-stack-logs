# Client

`BetterStackClient` is the entry point. Create one per process (or per set of credentials), share
it between threads/tasks and close it on shutdown. It holds **two transports**: one for the
source's ingesting host (Bearer source token) and one for the SQL host (HTTP Basic); a client with
only one side configured is valid, and calls that need the other side raise `CONFIG`.

| Function | Returns | Notes |
| - | - | - |
| constructor / `create` / `new` | client | validates the config (`CONFIG` error) |
| `fromEnv()` | client | config from `GROW_BETTER_STACK_LOGS_*` and `BETTERSTACK_*` ([Configuration](configuration.md)) |
| `health()` | `HealthStatus { healthy, status, details }` | unauthenticated; with a SQL host `GET {queryUrl}/ping` (`Ok.`), otherwise `GET {url}/` on the ingesting host (any answer below 500 means reachable; no data is sent). `details.endpoint` is `query` or `ingest`. **Never throws** for an unhealthy or unreachable service, only for `CANCELLED` and `CONFIG` |
| `ping()` | latency | authenticated: `SELECT count() AS c FROM remote(<sourceTable>_logs) WHERE 1 = 0` (or `SELECT 1` without a `sourceTable`); ingest-only clients post an empty batch `[]` (no event is stored). `AUTHENTICATION` on bad credentials |
| `serverVersion()` | — | always `UNSUPPORTED`: Better Stack is a SaaS without a version (the SQL host's ClickHouse version is not a Better Stack version) |
| `close()` | — | idempotent; flushes every log client created by this client, stops tails, closes both transports. Later calls on the client, its exporters and its query client raise `CANCELLED` |
| `exporter`, `newExporter`, `otlpExporter`, `newOtlpExporter` | exporters | [Ingest](functions/ingest.md) |
| `logClient(options)` | `BetterStackLogClient` | [Log client and search](functions/logging.md) |
| `query()` | `BetterStackQuery` | [SQL query](functions/query.md) |

`status` is `ok`, `unhealthy`, `unreachable` (network error) or ClickHouse's word.

## Create, use and close


=== "TypeScript"

    ```ts
    import { BetterStackClient, BetterStackConfig } from "@grow-codes/better-stack-logs";

    const client = new BetterStackClient(
      new BetterStackConfig({
        url: "s1234.eu-nbg-2.betterstackdata.com", // bare host → https://
        sourceToken: process.env.BETTERSTACK_SOURCE_TOKEN,
        queryUrl: "eu-nbg-2-connect.betterstackdata.com",
        queryUsername: process.env.BETTERSTACK_QUERY_USERNAME,
        queryPassword: process.env.BETTERSTACK_QUERY_PASSWORD,
        sourceTable: "t1234_myapp",
      }),
    );
    const health = await client.health();   // { healthy, status, details: { endpoint: "query" } }
    const latencyMs = await client.ping();  // number (ms)
    await client.serverVersion().catch((e) => console.log(e.code)); // "UNSUPPORTED"
    await client.close();                   // safe to call twice; later calls → CANCELLED

    // Cancellation and per-call deadline: every I/O method accepts { signal }
    const status = await BetterStackClient.fromEnv().health({ signal: AbortSignal.timeout(2_000) });
    ```

=== "Rust"

    ```rust
    use grow_better_stack_logs::{BetterStackClient, BetterStackConfig};

    let config = BetterStackConfig::builder()
        .url("s1234.eu-nbg-2.betterstackdata.com")
        .source_token(std::env::var("BETTERSTACK_SOURCE_TOKEN").unwrap_or_default())
        .query_url("eu-nbg-2-connect.betterstackdata.com")
        .query_username(std::env::var("BETTERSTACK_QUERY_USERNAME").unwrap_or_default())
        .query_password(std::env::var("BETTERSTACK_QUERY_PASSWORD").unwrap_or_default())
        .source_table("t1234_myapp")
        .build()?;
    let client = BetterStackClient::new(config)?;
    let health = client.health().await?;   // HealthStatus
    let latency = client.ping().await?;    // Duration
    assert!(client.server_version().await.is_err()); // UNSUPPORTED
    client.close().await;                  // safe to call twice

    // Without an async runtime, enable the `blocking` feature:
    let blocking = grow_better_stack_logs::blocking::BetterStackClient::from_env()?;
    let health = blocking.health()?;
    ```

=== "Go"

    ```go
    client, err := betterstack.NewClient(betterstack.Config{
    	CommonConfig:  betterstack.CommonConfig{URL: "s1234.eu-nbg-2.betterstackdata.com"},
    	SourceToken:   betterstack.NewSecret(os.Getenv("BETTERSTACK_SOURCE_TOKEN")),
    	QueryURL:      "eu-nbg-2-connect.betterstackdata.com",
    	QueryUsername: os.Getenv("BETTERSTACK_QUERY_USERNAME"),
    	QueryPassword: betterstack.NewSecret(os.Getenv("BETTERSTACK_QUERY_PASSWORD")),
    	SourceTable:   "t1234_myapp",
    })
    if err != nil {
    	return err
    }
    defer client.Close() // safe to call twice

    health, err := client.Health(ctx)  // HealthStatus
    latency, err := client.Ping(ctx)   // time.Duration
    _, err = client.ServerVersion(ctx) // errors.Is(err, betterstack.ErrUnsupported)

    // Cancellation and deadlines come from ctx
    ctx2, cancel := context.WithTimeout(ctx, 2*time.Second)
    defer cancel()
    health, err = client.Health(ctx2)
    ```

=== "Python"

    ```python
    import os
    from grow_better_stack_logs import BetterStackClient, BetterStackConfig, CancelToken, UnsupportedError

    config = BetterStackConfig(
        url="s1234.eu-nbg-2.betterstackdata.com",
        source_token=os.environ["BETTERSTACK_SOURCE_TOKEN"],
        query_url="eu-nbg-2-connect.betterstackdata.com",
        query_username=os.environ["BETTERSTACK_QUERY_USERNAME"],
        query_password=os.environ["BETTERSTACK_QUERY_PASSWORD"],
        source_table="t1234_myapp",
    )
    with BetterStackClient(config) as client:
        health = client.health()      # HealthStatus
        latency_ms = client.ping()    # float (ms)
        try:
            client.server_version()
        except UnsupportedError:
            pass
        health = client.health(cancel=CancelToken())  # cancel from another thread
    # close() ran; calling it again is safe, other calls raise CancelledError
    ```

=== "Java"

    ```java
    BetterStackConfig config = BetterStackConfig.builder()
        .url("s1234.eu-nbg-2.betterstackdata.com")
        .sourceToken(System.getenv("BETTERSTACK_SOURCE_TOKEN"))
        .queryUrl("eu-nbg-2-connect.betterstackdata.com")
        .queryUsername(System.getenv("BETTERSTACK_QUERY_USERNAME"))
        .queryPassword(System.getenv("BETTERSTACK_QUERY_PASSWORD"))
        .sourceTable("t1234_myapp")
        .build();
    try (BetterStackClient client = BetterStackClient.create(config)) {
      HealthStatus health = client.health();
      Duration latency = client.ping();
      // client.serverVersion() throws UnsupportedException

      // Async variants return CompletableFuture
      client.healthAsync().orTimeout(2, TimeUnit.SECONDS).join();
    } // close() is idempotent; later calls throw CancelledException
    ```


`AsyncBetterStackClient` (Python) has the same methods as coroutines (`await client.health()`,
`await client.aclose()`, `async with`) and honours task cancellation.

## Lifecycle rules

- The client is safe for concurrent use. Reuse it: it keeps a connection pool per host.
- `close()` flushes queued log batches within the configured deadline, stops polling tails and is
  safe to call more than once. Calls after `close()` raise `CANCELLED` in every language.
- Each language also offers its idiomatic scope: TypeScript `try/finally`, Rust `close().await`
  (plus best-effort `Drop`), Go `defer client.Close()`, Python `with`/`async with`, Java
  try-with-resources.
