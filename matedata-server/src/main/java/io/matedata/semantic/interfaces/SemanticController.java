package io.matedata.semantic.interfaces;

import io.matedata.harness.application.AuditService;
import io.matedata.identity.application.DataAccessService;
import io.matedata.identity.interfaces.Access;
import io.matedata.semantic.*;
import io.matedata.semantic.application.SemanticService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/datasets")
public class SemanticController {
  private final SemanticService service;
  private final AuditService audit;
  private final DataAccessService access;

  public SemanticController(SemanticService service, AuditService audit, DataAccessService access) {
    this.service = service;
    this.audit = audit;
    this.access = access;
  }

  @GetMapping
  public List<SemanticModel> list(HttpServletRequest request) {
    return access.visible(Access.user(request).username());
  }

  @PostMapping
  public SemanticModel create(@RequestBody SemanticModel model, HttpServletRequest request) {
    var u = Access.admin(request);
    service.create(model);
    audit.record(u.username(), "DATASET_CREATE", model.id());
    return model;
  }

  @PutMapping("/{id}")
  public SemanticModel update(
      @PathVariable String id, @RequestBody SemanticModel model, HttpServletRequest request) {
    var u = Access.admin(request);
    service.update(id, model);
    audit.record(u.username(), "DATASET_UPDATE", id);
    return model;
  }
}
