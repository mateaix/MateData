package io.matedata.shared.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/** Durable adapter shared by domain repositories. Namespaces prevent cross-domain coupling. */
@Repository
public class DocumentStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    public DocumentStore(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        jdbc.execute("CREATE TABLE IF NOT EXISTS md_document (namespace VARCHAR(64) NOT NULL, id VARCHAR(128) NOT NULL, payload CLOB NOT NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, PRIMARY KEY(namespace,id))");
    }
    public <T> Optional<T> get(String namespace,String id,Class<T> type) {
        return jdbc.query("SELECT payload FROM md_document WHERE namespace=? AND id=?",(rs,n)->read(rs.getString(1),type),namespace,id).stream().findFirst();
    }
    public <T> List<T> list(String namespace,Class<T> type) {
        return jdbc.query("SELECT payload FROM md_document WHERE namespace=? ORDER BY updated_at DESC,id",(rs,n)->read(rs.getString(1),type),namespace);
    }
    public <T> void save(String namespace,String id,T value) {
        jdbc.update("MERGE INTO md_document(namespace,id,payload,updated_at) KEY(namespace,id) VALUES(?,?,?,CURRENT_TIMESTAMP)",namespace,id,write(value));
    }
    public void delete(String namespace,String id) { jdbc.update("DELETE FROM md_document WHERE namespace=? AND id=?",namespace,id); }
    public String write(Object value) { try {return json.writeValueAsString(value);} catch(Exception e){throw new IllegalStateException("无法序列化数据",e);} }
    public <T> T read(String value,Class<T> type) {try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("无法读取持久化数据",e);} }
}
