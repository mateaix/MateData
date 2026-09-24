package io.matedata.shared.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable adapter shared by domain repositories. Namespaces prevent cross-domain coupling. */
@Repository
public class DocumentStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  public DocumentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS md_document (namespace VARCHAR(64) NOT NULL, id VARCHAR(128) NOT NULL, payload CLOB NOT NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, PRIMARY KEY(namespace,id))");
  }

  public <T> Optional<T> get(String namespace, String id, Class<T> type) {
    return jdbc
        .query(
            "SELECT payload FROM md_document WHERE namespace=? AND id=?",
            (rs, n) -> read(rs.getString(1), type),
            namespace,
            id)
        .stream()
        .findFirst();
  }

  public <T> List<T> list(String namespace, Class<T> type) {
    return page(namespace, type, 0, 1000);
  }

  public <T> List<T> page(String namespace, Class<T> type, int offset, int limit) {
    if (offset < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("分页参数无效");
    return jdbc.query(
        "SELECT payload FROM md_document WHERE namespace=? ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",
        (rs, n) -> read(rs.getString(1), type),
        namespace,
        limit,
        offset);
  }

  public <T> void save(String namespace, String id, T value) {
    jdbc.update(
        "MERGE INTO md_document(namespace,id,payload,updated_at) KEY(namespace,id) VALUES(?,?,?,CURRENT_TIMESTAMP)",
        namespace,
        id,
        write(value));
  }

  /** Inserts a new document without changing an existing document with the same key. */
  public <T> boolean insertIfAbsent(String namespace, String id, T value) {
    try {
      jdbc.update(
          "INSERT INTO md_document(namespace,id,payload,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",
          namespace,
          id,
          write(value));
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  public void delete(String namespace, String id) {
    jdbc.update("DELETE FROM md_document WHERE namespace=? AND id=?", namespace, id);
  }

  public String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("无法序列化数据", e);
    }
  }

  public <T> T read(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException("无法读取持久化数据", e);
    }
  }
}
