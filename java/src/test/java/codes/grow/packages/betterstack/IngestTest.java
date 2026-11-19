package codes.grow.packages.betterstack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.betterstack.core.AuthenticationException;
import codes.grow.packages.betterstack.core.ErrorCode;
import codes.grow.packages.betterstack.core.Json;
import codes.grow.packages.betterstack.core.RateLimitException;
import codes.grow.packages.betterstack.core.RetryPolicy;
import codes.grow.packages.betterstack.core.UnavailableException;
import codes.grow.packages.betterstack.core.ValidationException;
import codes.grow.packages.betterstack.logging.ExportOptions;
import codes.grow.packages.betterstack.logging.ExportResult;
import codes.grow.packages.betterstack.logging.LogRecord;
import codes.grow.packages.betterstack.logging.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IngestTest {
  private static final Instant TS = Instant.parse("2026-01-02T03:04:05.678901234Z");
  private static final ExportOptions GZIP = new ExportOptions(true, Duration.ofSeconds(10));
  private static final ExportOptions PLAIN = new ExportOptions(false, Duration.ofSeconds(10));

  private FakeServer server;
  private BetterStackClient client;

  @BeforeEach
  void start() {
    server = FakeServer.start();
    client = client(IngestEncoding.JSON, 0);
  }

  @AfterEach
  void stop() {
    client.close();
    server.close();
  }

  private BetterStackClient client(IngestEncoding encoding, int maxRetries) {
    return BetterStackClient.builder()
        .url(server.url())
        .sourceToken("src-token-abc")
        .encoding(encoding)
        .retry(
            RetryPolicy.builder()
                .maxRetries(maxRetries)
                .initialBackoff(Duration.ofMillis(1))
                .build())
        .build();
  }

  private static LogRecord record(String body) {
    return LogRecord.builder()
        .timestamp(TS)
        .observedTimestamp(TS.plusSeconds(1))
        .severity(Severity.WARN)
        .severityText("WARN")
        .body(body)
        .attributes(Map.of("n", 1, "unicode", "Grüße ✓"))
        .resource(Map.of("service.name", "svc"))
        .traceId("4bf92f3577b34da6a3ce929d0e0e4736")
        .spanId("00f067aa0ba902b7")
        .traceFlags(1)
        .build();
  }

  @Test
  void eventMapping() {
    Map<String, Object> ev = BetterStackEvents.toEvent(record("hello"));
    assertThat(ev)
        .containsEntry("dt", TS)
        .containsEntry("message", "hello")
        .containsEntry("level", "warn")
        .containsEntry("severity_number", 13)
        .containsEntry("trace_flags", 1)
        .containsEntry("observed_dt", TS.plusSeconds(1))
        .containsKeys("resource", "attributes");
    assertThat(ev.get("span"))
        .isEqualTo(
            Map.of("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736", "span_id", "00f067aa0ba902b7"));
    Map<String, Object> minimal =
        BetterStackEvents.toEvent(LogRecord.builder().timestamp(TS).body("x").build());
    assertThat(minimal).doesNotContainKeys("span", "resource", "attributes", "observed_dt");
    Map<String, Object> traceOnly =
        BetterStackEvents.toEvent(
            LogRecord.builder().timestamp(TS).traceId("4bf92f3577b34da6a3ce929d0e0e4736").build());
    assertThat(traceOnly.get("span"))
        .isEqualTo(Map.of("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736"));
    assertThat(BetterStackEvents.eventJson(ev))
        .contains("\"dt\":\"2026-01-02T03:04:05.678901234Z\"")
        .contains("Grüße ✓");
    assertThat(BetterStackEvents.formatTimestamp(Instant.parse("2026-01-02T03:04:05Z")))
        .isEqualTo("2026-01-02T03:04:05Z");
  }

  @Test
  void encodings() {
    List<Map<String, Object>> events =
        List.of(Map.of("message", "a"), Map.of("message", "b", "list", List.of(1, TS)));
    assertThat(
            new String(
                BetterStackEvents.encode(events, IngestEncoding.JSON), StandardCharsets.UTF_8))
        .startsWith("[{")
        .endsWith("}]")
        .contains("},{")
        .contains("2026-01-02T03:04:05.678901234Z");
    String nd =
        new String(BetterStackEvents.encode(events, IngestEncoding.NDJSON), StandardCharsets.UTF_8);
    assertThat(nd.split("\n")).hasSize(2);
    assertThat(nd).endsWith("}\n");
    byte[] mp = BetterStackEvents.encode(List.of(Map.of("message", "a")), IngestEncoding.MSGPACK);
    assertThat(HexFormat.of().formatHex(mp)).isEqualTo("9181a76d657373616765a161");
    assertThat(BetterStackEvents.encode(List.of(), IngestEncoding.JSON)).isEqualTo("[]".getBytes());
  }

  @Test
  void messagePack() {
    HexFormat hex = HexFormat.of();
    assertThat(hex.formatHex(MessagePack.encode(null))).isEqualTo("c0");
    assertThat(hex.formatHex(MessagePack.encode(true))).isEqualTo("c3");
    assertThat(hex.formatHex(MessagePack.encode(false))).isEqualTo("c2");
    assertThat(hex.formatHex(MessagePack.encode(5))).isEqualTo("05");
    assertThat(hex.formatHex(MessagePack.encode(200))).isEqualTo("ccc8");
    assertThat(hex.formatHex(MessagePack.encode(300))).isEqualTo("cd012c");
    assertThat(hex.formatHex(MessagePack.encode(70000L))).isEqualTo("ce00011170");
    assertThat(hex.formatHex(MessagePack.encode(5_000_000_000L))).isEqualTo("cf000000012a05f200");
    assertThat(hex.formatHex(MessagePack.encode(-1))).isEqualTo("ff");
    assertThat(hex.formatHex(MessagePack.encode((byte) -100))).isEqualTo("d09c");
    assertThat(hex.formatHex(MessagePack.encode((short) -1000))).isEqualTo("d1fc18");
    assertThat(hex.formatHex(MessagePack.encode(-100000))).isEqualTo("d2fffe7960");
    assertThat(hex.formatHex(MessagePack.encode(-5_000_000_000L))).isEqualTo("d3fffffffed5fa0e00");
    assertThat(hex.formatHex(MessagePack.encode(BigInteger.TEN))).isEqualTo("0a");
    assertThat(hex.formatHex(MessagePack.encode(BigInteger.ONE.shiftLeft(63))))
        .isEqualTo("cf8000000000000000");
    assertThatThrownBy(() -> MessagePack.encode(BigInteger.ONE.shiftLeft(64)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> MessagePack.encode(BigInteger.ONE.shiftLeft(64).negate()))
        .isInstanceOf(ValidationException.class);
    assertThat(hex.formatHex(MessagePack.encode(1.5))).isEqualTo("cb3ff8000000000000");
    assertThat(hex.formatHex(MessagePack.encode(1.5f))).isEqualTo("cb3ff8000000000000");
    assertThat(hex.formatHex(MessagePack.encode(new java.math.BigDecimal("1.25"))))
        .isEqualTo("a4312e3235");
    assertThat(hex.formatHex(MessagePack.encode(new StringBuilder("ab")))).isEqualTo("a26162");
    assertThat(hex.formatHex(MessagePack.encode(new byte[] {1, 2}))).isEqualTo("c4020102");
    assertThat(hex.formatHex(MessagePack.encode(new Object[] {1, "a"}))).isEqualTo("9201a161");
    assertThat(hex.formatHex(MessagePack.encode(Optional.empty())))
        .startsWith("ae"); // toString() text "Optional.empty"
    // lengths: str8, str16, str32 header, array16, map16, bin16
    assertThat(MessagePack.encode("x".repeat(40))[0] & 0xff).isEqualTo(0xd9);
    assertThat(MessagePack.encode("x".repeat(300))[0] & 0xff).isEqualTo(0xda);
    assertThat(MessagePack.encode("x".repeat(70000))[0] & 0xff).isEqualTo(0xdb);
    List<Integer> many = new ArrayList<>();
    Map<String, Integer> big = new LinkedHashMap<>();
    for (int i = 0; i < 20; i++) {
      many.add(i);
      big.put("k" + i, i);
    }
    assertThat(MessagePack.encode(many)[0] & 0xff).isEqualTo(0xdc);
    assertThat(MessagePack.encode(big)[0] & 0xff).isEqualTo(0xde);
    assertThat(MessagePack.encode(new byte[300])[0] & 0xff).isEqualTo(0xc5);
    assertThat(MessagePack.encode(new byte[70000])[0] & 0xff).isEqualTo(0xc6);
    assertThat(MessagePack.arrayHeader(70000)[0] & 0xff).isEqualTo(0xdd);
    // timestamps: 32-bit, 64-bit, 96-bit
    assertThat(hex.formatHex(MessagePack.encode(Instant.ofEpochSecond(1))))
        .isEqualTo("d6ff00000001");
    assertThat(hex.formatHex(MessagePack.encode(Instant.ofEpochSecond(1, 5))))
        .isEqualTo("d7ff0000001400000001");
    assertThat(hex.formatHex(MessagePack.encode(Instant.ofEpochSecond(-1))))
        .isEqualTo("c70cff00000000ffffffffffffffff");
    // depth limit
    Object deep = "leaf";
    for (int i = 0; i < 120; i++) {
      deep = List.of(deep);
    }
    Object tooDeep = deep;
    assertThatThrownBy(() -> MessagePack.encode(tooDeep))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nested");
  }

  @Test
  void exportJsonGzip() {
    server.enqueueFixture("sendBatch.gzip_json");
    ExportResult r = client.exporter().export(List.of(record("a"), record("b")), GZIP).join();
    assertThat(r).isEqualTo(ExportResult.success(2));
    FakeServer.Req req = server.last();
    assertThat(req.method()).isEqualTo("POST");
    assertThat(req.path()).isEqualTo("/");
    assertThat(req.header("Authorization")).isEqualTo("Bearer src-token-abc");
    assertThat(req.header("Content-Type")).isEqualTo("application/json");
    assertThat(req.header("Content-Encoding")).isEqualTo("gzip");
    JsonNode arr = Json.tree(req.decoded());
    assertThat(arr.size()).isEqualTo(2);
    assertThat(arr.get(0).get("dt").asText()).isEqualTo("2026-01-02T03:04:05.678901234Z");
    assertThat(arr.get(1).get("message").asText()).isEqualTo("b");
    assertThat(client.exporter().encoding()).isEqualTo(IngestEncoding.JSON);
    assertThat(client.exporter().maxRequestBytes())
        .isEqualTo(BetterStackLogExporter.MAX_REQUEST_BYTES);
    assertThat(client.exporter().maxRecordBytes())
        .isEqualTo(BetterStackLogExporter.MAX_RECORD_BYTES);
    assertThat(client.exporter().shutdown()).isCompleted();
  }

  @Test
  void exportNdjsonAndMsgpack() {
    try (var nd = client(IngestEncoding.NDJSON, 0);
        var mp = client(IngestEncoding.MSGPACK, 0)) {
      server.enqueueFixture("sendBatch.ndjson").enqueueFixture("sendBatch.msgpack");
      assertThat(nd.exporter().export(List.of(record("a"), record("b")), PLAIN).join().accepted())
          .isEqualTo(2);
      FakeServer.Req r1 = server.last();
      assertThat(r1.header("Content-Type")).isEqualTo("application/x-ndjson");
      assertThat(r1.header("Content-Encoding")).isNull();
      assertThat(r1.text().split("\n")).hasSize(2);
      assertThat(mp.exporter().export(List.of(record("a")), PLAIN).join().accepted()).isEqualTo(1);
      FakeServer.Req r2 = server.last();
      assertThat(r2.header("Content-Type")).isEqualTo("application/msgpack");
      byte[] body = r2.decoded();
      assertThat(body[0] & 0xff).isEqualTo(0x91);
      // the dt timestamp extension (fixext8, type -1) is in the body
      assertThat(HexFormat.of().formatHex(body)).contains("d7ff");
    }
  }

  @Test
  void exportSplitsByRequestLimit() {
    BetterStackLogExporter small =
        client.newExporter(
            BetterStackLogExporterOptions.of(IngestEncoding.JSON).withMaxRequestBytes(700));
    List<LogRecord> recs = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      recs.add(record("message number " + i));
    }
    ExportResult r = small.export(recs, PLAIN).join();
    assertThat(r.accepted()).isEqualTo(6);
    assertThat(server.requests().size()).isGreaterThan(1);
    int total = 0;
    for (FakeServer.Req q : server.requests()) {
      assertThat(q.body().length).isLessThanOrEqualTo(700);
      total += Json.tree(q.body()).size();
    }
    assertThat(total).isEqualTo(6);
    assertThat(BetterStackLogExporter.split(List.of(), 10)).isEmpty();
  }

  @Test
  void oversizedRecordsAreRejected() {
    BetterStackLogExporter e =
        client.newExporter(BetterStackLogExporterOptions.defaults().withMaxRecordBytes(400));
    ExportResult r = e.export(List.of(record("ok"), record("x".repeat(1000))), PLAIN).join();
    assertThat(r.accepted()).isEqualTo(1);
    assertThat(r.rejected()).isEqualTo(1);
    assertThat(r.error()).containsInstanceOf(ValidationException.class);
    assertThat(r.error().orElseThrow().getMessage()).contains("1 record(s) dropped");
    ExportResult none = e.export(List.of(record("y".repeat(1000))), PLAIN).join();
    assertThat(none.accepted()).isZero();
    assertThat(server.requests()).hasSize(1);
    assertThatThrownBy(
            () ->
                client.newExporter(
                    new BetterStackLogExporterOptions(
                        Optional.empty(), BetterStackLogExporter.MAX_REQUEST_BYTES + 1, 0)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                client.newExporter(BetterStackLogExporterOptions.defaults().withMaxRecordBytes(-5)))
        .isInstanceOf(ValidationException.class);
    assertThat(new BetterStackLogExporterOptions(null, 0, 0).encoding()).isEmpty();
  }

  @Test
  void ingestErrorMapping() {
    server
        .enqueueFixture("send.403_bad_token")
        .enqueueFixture("send.402_fault")
        .enqueueFixture("send.406_bad_json")
        .enqueueFixture("send.413_too_large");
    BetterStackLogExporter e = client.exporter();
    ExportResult auth = e.export(List.of(record("a")), PLAIN).join();
    assertThat(auth.accepted()).isZero();
    assertThat(auth.rejected()).isEqualTo(1);
    assertThat(auth.error().orElseThrow())
        .isInstanceOf(AuthenticationException.class)
        .hasMessageNotContaining("src-token-abc");
    assertThat(auth.error().orElseThrow().httpStatus()).hasValue(403);
    var quota = e.export(List.of(record("a")), PLAIN).join().error().orElseThrow();
    assertThat(quota)
        .isInstanceOf(RateLimitException.class)
        .isInstanceOf(QuotaExceededException.class);
    assertThat(quota.code()).isEqualTo(ErrorCode.RATE_LIMITED);
    assertThat(quota.retryable()).isFalse();
    assertThat(quota.httpStatus()).hasValue(402);
    assertThat(e.export(List.of(record("a")), PLAIN).join().error().orElseThrow())
        .isInstanceOf(ValidationException.class);
    assertThat(e.export(List.of(record("a")), PLAIN).join().error().orElseThrow())
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("413");
  }

  @Test
  void retriesThenPartialFailure() {
    try (var retrying = client(IngestEncoding.JSON, 2)) {
      server.enqueueFixture("send.429_fault").enqueueFixture("send.503_fault");
      assertThat(retrying.exporter().export(List.of(record("a")), PLAIN).join().accepted())
          .isEqualTo(1);
      assertThat(server.requests()).hasSize(3);
    }
    BetterStackLogExporter small =
        client.newExporter(BetterStackLogExporterOptions.defaults().withMaxRequestBytes(500));
    server.enqueue(202, "", null).enqueueFixture("send.503_fault");
    ExportResult r = small.export(List.of(record("a"), record("b"), record("c")), PLAIN).join();
    assertThat(r.accepted()).isEqualTo(1);
    assertThat(r.rejected()).isEqualTo(2);
    assertThat(r.error().orElseThrow()).isInstanceOf(UnavailableException.class);
  }

  @Test
  void sendEvents() {
    server
        .enqueueFixture("send.json_single")
        .enqueue(202, "", null)
        .enqueueFixture("send.402_fault");
    BetterStackLogExporter e = client.exporter();
    e.sendEvents(List.of(Map.of("message", "raw", "dt", TS, "custom", Map.of("k", "v"))));
    FakeServer.Req r = server.last();
    assertThat(r.header("Content-Encoding")).isEqualTo("gzip");
    assertThat(Json.tree(r.decoded()).get(0).get("custom").get("k").asText()).isEqualTo("v");
    e.sendEvents(
        List.of(Map.of("message", "mp")),
        SendEventsOptions.defaults().withEncoding(IngestEncoding.MSGPACK).withGzip(false));
    assertThat(server.last().header("Content-Type")).isEqualTo("application/msgpack");
    assertThatThrownBy(() -> e.sendEvents(List.of(Map.of("message", "q"))))
        .isInstanceOf(QuotaExceededException.class);
    assertThatThrownBy(
            () ->
                e.sendEventsAsync(
                        List.of(Map.of("deep", deepList())),
                        SendEventsOptions.defaults().withEncoding(IngestEncoding.MSGPACK))
                    .join())
        .hasCauseInstanceOf(ValidationException.class);
    BetterStackLogExporter tiny =
        client.newExporter(BetterStackLogExporterOptions.defaults().withMaxRequestBytes(10));
    assertThatThrownBy(() -> tiny.sendEvents(List.of(Map.of("message", "too long for ten"))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("exceeds");
    assertThatThrownBy(
            () ->
                e.sendEventsAsync(
                        List.of(Map.of("bad", new Object() {})), SendEventsOptions.defaults())
                    .join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(ValidationException.class);
    assertThat(new SendEventsOptions(null, true).encoding()).isEmpty();
  }

  @Test
  void unencodableRecordIsDropped() {
    try (var mp = client(IngestEncoding.MSGPACK, 0)) {
      LogRecord bad =
          LogRecord.builder().timestamp(TS).body("x").attribute("big", deepList()).build();
      ExportResult r = mp.exporter().export(List.of(bad), PLAIN).join();
      assertThat(r.rejected()).isEqualTo(1);
      assertThat(r.error().orElseThrow()).isInstanceOf(ValidationException.class);
    }
  }

  @Test
  void quotaExceptionShape() {
    QuotaExceededException q = new QuotaExceededException("m", 402, "rid", null);
    assertThat(q.retryable()).isFalse();
    assertThat(q.requestId()).contains("rid");
    ServerResponseException s = new ServerResponseException("bad", 200, null);
    assertThat(s.retryable()).isFalse();
    assertThat(s.code()).isEqualTo(ErrorCode.UNAVAILABLE);
    assertThat(Arrays.asList(IngestEncoding.values())).hasSize(3);
  }

  private static Object deepList() {
    Object deep = "leaf";
    for (int i = 0; i < 120; i++) {
      deep = List.of(deep);
    }
    return deep;
  }
}
