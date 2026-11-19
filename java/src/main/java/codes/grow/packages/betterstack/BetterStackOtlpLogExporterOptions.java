package codes.grow.packages.betterstack;

/**
 * Options of a {@link BetterStackOtlpLogExporter}.
 *
 * @param protocol payload format (default {@link OtlpProtocol#PROTOBUF})
 * @param scopeName instrumentation scope name (default {@link OtlpLogs#DEFAULT_SCOPE_NAME})
 * @param maxRequestBytes uncompressed bytes per request, 1 to 10 MiB (default 10 MiB)
 */
public record BetterStackOtlpLogExporterOptions(
    OtlpProtocol protocol, String scopeName, int maxRequestBytes) {
  /**
   * Fills defaults for {@code null} / 0 values.
   *
   * @param protocol payload format
   * @param scopeName scope name
   * @param maxRequestBytes request limit, 0 for the default
   */
  public BetterStackOtlpLogExporterOptions {
    protocol = protocol == null ? OtlpProtocol.PROTOBUF : protocol;
    scopeName = scopeName == null || scopeName.isEmpty() ? OtlpLogs.DEFAULT_SCOPE_NAME : scopeName;
    maxRequestBytes =
        maxRequestBytes == 0 ? BetterStackLogExporter.MAX_REQUEST_BYTES : maxRequestBytes;
  }

  /**
   * Defaults: protobuf, default scope, 10 MiB.
   *
   * @return options
   */
  public static BetterStackOtlpLogExporterOptions defaults() {
    return new BetterStackOtlpLogExporterOptions(null, null, 0);
  }

  /**
   * Defaults with another payload format.
   *
   * @param protocol payload format
   * @return options
   */
  public static BetterStackOtlpLogExporterOptions of(OtlpProtocol protocol) {
    return new BetterStackOtlpLogExporterOptions(protocol, null, 0);
  }
}
