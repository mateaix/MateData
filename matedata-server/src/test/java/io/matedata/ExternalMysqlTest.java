package io.matedata;

import static org.assertj.core.api.Assertions.*;

import io.matedata.catalog.application.CatalogService;
import io.matedata.catalog.infrastructure.*;
import io.matedata.conversation.infrastructure.JdbcQueryExecutor;
import io.matedata.semantic.*;
import io.matedata.semantic.application.SemanticService;
import io.matedata.semantic.infrastructure.JdbcModelRepository;
import io.matedata.shared.infrastructure.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "MATEDATA_TEST_MYSQL_URL", matches = ".+")
class ExternalMysqlTest extends ExternalDatabaseContract {
  @Test
  void supportsRealMysql() throws Exception {
    verifyDatabase("MYSQL", "MYSQL");
  }

  /**
   * Opt-in fixture: CREATE TABLE named_by_env (id INT, alias_value BOOLEAN, tiny_value TINYINT(1),
   * real_bits BIT(8)); INSERT INTO named_by_env VALUES (1,0,0,0),(2,1,1,1),(3,2,2,2). Provision and
   * remove it with a test administrator; this test uses only the read-only connection.
   */
  @Test
  @EnabledIfEnvironmentVariable(named = "MATEDATA_TEST_MYSQL_TYPE_TABLE", matches = ".+")
  void preservesBooleanAliasesAndTinyintValuesAsNumbers() throws Exception {
    var store =
        new DocumentStore(
            new JdbcTemplate(
                new DriverManagerDataSource(
                    "jdbc:h2:mem:mysql_types_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                    "sa",
                    "")));
    var vault = new SecretVault(dir.toString(), "");
    var connections = new BusinessConnections(vault);
    var catalog = new CatalogService(new JdbcSourceRepository(store), vault, connections);
    var source =
        catalog.create(
            "MySQL type fixture",
            "MYSQL",
            System.getenv("MATEDATA_TEST_MYSQL_URL"),
            System.getenv().getOrDefault("MATEDATA_TEST_MYSQL_USER", "matedata_reader"),
            System.getenv().getOrDefault("MATEDATA_TEST_MYSQL_PASSWORD", ""));
    var semantic = new SemanticService(new JdbcModelRepository(store), catalog);
    var table = System.getenv("MATEDATA_TEST_MYSQL_TYPE_TABLE");
    for (String column : List.of("alias_value", "tiny_value")) {
      assertThat(
              catalog.tables(source.id()).stream()
                  .filter(t -> t.name().equals(table))
                  .findFirst()
                  .orElseThrow()
                  .columns()
                  .stream()
                  .filter(c -> c.name().equals(column))
                  .findFirst()
                  .orElseThrow()
                  .type())
          .isEqualTo("TINYINT");
      var model =
          semantic.create(
              new SemanticModel(
                  "types_" + column,
                  "Types",
                  "",
                  source.id(),
                  table,
                  List.of(new SemanticModel.Metric("total", "Total", "id", "SUM", List.of())),
                  List.of(new SemanticModel.Dimension("flag", "Flag", column, List.of()))));
      assertThat(model.dimensions().getFirst().valueType()).isEqualTo(ValueType.NUMBER);
      var executor = new JdbcQueryExecutor(catalog, connections);
      var plan = new QueryPlan("total", "flag", Map.of(), 10);
      var result = executor.execute(model, plan, new SemanticCompiler().compile(model, plan));
      assertThat(result.rows())
          .extracting(row -> row.get("flag"))
          .containsExactlyInAnyOrder(0, 1, 2);
      for (String value : List.of("0", "1", "2")) {
        var filtered = new QueryPlan("total", "flag", Map.of("flag", value), 10);
        var single =
            executor.execute(model, filtered, new SemanticCompiler().compile(model, filtered));
        assertThat(single.rows()).hasSize(1);
        assertThat(single.rows().getFirst().get("flag")).isEqualTo(Integer.valueOf(value));
      }
    }
    var bitModel =
        new SemanticModel(
            "real_bits",
            "Bits",
            "",
            source.id(),
            table,
            List.of(new SemanticModel.Metric("total", "Total", "id", "SUM", List.of())),
            List.of(new SemanticModel.Dimension("flag", "Flag", "real_bits", List.of())),
            SemanticModel.Dialect.MYSQL);
    assertThatThrownBy(() -> semantic.create(bitModel))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持");
    var bitPlan = new QueryPlan("total", "flag", Map.of(), 10);
    assertThatThrownBy(
            () ->
                new JdbcQueryExecutor(catalog, connections)
                    .execute(bitModel, bitPlan, new SemanticCompiler().compile(bitModel, bitPlan)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持");
  }
}
