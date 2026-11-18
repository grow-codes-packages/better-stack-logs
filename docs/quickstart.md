# Quickstart

Connect to Better Stack Logs, check that it is healthy, make an authenticated call and close the client.
The client reads `GROW_BETTER_STACK_LOGS_URL` and `GROW_BETTER_STACK_LOGS_TOKEN` (and the vendor variables
listed in [Configuration](configuration.md)), or takes the values in code.

```sh
export GROW_BETTER_STACK_LOGS_URL=http://127.0.0.1:8080
export GROW_BETTER_STACK_LOGS_TOKEN=...   # never commit real tokens
```

=== "TypeScript"

    ```ts
    import { BetterStackClient } from "@grow-codes/better-stack-logs";

    const client = BetterStackClient.fromEnv();
    try {
      const health = await client.health();
      console.log("healthy:", health.healthy, health.status);
      console.log("ping:", await client.ping(), "ms");
      console.log("server version:", await client.serverVersion());
    } finally {
      await client.close();
    }
    ```

=== "Rust"

    ```rust
    use grow_better_stack_logs::BetterStackClient;

    #[tokio::main]
    async fn main() -> grow_better_stack_logs::Result<()> {
        let client = BetterStackClient::from_env()?;
        let health = client.health().await?;
        println!("healthy: {} {}", health.healthy, health.status);
        println!("ping: {:?}", client.ping().await?);
        println!("server version: {}", client.server_version().await?);
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

        betterstack "github.com/grow-codes-packages/better-stack-logs/go"
    )

    func main() {
        ctx := context.Background()
        client, err := betterstack.NewClientFromEnv()
        if err != nil {
            log.Fatal(err)
        }
        defer client.Close()

        health, err := client.Health(ctx)
        if err != nil {
            log.Fatal(err)
        }
        fmt.Println("healthy:", health.Healthy, health.Status)

        latency, err := client.Ping(ctx)
        if err != nil {
            log.Fatal(err)
        }
        fmt.Println("ping:", latency)

        version, err := client.ServerVersion(ctx)
        if err != nil {
            log.Fatal(err)
        }
        fmt.Println("server version:", version)
    }
    ```

=== "Python"

    ```python
    from grow_better_stack_logs import BetterStackClient

    with BetterStackClient.from_env() as client:
        health = client.health()
        print("healthy:", health.healthy, health.status)
        print("ping:", client.ping(), "ms")
        print("server version:", client.server_version())
    ```

    Async code uses `AsyncBetterStackClient` with the same methods:

    ```python
    import asyncio
    from grow_better_stack_logs import AsyncBetterStackClient

    async def main() -> None:
        async with AsyncBetterStackClient.from_env() as client:
            print(await client.health())

    asyncio.run(main())
    ```

=== "Java"

    ```java
    import codes.grow.packages.betterstack.BetterStackClient;
    import codes.grow.packages.betterstack.core.HealthStatus;

    public final class Quickstart {
      public static void main(String[] args) {
        try (BetterStackClient client = BetterStackClient.fromEnv()) {
          HealthStatus health = client.health();
          System.out.println("healthy: " + health.healthy() + " " + health.status());
          System.out.println("ping: " + client.ping().toMillis() + " ms");
          System.out.println("server version: " + client.serverVersion());
        }
      }
    }
    ```

## Next steps

- [Configuration](configuration.md): timeouts, retries, TLS, proxy, environment variables.
- [Functions](functions.md): the Better Stack Logs API of this SDK.
- [Error handling](errors.md): error codes and retries.
