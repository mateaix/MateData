package io.matedata;

import static org.assertj.core.api.Assertions.*;

import io.matedata.catalog.application.CatalogService;
import io.matedata.catalog.infrastructure.BusinessConnections;
import io.matedata.catalog.infrastructure.JdbcSourceRepository;
import io.matedata.conversation.infrastructure.JdbcQueryExecutor;
import io.matedata.semantic.*;
import io.matedata.shared.infrastructure.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Opt-in only: an administrator holds a lock on the disposable test fixture. */
@EnabledIfEnvironmentVariable(named = "MATEDATA_TEST_POSTGRES_ADMIN_URL", matches = ".+")
class PostgresQueryBudgetTest {
  @TempDir Path directory;

  @Test
  void cancelsBlockedSqlAndCanQueryAgainAfterLockRelease() throws Exception {
    var store =
        new DocumentStore(
            new JdbcTemplate(
                new DriverManagerDataSource(
                    "jdbc:h2:mem:budget_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")));
    var vault = new SecretVault(directory.toString(), "");
    var connections = new BusinessConnections(vault);
    var catalog = new CatalogService(new JdbcSourceRepository(store), vault, connections);
    var source =
        catalog.create(
            "Budget fixture",
            "POSTGRESQL",
            System.getenv("MATEDATA_TEST_POSTGRES_URL"),
            System.getenv().getOrDefault("MATEDATA_TEST_POSTGRES_USER", "matedata_reader"),
            System.getenv().getOrDefault("MATEDATA_TEST_POSTGRES_PASSWORD", ""));
    var model =
        new SemanticModel(
            "budget_sales",
            "Budget fixture",
            "",
            source.id(),
            "Sales_Data",
            List.of(new SemanticModel.Metric("revenue", "销售额", "Revenue", "SUM", List.of())),
            List.of(new SemanticModel.Dimension("region", "区域", "Region", List.of())),
            SemanticModel.Dialect.ANSI);
    var plan = new QueryPlan("revenue", "region", Map.of(), 10);
    var compiled = new SemanticCompiler().compile(model, plan);
    var executor = new JdbcQueryExecutor(catalog, connections);
    try (var lock =
        DriverManager.getConnection(
            System.getenv("MATEDATA_TEST_POSTGRES_ADMIN_URL"),
            System.getenv().getOrDefault("MATEDATA_TEST_POSTGRES_ADMIN_USER", "matedata_test"),
            System.getenv().getOrDefault("MATEDATA_TEST_POSTGRES_ADMIN_PASSWORD", ""))) {
      lock.setAutoCommit(false);
      try (var statement = lock.createStatement()) {
        statement.setQueryTimeout(5);
        statement.execute("LOCK TABLE \"Sales_Data\" IN ACCESS EXCLUSIVE MODE");
      }
      long started = System.nanoTime();
      try {
        assertThatThrownBy(() -> executor.execute(model, plan, compiled))
            .isInstanceOfSatisfying(
                SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("57014"));
        assertThat(Duration.ofNanos(System.nanoTime() - started))
            .isBetween(Duration.ofSeconds(8), Duration.ofSeconds(14));
      } finally {
        lock.rollback();
      }
    }
    assertThat(executor.execute(model, plan, compiled).rows()).hasSize(2);
  }
}
