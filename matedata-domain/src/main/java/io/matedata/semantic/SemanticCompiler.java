package io.matedata.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public final class SemanticCompiler {
  public CompiledQuery compile(SemanticModel model, QueryPlan plan) {
    if (plan.limit() < 1 || plan.limit() > 1000)
      throw new IllegalArgumentException("结果行数必须为 1–1000");
    var metric = model.metric(plan.metric());
    var dimension =
        plan.dimension() == null || plan.dimension().isBlank()
            ? null
            : model.dimension(plan.dimension());
    String aggregate =
        metric.aggregation()
            + "("
            + physical(model, metric.column())
            + ") AS "
            + alias(model, metric.id());
    var sql = new StringBuilder("SELECT ");
    if (dimension != null)
      sql.append(physical(model, dimension.column()))
          .append(" AS ")
          .append(alias(model, dimension.id()))
          .append(", ");
    sql.append(aggregate).append(" FROM ").append(physical(model, model.tableName()));
    var params = new ArrayList<Object>();
    for (var filter : new TreeMap<>(plan.filters()).entrySet()) {
      sql.append(params.isEmpty() ? " WHERE " : " AND ");
      appendEquality(model, filter.getKey(), filter.getValue(), sql, params);
    }
    if (dimension != null) {
      sql.append(" GROUP BY ").append(physical(model, dimension.column()));
      var sort =
          plan.sort() != null
              ? plan.sort()
              : chronological(dimension)
                  ? QueryPlan.Sort.DIMENSION_ASC
                  : QueryPlan.Sort.METRIC_DESC;
      sql.append(" ORDER BY ")
          .append(
              switch (sort) {
                case METRIC_DESC -> "2 DESC";
                case METRIC_ASC -> "2 ASC";
                case DIMENSION_ASC -> "1 ASC";
                case DIMENSION_DESC -> "1 DESC";
              });
    }
    sql.append(" LIMIT ").append(plan.limit());
    return new CompiledQuery(sql.toString(), params);
  }

  /**
   * Distinct non-null values of one dimension in ascending order. One row beyond the limit is
   * requested so callers can tell whether the list was truncated.
   */
  public CompiledQuery compileValues(SemanticModel model, ValuesPlan plan) {
    if (plan.limit() < 1 || plan.limit() > ValuesPlan.MAX_LIMIT)
      throw new IllegalArgumentException("维度值数量必须为 1–" + ValuesPlan.MAX_LIMIT);
    var dimension = model.dimension(plan.dimension());
    String column = physical(model, dimension.column());
    var sql =
        new StringBuilder("SELECT DISTINCT ")
            .append(column)
            .append(" AS ")
            .append(alias(model, dimension.id()))
            .append(" FROM ")
            .append(physical(model, model.tableName()))
            .append(" WHERE ")
            .append(column)
            .append(" IS NOT NULL");
    var params = new ArrayList<Object>();
    for (var filter : new TreeMap<>(plan.filters()).entrySet()) {
      sql.append(" AND ");
      appendEquality(model, filter.getKey(), filter.getValue(), sql, params);
    }
    if (plan.keyword() != null) {
      if (dimension.valueType() != ValueType.TEXT)
        throw new IllegalArgumentException("仅文本维度支持关键字筛选");
      if (plan.keyword().length() > 50) throw new IllegalArgumentException("关键字过长");
      // '!' escapes the wildcard characters identically on H2, PostgreSQL and MySQL.
      sql.append(" AND ").append(column).append(" LIKE ? ESCAPE '!'");
      params.add("%" + plan.keyword().replaceAll("([!%_])", "!$1") + "%");
    }
    sql.append(" ORDER BY 1 ASC LIMIT ").append(plan.limit() + 1);
    return new CompiledQuery(sql.toString(), params);
  }

  private void appendEquality(
      SemanticModel model, String id, String value, StringBuilder sql, List<Object> params) {
    var dimension = model.dimension(id);
    sql.append(physical(model, dimension.column())).append(" = ?");
    if (value.length() > 200) throw new IllegalArgumentException("过滤值过长");
    params.add(dimension.valueType().parse(value));
  }

  /** Time series read in time order; the demo "month" dimension stores periods as text. */
  private static boolean chronological(SemanticModel.Dimension dimension) {
    return dimension.id().equals("month")
        || switch (dimension.valueType()) {
          case DATE, TIME, TIMESTAMP, TIMESTAMP_WITH_ZONE -> true;
          case TEXT, NUMBER, BOOLEAN -> false;
        };
  }

  private String alias(SemanticModel model, String id) {
    String quote = model.dialect() == SemanticModel.Dialect.MYSQL ? "`" : "\"";
    return quote + id + quote;
  }

  private String physical(SemanticModel model, String id) {
    return model.dialect() == null ? id : alias(model, id);
  }
}
