package io.matedata.semantic;

import java.util.ArrayList;
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
      sql.append(physical(model, model.dimension(filter.getKey()).column())).append(" = ?");
      if (filter.getValue().length() > 200) throw new IllegalArgumentException("过滤值过长");
      params.add(model.dimension(filter.getKey()).valueType().parse(filter.getValue()));
    }
    if (dimension != null) {
      sql.append(" GROUP BY ").append(physical(model, dimension.column()));
      sql.append(" ORDER BY ").append(chronological(dimension) ? "1 ASC" : "2 DESC");
    }
    sql.append(" LIMIT ").append(plan.limit());
    return new CompiledQuery(sql.toString(), params);
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
