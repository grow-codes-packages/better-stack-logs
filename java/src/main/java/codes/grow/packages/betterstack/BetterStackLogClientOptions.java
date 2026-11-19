package codes.grow.packages.betterstack;

import codes.grow.packages.betterstack.logging.BatchOptions;
import codes.grow.packages.betterstack.logging.RedactionProcessor;
import java.util.Map;
import java.util.Objects;

/**
 * Options of {@link BetterStackClient#logClient(BetterStackLogClientOptions)}: protocol, exporter
 * options, batching, resource and redaction.
 */
public final class BetterStackLogClientOptions {
  private final LogProtocol protocol;
  private final BetterStackLogExporterOptions exporter;
  private final BetterStackOtlpLogExporterOptions otlp;
  private final BatchOptions batchOptions;
  private final Map<String, Object> resource;
  private final RedactionProcessor redaction;

  private BetterStackLogClientOptions(Builder b) {
    this.protocol = b.protocol;
    this.exporter = b.exporter;
    this.otlp = b.otlp;
    this.batchOptions = b.batchOptions;
    this.resource = Map.copyOf(b.resource);
    this.redaction = b.redaction;
  }

  /**
   * Defaults: HTTP ingest in the configured encoding, default batching, no resource, default
   * redaction.
   *
   * @return options
   */
  public static BetterStackLogClientOptions defaults() {
    return builder().build();
  }

  /**
   * Returns a builder.
   *
   * @return builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Wire protocol.
   *
   * @return protocol
   */
  public LogProtocol protocol() {
    return protocol;
  }

  /**
   * Options of the HTTP ingest exporter (protocol {@link LogProtocol#HTTP}).
   *
   * @return options
   */
  public BetterStackLogExporterOptions exporter() {
    return exporter;
  }

  /**
   * Options of the OTLP exporter (protocol {@link LogProtocol#OTLP}).
   *
   * @return options
   */
  public BetterStackOtlpLogExporterOptions otlp() {
    return otlp;
  }

  /**
   * Batching options; {@code maxBatchBytes} is capped at 10 MiB.
   *
   * @return options
   */
  public BatchOptions batchOptions() {
    return batchOptions;
  }

  /**
   * Resource attributes ({@code service.name}, {@code deployment.environment}, …).
   *
   * @return attributes
   */
  public Map<String, Object> resource() {
    return resource;
  }

  /**
   * Redaction processor, or {@code null} when redaction is disabled.
   *
   * @return processor
   */
  public RedactionProcessor redaction() {
    return redaction;
  }

  /** Builder of {@link BetterStackLogClientOptions}. */
  public static final class Builder {
    private LogProtocol protocol = LogProtocol.HTTP;
    private BetterStackLogExporterOptions exporter = BetterStackLogExporterOptions.defaults();
    private BetterStackOtlpLogExporterOptions otlp = BetterStackOtlpLogExporterOptions.defaults();
    private BatchOptions batchOptions = BatchOptions.defaults();
    private Map<String, ?> resource = Map.of();
    private RedactionProcessor redaction = RedactionProcessor.defaults();

    private Builder() {}

    /**
     * Sets the wire protocol.
     *
     * @param protocol protocol
     * @return this builder
     */
    public Builder protocol(LogProtocol protocol) {
      this.protocol = Objects.requireNonNull(protocol, "protocol");
      return this;
    }

    /**
     * Sets the HTTP exporter options (encoding, limits).
     *
     * @param exporter options
     * @return this builder
     */
    public Builder exporter(BetterStackLogExporterOptions exporter) {
      this.exporter = Objects.requireNonNull(exporter, "exporter");
      return this;
    }

    /**
     * Sets the OTLP exporter options.
     *
     * @param otlp options
     * @return this builder
     */
    public Builder otlp(BetterStackOtlpLogExporterOptions otlp) {
      this.otlp = Objects.requireNonNull(otlp, "otlp");
      return this;
    }

    /**
     * Sets the batching options.
     *
     * @param batchOptions options
     * @return this builder
     */
    public Builder batchOptions(BatchOptions batchOptions) {
      this.batchOptions = Objects.requireNonNull(batchOptions, "batchOptions");
      return this;
    }

    /**
     * Sets the resource attributes.
     *
     * @param resource attributes
     * @return this builder
     */
    public Builder resource(Map<String, ?> resource) {
      this.resource = Objects.requireNonNull(resource, "resource");
      return this;
    }

    /**
     * Sets the redaction processor; {@code null} disables redaction (not recommended).
     *
     * @param redaction processor or {@code null}
     * @return this builder
     */
    public Builder redaction(RedactionProcessor redaction) {
      this.redaction = redaction;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return options
     */
    public BetterStackLogClientOptions build() {
      return new BetterStackLogClientOptions(this);
    }
  }
}
