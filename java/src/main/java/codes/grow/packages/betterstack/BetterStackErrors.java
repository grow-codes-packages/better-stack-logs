package codes.grow.packages.betterstack;

import codes.grow.packages.betterstack.core.AuthenticationException;
import codes.grow.packages.betterstack.core.BetterStackException;
import codes.grow.packages.betterstack.core.ErrorCode;
import codes.grow.packages.betterstack.core.HttpTransport;
import codes.grow.packages.betterstack.core.NotFoundException;
import codes.grow.packages.betterstack.core.PermissionDeniedException;
import codes.grow.packages.betterstack.core.RateLimitException;
import codes.grow.packages.betterstack.core.TimeoutException;
import codes.grow.packages.betterstack.core.UnsupportedException;
import codes.grow.packages.betterstack.core.ValidationException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Better Stack specific error mapping on top of the SPEC section 2.1 HTTP mapping.
 *
 * <ul>
 *   <li>Ingest host: {@code 403} (invalid or missing source token) becomes {@link
 *       AuthenticationException}; {@code 402} (quota or spending limit) becomes a non-retryable
 *       {@link QuotaExceededException}; {@code 406}/{@code 413} stay {@link ValidationException}.
 *   <li>SQL host: the ClickHouse exception code ({@code Code: 60. DB::Exception: …}) wins over the
 *       HTTP status: 516/192/193/194 authentication, 497 permission denied, 60/81 not found,
 *       62/47/46/36/53/42/73/456/457/241 validation, 48 unsupported, 159/160 timeout, 202 rate
 *       limited (retryable).
 * </ul>
 */
public final class BetterStackErrors {
  private static final Pattern CODE = Pattern.compile("Code:\\s*(\\d+)\\.\\s*DB::Exception");
  private static final Set<Integer> AUTH = Set.of(516, 192, 193, 194);
  private static final Set<Integer> NOT_FOUND = Set.of(60, 81);
  private static final Set<Integer> VALIDATION = Set.of(62, 47, 46, 36, 53, 42, 73, 456, 457);
  private static final Set<Integer> TIMEOUT = Set.of(159, 160);

  /** Hint appended to ClickHouse {@code MEMORY_LIMIT_EXCEEDED} (241) errors. */
  public static final String MEMORY_HINT =
      " (hint: narrow the time range or sample the cold table)";

  private BetterStackErrors() {}

  /**
   * Maps an ingest-host failure (see the class description). Other errors are returned unchanged.
   *
   * @param error failure (may be wrapped in a {@code CompletionException})
   * @param operation operation name for non-SDK failures
   * @return the mapped SDK exception
   */
  public static BetterStackException mapIngestError(Throwable error, String operation) {
    BetterStackException e = HttpTransport.asSdkException(error, operation);
    Integer status = e.httpStatus().isPresent() ? e.httpStatus().getAsInt() : null;
    if (status == null) {
      return e;
    }
    if (status == 403 && e.code() == ErrorCode.PERMISSION_DENIED) {
      return new AuthenticationException(
          e.getMessage(), status, e.requestId().orElse(null), null, e.getCause());
    }
    if (status == 402) {
      return new QuotaExceededException(
          e.getMessage(), status, e.requestId().orElse(null), e.getCause());
    }
    return e;
  }

  /**
   * Extracts the ClickHouse exception code from an error text ({@code Code: 60. DB::Exception: …}).
   *
   * @param text error text, may be null
   * @return the code, or empty
   */
  public static OptionalInt clickHouseCode(String text) {
    if (text == null) {
      return OptionalInt.empty();
    }
    Matcher m = CODE.matcher(text);
    if (!m.find()) {
      return OptionalInt.empty();
    }
    try {
      return OptionalInt.of(Integer.parseInt(m.group(1)));
    } catch (NumberFormatException e) {
      return OptionalInt.empty();
    }
  }

  /**
   * Builds the SDK exception for a ClickHouse exception code, or empty when the code has no
   * specific mapping (the HTTP status mapping applies then).
   *
   * @param code ClickHouse exception code
   * @param message redacted message
   * @param httpStatus HTTP status or {@code null}
   * @param requestId request id or {@code null}
   * @return the exception, or empty
   */
  public static Optional<BetterStackException> errorForClickHouseCode(
      int code, String message, Integer httpStatus, String requestId) {
    if (AUTH.contains(code)) {
      return Optional.of(new AuthenticationException(message, httpStatus, requestId, null, null));
    }
    if (code == 497) {
      return Optional.of(new PermissionDeniedException(message, httpStatus, requestId, null, null));
    }
    if (NOT_FOUND.contains(code)) {
      return Optional.of(new NotFoundException(message, httpStatus, requestId, null, null));
    }
    if (VALIDATION.contains(code)) {
      return Optional.of(new ValidationException(message, httpStatus, requestId, null, null));
    }
    if (code == 241) {
      return Optional.of(
          new ValidationException(message + MEMORY_HINT, httpStatus, requestId, null, null));
    }
    if (code == 48) {
      return Optional.of(new UnsupportedException(message, httpStatus, requestId, null, null));
    }
    if (TIMEOUT.contains(code)) {
      return Optional.of(new TimeoutException(message, httpStatus, requestId, null, null));
    }
    if (code == 202) {
      return Optional.of(new RateLimitException(message, httpStatus, requestId, null, null));
    }
    return Optional.empty();
  }

  /**
   * Maps a SQL-host failure by the ClickHouse code found in its message; otherwise the error is
   * returned unchanged.
   *
   * @param error failure (may be wrapped)
   * @param operation operation name for non-SDK failures
   * @return the mapped SDK exception
   */
  public static BetterStackException mapQueryError(Throwable error, String operation) {
    BetterStackException e = HttpTransport.asSdkException(error, operation);
    OptionalInt code = clickHouseCode(e.getMessage());
    if (code.isEmpty()) {
      return e;
    }
    Integer status = e.httpStatus().isPresent() ? e.httpStatus().getAsInt() : null;
    return errorForClickHouseCode(
            code.getAsInt(), e.getMessage(), status, e.requestId().orElse(null))
        .orElse(e);
  }

  /**
   * Returns a future that completes like {@code source} with failures passed through {@code map};
   * cancelling the returned future cancels {@code source}.
   */
  static <T> CompletableFuture<T> mapFailure(
      CompletableFuture<T> source, Function<Throwable, ? extends Throwable> map) {
    CompletableFuture<T> out = new CompletableFuture<>();
    source.whenComplete(
        (v, err) -> {
          if (err == null) {
            out.complete(v);
          } else {
            out.completeExceptionally(map.apply(err));
          }
        });
    out.whenComplete(
        (v, err) -> {
          if (out.isCancelled()) {
            source.cancel(true);
          }
        });
    return out;
  }
}
