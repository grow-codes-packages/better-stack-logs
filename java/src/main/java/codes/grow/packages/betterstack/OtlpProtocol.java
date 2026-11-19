package codes.grow.packages.betterstack;

/** Payload format of {@link BetterStackOtlpLogExporter}. */
public enum OtlpProtocol {
  /** OTLP/HTTP protobuf ({@code application/x-protobuf}). Default. */
  PROTOBUF("application/x-protobuf"),
  /** OTLP/HTTP JSON ({@code application/json}). */
  JSON("application/json");

  private final String contentType;

  OtlpProtocol(String contentType) {
    this.contentType = contentType;
  }

  /**
   * Returns the {@code Content-Type} of this format.
   *
   * @return the media type
   */
  public String contentType() {
    return contentType;
  }
}
