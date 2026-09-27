package io.matedata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.matedata.catalog.application.CatalogService;
import io.matedata.catalog.domain.DataSourceDefinition;
import io.matedata.catalog.infrastructure.BusinessConnections;
import io.matedata.conversation.infrastructure.JdbcQueryExecutor;
import io.matedata.semantic.*;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Dimension value lookups against a real H2 database, including wildcard-looking values. */
class DimensionValuesTest {
  SemanticModel model;
  JdbcQueryExecutor executor;

  @BeforeEach
  void setUp() throws Exception {
    String url = "jdbc:h2:mem:values_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    try (var connection = DriverManager.getConnection(url, "sa", "");
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE orders(id INT, region VARCHAR(20), channel VARCHAR(20), amount DECIMAL(12,2))");
      statement.execute(
          "INSERT INTO orders VALUES "
              + "(1,'华东','直销',1),(2,'华东','直销',1),(3,'华东区','合作',1),(4,'华南','直销',1),"
              + "(5,'50%折扣','直销',1),(6,'a_b','直销',1),(7,'axb','直销',1),(8,NULL,'直销',1),"
              + "(9,'西部!','合作',1)");
    }
    var catalog = mock(CatalogService.class);
    var connections = mock(BusinessConnections.class);
    when(catalog.require("fixture"))
        .thenReturn(
            new DataSourceDefinition(
                "fixture", "Test fixture", "DEMO", url, "sa", "", "AVAILABLE", ""));
    when(connections.open(any()))
        .thenAnswer(invocation -> DriverManager.getConnection(url, "sa", ""));
    model =
        new SemanticModel(
            "orders",
            "订单",
            "",
            "fixture",
            "orders",
            List.of(new SemanticModel.Metric("amount", "金额", "amount", "SUM", List.of())),
            List.of(
                new SemanticModel.Dimension("region", "区域", "region", List.of()),
                new SemanticModel.Dimension("channel", "渠道", "channel", List.of())));
    executor = new JdbcQueryExecutor(catalog, connections);
  }

  List<Object> values(ValuesPlan plan) throws Exception {
    var result = executor.values(model, plan, new SemanticCompiler().compileValues(model, plan));
    return result.rows().stream().map(row -> row.get("region")).toList();
  }

  @Test
  void returnsDistinctNonNullValuesInOrder() throws Exception {
    assertThat(values(new ValuesPlan("region", null, Map.of(), 20)))
        .containsExactly("50%折扣", "a_b", "axb", "华东", "华东区", "华南", "西部!");
  }

  @Test
  void keywordsMatchLiterallyEvenWithWildcardCharacters() throws Exception {
    assertThat(values(new ValuesPlan("region", "华东", Map.of(), 20))).containsExactly("华东", "华东区");
    assertThat(values(new ValuesPlan("region", "_", Map.of(), 20))).containsExactly("a_b");
    assertThat(values(new ValuesPlan("region", "%", Map.of(), 20))).containsExactly("50%折扣");
    assertThat(values(new ValuesPlan("region", "!", Map.of(), 20))).containsExactly("西部!");
    assertThat(values(new ValuesPlan("region", "不存在", Map.of(), 20))).isEmpty();
  }

  @Test
  void rowFiltersNarrowTheValuesAndOneExtraRowSignalsTruncation() throws Exception {
    assertThat(values(new ValuesPlan("region", null, Map.of("channel", "合作"), 20)))
        .containsExactly("华东区", "西部!");
    assertThat(values(new ValuesPlan("region", null, Map.of(), 2))).hasSize(3);
  }

  @Test
  void tamperedLookupsAreRejectedBeforeExecution() {
    var plan = new ValuesPlan("region", "华东", Map.of(), 20);
    var compiled = new SemanticCompiler().compileValues(model, plan);
    assertThatThrownBy(
            () -> executor.values(model, plan, new CompiledQuery(compiled.sql(), List.of("%"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                executor.values(
                    model,
                    plan,
                    new CompiledQuery(
                        compiled.sql().replace("region", "channel"), compiled.parameters())))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
