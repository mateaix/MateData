package io.matedata.catalog.domain;

import java.util.Locale;
import java.util.Set;

public final class ConnectionPolicy {
  private ConnectionPolicy() {}

  public static void validate(String type, String url) {
    String scheme =
        switch (type == null ? "" : type) {
          case "POSTGRESQL" -> "postgresql";
          case "MYSQL" -> "mysql";
          default -> throw new IllegalArgumentException("仅支持 PostgreSQL / MySQL 外部数据源");
        };
    if (url == null
        || !url.matches("jdbc:" + scheme + "://[a-zA-Z0-9.-]+(:[0-9]{1,5})?/[a-zA-Z0-9_-]+"))
      throw new IllegalArgumentException(
          "连接地址需为 jdbc:" + scheme + "://host:port/database；不接受驱动参数或脚本");
    if (type.equals("MYSQL") && isMysqlSystemCatalog(url.substring(url.lastIndexOf('/') + 1)))
      throw new IllegalArgumentException("不允许连接 MySQL 系统数据库，请选择业务数据库");
  }

  public static void validateNamespace(String type, String catalog, String schema) {
    if (type.equals("MYSQL")
        && (catalog == null || catalog.isBlank() || isMysqlSystemCatalog(catalog)))
      throw new IllegalArgumentException("请连接明确的业务数据库，不允许访问系统对象");
    if (type.equals("POSTGRESQL")
        && (schema == null
            || schema.isBlank()
            || schema.toLowerCase(Locale.ROOT).startsWith("pg_")
            || schema.equalsIgnoreCase("information_schema")))
      throw new IllegalArgumentException("请设置明确的业务 schema，不允许访问系统对象");
  }

  private static boolean isMysqlSystemCatalog(String catalog) {
    return Set.of("mysql", "information_schema", "performance_schema", "sys")
        .contains(catalog.toLowerCase(Locale.ROOT));
  }
}
