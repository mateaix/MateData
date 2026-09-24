package io.matedata.semantic;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class SemanticCompilerTest {
    private final SemanticModel sales = SemanticModel.sales();
    private final SemanticCompiler compiler = new SemanticCompiler();

    @Test void compilesOnlyRegisteredIdentifiers() {
        var query = compiler.compile(sales, new QueryPlan("revenue", "region", Map.of(), 20));
        assertThat(query.sql()).isEqualTo("SELECT region AS \"region\", SUM(amount) AS \"revenue\" FROM sales GROUP BY region ORDER BY 2 DESC LIMIT 20");
        assertThat(query.parameters()).isEmpty();
    }
    @Test void bindsFilterValuesInsteadOfConcatenating() {
        var query = compiler.compile(sales, new QueryPlan("revenue", null, Map.of("region", "x' OR 1=1 --"), 10));
        assertThat(query.sql()).contains("WHERE region = ?").doesNotContain("OR 1=1");
        assertThat(query.parameters()).containsExactly("x' OR 1=1 --");
    }
    @Test void rejectsUnknownFieldsAndOversizedQueries() {
        assertThatThrownBy(() -> compiler.compile(sales, new QueryPlan("password", null, Map.of(), 10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compiler.compile(sales, new QueryPlan("revenue", "region; DROP TABLE sales", Map.of(), 10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compiler.compile(sales, new QueryPlan("revenue", null, Map.of(), 1001))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void validatesModelIdentifiersAndAggregation() {
        assertThatThrownBy(() -> new SemanticModel("x","x","x","s","sales; DROP TABLE x",List.of(),List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticModel.Metric("m","m","amount","EXEC",List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void demoUnderstandsRegisteredChineseAndRejectsAmbiguity() {
        var planner = new DemoPlanner();
        assertThat(planner.plan("各区域销售额", sales).dimension()).isEqualTo("region");
        assertThat(planner.plan("各品类利润", sales).metric()).isEqualTo("profit");
        assertThatThrownBy(() -> planner.plan("删除全部订单", sales)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.plan("各区域销售额和利润", sales)).isInstanceOf(IllegalArgumentException.class);
    }
}
