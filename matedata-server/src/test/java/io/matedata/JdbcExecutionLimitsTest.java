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
import org.junit.jupiter.api.Test;

class JdbcExecutionLimitsTest {
  record Fixture(String url, SemanticModel model, JdbcQueryExecutor executor) {}

  Fixture fixture() throws Exception {
    String url = "jdbc:h2:mem:limits_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    try (var connection = DriverManager.getConnection(url, "sa", "");
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE budget_rows(id INT, amount DECIMAL(12,2))");
      statement.execute("INSERT INTO budget_rows SELECT X, X FROM SYSTEM_RANGE(1,1005)");
    }
    var catalog = mock(CatalogService.class);
    var connections = mock(BusinessConnections.class);
    when(catalog.require("fixture"))
        .thenReturn(
            new DataSourceDefinition(
                "fixture", "Test fixture", "DEMO", url, "sa", "", "AVAILABLE", ""));
    when(connections.open(any()))
        .thenAnswer(invocation -> DriverManager.getConnection(url, "sa", ""));
    var model =
        new SemanticModel(
            "fixture",
            "Test fixture",
            "",
            "fixture",
            "budget_rows",
            List.of(new SemanticModel.Metric("amount", "金额", "amount", "SUM", List.of())),
            List.of(new SemanticModel.Dimension("id", "编号", "id", List.of())));
    return new Fixture(url, model, new JdbcQueryExecutor(catalog, connections));
  }

  @Test
  void boundsActualDatabaseResultsAtOneThousandRows() throws Exception {
    var fixture = fixture();
    var plan = new QueryPlan("amount", "id", Map.of(), 1000);
    var result =
        fixture
            .executor()
            .execute(fixture.model(), plan, new SemanticCompiler().compile(fixture.model(), plan));
    assertThat(result.rows()).hasSize(1000);
    assertThat(result.rows().getFirst().get("id")).isEqualTo(1005);
    assertThat(result.rows().getLast().get("id")).isEqualTo(6);
  }

  @Test
  void rejectsOversizedTextCellsInsteadOfTruncatingOrMaterializingThem() throws Exception {
    var fixture = fixture();
    try (var connection = DriverManager.getConnection(fixture.url(), "sa", "");
        var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE budget_rows ADD label VARCHAR");
      statement.execute("UPDATE budget_rows SET label = REPEAT('x', 65537) WHERE id = 1");
    }
    var model = textModel(fixture.model());
    var plan = new QueryPlan("amount", "label", Map.of("id", "1"), 10);
    var compiled = new SemanticCompiler().compile(model, plan);
    assertThatThrownBy(() -> fixture.executor().execute(model, plan, compiled))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("文本容量");
    try (var connection = DriverManager.getConnection(fixture.url(), "sa", "");
        var statement = connection.createStatement()) {
      statement.execute("UPDATE budget_rows SET label = REPEAT('x', 65536) WHERE id = 1");
    }
    assertThat(fixture.executor().execute(model, plan, compiled).rows().getFirst().get("label"))
        .isEqualTo("x".repeat(65536));
  }

  @Test
  void boundsCombinedTextAcrossRows() throws Exception {
    var fixture = fixture();
    try (var connection = DriverManager.getConnection(fixture.url(), "sa", "");
        var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE budget_rows ADD label VARCHAR");
      statement.execute(
          "UPDATE budget_rows SET label = LPAD(CAST(id AS VARCHAR), 10, '0') || REPEAT('x', 65526) WHERE id <= 17");
    }
    var model = textModel(fixture.model());
    var plan = new QueryPlan("amount", "label", Map.of(), 100);
    assertThatThrownBy(
            () ->
                fixture
                    .executor()
                    .execute(model, plan, new SemanticCompiler().compile(model, plan)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("文本容量");
  }

  @Test
  void rejectsBinaryColumnAfterSourceSchemaDrift() throws Exception {
    var fixture = fixture();
    try (var connection = DriverManager.getConnection(fixture.url(), "sa", "");
        var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE budget_rows ADD label VARBINARY");
      statement.execute("UPDATE budget_rows SET label = X'0102'");
    }
    var model = textModel(fixture.model());
    var plan = new QueryPlan("amount", "label", Map.of(), 10);
    assertThatThrownBy(
            () ->
                fixture
                    .executor()
                    .execute(model, plan, new SemanticCompiler().compile(model, plan)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持");
  }

  @Test
  void preservesMicrosecondTimestampResults() throws Exception {
    var fixture = fixture();
    try (var connection = DriverManager.getConnection(fixture.url(), "sa", "");
        var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE budget_rows ADD label TIMESTAMP(6)");
      statement.execute("UPDATE budget_rows SET label = TIMESTAMP '2026-01-15 13:14:15.123456'");
    }
    var model = textModel(fixture.model());
    var plan = new QueryPlan("amount", "label", Map.of(), 10);
    assertThat(
            fixture
                .executor()
                .execute(model, plan, new SemanticCompiler().compile(model, plan))
                .rows()
                .getFirst()
                .get("label"))
        .isEqualTo("2026-01-15T13:14:15.123456");
  }

  SemanticModel textModel(SemanticModel original) {
    return new SemanticModel(
        original.id(),
        original.name(),
        "",
        original.sourceId(),
        original.tableName(),
        original.metrics(),
        List.of(
            new SemanticModel.Dimension("label", "标签", "label", List.of()),
            new SemanticModel.Dimension("id", "编号", "id", List.of(), ValueType.NUMBER)));
  }

  @Test
  void executorRejectsTamperedSqlBeforeItCanChangeTheDatabase() throws Exception {
    var fixture = fixture();
    var plan = new QueryPlan("amount", "id", Map.of(), 10);
    var tampered = new CompiledQuery("DELETE FROM budget_rows", List.of());
    assertThatThrownBy(() -> fixture.executor().execute(fixture.model(), plan, tampered))
        .isInstanceOf(IllegalArgumentException.class);
    try (var connection = DriverManager.getConnection(fixture.url(), "sa", "");
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM budget_rows")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isEqualTo(1005);
    }
  }
}
