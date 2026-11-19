package codes.grow.packages.betterstack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.betterstack.core.AuthenticationException;
import codes.grow.packages.betterstack.core.CancelledException;
import codes.grow.packages.betterstack.core.ConfigException;
import codes.grow.packages.betterstack.core.EnvReader;
import codes.grow.packages.betterstack.core.HealthStatus;
import codes.grow.packages.betterstack.core.Hooks;
import codes.grow.packages.betterstack.core.Logger;
import codes.grow.packages.betterstack.core.RetryPolicy;
import codes.grow.packages.betterstack.core.TlsOptions;
import codes.grow.packages.betterstack.core.UnsupportedException;
import codes.grow.packages.betterstack.core.ValidationException;
import codes.grow.packages.betterstack.logging.BatchOptions;
import codes.grow.packages.betterstack.logging.BatchingLogClient;
import codes.grow.packages.betterstack.logging.LogInput;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BetterStackClientTest {
  private FakeServer ingestServer;
  private FakeServer queryServer;

  @BeforeEach
  void start() {
    ingestServer = FakeServer.start();
    queryServer = FakeServer.start();
  }

  @AfterEach
  void stop() {
    ingestServer.close();
    queryServer.close();
  }

  private BetterStackClient.Builder both() {
    return BetterStackClient.builder()
        .url(ingestServer.url())
        .sourceToken("src-token-123")
        .queryUrl(queryServer.url())
        .queryUsername("user")
        .queryPassword("query-pass-456")
        .sourceTable("t1_app")
        .retry(RetryPolicy.builder().initialBackoff(Duration.ofMillis(1)).build());
  }

  private BetterStackClient ingestOnly() {
    return BetterStackClient.builder()
        .url(ingestServer.url())
        .sourceToken("src-token-123")
        .retry(RetryPolicy.builder().maxRetries(0).build())
        .build();
  }

  @Test
  void healthViaQueryPing() {
    queryServer
        .enqueueFixture("health.query_ping")
        .enqueue(503, "down", "text/plain")
        .enqueue(200, "", null);
    try (var c = both().build()) {
      HealthStatus h = c.health();
      assertThat(h.healthy()).isTrue();
      assertThat(h.status()).isEqualTo("ok");
      assertThat(h.details()).containsEntry("endpoint", "query");
      assertThat(queryServer.last().path()).isEqualTo("/ping");
      assertThat(queryServer.last().header("Authorization")).isNull();
      HealthStatus down = c.healthAsync().join();
      assertThat(down.healthy()).isFalse();
      assertThat(down.status()).isEqualTo("unhealthy");
      assertThat(down.details()).containsEntry("httpStatus", "503");
      assertThat(c.health().status()).isEqualTo("ok");
      assertThat(ingestServer.requests()).isEmpty();
    }
  }

  @Test
  void healthIngestOnly() {
    ingestServer.enqueueFixture("health.ingest_get_root").enqueue(502, "bad", "text/plain");
    try (var c = ingestOnly()) {
      HealthStatus h = c.health();
      assertThat(h.healthy()).isTrue();
      assertThat(h.details())
          .containsEntry("endpoint", "ingest")
          .containsEntry("httpStatus", "405");
      assertThat(ingestServer.last().method()).isEqualTo("GET");
      assertThat(ingestServer.last().header("Authorization")).isNull();
      HealthStatus bad = c.health();
      assertThat(bad.healthy()).isFalse();
      assertThat(bad.status()).isEqualTo("unhealthy");
    }
  }

  @Test
  void core_health_unreachable() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    try (var c =
        BetterStackClient.builder()
            .url("http://127.0.0.1:" + port)
            .sourceToken("t")
            .connectTimeout(Duration.ofSeconds(2))
            .build()) {
      HealthStatus h = c.health();
      assertThat(h.healthy()).isFalse();
      assertThat(h.status()).isEqualTo("unreachable");
      assertThat(h.details()).containsKey("error").containsEntry("endpoint", "ingest");
    }
  }

  @Test
  void pingWithQueryCredentials() {
    queryServer
        .enqueueFixture("ping.fallback_count")
        .enqueue(200, "{\"ok\":1}\n", "application/x-ndjson");
    try (var c = both().build()) {
      assertThat(c.ping()).isPositive();
      FakeServer.Req r = queryServer.last();
      assertThat(r.method()).isEqualTo("POST");
      assertThat(r.text())
          .isEqualTo("SELECT count() AS c FROM remote(t1_app_logs) WHERE 1 = 0 FORMAT JSONEachRow");
      assertThat(r.header("Authorization")).startsWith("Basic ");
      assertThat(r.header("Authorization")).doesNotContain("src-token-123");
    }
    try (var c = both().sourceTable(null).build()) {
      assertThat(c.pingAsync().join()).isPositive();
      assertThat(queryServer.last().text()).isEqualTo("SELECT 1 AS ok FORMAT JSONEachRow");
    }
  }

  @Test
  void pingBadQueryPassword() {
    queryServer.enqueueFixture("query.401_bad_password");
    try (var c = both().build()) {
      assertThatThrownBy(c::ping)
          .isInstanceOf(AuthenticationException.class)
          .hasMessageNotContaining("query-pass-456");
    }
  }

  @Test
  void pingIngestOnlyPostsEmptyBatch() {
    ingestServer.enqueue(202, "", null).enqueueFixture("send.403_bad_token");
    try (var c = ingestOnly()) {
      assertThat(c.ping()).isPositive();
      FakeServer.Req r = ingestServer.last();
      assertThat(r.text()).isEqualTo("[]");
      assertThat(r.header("Authorization")).isEqualTo("Bearer src-token-123");
      assertThat(r.header("Content-Encoding")).isNull();
      assertThatThrownBy(c::ping)
          .isInstanceOf(AuthenticationException.class)
          .hasMessageNotContaining("src-token-123");
    }
  }

  @Test
  void serverVersionUnsupported() {
    try (var c = both().build()) {
      assertThatThrownBy(c::serverVersion).isInstanceOf(UnsupportedException.class);
      assertThatThrownBy(() -> c.serverVersionAsync().join())
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(UnsupportedException.class);
    }
  }

  @Test
  void sidesAreRequired() {
    try (var c = ingestOnly()) {
      assertThatThrownBy(c::query).isInstanceOf(ConfigException.class);
      assertThat(c.exporter()).isNotNull();
      assertThat(c.otlpExporter()).isNotNull();
      assertThat(
              c.newOtlpExporter(BetterStackOtlpLogExporterOptions.of(OtlpProtocol.JSON)).protocol())
          .isEqualTo(OtlpProtocol.JSON);
      assertThatThrownBy(
              () -> c.newExporter(BetterStackLogExporterOptions.defaults().withMaxRequestBytes(-1)))
          .isInstanceOf(ValidationException.class);
    }
    try (var c =
        BetterStackClient.builder()
            .queryUrl(queryServer.url())
            .queryUsername("u")
            .queryPassword("p")
            .build()) {
      assertThat(c.query()).isNotNull();
      assertThatThrownBy(c::exporter).isInstanceOf(ConfigException.class);
      assertThatThrownBy(c::otlpExporter).isInstanceOf(ConfigException.class);
      assertThatThrownBy(c::logClient).isInstanceOf(ConfigException.class);
      assertThatThrownBy(() -> c.newExporter(BetterStackLogExporterOptions.defaults()))
          .isInstanceOf(ConfigException.class);
      assertThatThrownBy(() -> c.newOtlpExporter(BetterStackOtlpLogExporterOptions.defaults()))
          .isInstanceOf(ConfigException.class);
    }
  }

  @Test
  void logClientsAreFlushedOnClose() {
    BetterStackClient c = both().build();
    BatchingLogClient http =
        c.logClient(
            BetterStackLogClientOptions.builder()
                .batchOptions(BatchOptions.builder().maxBatchBytes(64 * 1024 * 1024).build())
                .resource(Map.of("service.name", "svc"))
                .build());
    assertThat(http.processor()).isNotNull();
    BatchingLogClient otlp =
        c.logClient(
            BetterStackLogClientOptions.builder()
                .protocol(LogProtocol.OTLP)
                .otlp(BetterStackOtlpLogExporterOptions.defaults())
                .exporter(BetterStackLogExporterOptions.of(IngestEncoding.NDJSON))
                .redaction(null)
                .build());
    ingestServer.fallback(
        (ex, r) -> FakeServer.send(ex, r.path().equals("/") ? 202 : 200, "", null));
    http.send(LogInput.of("one"));
    otlp.send(LogInput.of("two"));
    c.close();
    c.close();
    assertThat(c.isClosed()).isTrue();
    assertThat(ingestServer.requests())
        .extracting(FakeServer.Req::path)
        .containsExactlyInAnyOrder("/", "/v1/logs");
    assertThatThrownBy(c::logClient).isInstanceOf(CancelledException.class);
    assertThatThrownBy(c::ping).isInstanceOf(CancelledException.class);
    assertThatThrownBy(c::health).isInstanceOf(CancelledException.class);
    AtomicInteger closedCount = new AtomicInteger();
    assertThatThrownBy(() -> c.register(closedCount::incrementAndGet))
        .isInstanceOf(CancelledException.class);
    assertThat(closedCount).hasValue(1);
  }

  @Test
  void core_close_idempotent() {
    BetterStackClient c = both().build();
    c.register(
        () -> {
          throw new IllegalStateException("ignored");
        });
    c.close();
    c.close();
    assertThat(c.isClosed()).isTrue();
    assertThat(c.toString()).contains("BetterStackClient").doesNotContain("query-pass-456");
  }

  @Test
  void core_useragent_and_hooks() {
    List<String> events = new CopyOnWriteArrayList<>();
    Hooks hooks =
        new Hooks() {
          @Override
          public void onRequest(RequestInfo info) {
            events.add("req " + info.headers());
          }
        };
    queryServer.enqueue(200, "", "application/x-ndjson");
    try (var c =
        both()
            .userAgentSuffix("suite/1")
            .header("X-Extra", "1")
            .hooks(hooks)
            .logger(new Logger() {})
            .connectTimeout(Duration.ofSeconds(5))
            .requestTimeout(Duration.ofSeconds(5))
            .totalTimeout(Duration.ofSeconds(10))
            .tls(TlsOptions.defaults())
            .encoding(IngestEncoding.NDJSON)
            .proxy(null)
            .build()) {
      c.ping();
      assertThat(queryServer.last().header("User-Agent"))
          .startsWith("grow-codes-better-stack-logs/")
          .contains("(java)")
          .contains("suite/1");
      assertThat(queryServer.last().header("X-Extra")).isEqualTo("1");
      assertThat(events).hasSize(1);
      assertThat(events.get(0)).doesNotContain("query-pass-456");
      assertThat(c.config().encoding()).isEqualTo(IngestEncoding.NDJSON);
    }
  }

  @Test
  void builderFromEnvAndCreate() {
    Map<String, String> env =
        Map.of(
            EnvReader.DEFAULT_PREFIX + "URL",
            ingestServer.url(),
            "BETTERSTACK_SOURCE_TOKEN",
            "t",
            "BETTERSTACK_QUERY_URL",
            queryServer.url(),
            "BETTERSTACK_QUERY_USERNAME",
            "u",
            "BETTERSTACK_QUERY_PASSWORD",
            "p");
    try (var c =
        BetterStackClient.builder()
            .fromEnv(EnvReader.of(env))
            .sourceToken(codes.grow.packages.betterstack.core.Secret.of("explicit"))
            .queryPassword(codes.grow.packages.betterstack.core.Secret.of("pw"))
            .build()) {
      assertThat(c.config().sourceToken().orElseThrow().reveal()).isEqualTo("explicit");
      assertThat(c.config().hasQuery()).isTrue();
    }
    try (var c = BetterStackClient.create(BetterStackConfig.fromEnv(env))) {
      assertThat(c.config().hasIngest()).isTrue();
    }
    if (System.getenv(EnvReader.DEFAULT_PREFIX + "URL") == null
        && System.getenv("BETTERSTACK_INGESTING_URL") == null) {
      assertThatThrownBy(BetterStackClient::fromEnv).isInstanceOf(ConfigException.class);
    }
    assertThatThrownBy(
            () ->
                BetterStackClient.builder()
                    .url(ingestServer.url())
                    .sourceToken("t")
                    .queryUrl(queryServer.url())
                    .queryUsername("u")
                    .queryPassword("p")
                    .tls(
                        TlsOptions.builder()
                            .caCertPem(
                                "-----BEGIN CERTIFICATE-----\nbad\n-----END CERTIFICATE-----")
                            .build())
                    .build())
        .isInstanceOf(ConfigException.class);
  }
}
