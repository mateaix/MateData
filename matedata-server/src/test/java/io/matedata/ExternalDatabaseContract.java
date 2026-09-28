package io.matedata;

import static org.assertj.core.api.Assertions.*;

import io.matedata.catalog.application.*;
import io.matedata.catalog.infrastructure.*;
import io.matedata.conversation.infrastructure.JdbcQueryExecutor;
import io.matedata.semantic.*;
import io.matedata.semantic.application.SemanticService;
import io.matedata.semantic.infrastructure.JdbcModelRepository;
import io.matedata.shared.infrastructure.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Shared contract exercised against independent database engines. */
abstract class ExternalDatabaseContract {
  @TempDir Path dir;

  void verifyDatabase(String type, String envPrefix) throws Exception {
    var store =
        new DocumentStore(
            new JdbcTemplate(
                new DriverManagerDataSource(
                    "jdbc:h2:mem:pgtest_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")));
    var vault = new SecretVault(dir.toString(), "");
    var connections = new BusinessConnections(vault);
    var catalog = new CatalogService(new JdbcSourceRepository(store), vault, connections);
    var source =
        catalog.create(
            type + " test",
            type,
            System.getenv("MATEDATA_TEST_" + envPrefix + "_URL"),
            System.getenv().getOrDefault("MATEDATA_TEST_" + envPrefix + "_USER", "matedata_reader"),
            System.getenv().getOrDefault("MATEDATA_TEST_" + envPrefix + "_PASSWORD", ""));
    assertThat(catalog.test(source.id()).get("success")).isEqualTo(true);
    // Verify actual grants rather than only the driver's read-only hint. No row can be changed.
    try (var connection = connections.open(catalog.require(source.id()))) {
      connection.setReadOnly(false);
      String tableName = type.equals("MYSQL") ? "`Sales_Data`" : "\"Sales_Data\"";
      try (var statement = connection.createStatement()) {
        assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM " + tableName + " WHERE 1=0"))
            .isInstanceOfSatisfying(
                java.sql.SQLException.class, e -> assertThat(e.getSQLState()).startsWith("42"));
      }
    }
    var table =
        catalog.tables(source.id()).stream()
            .filter(t -> t.name().equals("Sales_Data"))
            .findFirst()
            .orElseThrow();
    assertThat(table.columns())
        .extracting(io.matedata.catalog.domain.SourceColumn::name)
        .containsExactlyInAnyOrder(
            "Region", "Revenue", "LargeId", "OrderDate", "OrderTime", "Clock");
    var models = new JdbcModelRepository(store);
    var semantic = new SemanticService(models, catalog);
    var model =
        semantic.create(
            new SemanticModel(
                "pg_sales",
                "PostgreSQL sales",
                "",
                source.id(),
                "sales_data",
                List.of(
                    new SemanticModel.Metric("revenue", "销售额", "revenue", "SUM", List.of("销售额"))),
                List.of(
                    new SemanticModel.Dimension("region", "区域", "region", List.of("区域")),
                    new SemanticModel.Dimension("large_id", "编号", "largeid", List.of("编号")),
                    new SemanticModel.Dimension("order_date", "日期", "orderdate", List.of("日期")),
                    new SemanticModel.Dimension("order_time", "时间", "ordertime", List.of("时间")),
                    new SemanticModel.Dimension("clock", "时刻", "clock", List.of("时刻")))));
    assertThat(model.tableName()).isEqualTo("Sales_Data");
    var plan = new QueryPlan("revenue", "region", Map.of(), 10);
    var compiled = new SemanticCompiler().compile(model, plan);
    var result = new JdbcQueryExecutor(catalog, connections).execute(model, plan, compiled);
    assertThat(result.rows()).hasSize(2);
    assertThat((BigDecimal) result.rows().getFirst().get("revenue"))
        .isEqualByComparingTo("9007199254740993.01");
    var filtered = new QueryPlan("revenue", "region", Map.of("large_id", "9007199254740993"), 10);
    var single =
        new JdbcQueryExecutor(catalog, connections)
            .execute(model, filtered, new SemanticCompiler().compile(model, filtered));
    assertThat(single.rows()).hasSize(1);
    assertThat(single.rows().getFirst().get("region")).isEqualTo("华东");
    var datePlan =
        new QueryPlan("revenue", "order_date", Map.of("order_time", "2026-01-15T13:14:15"), 10);
    var dated =
        new JdbcQueryExecutor(catalog, connections)
            .execute(model, datePlan, new SemanticCompiler().compile(model, datePlan));
    assertThat(dated.rows()).hasSize(1);
    assertThat(dated.rows().getFirst().get("order_date")).isEqualTo("2026-01-15");
    var timePlan = new QueryPlan("revenue", "clock", Map.of(), 10);
    var timed =
        new JdbcQueryExecutor(catalog, connections)
            .execute(model, timePlan, new SemanticCompiler().compile(model, timePlan));
    assertThat(timed.rows().getFirst().get("clock")).isEqualTo("12:34:56.123456");
    // Keyword lookups use the same LIKE ... ESCAPE syntax on every supported database.
    var valuesPlan = new ValuesPlan("region", "华", Map.of(), 10);
    var values =
        new JdbcQueryExecutor(catalog, connections)
            .values(model, valuesPlan, new SemanticCompiler().compileValues(model, valuesPlan));
    assertThat(values.rows()).extracting(row -> row.get("region")).contains("华东");
  }
}
