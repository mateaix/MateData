package io.matedata.catalog.application;

import io.matedata.catalog.domain.*;
import io.matedata.shared.ApplicationException;
import io.matedata.shared.ApplicationException.Kind;
import io.matedata.shared.CredentialCipher;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {
  private final SourceRepository repository;
  private final CredentialCipher vault;
  private final SourceConnections connections;

  public CatalogService(
      SourceRepository repository, CredentialCipher vault, SourceConnections connections) {
    this.repository = repository;
    this.vault = vault;
    this.connections = connections;
    if (repository.find("demo_sales").isEmpty())
      repository.save(
          new DataSourceDefinition(
              "demo_sales",
              "销售演示数据库",
              "DEMO",
              "jdbc:h2:mem:matedata_sales;DB_CLOSE_DELAY=-1",
              "sa",
              "",
              "AVAILABLE",
              Instant.now().toString()));
  }

  public List<DataSourceDefinition.View> all() {
    return repository.all().stream().map(DataSourceDefinition::view).toList();
  }

  public DataSourceDefinition require(String id) {
    return repository
        .find(id)
        .orElseThrow(() -> new ApplicationException(Kind.NOT_FOUND, "数据源不存在"));
  }

  public DataSourceDefinition.View create(
      String name, String type, String url, String username, String password) {
    ConnectionPolicy.validate(type, url);
    if (name == null
        || name.isBlank()
        || name.length() > 100
        || username == null
        || username.length() > 100
        || password == null
        || password.length() > 1024) throw new IllegalArgumentException("数据源名称与凭证不合法");
    var source =
        new DataSourceDefinition(
            "s_" + UUID.randomUUID().toString().replace("-", ""),
            name,
            type,
            url,
            username,
            vault.encrypt(password),
            "UNTESTED",
            Instant.now().toString());
    repository.save(source);
    return source.view();
  }

  public Map<String, Object> test(String id) {
    var source = require(id);
    boolean ok = connections.test(source);
    repository.save(
        new DataSourceDefinition(
            source.id(),
            source.name(),
            source.type(),
            source.jdbcUrl(),
            source.username(),
            source.encryptedPassword(),
            ok ? "AVAILABLE" : "ERROR",
            source.createdAt()));
    return Map.of("success", ok, "message", ok ? "连接成功" : "连接失败，请检查地址、只读账号与网络配置");
  }

  public List<SourceTable> tables(String id) {
    return connections.tables(require(id));
  }
}
