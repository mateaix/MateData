package io.matedata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.matedata.catalog.domain.DataSourceDefinition;
import io.matedata.catalog.infrastructure.BusinessConnections;
import io.matedata.shared.infrastructure.SecretVault;
import java.sql.*;
import java.util.ArrayList;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SystemObjectBoundaryTest {
  DataSourceDefinition source(String type, String database) {
    return new DataSourceDefinition(
        "old_source",
        "Persisted source",
        type,
        "jdbc:" + (type.equals("MYSQL") ? "mysql" : "postgresql") + "://db/" + database,
        "reader",
        "",
        "AVAILABLE",
        "");
  }

  @Test
  void rechecksPersistedMysqlSystemSourceBeforeOpeningConnection() throws Exception {
    var vault = mock(SecretVault.class);
    when(vault.decrypt(anyString())).thenReturn("");
    var connections = new BusinessConnections(vault);
    var connection = mock(Connection.class);
    try (var driver = new DriverFixture(connection)) {
      assertThatThrownBy(() -> connections.open(source("MYSQL", "mysql")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("系统");
      verify(driver.driver, never()).connect(anyString(), any(Properties.class));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"pg_catalog", "information_schema", "pg_toast", "pg_temp_3", "PG_CATALOG"})
  @NullSource
  void rejectsSystemOrUnknownPostgresCurrentSchemaAndClosesConnection(String schema)
      throws Exception {
    var vault = mock(SecretVault.class);
    when(vault.decrypt(anyString())).thenReturn("");
    var connections = new BusinessConnections(vault);
    var connection = mock(Connection.class);
    when(connection.getCatalog()).thenReturn("postgres");
    when(connection.getSchema()).thenReturn(schema);
    try (var driver = new DriverFixture(connection)) {
      assertThatThrownBy(() -> connections.open(source("POSTGRESQL", "postgres")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("业务");
      verify(connection).close();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"mysql", "information_schema", "performance_schema", "sys"})
  void rejectsSystemMysqlCurrentCatalogEvenForBusinessUrl(String catalog) throws Exception {
    var vault = mock(SecretVault.class);
    when(vault.decrypt(anyString())).thenReturn("");
    var connections = new BusinessConnections(vault);
    var connection = mock(Connection.class);
    when(connection.getCatalog()).thenReturn(catalog);
    try (var driver = new DriverFixture(connection)) {
      assertThatThrownBy(() -> connections.open(source("MYSQL", "analytics")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("业务");
      verify(connection).close();
    }
  }

  @ParameterizedTest
  @CsvSource({"POSTGRESQL,postgres,public", "POSTGRESQL,postgres,analytics", "MYSQL,analytics,"})
  void allowsOrdinaryCurrentNamespaces(String type, String catalog, String schema)
      throws Exception {
    var vault = mock(SecretVault.class);
    when(vault.decrypt(anyString())).thenReturn("");
    var connections = new BusinessConnections(vault);
    var connection = mock(Connection.class);
    when(connection.getCatalog()).thenReturn(catalog);
    when(connection.getSchema()).thenReturn(schema);
    try (var driver = new DriverFixture(connection)) {
      assertThat(connections.open(source(type, catalog))).isSameAs(connection);
      verify(connection).setReadOnly(true);
      verify(connection, never()).close();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"POSTGRESQL", "MYSQL"})
  void metadataExcludesTablesOutsideTheBusinessNamespace(String type) throws Exception {
    var vault = mock(SecretVault.class);
    when(vault.decrypt(anyString())).thenReturn("");
    var connections = new BusinessConnections(vault);
    var connection = mock(Connection.class);
    var metadata = mock(DatabaseMetaData.class);
    var rows = mock(ResultSet.class);
    var columns = mock(ResultSet.class);
    when(connection.getCatalog()).thenReturn("analytics");
    when(connection.getSchema()).thenReturn(type.equals("POSTGRESQL") ? "public" : null);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getTables(any(), any(), any(), any())).thenReturn(rows);
    when(metadata.getColumns(any(), any(), any(), any())).thenReturn(columns);
    when(rows.next()).thenReturn(true, true, false);
    when(rows.getString("TABLE_NAME")).thenReturn("sales");
    when(rows.getString("TABLE_CAT"))
        .thenReturn(type.equals("MYSQL") ? "mysql" : "analytics", "analytics");
    when(rows.getString("TABLE_SCHEM"))
        .thenReturn(
            type.equals("POSTGRESQL") ? "pg_catalog" : null,
            type.equals("POSTGRESQL") ? "public" : null);
    try (var driver = new DriverFixture(connection)) {
      assertThat(connections.tables(source(type, "analytics")))
          .extracting(t -> t.name())
          .containsExactly("sales");
      verify(metadata, times(1)).getColumns(any(), any(), any(), any());
      verify(connection).close();
    }
  }

  /** Isolated JDBC driver double; restore real drivers after each test. */
  private static final class DriverFixture implements AutoCloseable {
    final Driver driver = mock(Driver.class);
    final ArrayList<Driver> removed = new ArrayList<>();

    DriverFixture(Connection connection) throws SQLException {
      var registered = DriverManager.getDrivers();
      while (registered.hasMoreElements()) {
        var current = registered.nextElement();
        if (current.acceptsURL("jdbc:mysql://db/analytics")
            || current.acceptsURL("jdbc:postgresql://db/analytics")) {
          removed.add(current);
          DriverManager.deregisterDriver(current);
        }
      }
      when(driver.connect(anyString(), any(Properties.class))).thenReturn(connection);
      DriverManager.registerDriver(driver);
    }

    @Override
    public void close() throws SQLException {
      DriverManager.deregisterDriver(driver);
      for (var original : removed) DriverManager.registerDriver(original);
    }
  }
}
