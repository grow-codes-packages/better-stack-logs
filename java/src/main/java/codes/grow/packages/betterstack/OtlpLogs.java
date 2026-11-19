package codes.grow.packages.betterstack;

import codes.grow.packages.betterstack.core.Json;
import codes.grow.packages.betterstack.core.SdkInfo;
import codes.grow.packages.betterstack.logging.LogRecord;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * OTLP logs encoding without dependencies: {@code ExportLogsServiceRequest} as protobuf (field
 * numbers of opentelemetry-proto {@code collector/logs/v1} and {@code logs/v1}) or as OTLP/JSON,
 * and {@code partial_success} parsing of the response.
 */
public final class OtlpLogs {
  /** Instrumentation scope name of exported records. */
  public static final String DEFAULT_SCOPE_NAME = "grow-codes-better-stack-logs";

  private OtlpLogs() {}

  /**
   * Partial success reported by an OTLP response.
   *
   * @param rejected number of rejected log records
   * @param message the service's error message, may be empty
   */
  public record PartialSuccess(long rejected, String message) {}

  /** Minimal protobuf writer. */
  private static final class Pb {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    void varint(long v) {
      long x = v;
      while ((x & ~0x7fL) != 0) {
        out.write((int) ((x & 0x7f) | 0x80));
        x >>>= 7;
      }
      out.write((int) x);
    }

    void tag(int field, int wire) {
      varint(((long) field << 3) | wire);
    }

    void uint(int field, long v) {
      tag(field, 0);
      varint(v);
    }

    void fixed64(int field, long v) {
      tag(field, 1);
      for (int i = 0; i < 8; i++) {
        out.write((int) (v >>> (8 * i)) & 0xff);
      }
    }

    void fixed32(int field, int v) {
      tag(field, 5);
      for (int i = 0; i < 4; i++) {
        out.write((v >>> (8 * i)) & 0xff);
      }
    }

    void bytes(int field, byte[] b) {
      tag(field, 2);
      varint(b.length);
      out.writeBytes(b);
    }

    void string(int field, String s) {
      bytes(field, s.getBytes(StandardCharsets.UTF_8));
    }

    void message(int field, Pb m) {
      bytes(field, m.toByteArray());
    }

    byte[] toByteArray() {
      return out.toByteArray();
    }
  }

  private static long nanos(Instant t) {
    return Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000_000L), t.getNano());
  }

  private static Pb anyValue(Object v) {
    Pb m = new Pb();
    if (v instanceof Boolean) {
      m.uint(2, (Boolean) v ? 1 : 0);
    } else if (v instanceof Long
        || v instanceof Integer
        || v instanceof Short
        || v instanceof Byte) {
      m.uint(3, ((Number) v).longValue());
    } else if (v instanceof Double || v instanceof Float) {
      m.fixed64(4, Double.doubleToLongBits(((Number) v).doubleValue()));
    } else if (v instanceof List) {
      Pb arr = new Pb();
      for (Object item : (List<?>) v) {
        arr.message(1, anyValue(item));
      }
      m.message(5, arr);
    } else if (v instanceof Map) {
      Pb list = new Pb();
      for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
        list.message(1, keyValue(String.valueOf(e.getKey()), e.getValue()));
      }
      m.message(6, list);
    } else {
      m.string(1, String.valueOf(v));
    }
    return m;
  }

  private static Pb keyValue(String key, Object value) {
    Pb kv = new Pb();
    kv.string(1, key);
    kv.message(2, anyValue(value));
    return kv;
  }

  /**
   * Encodes one OTLP {@code LogRecord} message.
   *
   * @param r record
   * @return protobuf bytes
   */
  public static byte[] encodeLogRecord(LogRecord r) {
    Pb m = new Pb();
    m.fixed64(1, nanos(r.timestamp()));
    m.uint(2, r.severity().number());
    m.string(3, r.severityText());
    m.message(5, anyValue(r.body()));
    for (Map.Entry<String, Object> e : r.attributes().entrySet()) {
      m.message(6, keyValue(e.getKey(), e.getValue()));
    }
    r.traceFlags().ifPresent(f -> m.fixed32(8, f));
    r.traceId().ifPresent(t -> m.bytes(9, HexFormat.of().parseHex(t)));
    r.spanId().ifPresent(s -> m.bytes(10, HexFormat.of().parseHex(s)));
    r.observedTimestamp().ifPresent(t -> m.fixed64(11, nanos(t)));
    return m.toByteArray();
  }

  /** Records grouped by resource, in first-seen order. */
  private static Map<String, List<LogRecord>> byResource(List<LogRecord> records) {
    Map<String, List<LogRecord>> groups = new LinkedHashMap<>();
    for (LogRecord r : records) {
      groups.computeIfAbsent(new TreeMap<>(r.resource()).toString(), k -> new ArrayList<>()).add(r);
    }
    return groups;
  }

  /**
   * Encodes an {@code ExportLogsServiceRequest} as protobuf: one {@code ResourceLogs} per distinct
   * resource with one {@code ScopeLogs} ({@code scopeName}, SDK version).
   *
   * @param records records
   * @param scopeName instrumentation scope name
   * @return protobuf bytes
   */
  public static byte[] encodeProtobuf(List<LogRecord> records, String scopeName) {
    Pb req = new Pb();
    for (List<LogRecord> group : byResource(records).values()) {
      Pb rl = new Pb();
      Pb res = new Pb();
      for (Map.Entry<String, Object> e : group.get(0).resource().entrySet()) {
        res.message(1, keyValue(e.getKey(), e.getValue()));
      }
      rl.message(1, res);
      Pb sl = new Pb();
      Pb scope = new Pb();
      scope.string(1, scopeName);
      scope.string(2, SdkInfo.VERSION);
      sl.message(1, scope);
      for (LogRecord r : group) {
        sl.bytes(2, encodeLogRecord(r));
      }
      rl.message(2, sl);
      req.message(1, rl);
    }
    return req.toByteArray();
  }

  private static Object jsonAny(Object v) {
    if (v instanceof Boolean) {
      return Map.of("boolValue", v);
    }
    if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
      return Map.of("intValue", v.toString());
    }
    if (v instanceof Double || v instanceof Float) {
      return Map.of("doubleValue", v);
    }
    if (v instanceof List) {
      List<Object> values = new ArrayList<>();
      for (Object item : (List<?>) v) {
        values.add(jsonAny(item));
      }
      return Map.of("arrayValue", Map.of("values", values));
    }
    if (v instanceof Map) {
      return Map.of("kvlistValue", Map.of("values", jsonKeyValues((Map<?, ?>) v)));
    }
    return Map.of("stringValue", String.valueOf(v));
  }

  private static List<Object> jsonKeyValues(Map<?, ?> attrs) {
    List<Object> out = new ArrayList<>();
    for (Map.Entry<?, ?> e : attrs.entrySet()) {
      Map<String, Object> kv = new LinkedHashMap<>();
      kv.put("key", String.valueOf(e.getKey()));
      kv.put("value", jsonAny(e.getValue()));
      out.add(kv);
    }
    return out;
  }

  /**
   * Builds the OTLP/JSON {@code ExportLogsServiceRequest} object.
   *
   * @param records records
   * @param scopeName instrumentation scope name
   * @return the request as nested maps and lists
   */
  public static Map<String, Object> jsonRequest(List<LogRecord> records, String scopeName) {
    List<Object> rls = new ArrayList<>();
    for (List<LogRecord> group : byResource(records).values()) {
      List<Object> logRecords = new ArrayList<>();
      for (LogRecord r : group) {
        Map<String, Object> lr = new LinkedHashMap<>();
        lr.put("timeUnixNano", Long.toString(nanos(r.timestamp())));
        r.observedTimestamp()
            .ifPresent(t -> lr.put("observedTimeUnixNano", Long.toString(nanos(t))));
        lr.put("severityNumber", r.severity().number());
        lr.put("severityText", r.severityText());
        lr.put("body", jsonAny(r.body()));
        lr.put("attributes", jsonKeyValues(r.attributes()));
        r.traceFlags().ifPresent(f -> lr.put("flags", f));
        r.traceId().ifPresent(t -> lr.put("traceId", t));
        r.spanId().ifPresent(s -> lr.put("spanId", s));
        logRecords.add(lr);
      }
      Map<String, Object> scope = new LinkedHashMap<>();
      scope.put("scope", Map.of("name", scopeName, "version", SdkInfo.VERSION));
      scope.put("logRecords", logRecords);
      Map<String, Object> rl = new LinkedHashMap<>();
      rl.put("resource", Map.of("attributes", jsonKeyValues(group.get(0).resource())));
      rl.put("scopeLogs", List.of(scope));
      rls.add(rl);
    }
    return Map.of("resourceLogs", rls);
  }

  /**
   * Reads {@code partial_success} from a protobuf {@code ExportLogsServiceResponse}.
   *
   * @param body response bytes
   * @return the partial success when records were rejected; empty otherwise or when unreadable
   */
  public static Optional<PartialSuccess> parsePartialSuccess(byte[] body) {
    try {
      int[] pos = {0};
      while (pos[0] < body.length) {
        long key = readVarint(body, pos);
        int field = (int) (key >>> 3);
        int wire = (int) (key & 7);
        byte[] value = skip(body, pos, wire);
        if (field != 1 || wire != 2) {
          continue;
        }
        long rejected = 0;
        String message = "";
        int[] p = {0};
        while (p[0] < value.length) {
          long k = readVarint(value, p);
          int f = (int) (k >>> 3);
          int w = (int) (k & 7);
          if (f == 1 && w == 0) {
            rejected = readVarint(value, p);
          } else {
            byte[] v = skip(value, p, w);
            if (f == 2 && w == 2) {
              message = new String(v, StandardCharsets.UTF_8);
            }
          }
        }
        if (rejected > 0) {
          return Optional.of(new PartialSuccess(rejected, message));
        }
      }
      return Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /**
   * Reads {@code partialSuccess} from an OTLP/JSON response body.
   *
   * @param body response bytes (may be empty)
   * @return the partial success when records were rejected; empty otherwise or when unreadable
   */
  public static Optional<PartialSuccess> parsePartialSuccessJson(byte[] body) {
    if (new String(body, StandardCharsets.UTF_8).isBlank()) {
      return Optional.empty();
    }
    try {
      JsonNode ps = Json.tree(body).path("partialSuccess");
      long rejected = ps.path("rejectedLogRecords").asLong(0);
      if (rejected <= 0) {
        return Optional.empty();
      }
      return Optional.of(new PartialSuccess(rejected, ps.path("errorMessage").asText("")));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static long readVarint(byte[] b, int[] pos) {
    long value = 0;
    for (int shift = 0; shift < 64; shift += 7) {
      if (pos[0] >= b.length) {
        throw new IllegalArgumentException("truncated varint");
      }
      byte x = b[pos[0]++];
      value |= (long) (x & 0x7f) << shift;
      if ((x & 0x80) == 0) {
        return value;
      }
    }
    throw new IllegalArgumentException("varint too long");
  }

  /** Skips one field value; returns the bytes of a length-delimited value (else empty). */
  private static byte[] skip(byte[] b, int[] pos, int wire) {
    switch (wire) {
      case 0:
        readVarint(b, pos);
        return new byte[0];
      case 1:
        return advance(b, pos, 8);
      case 2:
        long len = readVarint(b, pos);
        if (len < 0 || len > b.length - pos[0]) {
          throw new IllegalArgumentException("truncated bytes");
        }
        return advance(b, pos, (int) len);
      case 5:
        return advance(b, pos, 4);
      default:
        throw new IllegalArgumentException("unsupported wire type " + wire);
    }
  }

  private static byte[] advance(byte[] b, int[] pos, int n) {
    if (n > b.length - pos[0]) {
      throw new IllegalArgumentException("truncated field");
    }
    byte[] out = java.util.Arrays.copyOfRange(b, pos[0], pos[0] + n);
    pos[0] += n;
    return out;
  }
}
