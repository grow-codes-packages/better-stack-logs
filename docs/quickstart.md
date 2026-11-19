# Quickstart

Send a log line to Better Stack, read it back with the SQL API and close the client. You need a
**source** (Better Stack → Sources: its ingesting host and source token) and, to read logs back, a
**SQL connection** (Integrations → Connect remotely: host, username, password) plus the source's
collection name (`t<team>_<table>`).

```sh
export BETTERSTACK_INGESTING_HOST=s1234.eu-nbg-2.betterstackdata.com
export BETTERSTACK_SOURCE_TOKEN=...            # never commit real tokens
export BETTERSTACK_QUERY_HOST=eu-nbg-2-connect.betterstackdata.com
export BETTERSTACK_QUERY_USERNAME=... BETTERSTACK_QUERY_PASSWORD=...
export BETTERSTACK_SOURCE_TABLE=t1234_myapp
```

The `GROW_BETTER_STACK_LOGS_*` names work too and win over the vendor names
([Configuration](configuration.md)). Logs usually appear in the SQL API within seconds; allow up to
a minute.


=== "TypeScript"

    ```ts
    import { BetterStackClient, BetterStackError } from "@grow-codes/better-stack-logs";

    const client = BetterStackClient.fromEnv();
    try {
      console.log(await client.health()); // never throws for an unreachable service
      console.log("ping:", await client.ping(), "ms");

      const logs = client.logClient({ resource: { "service.name": "quickstart" } });
      logs.logger("demo").info("hello from TypeScript", { answer: 42 });
      await logs.flush();

      const record = await client.query().waitForLog("hello from TypeScript", { timeoutMs: 60_000, intervalMs: 2_000 });
      console.log(record.timestamp, record.severityText, record.body, record.attributes);
    } catch (err) {
      if (err instanceof BetterStackError) console.error(err.code, err.message);
      else throw err;
    } finally {
      await client.close();
    }
    ```

=== "Rust"

    ```rust
    use std::time::Duration;
    use grow_better_stack_logs::logging::{attrs, LogClient, LogQueryClient, WaitOptions};
    use grow_better_stack_logs::{BetterStackClient, LogClientOptions};

    #[tokio::main]
    async fn main() -> grow_better_stack_logs::Result<()> {
        let client = BetterStackClient::from_env()?;
        println!("{:?}", client.health().await?);
        println!("ping: {:?}", client.ping().await?);

        let logs = client.log_client(LogClientOptions::default().resource_attribute("service.name", "quickstart"))?;
        logs.logger(Some("demo"), attrs! {}).info("hello from Rust", attrs! { "answer" => 42 });
        logs.flush(None).await?;

        let record = client
            .query()?
            .wait_for_log("hello from Rust", WaitOptions::default().timeout(Duration::from_secs(60)))
            .await?;
        println!("{:?} {} {}", record.timestamp, record.severity_text, record.body);
        client.close().await;
        Ok(())
    }
    ```

=== "Go"

    ```go
    package main

    import (
    	"context"
    	"fmt"
    	"log"
    	"time"

    	betterstack "github.com/grow-codes-packages/better-stack-logs/go"
    )

    func main() {
    	ctx := context.Background()
    	client, err := betterstack.NewClientFromEnv()
    	if err != nil {
    		log.Fatal(err)
    	}
    	defer client.Close()

    	hs, _ := client.Health(ctx)
    	fmt.Println(hs.Status, hs.Healthy)

    	logs, err := client.NewLogClient(betterstack.LogClientOptions{
    		Resource: map[string]betterstack.AttrValue{"service.name": "quickstart"},
    	})
    	if err != nil {
    		log.Fatal(err)
    	}
    	logs.Logger("demo", nil).Info(ctx, "hello from Go", "answer", 42)
    	if err := logs.Flush(ctx); err != nil {
    		log.Fatal(err)
    	}

    	rec, err := client.Query().WaitForLog(ctx, "hello from Go",
    		betterstack.WaitForLogOptions{Timeout: time.Minute, Interval: 2 * time.Second})
    	if err != nil {
    		log.Fatal(err)
    	}
    	fmt.Println(rec.Timestamp, rec.SeverityText, rec.Body)
    }
    ```

=== "Python"

    ```python
    from grow_better_stack_logs import BetterStackClient, BetterStackError

    try:
        with BetterStackClient.from_env() as client:
            print(client.health())
            print(f"ping: {client.ping():.1f} ms")

            logs = client.log_client(resource={"service.name": "quickstart"})
            logs.logger("demo").info("hello from Python", {"answer": 42})
            logs.flush(10)

            record = client.query().wait_for_log("hello from Python", timeout_s=60, interval_s=2)
            print(record.timestamp, record.severity_text, record.body, record.attributes)
    except BetterStackError as err:
        print(err.code, err)
    ```

=== "Java"

    ```java
    import codes.grow.packages.betterstack.BetterStackClient;
    import codes.grow.packages.betterstack.BetterStackLogClientOptions;
    import codes.grow.packages.betterstack.core.BetterStackException;
    import codes.grow.packages.betterstack.logging.LogRecord;
    import codes.grow.packages.betterstack.logging.WaitForLogOptions;
    import java.time.Duration;
    import java.util.Map;

    public final class Quickstart {
      public static void main(String[] args) {
        try (BetterStackClient client = BetterStackClient.fromEnv()) {
          System.out.println(client.health());
          System.out.println("ping: " + client.ping().toMillis() + " ms");

          var logs = client.logClient(
              BetterStackLogClientOptions.builder().resource(Map.of("service.name", "quickstart")).build());
          logs.logger("demo").info("hello from Java", Map.of("answer", 42));
          logs.flush();

          LogRecord record = client.query().waitForLog("hello from Java",
              WaitForLogOptions.of(Duration.ofSeconds(60), Duration.ofSeconds(2)));
          System.out.println(record.timestamp() + " " + record.severityText() + " " + record.body());
        } catch (BetterStackException e) {
          System.err.println(e.code() + " " + e.getMessage());
        }
      }
    }
    ```


Next: [Ingest](functions/ingest.md) for encodings and raw events, [SQL query](functions/query.md)
for `query` and `searchLogs`, and [Logging](logging.md) for batching, redaction and framework
adapters.
