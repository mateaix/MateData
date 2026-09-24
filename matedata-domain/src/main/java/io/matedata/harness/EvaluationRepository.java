package io.matedata.harness;

import java.util.*;

public interface EvaluationRepository {
  void save(String owner, EvaluationReport report);

  List<EvaluationReport> all(String owner);

  Optional<EvaluationReport> find(String owner, String id);
}
