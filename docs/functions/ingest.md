# Ingest

Logs go to the **source's ingesting host** (`s<N>.<region>.betterstackdata.com`, config `url`) with
`Authorization: Bearer <source token>`. The SDK never sends the source token to the SQL host. Most
applications use the batching [log client](logging.md); the exporters below are the layer under it
and are useful for raw events, one-off sends and custom pipelines.

| Function | Better Stack API | Result |
| - | - | - |
| `exporter` | `POST {url}/` | the shared HTTP exporter with the config's `encoding` (`json` unless `GROW_BETTER_STACK_LOGS_ENCODING` says otherwise) |
| `newExporter({encoding, maxRequestBytes, maxRecordBytes})` | `POST {url}/` | a new HTTP exporter with its own encoding and limits |
| `exporter.export(records, {gzip})` | `POST {url}/` | `ExportResult{accepted, rejected}`; `LogRecord`s are mapped to Better Stack events (below) |
| `exporter.sendEvents(events, {encoding, gzip})` | `POST {url}/` | raw event objects, sent as given (nested JSON kept); `dt` may be a time value, UNIX seconds/ms or an RFC 3339 string |
| `otlpExporter` | `POST {url}/v1/logs` | the shared OTLP/HTTP exporter (protobuf, `application/x-protobuf`) |
| `newOtlpExporter({protocol, scopeName, maxRequestBytes})` | `POST {url}/v1/logs` | a new OTLP exporter; `protocol` `protobuf` (default) or `json` (not in Go) |

## Encodings and limits

| Encoding | `Content-Type` | Body |
| - | - | - |
| `json` (default) | `application/json` | one JSON array per request |
| `ndjson` | `application/x-ndjson` | one JSON object per line |
| `msgpack` | `application/msgpack` | one MessagePack array; `dt` is a MessagePack timestamp extension (type −1) |

- Bodies are gzip-compressed by default (`Content-Encoding: gzip`); pass `gzip: false` to send them
  plain.
- A request never exceeds 10 MiB (Better Stack's limit, measured compressed; the SDK splits on the
  uncompressed size, which is stricter). A single record above `maxRecordBytes` (default 10 MiB) is
  rejected before sending: `export` counts it as `rejected`, the log client reports it to `onDrop`.
- `dt` is written as RFC 3339 UTC with up to nanosecond digits (`2026-11-18T21:00:00.123456789Z`).

## Record → event mapping

```json
{
  "dt": "2026-11-18T21:00:00.123Z",
  "message": "order placed",
  "level": "info",
  "severity_number": 9,
  "span": { "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "span_id": "00f067aa0ba902b7" },
  "trace_flags": 1,
  "resource": { "service.name": "checkout" },
  "attributes": { "order_id": 42 },
  "observed_dt": "2026-11-18T21:00:00.125Z"
}
```

`span.trace_id` / `span.span_id` are Better Stack's documented log ↔ trace correlation fields.
Reading a row back ([SQL query](query.md)) reverses the mapping.

## Errors and retries

| Answer | Error | Retried |
| - | - | - |
| `403 Unauthorized` (bad or missing source token) | `AUTHENTICATION` | no |
| `402 Quota exceeded` | `RATE_LIMITED`, `retryable = false` | no |
| `406 Couldn't parse JSON content.` | `VALIDATION` | no |
| `413 payload reached size limit` | `VALIDATION` | no |
| `429`, `5xx`, network errors | `RATE_LIMITED` / `UNAVAILABLE` | yes, with backoff and `Retry-After` |

Exports are retried as idempotent even though `POST` is not: Better Stack documents no
deduplication, so a retried batch can be stored twice.

## Examples

### Raw events with another encoding


=== "TypeScript"

    ```ts
    import { BetterStackClient } from "@grow-codes/better-stack-logs";

    const client = BetterStackClient.fromEnv();
    const msgpack = client.newExporter({ encoding: "msgpack" });
    await msgpack.sendEvents([
      { message: "payment accepted", dt: new Date(), order: { id: 42, total: 9.5 } },
    ]);
    await client.exporter.sendEvents([{ message: "plain JSON, no gzip" }], { gzip: false });
    await client.close();
    ```

=== "Rust"

    ```rust
    use grow_better_stack_logs::{BetterStackClient, Encoding, IngestExporterOptions, SendEventsOptions};
    use serde_json::json;

    let client = BetterStackClient::from_env()?;
    let msgpack = client.new_exporter(IngestExporterOptions::default().encoding(Encoding::Msgpack))?;
    msgpack
        .send_events(&[json!({ "message": "payment accepted", "order": { "id": 42 } })], SendEventsOptions::default())
        .await?;
    let plain = SendEventsOptions { gzip: false, ..SendEventsOptions::default() };
    client.exporter()?.send_events(&[json!({ "message": "plain JSON, no gzip" })], plain).await?;
    client.close().await;
    ```

=== "Go"

    ```go
    client, err := betterstack.NewClientFromEnv()
    if err != nil {
    	return err
    }
    defer client.Close()

    msgpack, err := client.NewExporter(betterstack.IngestExporterOptions{Encoding: betterstack.EncodingMsgPack})
    if err != nil {
    	return err
    }
    err = msgpack.SendEvents(ctx, []map[string]any{
    	{"message": "payment accepted", "dt": time.Now(), "order": map[string]any{"id": 42}},
    }, betterstack.SendEventsOptions{})
    err = client.Exporter().SendEvents(ctx, []map[string]any{{"message": "plain JSON, no gzip"}},
    	betterstack.SendEventsOptions{DisableGzip: true})
    ```

=== "Python"

    ```python
    from datetime import datetime, timezone
    from grow_better_stack_logs import BetterStackClient

    with BetterStackClient.from_env() as client:
        client.new_exporter(encoding="msgpack").send_events(
            [{"message": "payment accepted", "dt": datetime.now(timezone.utc), "order": {"id": 42}}]
        )
        client.exporter.send_events([{"message": "plain JSON, no gzip"}], gzip=False)
    ```

=== "Java"

    ```java
    import codes.grow.packages.betterstack.*;

    try (BetterStackClient client = BetterStackClient.fromEnv()) {
      client.newExporter(BetterStackLogExporterOptions.of(IngestEncoding.MSGPACK))
          .sendEvents(List.of(Map.of("message", "payment accepted", "order", Map.of("id", 42))));
      client.exporter().sendEvents(
          List.of(Map.of("message", "plain JSON, no gzip")), SendEventsOptions.defaults().withGzip(false));
    }
    ```


### OTLP exporter

The OTLP exporter posts `ExportLogsServiceRequest` messages to `/v1/logs` on the same ingesting
host with the same token. Records are grouped by resource; the instrumentation scope is the
package name (see [Compatibility](../compatibility.md#otlp-scope-name)) with the SDK version.
`partial_success.rejected_log_records` is reported as rejected records.


=== "TypeScript"

    ```ts
    const otlpLogs = client.logClient({ protocol: "otlp" }); // batching client over OTLP
    otlpLogs.logger("api").info("via OTLP", { route: "/orders" });
    await otlpLogs.flush();

    const json = client.newOtlpExporter({ protocol: "json" }); // OTLP/JSON instead of protobuf
    ```

=== "Rust"

    ```rust
    use grow_better_stack_logs::{LogClientOptions, LogProtocol, OtlpExporterOptions, OtlpProtocol};

    let otlp_logs = client.log_client(LogClientOptions::default().protocol(LogProtocol::Otlp))?;
    otlp_logs.logger(Some("api"), attrs! {}).info("via OTLP", attrs! { "route" => "/orders" });
    otlp_logs.flush(None).await?;

    let json = client.new_otlp_exporter(OtlpExporterOptions { protocol: OtlpProtocol::Json, ..Default::default() })?;
    ```

=== "Go"

    ```go
    // Go sends OTLP/HTTP protobuf only.
    otlpLogs, err := client.NewLogClientWith(client.OTLPExporter(), betterstack.LogClientOptions{})
    if err != nil {
    	return err
    }
    otlpLogs.Logger("api", nil).Info(ctx, "via OTLP", "route", "/orders")
    err = otlpLogs.Flush(ctx)
    ```

=== "Python"

    ```python
    otlp_logs = client.log_client(protocol="otlp")
    otlp_logs.logger("api").info("via OTLP", {"route": "/orders"})
    otlp_logs.flush(10)

    json_exporter = client.new_otlp_exporter(protocol="json")
    ```

=== "Java"

    ```java
    BatchingLogClient otlpLogs = client.logClient(
        BetterStackLogClientOptions.builder().protocol(LogProtocol.OTLP).build());
    otlpLogs.logger("api").info("via OTLP", Map.of("route", "/orders"));
    otlpLogs.flush();

    BetterStackOtlpLogExporter json =
        client.newOtlpExporter(BetterStackOtlpLogExporterOptions.of(OtlpProtocol.JSON));
    ```
