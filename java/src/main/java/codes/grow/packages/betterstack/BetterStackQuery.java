package codes.grow.packages.betterstack;

import codes.grow.packages.betterstack.core.BetterStackException;
import codes.grow.packages.betterstack.core.CancelledException;
import codes.grow.packages.betterstack.core.ConfigException;
import codes.grow.packages.betterstack.core.HttpTransport;
import codes.grow.packages.betterstack.core.Json;
import codes.grow.packages.betterstack.core.TransportRequest;
import codes.grow.packages.betterstack.core.TransportResponse;
import codes.grow.packages.betterstack.core.UnsupportedException;
import codes.grow.packages.betterstack.core.ValidationException;
import codes.grow.packages.betterstack.logging.LogQueryClient;
import codes.grow.packages.betterstack.logging.LogRecord;
import codes.grow.packages.betterstack.logging.LogRecordJson;
import codes.grow.packages.betterstack.logging.LogSearchResult;
import codes.grow.packages.betterstack.logging.SearchOptions;
import codes.grow.packages.betterstack.logging.Severity;
import codes.grow.packages.betterstack.logging.SortOrder;
import codes.grow.packages.betterstack.logging.TailHandle;
import codes.grow.packages.betterstack.logging.TailOptions;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL query client for Better Stack Logs: the ClickHouse HTTP interface of the query host with the
 * connection's own Basic credentials. Get it from {@link BetterStackClient#query()}; it is
 * thread-safe. Better Stack allows 4 concurrent log queries per account.
 *
 * <p>Implements {@link LogQueryClient} over the configured source table: {@code search} runs {@link
 * #searchLogs}, {@code tail} polls {@code searchLogs}, {@code listStreams} returns the configured
 * collection and {@code listLabels} is unsupported (Better Stack events have no labels).
 */
public final class BetterStackQuery implements LogQueryClient {
  /** Default page size of {@link #searchLogs} and {@link #search}. */
  public static final int DEFAULT_LIMIT = 100;

  /** Largest page size. */
  public static final int MAX_LIMIT = 10_000;

  /** Window of a search without a start: 24 h before the end (or before now). */
  public static final Duration DEFAULT_SEARCH_WINDOW = Duration.ofHours(24);

  /** Rows read per tail poll. */
  static final int TAIL_PAGE_LIMIT = 1000;

  private static final Pattern FORMAT_CLAUSE =
      Pattern.compile("\\bFORMAT\\s+([A-Za-z][A-Za-z0-9_]*)\\s*;?\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Set<String> JSON_FORMATS =
      Set.of("jsoneachrow", "json", "jsonstringseachrow", "ndjson", "jsonlines");
  private static final Pattern DATE_TIME =
      Pattern.compile(
          "^(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:?\\d{2})?$");
  private static final DateTimeFormatter DT64 =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
  private static final Pattern HEX = Pattern.compile("[0-9a-f]+");
  private static final Set<String> TOP_LEVEL_KEYS =
      Set.of(
          "dt",
          "message",
          "msg",
          "level",
          "severity",
          "severity_number",
          "span",
          "trace_id",
          "span_id",
          "trace_flags",
          "resource",
          "attributes",
          "observed_dt");
  private static final TypeReference<LinkedHashMap<String, Object>> ROW = new TypeReference<>() {};

  private final BetterStackClient client;
  private final HttpTransport transport;
  private final BetterStackConfig config;
  private final Clock clock;

  BetterStackQuery(
      BetterStackClient client, HttpTransport transport, BetterStackConfig config, Clock clock) {
    this.client = client;
    this.transport = transport;
    this.config = config;
    this.clock = clock;
  }

  // --------------------------------------------------------------------------------- raw SQL

  /**
   * Runs SQL and returns the response text unchanged (any {@code FORMAT}).
   *
   * @param sql SQL text
   * @return the body
   */
  public String queryRaw(String sql) {
    return queryRaw(sql, Map.of(), QueryOptions.defaults());
  }

  /**
   * Runs SQL with bound parameters and returns the response text unchanged.
   *
   * @param sql SQL with {@code {name:Type}} placeholders
   * @param params values: String, Number, Boolean, {@link Instant} ({@code YYYY-MM-DD
   *     HH:MM:SS.ffffff} UTC) or {@code null} (sent as {@code \N})
   * @param options ClickHouse settings
   * @return the body
   * @throws BetterStackException mapped by ClickHouse exception code
   */
  public String queryRaw(String sql, Map<String, ?> params, QueryOptions options) {
    return HttpTransport.await(queryRawAsync(sql, params, options), "query");
  }

  /**
   * Asynchronous {@link #queryRaw(String, Map, QueryOptions)}. Parameters are bound server side:
   * each {@code {name:Type}} placeholder takes the URL parameter {@code param_<name>}.
   *
   * @param sql SQL with placeholders
   * @param params values
   * @param options ClickHouse settings
   * @return a future with the body
   */
  public CompletableFuture<String> queryRawAsync(
      String sql, Map<String, ?> params, QueryOptions options) {
    return send(sql, params, options, "query").thenApply(TransportResponse::text);
  }

  private CompletableFuture<TransportResponse> send(
      String sql, Map<String, ?> params, QueryOptions options, String operation) {
    TransportRequest request;
    try {
      request = request(sql, params, options, operation);
    } catch (BetterStackException e) {
      return CompletableFuture.failedFuture(e);
    }
    return BetterStackErrors.mapFailure(
        transport.sendAsync(request), e -> BetterStackErrors.mapQueryError(e, operation));
  }

  private TransportRequest request(
      String sql, Map<String, ?> params, QueryOptions options, String operation) {
    if (sql == null || sql.trim().isEmpty()) {
      throw new ValidationException(operation + ": sql must not be empty");
    }
    TransportRequest.Builder b =
        TransportRequest.post("/")
            .header("Authorization", "Basic " + config.basicCredentials().orElseThrow())
            .body(sql.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8")
            .idempotent(true)
            .operation(operation);
    for (Map.Entry<String, Object> e : options.settings().entrySet()) {
      if (!BetterStackConfig.isValidIdentifier(e.getKey())) {
        throw new ValidationException(
            operation + ": invalid setting name " + Json.stringify(e.getKey()));
      }
      b.query(e.getKey(), String.valueOf(e.getValue()));
    }
    if (params != null) {
      for (Map.Entry<String, ?> e : params.entrySet()) {
        if (!BetterStackConfig.isValidIdentifier(e.getKey())) {
          throw new ValidationException(
              operation + ": invalid parameter name " + Json.stringify(e.getKey()));
        }
        b.query("param_" + e.getKey(), paramText(operation, e.getKey(), e.getValue()));
      }
    }
    return b.build();
  }

  private static String paramText(String operation, String name, Object value) {
    if (value == null) {
      return "\\N";
    }
    if (value instanceof Instant) {
      return formatDateTime64((Instant) value);
    }
    if ((value instanceof Double && !Double.isFinite((Double) value))
        || (value instanceof Float && !Float.isFinite((Float) value))) {
      throw new ValidationException(operation + ": parameter " + name + " must be a finite number");
    }
    return value.toString();
  }

  /**
   * Runs SQL and returns the rows.
   *
   * @param sql SQL text
   * @return rows
   */
  public List<Map<String, Object>> query(String sql) {
    return query(sql, Map.of(), QueryOptions.defaults());
  }

  /**
   * Runs SQL with bound parameters and returns the rows.
   *
   * @param sql SQL with {@code {name:Type}} placeholders
   * @param params values (see {@link #queryRaw(String, Map, QueryOptions)})
   * @return rows
   */
  public List<Map<String, Object>> query(String sql, Map<String, ?> params) {
    return query(sql, params, QueryOptions.defaults());
  }

  /**
   * Runs SQL and returns the rows. Without a {@code FORMAT} clause {@code FORMAT JSONEachRow} is
   * appended; {@code FORMAT JSONEachRow}/{@code JSON} (also with {@code
   * output_format_json_array_of_rows}) are parsed, other formats raise {@link ValidationException}
   * (use {@link #queryRaw}). ClickHouse renders 64-bit integers as strings. A ClickHouse exception
   * appended to a {@code 200} answer raises the mapped error.
   *
   * <pre>{@code
   * List<Map<String, Object>> rows = q.query(
   *     "SELECT count() AS c FROM remote(t1_app_logs) WHERE position(raw, {needle:String}) > 0",
   *     Map.of("needle", "checkout"));
   * }</pre>
   *
   * @param sql SQL with placeholders
   * @param params values
   * @param options ClickHouse settings
   * @return rows
   */
  public List<Map<String, Object>> query(String sql, Map<String, ?> params, QueryOptions options) {
    return HttpTransport.await(queryAsync(sql, params, options), "query");
  }

  /**
   * Asynchronous {@link #query(String, Map, QueryOptions)}.
   *
   * @param sql SQL with placeholders
   * @param params values
   * @param options ClickHouse settings
   * @return a future with the rows
   */
  public CompletableFuture<List<Map<String, Object>>> queryAsync(
      String sql, Map<String, ?> params, QueryOptions options) {
    return rowsAsync(sql, params, options, "query");
  }

  private CompletableFuture<List<Map<String, Object>>> rowsAsync(
      String sql, Map<String, ?> params, QueryOptions options, String operation) {
    String trimmed = sql == null ? "" : sql.trim().replaceAll(";\\s*$", "");
    Matcher m = FORMAT_CLAUSE.matcher(trimmed);
    if (m.find()) {
      if (!JSON_FORMATS.contains(m.group(1).toLowerCase(Locale.ROOT))) {
        return CompletableFuture.failedFuture(
            new ValidationException(
                operation
                    + ": FORMAT "
                    + m.group(1)
                    + " cannot be parsed into rows; use queryRaw or FORMAT JSONEachRow"));
      }
    } else if (!trimmed.isEmpty()) {
      trimmed = trimmed + " FORMAT JSONEachRow";
    }
    return send(trimmed, params, options, operation)
        .thenApply(r -> parseRows(r.text(), r.status(), r.requestId().orElse(null)));
  }

  /**
   * Parses a JSON-family response body ({@code JSONEachRow}, a JSON array of rows or {@code FORMAT
   * JSON}) into rows.
   *
   * @param text response body
   * @return rows
   * @throws BetterStackException for a ClickHouse exception in the body or invalid JSON
   */
  public static List<Map<String, Object>> parseRows(String text) {
    return parseRows(text, 200, null);
  }

  private static List<Map<String, Object>> parseRows(String text, int status, String requestId) {
    String trimmed = text.trim();
    List<Map<String, Object>> rows = new ArrayList<>();
    if (trimmed.isEmpty()) {
      return rows;
    }
    if (trimmed.startsWith("[")) {
      JsonNode arr = tryParse(trimmed);
      if (arr == null || !arr.isArray()) {
        throw streamError(trimmed, status, requestId);
      }
      for (JsonNode n : arr) {
        rows.add(row(n, status, requestId));
      }
      return rows;
    }
    for (String line : trimmed.split("\n")) {
      String l = line.trim();
      if (l.isEmpty()) {
        continue;
      }
      JsonNode n = tryParse(l);
      if (n == null || !n.isObject()) {
        if (rows.isEmpty()) {
          // FORMAT JSON spans several lines: parse the whole document instead
          JsonNode doc = tryParse(trimmed);
          if (doc != null && doc.isObject() && doc.path("data").isArray()) {
            return dataRows(doc, status, requestId);
          }
        }
        throw streamError(l, status, requestId);
      }
      rows.add(Json.mapper().convertValue(n, ROW));
    }
    if (rows.size() == 1 && rows.get(0).containsKey("meta") && rows.get(0).get("data") != null) {
      return dataRows(tryParse(trimmed), status, requestId);
    }
    return rows;
  }

  private static List<Map<String, Object>> dataRows(JsonNode doc, int status, String requestId) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (JsonNode n : doc.path("data")) {
      rows.add(row(n, status, requestId));
    }
    return rows;
  }

  private static Map<String, Object> row(JsonNode n, int status, String requestId) {
    if (!n.isObject()) {
      throw new ServerResponseException("query: invalid row in response", status, requestId);
    }
    return Json.mapper().convertValue(n, ROW);
  }

  private static JsonNode tryParse(String text) {
    try {
      return Json.tree(text.getBytes(StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** The error for a ClickHouse exception in a 200 body, or an invalid-response error. */
  private static BetterStackException streamError(String text, int status, String requestId) {
    for (String line : text.split("\n")) {
      if (BetterStackErrors.clickHouseCode(line).isPresent()) {
        String l = line.trim();
        String message = "query: " + (l.length() > 300 ? l.substring(0, 300) : l);
        return BetterStackErrors.errorForClickHouseCode(
                BetterStackErrors.clickHouseCode(line).getAsInt(), message, status, requestId)
            .orElseGet(() -> new ServerResponseException(message, status, requestId));
      }
    }
    return new ServerResponseException("query: invalid JSON in response", status, requestId);
  }

  // ------------------------------------------------------------------------------- helpers

  /**
   * Escapes a value as a ClickHouse single-quoted string literal (backslash, quote, newline,
   * carriage return, tab and NUL are backslash-escaped).
   *
   * @param value text
   * @return the literal including quotes
   */
  public static String sqlString(String value) {
    StringBuilder sb = new StringBuilder(value.length() + 2).append('\'');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\':
          sb.append("\\\\");
          break;
        case '\'':
          sb.append("\\'");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        case '\0':
          sb.append("\\0");
          break;
        default:
          sb.append(c);
      }
    }
    return sb.append('\'').toString();
  }

  /**
   * Escapes text like a JSON string value without the surrounding quotes (quote, backslash and
   * control characters), as it appears inside the raw event JSON.
   *
   * @param text text
   * @return escaped text
   */
  public static String jsonEscape(String text) {
    String s = Json.stringify(text);
    return s.substring(1, s.length() - 1);
  }

  /**
   * Formats an instant as ClickHouse {@code DateTime64(6)} text in UTC ({@code YYYY-MM-DD
   * HH:MM:SS.ffffff}).
   *
   * @param t instant
   * @return the text
   */
  public static String formatDateTime64(Instant t) {
    return DT64.format(t);
  }

  /**
   * Parses a {@code dt} value: ClickHouse {@code DateTime64} text ({@code 2026-11-18
   * 21:00:00.123456}, UTC, 0 to 9 fraction digits), an RFC 3339 string, UNIX seconds or
   * milliseconds, or an {@link Instant}.
   *
   * @param value value
   * @return the instant, or empty when unparseable
   */
  public static Optional<Instant> parseDateTime(Object value) {
    if (value instanceof Instant) {
      return Optional.of((Instant) value);
    }
    if (value instanceof Number) {
      double d = ((Number) value).doubleValue();
      if (!Double.isFinite(d)) {
        return Optional.empty();
      }
      long ms = d > 1e12 ? (long) d : (long) (d * 1000);
      return Optional.of(Instant.ofEpochMilli(ms));
    }
    if (!(value instanceof String)) {
      return Optional.empty();
    }
    Matcher m = DATE_TIME.matcher(((String) value).trim());
    if (!m.matches()) {
      return Optional.empty();
    }
    try {
      String frac = m.group(3) == null ? "" : m.group(3);
      if (frac.length() > 9) {
        frac = frac.substring(0, 9);
      }
      int nanos =
          frac.isEmpty() ? 0 : Integer.parseInt(String.format("%-9s", frac).replace(' ', '0'));
      LocalDateTime local = LocalDateTime.parse(m.group(1) + "T" + m.group(2)).withNano(nanos);
      String zone = m.group(4);
      ZoneOffset offset =
          zone == null || zone.equals("Z")
              ? ZoneOffset.UTC
              : ZoneOffset.of(
                  zone.length() == 5 ? zone.substring(0, 3) + ":" + zone.substring(3) : zone);
      return Optional.of(local.toInstant(offset));
    } catch (java.time.DateTimeException e) {
      return Optional.empty();
    }
  }

  private static String hexId(JsonNode v, int length) {
    if (v == null || !v.isTextual()) {
      return null;
    }
    String s = v.asText().trim().toLowerCase(Locale.ROOT);
    if (s.length() != length || !HEX.matcher(s).matches() || s.chars().allMatch(c -> c == '0')) {
      return null;
    }
    return s;
  }

  private static JsonNode first(JsonNode a, JsonNode b) {
    return a != null && !a.isNull() && !a.isMissingNode() ? a : b;
  }

  /**
   * Normalizes a {@code {dt, raw}} row into a {@link LogRecord}: {@code body} = {@code message}
   * (else {@code msg}, else the raw text); severity from {@code severity_number}, else {@code
   * level}/{@code severity} text (default INFO), with the canonical text when the level is known;
   * timestamp from {@code dt} (else the event's {@code dt}); trace ids from {@code
   * span.trace_id}/{@code span.span_id} (else top-level {@code trace_id}/{@code span_id}, hex
   * only); {@code resource} from {@code resource}; every other key plus the {@code attributes}
   * object into attributes.
   *
   * @param row result row
   * @return the record
   */
  public static LogRecord logRecordFromRow(Map<String, ?> row) {
    Object rawValue = row.get("raw");
    String rawText = rawValue instanceof String ? (String) rawValue : Json.stringify(rawValue);
    JsonNode parsed = tryParse(rawText);
    JsonNode event =
        parsed != null && parsed.isObject() ? parsed : Json.mapper().createObjectNode();
    LogRecord.Builder b = LogRecord.builder();
    Instant ts =
        parseDateTime(row.get("dt"))
            .or(() -> parseDateTime(event.path("dt").isTextual() ? event.get("dt").asText() : null))
            .orElse(Instant.EPOCH);
    b.timestamp(ts);
    JsonNode message = first(event.get("message"), event.get("msg"));
    if (message == null || message.isNull()) {
      b.body(rawText);
    } else {
      b.body(message.isTextual() ? message.asText() : message.toString());
    }
    String levelText = null;
    for (String k : new String[] {"level", "severity"}) {
      JsonNode v = event.get(k);
      if (v != null && v.isTextual() && !v.asText().isEmpty()) {
        levelText = v.asText();
        break;
      }
    }
    Optional<Severity> fromText = Severity.parse(levelText);
    Optional<Severity> fromNumber = severityFromNumber(event.get("severity_number"));
    Severity severity = fromNumber.or(() -> fromText).orElse(Severity.INFO);
    b.severity(severity);
    b.severityText(
        fromText.map(Severity::text).orElse(levelText != null ? levelText : severity.text()));
    Map<String, Object> attributes = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> e : event.properties()) {
      if (!TOP_LEVEL_KEYS.contains(e.getKey()) && !e.getValue().isNull()) {
        attributes.put(e.getKey(), LogRecordJson.toAttrValue(e.getValue()));
      }
    }
    JsonNode attrs = event.get("attributes");
    if (attrs != null && attrs.isObject()) {
      for (Map.Entry<String, JsonNode> e : attrs.properties()) {
        if (!e.getValue().isNull()) {
          attributes.put(e.getKey(), LogRecordJson.toAttrValue(e.getValue()));
        }
      }
    }
    b.attributes(attributes);
    JsonNode resource = event.get("resource");
    if (resource != null && resource.isObject()) {
      Map<String, Object> res = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> e : resource.properties()) {
        if (!e.getValue().isNull()) {
          res.put(e.getKey(), LogRecordJson.toAttrValue(e.getValue()));
        }
      }
      b.resource(res);
    }
    JsonNode observed = event.get("observed_dt");
    if (observed != null && observed.isTextual()) {
      parseDateTime(observed.asText()).ifPresent(b::observedTimestamp);
    }
    JsonNode span = event.path("span");
    String traceId = hexId(first(span.get("trace_id"), event.get("trace_id")), 32);
    if (traceId != null) {
      b.traceId(traceId);
      String spanId = hexId(first(span.get("span_id"), event.get("span_id")), 16);
      if (spanId != null) {
        b.spanId(spanId);
      }
      JsonNode flags = event.get("trace_flags");
      if (flags != null && flags.isIntegralNumber() && flags.asInt() >= 0 && flags.asInt() <= 255) {
        b.traceFlags(flags.asInt());
      }
    }
    return b.build();
  }

  private static Optional<Severity> severityFromNumber(JsonNode v) {
    if (v == null) {
      return Optional.empty();
    }
    if (v.isNumber()) {
      int n = v.asInt();
      return n >= 1 && n <= 24 ? Optional.of(Severity.fromNumber(n)) : Optional.empty();
    }
    if (v.isTextual()) {
      try {
        int n = Integer.parseInt(v.asText().trim());
        return n >= 1 && n <= 24 ? Optional.of(Severity.fromNumber(n)) : Optional.empty();
      } catch (NumberFormatException e) {
        return Severity.parse(v.asText());
      }
    }
    return Optional.empty();
  }

  // -------------------------------------------------------------------------------- search

  private static String encodeCursor(int offset) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(("{\"o\":" + offset + "}").getBytes(StandardCharsets.UTF_8));
  }

  private static int decodeCursor(String cursor) {
    if (cursor == null || cursor.isEmpty()) {
      return 0;
    }
    try {
      JsonNode n = Json.tree(Base64.getUrlDecoder().decode(cursor.replace("=", "")));
      JsonNode o = n.get("o");
      if (o != null && o.isInt() && o.asInt() >= 0) {
        return o.asInt();
      }
    } catch (RuntimeException e) {
      // invalid below
    }
    throw new ValidationException("logs.search: invalid cursor");
  }

  /**
   * Renders the SQL of {@link #searchLogs}: hot {@code remote(<source>_logs)} UNION ALL cold {@code
   * s3Cluster(primary, <source>_s3) WHERE _row_type = 1}, the inclusive {@code dt} window ({@code
   * toDateTime64('…', 6, 'UTC')}), {@code position(raw, <JSON-escaped contains>) > 0}, {@code ORDER
   * BY dt}, {@code LIMIT <limit + 1>} (to detect a next page) and the cursor's {@code OFFSET},
   * {@code FORMAT JSONEachRow}. Values are escaped string literals; the source is a checked
   * identifier.
   *
   * @param options search options
   * @return the SQL
   * @throws ConfigException without a source and a configured source table
   * @throws ValidationException for an invalid source, limit, window or cursor
   */
  public String renderSearchSql(SearchLogsOptions options) {
    String source = options.source().orElse(config.sourceTable().orElse(null));
    if (source == null || source.isEmpty()) {
      throw new ConfigException(
          "config: searchLogs needs a source or a configured sourceTable"
              + " (GROW_BETTER_STACK_LOGS_SOURCE_TABLE)");
    }
    if (!BetterStackConfig.isValidIdentifier(source)) {
      throw new ValidationException("logs.search: source must match ^[A-Za-z_][A-Za-z0-9_]*$");
    }
    int limit = options.limit();
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new ValidationException(
          "logs.search: limit must be an integer between 1 and " + MAX_LIMIT);
    }
    Instant end = options.end().orElse(null);
    Instant start =
        options
            .start()
            .orElseGet(() -> (end != null ? end : clock.instant()).minus(DEFAULT_SEARCH_WINDOW));
    if (end != null && start.isAfter(end)) {
      throw new ValidationException("logs.search: start must not be after end");
    }
    int offset = decodeCursor(options.cursor().orElse(null));
    StringBuilder sql = new StringBuilder();
    sql.append("SELECT dt, raw FROM (SELECT dt, raw FROM remote(")
        .append(source)
        .append("_logs) UNION ALL SELECT dt, raw FROM s3Cluster(primary, ")
        .append(source)
        .append("_s3) WHERE _row_type = 1) WHERE ");
    if (end == null) {
      sql.append("dt >= ").append(ts(start));
    } else {
      sql.append("dt BETWEEN ").append(ts(start)).append(" AND ").append(ts(end));
    }
    String contains = options.contains().orElse("");
    if (!contains.isEmpty()) {
      sql.append(" AND position(raw, ").append(sqlString(jsonEscape(contains))).append(") > 0");
    }
    sql.append(" ORDER BY dt ")
        .append(options.order() == SortOrder.ASC ? "ASC" : "DESC")
        .append(" LIMIT ")
        .append(limit + 1);
    if (offset > 0) {
      sql.append(" OFFSET ").append(offset);
    }
    return sql.append(" FORMAT JSONEachRow").toString();
  }

  private static String ts(Instant t) {
    return "toDateTime64(" + sqlString(formatDateTime64(t)) + ", 6, 'UTC')";
  }

  /** One de-duplicated page of rows and whether more rows exist. */
  private record Page(List<Map<String, Object>> rows, boolean more) {}

  private CompletableFuture<Page> searchRowsAsync(SearchLogsOptions options) {
    String sql;
    try {
      sql = renderSearchSql(options);
    } catch (BetterStackException e) {
      return CompletableFuture.failedFuture(e);
    }
    int limit = options.limit();
    return rowsAsync(sql, Map.of(), QueryOptions.defaults(), "logs.search")
        .thenApply(
            rows -> {
              boolean more = rows.size() > limit;
              List<Map<String, Object>> page = more ? rows.subList(0, limit) : rows;
              List<Map<String, Object>> out = new ArrayList<>(page.size());
              Set<String> seen = new HashSet<>();
              for (Map<String, Object> r : page) {
                if (seen.add(r.get("dt") + "\u0000" + r.get("raw"))) {
                  out.add(r);
                }
              }
              return new Page(out, more);
            });
  }

  /**
   * Searches the hot and cold logs of a collection (see {@link #renderSearchSql}) with an inclusive
   * time window and a literal substring match, newest first by default. Rows are normalized ({@link
   * #logRecordFromRow}) and de-duplicated on {@code (dt, raw)} where hot and cold overlap; {@code
   * nextCursor} is set when more rows exist.
   *
   * @param options search options
   * @return one page
   */
  public LogSearchResult searchLogs(SearchLogsOptions options) {
    return HttpTransport.await(searchLogsAsync(options), "logs.search");
  }

  /**
   * Asynchronous {@link #searchLogs(SearchLogsOptions)}.
   *
   * @param options search options
   * @return a future page
   */
  public CompletableFuture<LogSearchResult> searchLogsAsync(SearchLogsOptions options) {
    return searchRowsAsync(options)
        .thenApply(
            page -> {
              List<LogRecord> records = new ArrayList<>(page.rows().size());
              for (Map<String, Object> r : page.rows()) {
                records.add(logRecordFromRow(r));
              }
              Optional<String> next =
                  page.more()
                      ? Optional.of(
                          encodeCursor(
                              decodeCursor(options.cursor().orElse(null)) + options.limit()))
                      : Optional.empty();
              return new LogSearchResult(records, next, null);
            });
  }

  private static SearchLogsOptions toSearchLogs(String query, SearchOptions options) {
    return SearchLogsOptions.builder()
        .contains(query)
        .start(options.start().orElse(null))
        .end(options.end().orElse(null))
        .limit(options.limit())
        .order(options.order())
        .cursor(options.cursor().orElse(null))
        .build();
  }

  /**
   * {@link LogQueryClient#search}: {@code query} is the {@code contains} text, searched in the
   * configured source table.
   *
   * @param query text
   * @param options window, limit, order, cursor
   * @return one page
   */
  @Override
  public LogSearchResult search(String query, SearchOptions options) {
    return searchLogs(toSearchLogs(query, options));
  }

  /**
   * Asynchronous {@link #search(String, SearchOptions)}.
   *
   * @param query text
   * @param options options
   * @return a future page
   */
  @Override
  public CompletableFuture<LogSearchResult> searchAsync(String query, SearchOptions options) {
    return searchLogsAsync(toSearchLogs(query, options));
  }

  /**
   * The configured collections: {@code [sourceTable]}, or empty. Better Stack's data plane has no
   * listing endpoint.
   *
   * @return collection names
   */
  @Override
  public List<String> listStreams() {
    return config.sourceTable().map(List::of).orElse(List.of());
  }

  /**
   * Better Stack events are schemaless JSON without labels.
   *
   * @return never
   * @throws UnsupportedException always
   */
  @Override
  public List<String> listLabels() {
    throw new UnsupportedException("logs.listLabels: Better Stack events have no labels");
  }

  // ---------------------------------------------------------------------------------- tail

  /**
   * Polls {@link #searchLogs} every {@code options.interval()} (default 1 s) for new records of the
   * configured source table that contain {@code query}, starting at {@code options.start()}
   * (default now), and calls {@code onRecord} for each new record in {@code dt} order. Records are
   * de-duplicated at the polling boundary; polling errors go to {@code options.onError()} and the
   * next poll retries. The tail ends when the handle is stopped or the client is closed.
   *
   * @param query text to match
   * @param onRecord callback per record (exceptions are ignored)
   * @param options start, interval, error callback
   * @return the handle
   * @throws ConfigException without a configured source table
   */
  @Override
  public TailHandle tail(String query, Consumer<LogRecord> onRecord, TailOptions options) {
    if (config.sourceTable().isEmpty()) {
      throw new ConfigException("config: tail needs a configured sourceTable");
    }
    if (onRecord == null) {
      throw new ValidationException("logs.tail: onRecord is required");
    }
    if (options.interval().isZero() || options.interval().isNegative()) {
      throw new ValidationException("logs.tail: interval must be positive");
    }
    PollTail tail =
        new PollTail(query, onRecord, options, options.start().orElseGet(clock::instant));
    client.register(tail);
    tail.thread.start();
    return tail;
  }

  private final class PollTail implements TailHandle {
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Thread thread;

    PollTail(String query, Consumer<LogRecord> onRecord, TailOptions options, Instant start) {
      this.thread = new Thread(() -> run(query, onRecord, options, start), "better-stack-tail");
      this.thread.setDaemon(true);
    }

    private void run(
        String query, Consumer<LogRecord> onRecord, TailOptions options, Instant start) {
      Instant cursor = start;
      Set<String> seen = new HashSet<>();
      while (!stopped.get()) {
        try {
          Page page =
              HttpTransport.await(
                  searchRowsAsync(
                      SearchLogsOptions.builder()
                          .contains(query)
                          .start(cursor)
                          .order(SortOrder.ASC)
                          .limit(TAIL_PAGE_LIMIT)
                          .build()),
                  "logs.tail");
          for (Map<String, Object> r : page.rows()) {
            Optional<Instant> dt = parseDateTime(r.get("dt"));
            if (dt.isEmpty() || dt.get().isBefore(cursor)) {
              continue;
            }
            if (dt.get().isAfter(cursor)) {
              cursor = dt.get();
              seen.clear();
            }
            if (!seen.add(String.valueOf(r.get("raw"))) || stopped.get()) {
              continue;
            }
            try {
              onRecord.accept(logRecordFromRow(r));
            } catch (RuntimeException ignored) {
              // a failing callback must not stop the tail
            }
          }
        } catch (CancelledException e) {
          if (stopped.get()) {
            return;
          }
          report(options, e);
        } catch (RuntimeException e) {
          report(options, e);
        }
        try {
          Thread.sleep(options.interval().toMillis());
        } catch (InterruptedException e) {
          return;
        }
      }
    }

    private void report(TailOptions options, RuntimeException e) {
      if (!stopped.get()) {
        try {
          options.onError().accept(e);
        } catch (RuntimeException ignored) {
          // the error callback must not stop the tail
        }
      }
    }

    @Override
    public void stop() {
      if (stopped.compareAndSet(false, true)) {
        thread.interrupt();
        if (Thread.currentThread() != thread) {
          try {
            thread.join(5000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }
  }
}
