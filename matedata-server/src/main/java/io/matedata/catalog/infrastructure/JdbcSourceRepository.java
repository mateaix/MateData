package io.matedata.catalog.infrastructure;

import io.matedata.catalog.domain.*;
import io.matedata.shared.infrastructure.DocumentStore;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSourceRepository implements SourceRepository {
  private final DocumentStore store;

  public JdbcSourceRepository(DocumentStore store) {
    this.store = store;
  }

  public Optional<DataSourceDefinition> find(String id) {
    return store.get("catalog", id, DataSourceDefinition.class);
  }

  public List<DataSourceDefinition> all() {
    return store.list("catalog", DataSourceDefinition.class);
  }

  public void save(DataSourceDefinition source) {
    store.save("catalog", source.id(), source);
  }
}
