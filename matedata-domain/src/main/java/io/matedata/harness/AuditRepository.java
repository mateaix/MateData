package io.matedata.harness;

import java.util.List;

public interface AuditRepository {
  void save(AuditEntry entry);

  List<AuditEntry> all();
}
