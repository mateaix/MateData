package io.matedata.semantic;
import java.util.ArrayList;
import java.util.TreeMap;

public final class SemanticCompiler {
    public CompiledQuery compile(SemanticModel model, QueryPlan plan) {
        if (plan.limit() < 1 || plan.limit() > 1000) throw new IllegalArgumentException("结果行数必须为 1–1000");
        var metric = model.metric(plan.metric());
        var dimension = plan.dimension() == null || plan.dimension().isBlank() ? null : model.dimension(plan.dimension());
        String aggregate = metric.aggregation() + "(" + metric.column() + ") AS \"" + metric.id() + "\"";
        var sql = new StringBuilder("SELECT ");
        if (dimension != null) sql.append(dimension.column()).append(" AS \"").append(dimension.id()).append("\", ");
        sql.append(aggregate).append(" FROM ").append(model.tableName());
        var params = new ArrayList<Object>();
        for (var filter : new TreeMap<>(plan.filters()).entrySet()) {
            sql.append(params.isEmpty() ? " WHERE " : " AND ");
            sql.append(model.dimension(filter.getKey()).column()).append(" = ?");
            if (filter.getValue().length() > 200) throw new IllegalArgumentException("过滤值过长");
            params.add(filter.getValue());
        }
        if (dimension != null) {
            sql.append(" GROUP BY ").append(dimension.column());
            sql.append(" ORDER BY ").append(dimension.id().equals("month") ? "1 ASC" : "2 DESC");
        }
        sql.append(" LIMIT ").append(plan.limit());
        return new CompiledQuery(sql.toString(), params);
    }
}
