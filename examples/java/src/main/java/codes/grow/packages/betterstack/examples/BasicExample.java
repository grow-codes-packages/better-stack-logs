// Created by sdk-kit 0.1.0; owned by this repository.
package codes.grow.packages.betterstack.examples;

import codes.grow.packages.betterstack.BetterStackClient;
import codes.grow.packages.betterstack.BetterStackLogClientOptions;
import codes.grow.packages.betterstack.BetterStackQuery;
import codes.grow.packages.betterstack.SearchLogsOptions;
import codes.grow.packages.betterstack.core.AuthenticationException;
import codes.grow.packages.betterstack.core.HealthStatus;
import codes.grow.packages.betterstack.logging.BatchingLogClient;
import codes.grow.packages.betterstack.logging.LogRecord;
import codes.grow.packages.betterstack.logging.WaitForLogOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Better Stack Logs tour: configuration from the environment, health and ping, sending logs through
 * the batching log client (Logger facade), reading them back with the SQL query API ({@code
 * waitForLog}, {@code searchLogs}, {@code query} with bound parameters) and error handling.
 *
 * <pre>
 * GROW_BETTER_STACK_LOGS_URL=https://s1234.eu-nbg-2.betterstackdata.com
 * GROW_BETTER_STACK_LOGS_SOURCE_TOKEN=...
 * GROW_BETTER_STACK_LOGS_QUERY_URL=https://eu-nbg-2-connect.betterstackdata.com
 * GROW_BETTER_STACK_LOGS_QUERY_USERNAME=...  GROW_BETTER_STACK_LOGS_QUERY_PASSWORD=...
 * GROW_BETTER_STACK_LOGS_SOURCE_TABLE=t1234_myapp
 * </pre>
 */
public final class BasicExample {
  private BasicExample() {}

  /**
   * Runs the example.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    // A ConfigException names the missing variable, never a value.
    try (BetterStackClient client = BetterStackClient.fromEnv()) {
      HealthStatus health = client.health(); // never throws for an unreachable service
      System.out.println("health: " + health);
      try {
        System.out.println("ping: " + client.ping().toMillis() + " ms");
      } catch (AuthenticationException e) {
        System.out.println("check the source token / query credentials: " + e.getMessage());
        return;
      }

      // Send logs: batched, gzip, password/token/... attributes redacted before they leave.
      BatchingLogClient logs =
          client.logClient(
              BetterStackLogClientOptions.builder()
                  .resource(Map.of("service.name", "example-java", "deployment.environment", "dev"))
                  .build());
      String run = "example-" + System.nanoTime();
      logs.logger("checkout").info("order placed " + run, Map.of("order_id", 42, "password", "x"));
      logs.logger("payments").warn("slow payment " + run, Map.of("ms", 950));
      logs.flush();
      System.out.println("sent: " + logs.stats());

      if (!client.config().hasQuery()) {
        System.out.println("no query credentials: skipping the read-back");
        return;
      }
      BetterStackQuery q = client.query();
      LogRecord rec = q.waitForLog(run, WaitForLogOptions.of(Duration.ofSeconds(60), Duration.ofSeconds(2)));
      System.out.println("found: " + rec.body() + " [" + rec.severityText() + "]");
      for (LogRecord r : q.searchLogs(SearchLogsOptions.builder().contains(run).build()).records()) {
        System.out.println("  " + r.timestamp() + " " + r.body() + " " + r.attributes());
      }
      if (client.config().sourceTable().isPresent()) {
        List<Map<String, Object>> rows =
            q.query(
                "SELECT count() AS c FROM remote("
                    + client.config().sourceTable().get()
                    + "_logs) WHERE position(raw, {run:String}) > 0",
                Map.of("run", run));
        System.out.println("count: " + rows.get(0).get("c"));
      }
    }
  }
}
