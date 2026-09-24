package io.matedata.semantic;
import java.util.Map;
public record QueryPlan(String metric, String dimension, Map<String, String> filters, int limit) {
    public QueryPlan { filters = Map.copyOf(filters == null ? Map.of() : filters); }
}
