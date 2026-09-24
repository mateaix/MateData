package io.matedata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.matedata.catalog.application.CatalogService;
import io.matedata.catalog.domain.*;
import io.matedata.catalog.infrastructure.BusinessConnections;
import io.matedata.conversation.infrastructure.JdbcQueryExecutor;
import io.matedata.semantic.*;
import io.matedata.semantic.application.SemanticService;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class JdbcTypeSafetyTest {
  SemanticModel model(String aggregation, boolean dimension) {
    return new SemanticModel(
        "types",
        "Types",
        "",
        "source",
        "items",
        List.of(new SemanticModel.Metric("value", "Value", "payload", aggregation, List.of())),
        dimension
            ? List.of(new SemanticModel.Dimension("label", "Label", "payload", List.of()))
            : List.of());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"BYTEA", "BLOB", "JSON", "JSONB", "ARRAY", "UUID", "GEOMETRY", "TIMESTAMP_ARRAY"})
  void publicationRejectsUnsupportedTypesButAllowsCount(String type) {
    var catalog = mock(CatalogService.class);
    when(catalog.require("source"))
        .thenReturn(new DataSourceDefinition("source", "Source", "POSTGRESQL", "", "", "", "", ""));
    when(catalog.tables("source"))
        .thenReturn(List.of(new SourceTable("items", List.of(new SourceColumn("payload", type)))));
    var models = mock(ModelRepository.class);
    when(models.createIfAbsent(any())).thenReturn(true);
    var service = new SemanticService(models, catalog);
    assertThatThrownBy(() -> service.create(model("COUNT", true)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持");
    assertThatThrownBy(() -> service.create(model("MIN", false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持");
    assertThatThrownBy(() -> service.create(model("MAX", false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持");
    assertThat(service.create(model("COUNT", false)).metrics()).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CHAR",
        "VARCHAR",
        "CHARACTER VARYING",
        "BPCHAR",
        "TEXT",
        "LONGTEXT",
        "CLOB",
        "BOOLEAN",
        "BOOL",
        "INTEGER",
        "INT8",
        "DECIMAL",
        "DOUBLE PRECISION",
        "BIGINT UNSIGNED",
        "DATE",
        "TIME",
        "TIME WITHOUT TIME ZONE",
        "TIMESTAMP",
        "TIMESTAMP WITHOUT TIME ZONE",
        "TIMESTAMPTZ",
        "TIMESTAMP WITH TIME ZONE",
        "DATETIME"
      })
  void keepsSupportedPhysicalTypesPublishable(String type) {
    var catalog = mock(CatalogService.class);
    when(catalog.require("source"))
        .thenReturn(new DataSourceDefinition("source", "Source", "POSTGRESQL", "", "", "", "", ""));
    when(catalog.tables("source"))
        .thenReturn(List.of(new SourceTable("items", List.of(new SourceColumn("payload", type)))));
    var models = mock(ModelRepository.class);
    when(models.createIfAbsent(any())).thenReturn(true);
    var service = new SemanticService(models, catalog);
    assertThat(service.create(model("MIN", true)).dimensions()).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        Types.BINARY,
        Types.VARBINARY,
        Types.LONGVARBINARY,
        Types.BLOB,
        Types.ARRAY,
        Types.OTHER,
        Types.JAVA_OBJECT,
        Types.SQLXML,
        Types.STRUCT
      })
  void executionRejectsUnexpectedJdbcTypesBeforeReadingValues(int type) throws Exception {
    var catalog = mock(CatalogService.class);
    var connections = mock(BusinessConnections.class);
    var c = mock(Connection.class);
    var s = mock(PreparedStatement.class);
    var rs = mock(ResultSet.class);
    var md = mock(ResultSetMetaData.class);
    when(catalog.require("source"))
        .thenReturn(new DataSourceDefinition("source", "Source", "DEMO", "", "", "", "", ""));
    when(connections.open(any())).thenReturn(c);
    when(c.prepareStatement(anyString())).thenReturn(s);
    when(c.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(s);
    when(s.executeQuery()).thenReturn(rs);
    when(rs.getMetaData()).thenReturn(md);
    when(md.getColumnCount()).thenReturn(1);
    when(md.getColumnType(1)).thenReturn(type);
    when(md.getColumnLabel(1)).thenReturn("value");
    when(rs.next()).thenReturn(true, false);
    var model = model("MIN", false);
    var plan = new QueryPlan("value", null, Map.of(), 10);
    assertThatThrownBy(
            () ->
                new JdbcQueryExecutor(catalog, connections)
                    .execute(model, plan, new SemanticCompiler().compile(model, plan)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持");
    verify(rs, never()).getObject(anyInt());
    verify(rs).close();
    verify(s).close();
    verify(c).close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"JSON", "ENUM", "CUSTOM_TEXT"})
  void rejectsUnsupportedNativeTypesEvenWhenDriverReportsVarchar(String nativeType)
      throws Exception {
    var catalog = mock(CatalogService.class);
    var connections = mock(BusinessConnections.class);
    var c = mock(Connection.class);
    var s = mock(PreparedStatement.class);
    var rs = mock(ResultSet.class);
    var md = mock(ResultSetMetaData.class);
    when(catalog.require("source"))
        .thenReturn(new DataSourceDefinition("source", "Source", "DEMO", "", "", "", "", ""));
    when(connections.open(any())).thenReturn(c);
    when(c.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(s);
    when(s.executeQuery()).thenReturn(rs);
    when(rs.getMetaData()).thenReturn(md);
    when(md.getColumnCount()).thenReturn(1);
    when(md.getColumnType(1)).thenReturn(Types.VARCHAR);
    when(md.getColumnTypeName(1)).thenReturn(nativeType);
    when(md.getColumnLabel(1)).thenReturn("value");
    when(rs.next()).thenReturn(true, false);
    var model = model("MIN", false);
    var plan = new QueryPlan("value", null, Map.of(), 10);
    assertThatThrownBy(
            () ->
                new JdbcQueryExecutor(catalog, connections)
                    .execute(model, plan, new SemanticCompiler().compile(model, plan)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持");
    verify(rs, never()).getCharacterStream(anyInt());
  }

  @ParameterizedTest
  @CsvSource({
    "BIT,-7,false",
    "VARBIT,-7,false",
    "bool,-7,true",
    "BOOLEAN,-7,true",
    "BOOL,16,true",
    "BOOLEAN,16,true"
  })
  void acceptsOnlyNativeBooleansForJdbcBit(String nativeType, int jdbcType, boolean supported)
      throws Exception {
    var catalog = mock(CatalogService.class);
    var connections = mock(BusinessConnections.class);
    var c = mock(Connection.class);
    var s = mock(PreparedStatement.class);
    var rs = mock(ResultSet.class);
    var md = mock(ResultSetMetaData.class);
    when(catalog.require("source"))
        .thenReturn(new DataSourceDefinition("source", "Source", "DEMO", "", "", "", "", ""));
    when(connections.open(any())).thenReturn(c);
    when(c.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(s);
    when(s.executeQuery()).thenReturn(rs);
    when(rs.getMetaData()).thenReturn(md);
    when(md.getColumnCount()).thenReturn(1);
    when(md.getColumnType(1)).thenReturn(jdbcType);
    when(md.getColumnTypeName(1)).thenReturn(nativeType);
    when(md.getPrecision(1)).thenReturn(supported ? 1 : 8);
    when(md.getColumnLabel(1)).thenReturn("value");
    when(rs.next()).thenReturn(true, false);
    when(rs.getBoolean(1)).thenReturn(true);
    var model = model("MIN", false);
    var plan = new QueryPlan("value", null, Map.of(), 10);
    var executor = new JdbcQueryExecutor(catalog, connections);
    var compiled = new SemanticCompiler().compile(model, plan);
    if (supported) {
      assertThat(executor.execute(model, plan, compiled).rows().getFirst().get("value"))
          .isEqualTo(true);
      verify(rs).getBoolean(1);
    } else {
      assertThatThrownBy(() -> executor.execute(model, plan, compiled))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("不支持");
      verify(rs, never()).getBoolean(anyInt());
      verify(rs, never()).getObject(anyInt());
    }
    verify(rs).close();
    verify(s).close();
    verify(c).close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"POSTGRESQL", "MYSQL"})
  void configuresIncrementalFetchingAndClosesResources(String type) throws Exception {
    var catalog = mock(CatalogService.class);
    var connections = mock(BusinessConnections.class);
    var c = mock(Connection.class);
    var s = mock(PreparedStatement.class);
    var rs = mock(ResultSet.class);
    when(catalog.require("source"))
        .thenReturn(new DataSourceDefinition("source", "Source", type, "", "", "", "", ""));
    when(connections.open(any())).thenReturn(c);
    when(c.prepareStatement(anyString())).thenReturn(s);
    when(c.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(s);
    when(s.executeQuery()).thenReturn(rs);
    when(rs.getMetaData()).thenReturn(mock(ResultSetMetaData.class));
    var model = model("COUNT", false);
    var plan = new QueryPlan("value", null, Map.of(), 10);
    new JdbcQueryExecutor(catalog, connections)
        .execute(model, plan, new SemanticCompiler().compile(model, plan));
    verify(c)
        .prepareStatement(
            anyString(), eq(ResultSet.TYPE_FORWARD_ONLY), eq(ResultSet.CONCUR_READ_ONLY));
    if (type.equals("POSTGRESQL")) verify(c).setAutoCommit(false);
    verify(s).setFetchSize(type.equals("POSTGRESQL") ? 64 : Integer.MIN_VALUE);
    verify(s).setQueryTimeout(10);
    verify(s).setMaxRows(10);
    verify(rs).close();
    verify(s).close();
    verify(c).close();
  }
}
