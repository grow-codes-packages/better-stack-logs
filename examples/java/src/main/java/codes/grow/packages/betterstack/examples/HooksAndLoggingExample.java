// Created by sdk-kit 0.1.0; owned by this repository.
package codes.grow.packages.betterstack.examples;

import codes.grow.packages.betterstack.BetterStackClient;
import codes.grow.packages.betterstack.BetterStackLogClientOptions;
import codes.grow.packages.betterstack.IngestEncoding;
import codes.grow.packages.betterstack.LogProtocol;
import codes.grow.packages.betterstack.core.EnvReader;
import codes.grow.packages.betterstack.core.Hooks;
import codes.grow.packages.betterstack.core.Logger;
import codes.grow.packages.betterstack.core.RetryPolicy;
import codes.grow.packages.betterstack.logging.BatchOptions;
import codes.grow.packages.betterstack.logging.BatchingLogClient;
import codes.grow.packages.betterstack.logging.GrowJulHandler;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;

/**
 * Request hooks, an SDK logger, retries and timeouts, MessagePack ingest, the OTLP exporter and the
 * {@code java.util.logging} handler. URLs and headers in hooks are redacted.
 */
public final class HooksAndLoggingExample {
  private HooksAndLoggingExample() {}

  /**
   * Runs the example.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    Hooks hooks =
        new Hooks() {
          @Override
          public void onRequest(RequestInfo info) {
            System.out.println("-> " + info.method() + " " + info.url() + " #" + info.attempt());
          }

          @Override
          public void onResponse(ResponseInfo info) {
            System.out.println("<- " + info.status() + " in " + info.durationMs() + " ms");
          }

          @Override
          public void onError(ErrorInfo info) {
            System.out.println("!! " + info.error().code() + " retry=" + info.willRetry());
          }
        };
    Logger logger =
        new Logger() {
          @Override
          public void debug(String message, Map<String, Object> fields) {
            System.out.println("debug: " + message + " " + fields);
          }
        };
    try (var client =
        BetterStackClient.builder()
            .fromEnv(EnvReader.system()) // explicit values below win over the environment
            .encoding(IngestEncoding.MSGPACK)
            .connectTimeout(Duration.ofSeconds(2))
            .requestTimeout(Duration.ofSeconds(5))
            .totalTimeout(Duration.ofSeconds(20))
            .retry(RetryPolicy.builder().maxRetries(5).initialBackoff(Duration.ofMillis(200)).build())
            .userAgentSuffix("hooks-example/1.0")
            .hooks(hooks)
            .logger(logger)
            .build()) {
      System.out.println(client.health());

      BatchingLogClient msgpack =
          client.logClient(
              BetterStackLogClientOptions.builder()
                  .batchOptions(BatchOptions.builder().maxBatchSize(100).flushInterval(Duration.ofSeconds(2)).build())
                  .build());
      java.util.logging.Logger jul = java.util.logging.Logger.getLogger("example");
      jul.addHandler(new GrowJulHandler(msgpack));
      jul.log(Level.WARNING, "sent as MessagePack through java.util.logging");

      BatchingLogClient otlp =
          client.logClient(BetterStackLogClientOptions.builder().protocol(LogProtocol.OTLP).build());
      otlp.logger("otlp").info("sent as OTLP/HTTP protobuf to /v1/logs");
    } // close() flushes both log clients
  }
}
