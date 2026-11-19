package codes.grow.packages.betterstack;

import codes.grow.packages.betterstack.core.BetterStackException;
import codes.grow.packages.betterstack.core.HttpTransport;
import codes.grow.packages.betterstack.core.Json;
import codes.grow.packages.betterstack.core.TransportRequest;
import codes.grow.packages.betterstack.core.TransportResponse;
import codes.grow.packages.betterstack.core.ValidationException;
import codes.grow.packages.betterstack.logging.ExportOptions;
import codes.grow.packages.betterstack.logging.ExportResult;
import codes.grow.packages.betterstack.logging.LogExporter;
import codes.grow.packages.betterstack.logging.LogRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@link LogExporter} that sends OTLP/HTTP logs to the source's ingesting host ({@code POST
 * {url}/v1/logs}, {@code Authorization: Bearer <source token>}), protobuf by default or OTLP/JSON.
 *
 * <p>Records are grouped by resource; batches are split so that no request exceeds {@code
 * maxRequestBytes}. A {@code partial_success} answer counts its records as rejected. Exports are
 * retried like idempotent calls (duplicates are possible after an ambiguous failure). Get one from
 * {@link BetterStackClient#otlpExporter()}.
 */
public final class BetterStackOtlpLogExporter implements LogExporter {
  /** OTLP logs path on the ingesting host. */
  public static final String OTLP_LOGS_PATH = "/v1/logs";

  private final HttpTransport transport;
  private final BetterStackConfig config;
  private final OtlpProtocol protocol;
  private final String scopeName;
  private final int maxRequest;

  BetterStackOtlpLogExporter(
      HttpTransport transport,
      BetterStackConfig config,
      BetterStackOtlpLogExporterOptions options) {
    this.transport = transport;
    this.config = config;
    this.protocol = options.protocol();
    this.scopeName = options.scopeName();
    if (options.maxRequestBytes() < 1
        || options.maxRequestBytes() > BetterStackLogExporter.MAX_REQUEST_BYTES) {
      throw new ValidationException("logs.otlp: maxRequestBytes must be between 1 and 10 MiB");
    }
    this.maxRequest = options.maxRequestBytes();
  }

  /**
   * Returns the payload format.
   *
   * @return the protocol
   */
  public OtlpProtocol protocol() {
    return protocol;
  }

  /**
   * Returns the instrumentation scope name.
   *
   * @return the name
   */
  public String scopeName() {
    return scopeName;
  }

  /**
   * Encodes records as one request body (protobuf bytes or OTLP/JSON text).
   *
   * @param records records
   * @return the body
   */
  public byte[] encode(List<LogRecord> records) {
    return protocol == OtlpProtocol.PROTOBUF
        ? OtlpLogs.encodeProtobuf(records, scopeName)
        : Json.encode(OtlpLogs.jsonRequest(records, scopeName));
  }

  /** Chunks plus the number of records that cannot be sent. */
  private record Chunks(List<List<LogRecord>> chunks, int oversized) {}

  private Chunks chunks(List<LogRecord> records) {
    List<List<LogRecord>> out = new ArrayList<>();
    List<LogRecord> current = new ArrayList<>();
    long bytes = 0;
    int oversized = 0;
    for (LogRecord r : records) {
      // a one-record request over-estimates the record's share (wrappers counted every time)
      long size;
      try {
        size = encode(List.of(r)).length;
      } catch (RuntimeException e) {
        size = Long.MAX_VALUE;
      }
      if (size > maxRequest) {
        oversized++;
        continue;
      }
      if (!current.isEmpty() && bytes + size > maxRequest) {
        out.add(current);
        current = new ArrayList<>();
        bytes = 0;
      }
      current.add(r);
      bytes += size;
    }
    if (!current.isEmpty()) {
      out.add(current);
    }
    return new Chunks(out, oversized);
  }

  /**
   * Sends the records; the future never fails for service errors (see {@link LogExporter}).
   *
   * @param records records
   * @param options gzip flag and time budget
   * @return the result
   */
  @Override
  public CompletableFuture<ExportResult> export(List<LogRecord> records, ExportOptions options) {
    Chunks c = chunks(records);
    State start = new State(0, c.oversized(), null);
    if (c.oversized() > 0) {
      start =
          new State(
              0,
              c.oversized(),
              new ValidationException(
                  "logs.otlp: "
                      + c.oversized()
                      + " record(s) dropped: not encodable or larger than "
                      + maxRequest
                      + " bytes"));
    }
    CompletableFuture<State> chain = CompletableFuture.completedFuture(start);
    for (List<LogRecord> chunk : c.chunks()) {
      chain =
          chain.thenCompose(
              st -> {
                if (st.failed) {
                  return CompletableFuture.completedFuture(st);
                }
                return sendAsync(chunk, options.gzip())
                    .handle(
                        (partial, err) -> {
                          if (err != null) {
                            return new State(
                                st.accepted,
                                records.size() - st.accepted,
                                BetterStackErrors.mapIngestError(err, "logs.otlp"),
                                true);
                          }
                          long bad =
                              Math.min(partial.map(p -> p.rejected()).orElse(0L), chunk.size());
                          if (bad == 0) {
                            return new State(st.accepted + chunk.size(), st.rejected, st.error);
                          }
                          String msg = partial.map(p -> p.message()).orElse("");
                          return new State(
                              st.accepted + chunk.size() - (int) bad,
                              st.rejected + (int) bad,
                              new ValidationException(
                                  "logs.otlp: the service rejected "
                                      + bad
                                      + " record(s)"
                                      + (msg.isEmpty() ? "" : ": " + msg)));
                        });
              });
    }
    return chain.thenApply(
        st ->
            st.error == null
                ? new ExportResult(st.accepted, st.rejected, Optional.empty())
                : ExportResult.failure(st.accepted, st.rejected, st.error));
  }

  private static final class State {
    final int accepted;
    final int rejected;
    final BetterStackException error;
    final boolean failed;

    State(int accepted, int rejected, BetterStackException error) {
      this(accepted, rejected, error, false);
    }

    State(int accepted, int rejected, BetterStackException error, boolean failed) {
      this.accepted = accepted;
      this.rejected = rejected;
      this.error = error;
      this.failed = failed;
    }
  }

  /**
   * Posts one OTLP request for {@code records} (no splitting).
   *
   * @param records records
   * @param gzip gzip-compress the body
   * @return the partial success when the service reports rejected records
   * @throws BetterStackException mapped like the ingest API (403 authentication, 402 quota)
   */
  public Optional<OtlpLogs.PartialSuccess> send(List<LogRecord> records, boolean gzip) {
    return HttpTransport.await(sendAsync(records, gzip), "logs.otlp");
  }

  /**
   * Asynchronous {@link #send(List, boolean)}.
   *
   * @param records records
   * @param gzip gzip-compress the body
   * @return a future with the partial success, if any
   */
  public CompletableFuture<Optional<OtlpLogs.PartialSuccess>> sendAsync(
      List<LogRecord> records, boolean gzip) {
    byte[] body;
    try {
      body = encode(records);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(
          new ValidationException("logs.otlp: records cannot be encoded", e));
    }
    TransportRequest.Builder b =
        TransportRequest.post(OTLP_LOGS_PATH)
            .header("Authorization", "Bearer " + config.sourceToken().orElseThrow().reveal())
            .header("Accept", protocol.contentType())
            .idempotent(true)
            .operation("logs.otlp");
    if (gzip) {
      b.header("Content-Encoding", "gzip")
          .body(BetterStackLogExporter.gzip(body), protocol.contentType());
    } else {
      b.body(body, protocol.contentType());
    }
    return BetterStackErrors.mapFailure(
        transport.sendAsync(b.build()).thenApply(this::partial),
        e -> BetterStackErrors.mapIngestError(e, "logs.otlp"));
  }

  private Optional<OtlpLogs.PartialSuccess> partial(TransportResponse r) {
    return protocol == OtlpProtocol.PROTOBUF
        ? OtlpLogs.parsePartialSuccess(r.body())
        : OtlpLogs.parsePartialSuccessJson(r.body());
  }

  /**
   * Nothing to release; the client owns the transport.
   *
   * @return a completed future
   */
  @Override
  public CompletableFuture<Void> shutdown() {
    return CompletableFuture.completedFuture(null);
  }
}
