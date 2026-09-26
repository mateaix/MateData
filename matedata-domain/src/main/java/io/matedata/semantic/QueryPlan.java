package io.matedata.semantic;

import java.util.Map;

public record QueryPlan(
    String metric, String dimension, Map<String, String> filters, int limit, Sort sort) {
  /**
   * How grouped rows are ordered before the limit applies. {@code null} keeps each dimension's
   * natural order: time dimensions chronologically, others by metric from high to low.
   */
  public enum Sort {
    METRIC_DESC,
    METRIC_ASC,
    DIMENSION_ASC,
    DIMENSION_DESC
  }

  public QueryPlan {
    filters = Map.copyOf(filters == null ? Map.of() : filters);
  }

  public QueryPlan(String metric, String dimension, Map<String, String> filters, int limit) {
    this(metric, dimension, filters, limit, null);
  }
}
