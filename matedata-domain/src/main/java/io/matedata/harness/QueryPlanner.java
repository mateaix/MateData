package io.matedata.harness;

import io.matedata.semantic.*;

public interface QueryPlanner {
  QueryPlan plan(String question, SemanticModel model, String userId, String runId);

  default QueryPlan plan(
      String question,
      SemanticModel model,
      String userId,
      String runId,
      java.util.function.Consumer<ExecutionStep> observer) {
    return plan(question, model, userId, runId);
  }
}
