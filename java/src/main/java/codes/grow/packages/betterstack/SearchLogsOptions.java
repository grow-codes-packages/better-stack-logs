package codes.grow.packages.betterstack;

import codes.grow.packages.betterstack.logging.SortOrder;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Options of {@link BetterStackQuery#searchLogs(SearchLogsOptions)}. Values are checked when the
 * search runs ({@link codes.grow.packages.betterstack.core.ValidationException}).
 */
public final class SearchLogsOptions {
  private final String source;
  private final String contains;
  private final Instant start;
  private final Instant end;
  private final int limit;
  private final SortOrder order;
  private final String cursor;

  private SearchLogsOptions(Builder b) {
    this.source = b.source;
    this.contains = b.contains;
    this.start = b.start;
    this.end = b.end;
    this.limit = b.limit;
    this.order = b.order;
    this.cursor = b.cursor;
  }

  /**
   * Defaults: the configured source table, no text filter, the last 24 h, 100 records, newest
   * first.
   *
   * @return options
   */
  public static SearchLogsOptions defaults() {
    return builder().build();
  }

  /**
   * Returns a builder.
   *
   * @return builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns a builder pre-filled with these options.
   *
   * @return builder
   */
  public Builder toBuilder() {
    return new Builder()
        .source(source)
        .contains(contains)
        .start(start)
        .end(end)
        .limit(limit)
        .order(order)
        .cursor(cursor);
  }

  /**
   * Collection ({@code t1234_myapp}); empty uses the configured source table.
   *
   * @return source
   */
  public Optional<String> source() {
    return Optional.ofNullable(source);
  }

  /**
   * Case-sensitive literal text searched in the raw event JSON (JSON-escaped first, so it matches
   * text inside string values); empty matches everything.
   *
   * @return text
   */
  public Optional<String> contains() {
    return Optional.ofNullable(contains);
  }

  /**
   * Inclusive window start; empty means 24 h before {@link #end()} (or before now).
   *
   * @return start
   */
  public Optional<Instant> start() {
    return Optional.ofNullable(start);
  }

  /**
   * Inclusive window end; empty means no upper bound (robust to clock skew and ingest delay).
   *
   * @return end
   */
  public Optional<Instant> end() {
    return Optional.ofNullable(end);
  }

  /**
   * Records per page, 1 to 10000 (default 100).
   *
   * @return limit
   */
  public int limit() {
    return limit;
  }

  /**
   * Order by {@code dt} (default newest first).
   *
   * @return order
   */
  public SortOrder order() {
    return order;
  }

  /**
   * Opaque cursor of a previous page.
   *
   * @return cursor
   */
  public Optional<String> cursor() {
    return Optional.ofNullable(cursor);
  }

  /** Builder of {@link SearchLogsOptions}. */
  public static final class Builder {
    private String source;
    private String contains;
    private Instant start;
    private Instant end;
    private int limit = BetterStackQuery.DEFAULT_LIMIT;
    private SortOrder order = SortOrder.DESC;
    private String cursor;

    private Builder() {}

    /**
     * Sets the collection.
     *
     * @param source identifier or {@code null}
     * @return this builder
     */
    public Builder source(String source) {
      this.source = source;
      return this;
    }

    /**
     * Sets the text to search.
     *
     * @param contains text or {@code null}
     * @return this builder
     */
    public Builder contains(String contains) {
      this.contains = contains;
      return this;
    }

    /**
     * Sets the inclusive window start.
     *
     * @param start instant or {@code null}
     * @return this builder
     */
    public Builder start(Instant start) {
      this.start = start;
      return this;
    }

    /**
     * Sets the inclusive window end.
     *
     * @param end instant or {@code null}
     * @return this builder
     */
    public Builder end(Instant end) {
      this.end = end;
      return this;
    }

    /**
     * Sets the page size.
     *
     * @param limit 1 to 10000
     * @return this builder
     */
    public Builder limit(int limit) {
      this.limit = limit;
      return this;
    }

    /**
     * Sets the order.
     *
     * @param order order
     * @return this builder
     */
    public Builder order(SortOrder order) {
      this.order = Objects.requireNonNull(order, "order");
      return this;
    }

    /**
     * Sets the page cursor.
     *
     * @param cursor cursor or {@code null}
     * @return this builder
     */
    public Builder cursor(String cursor) {
      this.cursor = cursor;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return options
     */
    public SearchLogsOptions build() {
      return new SearchLogsOptions(this);
    }
  }
}
