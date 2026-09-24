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

  private Object scalar(ResultSet rows, ResultSetMetaData metadata, int column)
      throws SQLException {
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
    try (var c = connections.open(catalog.require(model.sourceId()));
        var s = c.prepareStatement(query.sql())) {
      s.setQueryTimeout(10);
      s.setMaxRows(Math.min(plan.limit(), 1000));
      for (int i = 0; i < query.parameters().size(); i++)
        s.setObject(i + 1, query.parameters().get(i));
      try (var rs = s.executeQuery()) {
        var md = rs.getMetaData();
        var columns = new ArrayList<String>();
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnLabel(i));
        while (rs.next() && rows.size() < 1000) {
          var row = new LinkedHashMap<String, Object>();
          for (int i = 1; i <= columns.size(); i++) row.put(columns.get(i - 1), scalar(rs, md, i));
          rows.add(row);
        }
        return new Result(columns, rows);
      }
    }
  }
}
