package io.matedata.semantic;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticCompilerTest {
  private final SemanticModel sales = SemanticModel.sales();
  private final SemanticCompiler compiler = new SemanticCompiler();

  @Test
  void compilesOnlyRegisteredIdentifiers() {
    var query = compiler.compile(sales, new QueryPlan("revenue", "region", Map.of(), 20));
    assertThat(query.sql())
        .isEqualTo(
            "SELECT region AS \"region\", SUM(amount) AS \"revenue\" FROM sales GROUP BY region ORDER BY 2 DESC LIMIT 20");
    assertThat(query.parameters()).isEmpty();
  }

  @Test
  void bindsFilterValuesInsteadOfConcatenating() {
    var query =
        compiler.compile(
            sales, new QueryPlan("revenue", null, Map.of("region", "x' OR 1=1 --"), 10));
    assertThat(query.sql()).contains("WHERE region = ?").doesNotContain("OR 1=1");
    assertThat(query.parameters()).containsExactly("x' OR 1=1 --");
  }

  @Test
  void rejectsUnknownFieldsAndOversizedQueries() {
    assertThatThrownBy(() -> compiler.compile(sales, new QueryPlan("password", null, Map.of(), 10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                compiler.compile(
                    sales, new QueryPlan("revenue", "region; DROP TABLE sales", Map.of(), 10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> compiler.compile(sales, new QueryPlan("revenue", null, Map.of(), 1001)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validatesModelIdentifiersAndAggregation() {
    assertThatThrownBy(
            () ->
                new SemanticModel("x", "x", "x", "s", "sales; DROP TABLE x", List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SemanticModel.Metric("m", "m", "amount", "EXEC", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void demoUnderstandsRegisteredChineseAndRejectsAmbiguity() {
    var planner = new DemoPlanner();
    assertThat(planner.plan("各区域销售额", sales).dimension()).isEqualTo("region");
    assertThat(planner.plan("各品类利润", sales).metric()).isEqualTo("profit");
    assertThatThrownBy(() -> planner.plan("删除全部订单", sales))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> planner.plan("各区域销售额和利润", sales))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void demoNeverSilentlyDiscardsUnsupportedQualifiers() {
    var planner = new DemoPlanner();
    for (String question :
        List.of("华东销售额", "2025年销售额", "销售额同比增长率", "各区域销售额前两名", "各渠道订单数只看直销", "销售额大于100万"))
      assertThatThrownBy(() -> planner.plan(question, sales))
          .as(question)
          .isInstanceOf(IllegalArgumentException.class);
    assertThat(planner.plan("请帮我查询各区域的销售额是多少？", sales).dimension()).isEqualTo("region");
  }

  @Test
  void temporalDimensionsAreOrderedChronologicallyWhateverTheirId() {
    var metric = new SemanticModel.Metric("revenue", "销售额", "amount", "SUM", List.of());
    for (var type :
        List.of(
            ValueType.DATE, ValueType.TIME, ValueType.TIMESTAMP, ValueType.TIMESTAMP_WITH_ZONE)) {
      var model =
          new SemanticModel(
              "orders",
              "订单",
              "",
              "source",
              "orders",
              List.of(metric),
              List.of(
                  new SemanticModel.Dimension("order_date", "下单时间", "created", List.of(), type)),
              SemanticModel.Dialect.ANSI);
      assertThat(
              compiler.compile(model, new QueryPlan("revenue", "order_date", Map.of(), 10)).sql())
          .as(type.name())
          .endsWith("ORDER BY 1 ASC LIMIT 10");
    }
    assertThat(compiler.compile(sales, new QueryPlan("revenue", "month", Map.of(), 10)).sql())
        .endsWith("ORDER BY 1 ASC LIMIT 10");
  }

  @Test
  void explicitSortsDecideWhichRowsTheLimitKeeps() {
    for (var expectation :
        Map.of(
                QueryPlan.Sort.METRIC_DESC, "ORDER BY 2 DESC LIMIT 2",
                QueryPlan.Sort.METRIC_ASC, "ORDER BY 2 ASC LIMIT 2",
                QueryPlan.Sort.DIMENSION_ASC, "ORDER BY 1 ASC LIMIT 2",
                QueryPlan.Sort.DIMENSION_DESC, "ORDER BY 1 DESC LIMIT 2")
            .entrySet()) {
      var plan = new QueryPlan("revenue", "region", Map.of(), 2, expectation.getKey());
      var query = compiler.compile(sales, plan);
      assertThat(query.sql()).as(expectation.getKey().name()).endsWith(expectation.getValue());
      new SqlGuard().verify(query, sales, plan);
    }
    // The most recent periods of a time dimension.
    assertThat(
            compiler
                .compile(
                    sales,
                    new QueryPlan("revenue", "month", Map.of(), 3, QueryPlan.Sort.DIMENSION_DESC))
                .sql())
        .endsWith("ORDER BY 1 DESC LIMIT 3");
    // Without a dimension there is one row and nothing to order.
    assertThat(
            compiler
                .compile(
                    sales, new QueryPlan("revenue", null, Map.of(), 2, QueryPlan.Sort.METRIC_ASC))
                .sql())
        .doesNotContain("ORDER BY");
  }

  @Test
  void aPlanWithADifferentSortFailsTheGuard() {
    var descending = new QueryPlan("revenue", "region", Map.of(), 2, QueryPlan.Sort.METRIC_DESC);
    var ascending = new QueryPlan("revenue", "region", Map.of(), 2, QueryPlan.Sort.METRIC_ASC);
    assertThatThrownBy(
            () -> new SqlGuard().verify(compiler.compile(sales, descending), sales, ascending))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void valuesLookupsCompileToBoundedDistinctSelects() {
    var plain = compiler.compileValues(sales, new ValuesPlan("region", null, Map.of(), 20));
    assertThat(plain.sql())
        .isEqualTo(
            "SELECT DISTINCT region AS \"region\" FROM sales WHERE region IS NOT NULL ORDER BY 1 ASC LIMIT 21");
    assertThat(plain.parameters()).isEmpty();

    var filtered =
        compiler.compileValues(
            sales, new ValuesPlan("region", " 50%_! ", Map.of("channel", "直销"), 5));
    assertThat(filtered.sql())
        .isEqualTo(
            "SELECT DISTINCT region AS \"region\" FROM sales WHERE region IS NOT NULL"
                + " AND channel = ? AND region LIKE ? ESCAPE '!' ORDER BY 1 ASC LIMIT 6");
    assertThat(filtered.parameters()).containsExactly("直销", "%50!%!_!!%");
    new SqlGuard()
        .verifyValues(
            filtered, sales, new ValuesPlan("region", "50%_!", Map.of("channel", "直销"), 5));
  }

  @Test
  void valuesLookupsRejectUnknownDimensionsBadLimitsAndNonTextKeywords() {
    for (var plan :
        List.of(
            new ValuesPlan("secret", null, Map.of(), 10),
            new ValuesPlan("region", null, Map.of(), 0),
            new ValuesPlan("region", null, Map.of(), 51),
            new ValuesPlan("region", "x".repeat(51), Map.of(), 10)))
      assertThatThrownBy(() -> compiler.compileValues(sales, plan))
          .as(plan.toString())
          .isInstanceOf(IllegalArgumentException.class);
    var numeric =
        new SemanticModel(
            "n",
            "N",
            "",
            "s",
            "t",
            List.of(new SemanticModel.Metric("m", "M", "m", "SUM", List.of())),
            List.of(
                new SemanticModel.Dimension("year", "年份", "year", List.of(), ValueType.NUMBER)));
    assertThatThrownBy(
            () -> compiler.compileValues(numeric, new ValuesPlan("year", "20", Map.of(), 10)))
        .hasMessageContaining("文本");
    assertThat(compiler.compileValues(numeric, new ValuesPlan("year", null, Map.of(), 10)).sql())
        .contains("SELECT DISTINCT year");
  }

  @Test
  void theGuardRejectsValuesLookupsThatDifferFromTheirPlan() {
    var plan = new ValuesPlan("region", "华东", Map.of(), 10);
    var compiled = compiler.compileValues(sales, plan);
    assertThatThrownBy(
            () ->
                new SqlGuard()
                    .verifyValues(compiled, sales, new ValuesPlan("region", "华南", Map.of(), 10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SqlGuard()
                    .verifyValues(
                        compiled,
                        sales,
                        new ValuesPlan("region", "华东", Map.of("channel", "直销"), 10)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void quotedPhysicalIdentifiersPreserveCaseAndDatabaseDialect() {
    var metric = new SemanticModel.Metric("Value", "金额", "Amount", "SUM", List.of());
    var dimension = new SemanticModel.Dimension("value", "分组", "Region", List.of());
    var ansi =
        new SemanticModel(
            "test",
            "Test",
            "",
            "source",
            "Sales",
            List.of(metric),
            List.of(dimension),
            SemanticModel.Dialect.ANSI);
    var mysql =
        new SemanticModel(
            "test",
            "Test",
            "",
            "source",
            "Sales",
            List.of(metric),
            List.of(dimension),
            SemanticModel.Dialect.MYSQL);
    var plan = new QueryPlan("Value", "value", Map.of(), 10);
    assertThat(compiler.compile(ansi, plan).sql())
        .contains("FROM \"Sales\"", "SUM(\"Amount\")", "\"Region\" AS \"value\"");
    assertThat(compiler.compile(mysql, plan).sql())
        .contains("FROM `Sales`", "SUM(`Amount`)", "`Region` AS `value`");
  }
}
