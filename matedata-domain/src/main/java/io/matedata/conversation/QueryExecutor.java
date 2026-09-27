package io.matedata.conversation;

import io.matedata.semantic.*;
import java.util.*;

public interface QueryExecutor {
  record Result(List<String> columns, List<Map<String, Object>> rows) {}

  Result execute(SemanticModel model, QueryPlan plan, CompiledQuery query) throws Exception;

  /** Runs a compiled dimension-values lookup; at most {@code plan.limit() + 1} rows. */
  Result values(SemanticModel model, ValuesPlan plan, CompiledQuery query) throws Exception;
}
