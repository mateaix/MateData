package io.matedata.catalog.domain;

import java.util.List;
import java.util.Optional;

public interface SourceRepository {
  Optional<DataSourceDefinition> find(String id);

  List<DataSourceDefinition> all();

  void save(DataSourceDefinition source);
}
