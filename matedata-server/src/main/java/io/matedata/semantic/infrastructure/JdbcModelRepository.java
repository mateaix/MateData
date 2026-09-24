package io.matedata.semantic.infrastructure;

import io.matedata.semantic.*;
import io.matedata.shared.infrastructure.DocumentStore;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcModelRepository implements ModelRepository {
  private final DocumentStore store;

  public JdbcModelRepository(DocumentStore store) {
    this.store = store;
    if (find("sales").isEmpty()) save(SemanticModel.sales());
  }

  public Optional<SemanticModel> find(String id) {
    return store.get("semantic", id, SemanticModel.class);
  }

  public List<SemanticModel> all() {
    return store.list("semantic", SemanticModel.class);
  }

  public void save(SemanticModel model) {
    store.save("semantic", model.id(), model);
  }
}
