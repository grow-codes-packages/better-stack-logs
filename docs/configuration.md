# Configuration

`BetterStackConfig` holds every setting of a client. It is **immutable** after construction,
validated when it is built (invalid values raise a `CONFIG` error) and its string/debug form
**redacts every credential** (source token, query password and the Basic credentials).

## Better Stack settings

Better Stack Logs has two hosts with their own credentials. The **ingest side** (`url` +
`sourceToken`) and the **SQL side** (`queryUrl` + `queryUsername` + `queryPassword`) are each
all-or-nothing; at least one side must be complete. A client with one side is valid, and calls that
need the missing side raise `CONFIG`.

| Setting | Default | Environment variables (precedence order) |
| - | - | - |
| `url`: the source's ingesting host (`s1234.eu-nbg-2.betterstackdata.com`) | none; required for ingestion (no legacy default host) | `GROW_BETTER_STACK_LOGS_URL`, `BETTERSTACK_INGESTING_URL`, `BETTERSTACK_INGESTING_HOST` |
| `sourceToken`: source token (secret), `Authorization: Bearer` to the ingest host only | none | `GROW_BETTER_STACK_LOGS_SOURCE_TOKEN`, `BETTERSTACK_SOURCE_TOKEN` |
| `queryUrl`: SQL host of a connection (`eu-nbg-2-connect.betterstackdata.com`) | none; required for SQL | `GROW_BETTER_STACK_LOGS_QUERY_URL`, `BETTERSTACK_QUERY_URL`, `BETTERSTACK_QUERY_HOST` |
| `queryUsername` | none | `GROW_BETTER_STACK_LOGS_QUERY_USERNAME`, `BETTERSTACK_QUERY_USERNAME` |
| `queryPassword` (secret), HTTP Basic to the SQL host only | none | `GROW_BETTER_STACK_LOGS_QUERY_PASSWORD`, `BETTERSTACK_QUERY_PASSWORD` |
| `sourceTable`: default collection (`t1234_myapp`) for `search`, `searchLogs`, `ping`, `listStreams` | none | `GROW_BETTER_STACK_LOGS_SOURCE_TABLE`, `BETTERSTACK_SOURCE_TABLE` |
| `encoding`: default ingest body encoding of the exporters, `json`, `ndjson` or `msgpack` | `json` | `GROW_BETTER_STACK_LOGS_ENCODING` |

- A URL that contains `://` is used as given (`http://better-stack-logs:8080` in Docker); a bare
  `host[:port]`, the form Better Stack shows for the `*_HOST` variables, becomes
  `https://host[:port]`. Trailing slashes are removed. Both URLs must be `http` or `https`.
- `sourceTable` must match `^[A-Za-z_][A-Za-z0-9_]*$` (it is an SQL identifier).
- `encoding` is case-insensitive; each exporter can override it (`newExporter({encoding})`).
- Ingest URLs are per source: Better Stack shows the ingesting host on the source's page (the
  `ingesting_host` of the Sources API). SQL hosts and credentials come from
  Integrations → Connect remotely (the password is shown once).

## Common settings

| Setting | Default | Environment variable |
| - | - | - |
| `connectTimeout`: connection setup | 10 s | `GROW_BETTER_STACK_LOGS_CONNECT_TIMEOUT` |
| `requestTimeout`: per attempt (headers + body) | 30 s | `GROW_BETTER_STACK_LOGS_REQUEST_TIMEOUT` |
| `totalTimeout`: whole call incl. retries and backoff | 120 s | `GROW_BETTER_STACK_LOGS_TOTAL_TIMEOUT` |
| `retry`: retry policy (below) | 3 retries | `GROW_BETTER_STACK_LOGS_MAX_RETRIES` |
| `tls.caCertPath` / `tls.caCertPem`: extra CA | none | `GROW_BETTER_STACK_LOGS_CA_CERT` (path) |
| `tls.clientCertPath` + `tls.clientKeyPath`: mutual TLS | none | `GROW_BETTER_STACK_LOGS_CLIENT_CERT`, `GROW_BETTER_STACK_LOGS_CLIENT_KEY` |
| `tls.insecureSkipVerify`: tests only, logs a warning once | `false` | `GROW_BETTER_STACK_LOGS_INSECURE_SKIP_VERIFY` |
| `proxy`: proxy URL | none (environment proxies are **ignored**) | `GROW_BETTER_STACK_LOGS_PROXY` |
| `headers`: extra request headers (both hosts) | `{}` | — |
| `userAgentSuffix`: appended to the User-Agent | none | — |
| `hooks`: request/response callbacks ([Hooks](hooks.md)) | none | — |
| `logger`: diagnostic logger ([Hooks](hooks.md)) | silent | — |

The common settings apply to both hosts. Every request sends
`User-Agent: grow-codes-better-stack-logs/<version> (<language>)`.

## From the environment

`fromEnv` resolves each setting with this precedence:

1. the value passed in code (overrides),
2. `GROW_BETTER_STACK_LOGS_<NAME>`,
3. the `BETTERSTACK_*` vendor variables, in the order of the table above,
4. the default.

Parsing rules:

- **Durations** accept integer milliseconds (`1500`) or Go-style units: `500ms`, `10s`, `2m`, `1h`,
  combinations such as `1m30s`.
- **Booleans** accept `true/false`, `1/0`, `yes/no`, `on/off` (any case).
- An **empty** variable counts as unset.
- A present but **invalid** value raises a `CONFIG` error that names the variable, never its value.


=== "TypeScript"

    ```ts
    import { BetterStackClient, BetterStackConfig } from "@grow-codes/better-stack-logs";

    // Environment only
    const client = BetterStackClient.fromEnv();

    // Environment with explicit overrides (explicit values win)
    const config = BetterStackConfig.fromEnv({ requestTimeoutMs: 5_000, encoding: "ndjson" });

    // Everything in code (ingest-only: no SQL side)
    const ingestOnly = new BetterStackConfig({
      url: "s1234.eu-nbg-2.betterstackdata.com",
      sourceToken: process.env.MY_SOURCE_TOKEN,
      encoding: "msgpack",
      connectTimeoutMs: 5_000,
      retry: { maxRetries: 5, initialBackoffMs: 200 },
      proxy: "http://proxy.internal:3128",
      userAgentSuffix: "my-app/1.4",
    });
    console.log(String(ingestOnly)); // sourceToken shown as [REDACTED]
    ```

=== "Rust"

    ```rust
    use std::time::Duration;
    use grow_better_stack_logs::{BetterStackClient, BetterStackConfig, Encoding, RetryPolicy};

    // Environment only
    let client = BetterStackClient::from_env()?;

    // Environment with explicit overrides (explicit values win)
    let config = BetterStackConfig::builder()
        .request_timeout(Duration::from_secs(5))
        .build_from_env()?;

    // Everything in code (query-only: no ingest side)
    let query_only = BetterStackConfig::builder()
        .query_url("eu-nbg-2-connect.betterstackdata.com")
        .query_username("u-abc")
        .query_password(std::env::var("MY_QUERY_PASSWORD").unwrap_or_default())
        .source_table("t1234_myapp")
        .encoding(Encoding::Ndjson)
        .retry(RetryPolicy::default().with_max_retries(5))
        .proxy("http://proxy.internal:3128")
        .build()?;
    println!("{query_only:?}"); // password shown as [REDACTED]
    ```

=== "Go"

    ```go
    // Environment only
    client, err := betterstack.NewClientFromEnv()

    // Everything in code: zero values mean "use the default"
    cfg := betterstack.Config{
    	CommonConfig: betterstack.CommonConfig{
    		URL:            "s1234.eu-nbg-2.betterstackdata.com",
    		ConnectTimeout: 5 * time.Second,
    		Retry:          &betterstack.RetryPolicy{MaxRetries: 5, InitialBackoff: 200 * time.Millisecond},
    		Proxy:          "http://proxy.internal:3128",
    	},
    	SourceToken: betterstack.NewSecret(os.Getenv("MY_SOURCE_TOKEN")),
    	Encoding:    betterstack.EncodingMsgPack,
    }
    if err := cfg.Validate(); err != nil {
    	log.Fatal(err) // errors.Is(err, betterstack.ErrConfig)
    }
    custom, err := betterstack.NewClient(cfg)

    // Environment with explicit overrides (explicit values win; nil env = process environment)
    merged, err := betterstack.ConfigFromEnvWith(betterstack.Config{SourceTable: "t1234_myapp"}, nil)
    fmt.Println(merged) // credentials shown as [REDACTED]
    ```

=== "Python"

    ```python
    import os
    from grow_better_stack_logs import BetterStackClient, BetterStackConfig, RetryPolicy

    # Environment only
    client = BetterStackClient.from_env()

    # Environment with explicit overrides (explicit values win)
    config = BetterStackConfig.from_env(request_timeout=5.0, encoding="ndjson")

    # Everything in code: durations are float seconds (datetime.timedelta also works)
    explicit = BetterStackConfig(
        url="s1234.eu-nbg-2.betterstackdata.com",
        source_token=os.environ.get("MY_SOURCE_TOKEN"),
        query_url="eu-nbg-2-connect.betterstackdata.com",
        query_username="u-abc",
        query_password=os.environ.get("MY_QUERY_PASSWORD"),
        source_table="t1234_myapp",
        retry=RetryPolicy(max_retries=5, initial_backoff=0.2),
        proxy="http://proxy.internal:3128",
    )
    print(repr(explicit))  # credentials shown as [REDACTED]
    ```

=== "Java"

    ```java
    import java.time.Duration;
    import codes.grow.packages.betterstack.BetterStackClient;
    import codes.grow.packages.betterstack.BetterStackConfig;
    import codes.grow.packages.betterstack.IngestEncoding;

    // Environment only
    BetterStackClient client = BetterStackClient.fromEnv();

    // Everything in code
    BetterStackConfig config = BetterStackConfig.builder()
        .url("s1234.eu-nbg-2.betterstackdata.com")
        .sourceToken(System.getenv("MY_SOURCE_TOKEN"))
        .queryUrl("eu-nbg-2-connect.betterstackdata.com")
        .queryUsername("u-abc")
        .queryPassword(System.getenv("MY_QUERY_PASSWORD"))
        .sourceTable("t1234_myapp")
        .encoding(IngestEncoding.NDJSON)
        .connectTimeout(Duration.ofSeconds(5))
        .proxy("http://proxy.internal:3128")
        .build();
    BetterStackClient custom = BetterStackClient.create(config);
    System.out.println(config); // credentials shown as [REDACTED]
    ```

## Retry policy

| Field | Default | Meaning |
| - | - | - |
| `maxRetries` | 3 | retries after the first attempt (so up to 4 attempts) |
| `initialBackoff` | 100 ms | backoff cap of the first retry |
| `maxBackoff` | 10 s | cap of every backoff |
| `multiplier` | 2 | growth of the cap per retry |
| `maxRetryAfter` | 60 s | longest server-requested `Retry-After` the client waits for |

Backoff for retry *n* (0-based) is **full jitter**: a random wait between 0 and
`min(maxBackoff, initialBackoff × multiplier^n)`.

Requests are retried on HTTP 429, 408, 502, 503, 504 and on network errors. **Idempotent**
requests (`GET HEAD OPTIONS PUT DELETE`, or marked idempotent) are also retried on HTTP 500.
Other requests (for example `POST`) are retried only on 429 and on connection errors that happened
before the request was sent, never after an uncertain send.

On 429 and 503 the client honours `Retry-After` (seconds or HTTP date) and waits
`max(retryAfter, backoff)`. When that wait exceeds `maxRetryAfter` or the remaining
`totalTimeout`, it stops at once with `RATE_LIMITED`/`UNAVAILABLE` and sets `retryAfter` on the
error. Set `maxRetries` to 0 to turn retries off.

## Timeouts and cancellation

- `connectTimeout` bounds connection setup, `requestTimeout` each attempt, `totalTimeout` the whole
  call including backoff sleeps. An exhausted timeout raises `TIMEOUT`.
- Cancellation is honoured during requests and backoff sleeps and raises `CANCELLED`:
  TypeScript `{ signal }` (`AbortSignal`), Rust dropping the future, Go `ctx`, Python `cancel=`
  (`CancelToken`) or task cancellation, Java `…Async` futures (`cancel(true)`).

## TLS and proxies

- Add a private CA with `tls.caCertPath` (or the PEM text in `tls.caCertPem`).
- Mutual TLS needs `tls.clientCertPath` and `tls.clientKeyPath` together.
- `tls.insecureSkipVerify` disables certificate checks. Use it for local tests only; the client
  logs a warning once.
- `HTTP_PROXY`/`HTTPS_PROXY` are **ignored** so that credentials never leave through an unexpected
  proxy. Set `proxy` (or `GROW_BETTER_STACK_LOGS_PROXY`) to use one.
