package io.matedata.catalog.domain;

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
  }
}
