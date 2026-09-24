package io.matedata.semantic;
import java.util.Map;
import java.util.Locale;

/** Intentionally constrained offline planner. Never presents guessed results as AI output. */
public final class DemoPlanner {
    public QueryPlan plan(String question, SemanticModel model) {
        if (question == null || question.isBlank() || question.length() > 2000) throw new IllegalArgumentException("问题长度需为 1–2000 字符");
        String q = question.toLowerCase(Locale.ROOT);
        if (q.matches("(?s).*(删除|修改|插入|更新|drop |delete |insert |update ).*")) throw new IllegalArgumentException("问数仅支持只读分析");
        var metrics = model.metrics().stream().filter(m -> q.contains(m.name().toLowerCase(Locale.ROOT)) || m.aliases().stream().anyMatch(a -> q.contains(a.toLowerCase(Locale.ROOT)))).toList();
        var dimensions = model.dimensions().stream().filter(d -> q.contains(d.name().toLowerCase(Locale.ROOT)) || d.aliases().stream().anyMatch(a -> q.contains(a.toLowerCase(Locale.ROOT)))).toList();
        if (metrics.size() != 1 || dimensions.size() > 1) throw new IllegalArgumentException("演示模式每次请选择一个指标和至多一个维度，例如：各区域销售额。复杂问题请配置模型后使用 Agent 模式。");
        return new QueryPlan(metrics.getFirst().id(), dimensions.isEmpty() ? null : dimensions.getFirst().id(), Map.of(), 100);
    }
}
