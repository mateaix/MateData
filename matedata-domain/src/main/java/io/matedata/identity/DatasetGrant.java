package io.matedata.identity;

import java.util.*;

public record DatasetGrant(
    String username,
    String datasetId,
    boolean enabled,
    List<String> metrics,
    List<String> dimensions,
    Map<String, String> rowFilters) {
  public DatasetGrant {
    if (metrics == null
        || dimensions == null
        || rowFilters == null
        || metrics.stream().anyMatch(Objects::isNull)
        || dimensions.stream().anyMatch(Objects::isNull)
        || rowFilters.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null))
      throw new IllegalArgumentException("指标、维度及行级过滤条件不能为空");
    metrics = List.copyOf(metrics);
    dimensions = List.copyOf(dimensions);
    rowFilters = Map.copyOf(rowFilters);
  }
}
