package codes.grow.packages.betterstack;

import codes.grow.packages.betterstack.core.ConfigException;
import codes.grow.packages.betterstack.core.EnvReader;
import codes.grow.packages.betterstack.core.Hooks;
import codes.grow.packages.betterstack.core.Logger;
import codes.grow.packages.betterstack.core.Redaction;
import codes.grow.packages.betterstack.core.RetryPolicy;
import codes.grow.packages.betterstack.core.Secret;
import codes.grow.packages.betterstack.core.TlsOptions;
import codes.grow.packages.betterstack.core.TransportConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Immutable configuration of a {@link BetterStackClient} (SPEC section 2.2).
 *
 * <p>Better Stack Logs has two hosts with separate credentials:
 *
 * <ul>
 *   <li>the source's <b>ingesting host</b> ({@link #url()}, for example {@code
 *       s1234.eu-nbg-2.betterstackdata.com}) with the source token ({@code Authorization: Bearer})
 *   <li>the <b>SQL query host</b> ({@link #queryUrl()}, ClickHouse HTTP interface, for example
 *       {@code eu-nbg-2-connect.betterstackdata.com}) with the username and password of a
 *       connection (HTTP Basic)
 * </ul>
 *
 * <p>Each set is all-or-nothing and at least one must be complete. A value without {@code ://}
 * becomes {@code https://value}; trailing slashes are removed. There is no default ingesting host.
 *
 * <p>Build one with {@link #builder()} or read it from the environment with {@link #fromEnv()}.
 * Precedence: explicit builder value &gt; {@code GROW_BETTER_STACK_LOGS_<NAME>} &gt; vendor
 * variables ({@code BETTERSTACK_*}) &gt; default. {@link #toString()} never prints credentials.
 */
public final class BetterStackConfig {
  /** Collection (table) names are identifiers: they cannot be bound as SQL parameters. */
  public static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

  /** Vendor variables read for {@code url} after {@code GROW_BETTER_STACK_LOGS_URL}, in order. */
  public static final List<String> URL_VENDOR_VARS =
      List.of("BETTERSTACK_INGESTING_URL", "BETTERSTACK_INGESTING_HOST");

  /** Vendor variables read for {@code sourceToken}. */
  public static final List<String> SOURCE_TOKEN_VENDOR_VARS = List.of("BETTERSTACK_SOURCE_TOKEN");

  /** Vendor variables read for {@code queryUrl}, in order. */
  public static final List<String> QUERY_URL_VENDOR_VARS =
      List.of("BETTERSTACK_QUERY_URL", "BETTERSTACK_QUERY_HOST");

  /** Vendor variables read for {@code queryUsername}. */
  public static final List<String> QUERY_USERNAME_VENDOR_VARS =
      List.of("BETTERSTACK_QUERY_USERNAME");

  /** Vendor variables read for {@code queryPassword}. */
  public static final List<String> QUERY_PASSWORD_VENDOR_VARS =
      List.of("BETTERSTACK_QUERY_PASSWORD");

  /** Vendor variables read for {@code sourceTable}. */
  public static final List<String> SOURCE_TABLE_VENDOR_VARS = List.of("BETTERSTACK_SOURCE_TABLE");

  private final String url;
  private final Secret sourceToken;
  private final String queryUrl;
  private final String queryUsername;
  private final Secret queryPassword;
  private final String sourceTable;
  private final IngestEncoding encoding;
  private final Duration connectTimeout;
  private final Duration requestTimeout;
  private final Duration totalTimeout;
  private final RetryPolicy retry;
  private final TlsOptions tls;
  private final String proxy;
  private final Map<String, String> headers;
  private final String userAgentSuffix;
  private final Hooks hooks;
  private final Logger logger;

  private BetterStackConfig(Builder b) {
    this.url = normalizeBaseUrl(b.url);
    this.sourceToken = b.sourceToken;
    this.queryUrl = normalizeBaseUrl(b.queryUrl);
    this.queryUsername = blankToNull(b.queryUsername);
    this.queryPassword = b.queryPassword;
    this.sourceTable = blankToNull(b.sourceTable);
    this.encoding = b.encoding != null ? b.encoding : IngestEncoding.JSON;
    this.connectTimeout = b.connectTimeout != null ? b.connectTimeout : Duration.ofSeconds(10);
    this.requestTimeout = b.requestTimeout != null ? b.requestTimeout : Duration.ofSeconds(30);
    this.totalTimeout = b.totalTimeout != null ? b.totalTimeout : Duration.ofSeconds(120);
    this.retry = b.retry != null ? b.retry : RetryPolicy.defaults();
    this.tls = b.tls != null ? b.tls : TlsOptions.defaults();
    this.proxy = b.proxy;
    this.headers = Map.copyOf(b.headers);
    this.userAgentSuffix = b.userAgentSuffix;
    this.hooks = b.hooks != null ? b.hooks : Hooks.NONE;
    this.logger = b.logger != null ? b.logger : Logger.NOOP;
  }

  private static String blankToNull(String s) {
    return s == null || s.trim().isEmpty() ? null : s.trim();
  }

  /**
   * Normalizes a host or URL: a value containing {@code ://} is used as given, a bare {@code
   * host[:port]} becomes {@code https://host[:port]}; trailing slashes are removed.
   *
   * @param value host or URL, may be null
   * @return the base URL, or {@code null} for blank input
   */
  public static String normalizeBaseUrl(String value) {
    String t = blankToNull(value);
    if (t == null) {
      return null;
    }
    String withScheme = t.contains("://") ? t : "https://" + t;
    int end = withScheme.length();
    while (end > 0 && withScheme.charAt(end - 1) == '/') {
      end--;
    }
    return withScheme.substring(0, end);
  }

  /**
   * Whether {@code s} is a safe ClickHouse identifier ({@code ^[A-Za-z_][A-Za-z0-9_]*$}), the rule
   * for collections (source tables).
   *
   * @param s text
   * @return true when valid
   */
  public static boolean isValidIdentifier(String s) {
    return s != null && IDENTIFIER_PATTERN.matcher(s).matches();
  }

  /**
   * Starts an empty builder (every field at its default).
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Reads the configuration from the process environment.
   *
   * @return the validated configuration
   * @throws ConfigException when a variable holds an invalid value (the message names the variable,
   *     never the value) or neither credential set is complete
   */
  public static BetterStackConfig fromEnv() {
    return builder().fromEnv(EnvReader.system()).build();
  }

  /**
   * Reads the configuration from an explicit variable map (tests).
   *
   * @param env variable map
   * @return the validated configuration
   */
  public static BetterStackConfig fromEnv(Map<String, String> env) {
    return builder().fromEnv(EnvReader.of(env)).build();
  }

  /**
   * Whether the ingest side ({@code url} and {@code sourceToken}) is configured.
   *
   * @return true with ingest credentials
   */
  public boolean hasIngest() {
    return url != null && sourceToken != null;
  }

  /**
   * Whether the SQL query side ({@code queryUrl}, {@code queryUsername} and {@code queryPassword})
   * is configured.
   *
   * @return true with query credentials
   */
  public boolean hasQuery() {
    return queryUrl != null && queryUsername != null && queryPassword != null;
  }

  /**
   * Checks the credential sets, URLs, the source table, timeouts, TLS and proxy.
   *
   * @throws ConfigException when a rule is violated
   */
  public void validate() {
    boolean anyIngest = url != null || sourceToken != null;
    boolean anyQuery = queryUrl != null || queryUsername != null || queryPassword != null;
    if (!anyIngest && !anyQuery) {
      throw new ConfigException(
          "config: set url + sourceToken (ingest) and/or queryUrl + queryUsername + queryPassword"
              + " (SQL query)");
    }
    List<String> problems = new ArrayList<>();
    if (anyIngest && url == null) {
      problems.add("url (the source's ingesting host) is required with sourceToken");
    }
    if (anyIngest && sourceToken == null) {
      problems.add("sourceToken is required with url");
    }
    if (anyQuery && !hasQuery()) {
      problems.add("queryUrl, queryUsername and queryPassword must be given together");
    }
    if (url != null && !isHttpUrl(url)) {
      problems.add("url must use http or https and include a host");
    }
    if (queryUrl != null && !isHttpUrl(queryUrl)) {
      problems.add("queryUrl must use http or https and include a host");
    }
    if (sourceToken != null && sourceToken.reveal().trim().isEmpty()) {
      problems.add("sourceToken must not be blank");
    }
    if (queryPassword != null && queryPassword.reveal().isEmpty()) {
      problems.add("queryPassword must not be blank");
    }
    if (sourceTable != null && !isValidIdentifier(sourceTable)) {
      problems.add("sourceTable must match ^[A-Za-z_][A-Za-z0-9_]*$");
    }
    if (!problems.isEmpty()) {
      throw new ConfigException("config: " + String.join("; ", problems));
    }
    requirePositive("connectTimeout", connectTimeout);
    requirePositive("requestTimeout", requestTimeout);
    requirePositive("totalTimeout", totalTimeout);
    tls.validate();
    if (proxy != null) {
      try {
        URI p = new URI(proxy);
        if (p.getHost() == null) {
          throw new ConfigException("config: proxy must be an absolute URL");
        }
      } catch (URISyntaxException e) {
        throw new ConfigException("config: proxy is not a valid URL");
      }
    }
  }

  private static boolean isHttpUrl(String value) {
    try {
      URI u = new URI(value);
      String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
      return (scheme.equals("http") || scheme.equals("https")) && u.getHost() != null;
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static void requirePositive(String name, Duration d) {
    if (d.isZero() || d.isNegative()) {
      throw new ConfigException("config: " + name + " must be positive");
    }
  }

  /**
   * Transport settings of the ingest side ({@code baseUrl} = {@link #url()}).
   *
   * @return the transport configuration
   * @throws ConfigException without ingest credentials
   */
  public TransportConfig ingestTransportConfig() {
    if (!hasIngest()) {
      throw new ConfigException(
          "config: url (the source's ingesting host) and sourceToken are required for ingestion");
    }
    return transportConfig(url);
  }

  /**
   * Transport settings of the SQL query side ({@code baseUrl} = {@link #queryUrl()}).
   *
   * @return the transport configuration
   * @throws ConfigException without query credentials
   */
  public TransportConfig queryTransportConfig() {
    if (!hasQuery()) {
      throw new ConfigException(
          "config: queryUrl, queryUsername and queryPassword are required for SQL queries");
    }
    return transportConfig(queryUrl);
  }

  private TransportConfig transportConfig(String baseUrl) {
    return TransportConfig.builder(baseUrl)
        .connectTimeout(connectTimeout)
        .requestTimeout(requestTimeout)
        .totalTimeout(totalTimeout)
        .retry(retry)
        .tls(tls)
        .proxy(proxy)
        .headers(headers)
        .userAgentSuffix(userAgentSuffix)
        .hooks(hooks)
        .logger(logger)
        .knownSecrets(this::knownSecrets)
        .build();
  }

  /**
   * Credential values to redact from error messages and logs (source token, query password and the
   * Basic credential).
   *
   * @return secret values
   */
  public List<String> knownSecrets() {
    List<String> out = new ArrayList<>();
    for (Secret s : new Secret[] {sourceToken, queryPassword}) {
      if (s != null && !s.isCleared() && !s.isEmpty()) {
        out.add(s.reveal());
      }
    }
    basicCredentials().ifPresent(out::add);
    return out;
  }

  /**
   * Returns base64({@code username:password}) for HTTP Basic.
   *
   * @return the credential, or empty without query credentials
   */
  public Optional<String> basicCredentials() {
    if (queryUsername == null || queryPassword == null || queryPassword.isCleared()) {
      return Optional.empty();
    }
    String pair = queryUsername + ":" + queryPassword.reveal();
    return Optional.of(Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Returns the ingest base URL (the source's ingesting host).
   *
   * @return the URL, or empty for a query-only client
   */
  public Optional<String> url() {
    return Optional.ofNullable(url);
  }

  /**
   * Returns the source token.
   *
   * @return the token, or empty
   */
  public Optional<Secret> sourceToken() {
    return Optional.ofNullable(sourceToken);
  }

  /**
   * Returns the SQL query base URL.
   *
   * @return the URL, or empty for an ingest-only client
   */
  public Optional<String> queryUrl() {
    return Optional.ofNullable(queryUrl);
  }

  /**
   * Returns the SQL connection username.
   *
   * @return the username, or empty
   */
  public Optional<String> queryUsername() {
    return Optional.ofNullable(queryUsername);
  }

  /**
   * Returns the SQL connection password.
   *
   * @return the password, or empty
   */
  public Optional<Secret> queryPassword() {
    return Optional.ofNullable(queryPassword);
  }

  /**
   * Returns the default collection ({@code t1234_myapp}) used by {@code search}, {@code
   * searchLogs}, {@code tail} and {@code ping}.
   *
   * @return the collection, or empty
   */
  public Optional<String> sourceTable() {
    return Optional.ofNullable(sourceTable);
  }

  /**
   * Returns the ingest body encoding (default {@link IngestEncoding#JSON}).
   *
   * @return the encoding
   */
  public IngestEncoding encoding() {
    return encoding;
  }

  /**
   * Returns the connect timeout.
   *
   * @return the timeout
   */
  public Duration connectTimeout() {
    return connectTimeout;
  }

  /**
   * Returns the per-attempt timeout.
   *
   * @return the timeout
   */
  public Duration requestTimeout() {
    return requestTimeout;
  }

  /**
   * Returns the total timeout per call including retries.
   *
   * @return the timeout
   */
  public Duration totalTimeout() {
    return totalTimeout;
  }

  /**
   * Returns the retry policy.
   *
   * @return the policy
   */
  public RetryPolicy retry() {
    return retry;
  }

  /**
   * Returns the TLS options.
   *
   * @return the options
   */
  public TlsOptions tls() {
    return tls;
  }

  /**
   * Returns the explicit proxy URL.
   *
   * @return the proxy, or empty
   */
  public Optional<String> proxy() {
    return Optional.ofNullable(proxy);
  }

  /**
   * Returns the extra request headers.
   *
   * @return immutable map
   */
  public Map<String, String> headers() {
    return headers;
  }

  /**
   * Returns the user agent suffix.
   *
   * @return the suffix, or empty
   */
  public Optional<String> userAgentSuffix() {
    return Optional.ofNullable(userAgentSuffix);
  }

  /**
   * Returns the hooks.
   *
   * @return the hooks
   */
  public Hooks hooks() {
    return hooks;
  }

  /**
   * Returns the logger.
   *
   * @return the logger
   */
  public Logger logger() {
    return logger;
  }

  /**
   * Returns a builder pre-filled with this configuration.
   *
   * @return a new builder
   */
  public Builder toBuilder() {
    Builder b = new Builder();
    b.url = url;
    b.sourceToken = sourceToken;
    b.queryUrl = queryUrl;
    b.queryUsername = queryUsername;
    b.queryPassword = queryPassword;
    b.sourceTable = sourceTable;
    b.encoding = encoding;
    b.connectTimeout = connectTimeout;
    b.requestTimeout = requestTimeout;
    b.totalTimeout = totalTimeout;
    b.retry = retry;
    b.tls = tls;
    b.proxy = proxy;
    b.headers.putAll(headers);
    b.userAgentSuffix = userAgentSuffix;
    b.hooks = hooks;
    b.logger = logger;
    return b;
  }

  @Override
  public String toString() {
    return "BetterStackConfig{url="
        + (url == null ? "null" : Redaction.redactUrl(url))
        + ", sourceToken="
        + (sourceToken == null ? "null" : Redaction.REDACTED)
        + ", queryUrl="
        + (queryUrl == null ? "null" : Redaction.redactUrl(queryUrl))
        + ", queryUsername="
        + queryUsername
        + ", queryPassword="
        + (queryPassword == null ? "null" : Redaction.REDACTED)
        + ", sourceTable="
        + sourceTable
        + ", encoding="
        + encoding.wireValue()
        + ", connectTimeout="
        + connectTimeout
        + ", requestTimeout="
        + requestTimeout
        + ", totalTimeout="
        + totalTimeout
        + ", retry="
        + retry
        + ", tls="
        + tls
        + ", proxy="
        + (proxy == null ? "null" : Redaction.redactUrl(proxy))
        + ", headers="
        + Redaction.redactHeaders(headers)
        + ", userAgentSuffix="
        + userAgentSuffix
        + "}";
  }

  /**
   * Builder of the configuration.
   *
   * <p>Unset fields take the environment (when {@link #fromEnv(EnvReader)} is called) or the
   * default.
   */
  public static final class Builder {
    private String url;
    private Secret sourceToken;
    private String queryUrl;
    private String queryUsername;
    private Secret queryPassword;
    private String sourceTable;
    private IngestEncoding encoding;
    private Duration connectTimeout;
    private Duration requestTimeout;
    private Duration totalTimeout;
    private RetryPolicy retry;
    private TlsOptions tls;
    private String proxy;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private String userAgentSuffix;
    private Hooks hooks;
    private Logger logger;

    private Builder() {}

    private static String[] vendor(List<String> names) {
      return names.toArray(new String[0]);
    }

    /**
     * Fills every field that was not set explicitly from the environment: {@code
     * GROW_BETTER_STACK_LOGS_<NAME>} first, then the vendor variables ({@code
     * BETTERSTACK_INGESTING_URL}, {@code BETTERSTACK_INGESTING_HOST}, {@code
     * BETTERSTACK_SOURCE_TOKEN}, {@code BETTERSTACK_QUERY_URL}, {@code BETTERSTACK_QUERY_HOST},
     * {@code BETTERSTACK_QUERY_USERNAME}, {@code BETTERSTACK_QUERY_PASSWORD}, {@code
     * BETTERSTACK_SOURCE_TABLE}).
     *
     * @param env environment reader
     * @return this builder
     * @throws ConfigException when a variable holds an invalid value
     */
    public Builder fromEnv(EnvReader env) {
      if (url == null) {
        url = env.string("URL", vendor(URL_VENDOR_VARS)).orElse(null);
      }
      if (sourceToken == null) {
        sourceToken =
            env.string("SOURCE_TOKEN", vendor(SOURCE_TOKEN_VENDOR_VARS))
                .map(Secret::of)
                .orElse(null);
      }
      if (queryUrl == null) {
        queryUrl = env.string("QUERY_URL", vendor(QUERY_URL_VENDOR_VARS)).orElse(null);
      }
      if (queryUsername == null) {
        queryUsername =
            env.string("QUERY_USERNAME", vendor(QUERY_USERNAME_VENDOR_VARS)).orElse(null);
      }
      if (queryPassword == null) {
        queryPassword =
            env.string("QUERY_PASSWORD", vendor(QUERY_PASSWORD_VENDOR_VARS))
                .map(Secret::of)
                .orElse(null);
      }
      if (sourceTable == null) {
        sourceTable = env.string("SOURCE_TABLE", vendor(SOURCE_TABLE_VENDOR_VARS)).orElse(null);
      }
      if (encoding == null && env.string("ENCODING").isPresent()) {
        encoding =
            IngestEncoding.parse(env.string("ENCODING").get())
                .orElseThrow(
                    () ->
                        new ConfigException(
                            "config: invalid value in "
                                + EnvReader.DEFAULT_PREFIX
                                + "ENCODING (expected json, ndjson or msgpack)"));
      }
      if (connectTimeout == null) {
        connectTimeout = env.duration("CONNECT_TIMEOUT").orElse(null);
      }
      if (requestTimeout == null) {
        requestTimeout = env.duration("REQUEST_TIMEOUT").orElse(null);
      }
      if (totalTimeout == null) {
        totalTimeout = env.duration("TOTAL_TIMEOUT").orElse(null);
      }
      if (retry == null && env.integer("MAX_RETRIES").isPresent()) {
        int n = env.integer("MAX_RETRIES").getAsInt();
        if (n < 0) {
          String variable = EnvReader.DEFAULT_PREFIX + "MAX_RETRIES";
          throw new ConfigException("config: invalid value in " + variable + " (expected >= 0)");
        }
        retry = RetryPolicy.builder().maxRetries(n).build();
      }
      if (tls == null) {
        TlsOptions.Builder t = TlsOptions.builder();
        env.string("CA_CERT").ifPresent(p -> t.caCertPath(Paths.get(p)));
        env.string("CLIENT_CERT").ifPresent(p -> t.clientCertPath(Paths.get(p)));
        env.string("CLIENT_KEY").ifPresent(p -> t.clientKeyPath(Paths.get(p)));
        env.bool("INSECURE_SKIP_VERIFY").ifPresent(t::insecureSkipVerify);
        tls = t.build();
      }
      if (proxy == null) {
        proxy = env.string("PROXY").orElse(null);
      }
      return this;
    }

    /**
     * Sets the ingest base URL: the source's ingesting host ({@code
     * s1234.eu-nbg-2.betterstackdata.com} becomes {@code https://…}).
     *
     * @param url host or {@code http(s)} URL
     * @return this builder
     */
    public Builder url(String url) {
      this.url = url;
      return this;
    }

    /**
     * Sets the source token (sent as {@code Authorization: Bearer} to the ingesting host only).
     *
     * @param token token
     * @return this builder
     */
    public Builder sourceToken(Secret token) {
      this.sourceToken = token;
      return this;
    }

    /**
     * Sets the source token.
     *
     * @param token token
     * @return this builder
     */
    public Builder sourceToken(String token) {
      this.sourceToken = token == null ? null : Secret.of(token);
      return this;
    }

    /**
     * Sets the SQL query base URL (ClickHouse HTTP interface).
     *
     * @param queryUrl host or {@code http(s)} URL
     * @return this builder
     */
    public Builder queryUrl(String queryUrl) {
      this.queryUrl = queryUrl;
      return this;
    }

    /**
     * Sets the SQL connection username.
     *
     * @param username username
     * @return this builder
     */
    public Builder queryUsername(String username) {
      this.queryUsername = username;
      return this;
    }

    /**
     * Sets the SQL connection password (HTTP Basic, query host only).
     *
     * @param password password
     * @return this builder
     */
    public Builder queryPassword(Secret password) {
      this.queryPassword = password;
      return this;
    }

    /**
     * Sets the SQL connection password.
     *
     * @param password password
     * @return this builder
     */
    public Builder queryPassword(String password) {
      this.queryPassword = password == null ? null : Secret.of(password);
      return this;
    }

    /**
     * Sets the default collection ({@code t1234_myapp}).
     *
     * @param sourceTable identifier
     * @return this builder
     */
    public Builder sourceTable(String sourceTable) {
      this.sourceTable = sourceTable;
      return this;
    }

    /**
     * Sets the ingest body encoding (default {@link IngestEncoding#JSON}).
     *
     * @param encoding encoding
     * @return this builder
     */
    public Builder encoding(IngestEncoding encoding) {
      this.encoding = encoding;
      return this;
    }

    /**
     * Sets the connect timeout (default 10 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder connectTimeout(Duration d) {
      this.connectTimeout = d;
      return this;
    }

    /**
     * Sets the per-attempt timeout (default 30 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder requestTimeout(Duration d) {
      this.requestTimeout = d;
      return this;
    }

    /**
     * Sets the total timeout per call including retries (default 120 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder totalTimeout(Duration d) {
      this.totalTimeout = d;
      return this;
    }

    /**
     * Sets the retry policy (default {@link RetryPolicy#defaults()}).
     *
     * @param retry policy
     * @return this builder
     */
    public Builder retry(RetryPolicy retry) {
      this.retry = retry;
      return this;
    }

    /**
     * Sets the TLS options; environment TLS variables are then ignored.
     *
     * @param tls options
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      this.tls = tls;
      return this;
    }

    /**
     * Sets an explicit proxy URL; environment proxies ({@code HTTPS_PROXY}) are never used.
     *
     * @param proxy proxy URL
     * @return this builder
     */
    public Builder proxy(String proxy) {
      this.proxy = proxy;
      return this;
    }

    /**
     * Adds a header sent with every request to both hosts.
     *
     * @param name header name
     * @param value header value
     * @return this builder
     */
    public Builder header(String name, String value) {
      this.headers.put(name, value);
      return this;
    }

    /**
     * Sets the text appended to the {@code User-Agent}.
     *
     * @param suffix suffix
     * @return this builder
     */
    public Builder userAgentSuffix(String suffix) {
      this.userAgentSuffix = suffix;
      return this;
    }

    /**
     * Sets request hooks.
     *
     * @param hooks hooks
     * @return this builder
     */
    public Builder hooks(Hooks hooks) {
      this.hooks = hooks;
      return this;
    }

    /**
     * Sets the logger (default silent).
     *
     * @param logger logger
     * @return this builder
     */
    public Builder logger(Logger logger) {
      this.logger = logger;
      return this;
    }

    /**
     * Builds and validates the configuration.
     *
     * @return the configuration
     * @throws ConfigException when a rule is violated
     */
    public BetterStackConfig build() {
      BetterStackConfig c = new BetterStackConfig(this);
      c.validate();
      return c;
    }
  }
}
