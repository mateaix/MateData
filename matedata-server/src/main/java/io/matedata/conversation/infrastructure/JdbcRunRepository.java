package io.matedata.conversation.infrastructure;

import io.matedata.conversation.*;
import io.matedata.shared.infrastructure.DocumentStore;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRunRepository implements RunRepository {
  private final DocumentStore store;

  public JdbcRunRepository(DocumentStore store) {
    this.store = store;
  }

  @org.springframework.transaction.annotation.Transactional
  public void save(String owner, QueryRun run) {
    store.save("runs_" + owner, run.id(), run);
    store.save("run_summaries_" + owner, run.id(), run.summary());
  }

  public Optional<QueryRun> find(String owner, String id) {
    return store.get("runs_" + owner, id, QueryRun.class);
  }

  public List<QueryRun> all(String owner) {
    return page(owner, 0, 50);
  }

  public List<QueryRun> page(String owner, int offset, int limit) {
    return store.page("run_summaries_" + owner, QueryRun.class, offset, limit);
  }
}
