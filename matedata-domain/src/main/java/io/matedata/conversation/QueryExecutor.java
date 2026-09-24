package io.matedata.conversation;

import io.matedata.semantic.*;
import java.util.*;

public interface QueryExecutor {
  record Result(List<String> columns, List<Map<String, Object>> rows) {}

  Result execute(SemanticModel model, QueryPlan plan, CompiledQuery query) throws Exception;
}
