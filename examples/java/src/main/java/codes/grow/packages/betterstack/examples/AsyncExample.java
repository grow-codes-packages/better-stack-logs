// Created by sdk-kit 0.1.0; owned by this repository.
package codes.grow.packages.betterstack.examples;

import codes.grow.packages.betterstack.BetterStackClient;
import codes.grow.packages.betterstack.QueryOptions;
import codes.grow.packages.betterstack.SearchLogsOptions;
import codes.grow.packages.betterstack.core.HealthStatus;
import codes.grow.packages.betterstack.logging.LogSearchResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** The {@code …Async} methods: run calls concurrently, combine futures and cancel a slow one. */
public final class AsyncExample {
  private AsyncExample() {}

  /**
   * Runs the example.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    try (var client = BetterStackClient.fromEnv()) {
      CompletableFuture<HealthStatus> health = client.healthAsync();
      CompletableFuture<Duration> ping = client.pingAsync();
      CompletableFuture.allOf(health, ping.exceptionally(e -> null)).join();
      System.out.println("health: " + health.join());
      ping.whenComplete(
          (latency, error) ->
              System.out.println(error == null ? "ping: " + latency : "ping failed: " + error));

      client
          .serverVersionAsync()
          .exceptionally(e -> "unsupported (Better Stack is a SaaS without a version)")
          .thenAccept(v -> System.out.println("server version: " + v))
          .join();

      if (client.config().hasQuery()) {
        CompletableFuture<LogSearchResult> recent =
            client.query().searchLogsAsync(SearchLogsOptions.builder().limit(5).build());
        CompletableFuture<List<Map<String, Object>>> one =
            client.query().queryAsync("SELECT 1 AS one", Map.of(), QueryOptions.defaults());
        recent
            .thenCombine(one, (r, o) -> r.records().size() + " recent records, " + o)
            .exceptionally(e -> "query failed: " + e.getCause())
            .thenAccept(System.out::println)
            .join();

        // Cancelling a future aborts the HTTP exchange and any pending retry.
        CompletableFuture<LogSearchResult> slow =
            client.query().searchLogsAsync(SearchLogsOptions.builder().limit(10_000).build());
        slow.cancel(true);
        System.out.println("search cancelled: " + slow.isCancelled());
      }
    }
  }
}
