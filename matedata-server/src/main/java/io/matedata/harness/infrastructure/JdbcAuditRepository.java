package io.matedata.harness.infrastructure;

import io.matedata.harness.*;
import io.matedata.shared.infrastructure.DocumentStore;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditRepository implements AuditRepository {
  private final DocumentStore store;

  public JdbcAuditRepository(DocumentStore store) {
    this.store = store;
  }

  public void save(AuditEntry entry) {
    store.save("audit", entry.id(), entry);
  }

  public List<AuditEntry> all() {
    return store.list("audit", AuditEntry.class);
  }
}
