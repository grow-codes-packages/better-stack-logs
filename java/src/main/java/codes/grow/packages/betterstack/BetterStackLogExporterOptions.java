package codes.grow.packages.betterstack;

import java.util.Optional;

/**
 * Options of a {@link BetterStackLogExporter}: body encoding and request limits.
 *
 * @param encoding body encoding; empty uses {@link BetterStackConfig#encoding()}
 * @param maxRequestBytes uncompressed bytes per request, 1 to 10 MiB (default 10 MiB)
 * @param maxRecordBytes encoded bytes per record, 1 to 10 MiB (default 10 MiB); larger records are
 *     rejected before anything is sent
 */
public record BetterStackLogExporterOptions(
    Optional<IngestEncoding> encoding, int maxRequestBytes, int maxRecordBytes) {
  /**
   * Fills defaults.
   *
   * @param encoding body encoding or empty
   * @param maxRequestBytes request limit, 0 for the default
   * @param maxRecordBytes record limit, 0 for the default
   */
  public BetterStackLogExporterOptions {
    encoding = encoding == null ? Optional.empty() : encoding;
    maxRequestBytes =
        maxRequestBytes == 0 ? BetterStackLogExporter.MAX_REQUEST_BYTES : maxRequestBytes;
    maxRecordBytes = maxRecordBytes == 0 ? BetterStackLogExporter.MAX_RECORD_BYTES : maxRecordBytes;
  }

  /**
   * Defaults: the configured encoding, 10 MiB limits.
   *
   * @return options
   */
  public static BetterStackLogExporterOptions defaults() {
    return new BetterStackLogExporterOptions(Optional.empty(), 0, 0);
  }

  /**
   * Options with an explicit encoding and default limits.
   *
   * @param encoding body encoding
   * @return options
   */
  public static BetterStackLogExporterOptions of(IngestEncoding encoding) {
    return new BetterStackLogExporterOptions(Optional.of(encoding), 0, 0);
  }

  /**
   * Copy with another request limit.
   *
   * @param bytes uncompressed bytes per request
   * @return options
   */
  public BetterStackLogExporterOptions withMaxRequestBytes(int bytes) {
    return new BetterStackLogExporterOptions(encoding, bytes, maxRecordBytes);
  }

  /**
   * Copy with another record limit.
   *
   * @param bytes encoded bytes per record
   * @return options
   */
  public BetterStackLogExporterOptions withMaxRecordBytes(int bytes) {
    return new BetterStackLogExporterOptions(encoding, maxRequestBytes, bytes);
  }
}
