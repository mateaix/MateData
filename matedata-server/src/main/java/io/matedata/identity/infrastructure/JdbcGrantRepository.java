package io.matedata.identity.infrastructure;

import io.matedata.identity.*;
import io.matedata.shared.infrastructure.DocumentStore;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGrantRepository implements GrantRepository {
  private final DocumentStore store;

  public JdbcGrantRepository(DocumentStore store) {
    this.store = store;
  }

  public Optional<DatasetGrant> find(String user, String dataset) {
    return store.get("grants", user + "." + dataset, DatasetGrant.class);
  }

  public List<DatasetGrant> all() {
    return store.list("grants", DatasetGrant.class);
  }

  public void save(DatasetGrant grant) {
    store.save("grants", grant.username() + "." + grant.datasetId(), grant);
  }
}
