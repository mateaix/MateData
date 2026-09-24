package io.matedata.harness.application;

import io.matedata.harness.AuditEntry;
import io.matedata.harness.AuditRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final AuditRepository repository;

  public AuditService(AuditRepository repository) {
    this.repository = repository;
  }

  public void record(String user, String action, String resource) {
    String id = UUID.randomUUID().toString();
    repository.save(new AuditEntry(id, user, action, resource, Instant.now().toString()));
  }

  public List<AuditEntry> all() {
    return repository.all();
  }
}
