package codes.grow.packages.betterstack.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import codes.grow.packages.betterstack.BetterStackClient;
import codes.grow.packages.betterstack.BetterStackConfig;
import codes.grow.packages.betterstack.BetterStackLogClientOptions;
import codes.grow.packages.betterstack.BetterStackLogExporter;
import codes.grow.packages.betterstack.BetterStackLogExporterOptions;
import codes.grow.packages.betterstack.BetterStackOtlpLogExporter;
import codes.grow.packages.betterstack.BetterStackOtlpLogExporterOptions;
import codes.grow.packages.betterstack.BetterStackQuery;
import codes.grow.packages.betterstack.IngestEncoding;
import codes.grow.packages.betterstack.LogProtocol;
import codes.grow.packages.betterstack.OtlpProtocol;
import codes.grow.packages.betterstack.QueryOptions;
import codes.grow.packages.betterstack.QuotaExceededException;
import codes.grow.packages.betterstack.SearchLogsOptions;
import codes.grow.packages.betterstack.SendEventsOptions;
import codes.grow.packages.betterstack.core.AuthenticationException;
import codes.grow.packages.betterstack.core.BetterStackException;
import codes.grow.packages.betterstack.core.CancelledException;
import codes.grow.packages.betterstack.core.EnvReader;
import codes.grow.packages.betterstack.core.ErrorCode;
import codes.grow.packages.betterstack.core.HealthStatus;
import codes.grow.packages.betterstack.core.NotFoundException;
import codes.grow.packages.betterstack.core.RetryPolicy;
import codes.grow.packages.betterstack.core.TimeoutException;
import codes.grow.packages.betterstack.core.UnavailableException;
import codes.grow.packages.betterstack.core.UnsupportedException;
import codes.grow.packages.betterstack.core.ValidationException;
import codes.grow.packages.betterstack.logging.BatchOptions;
import codes.grow.packages.betterstack.logging.BatchingLogClient;
import codes.grow.packages.betterstack.logging.ExportOptions;
import codes.grow.packages.betterstack.logging.ExportResult;
import codes.grow.packages.betterstack.logging.GrowJulHandler;
import codes.grow.packages.betterstack.logging.LogInput;
import codes.grow.packages.betterstack.logging.LogRecord;
import codes.grow.packages.betterstack.logging.LogSearchResult;
import codes.grow.packages.betterstack.logging.SearchOptions;
import codes.grow.packages.betterstack.logging.Severity;
import codes.grow.packages.betterstack.logging.SortOrder;
import codes.grow.packages.betterstack.logging.TailHandle;
import codes.grow.packages.betterstack.logging.TailOptions;
import codes.grow.packages.betterstack.logging.WaitForLogOptions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Calls every public function of the Java package against the Better Stack contract stub from
 * {@code docker/compose.yml}, each with a round trip (write, then read back through the SQL API).
 */
class BetterStackIntegrationTest {
  private static final Duration WAIT = Duration.ofSeconds(30);

  private Map<String, String> env;
  private final List<BetterStackClient> clients = new ArrayList<>();
  private String run;

  @BeforeEach
  void setUp() throws Exception {
    env = ConformanceTest.environment();
    assumeTrue(
        env.containsKey(EnvReader.DEFAULT_PREFIX + "URL"),
        "GROW_BETTER_STACK_LOGS_URL is not set; run through docker/compose.yml");
    run = "java-" + UUID.randomUUID().toString().substring(0, 10);
  }

  @AfterEach
  void tearDown() {
    clients.forEach(BetterStackClient::close);
  }

  private BetterStackConfig.Builder config() {
    return BetterStackConfig.builder().fromEnv(EnvReader.of(env));
  }

  private BetterStackClient client(BetterStackConfig.Builder b) {
    BetterStackClient c = BetterStackClient.create(b.build());
    clients.add(c);
    return c;
  }

  private BetterStackClient client() {
    return client(config());
  }

  private String table() {
    return env.get("BETTERSTACK_SOURCE_TABLE");
  }

  /** Polls searchLogs until at least {@code n} records contain {@code needle}. */
  private static List<LogRecord> waitRows(BetterStackQuery q, String source, String needle, int n)
      throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (true) {
      LogSearchResult r =
          q.searchLogs(
              SearchLogsOptions.builder()
                  .source(source)
                  .contains(needle)
                  .order(SortOrder.ASC)
                  .limit(1000)
                  .build());
      if (r.records().size() >= n) {
        return r.records();
      }
      if (System.nanoTime() > deadline) {
        throw new AssertionError("found " + r.records().size() + " of " + n + " rows for " + needle);
      }
      Thread.sleep(200);
    }
  }

  /** Arms a stub fault (stub-only control endpoint, not part of the real API). */
  private void fault(String target, int status, String body) throws Exception {
    String ctl =
        env.get(
            target.equals("query")
                ? "BETTERSTACK_STUB_QUERY_CONTROL_URL"
                : "BETTERSTACK_STUB_INGEST_CONTROL_URL");
    assumeTrue(ctl != null, "no stub control URL");
    String json =
        "{\"status\":"
            + status
            + ",\"count\":1,\"target\":\""
            + target
            + "\",\"body\":\""
            + body
            + "\"}";
    HttpResponse<String> res =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(ctl + "/fault"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertTrue(res.statusCode() < 300, "fault control " + res.statusCode());
  }

  @Test
  void core() {
    BetterStackClient c = client();
    BetterStackConfig cfg = c.config();
    assertEquals(env.get(EnvReader.DEFAULT_PREFIX + "URL"), cfg.url().orElseThrow());
    assertTrue(cfg.hasIngest() && cfg.hasQuery());
    assertEquals(table(), cfg.sourceTable().orElseThrow());
    assertFalse(cfg.toString().contains(cfg.sourceToken().orElseThrow().reveal()));
    assertFalse(cfg.toString().contains(cfg.queryPassword().orElseThrow().reveal()));
    HealthStatus h = c.health();
    assertTrue(h.healthy(), h.toString());
    assertEquals("ok", h.status());
    assertEquals("query", h.details().get("endpoint"));
    assertTrue(c.healthAsync().join().healthy());
    assertTrue(c.ping().toNanos() > 0);
    assertTrue(c.pingAsync().join().toNanos() > 0);
    assertThrows(UnsupportedException.class, c::serverVersion);
    CompletionException ce =
        assertThrows(CompletionException.class, () -> c.serverVersionAsync().join());
    assertInstanceOf(UnsupportedException.class, ce.getCause());

    // ingest-only client: health = reachability, ping = empty batch (nothing stored)
    BetterStackClient ingestOnly =
        client(
            BetterStackConfig.builder()
                .url(cfg.url().orElseThrow())
                .sourceToken(cfg.sourceToken().orElseThrow()));
    HealthStatus ih = ingestOnly.health();
    assertTrue(ih.healthy(), ih.toString());
    assertEquals("ingest", ih.details().get("endpoint"));
    assertTrue(ingestOnly.ping().toNanos() > 0);
    assertThrows(
        codes.grow.packages.betterstack.core.ConfigException.class, ingestOnly::query);
    String host = env.get("BETTERSTACK_INGESTING_HOST");
    if (host != null) {
      assertEquals("https://" + host, BetterStackConfig.normalizeBaseUrl(host));
    }
    // fromEnv with the bare host:port (production form) and the vendor-only variables
    assertTrue(BetterStackConfig.fromEnv(env).hasQuery());

    c.close();
    c.close();
    assertTrue(c.isClosed());
    assertThrows(CancelledException.class, c::ping);
    assertThrows(CancelledException.class, c::logClient);
  }

  @Test
  void authErrors() {
    BetterStackClient bad =
        client(config().sourceToken("wrong-source-token").queryPassword("wrong-query-password"));
    AuthenticationException q = assertThrows(AuthenticationException.class, bad::ping);
    assertFalse(q.getMessage().contains("wrong-query-password"));
    ExportResult r =
        bad.exporter()
            .export(
                List.of(LogRecord.builder().timestamp(Instant.now()).body("x").build()),
                new ExportOptions(false, Duration.ofSeconds(10)))
            .join();
    BetterStackException e = r.error().orElseThrow();
    assertInstanceOf(AuthenticationException.class, e);
    assertFalse(e.getMessage().contains("wrong-source-token"));
    assertEquals(403, e.httpStatus().getAsInt());
  }

  @Test
  void logClientEncodingsRoundTrip() throws Exception {
    for (IngestEncoding enc : IngestEncoding.values()) {
      for (boolean gzip : new boolean[] {false, true}) {
        String name = enc.wireValue() + (gzip ? "-gzip" : "");
        BetterStackClient c = client(config().encoding(enc));
        BatchingLogClient lc =
            c.logClient(
                BetterStackLogClientOptions.builder()
                    .batchOptions(BatchOptions.builder().gzip(gzip).maxBatchSize(2).build())
                    .resource(Map.of("service.name", "grow-it-java"))
                    .build());
        String needle = run + "-" + name;
        Instant ts = Instant.parse("2026-01-02T03:04:05.678901Z");
        lc.send(
            LogRecord.builder()
                .timestamp(ts)
                .severity(Severity.WARN)
                .severityText("WARN")
                .body(needle + " first")
                .attributes(Map.of("n", 1, "unicode", "Grüße ✓", "token", "secret-value"))
                .traceId("4bf92f3577b34da6a3ce929d0e0e4736")
                .spanId("00f067aa0ba902b7")
                .build());
        lc.sendBatch(
            List.of(
                LogInput.builder(needle + " second").timestamp(ts.plusSeconds(1)).build(),
                LogInput.builder(needle + " third").timestamp(ts.plusSeconds(2)).build()));
        lc.flush();
        assertEquals(3, lc.stats().sent(), name + " " + lc.stats());
        assertEquals(0, lc.stats().failed());
        LogSearchResult res =
            c.query()
                .searchLogs(
                    SearchLogsOptions.builder()
                        .contains(needle)
                        .start(ts.minusSeconds(60))
                        .end(ts.plusSeconds(60))
                        .order(SortOrder.ASC)
                        .build());
        assertEquals(3, res.records().size(), name);
        LogRecord rec = res.records().get(0);
        assertEquals(needle + " first", rec.body());
        assertEquals(Severity.WARN, rec.severity());
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", rec.traceId().orElseThrow());
        assertEquals("00f067aa0ba902b7", rec.spanId().orElseThrow());
        assertEquals("Grüße ✓", rec.attributes().get("unicode"));
        assertFalse("secret-value".equals(rec.attributes().get("token")), "token not redacted");
        assertEquals("grow-it-java", rec.resource().get("service.name"));
        assertEquals(ts, rec.timestamp(), name);
      }
    }
  }

  @Test
  void exportersAndOtlp() throws Exception {
    BetterStackClient c = client();
    BetterStackQuery q = c.query();
    ExportResult r =
        c.exporter()
            .export(
                List.of(
                    LogRecord.builder()
                        .timestamp(Instant.now())
                        .severity(Severity.INFO)
                        .body(run + " direct")
                        .build()),
                new ExportOptions(true, Duration.ofSeconds(10)))
            .join();
    assertEquals(ExportResult.success(1), r);
    waitRows(q, null, run + " direct", 1);

    // raw events (sync + async, MessagePack with an Instant dt)
    c.exporter()
        .sendEvents(
            List.of(Map.of("message", run + " raw event", "dt", Instant.now(), "custom", Map.of("k", "v"))));
    List<LogRecord> raw = waitRows(q, null, run + " raw event", 1);
    assertEquals(Map.of("k", "v"), raw.get(0).attributes().get("custom"));
    c.exporter()
        .sendEventsAsync(
            List.of(Map.of("message", run + " async mp", "dt", Instant.now())),
            SendEventsOptions.defaults().withEncoding(IngestEncoding.MSGPACK))
        .join();
    waitRows(q, null, run + " async mp", 1);

    // custom exporter: NDJSON, own limit, through logClient(exporter, options)
    BetterStackLogExporter nd =
        c.newExporter(BetterStackLogExporterOptions.of(IngestEncoding.NDJSON).withMaxRequestBytes(1 << 20));
    assertEquals(IngestEncoding.NDJSON, nd.encoding());
    BatchingLogClient ndc = c.logClient(nd, BetterStackLogClientOptions.defaults());
    ndc.send(LogInput.of(run + " custom exporter"));
    ndc.flush();
    waitRows(q, null, run + " custom exporter", 1);

    // OTLP protobuf through a log client, OTLP JSON through send()
    BatchingLogClient otlp =
        c.logClient(
            BetterStackLogClientOptions.builder()
                .protocol(LogProtocol.OTLP)
                .resource(Map.of("service.name", "grow-it-java-otlp"))
                .build());
    otlp.logger("otlp").error(run + " via otlp", Map.of("k", "v"));
    otlp.flush();
    assertEquals(1, otlp.stats().sent(), otlp.stats().toString());
    LogRecord o = waitRows(q, null, run + " via otlp", 1).get(0);
    assertEquals(run + " via otlp", o.body());
    assertEquals(Severity.ERROR, o.severity());
    BetterStackOtlpLogExporter json =
        c.newOtlpExporter(BetterStackOtlpLogExporterOptions.of(OtlpProtocol.JSON));
    assertEquals(
        Optional.empty(),
        json.send(
            List.of(
                LogRecord.builder()
                    .timestamp(Instant.now())
                    .body(run + " otlp json")
                    .traceId("4bf92f3577b34da6a3ce929d0e0e4736")
                    .spanId("00f067aa0ba902b7")
                    .build()),
            true));
    LogRecord oj = waitRows(q, null, run + " otlp json", 1).get(0);
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", oj.traceId().orElseThrow());
    assertTrue(c.otlpExporter().sendAsync(List.of(LogRecord.builder().timestamp(Instant.now()).body(run + " otlp async").build()), false).join().isEmpty());
    waitRows(q, null, run + " otlp async", 1);
  }

  @Test
  void queryAndSearch() throws Exception {
    BetterStackClient c = client();
    BetterStackQuery q = c.query();
    BatchingLogClient lc = c.logClient();
    Instant base = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
    for (int i = 0; i < 5; i++) {
      lc.send(
          LogInput.builder(run + " it's \\ \"q\" #" + (char) ('a' + i))
              .timestamp(base.plusSeconds(i))
              .build());
    }
    lc.flush();

    // query(sql, params): placeholders bound server side (param_<name>, escaped TSV text)
    List<Map<String, Object>> rows =
        q.query(
            "SELECT count() AS c FROM remote("
                + table()
                + "_logs) WHERE position(raw, {needle:String}) > 0 AND dt >= {since:DateTime64(6)}",
            Map.of("needle", run + " it's", "since", base));
    assertEquals(List.of(Map.of("c", "5")), rows);
    // String parameters use ClickHouse's escaped (TSV) format: backslash and quote survive.
    String value = "C:\\tmp\\it's \"" + run + "\"";
    assertEquals(
        List.of(Map.of("v", value)), q.query("SELECT {v:String} AS v", Map.of("v", value)));
    rows =
        q.queryAsync(
                "SELECT JSONExtractString(raw, 'message') AS m FROM remote("
                    + table()
                    + "_logs) WHERE position(raw, {run:String}) > 0 ORDER BY dt ASC LIMIT 1 FORMAT JSON",
                Map.of("run", run),
                QueryOptions.defaults())
            .join();
    assertEquals(run + " it's \\ \"q\" #a", rows.get(0).get("m"));
    String raw =
        q.queryRaw(
            "SELECT count() AS c FROM remote("
                + table()
                + "_logs) WHERE position(raw, {run:String}) > 0 FORMAT JSON",
            Map.of("run", run),
            QueryOptions.defaults().withSetting("output_format_pretty_row_numbers", 0));
    assertTrue(raw.contains("\"c\":\"5\"") || raw.contains("\"c\": \"5\""), raw);
    assertTrue(q.queryRawAsync("SELECT 1 AS one", Map.of(), QueryOptions.defaults()).join().contains("1"));
    assertEquals(1, q.query("SELECT 1 AS one").size());

    // searchLogs: window, order, limit, cursor; contains with a quote and a backslash
    SearchLogsOptions page1 =
        SearchLogsOptions.builder()
            .contains(run + " it's \\ \"q\"")
            .start(base)
            .end(base.plusSeconds(10))
            .limit(2)
            .order(SortOrder.ASC)
            .build();
    LogSearchResult p1 = q.searchLogs(page1);
    assertEquals(2, p1.records().size());
    assertTrue(p1.nextCursor().isPresent());
    assertTrue(p1.records().get(1).body().endsWith("#b"));
    LogSearchResult p2 =
        q.searchLogsAsync(page1.toBuilder().cursor(p1.nextCursor().get()).build()).join();
    assertTrue(p2.records().get(0).body().endsWith("#c"), p2.toString());
    LogSearchResult desc =
        q.searchLogs(SearchLogsOptions.builder().contains(run).start(base.minusSeconds(1)).build());
    assertEquals(5, desc.records().size());
    assertTrue(desc.nextCursor().isEmpty());
    assertTrue(desc.records().get(0).body().endsWith("#e"));
    assertTrue(q.renderSearchSql(SearchLogsOptions.builder().contains(run).build()).contains("s3Cluster(primary, "));

    // LogQueryClient: search + cursor, searchAsync, waitForLog, waitForLogAsync
    LogSearchResult s = q.search(run, SearchOptions.builder().limit(2).build());
    assertEquals(2, s.records().size());
    assertTrue(s.nextCursor().isPresent());
    assertEquals(5, q.searchAsync(run, SearchOptions.builder().limit(10).build()).join().records().size());
    LogRecord hit =
        q.waitForLog(
            run,
            WaitForLogOptions.of(WAIT, Duration.ofMillis(500))
                .withPredicate(rec -> rec.body().endsWith("#c")));
    assertTrue(hit.body().endsWith("#c"));
    assertTrue(q.waitForLogAsync(run, WaitForLogOptions.defaults()).join().body().contains(run));
    assertThrows(
        TimeoutException.class,
        () -> q.waitForLog(run + " never", WaitForLogOptions.of(Duration.ofMillis(1500), Duration.ofMillis(500))));

    assertEquals(List.of(table()), q.listStreams());
    assertEquals(List.of(table()), q.listStreamsAsync().join());
    assertThrows(UnsupportedException.class, q::listLabels);

    // error mapping by ClickHouse exception code
    assertThrows(NotFoundException.class, () -> q.query("SELECT dt FROM remote(t1_does_not_exist_logs)"));
    assertThrows(ValidationException.class, () -> q.query("SELEC nonsense"));
  }

  @Test
  void sourceIsolation() throws Exception {
    String tokenB = env.get("BETTERSTACK_SOURCE_TOKEN_B");
    String tableB = env.get("BETTERSTACK_SOURCE_TABLE_B");
    assumeTrue(tokenB != null && tableB != null, "no second source");
    BetterStackClient b = client(config().sourceToken(tokenB).sourceTable(tableB));
    BatchingLogClient lc = b.logClient();
    lc.send(LogInput.of(run + " source b"));
    lc.flush();
    waitRows(b.query(), tableB, run, 1);
    BetterStackClient a = client();
    assertEquals(0, a.query().searchLogs(SearchLogsOptions.builder().contains(run).build()).records().size());
  }

  @Test
  void tail() throws Exception {
    BetterStackClient c = client();
    List<String> got = new CopyOnWriteArrayList<>();
    TailHandle h =
        c.query()
            .tail(
                run,
                rec -> got.add(rec.body()),
                new TailOptions(Optional.of(Instant.now().minusSeconds(1)), Duration.ofMillis(300), null));
    BatchingLogClient lc = c.logClient();
    lc.send(LogInput.of(run + " tail one"));
    lc.send(LogInput.of(run + " tail two"));
    lc.flush();
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (got.size() < 2 && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    Thread.sleep(700); // more polls must not repeat records
    h.stop();
    assertEquals(2, got.size(), got.toString());
  }

  @Test
  void loggerFacadeAndJulHandler() throws Exception {
    BetterStackClient c = client();
    BatchingLogClient lc = c.logClient();
    lc.logger("facade", Map.of("run", run))
        .child(Map.of("component", "child"))
        .warn(run + " from facade", Map.of("password", "hunter2"));
    java.util.logging.Logger jul = java.util.logging.Logger.getLogger("grow.it." + run);
    jul.setUseParentHandlers(false);
    GrowJulHandler handler = new GrowJulHandler(lc);
    jul.addHandler(handler);
    jul.log(Level.SEVERE, run + " from jul");
    handler.flush();
    lc.flush();
    List<LogRecord> rows = waitRows(c.query(), null, run, 2);
    for (LogRecord r : rows) {
      assertFalse(r.attributes().toString().contains("hunter2"), "password not redacted");
    }
    assertTrue(rows.stream().anyMatch(r -> "child".equals(r.attributes().get("component"))));
    assertTrue(rows.stream().anyMatch(r -> r.severity() == Severity.ERROR));
    jul.removeHandler(handler);
  }

  @Test
  void faults() throws Exception {
    BetterStackClient c = client(config().retry(RetryPolicy.builder().maxRetries(0).build()));
    fault("ingest", 402, "Quota exceeded");
    ExportResult r =
        c.exporter()
            .export(
                List.of(LogRecord.builder().timestamp(Instant.now()).body("quota").build()),
                new ExportOptions(false, Duration.ofSeconds(10)))
            .join();
    BetterStackException e = r.error().orElseThrow();
    assertInstanceOf(QuotaExceededException.class, e);
    assertEquals(ErrorCode.RATE_LIMITED, e.code());
    assertFalse(e.retryable());
    assertEquals(402, e.httpStatus().getAsInt());
    fault("query", 503, "busy");
    assertThrows(UnavailableException.class, () -> c.query().query("SELECT 1"));
  }
}
