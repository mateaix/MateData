package io.matedata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.matedata.catalog.application.CatalogService;
import io.matedata.catalog.domain.DataSourceDefinition;
import io.matedata.catalog.infrastructure.BusinessConnections;
import io.matedata.conversation.infrastructure.JdbcQueryExecutor;
import io.matedata.semantic.*;
import io.matedata.semantic.application.SemanticService;
import io.matedata.shared.infrastructure.SecretVault;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "MATEDATA_TEST_POSTGRES_ADMIN_URL", matches = ".+")
class PostgresSystemBoundaryTest {
  @TempDir Path directory;

  @Test
  void rejectsImplicitSystemTableResolutionButAllowsRealBusinessTable() throws Exception {
    String schema = "boundary_" + UUID.randomUUID().toString().replace("-", "");
    String fallback = schema + "_fallback";
    var preferBusiness = new java.util.concurrent.atomic.AtomicBoolean();
    String reader = System.getenv().getOrDefault("MATEDATA_TEST_POSTGRES_USER", "matedata_reader");
    try (var admin =
            DriverManager.getConnection(
                System.getenv("MATEDATA_TEST_POSTGRES_ADMIN_URL"),
                System.getenv().getOrDefault("MATEDATA_TEST_POSTGRES_ADMIN_USER", "matedata_test"),
                System.getenv().getOrDefault("MATEDATA_TEST_POSTGRES_ADMIN_PASSWORD", ""));
        var setup = admin.createStatement()) {
      setup.execute("CREATE SCHEMA " + schema);
      try {
        setup.execute("CREATE SCHEMA " + fallback);
        setup.execute("CREATE TABLE " + fallback + ".sales (oid BIGINT)");
        setup.execute("INSERT INTO " + fallback + ".sales VALUES (2)");
        setup.execute(
            "GRANT USAGE ON SCHEMA " + fallback + " TO \"" + reader.replace("\"", "\"\"") + "\"");
        setup.execute(
            "GRANT SELECT ON ALL TABLES IN SCHEMA "
                + fallback
                + " TO \""
                + reader.replace("\"", "\"\"")
                + "\"");
        setup.execute("CREATE TABLE " + schema + ".pg_class (oid BIGINT)");
        setup.execute("INSERT INTO " + schema + ".pg_class VALUES (1)");
        setup.execute("CREATE TABLE " + schema + ".\"PG_CLASS\" (oid BIGINT)");
        setup.execute("INSERT INTO " + schema + ".\"PG_CLASS\" VALUES (1)");
        setup.execute("CREATE TABLE " + schema + ".sales (oid BIGINT)");
        setup.execute("INSERT INTO " + schema + ".sales VALUES (1)");
        setup.execute(
            "GRANT USAGE ON SCHEMA " + schema + " TO \"" + reader.replace("\"", "\"\"") + "\"");
        setup.execute(
            "GRANT SELECT ON ALL TABLES IN SCHEMA "
                + schema
                + " TO \""
                + reader.replace("\"", "\"\"")
                + "\"");
        var vault = new SecretVault(directory.toString(), "");
        var source =
            new DataSourceDefinition(
                "fixture",
                "Fixture",
                "POSTGRESQL",
                System.getenv("MATEDATA_TEST_POSTGRES_URL"),
                reader,
                vault.encrypt(System.getenv().getOrDefault("MATEDATA_TEST_POSTGRES_PASSWORD", "")),
                "AVAILABLE",
                "");
        var connections =
            new BusinessConnections(vault) {
              @Override
              public Connection open(DataSourceDefinition definition) throws SQLException {
                var connection = super.open(definition);
                try (var statement = connection.createStatement()) {
                  statement.execute(
                      "SET search_path TO "
                          + schema
                          + (preferBusiness.get() ? ", pg_catalog, " : ", ")
                          + fallback);
                }
                return connection;
              }
            };
        var catalog = mock(CatalogService.class);
        when(catalog.require("fixture")).thenReturn(source);
        when(catalog.tables("fixture")).thenAnswer(ignored -> connections.tables(source));
        var models = mock(ModelRepository.class);
        when(models.createIfAbsent(any())).thenReturn(true);
        var semantic = new SemanticService(models, catalog);
        var colliding = semantic.create(model("collision", "pg_class"));
        // Confirm the actual engine resolves the unqualified name to its implicit system schema.
        try (var connection = connections.open(source);
            var query = connection.createStatement();
            var rows =
                query.executeQuery(
                    "SELECT n.nspname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE c.oid=pg_catalog.to_regclass('\"pg_class\"')")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("pg_catalog");
        }
        var executor = new JdbcQueryExecutor(catalog, connections);
        var plan = new QueryPlan("count", null, Map.of(), 10);
        assertThatThrownBy(
                () ->
                    executor.execute(
                        colliding, plan, new SemanticCompiler().compile(colliding, plan)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("业务");
        var legacyCollision = model("legacy_collision", "PG_CLASS");
        assertThat(legacyCollision.dialect()).isNull();
        assertThatThrownBy(
                () ->
                    executor.execute(
                        legacyCollision,
                        plan,
                        new SemanticCompiler().compile(legacyCollision, plan)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("业务");
        var canonicalUppercase = semantic.create(model("canonical_uppercase", "PG_CLASS"));
        assertThat(
                executor
                    .execute(
                        canonicalUppercase,
                        plan,
                        new SemanticCompiler().compile(canonicalUppercase, plan))
                    .rows()
                    .getFirst()
                    .get("count"))
            .isEqualTo(1L);
        var legacyBusiness = model("legacy_business", "SALES");
        assertThat(
                executor
                    .execute(
                        legacyBusiness, plan, new SemanticCompiler().compile(legacyBusiness, plan))
                    .rows()
                    .getFirst()
                    .get("count"))
            .isEqualTo(1L);
        var business = semantic.create(model("business", "sales"));
        assertThat(
                executor
                    .execute(business, plan, new SemanticCompiler().compile(business, plan))
                    .rows()
                    .getFirst()
                    .get("count"))
            .isEqualTo(1L);
        // A matching business name remains usable when the server resolves it to that schema.
        preferBusiness.set(true);
        assertThat(
                executor
                    .execute(colliding, plan, new SemanticCompiler().compile(colliding, plan))
                    .rows()
                    .getFirst()
                    .get("count"))
            .isEqualTo(1L);
        preferBusiness.set(false);
        setup.execute("DROP TABLE " + schema + ".sales");
        assertThatThrownBy(
                () ->
                    executor.execute(
                        business, plan, new SemanticCompiler().compile(business, plan)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("业务");
      } finally {
        setup.execute("DROP SCHEMA " + schema + " CASCADE");
        setup.execute("DROP SCHEMA IF EXISTS " + fallback + " CASCADE");
      }
    }
  }

  private SemanticModel model(String id, String table) {
    return new SemanticModel(
        id,
        "Fixture",
        "",
        "fixture",
        table,
        List.of(new SemanticModel.Metric("count", "Count", "oid", "COUNT", List.of())),
        List.of());
  }
}
