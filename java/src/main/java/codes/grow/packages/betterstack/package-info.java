/**
 * Grow Codes SDK for Better Stack Logs.
 *
 * <p>Start with {@link codes.grow.packages.betterstack.BetterStackClient#fromEnv()}. The client
 * talks to two hosts with separate credentials: the source's ingesting host ({@link
 * codes.grow.packages.betterstack.BetterStackClient#logClient()}, {@link
 * codes.grow.packages.betterstack.BetterStackLogExporter}, {@link
 * codes.grow.packages.betterstack.BetterStackOtlpLogExporter}) and the SQL query API ({@link
 * codes.grow.packages.betterstack.BetterStackQuery}: {@code query}, {@code searchLogs}, {@code
 * waitForLog}, {@code tail}).
 *
 * <p>Shared building blocks live in {@code core} (errors, transport, redaction); the logging
 * category API in {@code logging}; in-memory fakes and the conformance runner in {@code testing}.
 * Not affiliated with Better Stack, Inc.
 */
package codes.grow.packages.betterstack;
