package codes.grow.packages.betterstack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.betterstack.core.AuthenticationException;
import codes.grow.packages.betterstack.core.BetterStackException;
import codes.grow.packages.betterstack.core.CancelledException;
import codes.grow.packages.betterstack.core.ConfigException;
import codes.grow.packages.betterstack.core.ErrorCode;
import codes.grow.packages.betterstack.core.NotFoundException;
import codes.grow.packages.betterstack.core.PermissionDeniedException;
import codes.grow.packages.betterstack.core.RateLimitException;
import codes.grow.packages.betterstack.core.RetryPolicy;
import codes.grow.packages.betterstack.core.TimeoutException;
import codes.grow.packages.betterstack.core.UnavailableException;
import codes.grow.packages.betterstack.core.UnsupportedException;
import codes.grow.packages.betterstack.core.ValidationException;
import codes.grow.packages.betterstack.logging.LogRecord;
import codes.grow.packages.betterstack.logging.LogSearchResult;
import codes.grow.packages.betterstack.logging.SearchOptions;
import codes.grow.packages.betterstack.logging.Severity;
import codes.grow.packages.betterstack.logging.SortOrder;
import codes.grow.packages.betterstack.logging.TailHandle;
import codes.grow.packages.betterstack.logging.TailOptions;
import codes.grow.packages.betterstack.logging.WaitForLogOptions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueryTest {
  private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
  private static final String ND = "application/x-ndjson";

  private FakeServer server;
  private BetterStackClient client;
  private BetterStackQuery q;

  @BeforeEach
  void start() {
    server = FakeServer.start();
    client = client(0);
    q = client.query();
  }

  @AfterEach
  void stop() {
    client.close();
    server.close();
  }

  private BetterStackClient client(int retries) {
    return BetterStackClient.create(
        BetterStackConfig.builder()
            .queryUrl(server.url())
            .queryUsername("user")
            .queryPassword("query-secret")
            .sourceTable("t1_app")
            .retry(
                RetryPolicy.builder()
                    .maxRetries(retries)
                    .initialBackoff(Duration.ofMillis(1))
                    .build())
            .build(),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static String row(String dt, String rawJson) {
    return "{\"dt\":\""
        + dt
        + "\",\"raw\":"
        + codes.grow.packages.betterstack.core.Json.stringify(rawJson)
        + "}\n";
  }

  @Test
  void queryRowsFromFixtures() {
    server
        .enqueueFixture("query.json_each_row")
        .enqueueFixture("query.json_format")
        .enqueueFixture("query.array_of_rows")
        .enqueueFixture("query.json_extract")
        .enqueueFixture("query.s3_cold_empty");
    List<Map<String, Object>> rows = q.query("SELECT dt, raw FROM remote(t1_app_logs);");
    assertThat(rows).hasSize(11);
    assertThat(server.last().text())
        .isEqualTo("SELECT dt, raw FROM remote(t1_app_logs) FORMAT JSONEachRow");
    assertThat(server.last().header("Content-Type")).isEqualTo("text/plain; charset=utf-8");
    assertThat(server.last().header("Authorization")).startsWith("Basic ");
    assertThat(q.query("SELECT count() AS c FROM remote(t1_app_logs) FORMAT JSON", Map.of()))
        .containsExactly(Map.of("c", "11"));
    assertThat(server.last().text()).endsWith("FORMAT JSON");
    assertThat(
            q.query(
                "SELECT dt, raw FROM x SETTINGS output_format_json_array_of_rows = 1 FORMAT JSONEachRow"))
        .hasSize(2);
    assertThat(q.queryAsync("SELECT 1", Map.of(), QueryOptions.defaults()).join().get(0))
        .containsEntry("n", "7");
    assertThat(q.query("SELECT 1")).isEmpty();
  }

  @Test
  void escapeParamValueUsesTheTsvEscapedFormat() {
    assertThat(BetterStackQuery.escapeParamValue("plain 'quoted' \"text\""))
        .isEqualTo("plain 'quoted' \"text\"");
    assertThat(BetterStackQuery.escapeParamValue("a\\b\tc\nd\re")).isEqualTo("a\\\\b\\tc\\nd\\re");
    assertThat(BetterStackQuery.escapeParamValue("\\N")).isEqualTo("\\\\N");
    server.fallback((ex, r) -> FakeServer.send(ex, 200, "", ND));
    q.query("SELECT {v:String} AS v", Map.of("v", new StringBuilder("C:\\tmp\n")));
    assertThat(server.last().params()).containsEntry("param_v", "C:\\\\tmp\\n");
  }

  @Test
  void queryParamsAndSettings() {
    server.fallback((ex, r) -> FakeServer.send(ex, 200, "{\"c\":\"1\"}\n", ND));
    Map<String, Object> params = new HashMap<>();
    params.put("needle", "it's \\ x");
    params.put("since", Instant.parse("2026-01-02T03:04:05.123456789Z"));
    params.put("none", null);
    params.put("flag", true);
    params.put("n", 42);
    params.put("f", 1.5f);
    q.query(
        "SELECT count() AS c FROM remote(t1_app_logs) WHERE position(raw, {needle:String}) > 0",
        params,
        QueryOptions.defaults().withSetting("max_execution_time", 30));
    Map<String, String> sent = server.last().params();
    assertThat(sent)
        .containsEntry("param_needle", "it's \\\\ x")
        .containsEntry("param_since", "2026-01-02 03:04:05.123456")
        .containsEntry("param_none", "\\N")
        .containsEntry("param_flag", "true")
        .containsEntry("param_n", "42")
        .containsEntry("param_f", "1.5")
        .containsEntry("max_execution_time", "30");
    assertThat(server.last().text()).contains("{needle:String}");
    assertThatThrownBy(() -> q.query("SELECT {x:Float64}", Map.of("x", Double.NaN)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("finite");
    assertThatThrownBy(() -> q.query("SELECT {x:Float32}", Map.of("x", Float.POSITIVE_INFINITY)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.query("SELECT 1", Map.of("bad-name", 1)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("parameter name");
    assertThatThrownBy(
            () -> q.query("SELECT 1", Map.of(), QueryOptions.defaults().withSetting("bad;x", 1)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("setting name");
    assertThatThrownBy(() -> q.query("  ")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.queryRaw(null)).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.query("SELECT 1 FORMAT CSV"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("queryRaw");
    assertThat(q.query("SELECT 1", null)).hasSize(1);
    assertThat(new QueryOptions(null).settings()).isEmpty();
  }

  @Test
  void queryRawKeepsBody() {
    server.enqueue(200, "a\tb\n", "text/tab-separated-values");
    assertThat(q.queryRaw("SELECT a, b FROM x FORMAT TSV")).isEqualTo("a\tb\n");
    assertThat(server.last().text()).isEqualTo("SELECT a, b FROM x FORMAT TSV");
    server.enqueue(200, "x", "text/plain");
    assertThat(q.queryRawAsync("SELECT 1", Map.of("p", "v"), QueryOptions.defaults()).join())
        .isEqualTo("x");
  }

  @Test
  void queryErrorMapping() {
    server
        .enqueueFixture("query.401_bad_password")
        .enqueueFixture("query.404_unknown_table")
        .enqueueFixture("query.404_unknown_column")
        .enqueueFixture("query.400_syntax")
        .enqueueFixture("query.503_fault")
        .enqueue(
            500,
            "Code: 241. DB::Exception: Memory limit exceeded. (MEMORY_LIMIT_EXCEEDED)",
            "text/plain")
        .enqueue(500, "Code: 999. DB::Exception: odd. (ODD)", "text/plain");
    assertThatThrownBy(() -> q.query("SELECT 1"))
        .isInstanceOf(AuthenticationException.class)
        .hasMessageNotContaining("query-secret");
    assertThatThrownBy(() -> q.query("SELECT 1")).isInstanceOf(NotFoundException.class);
    BetterStackException col = catchSdk(() -> q.query("SELECT nope FROM x"));
    assertThat(col).isInstanceOf(ValidationException.class);
    assertThat(col.httpStatus()).hasValue(404);
    assertThatThrownBy(() -> q.query("SELEC")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.query("SELECT 1")).isInstanceOf(UnavailableException.class);
    assertThatThrownBy(() -> q.query("SELECT 1"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("hint");
    assertThatThrownBy(() -> q.query("SELECT 1")).isInstanceOf(UnavailableException.class);
  }

  @Test
  void retriesQueriesAsIdempotent() {
    try (var c = client(2)) {
      server.enqueueFixture("query.503_fault").enqueue(200, "{\"c\":\"1\"}\n", ND);
      assertThat(c.query().query("SELECT 1")).hasSize(1);
      assertThat(server.requests()).hasSize(2);
    }
  }

  @Test
  void exceptionAfter200() {
    server
        .enqueue(200, "{\"a\":1}\nCode: 60. DB::Exception: gone. (UNKNOWN_TABLE)\n", ND)
        .enqueue(200, "{\"a\":1}\nCode: 12345. DB::Exception: what. (X)\n", ND)
        .enqueue(200, "{\"a\":1}\nnot json\n", ND)
        .enqueue(200, "[{\"a\":1}, oops", ND)
        .enqueue(200, "[1, 2]", ND)
        .enqueue(
            200,
            "{\n  \"meta\": [],\n  \"data\": [{\"c\": \"1\"}],\n  \"rows\": 1\n}\n",
            "application/json")
        .enqueue(200, "{\"meta\":[],\"data\":[3]}", "application/json")
        .enqueue(200, "\"str\"\n", ND);
    assertThatThrownBy(() -> q.query("SELECT 1")).isInstanceOf(NotFoundException.class);
    BetterStackException unknown = catchSdk(() -> q.query("SELECT 1"));
    assertThat(unknown).isInstanceOf(ServerResponseException.class);
    assertThat(unknown.retryable()).isFalse();
    assertThat(unknown.getMessage()).contains("Code: 12345");
    assertThatThrownBy(() -> q.query("SELECT 1"))
        .isInstanceOf(ServerResponseException.class)
        .hasMessageContaining("invalid JSON");
    assertThatThrownBy(() -> q.query("SELECT 1")).isInstanceOf(ServerResponseException.class);
    assertThatThrownBy(() -> q.query("SELECT 1"))
        .isInstanceOf(ServerResponseException.class)
        .hasMessageContaining("invalid row");
    assertThat(q.query("SELECT 1 FORMAT JSON")).containsExactly(Map.of("c", "1"));
    assertThatThrownBy(() -> q.query("SELECT 1 FORMAT JSON"))
        .isInstanceOf(ServerResponseException.class);
    assertThatThrownBy(() -> q.query("SELECT 1")).isInstanceOf(ServerResponseException.class);
    assertThat(BetterStackQuery.parseRows("")).isEmpty();
    assertThat(BetterStackQuery.parseRows("{\"a\":1}\n\n{\"a\":2}")).hasSize(2);
    String longLine = "Code: 62. DB::Exception: " + "x".repeat(400);
    assertThatThrownBy(() -> BetterStackQuery.parseRows("{\"a\":1}\n" + longLine))
        .isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(330));
  }

  @Test
  void clickHouseCodeTable() {
    assertThat(BetterStackErrors.clickHouseCode(null)).isEmpty();
    assertThat(BetterStackErrors.clickHouseCode("no code")).isEmpty();
    assertThat(BetterStackErrors.clickHouseCode("Code: 99999999999. DB::Exception: x")).isEmpty();
    assertThat(BetterStackErrors.clickHouseCode("x Code: 60. DB::Exception: y")).hasValue(60);
    Map<Integer, Class<?>> expected = new HashMap<>();
    expected.put(516, AuthenticationException.class);
    expected.put(194, AuthenticationException.class);
    expected.put(497, PermissionDeniedException.class);
    expected.put(81, NotFoundException.class);
    expected.put(457, ValidationException.class);
    expected.put(241, ValidationException.class);
    expected.put(48, UnsupportedException.class);
    expected.put(159, TimeoutException.class);
    expected.put(202, RateLimitException.class);
    expected.forEach(
        (code, type) ->
            assertThat(BetterStackErrors.errorForClickHouseCode(code, "m", 400, "r"))
                .get()
                .isInstanceOf(type));
    assertThat(
            BetterStackErrors.errorForClickHouseCode(202, "m", null, null)
                .orElseThrow()
                .retryable())
        .isTrue();
    assertThat(BetterStackErrors.errorForClickHouseCode(1, "m", null, null)).isEmpty();
    // non-HTTP errors and errors without codes pass through
    BetterStackException plain = new UnavailableException("net");
    assertThat(BetterStackErrors.mapQueryError(plain, "q")).isSameAs(plain);
    assertThat(BetterStackErrors.mapIngestError(plain, "q")).isSameAs(plain);
    BetterStackException other = new NotFoundException("x", 404, null, null, null);
    assertThat(BetterStackErrors.mapIngestError(other, "q")).isSameAs(other);
    assertThat(BetterStackErrors.mapQueryError(new IllegalStateException("boom"), "q").code())
        .isEqualTo(ErrorCode.UNAVAILABLE);
  }

  @Test
  void helpers() {
    assertThat(BetterStackQuery.sqlString("a'b\\c\n\r\t\0d"))
        .isEqualTo("'a\\'b\\\\c\\n\\r\\t\\0d'");
    assertThat(BetterStackQuery.jsonEscape("say \"hi\" \\ \n"))
        .isEqualTo("say \\\"hi\\\" \\\\ \\n");
    assertThat(BetterStackQuery.formatDateTime64(Instant.parse("2026-01-02T03:04:05.1234567Z")))
        .isEqualTo("2026-01-02 03:04:05.123456");
    Instant t = Instant.parse("2026-01-02T03:04:05Z");
    assertThat(BetterStackQuery.parseDateTime(t)).contains(t);
    assertThat(BetterStackQuery.parseDateTime(1767323045L)).contains(t);
    assertThat(BetterStackQuery.parseDateTime(1767323045000L)).contains(t);
    assertThat(BetterStackQuery.parseDateTime(Double.NaN)).isEmpty();
    assertThat(BetterStackQuery.parseDateTime(List.of())).isEmpty();
    assertThat(BetterStackQuery.parseDateTime("2026-01-02 03:04:05")).contains(t);
    assertThat(BetterStackQuery.parseDateTime("2026-01-02 03:04:05.123456789123"))
        .contains(t.plusNanos(123456789));
    assertThat(BetterStackQuery.parseDateTime("2026-01-02T05:04:05.5+02:00"))
        .contains(t.plusMillis(500));
    assertThat(BetterStackQuery.parseDateTime("2026-01-02T01:04:05-0200")).contains(t);
    assertThat(BetterStackQuery.parseDateTime("2026-01-02T03:04:05Z")).contains(t);
    assertThat(BetterStackQuery.parseDateTime("2026-13-02 03:04:05")).isEmpty();
    assertThat(BetterStackQuery.parseDateTime("2026-01-02T03:04:05+99:00")).isEmpty();
    assertThat(BetterStackQuery.parseDateTime("yesterday")).isEmpty();
  }

  @Test
  void logRecordFromRowVariants() {
    LogRecord full =
        BetterStackQuery.logRecordFromRow(
            Map.of(
                "dt",
                "2026-01-02 03:04:05.678901",
                "raw",
                "{\"dt\":\"x\",\"message\":\"hello\",\"level\":\"warning\",\"severity_number\":13,"
                    + "\"span\":{\"trace_id\":\"4BF92F3577B34DA6A3CE929D0E0E4736\",\"span_id\":\"00f067aa0ba902b7\"},"
                    + "\"trace_flags\":1,\"resource\":{\"service.name\":\"svc\",\"nil\":null},"
                    + "\"attributes\":{\"n\":1,\"gone\":null},\"observed_dt\":\"2026-01-02T03:04:06Z\","
                    + "\"extra\":{\"k\":\"v\"},\"nothing\":null}"));
    assertThat(full.body()).isEqualTo("hello");
    assertThat(full.severity()).isEqualTo(Severity.WARN);
    assertThat(full.severityText()).isEqualTo("WARN");
    assertThat(full.timestamp()).isEqualTo(Instant.parse("2026-01-02T03:04:05.678901Z"));
    assertThat(full.traceId()).contains("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(full.spanId()).contains("00f067aa0ba902b7");
    assertThat(full.traceFlags()).hasValue(1);
    assertThat(full.resource()).containsEntry("service.name", "svc").doesNotContainKey("nil");
    assertThat(full.attributes())
        .containsEntry("n", 1L)
        .containsKey("extra")
        .doesNotContainKeys("gone", "nothing", "dt");
    assertThat(full.observedTimestamp()).contains(Instant.parse("2026-01-02T03:04:06Z"));

    LogRecord otlp =
        BetterStackQuery.logRecordFromRow(
            Map.of(
                "dt",
                5,
                "raw",
                "{\"msg\":{\"structured\":true},\"severity\":\"custom\",\"severity_number\":\"17\","
                    + "\"trace_id\":\"4bf92f3577b34da6a3ce929d0e0e4736\",\"span_id\":\"zz\","
                    + "\"trace_flags\":999,\"dt\":\"2026-01-02 03:04:05\"}"));
    assertThat(otlp.body()).isEqualTo("{\"structured\":true}");
    assertThat(otlp.severity()).isEqualTo(Severity.ERROR);
    assertThat(otlp.severityText()).isEqualTo("custom");
    assertThat(otlp.timestamp()).isEqualTo(Instant.ofEpochSecond(5));
    assertThat(otlp.traceId()).isPresent();
    assertThat(otlp.spanId()).isEmpty();
    assertThat(otlp.traceFlags()).isEmpty();

    Map<String, Object> rawRow = new HashMap<>();
    rawRow.put("dt", "bad");
    rawRow.put("raw", "plain text line");
    LogRecord plain = BetterStackQuery.logRecordFromRow(rawRow);
    assertThat(plain.body()).isEqualTo("plain text line");
    assertThat(plain.timestamp()).isEqualTo(Instant.EPOCH);
    assertThat(plain.severity()).isEqualTo(Severity.INFO);
    assertThat(plain.severityText()).isEqualTo("INFO");

    LogRecord nonString =
        BetterStackQuery.logRecordFromRow(
            Map.of(
                "raw",
                Map.of("message", "obj", "dt", "2026-01-02 03:04:05", "severity_number", "warn")));
    assertThat(nonString.body()).isEqualTo("obj");
    assertThat(nonString.severity()).isEqualTo(Severity.WARN);
    assertThat(nonString.timestamp()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
    LogRecord odd =
        BetterStackQuery.logRecordFromRow(
            Map.of(
                "raw",
                "{\"message\":null,\"severity_number\":99,\"level\":\"\",\"span\":{\"trace_id\":\"00000000000000000000000000000000\"},\"observed_dt\":5}"));
    assertThat(odd.traceId()).isEmpty();
    assertThat(odd.severity()).isEqualTo(Severity.INFO);
    LogRecord oddNumbers =
        BetterStackQuery.logRecordFromRow(
            Map.of(
                "raw",
                "{\"severity_number\":\"99\",\"trace_id\":5,\"span\":{\"trace_id\":\"abc\"}}"));
    assertThat(oddNumbers.severity()).isEqualTo(Severity.INFO);
    assertThat(oddNumbers.traceId()).isEmpty();
    LogRecord boolSeverity =
        BetterStackQuery.logRecordFromRow(Map.of("raw", "{\"severity_number\":true}"));
    assertThat(boolSeverity.severity()).isEqualTo(Severity.INFO);
  }

  @Test
  void renderSearchSql() {
    assertThat(q.renderSearchSql(SearchLogsOptions.defaults()))
        .isEqualTo(
            "SELECT dt, raw FROM (SELECT dt, raw FROM remote(t1_app_logs) UNION ALL SELECT dt, raw"
                + " FROM s3Cluster(primary, t1_app_s3) WHERE _row_type = 1) WHERE dt >="
                + " toDateTime64('2026-11-17 12:00:00.000000', 6, 'UTC') ORDER BY dt DESC LIMIT 101"
                + " FORMAT JSONEachRow");
    String sql =
        q.renderSearchSql(
            SearchLogsOptions.builder()
                .source("t2_other")
                .contains("it's \"x\" \\")
                .end(NOW)
                .limit(5)
                .order(SortOrder.ASC)
                .build());
    assertThat(sql)
        .contains("remote(t2_other_logs)")
        .contains(
            "dt BETWEEN toDateTime64('2026-11-17 12:00:00.000000', 6, 'UTC') AND"
                + " toDateTime64('2026-11-18 12:00:00.000000', 6, 'UTC')")
        .contains("position(raw, 'it\\'s \\\\\"x\\\\\" \\\\\\\\') > 0")
        .contains("ORDER BY dt ASC LIMIT 6");
    assertThatThrownBy(() -> q.renderSearchSql(SearchLogsOptions.builder().source("bad-x").build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.renderSearchSql(SearchLogsOptions.builder().limit(0).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.renderSearchSql(SearchLogsOptions.builder().limit(10001).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                q.renderSearchSql(
                    SearchLogsOptions.builder().start(NOW).end(NOW.minusSeconds(1)).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.renderSearchSql(SearchLogsOptions.builder().cursor("!!!").build()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("cursor");
    assertThatThrownBy(
            () -> q.renderSearchSql(SearchLogsOptions.builder().cursor("eyJvIjotMX0").build()))
        .isInstanceOf(ValidationException.class);
    assertThat(
            q.renderSearchSql(
                SearchLogsOptions.builder().cursor("eyJvIjoyMH0=").contains("").build()))
        .contains("OFFSET 20")
        .doesNotContain("position");
    SearchLogsOptions o = SearchLogsOptions.builder().source("s").contains("c").limit(3).build();
    assertThat(o.toBuilder().build().source()).contains("s");
    try (var noTable =
        BetterStackClient.builder()
            .queryUrl(server.url())
            .queryUsername("u")
            .queryPassword("p")
            .build()) {
      assertThatThrownBy(() -> noTable.query().renderSearchSql(SearchLogsOptions.defaults()))
          .isInstanceOf(ConfigException.class);
      assertThatThrownBy(() -> noTable.query().searchLogs(SearchLogsOptions.defaults()))
          .isInstanceOf(ConfigException.class);
      assertThat(noTable.query().listStreams()).isEmpty();
      assertThatThrownBy(() -> noTable.query().tail("x", r -> {}, TailOptions.defaults()))
          .isInstanceOf(ConfigException.class);
    }
  }

  @Test
  void searchLogsPagesAndDedups() {
    String a = row("2026-11-18 11:00:00.000001", "{\"message\":\"a\"}");
    String b = row("2026-11-18 11:00:00.000002", "{\"message\":\"b\"}");
    String c = row("2026-11-18 11:00:00.000003", "{\"message\":\"c\"}");
    server
        .enqueue(200, a + a + b + c, ND)
        .enqueue(200, c, ND)
        .enqueueFixture("searchLogs.contains");
    LogSearchResult page = q.searchLogs(SearchLogsOptions.builder().contains("x").limit(3).build());
    assertThat(page.records()).extracting(LogRecord::body).containsExactly("a", "b");
    assertThat(page.nextCursor()).isPresent();
    assertThat(server.last().text()).contains("LIMIT 4");
    LogSearchResult next =
        q.searchLogsAsync(
                SearchLogsOptions.builder().limit(3).cursor(page.nextCursor().get()).build())
            .join();
    assertThat(next.records()).extracting(LogRecord::body).containsExactly("c");
    assertThat(next.nextCursor()).isEmpty();
    assertThat(server.last().text()).contains("OFFSET 3");
    LogSearchResult fixture =
        q.search(
            "needle-prep",
            SearchOptions.builder()
                .start(Instant.parse("2023-01-01T00:00:00Z"))
                .end(NOW)
                .limit(50)
                .order(SortOrder.ASC)
                .build());
    assertThat(fixture.records()).isNotEmpty();
    assertThat(server.last().text())
        .contains("position(raw, 'needle-prep')", "ORDER BY dt ASC", "LIMIT 51");
  }

  @Test
  void searchAsyncWaitForLogAndLabels() {
    server.enqueue(200, "", ND).enqueueFixture("waitForLog.hit");
    assertThat(q.searchAsync("x", SearchOptions.defaults()).join().records()).isEmpty();
    LogRecord hit =
        q.waitForLog(
            "wait-for-log", WaitForLogOptions.of(Duration.ofSeconds(5), Duration.ofMillis(10)));
    assertThat(hit.body()).contains("wait-for-log");
    server.fallback((ex, r) -> FakeServer.send(ex, 200, "", ND));
    assertThatThrownBy(
            () ->
                q.waitForLog(
                    "never", WaitForLogOptions.of(Duration.ofMillis(200), Duration.ofMillis(50))))
        .isInstanceOf(TimeoutException.class);
    assertThat(q.listStreams()).containsExactly("t1_app");
    assertThatThrownBy(q::listLabels).isInstanceOf(UnsupportedException.class);
  }

  @Test
  void tailPollsDedupsAndStops() throws Exception {
    List<String> got = new CopyOnWriteArrayList<>();
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    String r1 = row("2026-11-18 12:00:01.000000", "{\"message\":\"one\"}");
    String r2 = row("2026-11-18 12:00:01.000000", "{\"message\":\"two\"}");
    String old = row("2026-11-18 11:00:00.000000", "{\"message\":\"old\"}");
    String bad = "{\"dt\":\"bad\",\"raw\":\"{}\"}\n";
    String r3 = row("2026-11-18 12:00:02.000000", "{\"message\":\"three\"}");
    AtomicInteger polls = new AtomicInteger();
    server.fallback(
        (ex, r) -> {
          int n = polls.incrementAndGet();
          if (n == 1) {
            FakeServer.send(ex, 200, old + bad + r1, ND);
          } else if (n == 2) {
            FakeServer.send(ex, 400, "Code: 62. DB::Exception: boom. (SYNTAX_ERROR)", "text/plain");
          } else if (n == 3) {
            FakeServer.send(ex, 200, r1 + r2, ND);
          } else {
            FakeServer.send(ex, 200, r1 + r2 + r3, ND);
          }
        });
    TailHandle h =
        q.tail(
            "x",
            rec -> {
              got.add(rec.body());
              if (rec.body().equals("two")) {
                throw new IllegalStateException("callback failure is ignored");
              }
            },
            new TailOptions(
                Optional.of(Instant.parse("2026-11-18T12:00:00Z")),
                Duration.ofMillis(20),
                e -> {
                  errors.add(e);
                  throw new IllegalStateException("ignored too");
                }));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (polls.get() < 6 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    h.stop();
    h.close();
    assertThat(got).containsExactly("one", "two", "three");
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0)).isInstanceOf(ValidationException.class);
    assertThat(server.requests().get(0).text())
        .contains("dt >= toDateTime64('2026-11-18 12:00:00.000000', 6, 'UTC')")
        .contains("ORDER BY dt ASC LIMIT 1001");
    assertThat(server.last().text()).contains("2026-11-18 12:00:02.000000");
    assertThatThrownBy(() -> q.tail("x", null, TailOptions.defaults()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.tail("x", r -> {}, new TailOptions(null, Duration.ZERO, null)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void tailStopsWhenClientCloses() throws Exception {
    server.fallback((ex, r) -> FakeServer.send(ex, 200, "", ND));
    BetterStackClient c = client(0);
    List<Throwable> errors = new ArrayList<>();
    TailHandle h = c.query().tail("x", r -> {}, TailOptions.defaults().withOnError(errors::add));
    Thread.sleep(50);
    c.close();
    h.stop();
    assertThat(errors).isEmpty();
    assertThatThrownBy(() -> c.query().tail("x", r -> {}, TailOptions.defaults()))
        .isInstanceOf(CancelledException.class);
  }

  @Test
  void queryAfterCloseIsCancelled() {
    client.close();
    assertThatThrownBy(() -> q.query("SELECT 1")).isInstanceOf(CancelledException.class);
    assertThatThrownBy(() -> q.searchLogsAsync(SearchLogsOptions.defaults()).join())
        .isInstanceOf(CompletionException.class);
  }

  private static BetterStackException catchSdk(Runnable r) {
    try {
      r.run();
    } catch (BetterStackException e) {
      return e;
    }
    throw new AssertionError("expected an SDK exception");
  }
}
