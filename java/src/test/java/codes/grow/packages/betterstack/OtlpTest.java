package codes.grow.packages.betterstack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.betterstack.core.AuthenticationException;
import codes.grow.packages.betterstack.core.Json;
import codes.grow.packages.betterstack.core.RetryPolicy;
import codes.grow.packages.betterstack.core.SdkInfo;
import codes.grow.packages.betterstack.core.ValidationException;
import codes.grow.packages.betterstack.logging.ExportOptions;
import codes.grow.packages.betterstack.logging.ExportResult;
import codes.grow.packages.betterstack.logging.LogRecord;
import codes.grow.packages.betterstack.logging.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OtlpTest {
  private static final Instant TS = Instant.parse("2026-01-02T03:04:05.000000123Z");
  private static final ExportOptions PLAIN = new ExportOptions(false, Duration.ofSeconds(10));

  private FakeServer server;
  private BetterStackClient client;

  @BeforeEach
  void start() {
    server = FakeServer.start();
    client =
        BetterStackClient.builder()
            .url(server.url())
            .sourceToken("otlp-token")
            .retry(RetryPolicy.builder().maxRetries(0).build())
            .build();
    server.fallback((ex, r) -> FakeServer.send(ex, 200, "", "application/x-protobuf"));
  }

  @AfterEach
  void stop() {
    client.close();
    server.close();
  }

  /** Decoded protobuf field: number, wire type, varint/fixed value or bytes. */
  record F(int field, int wire, long num, byte[] bytes) {
    String str() {
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  static List<F> fields(byte[] b) {
    List<F> out = new ArrayList<>();
    int[] p = {0};
    while (p[0] < b.length) {
      long key = varint(b, p);
      int field = (int) (key >>> 3);
      int wire = (int) (key & 7);
      switch (wire) {
        case 0:
          out.add(new F(field, wire, varint(b, p), null));
          break;
        case 1:
          long v = 0;
          for (int i = 0; i < 8; i++) {
            v |= (long) (b[p[0]++] & 0xff) << (8 * i);
          }
          out.add(new F(field, wire, v, null));
          break;
        case 5:
          long w = 0;
          for (int i = 0; i < 4; i++) {
            w |= (long) (b[p[0]++] & 0xff) << (8 * i);
          }
          out.add(new F(field, wire, w, null));
          break;
        default:
          int len = (int) varint(b, p);
          byte[] bytes = java.util.Arrays.copyOfRange(b, p[0], p[0] + len);
          p[0] += len;
          out.add(new F(field, wire, 0, bytes));
      }
    }
    return out;
  }

  static long varint(byte[] b, int[] p) {
    long value = 0;
    for (int shift = 0; ; shift += 7) {
      byte x = b[p[0]++];
      value |= (long) (x & 0x7f) << shift;
      if ((x & 0x80) == 0) {
        return value;
      }
    }
  }

  static F one(List<F> fs, int field) {
    return fs.stream().filter(f -> f.field() == field).findFirst().orElseThrow();
  }

  private static LogRecord record(String body, String service) {
    return LogRecord.builder()
        .timestamp(TS)
        .observedTimestamp(TS)
        .severity(Severity.ERROR)
        .severityText("ERROR")
        .body(body)
        .attributes(
            Map.of(
                "s",
                "v",
                "b",
                true,
                "i",
                7L,
                "d",
                1.5,
                "l",
                List.of("x", 2L),
                "m",
                Map.of("k", "v")))
        .resource(Map.of("service.name", service))
        .traceId("4bf92f3577b34da6a3ce929d0e0e4736")
        .spanId("00f067aa0ba902b7")
        .traceFlags(1)
        .build();
  }

  @Test
  void protobufStructure() {
    byte[] body =
        OtlpLogs.encodeProtobuf(
            List.of(record("a", "one"), record("b", "two"), record("c", "one")), "scope-x");
    List<F> rls = fields(body);
    assertThat(rls).hasSize(2);
    List<F> rl = fields(rls.get(0).bytes());
    List<F> resource = fields(one(rl, 1).bytes());
    List<F> kv = fields(resource.get(0).bytes());
    assertThat(one(kv, 1).str()).isEqualTo("service.name");
    assertThat(one(fields(one(kv, 2).bytes()), 1).str()).isEqualTo("one");
    List<F> sl = fields(one(rl, 2).bytes());
    List<F> scope = fields(one(sl, 1).bytes());
    assertThat(one(scope, 1).str()).isEqualTo("scope-x");
    assertThat(one(scope, 2).str()).isEqualTo(SdkInfo.VERSION);
    assertThat(sl.stream().filter(f -> f.field() == 2)).hasSize(2);
    List<F> lr = fields(one(sl, 2).bytes());
    assertThat(one(lr, 1).num()).isEqualTo(TS.getEpochSecond() * 1_000_000_000L + 123);
    assertThat(one(lr, 2).num()).isEqualTo(17);
    assertThat(one(lr, 3).str()).isEqualTo("ERROR");
    assertThat(one(fields(one(lr, 5).bytes()), 1).str()).isEqualTo("a");
    assertThat(lr.stream().filter(f -> f.field() == 6)).hasSize(6);
    assertThat(one(lr, 8).num()).isEqualTo(1);
    assertThat(HexFormat.of().formatHex(one(lr, 9).bytes()))
        .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(HexFormat.of().formatHex(one(lr, 10).bytes())).isEqualTo("00f067aa0ba902b7");
    assertThat(one(lr, 11).num()).isEqualTo(one(lr, 1).num());
    // attribute value kinds
    StringBuilder kinds = new StringBuilder();
    for (F a : lr) {
      if (a.field() == 6) {
        List<F> akv = fields(a.bytes());
        kinds
            .append(one(akv, 1).str())
            .append('=')
            .append(fields(one(akv, 2).bytes()).get(0).field())
            .append(' ');
      }
    }
    assertThat(kinds.toString()).contains("s=1", "b=2", "i=3", "d=4", "l=5", "m=6");
    // minimal record: no trace, no observed
    List<F> minimal =
        fields(OtlpLogs.encodeLogRecord(LogRecord.builder().timestamp(TS).body("x").build()));
    assertThat(minimal).extracting(F::field).containsExactly(1, 2, 3, 5);
  }

  @Test
  void jsonRequest() {
    Map<String, Object> req = OtlpLogs.jsonRequest(List.of(record("a", "one")), "s");
    JsonNode n = Json.tree(Json.encode(req));
    JsonNode lr = n.at("/resourceLogs/0/scopeLogs/0/logRecords/0");
    assertThat(lr.get("timeUnixNano").asText()).endsWith("000000123");
    assertThat(lr.get("observedTimeUnixNano").isTextual()).isTrue();
    assertThat(lr.get("severityNumber").asInt()).isEqualTo(17);
    assertThat(lr.at("/body/stringValue").asText()).isEqualTo("a");
    assertThat(lr.get("traceId").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(lr.get("flags").asInt()).isEqualTo(1);
    assertThat(n.at("/resourceLogs/0/scopeLogs/0/scope/name").asText()).isEqualTo("s");
    assertThat(n.toString())
        .contains("boolValue", "intValue", "doubleValue", "arrayValue", "kvlistValue");
    JsonNode bare =
        Json.tree(
            Json.encode(
                OtlpLogs.jsonRequest(
                    List.of(LogRecord.builder().timestamp(TS).body("x").build()), "s")));
    assertThat(bare.at("/resourceLogs/0/scopeLogs/0/logRecords/0").has("traceId")).isFalse();
  }

  static byte[] partialResponse(long rejected, String message) {
    ByteArrayOutputStream ps = new ByteArrayOutputStream();
    if (rejected > 0) {
      ps.write(0x08);
      ps.write((int) rejected);
    }
    if (message != null) {
      byte[] m = message.getBytes(StandardCharsets.UTF_8);
      ps.write(0x12);
      ps.write(m.length);
      ps.writeBytes(m);
    }
    byte[] inner = ps.toByteArray();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0x0a);
    out.write(inner.length);
    out.writeBytes(inner);
    return out.toByteArray();
  }

  @Test
  void partialSuccessParsing() {
    assertThat(OtlpLogs.parsePartialSuccess(new byte[0])).isEmpty();
    assertThat(OtlpLogs.parsePartialSuccess(partialResponse(2, "bad")))
        .contains(new OtlpLogs.PartialSuccess(2, "bad"));
    assertThat(OtlpLogs.parsePartialSuccess(partialResponse(0, "fine"))).isEmpty();
    // unknown fields of every wire type are skipped
    byte[] withExtras = {
      0x10,
      0x01, // field 2 varint
      0x19,
      1,
      2,
      3,
      4,
      5,
      6,
      7,
      8, // field 3 fixed64
      0x25,
      1,
      2,
      3,
      4, // field 4 fixed32
      0x0a,
      0x0d,
      0x10,
      0x05,
      0x19,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0x08,
      0x03 // partial with extras
    };
    assertThat(OtlpLogs.parsePartialSuccess(withExtras))
        .contains(new OtlpLogs.PartialSuccess(3, ""));
    assertThat(OtlpLogs.parsePartialSuccess(new byte[] {0x0a, 0x10, 0x08})).isEmpty();
    assertThat(OtlpLogs.parsePartialSuccess(new byte[] {(byte) 0x80})).isEmpty();
    assertThat(OtlpLogs.parsePartialSuccess(new byte[] {0x0b})).isEmpty();
    assertThat(OtlpLogs.parsePartialSuccess(new byte[] {0x19, 1})).isEmpty();
    byte[] longVarint = new byte[11];
    java.util.Arrays.fill(longVarint, (byte) 0xff);
    assertThat(OtlpLogs.parsePartialSuccess(longVarint)).isEmpty();
    assertThat(OtlpLogs.parsePartialSuccessJson("".getBytes())).isEmpty();
    assertThat(OtlpLogs.parsePartialSuccessJson("{}".getBytes())).isEmpty();
    assertThat(OtlpLogs.parsePartialSuccessJson("not json".getBytes())).isEmpty();
    assertThat(
            OtlpLogs.parsePartialSuccessJson(
                "{\"partialSuccess\":{\"rejectedLogRecords\":\"4\",\"errorMessage\":\"x\"}}"
                    .getBytes()))
        .contains(new OtlpLogs.PartialSuccess(4, "x"));
  }

  @Test
  void exportProtobufWithPartialSuccess() {
    server
        .enqueue((ex, r) -> FakeServer.sendBytes(ex, 200, new byte[0], "application/x-protobuf"))
        .enqueue(
            (ex, r) ->
                FakeServer.sendBytes(
                    ex, 200, partialResponse(1, "dropped"), "application/x-protobuf"));
    BetterStackOtlpLogExporter e = client.otlpExporter();
    assertThat(e.protocol()).isEqualTo(OtlpProtocol.PROTOBUF);
    assertThat(e.scopeName()).isEqualTo(OtlpLogs.DEFAULT_SCOPE_NAME);
    ExportResult ok =
        e.export(List.of(record("a", "svc")), new ExportOptions(true, Duration.ofSeconds(5)))
            .join();
    assertThat(ok).isEqualTo(ExportResult.success(1));
    FakeServer.Req r = server.last();
    assertThat(r.path()).isEqualTo("/v1/logs");
    assertThat(r.header("Content-Type")).isEqualTo("application/x-protobuf");
    assertThat(r.header("Accept")).isEqualTo("application/x-protobuf");
    assertThat(r.header("Content-Encoding")).isEqualTo("gzip");
    assertThat(r.header("Authorization")).isEqualTo("Bearer otlp-token");
    assertThat(fields(r.decoded())).hasSize(1);
    ExportResult partial = e.export(List.of(record("a", "svc"), record("b", "svc")), PLAIN).join();
    assertThat(partial.accepted()).isEqualTo(1);
    assertThat(partial.rejected()).isEqualTo(1);
    assertThat(partial.error().orElseThrow().getMessage()).contains("rejected 1", "dropped");
    assertThat(e.shutdown()).isCompleted();
    assertThat(e.export(List.of(), PLAIN).join()).isEqualTo(ExportResult.success(0));
  }

  @Test
  void exportJsonSplitAndErrors() {
    BetterStackOtlpLogExporter e =
        client.newOtlpExporter(
            new BetterStackOtlpLogExporterOptions(OtlpProtocol.JSON, "js", 1200));
    server
        .enqueue(200, "{}", "application/json")
        .enqueue(200, "{\"partialSuccess\":{\"rejectedLogRecords\":1}}", "application/json")
        .enqueueFixture("send.403_bad_token");
    List<LogRecord> recs =
        List.of(record("a", "s"), record("b", "s"), record("c".repeat(2000), "s"));
    ExportResult r = e.export(recs, PLAIN).join();
    assertThat(server.requests()).hasSize(2);
    assertThat(server.last().header("Content-Type")).isEqualTo("application/json");
    assertThat(r.accepted()).isEqualTo(1);
    assertThat(r.rejected()).isEqualTo(2);
    assertThat(r.error().orElseThrow().getMessage()).contains("rejected 1");
    ExportResult failed = e.export(List.of(record("x", "s"), record("y", "s")), PLAIN).join();
    assertThat(failed.accepted()).isZero();
    assertThat(failed.rejected()).isEqualTo(2);
    assertThat(failed.error().orElseThrow()).isInstanceOf(AuthenticationException.class);
    ExportResult tooBig = e.export(List.of(record("z".repeat(5000), "s")), PLAIN).join();
    assertThat(tooBig.error().orElseThrow()).isInstanceOf(ValidationException.class);
    assertThat(tooBig.rejected()).isEqualTo(1);
    assertThatThrownBy(
            () -> client.newOtlpExporter(new BetterStackOtlpLogExporterOptions(null, null, -1)))
        .isInstanceOf(ValidationException.class);
    assertThat(new BetterStackOtlpLogExporterOptions(null, "", 0).scopeName())
        .isEqualTo(OtlpLogs.DEFAULT_SCOPE_NAME);
  }

  @Test
  void sendSyncAndEncodeErrors() {
    server.enqueueFixture("otlpExport.v1_logs");
    BetterStackOtlpLogExporter e = client.otlpExporter();
    assertThatThrownBy(() -> e.send(List.of(record("a", "s")), false)).hasMessageContaining("404");
    server.enqueue(
        (ex, r) ->
            FakeServer.sendBytes(ex, 200, partialResponse(1, null), "application/x-protobuf"));
    assertThat(e.send(List.of(record("a", "s")), false))
        .contains(new OtlpLogs.PartialSuccess(1, ""));
    LogRecord far = LogRecord.builder().timestamp(Instant.MAX).body("x").build();
    assertThatThrownBy(() -> e.send(List.of(far), false)).isInstanceOf(ValidationException.class);
    ExportResult r = e.export(List.of(far), PLAIN).join();
    assertThat(r.rejected()).isEqualTo(1);
  }
}
