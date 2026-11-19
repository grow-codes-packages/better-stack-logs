package codes.grow.packages.betterstack;

import java.util.Optional;

/**
 * Options of {@link BetterStackLogExporter#sendEvents(java.util.List, SendEventsOptions)}.
 *
 * @param encoding body encoding; empty uses the exporter's encoding
 * @param gzip whether the body is gzip-compressed ({@code Content-Encoding: gzip})
 */
public record SendEventsOptions(Optional<IngestEncoding> encoding, boolean gzip) {
  /**
   * Replaces a {@code null} encoding by empty.
   *
   * @param encoding encoding or empty
   * @param gzip gzip flag
   */
  public SendEventsOptions {
    encoding = encoding == null ? Optional.empty() : encoding;
  }

  /**
   * Defaults: the exporter's encoding, gzip on.
   *
   * @return options
   */
  public static SendEventsOptions defaults() {
    return new SendEventsOptions(Optional.empty(), true);
  }

  /**
   * Copy with an explicit encoding.
   *
   * @param encoding encoding
   * @return options
   */
  public SendEventsOptions withEncoding(IngestEncoding encoding) {
    return new SendEventsOptions(Optional.of(encoding), gzip);
  }

  /**
   * Copy with gzip on or off.
   *
   * @param gzip gzip flag
   * @return options
   */
  public SendEventsOptions withGzip(boolean gzip) {
    return new SendEventsOptions(encoding, gzip);
  }
}
