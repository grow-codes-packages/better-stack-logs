package codes.grow.packages.betterstack;

import codes.grow.packages.betterstack.core.RateLimitException;

/**
 * Better Stack answered {@code 402 Quota exceeded}: the team's quota or spending limit is reached.
 * It is a {@link RateLimitException} (code {@code RATE_LIMITED}) that is <b>not</b> retryable,
 * since waiting does not lift the limit.
 */
public final class QuotaExceededException extends RateLimitException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message redacted message
   * @param httpStatus HTTP status or {@code null}
   * @param requestId request id or {@code null}
   * @param cause cause or {@code null}
   */
  public QuotaExceededException(
      String message, Integer httpStatus, String requestId, Throwable cause) {
    super(message, httpStatus, requestId, null, cause);
  }

  /**
   * Never retryable.
   *
   * @return false
   */
  @Override
  public boolean retryable() {
    return false;
  }
}
