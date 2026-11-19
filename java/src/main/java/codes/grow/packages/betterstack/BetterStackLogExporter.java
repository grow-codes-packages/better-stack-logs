package codes.grow.packages.betterstack;

import codes.grow.packages.betterstack.core.BetterStackException;
import codes.grow.packages.betterstack.core.HttpTransport;
import codes.grow.packages.betterstack.core.TransportRequest;
import codes.grow.packages.betterstack.core.ValidationException;
import codes.grow.packages.betterstack.logging.ExportOptions;
import codes.grow.packages.betterstack.logging.ExportResult;
import codes.grow.packages.betterstack.logging.LogExporter;
import codes.grow.packages.betterstack.logging.LogRecord;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

/**
 * {@link LogExporter} for Better Stack's HTTP ingest API ({@code POST {url}/} on the source's
 * ingesting host, {@code Authorization: Bearer <source token>}).
 *
 * <p>Records are mapped with {@link BetterStackEvents#toEvent}, split so that no request exceeds
 * {@code maxRequestBytes} (10 MiB), encoded as JSON, NDJSON or MessagePack and gzip-compressed when
 * the export asks for it. A record larger than {@code maxRecordBytes} is rejected with a {@link
 * ValidationException} before anything is sent. Exports are retried like idempotent calls (429,
 * 5xx, network errors); Better Stack does not deduplicate, so a retry after a lost answer can store
 * a record twice. Get one from {@link BetterStackClient#exporter()} or {@link
 * BetterStackClient#newExporter(BetterStackLogExporterOptions)}.
 */
public final class BetterStackLogExporter implements LogExporter {
  /** Better Stack limit for one request body (measured compressed): 10 MiB. */
  public static final int MAX_REQUEST_BYTES = 10 * 1024 * 1024;

  /** Better Stack limit for one record (uncompressed): 10 MiB. */
  public static final int MAX_RECORD_BYTES = 10 * 1024 * 1024;

  /** Upper bound of the framing bytes per request. */
  static final int FRAME_OVERHEAD = 8;

  private final HttpTransport transport;
  private final BetterStackConfig config;
  private final IngestEncoding encoding;
  private final int maxRequest;
  private final int maxRecord;

  BetterStackLogExporter(
      HttpTransport transport, BetterStackConfig config, BetterStackLogExporterOptions options) {
    this.transport = transport;
    this.config = config;
    this.encoding = options.encoding().orElse(config.encoding());
    this.maxRequest = checkLimit(options.maxRequestBytes(), "maxRequestBytes");
    this.maxRecord = checkLimit(options.maxRecordBytes(), "maxRecordBytes");
  }

  private static int checkLimit(int value, String name) {
    if (value < 1 || value > MAX_REQUEST_BYTES) {
      throw new ValidationException("logs.exporter: " + name + " must be between 1 and 10 MiB");
    }
    return value;
  }

  /**
   * Returns the body encoding.
   *
   * @return the encoding
   */
  public IngestEncoding encoding() {
    return encoding;
  }

  /**
   * Returns the uncompressed size limit of one request.
   *
   * @return bytes
   */
  public int maxRequestBytes() {
    return maxRequest;
  }

  /**
   * Returns the encoded size limit of one record.
   *
   * @return bytes
   */
  public int maxRecordBytes() {
    return maxRecord;
  }

  /**
   * Sends the records in as few requests as the limits allow. The future never fails for service
   * errors: a failing request stops the export and its records and every later record count as
   * rejected ({@link ExportResult#error()} holds the mapped exception).
   *
   * @param records records
   * @param options gzip flag and time budget
   * @return the result
   */
  @Override
  public CompletableFuture<ExportResult> export(List<LogRecord> records, ExportOptions options) {
    List<byte[]> encoded = new ArrayList<>(records.size());
    int oversized = 0;
    for (LogRecord r : records) {
      byte[] b;
      try {
        b = BetterStackEvents.encodeOne(BetterStackEvents.toEvent(r), encoding);
      } catch (RuntimeException e) {
        oversized++;
        continue;
      }
      if (b.length > maxRecord || b.length + FRAME_OVERHEAD > maxRequest) {
        oversized++;
        continue;
      }
      encoded.add(b);
    }
    List<List<byte[]>> chunks = split(encoded, maxRequest);
    int dropped = oversized;
    CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
    for (List<byte[]> chunk : chunks) {
      chain =
          chain.thenCompose(
              accepted ->
                  post(
                          BetterStackEvents.frame(chunk, encoding),
                          encoding.contentType(),
                          options.gzip(),
                          "logs.send")
                      .handle(
                          (v, err) -> {
                            if (err != null) {
                              throw new ExportFailure(accepted, err);
                            }
                            return accepted + chunk.size();
                          }));
    }
    return chain.handle(
        (accepted, err) -> {
          if (err != null) {
            Throwable c = err.getCause() instanceof ExportFailure ? err.getCause() : err;
            if (c instanceof ExportFailure) {
              ExportFailure f = (ExportFailure) c;
              return ExportResult.failure(
                  f.accepted,
                  records.size() - f.accepted,
                  BetterStackErrors.mapIngestError(f.getCause(), "logs.send"));
            }
            return ExportResult.failure(
                0, records.size(), BetterStackErrors.mapIngestError(err, "logs.send"));
          }
          if (dropped > 0) {
            return ExportResult.failure(
                accepted,
                dropped,
                new ValidationException(
                    "logs.send: "
                        + dropped
                        + " record(s) dropped: larger than "
                        + maxRecord
                        + " bytes or not encodable as "
                        + encoding.wireValue()));
          }
          return ExportResult.success(accepted);
        });
  }

  /** Carries the accepted count of a failed export through the future chain. */
  private static final class ExportFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final int accepted;

    ExportFailure(int accepted, Throwable cause) {
      super(null, cause, false, false);
      this.accepted = accepted;
    }
  }

  /** Groups encoded records so that each framed request stays within {@code limit} bytes. */
  static List<List<byte[]>> split(List<byte[]> encoded, int limit) {
    List<List<byte[]>> out = new ArrayList<>();
    List<byte[]> current = new ArrayList<>();
    long size = FRAME_OVERHEAD;
    for (byte[] b : encoded) {
      int n = b.length + 1;
      if (!current.isEmpty() && size + n > limit) {
        out.add(current);
        current = new ArrayList<>();
        size = FRAME_OVERHEAD;
      }
      current.add(b);
      size += n;
    }
    if (!current.isEmpty()) {
      out.add(current);
    }
    return out;
  }

  /**
   * Posts raw events (any JSON objects; {@code dt} may be an {@link java.time.Instant}, an RFC 3339
   * string or UNIX seconds/ms/ns) as one request, gzip-compressed, in the exporter's encoding.
   *
   * @param events events
   * @throws BetterStackException mapped like {@link #export}: 403 authentication, 402 quota (not
   *     retryable), 406/413 validation
   */
  public void sendEvents(List<? extends Map<String, ?>> events) {
    sendEvents(events, SendEventsOptions.defaults());
  }

  /**
   * Posts raw events as one request (no splitting).
   *
   * @param events events
   * @param options encoding and gzip
   * @throws ValidationException when the body exceeds {@code maxRequestBytes}
   */
  public void sendEvents(List<? extends Map<String, ?>> events, SendEventsOptions options) {
    HttpTransport.await(sendEventsAsync(events, options), "logs.sendEvents");
  }

  /**
   * Asynchronous {@link #sendEvents(List, SendEventsOptions)}.
   *
   * @param events events
   * @param options encoding and gzip
   * @return a future completed when the service accepted the request
   */
  public CompletableFuture<Void> sendEventsAsync(
      List<? extends Map<String, ?>> events, SendEventsOptions options) {
    IngestEncoding enc = options.encoding().orElse(encoding);
    byte[] body;
    try {
      body = BetterStackEvents.encode(events, enc);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(
          e instanceof ValidationException
              ? e
              : new ValidationException("logs.sendEvents: an event cannot be encoded", e));
    }
    if (body.length > maxRequest) {
      return CompletableFuture.failedFuture(
          new ValidationException(
              "logs.sendEvents: request body of "
                  + body.length
                  + " bytes exceeds "
                  + maxRequest
                  + " bytes"));
    }
    return BetterStackErrors.mapFailure(
        post(body, enc.contentType(), options.gzip(), "logs.sendEvents"),
        e -> BetterStackErrors.mapIngestError(e, "logs.sendEvents"));
  }

  /** Posts one body to {@code POST /} (failures not yet mapped). */
  CompletableFuture<Void> post(byte[] body, String contentType, boolean gzip, String operation) {
    TransportRequest.Builder b =
        TransportRequest.post("/")
            .header("Authorization", "Bearer " + config.sourceToken().orElseThrow().reveal())
            .idempotent(true)
            .operation(operation);
    if (gzip) {
      b.header("Content-Encoding", "gzip").body(gzip(body), contentType);
    } else {
      b.body(body, contentType);
    }
    return transport.sendAsync(b.build()).thenApply(r -> null);
  }

  /**
   * Gzip-compresses bytes.
   *
   * @param data input
   * @return gzip bytes
   */
  static byte[] gzip(byte[] data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
    try (GZIPOutputStream z = new GZIPOutputStream(out)) {
      z.write(data);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
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
