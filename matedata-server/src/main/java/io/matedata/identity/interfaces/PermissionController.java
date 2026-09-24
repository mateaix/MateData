package io.matedata.identity.interfaces;

import io.matedata.harness.application.AuditService;
import io.matedata.identity.DatasetGrant;
import io.matedata.identity.application.DataAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionController {
  private final DataAccessService access;
  private final AuditService audit;

  public PermissionController(DataAccessService access, AuditService audit) {
    this.access = access;
    this.audit = audit;
  }

  public record Grant(
      boolean enabled,
      List<String> metrics,
      List<String> dimensions,
      Map<String, String> rowFilters) {}

  @GetMapping
  public List<DatasetGrant> all(HttpServletRequest request) {
    Access.admin(request);
    return access.all();
  }

  @PutMapping("/{username}/{dataset}")
  public DatasetGrant save(
      @PathVariable String username,
      @PathVariable String dataset,
      @RequestBody Grant body,
      HttpServletRequest request) {
    var admin = Access.admin(request);
    var grant =
        access.save(
            new DatasetGrant(
                username,
                dataset,
                body.enabled(),
                body.metrics(),
                body.dimensions(),
                body.rowFilters()));
    audit.record(admin.username(), "DATASET_GRANT", username + ":" + dataset);
    return grant;
  }
}
