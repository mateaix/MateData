package io.matedata.semantic;

import java.util.*;

public interface ModelRepository {
  Optional<SemanticModel> find(String id);

  List<SemanticModel> all();

  /** Atomically creates a model; returns false if the identifier already exists. */
  boolean createIfAbsent(SemanticModel model);

  void save(SemanticModel model);
}
