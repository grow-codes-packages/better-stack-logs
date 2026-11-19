package codes.grow.packages.betterstack;

import codes.grow.packages.betterstack.core.UnavailableException;

/**
 * The service answered with a body the SDK cannot use: an invalid JSON response or a ClickHouse
 * exception without a specific mapping that followed a {@code 200} answer. Code {@code
 * UNAVAILABLE}, not retryable.
 */
public final class ServerResponseException extends UnavailableException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message redacted message
   * @param httpStatus HTTP status or {@code null}
   * @param requestId request id or {@code null}
   */
  public ServerResponseException(String message, Integer httpStatus, String requestId) {
    super(message, httpStatus, requestId, null, null);
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
