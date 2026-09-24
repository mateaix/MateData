package io.matedata.catalog.infrastructure;

import io.matedata.catalog.domain.*;
import io.matedata.shared.ApplicationException;
import io.matedata.shared.ApplicationException.Kind;
import io.matedata.shared.infrastructure.SecretVault;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.springframework.stereotype.Component;

@Component
public class BusinessConnections implements io.matedata.catalog.domain.SourceConnections {
  private final SecretVault vault;

  public BusinessConnections(SecretVault vault) throws SQLException {
    this.vault = vault;
    seedDemo();
  }

  public Connection open(DataSourceDefinition source) throws SQLException {
    if (!source.type().equals("DEMO")) ConnectionPolicy.validate(source.type(), source.jdbcUrl());
    var p = new Properties();
    p.setProperty("user", source.username());
    p.setProperty(
        "password", source.type().equals("DEMO") ? "" : vault.decrypt(source.encryptedPassword()));
    if (source.type().equals("POSTGRESQL")) {
      p.setProperty("connectTimeout", "5");
      p.setProperty("socketTimeout", "15");
      p.setProperty("readOnly", "true");
    }
    if (source.type().equals("MYSQL")) {
      p.setProperty("connectTimeout", "5000");
      p.setProperty("socketTimeout", "15000");
      p.setProperty("allowLoadLocalInfile", "false");
      p.setProperty("allowMultiQueries", "false");
      // BOOLEAN is a TINYINT(1) alias and may contain values other than 0/1.
      p.setProperty("tinyInt1isBit", "false");
    }
    var c = DriverManager.getConnection(source.jdbcUrl(), p);
    try {
      c.setReadOnly(true);
      ConnectionPolicy.validateNamespace(source.type(), c.getCatalog(), c.getSchema());
      return c;
    } catch (SQLException | RuntimeException failure) {
      try {
        c.close();
      } catch (SQLException closing) {
        failure.addSuppressed(closing);
      }
      throw failure;
    }
  }

  /** Check the server's unqualified lookup, including PostgreSQL's implicit pg_catalog path. */
  public void verifyTableResolution(
      Connection connection, DataSourceDefinition source, String table, boolean quoted)
      throws SQLException {
    if (!source.type().equals("POSTGRESQL")) return;
    String schema = connection.getSchema();
    ConnectionPolicy.validateNamespace(source.type(), connection.getCatalog(), schema);
    try (var statement =
        connection.prepareStatement(
            "SELECT n.nspname, c.relkind FROM pg_catalog.pg_class c "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE c.oid = pg_catalog.to_regclass(?)")) {
      statement.setQueryTimeout(10);
      statement.setString(1, quoted ? "\"" + table.replace("\"", "\"\"") + "\"" : table);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()
            || !schema.equals(rows.getString(1))
            || !java.util.Set.of("r", "p").contains(rows.getString(2)))
          throw new IllegalArgumentException("查询表未解析到当前业务 schema 的普通表，请刷新数据集配置");
      }
    }
  }

  @Override
  public boolean test(DataSourceDefinition source) {
    try (var connection = open(source)) {
      return connection.isValid(5);
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public List<SourceTable> tables(DataSourceDefinition source) {
    var tables = new ArrayList<SourceTable>();
    try (var c = open(source)) {
      var meta = c.getMetaData();
      String schema = source.type().equals("DEMO") ? "PUBLIC" : c.getSchema();
      try (var r = meta.getTables(c.getCatalog(), schema, "%", new String[] {"TABLE"})) {
        while (r.next() && tables.size() < 100) {
          if (!Objects.equals(c.getCatalog(), r.getString("TABLE_CAT"))
              || !Objects.equals(schema, r.getString("TABLE_SCHEM"))) continue;
          String table = r.getString("TABLE_NAME");
          var columns = new ArrayList<SourceColumn>();
          String escape = meta.getSearchStringEscape();
          String pattern =
              escape == null || escape.isEmpty()
                  ? table
                  : table
                      .replace(escape, escape + escape)
                      .replace("_", escape + "_")
                      .replace("%", escape + "%");
          try (var cr = meta.getColumns(c.getCatalog(), schema, pattern, "%")) {
            while (cr.next())
              if (table.equals(cr.getString("TABLE_NAME"))
                  && Objects.equals(c.getCatalog(), cr.getString("TABLE_CAT"))
                  && Objects.equals(schema, cr.getString("TABLE_SCHEM")))
                columns.add(
                    new SourceColumn(cr.getString("COLUMN_NAME"), cr.getString("TYPE_NAME")));
          }
          tables.add(new SourceTable(table, columns));
        }
      }
      return List.copyOf(tables);
    } catch (Exception e) {
      throw new ApplicationException(Kind.CONNECTION_FAILED, "无法读取元数据，请先验证数据源连接");
    }
  }

  private void seedDemo() throws SQLException {
    try (var c =
            DriverManager.getConnection("jdbc:h2:mem:matedata_sales;DB_CLOSE_DELAY=-1", "sa", "");
        var s = c.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS sales(id INT PRIMARY KEY, sales_month VARCHAR(7),region VARCHAR(20),category VARCHAR(20),channel VARCHAR(20),amount DECIMAL(16,2),profit DECIMAL(16,2))");
      try (var r = s.executeQuery("SELECT COUNT(*) FROM sales")) {
        r.next();
        if (r.getInt(1) > 0) return;
      }
      String[] regions = {"华东", "华南", "华北", "西部"},
          categories = {"软件服务", "智能硬件", "专业咨询"},
          channels = {"直销", "合作伙伴"};
      try (var p = c.prepareStatement("INSERT INTO sales VALUES(?,?,?,?,?,?,?)")) {
        int id = 1;
        for (int month = 1; month <= 6; month++)
          for (int region = 0; region < 4; region++)
            for (int category = 0; category < 3; category++)
              for (int channel = 0; channel < 2; channel++) {
                long amount =
                    18000L
                        + month * 2200L
                        + (4 - region) * 3100L
                        + category * 1700L
                        + channel * 900L;
                p.setInt(1, id++);
                p.setString(2, "2026-0" + month);
                p.setString(3, regions[region]);
                p.setString(4, categories[category]);
                p.setString(5, channels[channel]);
                p.setLong(6, amount);
                p.setBigDecimal(
                    7,
                    java.math.BigDecimal.valueOf(amount)
                        .multiply(new java.math.BigDecimal("0.28")));
                p.addBatch();
              }
        p.executeBatch();
      }
    }
  }
}
