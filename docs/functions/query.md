# SQL query

Better Stack exposes stored logs through the **ClickHouse HTTP interface** of a SQL connection
(`<region>-connect.betterstackdata.com`, config `queryUrl`) with the connection's own HTTP Basic
credentials (`queryUsername`, `queryPassword`). `client.query()` returns the `BetterStackQuery`
client; it raises `CONFIG` when the SQL side is not configured. Basic credentials are never sent to
the ingest host.

| Function | ClickHouse HTTP | Result |
| - | - | - |
| `query(sql, params, {settings})` | `POST {queryUrl}/`, body = SQL, `param_<name>` URL parameters | rows (objects keyed by column); `FORMAT JSONEachRow` is appended when the SQL has no `FORMAT`; `FORMAT JSON` / `JSONEachRow` (also with `output_format_json_array_of_rows`) are parsed, other formats raise `VALIDATION` |
| `queryRaw(sql, params, {settings})` | same | the response body unchanged (any format) |
| `searchLogs({source, contains, start, end, limit, order, cursor})` | the SQL below | `LogSearchResult{records, nextCursor}` |
| `renderSearchSql(options)` | — | the SQL `searchLogs` would run |
| `escapeParamValue(text)` | — | a String parameter value in ClickHouse's escaped (TSV) text format |
| `sqlString(text)`, `jsonEscape(text)` | — | a quoted SQL string literal; JSON string escaping without quotes |
| `logRecordFromRow(row)` | — | a `{dt, raw}` row as a normalized `LogRecord` |

## Tables

Each source has a **collection** `<team_id>_<table_name>` (for example `t1234_myapp`; config
`sourceTable`). Hot logs are `remote(<collection>_logs)`, cold logs
`s3Cluster(primary, <collection>_s3)` with `WHERE _row_type = 1` (spans `_row_type = 3`), spans
`remote(<collection>_spans)`. Columns are `dt` (`DateTime64`, UTC) and `raw` (the event JSON). The
collection is an identifier: it is validated against `^[A-Za-z_][A-Za-z0-9_]*$` and cannot be a
bound parameter (use `{t:Identifier}` in your own SQL).

## Parameters

`query` and `queryRaw` bind parameters **server side**: write ClickHouse placeholders
`{name:Type}` in the SQL and pass the values; each is sent as the URL parameter `param_<name>`.
Values are converted to ClickHouse text: `null` → `\N` (use a `Nullable(…)` type), booleans →
`true`/`false`, numbers as decimals (NaN and infinity raise `VALIDATION`), time values →
`YYYY-MM-DD HH:MM:SS.ffffff` (UTC), strings as they are. ClickHouse parses parameter values in the
**escaped (TabSeparated) text format**, so string values are escaped first: backslash → `\\`,
tab → `\t`, newline → `\n`, carriage return → `\r` (`escapeParamValue`). Quotes need no escaping.
Parameter names must match `^[A-Za-z_][A-Za-z0-9_]*$`; a placeholder without a value is rejected by
the server (`VALIDATION`). `settings` are sent as ClickHouse settings (for example
`max_execution_time`).


=== "TypeScript"

    ```ts
    const q = client.query();
    const rows = await q.query(
      "SELECT count() AS c FROM remote({t:Identifier}) " +
        "WHERE position(raw, {needle:String}) > 0 AND dt >= {since:DateTime64(6)}",
      { t: "t1234_myapp_logs", needle: 'C:\\tmp\\it\'s "quoted"', since: new Date(Date.now() - 3_600_000) },
      { settings: { max_execution_time: 30 } },
    );
    console.log(rows[0]?.c); // 64-bit integers arrive as strings: "5"

    const csv = await q.queryRaw("SELECT dt, raw FROM remote(t1234_myapp_logs) LIMIT 5 FORMAT CSV");
    ```

=== "Rust"

    ```rust
    use grow_better_stack_logs::{query_params, QueryOptions};

    let q = client.query()?;
    let rows = q
        .query(
            "SELECT count() AS c FROM remote({t:Identifier}) \
             WHERE position(raw, {needle:String}) > 0 AND dt >= {since:DateTime64(6)}",
            &query_params! {
                "t" => "t1234_myapp_logs",
                "needle" => r#"C:\tmp\it's "quoted""#,
                "since" => std::time::SystemTime::now() - std::time::Duration::from_secs(3600),
            },
            &QueryOptions::default().setting("max_execution_time", 30),
        )
        .await?;
    println!("{}", rows[0]["c"]);
    ```

=== "Go"

    ```go
    q := client.Query()
    rows, err := q.Query(ctx,
    	"SELECT count() AS c FROM remote({t:Identifier}) "+
    		"WHERE position(raw, {needle:String}) > 0 AND dt >= {since:DateTime64(6)}",
    	map[string]any{"t": "t1234_myapp_logs", "needle": `C:\tmp\it's "quoted"`, "since": time.Now().Add(-time.Hour)},
    	betterstack.QueryOptions{Settings: map[string]any{"max_execution_time": 30}})
    if err != nil {
    	return err
    }
    fmt.Println(rows[0]["c"])
    csv, err := q.QueryRaw(ctx, "SELECT dt, raw FROM remote(t1234_myapp_logs) LIMIT 5 FORMAT CSV", nil)
    ```

=== "Python"

    ```python
    from datetime import datetime, timedelta, timezone

    q = client.query()
    rows = q.query(
        "SELECT count() AS c FROM remote({t:Identifier}) "
        "WHERE position(raw, {needle:String}) > 0 AND dt >= {since:DateTime64(6)}",
        {
            "t": "t1234_myapp_logs",
            "needle": r'C:\tmp\it\'s "quoted"',
            "since": datetime.now(timezone.utc) - timedelta(hours=1),
        },
        settings={"max_execution_time": 30},
    )
    print(rows[0]["c"])
    csv = q.query_raw("SELECT dt, raw FROM remote(t1234_myapp_logs) LIMIT 5 FORMAT CSV")
    ```

=== "Java"

    ```java
    BetterStackQuery q = client.query();
    List<Map<String, Object>> rows = q.query(
        "SELECT count() AS c FROM remote({t:Identifier}) "
            + "WHERE position(raw, {needle:String}) > 0 AND dt >= {since:DateTime64(6)}",
        Map.of("t", "t1234_myapp_logs", "needle", "C:\\tmp\\it's \"quoted\"",
            "since", Instant.now().minus(Duration.ofHours(1))),
        QueryOptions.defaults().withSetting("max_execution_time", 30));
    System.out.println(rows.get(0).get("c"));
    String csv = q.queryRaw("SELECT dt, raw FROM remote(t1234_myapp_logs) LIMIT 5 FORMAT CSV");
    ```


## searchLogs

`searchLogs` searches hot and cold storage of one collection (default: the configured
`sourceTable`) and returns normalized records. It renders **one runnable SQL statement with escaped
SQL literals** (it does not use server-side parameters, so `renderSearchSql` output can be pasted
into any SQL client):

```sql
SELECT dt, raw FROM (
  SELECT dt, raw FROM remote(t1234_myapp_logs)
  UNION ALL
  SELECT dt, raw FROM s3Cluster(primary, t1234_myapp_s3) WHERE _row_type = 1
) WHERE dt BETWEEN toDateTime64('2026-11-18 20:00:00.000000', 6, 'UTC')
             AND toDateTime64('2026-11-18 21:00:00.000000', 6, 'UTC')
  AND position(raw, 'order \\"42\\"') > 0
ORDER BY dt DESC LIMIT 101 FORMAT JSONEachRow
```

| Option | Default | Notes |
| - | - | - |
| `source` | `sourceTable` | collection identifier; `CONFIG` when neither is set |
| `contains` | match all | case-sensitive literal substring of the raw event JSON; JSON-escaped first, so `"` and `\` match inside string values |
| `start` | `end` (or now) − 24 h | inclusive |
| `end` | no upper bound | inclusive when given (robust to clock skew and ingest delay when unset) |
| `limit` | 100 | 1 … 10 000 records per page |
| `order` | `desc` | `asc` or `desc` by `dt` |
| `cursor` | — | opaque `nextCursor` of the previous page |

Rows are deduplicated on `(dt, raw)` (hot and cold storage overlap briefly) and normalized:
`body` = `message` (else `msg`, else the raw text), severity from `severity_number` / `level` /
`severity`, `traceId` / `spanId` from `span.trace_id` / `span.span_id`, `resource` from `resource`,
every other field into `attributes`.


=== "TypeScript"

    ```ts
    const q = client.query();
    let page = await q.searchLogs({ contains: "order 42", start: new Date(Date.now() - 3_600_000), limit: 50 });
    for (const r of page.records) console.log(r.timestamp.toISOString(), r.severityText, r.body);
    if (page.nextCursor) page = await q.searchLogs({ contains: "order 42", cursor: page.nextCursor });
    console.log(q.renderSearchSql({ source: "t1234_other", contains: "x", order: "asc" }));
    ```

=== "Rust"

    ```rust
    use grow_better_stack_logs::SearchLogsOptions;
    use grow_better_stack_logs::logging::SortOrder;

    let q = client.query()?;
    let opts = SearchLogsOptions::default().contains("order 42").limit(50);
    let page = q.search_logs(opts.clone()).await?;
    for r in &page.records {
        println!("{:?} {} {}", r.timestamp, r.severity_text, r.body);
    }
    if let Some(cursor) = page.next_cursor {
        let _next = q.search_logs(opts.cursor(cursor)).await?;
    }
    println!("{}", q.render_search_sql(&SearchLogsOptions::default().source("t1234_other").order(SortOrder::Asc))?);
    ```

=== "Go"

    ```go
    q := client.Query()
    opts := betterstack.SearchLogsOptions{Contains: "order 42", Start: time.Now().Add(-time.Hour), Limit: 50}
    page, err := q.SearchLogs(ctx, opts)
    if err != nil {
    	return err
    }
    for _, r := range page.Records {
    	fmt.Println(r.Timestamp.Format(time.RFC3339Nano), r.SeverityText, r.Body)
    }
    if page.NextCursor != "" {
    	opts.Cursor = page.NextCursor
    	page, err = q.SearchLogs(ctx, opts)
    }
    sql, err := q.RenderSearchSQL(betterstack.SearchLogsOptions{Source: "t1234_other", Order: betterstack.OrderAsc})
    ```

=== "Python"

    ```python
    from datetime import datetime, timedelta, timezone

    q = client.query()
    start = datetime.now(timezone.utc) - timedelta(hours=1)
    page = q.search_logs(contains="order 42", start=start, limit=50)
    for r in page.records:
        print(r.timestamp.isoformat(), r.severity_text, r.body)
    if page.next_cursor:
        page = q.search_logs(contains="order 42", start=start, cursor=page.next_cursor)
    print(q.render_search_sql(source="t1234_other", contains="x", order="asc"))
    ```

=== "Java"

    ```java
    BetterStackQuery q = client.query();
    SearchLogsOptions opts = SearchLogsOptions.builder()
        .contains("order 42").start(Instant.now().minus(Duration.ofHours(1))).limit(50).build();
    LogSearchResult page = q.searchLogs(opts);
    page.records().forEach(r -> System.out.println(r.timestamp() + " " + r.severityText() + " " + r.body()));
    if (page.nextCursor().isPresent()) {
      page = q.searchLogs(opts.toBuilder().cursor(page.nextCursor().get()).build());
    }
    System.out.println(q.renderSearchSql(SearchLogsOptions.builder().source("t1234_other").build()));
    ```


## Errors

The ClickHouse exception code wins over the HTTP status. It is read from the
`X-ClickHouse-Exception-Code` header, else from `Code: N.` in the body, and also detected when
ClickHouse appends an exception to a `200` response after streaming started.

| ClickHouse code | Error |
| - | - |
| 516 `AUTHENTICATION_FAILED`, 192, 193, 194 | `AUTHENTICATION` |
| 497 `ACCESS_DENIED` | `PERMISSION_DENIED` |
| 60 `UNKNOWN_TABLE`, 81 `UNKNOWN_DATABASE` | `NOT_FOUND` |
| 62 `SYNTAX_ERROR`, 47 `UNKNOWN_IDENTIFIER`, 46, 36, 53, 42, 73, 456, 457 | `VALIDATION` |
| 241 `MEMORY_LIMIT_EXCEEDED` | `VALIDATION` with a hint (narrow the time range) |
| 202 `TOO_MANY_SIMULTANEOUS_QUERIES` | `RATE_LIMITED` (retryable) |
| 159, 160 (timeouts) | `TIMEOUT` |
| 48 `NOT_IMPLEMENTED` (or HTTP 501) | `UNSUPPORTED` |
| other codes | by HTTP status (SPEC rules); an unknown code after a `200` is a non-retryable `UNAVAILABLE` |

Queries are idempotent and retried on 429, 5xx and network errors. Better Stack allows 4
concurrent log queries per account (Standard plan) and suggests 1–2 s between calls, so poll with
`waitForLog` / `tail` at intervals of a second or more.
