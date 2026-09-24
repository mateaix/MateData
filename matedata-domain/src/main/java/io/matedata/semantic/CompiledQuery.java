package io.matedata.semantic;

import java.util.List;

public record CompiledQuery(String sql, List<Object> parameters) {
  public CompiledQuery {
    parameters = List.copyOf(parameters);
  }
}
