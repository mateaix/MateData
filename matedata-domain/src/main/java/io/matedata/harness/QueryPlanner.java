package io.matedata.harness;
import io.matedata.semantic.*;
public interface QueryPlanner {
    QueryPlan plan(String question,SemanticModel model,String userId,String runId);
}
