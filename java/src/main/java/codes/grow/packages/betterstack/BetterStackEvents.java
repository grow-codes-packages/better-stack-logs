package codes.grow.packages.betterstack;

import codes.grow.packages.betterstack.core.Json;
import codes.grow.packages.betterstack.logging.LogRecord;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps {@link LogRecord}s to Better Stack events and encodes request bodies (JSON, NDJSON,
 * MessagePack).
 */
public final class BetterStackEvents {
  private BetterStackEvents() {}

  /**
   * Maps a record to the Better Stack event shape (the JSON stored in the {@code raw} column):
   * {@code {dt, message, level, severity_number, span: {trace_id, span_id}, trace_flags, resource,
   * attributes, observed_dt}}. {@code span.trace_id}/{@code span.span_id} are Better Stack's log to
   * trace correlation fields. {@code dt} and {@code observed_dt} are {@link Instant}s until
   * encoded; empty {@code resource}/{@code attributes} are left out.
   *
   * @param record record
   * @return a mutable event map
   */
  public static Map<String, Object> toEvent(LogRecord record) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("dt", record.timestamp());
    event.put("message", record.body());
    event.put("level", record.severityText().toLowerCase(Locale.ROOT));
    event.put("severity_number", record.severity().number());
    record
        .traceId()
        .ifPresent(
            t -> {
              Map<String, Object> span = new LinkedHashMap<>();
              span.put("trace_id", t);
              record.spanId().ifPresent(s -> span.put("span_id", s));
              event.put("span", span);
              record.traceFlags().ifPresent(f -> event.put("trace_flags", f));
            });
    if (!record.resource().isEmpty()) {
      event.put("resource", record.resource());
    }
    if (!record.attributes().isEmpty()) {
      event.put("attributes", record.attributes());
    }
    record.observedTimestamp().ifPresent(t -> event.put("observed_dt", t));
    return event;
  }

  /**
   * Formats an instant as RFC 3339 UTC with up to nanosecond digits ({@code
   * 2026-11-18T21:00:00.123456789Z}).
   *
   * @param t instant
   * @return the text
   */
  public static String formatTimestamp(Instant t) {
    return DateTimeFormatter.ISO_INSTANT.format(t);
  }

  /**
   * JSON text of one event ({@link Instant} values become RFC 3339 UTC strings).
   *
   * @param event event
   * @return compact JSON
   */
  public static String eventJson(Map<String, ?> event) {
    return Json.stringify(jsonReady(event));
  }

  private static Object jsonReady(Object v) {
    if (v instanceof Instant) {
      return formatTimestamp((Instant) v);
    }
    if (v instanceof Map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
        out.put(String.valueOf(e.getKey()), jsonReady(e.getValue()));
      }
      return out;
    }
    if (v instanceof Collection) {
      List<Object> out = new ArrayList<>();
      for (Object item : (Collection<?>) v) {
        out.add(jsonReady(item));
      }
      return out;
    }
    return v;
  }

  /**
   * Encodes events as one request body: {@code JSON} a JSON array, {@code NDJSON} one object per
   * line, {@code MSGPACK} a MessagePack array with {@link Instant} values as timestamp extensions.
   *
   * @param events events
   * @param encoding body encoding
   * @return the body
   */
  public static byte[] encode(List<? extends Map<String, ?>> events, IngestEncoding encoding) {
    List<byte[]> parts = new ArrayList<>(events.size());
    for (Map<String, ?> e : events) {
      parts.add(encodeOne(e, encoding));
    }
    return frame(parts, encoding);
  }

  /** Encodes one event for {@code encoding} (JSON object bytes or MessagePack map). */
  static byte[] encodeOne(Map<String, ?> event, IngestEncoding encoding) {
    if (encoding == IngestEncoding.MSGPACK) {
      return MessagePack.encode(event);
    }
    return eventJson(event).getBytes(StandardCharsets.UTF_8);
  }

  /** Joins encoded events into one body. */
  static byte[] frame(List<byte[]> parts, IngestEncoding encoding) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    switch (encoding) {
      case NDJSON:
        for (byte[] p : parts) {
          out.writeBytes(p);
          out.write('\n');
        }
        break;
      case MSGPACK:
        out.writeBytes(MessagePack.arrayHeader(parts.size()));
        for (byte[] p : parts) {
          out.writeBytes(p);
        }
        break;
      default:
        out.write('[');
        for (int i = 0; i < parts.size(); i++) {
          if (i > 0) {
            out.write(',');
          }
          out.writeBytes(parts.get(i));
        }
        out.write(']');
        break;
    }
    return out.toByteArray();
  }
}
