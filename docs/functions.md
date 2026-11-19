# Functions

The Better Stack Logs API of this SDK. Every function exists in all five languages with the same
behaviour, named per language convention (`searchLogs` in TypeScript and Java, `search_logs` in
Python and Rust, `SearchLogs` in Go). Each page shows every function with an example per language.

| Page | Functions | Better Stack host |
| - | - | - |
| [Client](client.md) | `fromEnv`, `health`, `ping`, `serverVersion`, `close` | both |
| [Ingest](functions/ingest.md) | `exporter`, `newExporter`, `export`, `sendEvents`, `otlpExporter`, `newOtlpExporter` | ingesting host (Bearer source token) |
| [SQL query](functions/query.md) | `query()`, `query`, `queryRaw`, `searchLogs`, `renderSearchSql`, `escapeParamValue`, `sqlString`, `jsonEscape`, `logRecordFromRow` | SQL host (ClickHouse HTTP, Basic) |
| [Log client and search](functions/logging.md) | `logClient`, `send`, `sendBatch`, `flush`, `stats`, `logger`, `search`, `tail`, `listStreams`, `listLabels`, `waitForLog`, adapters | both |

## Integration coverage

Every public function is called against the Better Stack stub in the Docker suite
(`make test-docker`), with a round trip: what the SDK sends to the ingest host is read back through
the SQL API.

| Function | Docker round trip |
| - | - |
| `health`, `ping` (both sides and ingest-only), `serverVersion`, `close` | yes (`core.health` conformance scenario plus service tests) |
| `exporter` / `newExporter` / `sendEvents` (JSON, NDJSON, MessagePack, with and without gzip) | yes, read back with `query` |
| `export` (records with severity, trace ids, resource, attributes) | yes, read back with `searchLogs` |
| `otlpExporter` / `newOtlpExporter` (protobuf; JSON where supported) | yes, read back with `waitForLog` |
| `logClient` (HTTP and OTLP), `logger`, `send`, `sendBatch`, `flush`, `stats` | yes (`logging.*` conformance scenarios) |
| `query` (bound parameters incl. a value with a backslash and a quote, `FORMAT JSON`), `queryRaw` | yes |
| `searchLogs` (window, order, limit, cursor, quotes), `search`, `waitForLog`, `tail`, `listStreams` | yes |
| `listLabels` | yes (`UNSUPPORTED`) |
| error mapping: 403, 402, 406, 401/516, 404/60, 47, 400/62, 429 with `Retry-After`, 503 | yes (stub fault control) |

Cases the stub cannot produce are unit-tested only; see [Compatibility](compatibility.md).
