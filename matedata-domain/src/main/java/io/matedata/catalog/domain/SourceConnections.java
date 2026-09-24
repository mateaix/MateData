package io.matedata.catalog.domain;

import java.util.List;

/** Source connectivity and metadata capability, independent of database drivers. */
public interface SourceConnections {
  boolean test(DataSourceDefinition source);

  List<SourceTable> tables(DataSourceDefinition source);
}
