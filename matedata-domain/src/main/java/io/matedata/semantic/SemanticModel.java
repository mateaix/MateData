package io.matedata.semantic;

import java.util.List;
import java.util.Set;

/** Published business vocabulary; SQL identifiers are never supplied by the model. */
public record SemanticModel(String id, String name, String description, String sourceId,
                            String tableName, List<Metric> metrics, List<Dimension> dimensions) {
    public SemanticModel {
        identifier(id); identifier(sourceId); identifier(tableName);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("数据集名称不能为空");
        metrics = List.copyOf(metrics); dimensions = List.copyOf(dimensions);
        if (metrics.isEmpty()) throw new IllegalArgumentException("至少需要一个指标");
        var ids = new java.util.HashSet<String>();
        for (var m : metrics) if (!ids.add(m.id())) throw new IllegalArgumentException("指标维度标识重复");
        for (var d : dimensions) if (!ids.add(d.id())) throw new IllegalArgumentException("指标维度标识重复");
    }
    public record Metric(String id, String name, String column, String aggregation, List<String> aliases) {
        public Metric {
            identifier(id); identifier(column);
            if (!Set.of("SUM", "COUNT", "AVG", "MIN", "MAX").contains(aggregation)) throw new IllegalArgumentException("不支持的聚合函数");
            aliases = List.copyOf(aliases == null ? List.of() : aliases);
        }
    }
    public record Dimension(String id, String name, String column, List<String> aliases) {
        public Dimension { identifier(id); identifier(column); aliases = List.copyOf(aliases == null ? List.of() : aliases); }
    }
    public static void identifier(String value) {
        if (value == null || !value.matches("[a-zA-Z][a-zA-Z0-9_]{0,62}")) throw new IllegalArgumentException("标识符只能包含字母、数字和下划线");
    }
    public Metric metric(String id) { return metrics.stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("未知指标：" + id)); }
    public Dimension dimension(String id) { return dimensions.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("未知维度：" + id)); }
    public static SemanticModel sales() {
        return new SemanticModel("sales", "销售经营分析", "演示数据 · 2026 年 1–6 月，区域、品类和渠道经营指标", "demo_sales", "sales",
                List.of(new Metric("revenue", "销售额", "amount", "SUM", List.of("销售额", "营收", "收入", "revenue")),
                        new Metric("orders", "订单数", "id", "COUNT", List.of("订单数", "订单量", "orders")),
                        new Metric("profit", "利润", "profit", "SUM", List.of("利润", "profit"))),
                List.of(new Dimension("region", "区域", "region", List.of("区域", "地区", "region")),
                        new Dimension("category", "品类", "category", List.of("品类", "分类", "category")),
                        new Dimension("month", "月份", "sales_month", List.of("每月", "月份", "月度", "趋势", "month")),
                        new Dimension("channel", "渠道", "channel", List.of("渠道", "channel"))));
    }
}
