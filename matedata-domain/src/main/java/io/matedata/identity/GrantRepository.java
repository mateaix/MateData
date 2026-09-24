package io.matedata.identity;

import java.util.*;

public interface GrantRepository {
  Optional<DatasetGrant> find(String user, String dataset);

  List<DatasetGrant> all();

  void save(DatasetGrant grant);
}
