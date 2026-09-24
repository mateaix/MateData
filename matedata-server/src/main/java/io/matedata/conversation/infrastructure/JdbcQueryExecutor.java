package io.matedata.conversation.infrastructure;

import io.matedata.catalog.application.CatalogService;
import io.matedata.catalog.infrastructure.BusinessConnections;
import io.matedata.conversation.QueryExecutor;
import io.matedata.semantic.*;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class JdbcQueryExecutor implements QueryExecutor {
  private final CatalogService catalog;
  private final BusinessConnections connections;

  public JdbcQueryExecutor(CatalogService catalog, BusinessConnections connections) {
    this.catalog = catalog;
    this.connections = connections;
  }

  private Object scalar(ResultSet rows, ResultSetMetaData metadata, int column, TextBudget budget)
      throws SQLException {
    int type = metadata.getColumnType(column);
    if (type == Types.CHAR
        || type == Types.NCHAR
        || type == Types.VARCHAR
        || type == Types.NVARCHAR
        || type == Types.LONGVARCHAR
        || type == Types.LONGNVARCHAR
        || type == Types.CLOB
        || type == Types.NCLOB) {
      String nativeType = metadata.getColumnTypeName(column);
      if (nativeType == null
          || !Set.of(
                  "CHAR",
                  "CHARACTER",
                  "VARCHAR",
                  "CHARACTER VARYING",
                  "NCHAR",
                  "NVARCHAR",
                  "LONGVARCHAR",
                  "LONGNVARCHAR",
                  "BPCHAR",
                  "TEXT",
                  "TINYTEXT",
                  "MEDIUMTEXT",
                  "LONGTEXT",
                  "CLOB",
                  "NCLOB",
                  "CHARACTER LARGE OBJECT",
                  "NATIONAL CHARACTER LARGE OBJECT")
              .contains(nativeType.toUpperCase(Locale.ROOT)))
        throw new IllegalArgumentException("查询结果包含不支持的字段类型，请刷新数据集并选择支持的字段");
      try (var reader = rows.getCharacterStream(column)) {
        return reader == null ? null : budget.read(reader);
      } catch (java.io.IOException failure) {
        throw new SQLException("无法读取文本查询结果", failure);
      }
    }
    if (!Set.of(
            Types.BIT,
            Types.BOOLEAN,
            Types.TINYINT,
            Types.SMALLINT,
            Types.INTEGER,
            Types.BIGINT,
            Types.REAL,
            Types.FLOAT,
            Types.DOUBLE,
            Types.NUMERIC,
            Types.DECIMAL,
            Types.DATE,
            Types.TIME,
            Types.TIMESTAMP,
            Types.TIMESTAMP_WITH_TIMEZONE)
        .contains(type)) throw new IllegalArgumentException("查询结果包含不支持的字段类型，请刷新数据集并选择支持的字段");
    if (type == Types.BIT || type == Types.BOOLEAN) {
      String nativeType = metadata.getColumnTypeName(column);
      if (!"BOOL".equalsIgnoreCase(nativeType) && !"BOOLEAN".equalsIgnoreCase(nativeType))
        throw new IllegalArgumentException("查询结果包含不支持的字段类型，请刷新数据集并选择支持的字段");
      boolean value = rows.getBoolean(column);
      return rows.wasNull() ? null : value;
    }
    // Normalize temporal results before JSON persistence, preserving their logical type.
    if (metadata.getColumnType(column) == Types.TIMESTAMP_WITH_TIMEZONE
        || "timestamptz".equalsIgnoreCase(metadata.getColumnTypeName(column))) {
      var value = rows.getObject(column, java.time.OffsetDateTime.class);
      return value == null ? null : value.toString();
    }
    if (metadata.getColumnType(column) == Types.TIME) {
      var time = rows.getObject(column, java.time.LocalTime.class);
      return time == null ? null : time.toString();
    }
    Object value = rows.getObject(column);
    if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
    if (value instanceof java.sql.Time time) return time.toLocalTime().toString();
    if (value instanceof java.sql.Timestamp timestamp)
      return timestamp.toLocalDateTime().toString();
    if (value instanceof java.time.temporal.TemporalAccessor) return value.toString();
    return value;
  }

  public Result execute(SemanticModel model, QueryPlan plan, CompiledQuery query)
      throws SQLException {
    new SqlGuard().verify(query, model, plan);
    var source = catalog.require(model.sourceId());
    try (var c = connections.open(source)) {
      // pgJDBC needs a transaction for cursor fetching; closing this dedicated connection
      // also rolls back the read-only transaction on success or failure.
      if (source.type().equals("POSTGRESQL")) c.setAutoCommit(false);
      connections.verifyTableResolution(c, source, model.tableName(), model.dialect() != null);
      try (var s =
          c.prepareStatement(
              query.sql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
        // Both drivers otherwise buffer the complete result before executeQuery returns.
        if (source.type().equals("POSTGRESQL")) s.setFetchSize(64);
        if (source.type().equals("MYSQL")) s.setFetchSize(Integer.MIN_VALUE);
        s.setQueryTimeout(10);
        s.setMaxRows(Math.min(plan.limit(), 1000));
        for (int i = 0; i < query.parameters().size(); i++)
          s.setObject(i + 1, query.parameters().get(i));
        try (var rs = s.executeQuery()) {
          var md = rs.getMetaData();
          var columns = new ArrayList<String>();
          var rows = new ArrayList<Map<String, Object>>();
          var budget = new TextBudget();
          for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnLabel(i));
          while (rs.next() && rows.size() < 1000) {
            var row = new LinkedHashMap<String, Object>();
            for (int i = 1; i <= columns.size(); i++)
              row.put(columns.get(i - 1), scalar(rs, md, i, budget));
            rows.add(row);
          }
          return new Result(columns, rows);
        }
      }
    }
  }

  private static final class TextBudget {
    private int remaining = 1024 * 1024;

    String read(java.io.Reader reader) throws java.io.IOException {
      var text = new StringBuilder();
      char[] buffer = new char[4096];
      int count;
      while ((count = reader.read(buffer)) != -1) {
        if (count > remaining || text.length() + count > 64 * 1024)
          throw new IllegalArgumentException("查询结果超过文本容量上限，请缩小范围或使用汇总指标");
        remaining -= count;
        text.append(buffer, 0, count);
      }
      return text.toString();
    }
  }
}
