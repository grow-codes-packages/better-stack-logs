package codes.grow.packages.betterstack;

import java.util.Locale;
import java.util.Optional;

/** Body encodings accepted by {@code POST /} on a Better Stack source's ingesting host. */
public enum IngestEncoding {
  /** One JSON array per request ({@code application/json}). Default. */
  JSON("json", "application/json"),
  /** One JSON object per line ({@code application/x-ndjson}). */
  NDJSON("ndjson", "application/x-ndjson"),
  /**
   * One MessagePack array per request ({@code application/msgpack}); {@code dt} is a MessagePack
   * timestamp extension (type -1).
   */
  MSGPACK("msgpack", "application/msgpack");

  private final String wire;
  private final String contentType;

  IngestEncoding(String wire, String contentType) {
    this.wire = wire;
    this.contentType = contentType;
  }

  /**
   * Returns the configuration value ({@code json}, {@code ndjson} or {@code msgpack}).
   *
   * @return the wire name
   */
  public String wireValue() {
    return wire;
  }

  /**
   * Returns the {@code Content-Type} sent for this encoding.
   *
   * @return the media type
   */
  public String contentType() {
    return contentType;
  }

  /**
   * Parses a configuration value (case-insensitive, surrounding blanks ignored).
   *
   * @param text {@code json}, {@code ndjson} or {@code msgpack}
   * @return the encoding, or empty when unknown
   */
  public static Optional<IngestEncoding> parse(String text) {
    if (text == null) {
      return Optional.empty();
    }
    String t = text.trim().toLowerCase(Locale.ROOT);
    for (IngestEncoding e : values()) {
      if (e.wire.equals(t)) {
        return Optional.of(e);
      }
    }
    return Optional.empty();
  }
}
