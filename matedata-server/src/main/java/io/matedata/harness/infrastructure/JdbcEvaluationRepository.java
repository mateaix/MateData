package io.matedata.harness.infrastructure;

import io.matedata.harness.*;
import io.matedata.shared.infrastructure.DocumentStore;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEvaluationRepository implements EvaluationRepository {
  private final DocumentStore store;

  public JdbcEvaluationRepository(DocumentStore store) {
    this.store = store;
  }

  public void save(String owner, EvaluationReport report) {
    store.save("evaluations_" + owner, report.id(), report);
  }

  public List<EvaluationReport> all(String owner) {
    return store.list("evaluations_" + owner, EvaluationReport.class);
  }

  public Optional<EvaluationReport> find(String owner, String id) {
    return store.get("evaluations_" + owner, id, EvaluationReport.class);
  }
}
