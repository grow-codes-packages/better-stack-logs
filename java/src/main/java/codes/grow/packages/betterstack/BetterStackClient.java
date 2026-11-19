package codes.grow.packages.betterstack;

import codes.grow.packages.betterstack.core.AuthenticationException;
import codes.grow.packages.betterstack.core.BetterStackException;
import codes.grow.packages.betterstack.core.CancelledException;
import codes.grow.packages.betterstack.core.ConfigException;
import codes.grow.packages.betterstack.core.EnvReader;
import codes.grow.packages.betterstack.core.HealthStatus;
import codes.grow.packages.betterstack.core.Hooks;
import codes.grow.packages.betterstack.core.HttpTransport;
import codes.grow.packages.betterstack.core.Logger;
import codes.grow.packages.betterstack.core.RetryPolicy;
import codes.grow.packages.betterstack.core.Secret;
import codes.grow.packages.betterstack.core.TlsOptions;
import codes.grow.packages.betterstack.core.TransportRequest;
import codes.grow.packages.betterstack.core.TransportResponse;
import codes.grow.packages.betterstack.core.UnsupportedException;
import codes.grow.packages.betterstack.logging.BatchOptions;
import codes.grow.packages.betterstack.logging.BatchingLogClient;
import codes.grow.packages.betterstack.logging.LogExporter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client for Better Stack Logs: ingestion through the source's ingesting host ({@link
 * #logClient()}, {@link #exporter()}, {@link #otlpExporter()}) and the SQL query API ({@link
 * #query()}), each with its own credentials and transport. A source token is never sent to the
 * query host and SQL credentials never to the ingesting host.
 *
 * <pre>{@code
 * try (BetterStackClient client = BetterStackClient.fromEnv()) {
 *   BatchingLogClient logs = client.logClient(
 *       BetterStackLogClientOptions.builder().resource(Map.of("service.name", "checkout")).build());
 *   logs.logger().info("order placed", Map.of("orderId", 42));
 * } // close() flushes every log client
 * }</pre>
 *
 * <p>Thread-safe. Every I/O method has a blocking form and an {@code …Async} form returning {@link
 * CompletableFuture}.
 */
public final class BetterStackClient implements AutoCloseable {
  private final BetterStackConfig config;
  private final HttpTransport ingest;
  private final HttpTransport queryTransport;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final List<AutoCloseable> resources = new ArrayList<>();
  private final BetterStackLogExporter exporter;
  private final BetterStackOtlpLogExporter otlp;
  private final BetterStackQuery query;

  private BetterStackClient(BetterStackConfig config, Clock clock) {
    this.config = config;
    this.ingest = config.hasIngest() ? new HttpTransport(config.ingestTransportConfig()) : null;
    HttpTransport q = null;
    try {
      q = config.hasQuery() ? new HttpTransport(config.queryTransportConfig()) : null;
    } catch (RuntimeException e) {
      if (ingest != null) {
        ingest.close();
      }
      throw e;
    }
    this.queryTransport = q;
    this.exporter =
        ingest == null
            ? null
            : new BetterStackLogExporter(ingest, config, BetterStackLogExporterOptions.defaults());
    this.otlp =
        ingest == null
            ? null
            : new BetterStackOtlpLogExporter(
                ingest, config, BetterStackOtlpLogExporterOptions.defaults());
    this.query = q == null ? null : new BetterStackQuery(this, q, config, clock);
  }

  /**
   * Creates a client.
   *
   * @param config configuration
   * @return a new client
   * @throws ConfigException when the configuration, TLS or proxy settings are invalid
   */
  public static BetterStackClient create(BetterStackConfig config) {
    return create(config, Clock.systemUTC());
  }

  static BetterStackClient create(BetterStackConfig config, Clock clock) {
    config.validate();
    return new BetterStackClient(config, clock);
  }

  /**
   * Creates a client from {@code GROW_BETTER_STACK_LOGS_*} and the vendor variables ({@code
   * BETTERSTACK_SOURCE_TOKEN}, {@code BETTERSTACK_INGESTING_HOST}, {@code BETTERSTACK_QUERY_*}, …).
   *
   * @return a new client
   */
  public static BetterStackClient fromEnv() {
    return create(BetterStackConfig.fromEnv());
  }

  /**
   * Starts a client builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the configuration.
   *
   * @return the configuration
   */
  public BetterStackConfig config() {
    return config;
  }

  /**
   * Registers a resource (log client, tail) closed with the client.
   *
   * @param resource the resource
   * @throws CancelledException when the client is closed (the resource is closed first)
   */
  public void register(AutoCloseable resource) {
    synchronized (resources) {
      if (!closed.get()) {
        resources.add(resource);
        return;
      }
    }
    closeQuietly(resource);
    throw new CancelledException("register: client is closed");
  }

  private HttpTransport ingestTransport(String operation) {
    if (ingest == null) {
      throw new ConfigException(
          operation + ": url (the source's ingesting host) and sourceToken are required");
    }
    return ingest;
  }

  // ------------------------------------------------------------------------------- core

  /**
   * Unauthenticated liveness check that never sends data. With a query host: {@code GET
   * {queryUrl}/ping} ({@code Ok.}). Ingest-only: {@code GET {url}/} without credentials; any answer
   * below 500 means the host is reachable. {@code details.endpoint} is {@code query} or {@code
   * ingest}. Never throws for an unhealthy or unreachable service.
   *
   * @return the health status
   * @throws CancelledException when interrupted or closed
   */
  public HealthStatus health() {
    return HttpTransport.await(healthAsync(), "health");
  }

  /**
   * Asynchronous {@link #health()}.
   *
   * @return a future with the health status
   */
  public CompletableFuture<HealthStatus> healthAsync() {
    boolean viaQuery = queryTransport != null;
    HttpTransport t = viaQuery ? queryTransport : ingest;
    String endpoint = viaQuery ? "query" : "ingest";
    TransportRequest req =
        TransportRequest.get(viaQuery ? "/ping" : "/")
            .operation("health")
            .acceptAnyStatus()
            .maxRetries(0)
            .header("Accept", "*/*")
            .build();
    return t.sendAsync(req)
        .handle(
            (resp, err) -> {
              if (err != null) {
                BetterStackException e = HttpTransport.asSdkException(err, "health");
                if (e instanceof CancelledException || e instanceof ConfigException) {
                  throw e;
                }
                return new HealthStatus(
                    false,
                    "unreachable",
                    Map.of("error", String.valueOf(e.getMessage()), "endpoint", endpoint));
              }
              return viaQuery ? queryHealth(resp) : ingestHealth(resp);
            });
  }

  private static HealthStatus queryHealth(TransportResponse resp) {
    Map<String, String> details = new LinkedHashMap<>();
    details.put("endpoint", "query");
    if (resp.status() < 200 || resp.status() >= 300) {
      details.put("httpStatus", Integer.toString(resp.status()));
      return new HealthStatus(false, "unhealthy", details);
    }
    String word = new String(resp.body(), StandardCharsets.UTF_8).trim();
    if (word.endsWith(".")) {
      word = word.substring(0, word.length() - 1);
    }
    return new HealthStatus(true, word.isEmpty() ? "ok" : word.toLowerCase(Locale.ROOT), details);
  }

  private static HealthStatus ingestHealth(TransportResponse resp) {
    Map<String, String> details = new LinkedHashMap<>();
    details.put("endpoint", "ingest");
    details.put("httpStatus", Integer.toString(resp.status()));
    boolean ok = resp.status() < 500;
    return new HealthStatus(ok, ok ? "ok" : "unhealthy", details);
  }

  /**
   * Authenticated round trip. With query credentials: {@code SELECT count() AS c FROM
   * remote(<sourceTable>_logs) WHERE 1 = 0} (or {@code SELECT 1} without a source table).
   * Ingest-only: posts an empty batch {@code []} (no event is stored).
   *
   * @return the latency
   * @throws AuthenticationException on bad credentials
   */
  public Duration ping() {
    return HttpTransport.await(pingAsync(), "ping");
  }

  /**
   * Asynchronous {@link #ping()}.
   *
   * @return a future with the latency
   */
  public CompletableFuture<Duration> pingAsync() {
    long start = System.nanoTime();
    CompletableFuture<?> call;
    if (query != null) {
      String sql =
          config
              .sourceTable()
              .map(t -> "SELECT count() AS c FROM remote(" + t + "_logs) WHERE 1 = 0")
              .orElse("SELECT 1 AS ok");
      call = query.queryAsync(sql, Map.of(), QueryOptions.defaults());
    } else {
      call =
          BetterStackErrors.mapFailure(
              exporter.post(
                  "[]".getBytes(StandardCharsets.UTF_8), "application/json", false, "ping"),
              e -> BetterStackErrors.mapIngestError(e, "ping"));
    }
    return call.thenApply(r -> Duration.ofNanos(System.nanoTime() - start));
  }

  /**
   * Better Stack is a SaaS without a version endpoint (the query host's ClickHouse version is not
   * one).
   *
   * @return never
   * @throws UnsupportedException always
   */
  public String serverVersion() {
    return HttpTransport.await(serverVersionAsync(), "serverVersion");
  }

  /**
   * Asynchronous {@link #serverVersion()}: always fails with {@link UnsupportedException}.
   *
   * @return a failed future
   */
  public CompletableFuture<String> serverVersionAsync() {
    return CompletableFuture.failedFuture(
        new UnsupportedException("serverVersion: Better Stack does not report a server version"));
  }

  // ------------------------------------------------------------------------------ ingest

  /**
   * The HTTP ingest exporter in the configured encoding with the default limits.
   *
   * @return the exporter
   * @throws ConfigException without ingest credentials
   */
  public BetterStackLogExporter exporter() {
    ingestTransport("exporter");
    return exporter;
  }

  /**
   * A new HTTP ingest exporter with its own encoding and limits (use it with {@link
   * #logClient(LogExporter, BetterStackLogClientOptions)}).
   *
   * @param options encoding and limits
   * @return the exporter
   * @throws ConfigException without ingest credentials
   * @throws codes.grow.packages.betterstack.core.ValidationException for limits outside 1 to 10 MiB
   */
  public BetterStackLogExporter newExporter(BetterStackLogExporterOptions options) {
    return new BetterStackLogExporter(ingestTransport("newExporter"), config, options);
  }

  /**
   * The OTLP/HTTP logs exporter (protobuf, {@code /v1/logs}).
   *
   * @return the exporter
   * @throws ConfigException without ingest credentials
   */
  public BetterStackOtlpLogExporter otlpExporter() {
    ingestTransport("otlpExporter");
    return otlp;
  }

  /**
   * A new OTLP exporter with its own payload format, scope name and request limit.
   *
   * @param options options
   * @return the exporter
   * @throws ConfigException without ingest credentials
   */
  public BetterStackOtlpLogExporter newOtlpExporter(BetterStackOtlpLogExporterOptions options) {
    return new BetterStackOtlpLogExporter(ingestTransport("newOtlpExporter"), config, options);
  }

  /**
   * A batching log client over the HTTP ingest API with default options.
   *
   * @return the log client
   * @throws ConfigException without ingest credentials
   */
  public BatchingLogClient logClient() {
    return logClient(BetterStackLogClientOptions.defaults());
  }

  /**
   * A batching log client over the HTTP ingest API ({@link LogProtocol#HTTP}, default) or OTLP.
   * {@link #close()} flushes and closes every log client created here.
   *
   * @param options protocol, exporter options, batching, resource, redaction
   * @return the log client
   * @throws ConfigException without ingest credentials
   */
  public BatchingLogClient logClient(BetterStackLogClientOptions options) {
    HttpTransport t = ingestTransport("logClient");
    LogExporter e =
        options.protocol() == LogProtocol.OTLP
            ? new BetterStackOtlpLogExporter(t, config, options.otlp())
            : new BetterStackLogExporter(t, config, options.exporter());
    return logClient(e, options);
  }

  /**
   * A batching log client over any exporter (for example {@link #newExporter}). The protocol and
   * exporter options of {@code options} are ignored. {@link #close()} flushes and closes it.
   *
   * @param exporter the exporter
   * @param options batching, resource, redaction
   * @return the log client
   */
  public BatchingLogClient logClient(LogExporter exporter, BetterStackLogClientOptions options) {
    if (closed.get()) {
      throw new CancelledException("logClient: client is closed");
    }
    BatchOptions batch = options.batchOptions();
    if (batch.maxBatchBytes() > BetterStackLogExporter.MAX_REQUEST_BYTES) {
      batch = batch.toBuilder().maxBatchBytes(BetterStackLogExporter.MAX_REQUEST_BYTES).build();
    }
    BatchingLogClient client =
        BatchingLogClient.builder(exporter)
            .batchOptions(batch)
            .resource(options.resource())
            .redaction(options.redaction())
            .build();
    register(client);
    return client;
  }

  // ------------------------------------------------------------------------------- query

  /**
   * The SQL query client (also the {@link codes.grow.packages.betterstack.logging.LogQueryClient}).
   *
   * @return the query client
   * @throws ConfigException without query credentials
   */
  public BetterStackQuery query() {
    if (query == null) {
      throw new ConfigException(
          "query: queryUrl, queryUsername and queryPassword are required for SQL queries");
    }
    return query;
  }

  // ------------------------------------------------------------------------------- close

  /**
   * Returns whether {@link #close()} was called.
   *
   * @return true once closed
   */
  public boolean isClosed() {
    return closed.get();
  }

  /**
   * Flushes and closes every log client, stops tails, then releases both transports. Idempotent;
   * later calls raise {@link CancelledException}.
   */
  @Override
  public void close() {
    List<AutoCloseable> copy;
    synchronized (resources) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      copy = new ArrayList<>(resources);
      resources.clear();
    }
    for (AutoCloseable r : copy) {
      closeQuietly(r);
    }
    if (ingest != null) {
      ingest.close();
    }
    if (queryTransport != null) {
      queryTransport.close();
    }
  }

  private static void closeQuietly(AutoCloseable r) {
    try {
      r.close();
    } catch (Exception ignored) {
      // best effort: close must not throw
    }
  }

  @Override
  public String toString() {
    return "BetterStackClient{" + config + "}";
  }

  /** Builder that configures and creates a client in one chain. */
  public static final class Builder {
    private final BetterStackConfig.Builder config = BetterStackConfig.builder();

    private Builder() {}

    /**
     * Fills unset fields from an environment reader.
     *
     * @param env reader, e.g. {@link EnvReader#system()}
     * @return this builder
     */
    public Builder fromEnv(EnvReader env) {
      config.fromEnv(env);
      return this;
    }

    /**
     * Sets the ingest base URL (the source's ingesting host).
     *
     * @param url host or URL
     * @return this builder
     */
    public Builder url(String url) {
      config.url(url);
      return this;
    }

    /**
     * Sets the source token.
     *
     * @param token token
     * @return this builder
     */
    public Builder sourceToken(String token) {
      config.sourceToken(token);
      return this;
    }

    /**
     * Sets the source token.
     *
     * @param token token
     * @return this builder
     */
    public Builder sourceToken(Secret token) {
      config.sourceToken(token);
      return this;
    }

    /**
     * Sets the SQL query base URL.
     *
     * @param url host or URL
     * @return this builder
     */
    public Builder queryUrl(String url) {
      config.queryUrl(url);
      return this;
    }

    /**
     * Sets the SQL connection username.
     *
     * @param username username
     * @return this builder
     */
    public Builder queryUsername(String username) {
      config.queryUsername(username);
      return this;
    }

    /**
     * Sets the SQL connection password.
     *
     * @param password password
     * @return this builder
     */
    public Builder queryPassword(String password) {
      config.queryPassword(password);
      return this;
    }

    /**
     * Sets the SQL connection password.
     *
     * @param password password
     * @return this builder
     */
    public Builder queryPassword(Secret password) {
      config.queryPassword(password);
      return this;
    }

    /**
     * Sets the default collection.
     *
     * @param sourceTable identifier
     * @return this builder
     */
    public Builder sourceTable(String sourceTable) {
      config.sourceTable(sourceTable);
      return this;
    }

    /**
     * Sets the ingest body encoding.
     *
     * @param encoding encoding
     * @return this builder
     */
    public Builder encoding(IngestEncoding encoding) {
      config.encoding(encoding);
      return this;
    }

    /**
     * Sets the connect timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder connectTimeout(Duration d) {
      config.connectTimeout(d);
      return this;
    }

    /**
     * Sets the per-attempt timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder requestTimeout(Duration d) {
      config.requestTimeout(d);
      return this;
    }

    /**
     * Sets the total timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder totalTimeout(Duration d) {
      config.totalTimeout(d);
      return this;
    }

    /**
     * Sets the retry policy.
     *
     * @param retry policy
     * @return this builder
     */
    public Builder retry(RetryPolicy retry) {
      config.retry(retry);
      return this;
    }

    /**
     * Sets the TLS options.
     *
     * @param tls options
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      config.tls(tls);
      return this;
    }

    /**
     * Sets the proxy URL.
     *
     * @param proxy URL
     * @return this builder
     */
    public Builder proxy(String proxy) {
      config.proxy(proxy);
      return this;
    }

    /**
     * Adds a header.
     *
     * @param name name
     * @param value value
     * @return this builder
     */
    public Builder header(String name, String value) {
      config.header(name, value);
      return this;
    }

    /**
     * Sets the user agent suffix.
     *
     * @param suffix suffix
     * @return this builder
     */
    public Builder userAgentSuffix(String suffix) {
      config.userAgentSuffix(suffix);
      return this;
    }

    /**
     * Sets hooks.
     *
     * @param hooks hooks
     * @return this builder
     */
    public Builder hooks(Hooks hooks) {
      config.hooks(hooks);
      return this;
    }

    /**
     * Sets the logger.
     *
     * @param logger logger
     * @return this builder
     */
    public Builder logger(Logger logger) {
      config.logger(logger);
      return this;
    }

    /**
     * Builds the configuration and the client.
     *
     * @return a new client
     * @throws ConfigException when the configuration is invalid
     */
    public BetterStackClient build() {
      return create(config.build());
    }
  }
}
