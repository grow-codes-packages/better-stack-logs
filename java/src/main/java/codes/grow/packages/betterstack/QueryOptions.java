package codes.grow.packages.betterstack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Options of {@link BetterStackQuery#query(String, Map, QueryOptions)} and {@link
 * BetterStackQuery#queryRaw(String, Map, QueryOptions)}.
 *
 * @param settings ClickHouse settings sent as URL parameters (for example {@code
 *     max_execution_time=30}); names must be identifiers
 */
public record QueryOptions(Map<String, Object> settings) {
  /**
   * Copies the settings.
   *
   * @param settings settings, may be null
   */
  public QueryOptions {
    settings = settings == null ? Map.of() : Map.copyOf(settings);
  }

  /**
   * No settings.
   *
   * @return options
   */
  public static QueryOptions defaults() {
    return new QueryOptions(Map.of());
  }

  /**
   * Copy with one more setting.
   *
   * @param name setting name
   * @param value value ({@code toString()} is sent)
   * @return options
   */
  public QueryOptions withSetting(String name, Object value) {
    Map<String, Object> m = new LinkedHashMap<>(settings);
    m.put(name, value);
    return new QueryOptions(m);
  }
}
