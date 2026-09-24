package io.matedata.catalog.domain;

import java.util.List;

public record SourceTable(String name, List<SourceColumn> columns) {
  public SourceTable {
    columns = List.copyOf(columns);
  }
}
