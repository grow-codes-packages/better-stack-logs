package codes.grow.packages.betterstack.integration;

import codes.grow.packages.betterstack.BetterStackClient;
import codes.grow.packages.betterstack.BetterStackLogClientOptions;
import codes.grow.packages.betterstack.core.EnvReader;
import codes.grow.packages.betterstack.logging.BatchOptions;
import codes.grow.packages.betterstack.logging.BatchingLogClient;
import codes.grow.packages.betterstack.testing.ConformanceRunner;
import codes.grow.packages.betterstack.testing.logging.LoggingTarget;
import java.util.Map;

/**
 * Conformance targets of this service (owned by the service repository).
 *
 * <p>{@code core} is the client with both credential sets (ingest + SQL query); {@code logging} is a
 * batching {@code LogClient} over the HTTP ingest API plus {@code BetterStackQuery} as the query
 * client. Capabilities: {@code query} only. Better Stack exposes no server version ({@code version}
 * is not declared); tail is poll-based and labels are unsupported (see docs/compatibility.md).
 */
final class Factories {
  /** Capabilities the deployment under test offers (see the scenario {@code requires}). */
  static final String[] CAPABILITIES = {"query"};

  /** Service name used as the {@code service.name} resource of conformance records. */
  static final String SERVICE_NAME = "grow-conformance-java";

  private Factories() {}

  static void register(ConformanceRunner.Builder runner, EnvReader env) {
    runner.serviceName(SERVICE_NAME);
    runner.target("core", scenario -> BetterStackClient.builder().fromEnv(env).build());
    runner.target(
        "logging",
        scenario -> {
          BetterStackClient client = BetterStackClient.builder().fromEnv(env).build();
          BatchingLogClient logs =
              client.logClient(
                  BetterStackLogClientOptions.builder()
                      .batchOptions(LoggingTarget.batchOptions(scenario, BatchOptions.defaults()))
                      .resource(
                          Map.of(
                              "service.name", SERVICE_NAME,
                              "deployment.environment", "conformance"))
                      .build());
          return LoggingTarget.of(logs, client.query());
        });
  }
}
