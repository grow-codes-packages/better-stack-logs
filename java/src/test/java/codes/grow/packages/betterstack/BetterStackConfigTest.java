package codes.grow.packages.betterstack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.betterstack.core.ConfigException;
import codes.grow.packages.betterstack.core.EnvReader;
import codes.grow.packages.betterstack.core.Hooks;
import codes.grow.packages.betterstack.core.Logger;
import codes.grow.packages.betterstack.core.RetryPolicy;
import codes.grow.packages.betterstack.core.Secret;
import codes.grow.packages.betterstack.core.TlsOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BetterStackConfigTest {
  private static final String P = EnvReader.DEFAULT_PREFIX;

  private static BetterStackConfig.Builder ingest() {
    return BetterStackConfig.builder().url("http://ingest:8080").sourceToken("src-token-1");
  }

  @Test
  void normalizesHostsAndUrls() {
    assertThat(BetterStackConfig.normalizeBaseUrl(null)).isNull();
    assertThat(BetterStackConfig.normalizeBaseUrl("  ")).isNull();
    assertThat(BetterStackConfig.normalizeBaseUrl("s1.eu-nbg-2.betterstackdata.com"))
        .isEqualTo("https://s1.eu-nbg-2.betterstackdata.com");
    assertThat(BetterStackConfig.normalizeBaseUrl("host:8080//")).isEqualTo("https://host:8080");
    assertThat(BetterStackConfig.normalizeBaseUrl(" http://x:1/ ")).isEqualTo("http://x:1");
    assertThat(BetterStackConfig.isValidIdentifier("t1_app")).isTrue();
    assertThat(BetterStackConfig.isValidIdentifier("1t")).isFalse();
    assertThat(BetterStackConfig.isValidIdentifier(null)).isFalse();
  }

  @Test
  void ingestOnlyQueryOnlyAndBoth() {
    BetterStackConfig i = ingest().build();
    assertThat(i.hasIngest()).isTrue();
    assertThat(i.hasQuery()).isFalse();
    assertThat(i.url()).contains("http://ingest:8080");
    assertThat(i.encoding()).isEqualTo(IngestEncoding.JSON);
    assertThat(i.ingestTransportConfig().baseUrl()).isEqualTo("http://ingest:8080");
    assertThatThrownBy(i::queryTransportConfig).isInstanceOf(ConfigException.class);
    assertThat(i.basicCredentials()).isEmpty();
    BetterStackConfig q =
        BetterStackConfig.builder()
            .queryUrl("eu-nbg-2-connect.betterstackdata.com")
            .queryUsername(" user ")
            .queryPassword("pw")
            .sourceTable("t1_app")
            .build();
    assertThat(q.hasIngest()).isFalse();
    assertThat(q.hasQuery()).isTrue();
    assertThat(q.queryUrl()).contains("https://eu-nbg-2-connect.betterstackdata.com");
    assertThat(q.queryUsername()).contains("user");
    assertThat(q.sourceTable()).contains("t1_app");
    assertThatThrownBy(q::ingestTransportConfig).isInstanceOf(ConfigException.class);
    assertThat(q.queryTransportConfig().baseUrl()).startsWith("https://eu-nbg-2");
    String basic =
        new String(
            Base64.getDecoder().decode(q.basicCredentials().orElseThrow()), StandardCharsets.UTF_8);
    assertThat(basic).isEqualTo("user:pw");
    assertThat(q.knownSecrets()).contains("pw", q.basicCredentials().orElseThrow());
    BetterStackConfig both =
        ingest()
            .queryUrl("http://q:8123")
            .queryUsername("u")
            .queryPassword(Secret.of("p"))
            .sourceToken(Secret.of("s"))
            .encoding(IngestEncoding.MSGPACK)
            .build();
    assertThat(both.hasIngest() && both.hasQuery()).isTrue();
    assertThat(both.knownSecrets()).contains("s", "p");
    assertThat(both.toBuilder().build().toString()).isEqualTo(both.toString());
  }

  @Test
  void validationRules() {
    assertThatThrownBy(() -> BetterStackConfig.builder().build())
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("url + sourceToken");
    assertThatThrownBy(() -> BetterStackConfig.builder().url("http://x").build())
        .hasMessageContaining("sourceToken is required");
    assertThatThrownBy(() -> BetterStackConfig.builder().sourceToken("t").build())
        .hasMessageContaining("url (the source's ingesting host) is required");
    assertThatThrownBy(() -> BetterStackConfig.builder().queryUrl("http://q").build())
        .hasMessageContaining("must be given together");
    assertThatThrownBy(() -> ingest().url("ftp://x").build())
        .hasMessageContaining("url must use http");
    assertThatThrownBy(
            () ->
                ingest().queryUrl("http://bad host").queryUsername("u").queryPassword("p").build())
        .hasMessageContaining("queryUrl must use http");
    assertThatThrownBy(() -> ingest().sourceToken("  ").build())
        .hasMessageContaining("sourceToken must not be blank");
    assertThatThrownBy(
            () -> ingest().queryUrl("http://q").queryUsername("u").queryPassword("").build())
        .hasMessageContaining("queryPassword must not be blank");
    assertThatThrownBy(() -> ingest().sourceTable("bad-table").build())
        .hasMessageContaining("sourceTable must match");
    assertThatThrownBy(() -> ingest().connectTimeout(Duration.ZERO).build())
        .hasMessageContaining("connectTimeout");
    assertThatThrownBy(() -> ingest().requestTimeout(Duration.ofSeconds(-1)).build())
        .hasMessageContaining("requestTimeout");
    assertThatThrownBy(() -> ingest().totalTimeout(Duration.ZERO).build())
        .hasMessageContaining("totalTimeout");
    assertThatThrownBy(() -> ingest().proxy("nohost").build()).hasMessageContaining("absolute");
    assertThatThrownBy(() -> ingest().proxy("http://bad host").build())
        .hasMessageContaining("not a valid URL");
    assertThat(ingest().proxy("http://proxy:3128").build().proxy()).contains("http://proxy:3128");
  }

  @Test
  void core_config_env_precedence() {
    Map<String, String> env = new HashMap<>();
    env.put("BETTERSTACK_INGESTING_HOST", "vendor-host:8080");
    env.put("BETTERSTACK_SOURCE_TOKEN", "vendor-token");
    env.put("BETTERSTACK_QUERY_HOST", "query-host:8123");
    env.put("BETTERSTACK_QUERY_USERNAME", "vendor-user");
    env.put("BETTERSTACK_QUERY_PASSWORD", "vendor-pass");
    env.put("BETTERSTACK_SOURCE_TABLE", "t1_vendor");
    BetterStackConfig vendor = BetterStackConfig.fromEnv(env);
    assertThat(vendor.url()).contains("https://vendor-host:8080");
    assertThat(vendor.queryUrl()).contains("https://query-host:8123");
    assertThat(vendor.sourceToken().orElseThrow().reveal()).isEqualTo("vendor-token");
    assertThat(vendor.queryUsername()).contains("vendor-user");
    assertThat(vendor.sourceTable()).contains("t1_vendor");

    env.put("BETTERSTACK_INGESTING_URL", "http://vendor-url:8080");
    env.put(P + "SOURCE_TOKEN", "grow-token");
    env.put(P + "QUERY_URL", "http://grow-query:8123");
    env.put(P + "SOURCE_TABLE", "t1_grow");
    env.put(P + "ENCODING", " NDJSON ");
    env.put(P + "CONNECT_TIMEOUT", "2s");
    env.put(P + "REQUEST_TIMEOUT", "3000");
    env.put(P + "TOTAL_TIMEOUT", "1m");
    env.put(P + "MAX_RETRIES", "5");
    env.put(P + "INSECURE_SKIP_VERIFY", "true");
    env.put(P + "PROXY", "http://proxy:3128");
    BetterStackConfig grow = BetterStackConfig.fromEnv(env);
    assertThat(grow.url()).contains("http://vendor-url:8080");
    assertThat(grow.sourceToken().orElseThrow().reveal()).isEqualTo("grow-token");
    assertThat(grow.queryUrl()).contains("http://grow-query:8123");
    assertThat(grow.sourceTable()).contains("t1_grow");
    assertThat(grow.encoding()).isEqualTo(IngestEncoding.NDJSON);
    assertThat(grow.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(grow.requestTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(grow.totalTimeout()).isEqualTo(Duration.ofMinutes(1));
    assertThat(grow.retry().maxRetries()).isEqualTo(5);
    assertThat(grow.tls().insecureSkipVerify()).isTrue();
    assertThat(grow.proxy()).contains("http://proxy:3128");

    BetterStackConfig explicit =
        BetterStackConfig.builder()
            .url("http://explicit:1")
            .sourceTable("t1_explicit")
            .encoding(IngestEncoding.JSON)
            .retry(RetryPolicy.builder().maxRetries(1).build())
            .tls(TlsOptions.defaults())
            .connectTimeout(Duration.ofSeconds(9))
            .requestTimeout(Duration.ofSeconds(9))
            .totalTimeout(Duration.ofSeconds(90))
            .proxy("http://p:1")
            .fromEnv(EnvReader.of(env))
            .build();
    assertThat(explicit.url()).contains("http://explicit:1");
    assertThat(explicit.sourceTable()).contains("t1_explicit");
    assertThat(explicit.encoding()).isEqualTo(IngestEncoding.JSON);
    assertThat(explicit.retry().maxRetries()).isEqualTo(1);
    assertThat(explicit.connectTimeout()).isEqualTo(Duration.ofSeconds(9));
    assertThat(explicit.proxy()).contains("http://p:1");

    env.put(P + "ENCODING", "xml");
    assertThatThrownBy(() -> BetterStackConfig.fromEnv(env))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(P + "ENCODING")
        .hasMessageNotContaining("xml");
    env.put(P + "ENCODING", "json");
    env.put(P + "MAX_RETRIES", "-1");
    assertThatThrownBy(() -> BetterStackConfig.fromEnv(env))
        .hasMessageContaining(P + "MAX_RETRIES");
    env.put(P + "MAX_RETRIES", "2");
    env.put(P + "CONNECT_TIMEOUT", "soon");
    assertThatThrownBy(() -> BetterStackConfig.fromEnv(env))
        .hasMessageContaining(P + "CONNECT_TIMEOUT")
        .hasMessageNotContaining("soon");
  }

  @Test
  void tlsFromEnvAndSystemEnv() {
    Map<String, String> env = new HashMap<>();
    env.put(P + "URL", "http://x");
    env.put(P + "SOURCE_TOKEN", "t");
    env.put(P + "CA_CERT", "/nonexistent/ca.pem");
    env.put(P + "CLIENT_CERT", "/nonexistent/c.pem");
    env.put(P + "CLIENT_KEY", "/nonexistent/k.pem");
    BetterStackConfig c = BetterStackConfig.builder().fromEnv(EnvReader.of(env)).build();
    assertThat(c.tls().caCertPath()).isPresent();
    assertThat(c.tls().clientCertPath()).isPresent();
    if (System.getenv(P + "URL") == null && System.getenv("BETTERSTACK_INGESTING_URL") == null) {
      assertThatThrownBy(BetterStackConfig::fromEnv).isInstanceOf(ConfigException.class);
    }
  }

  @Test
  void core_redaction_config() {
    Hooks hooks = new Hooks() {};
    Logger logger = new Logger() {};
    BetterStackConfig c =
        ingest()
            .sourceToken("super-secret-source-token")
            .queryUrl("http://user:urlpass@q:8123")
            .queryUsername("u")
            .queryPassword("super-secret-password")
            .header("Authorization", "Bearer header-secret")
            .header("X-Custom", "visible")
            .proxy("http://proxyuser:proxypass@proxy:1")
            .userAgentSuffix("app/1")
            .hooks(hooks)
            .logger(logger)
            .build();
    String s = c.toString();
    assertThat(s)
        .doesNotContain("super-secret-source-token")
        .doesNotContain("super-secret-password")
        .doesNotContain("header-secret")
        .doesNotContain("urlpass")
        .doesNotContain("proxypass")
        .contains("[REDACTED]")
        .contains("visible")
        .contains("encoding=json");
    assertThat(c.hooks()).isSameAs(hooks);
    assertThat(c.logger()).isSameAs(logger);
    assertThat(c.headers()).containsEntry("X-Custom", "visible");
    assertThat(c.userAgentSuffix()).contains("app/1");
    assertThat(c.queryPassword()).isPresent();
    assertThat(
            BetterStackConfig.builder()
                .queryUrl("http://q")
                .queryUsername("u")
                .queryPassword("p")
                .build()
                .toString())
        .contains("url=null")
        .contains("sourceToken=null");
  }

  @Test
  void encodingParsing() {
    assertThat(IngestEncoding.parse("MsgPack")).contains(IngestEncoding.MSGPACK);
    assertThat(IngestEncoding.parse(null)).isEmpty();
    assertThat(IngestEncoding.parse("csv")).isEmpty();
    assertThat(IngestEncoding.NDJSON.contentType()).isEqualTo("application/x-ndjson");
    assertThat(IngestEncoding.MSGPACK.wireValue()).isEqualTo("msgpack");
  }
}
