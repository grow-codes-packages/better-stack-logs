package codes.grow.packages.betterstack;

/** Wire protocol of a {@link BetterStackClient#logClient(BetterStackLogClientOptions)}. */
public enum LogProtocol {
  /** Better Stack HTTP ingest API ({@code POST /}, JSON / NDJSON / MessagePack). Default. */
  HTTP,
  /** OTLP/HTTP logs ({@code POST /v1/logs}, protobuf or JSON). */
  OTLP
}
